# Working on Conduit

Mac (SwiftUI, `macos/`) + Android (Kotlin/Compose, `android/`) link app. The wire protocol is in `PROTOCOL.md`.

## Shipping rules (these apps auto-update on the owner's devices)
1. Work on a branch, never directly on `main`. Push the branch and wait for **both** workflows
   ("Build & release" and "Android screenshots") to pass on it. CI is the only compiler available.
2. Fix until green. Only then fast-forward `main` to the branch and push. Pushing `main` publishes
   a release that both apps install through their in-app updaters.
3. Keep the protocol backward compatible: the phone and Mac may run different versions for a while.
   Add new message types/fields; never rename or remove existing ones. Unknown types must be ignored.
4. Never touch signing secrets, the keystore settings, or the updater's manifest URL.
5. Battery matters: no polling loops, no shorter keepalives, nothing that wakes the phone more often.
6. Check UI changes in the screenshot test output (the `screenshots` release on main, or the job log on branches);
   add a case to `ScreenshotTest.kt` for new screens.
7. Update `CHANGELOG.md` (user-facing, short) and tick or add items in `ROADMAP.md`.
