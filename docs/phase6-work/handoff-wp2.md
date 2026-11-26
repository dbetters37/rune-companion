# Hand-off from WP2 (background work and recovery, Android side)

Branch `p6/wp2` (worktree `/home/user/p6-wp2`), from 6fb816d. WP2 changed only files it owns: app/work/*, app/health/*,
app/di/wiring/BackgroundWiring.kt, device/health/* (new), data/DataLayer.kt, data/security/*, data/recovery/* (new),
res/drawable/ic_stat_done.xml, ic_stat_stopped.xml, ic_stat_question.xml, and their tests (the data module's
`security/FakeAndroidKeyStore.kt` test double gained `brokenAliases`). No shared file was edited.

## 1. What is there (by plan item)

| Item | What | Where |
|---|---|---|
| 1 Wake lock (A1, rule 5) | `WakeDecision` (pure: hold iff the setting allows it and an agent is WORKING, a chat turn is live and not waiting for the owner, or a wait is due within 2 min; a longer wait gets one alarm 2 min before it is due); `WakeHold` (one PARTIAL_WAKE_LOCK "aicc:agents", not counted, 10 min per acquire, renewed every 5 min, released when only waiting/paused, setting off, `onDestroy`); `WakeAlarms` (`setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` to the non-exported `WakeReceiver`, one PendingIntent); `WakeReceiver` (only `graphIfBuilt()`: wakes `orchestrator.timedWaits` and `providerRechecks.timedWaits`, holds "aicc:wake" 30 s, pokes `WakeSignals` so the service decides again). The service's notification shows "Keeping the phone awake while agents work" (sub text) while held. **The Director's planning shows as WORKING** in the monitor (the plan's [A]; `ObjectiveWorkServiceTest` checks it with the real orchestrator). | app/work/WakeHold.kt, WakeAlarms.kt, WakeReceiver.kt, ObjectiveWorkService.kt |
| 2 Chats in the service (A2, F29) | `ObjectiveWorkController.shouldRun = (objectives || live chat turns) && (inForeground || batteryExempt)`; the `IllegalStateException` catch stays. While only chat answers are written the notification is "<agent> is answering" / "2 agents are answering" with **Stop** (`ACTION_STOP_CHATS` → `AgentChat.cancel` for every live turn). | ObjectiveWorkController.kt, ObjectiveWorkService.kt, WorkNotifications.kt |
| 3 Fewer reads (P1) | The work notice is built from `observeObjectiveRows` + `observeProgress(running)` (`WorkNotice.of(running, rows, progress)`), never whole trees. | ObjectiveProgress.kt |
| 4 Background check (A3) | `device/health/AndroidBackgroundHealth` (notifications, `isIgnoringBatteryOptimizations`, `isBackgroundRestricted` (28), standby bucket (28); `sdkInt` injected); `BackgroundSettingsIntents.batteryExemption/appDetails` (always an intent: a look-up would be hidden by package visibility; the caller answers `ActivityNotFoundException` with `BackgroundSettingsIntents.NO_SUCH_SCREEN`); `BackgroundWiring` adapts them as `BackgroundScreens`. Refreshed at start (hooks) and on every return to the app (`GraphServices`). | device/health/AndroidBackgroundHealth.kt, di/wiring/BackgroundWiring.kt |
| 5 Result notifications (A6, rule 4) | Channel "Results" (default importance). `WorkResult.of(row, before, cause)`: "Finished: <title>", "Stopped: it could not be finished / it ran out of time / it was cancelled", "Interrupted: <ExitKind.cause>", "Paused: today's limit is reached" (only for `statusDetailShort == DailyLimits.PAUSED_TEXT`), "Paused: the phone is almost full" (the service's). Only while the app is not on screen and `notifyWhenWorkEnds` is on; never a task's status text, error or result; no actions; private with a public version; tap opens the objective through the existing `EXTRA_OBJECTIVE_ID`. Approvals: a QUESTION says "A question from <agent>. Open the app to read it." (title "A question from <agent>", icon `ic_stat_question`, public "An agent has a question"), never `question`, `options`, `summary` or the task's text; **one reminder** 5 minutes before any request expires (alerts once more; none for a request that expires sooner). Screen sessions: "<agent> waits for your answer to act in <app>" while `waitingForOwner`; Stop stays the only action. | ResultNotifications.kt, ApprovalNotifications.kt, ScreenSessionNotifications.kt |
| 6 Exit reasons (R1, rule 31) | `device/health/AndroidExitHistory`: API 30+, main process only (`processName == applicationInfo.processName`; the script process's `<pkg>:scripts…` records never count), oldest first, after the time given; `kindOf(reason, description)` maps every `REASON_*`, 15/16/17/18 by value, `REASON_OTHER` + "MemoryLimiter" → MEMORY_LIMIT; the description is read for that alone and never kept; Android 9-10: nothing. | device/health/AndroidExitHistory.kt |
| 7 Safe start and crash notes (R2, rule 10) | `StartLedger` (noBackupFilesDir/health/start-ledger: started/ok with versionCode, safe start and its reason, last exit seen, recent crash times); `SafeStart` (two starts in a row without "ok", or three CRASH/CRASH_NATIVE/ANR of the main process in the 15 minutes before a start → on until the owner chooses); `SafeStartControl` (`state`, `leave()`) exposed as `BackgroundPorts.safeStart`; `CrashNotes` (uncaught handler chained to the previous one, main process only: exception class, cause classes, first 20 `com.aicontrolcenter` frames, no message; at most 5 notes); `AndroidStartupHooks` (beforeSteps: new exits since the last seen → ledger, safe start, cause; logs a crash note left by the last run; storage check; background check. interruptCause: the last exit's `ExitKind.cause`. resumeAllowed: not while safe start is on. afterSteps: "ok", then the upkeep job's schedule). | app/health/StartLedger.kt, SafeStart.kt, CrashNotes.kt, AndroidStartupHooks.kt; app/work/AppProcessHooks.kt |
| 8 Scope handler and supervised collectors (G7, rule 26) | `AppScopeExceptionHandler` (via `AppProcessHooks.scopeContext`): logs "Something in the app failed" with the class and app frames only, keeps the process for an `Exception`, rethrows an `Error`; logs only through a graph that exists. `SafetyCollectors`: restart with 1, 2, 4 … 60 s, WARNING each time; 3 failures within 5 minutes run the fail-stop: the "is operating" notification → `screenControl.stop()`, the controller → pause running objectives. `GraphServices` runs the controller, approvals, screen sessions, results and the background refresh under it. | app/health/AppScopeExceptionHandler.kt, app/work/SafetyCollectors.kt, GraphServices.kt |
| 9 Database cannot be opened (R5, F10, rules 9, 30) | `data/recovery/KeyGenerations` (note in `noBackupFilesDir/database-keys/generation`; absent = 1 = the 0.5.2 names exactly: `aicc.db`, `database-keys/aicc.db.key` under `aicc.database-key.v1`, `vault/secrets.json` under `aicc.secret-vault.v1`; generation N: `aicc.vN.db`, `database-keys/aicc.vN.db.key`, `aicc.database-key.vN`, `vault/secrets.vN.json`, `aicc.secret-vault.vN`); DataLayer chooses file, key and vault of the generation in use when they are first opened (off the main thread; building it still touches no file). `DatabaseKeyException.problem` (`KeyProblem` MISSING/DAMAGED/KEYSTORE_UNAVAILABLE/OTHER; a keystore entry that is gone is told from one that does not load). `DatabaseHealth.check()` / `classify()` (key problems; SQLite's "not a database"/"malformed"; Room's migration failures; other). `DataLayer.databaseHealth`, `DataLayer.ownerRecovery` = `FileOwnerRecovery` (Start over: refuses while the data opens; resets seedVersion, memorySeedVersion, directorAgentId, onboardingCompleted, both operator switches (other settings kept); notes the next generation durably; closes the database and refuses every later open in this process; moves the database and its journals, the sealed key, the vault and their side files of every older generation still in place into `noBackupFilesDir/set-aside-<date>[-n]/` with an `about.txt` naming the keystore entries (no secret); releases the persisted folder grants; a second tap returns the first result; never deletes; keystore entries stay). A damaged file is never deleted by the platform helper (`KeepOnCorruption`; SQLCipher's own handler already keeps it). `AppGate` (checks once off the main thread; queues `whenOpen` work and runs it in order on the main thread when open; drops it when the data does not open; `recheck()` for "Try again"). `RecoveryActions.restartApp(activity)` (MAIN activity, NEW_TASK | CLEAR_TASK, no extra, then `killProcess(myPid())`) and `startOverAndRestart(activity, recovery)`. | data/recovery/*, data/security/DatabaseKeyStore.kt, KeystoreVaultKey.kt, DatabaseKeying.kt, data/DataLayer.kt; app/health/AppGate.kt, RecoveryActions.kt |
| 10 Storage (R8) | `device/health/AndroidStorageWatch` (`getAllocatableBytes(getUuidForPath(filesDir))`, injectable measure); measured at start (hooks), when the set of running objectives changes (a new one) and every 5 minutes while one runs (service); CRITICAL pauses the running objectives, logs "Paused: the phone is almost full" per objective and posts that result notification when the app is away. | device/health/AndroidStorageWatch.kt, ObjectiveWorkService.kt |
| 11 Upkeep job (R9, D36, rule 1) | `UpkeepJobService` (JobScheduler, id 6001, idle, battery not low, 24 h, not persisted; `onStartJob` runs `(application as UpkeepHost).upkeep.run()` on its own scope and calls `jobFinished`; `onStopJob` cancels and returns true; scheduled by `afterSteps`). `UpkeepGraph.run()`: memory `maintain()`, activity pruning by `activityRetentionDays`, `UsageRetention(...).prune()` (WP4's; never today's rows), then the added steps (WP17); does nothing when the last "ok" was another versionCode, safe start is on, `MaintenanceGate.busy`, or the database check is not OK (`lastSkip` says why); one failing step never stops the others. `UpkeepPorts` gained `databaseCheck: DatabaseHealth` (default: one read of the activity log, sorted by `DatabaseHealth.classify`) and `startLedger: StartLedger` (default from `noBackupFilesDir`), so the application file needed no change. | app/work/UpkeepGraph.kt, UpkeepJobService.kt |

Also: `ObjectiveWorkService` handles `RoutinesWiring.ACTION_RUN_DUE_ROUTINES` (security hand-off): `startForeground` first, then
`graph.routinesAssembly.runDue()` on the service's scope, holding the service up until it returns (a notification's Pause and Stop and
the storage pause are held the same way, so stopping the service cannot cut them off). Test seam: `ObjectiveWorkService.runDue`
(internal).

## 2. Changes needed in files WP2 does not own

| Where | What | Why | Who |
|---|---|---|---|
| framework `orchestration/DefaultOrchestrator.kt`, core `tasks/TaskPorts.kt` | A `pause(rootId, reason: String)` (or a system pause) so the storage pause stores "Paused: the phone is almost full" as the objective's status text instead of "Paused by the owner." and is not journalled as an owner action. Until then the activity log entry and the result notification say the right thing, the objective's own text does not. | R8, plain words | WP8 (WP7's files) |
| app `ui/tasks/NewObjectiveViewModel.kt` (or the orchestrator) | Before starting: `graph.storageWatch.check()`; at `StorageLevel.CRITICAL` refuse with "The phone is almost full. Free some room before starting an objective." (the plan's "New objective refuses"). The service pauses one that started anyway. | R8 | WP11a |
| app `di/AppGraph.kt` | `val safeStart: SafeStartControl = ports.background.safeStart` for WP10's banner (until then `graph.ports.background.safeStart`). | rule 10 | WP8 |
| app `MainActivity.kt` | Optional: queued `gate.whenOpen` work is run even if the activity was destroyed meanwhile (harmless today: `graph.start()` is idempotent, `onAppForegrounded` sets the foreground flag the next `onStop` clears). A lifecycle-aware `whenOpen(owner, action)` would skip it. Also pass the gate to `RecoveryHost` so WP10's "Try again" can call `gate.recheck()`. | WP1d note | WP8 / WP10 |
| app `AiControlCenterApp.kt` | Optional: `UpkeepPorts(databaseCheck = dataLayer.databaseHealth)` instead of the default probe through the activity log (same classification; the default needs no change here). | rule 1 | WP8 |
| app `di/OperatorDependencyTest.kt` / `ProductionGraphDependencyTest.kt` | The rule-1 walk from the upkeep job is in `app/src/test/.../work/UpkeepJobTest.kt` (`AgentFacingWalk` with its own forbidden list: AppGraph, Orchestrator, ModelGateway, ToolExecutor, AgentChat, ProviderManager, SecretVault, PermissionRepository, OwnerRequestRepository, OwnerKeyCheck, AiControlCenterApp, DataLayer). WP8 may move it into the architecture tests ("UpkeepJobService as a root"). | rule 1, secfix | WP8 |
| docs (WP14) | STATUS/ARCHITECTURE/LIMITATIONS: key generations (names above; old entries kept; set-aside folders never deleted by the app); Start over; safe start; crash notes (no message text); wake lock and alarm rules; result notifications; storage pause; upkeep job (idle, charged, daily, not after a phone restart until the app is opened); "Android 9 and 10 do not say why"; only SDK 35 tested on the JVM, other API levels "not verified". | docs say what is built | WP14 |

No architecture-test list must grow: `FileOwnerRecovery` is an `OwnerRecovery` (listed); nothing agent-facing holds the new classes.
No manifest change: `UpkeepJobService` and `WakeReceiver` were declared by WP1d (the receiver has no intent filter; the alarm targets it
explicitly). `WAKE_LOCK` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` are declared.

## 3. Device tests to run

```
export TMPDIR=/tmp
./tools/gw --offline :data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.aicontrolcenter.data.recovery.StartOverDeviceTest
```

`data/src/androidTest/java/com/aicontrolcenter/data/recovery/StartOverDeviceTest.kt` (real AndroidKeyStore and SQLCipher; own database
name, own generation note, own settings file; removes what it made, generation-2 keystore entries included):
- `sealedKeyRemovedIsReportedAsMissingAndNoKeyIsInvented`
- `startOverWithTheRealKeystoreAndSqlCipherGivesAFreshStartAndKeepsTheOldCopyOpenable` (flips a byte of the sealed key → KEY_DAMAGED; Start
  over; generation 2 opens empty under `aicc.database-key.v2`; the set-aside database still opens with the intact sealed key under
  generation 1's entry, which stayed)
- `generationOneDataWrittenThe052WayStillOpensAfterTheUpgrade` (data, sealed key and vault written exactly as 0.5.2 did open unchanged,
  no generation note written, the sealed key not replaced)

Worth a look on the emulator in §10 (wave 5): `adb shell dumpsys power | grep aicc` while an objective plans with the screen off
(held) and after Pause (released); `adb shell dumpsys alarm | grep com.aicontrolcenter` for one alarm during a long provider wait;
`adb shell cmd jobscheduler run -f com.aicontrolcenter.hq 6001` (the upkeep job; it logs nothing on success, skips until a start of this
version reached "ok"); the "Results" notification after an objective finishes with the app in the background; a chat answer with the
app in the background ("<agent> is answering · Stop").

## 4. Notes for wave 2b and wave 3 packages

**WP17 (routines).** `ObjectiveWorkService` with `RoutinesWiring.ACTION_RUN_DUE_ROUTINES` goes into the foreground and calls
`graph.routinesAssembly.runDue()` once, holding the service until it returns; afterwards the service stays while objectives run and stops
when nothing does. Upkeep steps you add run after the job's own and only under its checks (this version reached "ok", no safe start, no
restore, the database opens). A routine run started while safe start is on: decide whether routines respect `resumeAllowed()`-like safety
(`graph.ports.background.safeStart.state`).

**WP3 (backup).** After a restore call `RecoveryActions.restartApp(activity)` (rule 30). A restore runs in `MaintenanceGate.runExclusive`; the
upkeep job skips while `busy`. The restored database is the current generation's; the backup never holds keys, so key generations do not
matter to the file.

**WP6 (scripts).** Exit history and crash notes count only the main process (`processName`); `AppProcessHooks.onCreate` installs crash notes
only when `Application.getProcessName()` is the main one (the isolated process returns before it anyway).

**WP8 (integration).** StrictMode: the main-thread file touches left are `noBackupFilesDir` look-ups (the crash notes' folder in
`Application.onCreate`, the ledger's path in `BackgroundWiring.production`); every read and write runs on IO. The gate opens the database
once, off the main thread, before the graph is built (`AppGate`); the upkeep job opens it only after a start of this version reached "ok".

**WP10 (screens).**
- Recovery: `RecoveryHost(state, recovery)`; the confirm word field (`OutlinedSecureTextField`, `TextFieldState` in a view model, typing
  test) checked with `OwnerRecovery.confirms`; then `RecoveryActions.startOverAndRestart(activity, recovery)`; show `RecoveryResult.Refused.reason`
  as it is (`FileOwnerRecovery.OPENS_NORMALLY`, `SETTINGS_UNWRITABLE`, `STORAGE_UNWRITABLE`); "Try again" → `gate.recheck()`; FLAG_SECURE;
  `DatabaseState.ownerText` for the reason. Tell the owner the old data is kept in the app's private storage (it is not reachable from the phone's
  files app).
- Background check: `graph.backgroundHealth.state` (`BackgroundState`: notifications, `batteryOptimised`, `backgroundRestricted`, `standbyBucket`,
  `standbyWarning`, `allClear`), `refresh()` on return from Android's screen; buttons `graph.backgroundScreens.batteryExemption()` (battery) and
  `appDetails()` (everything else); on `ActivityNotFoundException` show `BackgroundSettingsIntents.NO_SUCH_SCREEN` and offer the other button.
  The battery exemption also lets chat answers and resumed objectives keep the service from the background (F29).
- Storage: `graph.storageWatch.level` — LOW: a Dashboard warning ("Less than 500 MB free"); CRITICAL: "The phone is almost full: running
  objectives were paused". Exits: `graph.exitHistory` is live (newest last) if a history screen wants it; the start hooks already turn the last
  one into the "Interrupted: …" cause.
- Safe start banner (Tier B): `graph.ports.background.safeStart.state` → `SafeStartState(on, reason)`, text `reason.ownerText` plus "Interrupted
  work waits for you"; "Back to normal" → `leave()`.
- Settings: "Keep the phone awake while agents work" (`keepAwakeWhileAgentsWork`) and "Tell me when work ends" (`notifyWhenWorkEnds`) are
  fully working.
- Activity log lines WP2 writes: "Safe start is on", "Left safe start", "The app stopped because of an error", "Something in the app failed",
  "<collector> had to be restarted", "Paused: the phone is almost full".

**WP11a.** The question notification opens the Approval route with `EXTRA_REQUEST_ID` (unchanged); the new objective refusal at CRITICAL (§2).

**WP11b.** A chat answer keeps the service and the "<agent> is answering · Stop" notification; Stop cancels like the chat's own Stop.

**WP9.** Nothing new; the work notice already uses summaries.

## 5. Checks run

- Whole suite in the worktree at 6bc5797 (`./tools/gw --offline test --continue`, then `./tools/test-summary`): **3,129 tests, 0 failed**:
  app 540, core 165, data 453, device 360, framework 1,014, memory 107, providers 229, providers-anthropic 89, testkit 172.
  New in WP2: app 48 (health: SafeStartTest 6, AndroidStartupHooksTest 6, RecoveryPathTest 7; work: WakeHoldTest 4,
  NotificationRulesTest 6, SafetyCollectorsTest 5, UpkeepJobTest 7, ObjectiveWorkServiceTest +7; UpkeepIsolationTest's narrowness check
  extended), data 21 (KeyGenerationsTest 5, DatabaseHealthTest 6, FileOwnerRecoveryTest 10), device 8 (DeviceHealthTest): 77 in all
  (3,052 before), plus 3 instrumented (StartOverDeviceTest).
- `./tools/gw --offline :app:assembleDebug :data:assembleDebugAndroidTest`: BUILD SUCCESSFUL.

## 6. Deviations from the plan (and why)

1. **The database file is named by generation too** (`aicc.vN.db` for N > 1), not only the sealed key and the vault. Start over notes the
   next generation *before* it moves anything; with one shared file name a Start over cut off between the two steps could open the old data
   under a new key, or split the set-aside copy from its key. Generation 1 keeps every 0.5.2 name.
2. **`FileOwnerRecovery` is in `:data` (data/recovery)**, beside `DatabaseHealth`, not in `:app` as the core KDoc says: it needs the data
   layer's internals (closing the database, refusing later opens, the file names of each generation). The app gets it as
   `DataLayer.ownerRecovery` through `BackgroundWiring`.
3. **The last processed exit time is kept in the start ledger** (noBackupFilesDir), not in device SharedPreferences: all start bookkeeping
   in one small file, readable without the database and tested on the JVM.
4. **Safe start counts three crashes within the 15 minutes before a start** (not any three within 15 minutes of each other): the first start
   of 0.6.0 reads Android's whole kept history, and an old burst there must not turn safe start on.
5. **The wake alarm fires 2 minutes before the wait is due**, not at the due time: the receiver wakes both waits and tells the service to
   decide again, which then holds the wake lock for the last two minutes, so the wait's own timer (which does not run while the phone
   sleeps) fires on time. With the owner's "keep awake" switch off there is no alarm either.
6. **The storage check "before an objective starts"** happens as soon as it appears among the running objectives (the service), which pauses
   it at once when the phone is almost full; the refusal on the New objective screen is WP11a's (§2). The storage pause uses
   `Orchestrator.pause` (§2).
7. **The upkeep job's database check defaults to one read through the activity log** (classified exactly like the app's check), because the
   application builds `UpkeepPorts` and `AiControlCenterApp.kt` was not WP2's to change.
8. **`BackgroundScreens` always returns an intent**: resolving Android's settings screens first would be hidden by package visibility on some
   phones; a missing screen is answered when tapped.
9. **The storage watch is tested with an injected measure**, not `ShadowStorageManager`, which has no `getAllocatableBytes`
   (Robolectric 4.18.1); the real call is made once on the JVM and must not throw. The damaged-alias Start over test uses the real
   `KeystoreVaultKey` over the fake "AndroidKeyStore" with one broken entry, rather than a fake `VaultKeySource`.
10. **Added beyond the plan:** a damaged database file is never deleted by the platform's corruption handler (rule 9); a Start over makes the
   old data layer refuse every later open (no empty file under the old name before the restart); `BackgroundPorts.safeStart` (the owner's
   choice to leave safe start); `UpkeepGraph.lastSkip` (why a run did nothing).
