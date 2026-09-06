# AGENTS.md

## Project
GodViewer (上帝视角) — an **Xposed / LSPosed** runtime view-debugging tool for Android.
It injects only into **LSPosed-scoped target apps** so the user can touch-select a View, edit
attributes (size, margin, padding, visibility, TextView text, ImageView URL/scaleType) live,
and persist JSON rules in the **target app's own data dir**
(`/data/data/<target>/files/godviewer/rules.json`) that auto-replay after restart.
Package/namespace: `com.godviewer.app`. License: GPL-3.0. Verified on Android 16 + LSPosed.

User preference: communicate in **中文** unless they write in English.

## Build
- **Windows shell is Git Bash**: run `./gradlew` (or `gradlew.bat`).
- Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- Prefer `assembleDebug` after feature work. **Do not commit unless the user asks.**
- Requirements: **JDK 17**, Android SDK **platform 35**. `local.properties` (`sdk.dir`) is
  gitignored and must exist locally (this machine often uses `tools-Android/`).
- Stack: Gradle 8.7 wrapper, AGP 8.5.2, Kotlin 1.9.24 (jvmTarget 1.8), minSdk 23 / targetSdk 35.
- UI is **Views + XML + ViewBinding** (`viewBinding = true`), **not Compose**. No flavors, no CI,
  no DI framework, no coroutines, no Room.
- No real unit/instrumented tests; verify on device with LSPosed + logcat (`GvLog` / `GodViewer.*`).

## Architecture (single `:app` module)
Source root: `app/src/main/java/com/godviewer/app/`

Logical packages (phase-2):

| Package | Role |
|---------|------|
| `host/` | Host app process only: `GodViewerApp`, `MainActivity`, mirror Activities, `host/ui/*` fragments, `host/mirror` store, `host/control` notifier + `*Impl` receivers, prefs/entry UI helpers |
| `target/` | Injected into LSPosed-scoped apps: `target/hook` (entry `GodViewerModule`, META-INF/xposed), `target/rule`, `target/edit`, `target/ui` dialogs, `target/handler`, `target/dialog`, `target/dispatch`, `target/glide`, `target/mirror` push |
| `shared/` | Both sides, no Dialog/Activity/Hooker: `shared/model` (`ViewRule`…), `shared/mirror` protocol/DTO/codec, `shared/control` bridge, `shared/entry` mode keys, `GvLog`/`Hash`/`VeiwUtil`/`ViewSnapshot`/constants |
| `data/` | **Protocol-frozen FQCNs only**: thin stubs `RuleMirrorReceiver` / `HostControlReceiver` / `HostPrefsProvider` (subclass host `*Impl`) + `RuleMirror` facade API |
| `util/` | **Protocol-frozen**: `ModuleStatus` stays at `com.godviewer.app.util.ModuleStatus` (Xposed string + reflection) |
| root | `LegacyExports.kt` re-exports `IGNORE_HOOK` for historical imports |

Layers (conceptual): `target.hook` → `target.rule` / `shared` → `target.ui`/`handler` → host UI.
No MVVM; singletons are Kotlin `object`s. Large types keep **thin facades**
(`RuleMirror`, `ViewRuleManager`, `ModuleDialogUi`).

**Authority vs mirror:** rules live only under the target’s private dir. On save, the target
best-effort broadcasts a copy to the host (`filesDir/godviewer/mirror/<pkg>/`). Host cannot
read target private data directly.

### Boundary roadmap (optimization — no new features)
Decisions locked (grill-me): boundary/deps cleanup; internal refactor OK, **external protocols
frozen** (Manifest components, broadcast/Provider tokens/actions, `rules.json` schema);
logical packages `host` / `target` / `shared` in one Gradle module; practical shared (no
Dialog/Activity/Hooker).

| Phase | Scope | Status |
|-------|--------|--------|
| 1 | Same-package responsibility split of large classes (Mirror → Manager → BaseAttr → ModuleDialogUi); thin facades; GvLog on touched paths | **done** |
| 2 | Move into `com.godviewer.app.host` / `.target` / `.shared`; relocate root Activities & util by role; freeze protocol FQCNs via `data/*` stubs + `util.ModuleStatus` | **done** |
| 3 | Boundary hygiene: no `shared→host/target`, no `target→host`; `HostPrefsNames` shared keys; `EntryMode` self-contained + host `EntryControlUi.setEntryMode`; `HiddenEntryNotifier` on host only | **done** (assembleDebug OK) |
| 4 | Optional: drop `data.RuleMirror` facade / more call-site cleanup once stable on device | pending |

**Frozen FQCNs / strings (do not rename without compatibility):**
- Manifest: `.data.RuleMirrorReceiver`, `.data.HostControlReceiver`, `.data.HostPrefsProvider`
- Authority `com.godviewer.app.hostprefs`; tokens/actions unchanged
- Explicit broadcast class names in `RuleMirrorProtocol.RECEIVER_CLASS` /
  `HostControlBridge.HOST_RECEIVER` → still `com.godviewer.app.data.*`
- `ModuleStatus` class name string `com.godviewer.app.util.ModuleStatus`
- libxposed entry: `META-INF/xposed/java_init.list` → `com.godviewer.app.target.hook.GodViewerModule`
  (replaces the old `assets/xposed_init` + `AnyHookPackage` / `AnyHookZygote`, removed in the
  API 102 migration)

