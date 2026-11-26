# Hand-off from WP17 (routines and automatic folder pick-up; owner decision Q1 = "Yes, both"; Tier B)

Branch `p6/wp17` (worktree `/home/user/p6-wp17`), from 6fb816d. Spec: plan OWNER DECISIONS and Appendix A, the builder's
guide "Appendix A contracts", handoff-wp1d.md (WP17 part), handoff-secfix.md (WP17 parts, second review included).

WP17 changed only files it owns: framework/routines/* and framework/pickup/* (new), app/routines/*,
app/di/wiring/RoutinesWiring.kt, and their tests (framework/src/test/.../routines, .../pickup; app/src/test/.../routines),
with one exception: the two WP17 lines of WP1d's `app/src/test/.../di/AssemblyMoveTest.kt` ("Routines (WP17): nothing
yet": `assertEquals(null, graph.ownerRoutines/ownerPickUpRules)`) now assert that `graph.ownerRoutines` and
`graph.ownerPickUpRules` are the assembly's instances and not null (the test pinned the stub this package replaces; the
whole suite fails without the change).
No app/pickup/ file was needed (the pick-up step lives in RoutinesWiring; the runner in framework/pickup). Both master
switches stay off after installing (`routinesEnabled`, `folderPickUpEnabled` default false, unchanged); the two
receivers stay disabled while no routine exists.

## 1. What is built

| Item | Where | What it does |
|---|---|---|
| `DefaultOwnerRoutines : OwnerRoutines` | framework/routines/DefaultOwnerRoutines.kt | As the reference, plus a USER_ACTION log line per change (title = owner's routine title, data = routineId). Passes `OwnerRoutinesContract` in memory (framework) and over Room (app `RoomOwnerRoutinesContractTest`). `updatedAt` is the owner's last change; the runner never sets it. |
| `DefaultOwnerPickUpRules : OwnerPickUpRules` | framework/pickup/DefaultOwnerPickUpRules.kt | As `ReferenceOwnerPickUpRules` (F1 + second review: read-only folder, no overlap with a writable folder, baseline of seen files before switching on, `FOLDER_UNREADABLE`, one rule per folder, cap 10 under one lock), plus USER_ACTION log lines. Space check is a function (`spaceExists`). Passes `OwnerPickUpRulesContract` (9 tests) in memory and over Room. |
| `RoutineClock` | framework/routines/RoutineClock.kt | Due-time arithmetic on `RoutineSchedule.nextAfter` (DST rules of the contract), `overdue` (count/first/latest of missed due times, capped at 1,000), `realigned` (a stored next due time computed in another zone), `alarmAt` (earliest next due of routines that are on, none while the master switch is off). `LATE_START_MS` 30 min, `TAP_WINDOW_MS` 3 h. |
| `RoutineRunner : WaitingRoutines` | framework/routines/RoutineRunner.kt | `runDue()` (the alarm's service path; the only path that starts work by itself), `catchUp()` (the owner opened the app; never starts work), `startWaiting(routineId, runId)` (the owner's tap + confirmation), `dismissWaiting`, `observeWaiting`, `masterSwitchedOn`, `realign`. Checks at run time, all read afresh: master switch, routine on, at most 10 routines (oldest), no unfinished objective of this routine (`tasks.withStatus(unfinished)` with `options.routineId`; also finds an objective whose run was never recorded), daily limits (fails closed), local-only blocks the Director, maintenance gate, failures in a row since the owner's last change (pauses at 3 with `RoutineLimits.PAUSED_AFTER_FAILURES`). Starts every run with exactly `routine.objectiveOptions()`. Claims a due time (moves `nextRunAt`) before starting, so a due time never runs twice. Every due time recorded once (several missed in a row = one MISSED run that says how many), and logged (SYSTEM; STARTED carries the objective id). One lock for all of it. |
| `UsageDailyLimitCheck`, `RegistryLocalOnlyCheck`, ports `RoutineNotices`, `RoutineAlarmPort`, `WaitingRoutines`, `WaitingRoutine` | framework/routines/RoutineChecks.kt | Daily limits with `DailyUse.of(usageReports.byModel(UsageRange.day(now, zone)), prices)` and `settings.dailyLimits.reachedBy`; local-only = Director's availability is `BLOCKED_BY_LOCAL_ONLY_MODE` (read-only registry views). |
| `PickUpRunner` | framework/pickup/PickUpRunner.kt | One run of every rule (upkeep step). Gate busy: nothing. Rules that are off: left alone. `PickUpSafety.runProblem(rule, folder, folders.all(), master)` before listing and again after reading, before storing anything; on a sentence nothing is stored, the sentence becomes `lastResult`, and `RUN_FOLDER_NOT_READ_ONLY` / `RUN_FOLDER_OVERLAPS_WRITABLE` switch the rule off. `text/*` only, ≤ 1 MB per file, UTF-8 only (BOM dropped), content-hash de-duplication (`wasImported`: imported or seen; also within one run), ≤ 5 MB a local day over all rules (the rest waits for the next day). Each file → `PickUpSafety.memoryDraft` memories written as `MemoryActor.Owner` (origin IMPORT, source "Picked up from <folder>", tag `picked-up`), split into parts of ≤ 20,000 characters titled "<name> (part 2 of 3)", then `recordImport(seenOnly = false)`; if another run recorded the content first, or the space cannot take a part, the memories just made are taken back. Nothing reaches a model, a tool or a provider. `lastResult` is a plain sentence ("Picked up 2 files. 1 file was left for tomorrow: …"). |
| `AndroidRoutineAlarms : RoutineAlarmPort` | app/routines/RoutineAlarms.kt | One alarm (`RTC_WAKEUP`) to `RoutineAlarmReceiver` (explicit, action `ACTION_ROUTINE_ALARM`, no extra, `FLAG_IMMUTABLE`): `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()` (always on Android 9-11), else `setAndAllowWhileIdle`; a `SecurityException` between check and call falls back to inexact. Keeps only the alarm's time in a private SharedPreferences file `routine_alarm` (one number) for `BootReceiver`. `receiversEnabled` toggles both receivers with `setComponentEnabledSetting(…, DONT_KILL_APP)`. `sdkInt` injected. |
| `RoutineAlarmReceiver` | app/routines/RoutineAlarmReceiver.kt | Only for `ACTION_ROUTINE_ALARM`. Only an exact alarm starts work by itself: when exact alarms are allowed (`AndroidRoutineAlarms.exact()`: `canScheduleExactAlarms()`, always on Android 9-11) it calls `ContextCompat.startForegroundService(ObjectiveWorkService, action = RoutinesWiring.ACTION_RUN_DUE_ROUTINES)`, no extra. Without exact alarms, or when Android refuses the start (`ForegroundServiceStartNotAllowedException`, `SecurityException`), it posts "Tap to start" and starts nothing; the owner opening the app turns the due time into a waiting run (banner). |
| `BootReceiver` | app/routines/BootReceiver.kt | Only `BOOT_COMPLETED` and `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`: sets the one alarm again at the remembered time (`armAgain`); starts nothing, builds nothing. A routine due while the phone was off is handled when that alarm fires (missed, or waits for a tap; never starts more than 30 min late by itself). |
| `RoutineNotifications`, `AndroidRoutineNotices` | app/routines/RoutineNotifications.kt | Channel "routines" (default importance). No actions, `setAllowSystemGeneratedContextualActions(false)`, private with public version "A routine is waiting to start.", content intent = `MainActivity` by class, no extra, no action, no data (rule 23). Text: app-written + the owner's routine title. Small icon: the existing `ic_stat_director`. |
| `WaitingRoutinesBanner` + `WaitingRoutinesViewModel` | app/routines/WaitingRoutinesBanner.kt | The in-app "Routine '<title>' is waiting to start" banner (secfix second review: WP17's). Start asks once more (`ConfirmDialog`, guarded, says what the team will do, the limits, screens yes/no), "Start now" calls `startWaiting` for exactly that run; "Not now" dismisses (MISSED); a refusal shows its sentence with OK. The VM calls `catchUp()` when created. No text field. |
| `RoutinesPorts`, `RoutinesAssembly`, `PickUpStepPorts`, `RoutinesWiring` | app/di/wiring/RoutinesWiring.kt | `RoutinesPorts(routines, pickUpRules, alarms = None, notices = None, zone)` (new fields have defaults; `TestApp` unchanged). `RoutinesAssembly` (same constructor as WP1d's): `ownerRoutines`, `ownerPickUpRules` (now non-null when the stores exist), `runner` (internal), `waitingRoutines`, `runDue()`, `catchUp()`; on `appScope`: realign, then keep the alarm at `alarmAt(routines, master)`, receivers enabled iff a routine exists, `masterSwitchedOn()` on an off→on change of the master switch (nothing due while off runs), and `noneWaiting()` when no run waits. `PickUpStepPorts` gained `activity: ActivityRecorder? = null` (last, defaulted: AiControlCenterApp compiles unchanged). `upkeepSteps(ports)` = one step running `PickUpRunner.runAll()`, exceptions other than cancellation swallowed. `production(context, data)` adds `AndroidRoutineAlarms` and `AndroidRoutineNotices` over the application context. The `@Phase6Stub` marks of RoutinesWiring, RoutinesAssembly, RoutineAlarmReceiver and BootReceiver are gone (no longer stubs). |

## 2. Changes needed in files WP17 does not own (for the merger / WP8)

| File | Change | Why |
|---|---|---|
| app/AiControlCenterApp.kt (`upkeep`) | `folders = PickUpFoldersView(dataLayer.sharedFolders, <SAF reader>)` in place of `PickUpFolders.None`, and `activity = ActivityRecorder(dataLayer.activityLog, RandomIdGenerator, WallClock)` in `PickUpStepPorts(...)`. The SAF reader is the device's `SafSharedFolders` (internal to :device): either (a) a public factory in :device, e.g. in device/operator/PhoneOperatorLayer.kt `companion object { fun folderFiles(context: Context, folders: SharedFolderRepository): SharedFolderFiles = SafSharedFolders(context.applicationContext ?: context, folders) }`, or (b) without a device change, `PhoneOperatorLayer(this, dataLayer.sharedFolders, { ProtectedPackages(packageName) }).sharedFolders` (construction touches no system service; only its `sharedFolders` member is kept). Until this is applied, every production pick-up run finds its folder gone (`FOLDER_GONE` as last result) and imports nothing: safe, but pick-up does not work on the phone. | secfix WP17 "Wire `PickUpFoldersView(dataLayer.sharedFolders, <reader>)` in `AiControlCenterApp.upkeep`"; F11 (narrow port: `PickUpFoldersView` exposes reads only). `UpkeepIsolationTest` stays green: `PickUpStepPorts` field types are unchanged except the new `ActivityRecorder?`, which is not a wide type. |
| app/work/ObjectiveWorkService.kt (WP2) | On `RoutinesWiring.ACTION_RUN_DUE_ROUTINES`: `startForeground` first (already), then `graph.routinesAssembly.runDue()` in the service's scope, **and do not let `watch()` stop the service before `runDue()` has returned** (today `watch()` stops at once when `orchestrator.running` is empty, which it is until `runDue` started the objective). After `runDue`, if nothing runs, stop as usual. | secfix WP2 item; without the ordering the service may stop before the routine's objective starts (the objective itself still starts: `startObjective` launches on the app scope). |
| app/di/AppGraph.kt (WP8) | `val waitingRoutines: WaitingRoutines get() = routinesAssembly.waitingRoutines`; in `onAppForegrounded()`: `appScope.launch { routinesAssembly.catchUp() }` (catch and log failures). | The banner's VM and the "owner opened the app" catch-up; `routinesAssembly` is `internal`, so UI code in :app can also use it directly. |
| app/ui/dashboard (WP8/WP10) | At the top of the Dashboard: `val waiting = viewModel { WaitingRoutinesViewModel(graph.waitingRoutines, graph.clock) }` and `WaitingRoutinesBanner(waiting)` (it draws nothing when no run waits). WP10's Routines screen links to / shows the same banner. | secfix second review: the Start button is WP17's banner; WP10 only links to it. |
| both architecture tests (WP8, optional) | Add `com.aicontrolcenter.framework.routines.WaitingRoutines`, `RoutineRunner`, `com.aicontrolcenter.framework.pickup.PickUpRunner` (and the two `Default…` owner ports) to the owner-only lists. | They already hold owner-only stores (`RoutineRepository`, `PickUpRuleRepository`), so a holder fails the walks today; listing them names the reason. |
| app manifest (WP8, optional) | A `TIMEZONE_CHANGED`/`TIME_SET` filter on `BootReceiver` (re-arm). | After the owner changes zone, the alarm armed for the old instant still fires once at the old time (the run happens then); later due times follow the new zone (`realign` at every pass and graph start). |

Nothing else: no change to Ports.kt, ProductionPorts.kt, TestApp.kt, the manifest or build files is required.

## 3. Intent contract ("Tap to start", rule 23, F12)

- The notification: id 4301, channel `routines`, **no actions**; content intent `PendingIntent.getActivity(…, 7302,
  Intent(context, MainActivity::class.java).addFlags(NEW_TASK | SINGLE_TOP | CLEAR_TOP), IMMUTABLE | UPDATE_CURRENT)`:
  no extra, no action, no data. MainActivity needs no change; nothing in the intent selects a screen. The app shows the
  waiting run through the Dashboard banner (section 2). Never `showWhenLocked`; a lock-screen tap asks Android to unlock.
- The alarm: `PendingIntent.getBroadcast(…, 7301, Intent(context, RoutineAlarmReceiver::class.java).setAction(
  "com.aicontrolcenter.app.action.ROUTINE_ALARM"), IMMUTABLE | UPDATE_CURRENT)`.
- The service start: `Intent(context, ObjectiveWorkService::class.java).setAction(RoutinesWiring.ACTION_RUN_DUE_ROUTINES)`,
  no extra (`RoutineAlarmReceiver.runDueIntent`).

## 4. Tests

Whole suite in the worktree (`./tools/gw --offline test --continue`): 3,123 tests, 0 failed; after the last commit
(e0e2e95: exact-alarm rule, cancellation rethrow, per-routine/per-rule guards) `:framework:test` and
`:app:testDebugUnitTest` were run again in full: **3,124 tests, 0 failed** (app 521, core 165, data 432, device 352,
framework 1,057, memory 107, providers 229, providers-anthropic 89, testkit 172). `:app:assembleDebug` and
`:app:assembleDebugAndroidTest` build. Commits: e44cb1b (framework), 9717906 (app), e0e2e95 (exact alarms, guards).

framework (new, all green): `DefaultOwnerRoutinesContractTest` (3), `DefaultOwnerPickUpRulesContractTest` (9),
`OwnerRoutinesLogTest` (1), `RoutineClockTest` (6), `RoutineRunnerTest` (11), `RoutineRunReachesBrokerTest` (2),
`PickUpRunnerTest` (11): 43.

app (new): `RoomOwnerRoutinesContractTest` (3), `RoomOwnerPickUpRulesContractTest` (9), `RoutineAndroidTest` (8),
`RoutinesAssemblyTest` (5), `WaitingRoutinesBannerTest` (2), `PickUpStepTest` (2).

Secfix tests required of WP17, where each is: runner tests (rule made, folder then made writable, a run imports nothing,
`lastResult` is the sentence, rule off; master switch off; folder removed) = `PickUpRunnerTest` + `PickUpStepTest`; a
picked-up memory has origin IMPORT and is recalled framed as data = `PickUpRunnerTest` (AutoRecall: the file's words
only on quoted lines inside `<recalled_memories id=…>`); `routine.objectiveOptions()` reaches the broker (without the
tick a screen tool is refused, nothing asked; the routine's time-out is the request's) = `RoutineRunReachesBrokerTest`
+ `RoutinesAssemblyTest` (stored on the real orchestrator's objective); the waiting notification has no actions and
opens `MainActivity` without extras, `RoutineAlarmReceiver.onReceive` starts the service with the action and nothing
else = `RoutineAndroidTest`; the banner appears for a WAITING_FOR_TAP run, Start asks once more and starts exactly that
run, nothing starts without the tap = `WaitingRoutinesBannerTest` + `RoutineRunnerTest`.

## 5. Device tests to run (device gate of wave 2b; no instrumented test written: app/src/androidTest is WP6's)

1. Install, check `adb shell dumpsys package com.aicontrolcenter.hq | grep -A3 RoutineAlarmReceiver` shows it disabled;
   no alarm in `adb shell dumpsys alarm | grep aicontrolcenter`.
2. (Needs WP10's screen or a debug hook) make a routine due in 2 minutes, switch routines on: receivers enabled, one
   alarm listed (exact only after "Alarms and reminders" is allowed on Android 14+). Screen off,
   `adb shell dumpsys deviceidle force-idle`: the objective starts within a minute of its time (exact alarm), its
   objective shows "Started by routine" options (screens off unless ticked).
3. With "Alarms and reminders" off: the alarm is inexact; when it fires the "A routine is waiting to start"
   notification appears (no buttons) and nothing starts, even with the battery exemption; tapping it opens the app; the
   banner shows; Start asks once more; nothing started before that.
4. `adb reboot` with a routine: after boot `dumpsys alarm` lists the alarm again; nothing started at boot.
5. Folder pick-up: share a folder, make it read-only, make a rule, switch folder pick-up on, put a .txt file in it,
   `adb shell cmd jobscheduler run -f com.aicontrolcenter.hq <upkeep job id>` (WP2's job): the memory appears in the
   space as "Imported from outside this app", source "Picked up from <folder>". Make the folder writable: the rule
   switches off with the sentence.

## 6. Notes for wave 3 (screens WP9, WP10, WP11a, WP11b; WP8)

- **WP10 Routines screen**: `graph.ownerRoutines` (non-null in production and `TestApp`). Form fields (title ≤ 80,
  objective ≤ 4,000, schedule Daily/EveryHours with weekdays, approval time-out 1..240 or "usual", "May use other apps'
  screens" off by default) use `AppTextField(state = …)` with VM-owned `TextFieldState` (rule 27). Show `pausedNote`
  (and "Switch it on again"), `consecutiveFailures`, `nextRunAt`, the master switch (Settings > Routines,
  `routinesEnabled`), and the run history `observeRuns(id, n)` with each run's outcome and `note` (app-written; for
  STARTED, a link to the objective). Outcome words: STARTED "Started", SKIPPED_STILL_RUNNING "Skipped: the last run is
  not finished", SKIPPED_DAILY_LIMIT "Skipped: today's limit", SKIPPED_LOCAL_ONLY "Skipped: local-only mode",
  SKIPPED_DIRECTOR_UNAVAILABLE "Could not start", WAITING_FOR_TAP "Waiting for you", MISSED "Missed". Say that Android
  may delay routines and that "Alarms and reminders" makes them start on time (link `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`
  on Android 12+, from the screen, not from a notification). Routines are Tier B: hide the screen when WP12's round 2
  leaves a critical/major finding open.
- **WP10 Folder pick-up screen**: `graph.ownerPickUpRules`; refusals `FOLDER_NOT_READ_ONLY` (offer "Make it read-only"),
  `FOLDER_OVERLAPS_WRITABLE`, `FOLDER_UNREADABLE`, `FOLDER_GONE`; show each rule's `lastRunAt` and `lastResult` (sentences
  from `PickUpRunner`/`PickUpSafety`), its imports `PickUpRuleRepository.observeImports` (needs a read port: WP8 may expose
  the store's imports to the screen through a small owner-only view); the master switch `folderPickUpEnabled`; say that
  files already in the folder are left out and only text files arrive, 5 MB a day. Not built (optional, would carry
  "(test)"): importing the files seen at switch-on by ticking them.
- **WP9/WP11a**: a routine's objective has `options.routineId`; show "Started by routine '<title>'" (title from
  `ownerRoutines.observeAll()`), and "Screens: not allowed" when `screenActionsAllowed` is false.
- **WP8**: section 2's rows; the Dashboard banner; consider showing the "waiting to start" count in the attention list.
- **WP12 attack list**: a routine whose objective text asks the team to do something consequential (the broker still
  asks); a folder made writable and back between runs (covered); a file named like a memory heading (memory text is
  neutralised and quoted on recall); flooding the folder (5 MB/day, 1 MB/file, text only).

## 7. Deviations from the plan (and why)

1. **Due times while the master switch is off are not runs** (no `routine_runs` row): they pass silently, and switching
   the switch on moves stale due times to the next one. Recording a MISSED row for every due time of a switched-off
   feature would fill the history with noise; starting them on switch-on would start work the owner did not expect.
2. **A run more than 30 minutes late does not start by itself** (WAITING_FOR_TAP with a notice; MISSED after 3 hours):
   the plan names WAITING_FOR_TAP only for "Android refuses". A late alarm (Doze, phone off, inexact alarm) starting long
   after its time would surprise the owner; the tap keeps it the owner's decision.
3. **Failures in a row are counted from the run history since the owner's last change** (`Routine.updatedAt`), not with
   an increment per event: an objective's FAILED/TIMED_OUT end is seen at the next due time (and at every pass), so a
   restart never double-counts and switching on gives three fresh tries. The routine therefore pauses at the next due
   time after its third failure, not at the moment the third objective fails.
4. **Without "Alarms and reminders" nothing starts by itself** (the task's "otherwise a Tap to start notification"),
   even where Android would let an inexact alarm start a foreground service (battery exemption): the owner's tap is the
   start then. Appendix A's "Tap to start when Android refuses" is the narrower case; this is stricter, never laxer.
5. **"Tap to start" from the alarm receiver is generic** ("A routine is due…"): the receiver cannot read the routines
   (F11), so the title appears only when the service path records the waiting run. The in-app banner always names it.
6. **BootReceiver re-arms from a remembered time instead of starting the service** (the secfix allowed "a one-off job or
   the same action"): it needs neither the graph nor a foreground service at boot, and starts nothing.
7. **Catch-up happens when the owner opens the app** (the banner's VM; WP8 adds `onAppForegrounded`), not at graph start:
   a graph built by the alarm's own service must not turn the due run into a waiting one before `runDue` starts it.
8. **No app/pickup/ files**: the step is three lines in RoutinesWiring and the runner is pure framework code.

## 8. Still open

- Production folder pick-up reads no folder until the merger applies section 2's `AiControlCenterApp.upkeep` row (and
  WP2's `UpkeepGraph` runs its steps): until then every run stores `FOLDER_GONE` and imports nothing.
- A routine's alarm starts work only once WP2's `ObjectiveWorkService` calls `routinesAssembly.runDue()` for
  `ACTION_RUN_DUE_ROUTINES` (section 2); on this branch alone the service starts, finds nothing running and stops.
- The waiting banner is not placed on any screen yet (wave 3, section 2), and `onAppForegrounded` does not yet call
  `catchUp()`; until then a due time that passed waits only once a screen shows the banner.
- Optional, not built: importing files seen when a rule was switched on, by ticking them (would carry "(test)").
- No instrumented test (app/src/androidTest is WP6's); the device steps are in section 5.
