# AGENTS.md

## Project
GodViewer (上帝视角) — an **Xposed / LSPosed** runtime view-debugging tool for Android.
It injects into a *target app* so the user can touch-select a View, edit attributes (size,
margin, padding, visibility, TextView text, ImageView URL/scaleType) live, and persist the
changes as JSON rules in the **target app's own data dir**
(`/data/data/<target>/files/godviewer/rules.json`) that auto-replay after the app restarts.
Package/namespace: `com.godviewer.app`. License: GPL-3.0.

## Build
- **Windows shell is Git Bash**: run `./gradlew` (or `gradlew.bat`).
- Build APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Requirements: **JDK 17**, Android SDK **platform 35**. `local.properties` (sdk.dir) is gitignored and absent — must be provided locally.
- Stack: Gradle 8.7 wrapper, AGP 8.5.2, Kotlin 1.9.24 (jvmTarget 1.8), minSdk 23 / targetSdk 35.
- UI is **Views + XML + ViewBinding** (`viewBinding = true`), **not Compose**. No flavors, no CI, no DI framework, no coroutines, no Room.

## Architecture (single `:app` module, source under `app/src/main/java/com/godviewer/app/`)
- `hook/` — Xposed entry points (`AnyHookPackage`, `AnyHookZygote`) + hookers; registered in `app/src/main/assets/xposed_init`.
- `data/` — persistence core: `ViewRule` (Gson model), `RuleStore` (async atomic write: tmp file + rename), `ViewRuleManager` (singleton `object`).
- `handler/` + `ui/` — per-view-type edit dialogs (`textview/`, `imageview/`); `ui/BaseAttrDialog.kt` is the core dialog.
- `util/`, `glide/` — helpers; Glide custom `View → Bitmap` loader (generated `GlideApp`).

Layers: `hook` → `data` → `handler`/`ui` → `util`/`glide`. No MVVM; singletons are Kotlin `object`s (`by lazy(LazyThreadSafetyMode.SYNCHRONIZED)`).

## Critical gotchas
- **UI shown inside the injected target process must use `AnyHookZygote.Companion.moduleRes` (XModuleResources) for strings/layouts — NOT the app's own `R` resources.**
- All user-visible strings exist in **both** `res/values/strings.xml` (English) and `res/values-zh-rCN/strings.xml` (Chinese) — keep them parallel.
- The module must not hook itself: `AnyHookPackage.handleLoadPackage` checks `BuildConfig.PACKAGE_NAME`.
- XposedBridge API 82 is a local jar (`app/libs/api-82.jar`), `compileOnly` dependency.
- `proguard-rules.pro` keeps hook entry classes + `com.godviewer.app.data.**` (Gson reflection) — new classes in `data/` or new Xposed entry points must be added there.
- Persistence uses version-sensitive reflection (`XposedHelpers` into `ListenerInfo`, `View.mAttachInfo.mDebugLayout`, etc.) — changes must stay API-safe across Android versions. Corrupt rules file is treated as "no rules" and must never crash the target app.
- Rule matching relies on view-hierarchy depth paths with resourceName/text fallbacks, guarded by `matchVersionCode`.
- Keep existing (load-bearing) identifier spellings: `PupupWindowHooker.kt`, `VeiwUtil.kt`.
- Logging: `android.util.Log.d` with namespaced tags (`GodViewer.Rule`, etc.).

## Conventions
- Kotlin only, official style, defensive null-safe (`?: return`, `runCatching`).
- Comments are mixed English (older files, `@author hhvvg`) and Chinese (newer files like `data/`). Either is fine; match nearby code.
- UI layouts named `activity_*` / `layout_*`; dialogs inflated from `moduleRes`.

## Before changing sensitive areas
Read `README.md` first (features, persistence design/limitations, Android 16 + LSPosed verification checklist) and `docs/` (screenshots only).