Backup before large moves: desktop `GodViewer-backup-pre-optimize-*` +
`git stash` `backup-pre-optimize-*`.

## Critical gotchas
- **libxposed API 102 (2026-09 migration)**: module identity lives in
  `app/src/main/resources/META-INF/xposed/` (`java_init.list`, `module.prop` with
  `minApiVersion=101/targetApiVersion=102/staticScope=false/exceptionMode=protective`,
  empty `scope.list` — user picks targets in LSPosed). Manifest has **no** legacy
  `xposedmodule`/`xposedminversion` metadata; module description = `android:description`.
- **No legacy `de.robv.android.xposed.*` API**: with `targetApiVersion=102` LSPosed does not
  provide the legacy bridge. Entry is `GodViewerModule : XposedModule()` (public no-arg ctor,
  `onModuleLoaded` + `onPackageReady`); hooking goes through the **transitional in-project
  shim** `target/hook/GvHook.kt` (`GvMethodHook` / `MethodHookParam` over
  `hook(method).intercept { chain -> … }`). Migration notes live in `HANDOFF.md`.
- **Injected UI** must use `ModuleRes.moduleRes` (created in `onModuleLoaded` from
  `getModuleApplicationInfo()`, no `XModuleResources`) via `target.dialog.ModuleDialogUi`
  for layouts/strings — **not** the host app’s `R` / target `Context.getResources()` alone.
  Tag module UI with `IGNORE_HOOK` (`GODVIEWER_IGNORE_HOOK`) or edit mode intercepts itself.
- Host UI (`host.MainActivity`, mirror activities, `host.ui/`) uses normal `R` + ViewBinding.
- All user-visible strings: keep **`res/values/strings.xml`** (EN) and
  **`res/values-zh-rCN/strings.xml`** (zh) in parallel.
- Do not run business hooks on self: `GodViewerModule` checks `BuildConfig.PACKAGE_NAME`
  (**not** `APPLICATION_ID`) and only hooks `ModuleStatus.isActivated` → true. **Activation
  chip = OR of 3 signals** (`ModuleStatus.check()`): ① `libxposed/service` bound
  (`host/service/LspService.kt` — LSPosed only delivers the manager binder to *enabled*
  modules; primary signal, no self-scope needed), ② injected-self flag set via reflection
  (`markInjectedActivated`), ③ the self-hook. Without any of them the chip may stay
  “inactive” — e.g. LSPosed forks without XposedService support and no self-scope.
- Touch/click/Popup edit hooks are **lazy** — installed when edit mode enables
  (`target.edit.EditModeTouchInterceptor`), not always-on at package-ready time.
- libxposed **API 102** is a Maven `compileOnly` dep (`io.github.libxposed:api:102.0.0`);
  the old `app/libs/api-82.jar` is no longer on the classpath (kept on disk only).
- `proguard-rules.pro` keeps broadly `com.godviewer.app.**` plus the official libxposed
  rules (`-adaptresourcefilecontents META-INF/xposed/java_init.list` + entry-class keep).
  New entry classes must be listed in `META-INF/xposed/java_init.list`.
- Persistence uses version-sensitive reflection (`ReflectUtil`, `ListenerInfo`,
  `View.mAttachInfo.mDebugLayout`, etc.) — stay API-safe. Corrupt `rules.json` → empty list;
  **never crash the target app**. ImageView original URL is not fully recoverable on reset.
- Rule match: activityClass + hierarchy `depth[]` + viewClass, with resourceName/text fallbacks,
  guarded by `matchVersionCode`.
- Rule mirror is **broadcast-only** best-effort. Do **not** re-add Service/ContentProvider cold-start
  delivery unless the user explicitly asks (already tried and reverted). Soft token:
  `godviewer-rule-mirror-v1`.
- Host entry/control: `EntryMode` (`target` vs `host`), `HostPrefs` + `HostPrefsProvider`
  (`content://com.godviewer.app.hostprefs/...`), control token `godviewer-host-control-v1`
  (soft guard, not crypto).
- Do **not** restore deleted AppList* UI (`AppListActivity` / adapters / layouts).
- Keep load-bearing misspellings: `PupupWindowHooker.kt`, `VeiwUtil.kt`.
- Logging: prefer `GvLog` (`GodViewer.<tag>`, forwarded to the framework log via the sink
  injected by `GodViewerModule`; host process falls back to logcat only).

## Conventions
- Kotlin only, official style, defensive null-safe (`?: return`, `runCatching`).
- Comments mixed English (older, `@author hhvvg`) and Chinese (newer `data/` / `util/`) — match nearby.
- Host layouts: `activity_*` / `fragment_*`. Injected dialogs: `layout_*`, inflated from `moduleRes`.
- Leave local/agent-only trees alone unless asked: `.zcode/`, `tools-Android/`, `HANDOFF.md`, `a.py`.

## Before changing sensitive areas
1. `README.md` — features, persistence design/limits, Android 16 + LSPosed checklist.
2. `HANDOFF.md` — session constraints, reverted experiments, “don’t restore X” notes.
