# GodViewer

English | [中文](README.md)

- A runtime view debugging tool with a "God's eye view", built on **Xposed / LSPosed**.
- The module has been fully migrated to **libxposed API 102** (modern `META-INF/xposed` entry points, no legacy XposedBridge API); verified on Android 16 + LSPosed 2.1.0, **regression in progress**
- No app restart, no system-wide scope — just **tap and restyle any UI in the target app exactly how you want it**, and it **persists**.
- **Telegram discussion group**: [t.me/GodViewer](https://t.me/GodViewer) — join us for feedback, questions and suggestions

## What it can do

- **Tap to edit**: pick any view by touching the screen, smoother than F12
- **Any attribute**: size / margin / padding / visibility, `TextView` text & max lines, `ImageView` image URL & scale type
- **Hide & reset**: hide a view with one tap; restore modified views to original anytime
- **Edits survive restarts**: changes are saved as rules and automatically replayed after the target app is restarted, killed, or the device reboots
- **Host-notification entry**: the edit-mode entry is now unified as the **GodViewer host notification**, shown only after you enable the switch on the home page; tap **Enable / Undo / Manage rules** on the notification to control it
- **Clear all rules of one app**: from a rule page, the top-right menu can delete every rule of that app

## How to use

1. Select the target app in **LSPosed** and reboot it
2. Open **GodViewer** and **turn on the "host notification" switch** on the home page (notification permission will be requested the first time)
3. Open the target app, pull down the notification shade and tap **Enable** on the GodViewer notification
4. Tap the control you want to edit → change it → **Apply**, done — or just **Hide** it
5. When finished, tap **Exit edit mode** in the dialog to go back to normal use

## How it works

The module is injected into the target app's process; intercepted taps open an editor dialog. Changes are stored as JSON rules in the **target app's own data directory** and replayed automatically after restart. It never injects system services and needs no system-level permissions — it only lives inside the apps you select.

## 🛠 Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (requires JDK 17 + Android SDK platform 35)

## License

[GPL-3.0](LICENSE)

---

> This software is completely free and open source, for learning and research only. Please build it yourself from this repository and avoid APKs from unknown sources.

## Known limitations (a.k.a. bugs)
- 1. Rule management (~~hidden views could not be restored~~ ✅ fixed in v2.2.0: undo the last operation + a rule manager dialog to view/delete any rule; deleted rules restore the view immediately)
- 2.~~Edit buttons pushed off-screen by very large parent views~~ (✅ fixed in v2.2.1: preview capped at 200dp so big views no longer push buttons out of reach)
- 3. Some apps with strict layouts cannot be hooked yet (known cases: Meituan, NetEase Cloud Music — feel free to open an issue)
- 4.~~Entry could not open in apps that don't request notification permission~~ (✅ host-notification entry since v3.3.2; now **unified as the host notification**: target apps need no notification permission, the home-page switch controls visibility)
- 5.~~Dialog style depended on the target app's theme and could break~~ (✅ since v3.3.2 injected dialogs are isolated from the target theme and match the host UI)
- 6. Deleting all rules of an app requires that app's process to be running (the authoritative rules live in its private data directory); otherwise the host asks you to open the app first

## To Do
- 1.~~Rewrite the GodViewer app UI~~ ✅ new UI in v3.2.1: bottom navigation (Home / Guide / Rules / Settings), rule mirror, language switch, hide desktop icon, etc.
- 2.~~Host-notification entry + interception stability + injected dialog theme isolation~~ ✅ v3.3.2
- 3.~~Selectable entry: target-app notification (default) / host notification~~ ✅ unified as the host notification, controlled by the home-page switch
- 4.~~Rule management: delete a single / batch / all rules of an app~~ ✅ both the host rule page and the in-app rule dialog support one-tap deletion
- 5. Maybe try a rootless framework (live editing without a framework is already possible, but it cannot persist)

## Donations
Donations make the developer update faster and with more enthusiasm — and they help pay the developer's AI bills!