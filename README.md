# Conduit — your Android phone, on your Mac

A LinkMyMac-style link between a Mac and an Android phone, plus extras. Two native apps:

- **`macos/`** – SwiftUI menu bar app (macOS 14+) with a CoreMediaIO camera extension
- **`android/`** – Kotlin + Jetpack Compose app (Android 12+, tuned for the Galaxy Z Fold 7)

Everything runs over your local Wi-Fi. There are no accounts and no cloud. Each connection is
end-to-end encrypted (AES-256-GCM) with a key exchanged by QR code. See `PROTOCOL.md`.

## Features

| | Feature | How |
|---|---|---|
| 🔔 | **Notifications on the Mac** | Mirrored into Notification Center. Reply inline, use action buttons ("Mark as read"), and dismissing on either side clears both. Mute apps per-app in Settings. Clicking one opens the phone's screen. Search the session's history and filter by app. |
| 📋 | **Clipboard sync** | Text and images. Mac → phone is automatic. Phone → Mac uses the **Send clipboard** Quick Settings tile, the notification button or Share. Password-manager copies are skipped. The menu bar keeps a history of the last 20 items. |
| 📁 | **File transfer** | Drop files on the menu bar panel, the Files tab or the Dock icon. On the phone: Share → **Send to Mac**. Files land in `~/Downloads/Conduit` / `Download/Conduit`. |
| 💬 | **SMS** | Read threads, send texts, and get new messages live in a Messages window, with contact photos. Reading a thread on the Mac clears its notification on the phone. |
| 🖥️ | **Screen mirroring + control** | Hardware H.264 at up to 60 fps. Click = tap, drag = swipe, hold = long-press, scroll = swipe, right-click = Back, type to enter text, ⌘V pastes. Back/Home/Recents/Notifications/Screenshot/Lock buttons. The window follows fold/unfold and rotation. |
| 📷 | **Phone as webcam** | "Conduit Camera" appears in FaceTime, Zoom, Meet, Teams and OBS. Back or front lens, 1080p30. |
| ➕ | **Calls** | Incoming-call alert on the Mac with Answer/Decline, and you can place calls from Messages. Call audio stays on the phone or its headset. |
| ➕ | **Handoff** | Share a link on the phone → it opens on the Mac. **Send Tab** sends the current Safari/Chrome/Arc/Edge/Brave tab to the phone. |
| ➕ | **Media** | What's playing on the phone (any app) shows in the menu bar, with play/pause/next/previous. |
| ➕ | **Phone controls the Mac** | Trackpad (move, tap, two-finger tap to right-click, two-finger scroll, hold-then-move to drag), keyboard, arrows, ⌘Tab, Spotlight, Lock, Mission Control, media and volume keys, and a slide clicker. |
| ➕ | **Find my phone / battery** | Ring the phone at full volume, even on silent. The phone's battery shows in the menu bar and the Mac's battery shows on the phone. |

**Also:** missed-call alerts, Send to Phone in the Mac Services menu, global shortcuts (⌥⌘M/T/F/U), saving mirror
screenshots, low-battery alerts both ways, Quick Settings tiles, and in-app updates for both apps. See `CHANGELOG.md`
and `ROADMAP.md`.

## Build the Android app

1. Open `android/` in Android Studio and let Gradle sync. If Studio offers to upgrade AGP/Gradle, accept.
2. Run it on your phone (USB or wireless debugging).
3. Go through the **Setup** checklist in the app. Two items need extra steps:
   - **Remote control** (accessibility). On sideloaded apps Android greys this out at first. Go to
     *Settings → Apps → Conduit → ⋮ → Allow restricted settings*, then turn it on.
   - **Notification access**. Some phones need the same "Allow restricted settings" step first.

## Build the Mac app

```bash
brew install xcodegen
cd macos
xcodegen            # creates Conduit.xcodeproj from project.yml
open Conduit.xcodeproj
```

1. Choose your team under **Signing & Capabilities** for both targets, *Conduit* and *ConduitCamera*.
   The camera's **System Extension** capability needs a paid Apple Developer membership. Everything
   else works without one.
