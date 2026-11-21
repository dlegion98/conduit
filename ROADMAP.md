# Roadmap

A backlog for the weekly improvement runs. Pick from the top, move done items to CHANGELOG.md,
and add new ideas as they come up. Keep the rule: **small, safe, shippable increments**.

## Next up
- [x] Contact photos and names in Mac Messages; mark threads read on the phone. (2026-11-14: reading on the Mac clears the
  conversation's notification on the phone; Android only lets the default SMS app change the read flag itself.)
- [ ] MMS/image messages in Messages (view only first).
- [x] Mirror: map Mac arrow keys/Tab to Android D-pad, and pinch-to-zoom from the trackpad. (2026-11-21: arrows move the
  cursor in text fields, Shift extends the selection; the pinch is sent when the trackpad gesture ends, not live.)
- [ ] Webcam: resolution/fps picker, 4:3 option, auto-start when an app opens Conduit Camera. (2026-11-21: skipped again —
  4:3 needs a second format in the ConduitCamera system extension, which can't be tested from CI. Do it in a run that can
  be checked on the Mac, together with auto-start.)
- [x] Notification history search and per-app filters on the Mac. (2026-11-14)
- [x] Clipboard history (last 20 items) in the Mac menu bar panel. (2026-11-14, in memory only)
- [ ] Phone → Mac: share photos straight into a Mac app (drag out of the Files view).
- [ ] Mac media now-playing → phone (control Mac music from the phone's Remote tab).
- [x] Android home-screen widget: link status, plus quick buttons (send clipboard, mirror, find Mac). (2026-11-21)
- [ ] Mac: "Do Not Disturb" sync (Focus on the Mac silences mirrored notifications).
- [ ] Hotspot: one tap on the Mac to turn on the phone's hotspot (if possible without root).
- [ ] Localisation: Italian and Spanish.

- [ ] Fold: show webcam status/preview on the cover screen while unfolded (Jetpack WindowAreaController rear display), if Samsung supports it.
- [x] Fold: Mac mirror window remembers separate sizes for folded vs unfolded. (2026-11-21, landscape too)
- [ ] Messages: contact photos on SMS notification banners, and pick a contact by name in New Message (phone-side contact search).
- [ ] Notification history: optional "keep across restarts" (off by default, since it would write message text to disk).
- [ ] Mirror: live pinch (stream the gesture with `willContinue` strokes instead of replaying it at the end).
- [ ] Mirror: ⌘A / ⌥-arrow word jumps / Home-End in phone text fields.
- [ ] Widget: a 1x1 "send clipboard" variant, and Mac battery on the widget's lock-screen/cover-screen version.

## Ideas / needs investigation
- Call audio routing (macOS has no hands-free profile; possibly via a virtual audio device).
- RCS threads (no public API; notifications already work).
- Bluetooth fallback when Wi-Fi is unavailable.
