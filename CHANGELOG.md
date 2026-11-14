# Changelog

Newest first. Each release's build number is the commit count on `main`.

## Week of 2026-11-14
- **Contact photos in Messages** on the Mac, with coloured initials for contacts without a photo.
- **Read on the Mac, cleared on the phone:** opening a conversation on the Mac clears its notification on the phone, and the unread dot no longer comes back after a refresh. Right-click a thread for **Mark as Read**.
- **Clipboard history:** the last 20 things copied on the Mac or sent from the phone, in the menu bar panel. Click one to copy it again, or send it to the phone. Kept in memory only; turn it off in Settings.
- **Notification history:** an **On Phone / History** switch, search, and a per-app filter in the Notifications view.
- **Fix:** notifications no longer pop up a second time on the Mac every time the phone reconnects.

## Fewer security warnings
- **Android:** before an update, a one-time explanation of Google Play Protect's "App scan recommended" prompt (tap **Scan app**).
  Builds can now include Google's developer-verification snippet, so Play Protect recognises Conduit once it's registered.
- **Mac:** builds are signed with Developer ID and notarized by Apple once those credentials are added, so there's no Gatekeeper warning on install.

## Foldable support
- **Laptop mode:** half-fold the phone on a desk and the Remote turns into a tiny laptop, with keys above the hinge and a full-width trackpad below.
- **Book posture:** hold it half-open like a book, and Home and the Remote split cleanly at the hinge.
- **Tabletop Home:** status on the upper half, controls within thumb reach on the lower half, plus a one-tap "be the webcam" prompt when the phone is standing up.

## Week of 2026-11-12
- **Missed calls** on the Mac, with a Call Back button.
- **Send to Phone** in every Mac app's Services menu (files from Finder, links, text).
- **Global shortcuts** on the Mac: ⌥⌘M mirror, ⌥⌘T send tab, ⌥⌘F find phone, ⌥⌘U send files.
- **Mirror window:** save a screenshot to the Desktop (⌘S) and keep the window on top.
- **Only when locked:** an optional setting to skip mirroring notifications while you're using the phone.
- **Unread badges** for Messages and Notifications in the Mac window.

## 1.0.14
- Background battery: 90 s keepalive (3 min in Battery Saver); the Mac never pings; no retries off Wi-Fi; direct reconnect before mDNS; back-off up to 5 min; reconnect on unlock.
- Low-battery alerts both ways, a Mirror to Mac Quick Settings tile, and a new app icon.

## 1.0.9 – 1.0.13
- In-app updates for both apps, stable signing, redesigned Android UI, and Find my Mac.