2. Build and run. Conduit appears in the menu bar.
3. **Pair Phone…** → scan the QR code with the Android app.
4. For the webcam: copy the built `Conduit.app` into **/Applications**, run it from there, then go to
   **Settings → Camera → Install Conduit Camera** and approve it in System Settings.
5. For trackpad/keyboard control from the phone: grant **Accessibility** when prompted (Settings → General).

If `xcodegen` complains about the `system-extension` target type, update XcodeGen.
Alternatively, create the project in Xcode yourself: a macOS App target plus a *Camera Extension* target, with the
files from `Conduit/` and `ConduitCamera/` and the plists/entitlements already provided.

## Security warnings (Play Protect and Gatekeeper)

**Android, "App scan recommended":** Play Protect shows this for apps it hasn't seen that come from outside the
Play Store. Tap **Scan app**. To stop it for good, register the app with Google's free Android developer verification:
1. Create an account at the Android Developer Console (a limited distribution account is for personal use, up to 20 devices).
2. Register package `dev.conduit` with the signing-key SHA-256
   `DB:A4:2D:3C:4F:1C:F7:85:8A:F3:00:41:D5:F4:AB:9F:B3:CF:99:31:DE:C9:50:7C:6B:7D:54:81:43:52:37:28`.
3. If the console asks you to upload an APK with a snippet, save the snippet as the repo secret `ANDROID_ADI_SNIPPET`.
   The next build includes it as `assets/adi-registration.properties`. Upload that build's `Conduit.apk`.

**Mac, "Apple could not verify…":** add these secrets to sign with Developer ID and notarize (needs the Apple Developer Program):
`MAC_DEVID_P12_B64` + `MAC_DEVID_PASSWORD` (a *Developer ID Application* certificate exported as .p12, base64), and
`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_B64` (an App Store Connect API key with the Developer role; the .p8 file, base64).
Without them, the Mac build uses a self-signed certificate, so only the very first install shows the warning:
in-app updates never do.

## Things Android or macOS won't allow (and what Conduit does instead)

- **Screen sharing needs a tap on the phone every session.** This is an Android security rule.
  When the Mac asks, the consent dialog pops up on the phone (or as a notification if remote control is off).
- **Reading the phone's clipboard in the background** is blocked since Android 10, so phone → Mac clipboard is one tap.
- **RCS chats** in Google Messages aren't in the SMS database. They still arrive as notifications you can reply to from the Mac.
- **Call audio** can't be routed to a Mac, because macOS has no hands-free profile. Conduit only handles the call controls.
- **Google Play** restricts SMS/call-log permissions, so this build is meant for sideloading. It's your own phone.

## Not yet verified

This code was written without access to Xcode or the Android SDK, so neither app has been compiled
or run yet. Expect a few compile errors on the first build, most likely around exact API
signatures in the CoreMediaIO camera extension or Compose versions. The protocol, features and
structure are complete; paste any build errors back and they're quick to fix.

## Project layout

```
PROTOCOL.md                 wire format shared by both apps
android/app/src/main/java/dev/conduit/
  crypto/SecureChannel.kt   handshake, HKDF, AES-GCM framing
  net/                      pairing store, connections, discovery + reconnect, message routing
  service/                  foreground link service, boot + action receivers
  feature/                  notifications, media, clipboard, SMS, calls, files, ring,
                            mirroring, camera, H.264 encoder, accessibility remote control
  ui/                       Compose screens (pairing, setup, trackpad, keys) + helper activities
macos/
  project.yml               XcodeGen spec
  Conduit/Core/             crypto, connections, Bonjour listener, pairing/Keychain, app model
  Conduit/Features/         notifications, clipboard, files, H.264 decoder, mirror window,
                            camera bridge, input injection, handoff, battery
  Conduit/Views/            menu bar panel, Messages/Notifications/Files, pairing, settings
  ConduitCamera/            CoreMediaIO camera system extension
```
