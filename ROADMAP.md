# Roadmap

A backlog for the weekly improvement runs. Pick from the top, move done items to CHANGELOG.md,
and add new ideas as they come up. Keep the rule: **small, safe, shippable increments**.

## Next up
- [x] Contact photos and names in Mac Messages; mark threads read on the phone. (2026-11-14: reading on the Mac clears the
  conversation's notification on the phone; Android only lets the default SMS app change the read flag itself.)
- [x] MMS/image messages in Messages (view only first). (2026-11-28: pictures load on demand; video/audio/vCard show as a
  label. New incoming MMS don't push live yet — they appear on the next refresh, and the notification still mirrors.)
- [ ] MMS: push new picture messages live (observe the MMS table like SMS), and show who sent each message in group threads.
- [ ] MMS: send a photo from the Mac (drag an image into a conversation).
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
- [ ] Messages: contact photos on SMS notification banners.
- [x] Messages: pick a contact by name in New Message (phone-side contact search). (2026-11-28)
- [ ] Notification history: optional "keep across restarts" (off by default, since it would write message text to disk).
- [ ] Mirror: live pinch (stream the gesture with `willContinue` strokes instead of replaying it at the end).
- [x] Mirror: ⌘A / ⌥-arrow word jumps / Home-End in phone text fields. (2026-11-28, plus ⌥⌫/⌘⌫, forward delete, and ⌘C/⌘X
  copying the phone's selection to the Mac clipboard)
- [ ] Mirror: ⌥↑/↓ and ⌘↑/↓ in multi-line fields move by line using the field's layout (needs per-line positions from
  accessibility; today ⌘↑/↓ jump to the start/end of the text and plain ↑/↓ are a D-pad press).
- [ ] Widget: a 1x1 "send clipboard" variant, and Mac battery on the widget's lock-screen/cover-screen version.

## Ideas / needs investigation
- Call audio routing (macOS has no hands-free profile; possibly via a virtual audio device).
- RCS threads (no public API; notifications already work).
- Bluetooth fallback when Wi-Fi is unavailable.
