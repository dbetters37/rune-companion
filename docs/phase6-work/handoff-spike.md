# Hand-off from the WP6 spike (branch p6/spike-scripts, never merged)

The full evidence is in scratchpad/phase6/spike-scripts.md. The verdict is the isolated design. The items below land
in files the spike does not own.

## For WP1d (wave 1; owns AiControlCenterApp.kt, the manifest, app/build.gradle.kts)

1. **AiControlCenterApp.onCreate.** `if (Process.isIsolated()) return` must be the first statement after
   `super.onCreate()`, before `ScreenSessionNotifications.cancel(this)` and before `AppProcessHooks.onCreate(this)`.
   - Evidence: in the isolated process, `NotificationManager.cancel()` throws
     `NullPointerException ... INotificationManager.cancelNotificationWithTag ... on a null object reference` (run 3,
     PROBE.spike). An unguarded `onCreate` would crash every script process at start, and every script would then fail.
   - Field initialisers also run in the isolated process, because the Application object is constructed there.
     `AppProcessHooks.scopeContext(this)` in the `appScope` initialiser must touch no system service, file or database,
     or become `by lazy`.
2. **Manifest.** The planned `<service android:name=".scripts.ScriptProcessService" android:isolatedProcess="true"
   android:process=":scripts" android:exported="false"/>` is right as written; keep it.
3. **app/build.gradle.kts.** `buildFeatures { aidl = true }` as planned was enough for the spike's AIDL. No new library
   is needed if WP6 puts the Rhino warm-up (item 4) in :framework. Otherwise :app needs `implementation(libs.rhino)`.

## For WP6 (wave 2), in its own files (listed here so that nothing is lost)

4. **Initialise Rhino on the script service's main thread** in `onCreate`. Either add a framework helper such as
   `JsSandbox.prepareOnThisThread()` that touches `org.mozilla.javascript.Context`, or set the context class loader on
   the threads that run scripts. Without it, every script in the isolated process fails with "Regular expressions are
   not available." (run2-regex-PROBE.spike).
5. **Instance names `js0` and `js1`.** `"js-0"` throws `IllegalArgumentException: Illegal instanceName`.
6. **Plain `createPipe()` with length-prefixed frames, not `createReliablePipe()` status checks.** Closing a
   handed-over copy eats the status: "Remote side is dead" in 10 of 10 runs (RACE.spike). Send the input as a raw UTF-8
   part, not escaped inside the request JSON; cap it in characters.
7. **Death handling.** After a death, `unbindService` at once and bind afresh. A kept binding waits 1 s, 4 s, 16 s,
   and 30 minutes after two crashes.
8. **Watchdog per run.** Install a quiet uncaught-exception handler in the script process: report, then
   `killProcess(myPid())`.
9. **Device-test bounds on the emulator.** `engineStartMs` 90 s (the first run took up to 29 s under load). The "5 s
   warm" check works only with D2, because the per-run prelude alone is 3.7-5.2 s.

## For WP2 (wave 2; exit history, safe start)

10. **Script exits share the app's per-package exit history.** Records the app receives have `processName` =
    `<process>:<service class>:<instance>`. The spike measured
    `com.aicontrolcenter.spike:scripts:com.aicontrolcenter.spike.ScriptService:js_oom_a`. For the real app that is
    expected to be `com.aicontrolcenter.hq:scripts:com.aicontrolcenter.app.scripts.ScriptProcessService:js0` with
    `bindIsolatedService` (derived from the measured pattern; the class name follows the namespace), or
    `com.aicontrolcenter.hq:scripts` with plain `bindService` (API 28).
    - Reasons: `REASON_OTHER` (13) with subreason 17 "isolated not needed" after every unbind; `REASON_CRASH` (4) for
      an uncaught error.
    - The watchdog's and the quiet handler's self-kills are filed under the isolated UID and never reach the app.
    - The app got 16 records per call, so script records push older main-process records out. Read the history at
      every start and filter by process name, as planned. Use these names and reasons in the
      `ShadowActivityManager` records of the WP2 tests.

## For WP14 (LIMITATIONS)

11. **A runaway script while the app is in the background.** While the app is the "previous app" (another app opened,
    no foreground service), a runaway script uses a full core until the watchdog ends it (limit + 5 s). A frozen
    script process uses nothing.
