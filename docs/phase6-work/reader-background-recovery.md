# Phase 6 reader: background execution, advanced autonomy and recovery

Scope: how work runs today, which Android rules (14 to 17) limit long background runs, what "advanced
autonomy" and "longer background runs" should mean inside the owner's rules, and how the app should recover
when things go wrong. Repository read at HEAD d7315fd (0.5.1). Nothing under /home/user/rune-companion was
changed and no build was run.

Evidence tags: **[CODE]** read in this repository (file:line); **[DOC]** official Android documentation fetched
on 2026-11-25 (URLs in section 9); **[SRC]** AOSP source read from the GitHub `aosp-mirror` of
`platform/frameworks/base` `main` (googlesource itself was refused by the proxy; the mirror may lag AOSP);
**[JAR]** checked in a jar in the offline Gradle cache; **[MEM]** from memory, not re-checked here;
**[INF]** my inference from the above, not observed.

---

## 0. Twelve facts that decide the design

1. **A foreground service keeps the process alive, not the CPU awake.** The app holds no wake lock: no
   `WAKE_LOCK` permission in either manifest and no `PowerManager` use anywhere [CODE: app/src/main/AndroidManifest.xml:9-21;
   repo-wide grep]. Google's March 2026 guidance says a partial wake lock is needed "in conjunction with a
   foreground service for the duration of the CPU activity" [DOC blog]. AOSP honours a partial wake lock in Doze
   for a process at foreground-service state (`procState <= PROCESS_STATE_BOUND_FOREGROUND_SERVICE`) [SRC
   PowerManagerService.java `setWakeLockDisabledStateLocked`]. So "Running with the screen off" (STATUS.md:267-268)
   has not been shown to work on a real phone (STATUS.md:320-321 says so too), and is likely to crawl once the
   screen goes off. **Top fix: a partial wake lock held while agents compute.**
2. **Chats get no foreground service.** `ObjectiveWorkController` starts the service only for
   `orchestrator.running` [CODE: ObjectiveWorkController.kt:27-32]. A chat turn runs in the framework scope
   [CODE: DefaultAgentChat.kt:65, 147], so it survives leaving the chat screen, but once the owner leaves the app
   the process is cached. On Android 15+ "apps that start a network request outside of a valid process lifecycle
   receive an exception" [DOC 15-all], and swiping the app from Recents kills a process with no foreground service
   [SRC ActivityTaskSupervisor.java `killTaskProcessesIfPossible`].
3. **Nothing restarts by itself.** The service is `START_NOT_STICKY` [CODE: ObjectiveWorkService.kt:61]. There is
   no boot receiver, no alarm and no job (manifest; grep). Recovery runs only when `MainActivity.onCreate` calls
   `graph.start()` [CODE: MainActivity.kt:20 → AppGraph.kt:516 → AppStartup.kt:119]. "Resume interrupted tasks" is
   off by default [CODE: FrameworkSettings.kt:34].
4. **`specialUse` has no time limit on Android 14 to 17.** Only `dataSync` and `mediaProcessing` (6 h in 24 h,
   target 35+) and `shortService` (about 3 min) are limited [DOC fgs/timeout]. `specialUse` is not on the
   Android 15 list of types a `BOOT_COMPLETED` receiver may not start [DOC 15-target].
5. **Exact alarms may start a foreground service from the background** ("exact alarms aren't affected by
   foreground service launch restrictions") [DOC alarms]. That is the official route for scheduled objectives.
   `SCHEDULE_EXACT_ALARM` is granted by the owner ("Alarms & reminders") and not pre-granted on fresh installs
   targeting 33+ [DOC alarms].
6. **Turning battery optimisation off (owner's choice) is a documented exemption** for starting a foreground
   service from the background [DOC fgs/bg-start], lifts Doze/App Standby network and wake-lock limits [DOC doze],
   and keeps the app out of the "restricted" standby bucket [DOC appstandby]. Google Play's restriction on
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` does not apply to a sideloaded app; Android still shows its own dialog.
7. **Force stop on Android 15+ cancels all pending intents** (alarms included) and keeps the app stopped until the
   owner opens it; `BOOT_COMPLETED` is delivered when it leaves that state [DOC 15-all]. The Task Manager's Stop
   (13+) kills the whole app without a callback, but keeps jobs and alarms [DOC fgs/handle-user-stopping].
8. **Android tells the app why its last process died:** `ActivityManager.getHistoricalProcessExitReasons`
   (API 30) with reasons that include LOW_MEMORY, CRASH, ANR, USER_REQUESTED (Force stop or swiped from Recents),
   PACKAGE_UPDATED (34), and REASON_MEMORY_LIMITER = 17 (SDK 37.2) for the new Android 17 app memory limits
   [DOC ApplicationExitInfo; DOC 17-all]. The app uses none of this today (grep).
9. **A provider outage of a minute can fail a whole objective.** Each model call gets 2 retries at 1 s and 2 s,
   up to 8 s, Retry-After at most 20 s [CODE: RetryPolicy.kt:12-36]; a failed task is queued again at once
   [CODE: WorkSteps.kt:164-165] and has 2 attempts in all by default [CODE: FrameworkSettings.kt:28]. Fallback
   models are tried only when the primary is unavailable by its stored status [CODE: AgentAvailability.kt:41-56],
   and only an authentication error changes a provider's status [CODE: DefaultModelGateway.kt:259-261].
10. **If the database key is lost, the app has no way out.** Every database call throws
    `DatabaseKeyException` [CODE: DataLayer.kt:66-69; DatabaseKeyStore.kt:57]; nothing in `:app` catches it
    (grep); STATUS.md:79-80 says clearing app data is the only recovery. A likely cause: `allowBackup="false"`
    [CODE: AndroidManifest.xml:25] without `dataExtractionRules` does not stop device-to-device transfer on some
    makers' phones for apps targeting 31+ [DOC autobackup], and such a transfer copies the database but never
    `noBackupFilesDir`, where its sealed key lives [DOC autobackup; CODE: DataLayer.kt:61-62].
11. **There is no backup the owner can restore.** The only export is the one-way memory graph JSON
    [CODE: app/.../memory/graph/GraphExportFiles.kt]; uninstalling deletes everything (LIMITATIONS.md:67-68), and
    0.5.0 already forced one uninstall (STATUS.md:409-414).
12. **WorkManager is declared but not available offline.** `libs.versions.toml:16,49` names
    `androidx.work:work-runtime-ktx:2.12.0`, but no module uses it and `~/.gradle/caches/modules-2/files-2.1` has
    no `androidx.work` group [checked]. Everything proposed below uses platform APIs (AlarmManager, JobScheduler,
    PowerManager) and needs no new dependency.

---

## 1. How work runs today

### 1.1 The parts

| Part | What it does | Where |
|---|---|---|
| `AiControlCenterApp` | Holds `appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`, with no `CoroutineExceptionHandler` (none exists in the repo). Builds `AppGraph` lazily; with it starts `ObjectiveWorkController`, `ApprovalNotifier`, `ScreenSessionNotifier`. `onCreate` cancels a stale "operating" notification | AiControlCenterApp.kt:26, 31-36, 44-54 |
| `MainActivity` | `onCreate` → `graph.start()` (start-up steps, idempotent); `onStart`/`onStop` → `onAppForegrounded`/`onAppBackgrounded` | MainActivity.kt:20, 37-46 |
| `AppStartup` | In order: stop shell leftovers; tidy the workspace trash (own coroutine); register the simulator; starter team; starter space; refresh app statuses; `orchestrator.recoverInterrupted()`. Then every 60 s closes overdue requests; memory upkeep at start and every 30 min **only while in the foreground**; provider checks | AppStartup.kt:88-153, 166-192, 254-261 |
| `ObjectiveWorkController` | Starts the service when `running.isNotEmpty() && isInForeground`; catches the refusal and logs "Could not keep objectives running in the background" | ObjectiveWorkController.kt:27-49, 53 |
| `ObjectiveWorkService` | `specialUse` FGS, `START_NOT_STICKY`, ongoing low-importance notification with progress and **Pause** (pauses all), stops itself when nothing runs. It holds the process up; objectives run in the app scope | ObjectiveWorkService.kt:28-42, 56-62, 69-79, 120-123; WorkNotifications.kt:29, 37-57; manifest service with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` |
| `DefaultOrchestrator` | Each objective is a job under a supervisor in the app scope; owner commands write state first, under the objective's lock, then stop jobs | DefaultOrchestrator.kt:43-79, 123, 157-176, 464-499 |
| `ObjectiveDriver` | Re-reads the store before every decision; heartbeat every 60 s stores `updatedAt` and the moved deadline, so time left survives a crash | ObjectiveDriver.kt:28-67, 78-117, 722 |
| `withPausableTimeout` / `WaitClock` | Task, objective and run limits stand still while a call waits for the owner; the stored deadline moves with it | WaitClock.kt:19-60, 125-157; ObjectiveDriver.kt:48-53, 125-130, 163-170; AgentRunner.kt:71-73, 116-124 |
| `DefaultAgentChat` | A turn runs in the framework scope; everything is stored the moment it exists (owner message first) | DefaultAgentChat.kt:63-86, 142-163, 205-210 |
| `recoverInterrupted` | Withdraws every request no live call waits for; marks WORKING tasks INTERRUPTED (the run counts as an attempt; none left → FAILED); planning interrupted too often → objective FAILED; "idle" interruptions capped at 3 in a row; bookkeeping-only objectives finished either way; others resumed only with the setting on | DefaultOrchestrator.kt:352-441, 452-456, 505-514, 549 |
| Approvals | High-importance notification that only opens the app, private on the lock screen, removed at expiry; 30 min time-out by default (5 to 240) | ApprovalNotifications.kt:45, 55-79; FrameworkSettings.kt:32; OrchestrationLimit.kt:62 |

Default limits [CODE: FrameworkSettings.kt:18-34]: 2 tasks at once, 24 model calls and 40 tool calls per task,
2 attempts, 15 min per task, 90 min per objective (settable 15 to 480: OrchestrationLimit.kt:58), auto-resume off.

### 1.2 What happens to each kind of work

"Objective" means an objective with the service running (started while the app was on screen).

| Event | Objective | Chat turn | Memory upkeep | Waiting approval | Shell command | Screen session |
|---|---|---|---|---|---|---|
| Owner leaves the app, screen on | Goes on (FGS) [CODE] | Goes on while the process is not frozen; on Android 15+ new requests can fail [DOC 15-all]; not tried on a device | Loop stops [CODE: AppStartup.kt:188-192] | Waits; notification [CODE] | Goes on (in process) | Goes on; accessibility binding lifts the process while the screen is on [earlier research, android-phone-operator.md §0.3, SRC there] |
| Screen off, phone still | Process alive; CPU can sleep (no wake lock) [DOC blog; CODE] → streams and timers stall [INF] | As above, worse | — | Waits | Stalls with the CPU [INF] | Screen calls refused at the lock screen (protected) [LIMITATIONS.md:34] |
| Doze (screen off, still, unplugged for a while) | FGS keeps network access [DOC power-details: "App process is running a foreground service — Network: No restrictions"]; a wake lock would be honoured [SRC]; none is held | Network suspended [DOC doze] | — | Waits | — | — |
| Swiped away from Recents | AOSP keeps a process that has a foreground service [SRC]; some makers kill anyway [MEM] | Process killed [SRC] → turn lost | — | Closed at next start | Killed; leftovers swept at next start [CODE: AppStartup.kt:91-104] | Ends |
| Task Manager "Stop" (13+) | Whole app killed, no callback; jobs and alarms kept [DOC] | Lost | — | Closed at next start | As above | Ends |
| Force stop | Killed; on 15+ pending intents cancelled, app stays stopped [DOC 15-all]; accessibility service switched off [STATUS.md:500-501] | Lost | — | Closed | As above | Ends; service off |
| Low-memory kill, crash, update | Killed. At the next **owner-opened** start: tasks INTERRUPTED, attempt counted, time left from the last heartbeat; resumed only if "Resume interrupted tasks" is on [CODE] | Owner's message and whatever arrived stay; **no notice that the reply was cut off** (no start-up step for chats: AppStartup.kt:90-120) | — | Withdrawn as "No longer waiting" [CODE: OperatorTaskHooks.kt:43-44] | As above | Stale notification cancelled [CODE: AiControlCenterApp.kt:31-36] |
| Reboot | Nothing runs until the owner opens the app; tasks stay RUNNING in the database until then [CODE] | Lost | — | Closed | Swept at next start | — |

### 1.3 Gaps found (all verified in code unless tagged)

- **G1 No wake lock** (fact 1).
- **G2 Chats unprotected** (fact 2).
- **G3 Cut-off chat replies are silent.** `answer()` stores the owner's message first [DefaultAgentChat.kt:205-210];
  nothing at start-up adds the error entry that `storeError` [DefaultAgentChat.kt:401] would show.
- **G4 Only the activity runs start-up.** A process started by anything else (the system re-binding the enabled
  accessibility service after boot, a notification action) builds the graph but never runs recovery
  [MainActivity.kt:20 is the only caller of `graph.start()`; grep]. Harmless today, but routines and boot resume
  need a non-UI entry point that calls `AppGraph.start()` and `awaitStartup()` (AppGraph.kt:516, 537).
- **G5 The owner is not told that work stopped or finished.** The ongoing notification is removed with the service
  (`STOP_FOREGROUND_REMOVE`, ObjectiveWorkService.kt:120-123); there is no "finished", "failed" or "interrupted"
  notification. After a kill the owner sees "Interrupted: the app stopped while this was running."
  (DefaultOrchestrator.kt:542) only when they open the app, without the reason.
- **G6 Two clocks for one limit [INF].** In-run limits use `TimeSource.Monotonic` (WaitClock.kt:134,
  AgentRunner.kt:100, DefaultOrchestrator.kt:98; AppGraph passes none), which on Android is `System.nanoTime`, a
  clock that stops in deep sleep [MEM], while the objective's stored deadline and its checks use wall time
  (ObjectiveDriver.kt:123, 164). After the CPU slept, the next step can find the wall deadline already past. A
  wake lock (A1) makes this rare; a time source built on `SystemClock.elapsedRealtimeNanos()` makes it consistent.
- **G7 No exception handler on the app scope** (AiControlCenterApp.kt:26). The orchestrator and start-up catch
  their own (ObjectiveDriver.kt:78-90; AppStartup.kt:226-234), but an exception escaping any other collector
  launched in `appScope` (ApprovalNotifier, ScreenSessionNotifier, ObjectiveWorkController) ends the process
  [MEM: standard coroutine behaviour on Android].
- **G8 No exit diagnosis, no crash note** (fact 8).
- **G9 Provider trouble is not absorbed** (fact 9). Also STATUS.md:158-159: after a failed check, agents stay
  blocked until something checks again.
- **G10 No recovery from an unopenable database** (fact 10); the error cannot even be logged, because the activity
  log is in the same database (AppStartup.kt:236-249 swallows it).
- **G11 Device transfer can break the app** (fact 10).
- **G12 No backup or restore** (fact 11).
- **G13 No free-space checks** beyond the device report (device/report/AndroidDeviceInspector.kt:75-76); the
  workspace has its own 2 GB cap (LIMITATIONS.md:129-134).
- **G14 Background-health states are invisible:** battery optimisation, "background restricted", standby bucket
  and exact-alarm permission are never read (grep: no `PowerManager`, `isBackgroundRestricted`,
  `getAppStandbyBucket`, `canScheduleExactAlarms`).
- **G15 Memory upkeep and activity pruning run only while the app is open** (AppStartup.kt:166-192;
  STATUS.md:230-231).
- **G16 An interrupted task restarts from its brief** ("Resumed; restarts from its brief.",
  DefaultOrchestrator.kt:198), so every model call it had made is paid again.

---

## 2. Android rules that bound long background runs (14, 15, 16, 17)

The app targets 36 and compiles against 37.2 (LIMITATIONS.md:12). "Sideload" says whether a personal,
sideloaded build may use it; Google Play policies do not apply, the platform's rules do.

| Rule | Versions | Effect here | Sideload | Source |
|---|---|---|---|---|
| FGS needs a declared type and its permission | 14+ (target 34+) | Present: `specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE` | yes | DOC; CODE manifest |
| `specialUse`: no time limit | 14 to 17 | Long objectives are fine as an FGS | yes | DOC fgs/timeout ("Currently, this restriction only applies to dataSync and mediaProcessing") |
| `dataSync`, `mediaProcessing`: 6 h per 24 h, then `onTimeout(int,int)`, "a few seconds" to `stopSelf()`; timer resets when the owner opens the app | 15+ (target 35+) | Only for bounded transfers (model download, backup upload) | yes | DOC fgs/timeout |
| `shortService`: about 3 min | 14+ | Not useful for agent runs | yes | DOC (earlier research §3.1) |
| No FGS start from the background, except: leaving a visible state; app may start activities from the background; user action on a notification/widget; **exact alarm**; BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED; time/locale change; **battery optimisation off**; SYSTEM_ALERT_WINDOW **with a visible overlay** (target 35+); device owner; companion device | 12+ (target 31+) | Routines need the exact-alarm or battery exemption, else a "tap to start" notification | yes | DOC fgs/restrictions-bg-start |
| BOOT_COMPLETED may not start `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone` | 15+ (target 35+) | `specialUse` may be started at boot | yes | DOC 15-target |
| Background network requests outside a valid process lifecycle fail (`UnknownHostException`, other `IOException`) | 15+ (all apps) | Chats after leaving the app need the FGS | — | DOC 15-all |
| Force stop cancels all pending intents; app stays stopped until the user acts; `ApplicationStartInfo.wasForceStopped()` | 15+ (all apps) | Alarms must be rebuilt on every start and on BOOT_COMPLETED | yes | DOC 15-all |
| Task Manager Stop kills the app, no callback; jobs and alarms kept; `REASON_USER_REQUESTED` | 13+ (all apps) | Recovery at next start; tell the owner why | — | DOC fgs/handle-user-stopping |
| Doze: network suspended, wake locks ignored, alarms deferred except `setAndAllowWhileIdle` / `setExactAndAllowWhileIdle` (at most one per 9 min) / `setAlarmClock`, jobs deferred | 6+ | FGS process: network not restricted [DOC power-details]; wake lock honoured at FGS state [SRC] | — | DOC doze; SRC |
| Battery optimisation allowlist: `isIgnoringBatteryOptimizations`, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), or `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | 6+ | Network and partial wake locks in Doze; FGS-start exemption; out of the restricted bucket | yes (Play restricts it; not relevant) | DOC doze, bg-start, appstandby |
| Exact alarms: `SCHEDULE_EXACT_ALARM` (owner grants, revocable, not pre-granted for target 33+ fresh installs; `canScheduleExactAlarms()`, `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, broadcast `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`) or `USE_EXACT_ALARM` (auto-granted, not revocable, for alarm/calendar apps under Play policy) | 12+/13+ | Use `SCHEDULE_EXACT_ALARM`: the owner decides and can revoke | yes | DOC alarms |
| Alarms are cleared at shutdown; re-register on `BOOT_COMPLETED` (works only after the app was launched once) | all | Boot receiver must reschedule routines | yes | DOC alarms |
| Standby buckets: active (activity, long-running FGS, notification tap), working set, frequent, rare (network disabled for jobs), restricted (13+: after 8 days without interaction; jobs once a day for 10 min, one alarm a day). Exempt: apps the user set "unrestricted", apps holding `USE_EXACT_ALARM`; buckets do not apply while charging (except restricted) | 9+ / 12+ / 13+ | A routine the owner never opens drifts to "restricted" unless battery is unrestricted | — | DOC appstandby, power-details |
| Job quotas now also bind active-bucket jobs, jobs started while visible, and jobs running alongside an FGS; WorkManager long-running workers can exhaust them ("try launching the foreground service directly instead") | 16+ (all apps) | Keep objectives in our own FGS, not in WorkManager | — | DOC 16-all; DOC long-running workers |
| User-initiated data transfer jobs (`setUserInitiated(true)`, `RUN_USER_INITIATED_JOBS`, network constraint, notification via `setNotification`; schedulable only while visible or when the app may start activities from the background) | 14+ | Only for transfers the owner starts (model download, backup upload); not for agent runs | yes | DOC uidt |
| App memory limits by device RAM; exit reason `REASON_OTHER` with "MemoryLimiter:AnonSwap", and `REASON_MEMORY_LIMITER` = 17 (SDK 37.2) | 17 (all apps) | A runaway script can now get the app killed; recovery must recognise it | — | DOC 17-all; DOC ApplicationExitInfo |
| `ApplicationExitInfo` (`getHistoricalProcessExitReasons`, `setProcessStateSummary`, `getTraceInputStream` for ANR and, from 31, native crashes) | 30+ (11+) | Minimum is 28: Android 9 and 10 give no reason | yes | DOC ApplicationExitInfo |
| `ACCESS_LOCAL_NETWORK` runtime permission | 17 (target 37+) | Not yet (target 36), but LAN model servers break the day the target moves to 37. Cross-area note for the providers reader | — | DOC 17-target |
| `allowBackup="false"` may not stop device-to-device transfer (target 31+, some makers); with no `<device-transfer>` rules everything but `no_backup` and `cache` moves | 12+ | Fact 10 | — | DOC autobackup |
| Partial wake lock needed alongside an FGS for CPU work with the screen off; Play's "excessive partial wake locks" treatment (2 h average with the screen off) affects only Play listing | — | Hold one only while agents compute | yes | DOC blog, Play vitals |

Not for this app: FCM (needs a server and sends data off the device), companion-device permissions (would be a
false claim), `systemExempted` FGS, `SYSTEM_ALERT_WINDOW` as a start loophole (restricted settings on sideloaded
apps, LIMITATIONS.md:69-76, and needs a visible overlay from 15).

---

## 3. Advanced autonomy and longer background runs

What it should mean for this owner: **work the owner started, or scheduled themselves, keeps going while the phone
is in a pocket; the owner is called back only for decisions; nothing consequential happens without them; they
can see afterwards exactly what happened and what it cost.** It must not mean agents starting work nobody asked
for, or acting on other apps' screens while nobody watches.

### A1. Keep working with the screen off (wake lock while computing)

- **Mechanism.** New `app/work/WakeHold.kt`: one `PowerManager.PARTIAL_WAKE_LOCK` tagged `aicc:agents`, acquired
  with a timeout (10 min, renewed while needed) by `ObjectiveWorkService` while it is in the foreground and
  something computes; released when nothing runs, when every running task is `WAITING_FOR_OWNER` (no CPU needed;
  the approval notification calls the owner) and in `onDestroy`. Manifest: `android.permission.WAKE_LOCK` (normal
  permission). The ongoing notification says "Keeping the phone awake while agents work".
- **Also (G6).** Pass a `TimeSource` built on `SystemClock.elapsedRealtimeNanos()` into `DefaultOrchestrator`,
  `AgentRunner` and `withPausableTimeout` from `AppGraph`, so limits count real time like the stored deadline.
- **Risk.** Battery drain; bounded by the objective limit (max 8 h) and by releasing it while only waiting.
- **Tests.** Robolectric: `ShadowPowerManager.getLatestWakeLock()` held/released as `running` and task statuses
  change [JAR: present in shadows-framework-4.18.1]. JVM: the elapsed-realtime time source with the existing
  pausable-limit tests. Emulator: `adb shell dumpsys power | grep aicc` while an objective runs with
  `input keyevent KEYCODE_SLEEP`. The emulator does not deep-sleep like a phone, so the benefit itself is only
  testable on the owner's phone.

### A2. Chats keep going when the owner leaves the app

- **Mechanism.** `ObjectiveWorkController.shouldRun` becomes
  `(running.isNotEmpty() || liveTurns.isNotEmpty()) && inForeground` (combine `AgentChat.liveTurns`); the
  notice shows "<agent> is answering" with **Stop** for a chat (cancels that turn: `AgentChat.cancel`). Same
  service; widen the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text to "the owner's agents answering or working on an
  objective".
- **Risk.** None new; a chat turn has the task time limit (AgentRunner.kt:116-124).
- **Tests.** Pure `shouldRun` and `WorkNotice` tests (existing pattern); Robolectric service test; emulator: send a
  chat to the scripted model server used in Phase 5 (STATUS.md:436), press Home, check the reply arrives.

### A3. "Keep working in the background" check (Settings > Device, and the New objective form)

- **Shows, in plain words:** notifications allowed; battery optimisation on or off
  (`PowerManager.isIgnoringBatteryOptimizations`); "Android restricts this app in the background"
  (`ActivityManager.isBackgroundRestricted()`, API 28); a warning only when the standby bucket is rare or
  restricted (`UsageStatsManager.getAppStandbyBucket()`, API 28); "Alarms & reminders" for routines
  (`AlarmManager.canScheduleExactAlarms()`, API 31; always true below 31).
- **Buttons** open Android's own dialog or page: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with
  `package:` URI (permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`,
  `ACTION_APPLICATION_DETAILS_SETTINGS`. The owner decides there; the operator is already forbidden from driving
  Settings (ARCHITECTURE.md:238-240). Plus one line on maker battery savers ("On some phones, also allow the app
  in the phone's own battery manager"), no maker-specific automation.
- **Owner rules.** Official, user-granted settings; not a bypass.
- **Tests.** Robolectric: `ShadowPowerManager.setIgnoringBatteryOptimizations`,
  `ShadowActivityManager.setBackgroundRestricted`, `ShadowAlarmManager.setCanScheduleExactAlarms` [JAR]. The
  emulator image has no Settings app (STATUS.md:473-474), so the dialogs cannot be shown there; use
  `adb shell cmd deviceidle whitelist +<pkg>` and `adb shell appops set <pkg> SCHEDULE_EXACT_ALARM allow` [MEM].

### A4. Pick up again after a restart (opt-in)

- **Mechanism.** `app/work/BootReceiver` for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` (`RECEIVE_BOOT_COMPLETED`),
  declared `android:enabled="false"` and switched on with `setComponentEnabledSetting` only while the owner has
  "Resume interrupted tasks" and a new "Also after the phone restarts" on, or has routines [DOC alarms pattern].
  It starts `ObjectiveWorkService` with `ACTION_RECOVER`; the service calls `graph.start()` and `awaitStartup()`
  (fixes G4) and stops itself if nothing resumed. Not `LOCKED_BOOT_COMPLETED`: the database is in
  credential-protected storage [MEM]. With resuming off, it only posts "2 objectives were interrupted when the
  phone restarted. Open the app to resume them."
- **Risk.** Cloud calls start without the owner looking at the phone; mitigated by opt-in, the visible
  notification, daily limits (A7). A force-stopped app gets no boot broadcast until opened (fact 7): say so.
- **Tests.** Robolectric receiver → `ShadowApplication.getNextStartedService()`. Emulator:
  `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED <pkg>` [DOC 15-target testing], one real
  `adb reboot` (about 7 min boot).

### A5. Routines: objectives that run on a schedule

- **Model (`:core`).** `Routine(id, title, objectiveText, options: ObjectiveOptions, schedule: RoutineSchedule,
  enabled, maxRunsPerDay, approvalTimeoutMinutes?, allowScreenActions = false, createdAt, nextRunAt,
  consecutiveFailures)`; `RoutineSchedule` = daily at a time on chosen weekdays, or every N hours (N ≥ 1), in the
  owner's time zone. Ports `RoutineRepository`, owner-only `OwnerRoutines` (kept out of agent reach by the
  architecture tests, like `OwnerRequests`). `ObjectiveOptions` gains `routineId: RoutineId?` so the record says
  "Started by routine 'Morning news'".
- **Storage (`:data`, schema 6).** Tables `routines` and `routine_runs(id, routine_id, due_at, started_at,
  objective_id, outcome)`; outcome ∈ STARTED, SKIPPED_STILL_RUNNING, SKIPPED_DAILY_LIMIT, SKIPPED_LOCAL_ONLY,
  SKIPPED_DIRECTOR_UNAVAILABLE, WAITING_FOR_TAP, MISSED (phone off). Contract suite in `:testkit`.
- **Scheduling.** `framework/routines/RoutineClock` (pure, JVM): next due time from schedule, zone and now,
  including daylight-saving gaps. `app/work/RoutineAlarms`: one alarm for the earliest due routine,
  `setExactAndAllowWhileIdle(RTC_WAKEUP, …)` when `canScheduleExactAlarms()`, else `setAndAllowWhileIdle`
  (inexact). Rebuilt at every app start, on BOOT_COMPLETED, on
  `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, `ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED`,
  `MY_PACKAGE_REPLACED`.
- **Firing.** `RoutineAlarmReceiver` → `startForegroundService(ObjectiveWorkService, ACTION_RUN_ROUTINE)`
  (allowed: exact alarm, or battery exemption). The service runs start-up, then `RoutineRunner.fire()` checks the
  rules below and calls `orchestrator.startObjective`. If Android refuses the start (inexact alarm, no exemption),
  it posts "Routine 'Morning news' is due. Tap to start it." (a notification tap is an exemption) and records
  WAITING_FOR_TAP.
- **Limits and approvals.** Master switch Settings > Routines, off after installing. At most 10 routines; at least
  1 h apart; runs per day per routine 1 by default (max 24); never two runs of one routine at once; a routine
  pauses itself after 3 failed runs in a row and says so; daily limits (A7) and local-only mode are checked at run
  time; the creation form shows what the New objective form shows (models, where text goes, agents that cannot run:
  STATUS.md:241-244) plus the permissions the team holds "always" (read through `GrantLookup`). The broker is
  unchanged: missing permissions are asked, consequential actions are approved every time, unanswered requests are
  refused at the time-out (a routine may choose a longer one, up to 240 min). Screen actions are refused in
  routine runs unless the owner ticked "May use other apps' screens" for that routine (off by default): nobody may
  be watching, and the lock screen blocks them anyway. Each run posts a notification and an activity entry.
- **Risk.** Surprise cost and data leaving the phone (mitigated: switch, visible runs, daily limits, away report);
  delayed or dropped alarms (Doze, restricted bucket, makers' killers, force stop): recorded as MISSED and shown.
- **Tests.** JVM: `RoutineClock` (DST, time-zone change, weekday sets), `RoutineRunner` rules on in-memory ports.
  Robolectric: `ShadowAlarmManager.getNextScheduledAlarm()`, `setCanScheduleExactAlarms(false)` path, receiver →
  service, the tap-to-start notification. Emulator: a routine due in 2 minutes with the screen off and
  `adb shell dumpsys deviceidle force-idle`; `adb shell dumpsys alarm | grep aicontrolcenter`.

### A6. "While you were away" and result notifications

- **Report.** `framework/report/AwayReport` (pure) built from `ActivityLog`, finished/failed/interrupted
  objectives, requests closed as expired or withdrawn, egress entries, usage counters (A7) and exit reasons (R1)
  since `lastSeenAt` (stored in the settings DataStore at `onAppBackgrounded`, AppGraph.kt:593-596). Dashboard card
  "While you were away" when the owner was gone more than 10 minutes and something happened: "Finished: 2
  objectives. Needs you: 1 request expired unanswered. Sent to xAI: 14 times. Android closed the app once to free
  memory." Each line opens its screen.
- **Notifications.** New channel "Results" (default importance): "Objective finished", "Objective stopped: <why>",
  "Interrupted: <why>" when the app is not on screen; private on the lock screen with a public version (as
  ApprovalNotifications.kt:56-70 already does). Approval notifications gain one reminder 5 minutes before expiry.
  Notifications keep only opening the app; no answer from a notification (owner rule, STATUS.md:365-366).
- **Tests.** JVM report builder; Robolectric `ShadowNotificationManager`; screenshot tests for the card.

### A7. Daily limits

- **Mechanism.** Settings "Model calls per day" and "Tokens per day" (0 = no limit) in `FrameworkSettings`; port
  `UsageLedger` in `:core`; Room table `usage_days(day TEXT, provider_id TEXT, model_calls, input_tokens,
  output_tokens, PRIMARY KEY(day, provider_id))` written by `ModelCallJournal` for every attempt that went out;
  checked by `DefaultModelGateway` before every attempt, where it already re-checks local-only mode and the
  provider (DefaultModelGateway.kt:37-39, 73, 101, 193-199). A refusal is `LlmException.BlockedByPolicy("Today's
  limit of 300 model calls is reached. It starts again at midnight, or you can raise it in Settings.")`, which
  reaches the owner as a "Limit reached" message like the other limits. On-device providers are counted but not
  limited. Dashboard tile "Used today". No money estimate: providers do not report prices, and a guessed figure
  would mislead.
- **Tests.** JVM gateway tests with a fake clock crossing midnight; Room contract suite.

---

## 4. Recovery

### R1. Know why the app stopped

- `device/health/ExitHistory`: `getHistoricalProcessExitReasons(packageName, 0, 5)` (API 30+), each exit handled
  once (last-seen timestamp in DataStore); `ApplicationStartInfo.wasForceStopped()` on 35+. Plain texts: LOW_MEMORY
  "Android closed the app to free memory"; USER_REQUESTED "The app was stopped from Android (Force stop, the list
  of running apps, or swiped away)"; CRASH / CRASH_NATIVE "The app stopped because of an error in the app"; ANR
  "Android closed the app because it stopped responding"; PACKAGE_UPDATED "The app was updated"; MEMORY_LIMITER
  (37.2) or OTHER with "MemoryLimiter" "The app used more memory than Android allows"; PERMISSION_CHANGE "A
  permission was changed in Android's settings"; Android 9 and 10: "Android 9 and 10 do not say why".
- `ActivityManager.setProcessStateSummary(bytes)` updated when a step starts (objective id, task id, tool id), so
  the next start can say which step was running. Its size cap is small [DOC; exact cap not checked].
- Used by: the interrupted objective's status ("Interrupted: Android closed the app to free memory while 'Find
  sources' ran"), the away report, the activity log.
- **Tests.** Robolectric `ShadowActivityManager.addApplicationExitInfo` / `ApplicationExitInfoBuilder` [JAR].
  Emulator: `am kill <pkg>` (in background), `am force-stop`, `am crash <pkg>` [MEM],
  `cmd activity stop-app <pkg>` [DOC].

### R2. Crash loops: a safe start

- Today per-task loops are already bounded: an interrupted run is an attempt (DefaultOrchestrator.kt:505-514),
  idle restarts are capped at 3 (DefaultOrchestrator.kt:401, 549), planning interruptions fail the objective
  (DefaultOrchestrator.kt:389-395). Not bounded: a crash during start-up or in the UI, and crashes outside tasks.
- `app/health/StartLedger` in a small file under `noBackupFilesDir` (not the database, which may be the cause):
  each start writes "started"; the end of `AppStartup` writes "ok". Three crash-type exits (R1) within 15 minutes,
  or two starts in a row without "ok", put the next start in **safe start**: `recoverInterrupted` runs with
  auto-resume forced off (new parameter), routines do not fire, and a banner says "The app stopped several times,
  so agents were not restarted" with "Resume work", "Keep everything paused", "Save a backup file", "Details".
- `CoroutineExceptionHandler` on `appScope` (G7): records to the activity log when it can, keeps the process for
  `Exception`, rethrows `Error`. `Thread.setDefaultUncaughtExceptionHandler` chained to the previous one writes a
  crash note (exception class, first 20 frames of `com.aicontrolcenter`, no message text, which may hold user
  content) to `noBackupFilesDir/crash/`; Settings > Device can show and share it (owner's action).
- **Tests.** JVM ledger rules; Robolectric start in each ledger state; emulator `am crash` three times.

### R3. Chat replies cut off by a stop

- Mark a turn as running (DataStore set of conversation ids, or a `turn_started_at` column on `conversations`)
  when `DefaultAgentChat.send` starts it, clear it when the turn ends (DefaultAgentChat.kt:181-200). A start-up
  step before recovery adds, for each left-over mark, an entry through `storeError`: "The app stopped before
  <agent> finished answering. Send your message again to try again." The chat offers "Send again", which re-runs
  the last owner message without storing it twice.
- **Tests.** JVM: `DefaultAgentChat` with its scope cancelled mid-turn, then a new instance; Robolectric journey.

### R4. A provider that keeps failing

- **Wait before the next attempt** when a task failed on a retryable model error: `WorkSteps.failed`
  (WorkSteps.kt:159-177) stores `retryAt` on the task (schema 6 column), 30 s then 2 min; the scheduler skips the
  task until then; the status says "Waiting 2 minutes: xAI did not answer".
- **Notice an outage.** In `DefaultModelGateway.fail`, three final retryable failures from one provider within
  5 minutes store status OFFLINE with "Not answering since 10:42", and `ProviderManager` re-checks it after
  1, 2, 5, 10 minutes while an agent wants it. `resolveAvailability` then moves to fallbacks by itself
  (AgentAvailability.kt:51-55), and agents come back on their own after a successful check (fixes STATUS.md:158-159).
- **Fallback during a run.** When a step ends FAILED on a retryable error and the agent has a fallback that is
  ready and **no further from the phone** (on device < local network < cloud), `StepRunner` runs the step once
  more on it and the result says "answered by <fallback>" (the flag already exists). A fallback further away is
  used only if the owner saw it on the objective form; never one that local-only mode forbids.
- **Tests.** JVM: `ModelGatewayTest`-style mock server returning 503s; `ObjectiveEndToEndTest` with scripted
  failures; availability tests for the automatic recheck.

### R5. The database cannot be opened

- Detect at the composition root before the shell: open the database on IO and read one row; classify
  `DatabaseKeyException` (key missing, damaged, keystore error), SQLCipher "file is not a database", and a failed
  migration (`AiccMigrations.all`, AiccDatabase.kt:132; there is no destructive fallback, correctly).
- Show a **Recovery screen** instead of the shell, plain words per cause: "This phone cannot unlock the app's
  data. This happens when the data was copied from another phone, or when Android lost the app's key." Choices:
  "Restore from a backup file" (R7); "Start over" (moves the database and sealed key to
  `noBackupFilesDir/broken-<date>/`, asks twice with a typed word, since it deletes important data); "Close and try
  later" (for a keystore that is only briefly unavailable). Settings in DataStore survive; `directorAgentId` is
  cleared on start over.
- **Safety copy before an upgrade:** when the stored schema is older than `AiccDatabase.VERSION`
  (AiccDatabase.kt:100), copy the database file (same key) to `noBackupFilesDir/before-v6/` before Room migrates;
  keep one copy; offer "Go back to the copy" on the Recovery screen if the migration fails.
- **Tests.** Robolectric with an injected failing open-helper factory; instrumented tests on the emulator with the
  real keystore (the existing set: STATUS.md:51, 433): delete the sealed key, start, see the Recovery screen.

### R6. Stop device transfers from breaking the app

- Add `android:dataExtractionRules="@xml/data_extraction_rules"` with `<cloud-backup>` and `<device-transfer>`
  that exclude every domain, and `android:fullBackupContent="@xml/backup_rules"` excluding all for Android 11 and
  lower [DOC autobackup]. A new phone then starts clean, and the owner moves data with R7.
- **Tests.** A unit test over the merged manifest and XML (pattern: `QueriesManifestTest`,
  device/build.gradle.kts:23); a real D2D transfer cannot be simulated here.

### R7. A backup file the owner keeps

- **What.** "Save a backup file" in Settings: one encrypted file, written where the owner picks with
  `ACTION_CREATE_DOCUMENT`; "Restore from a backup file" with `ACTION_OPEN_DOCUMENT`, offered at first start, on
  the Recovery screen and in Settings.
- **Format `aicc-backup` v1.** Header (magic, format version, schema version, app version, created at, KDF
  parameters, salt) and an AES-256-GCM body in chunks of 1 MB, each with its own nonce and the chunk number as
  associated data so chunks cannot be reordered. Key from the owner's passphrase with `PBKDF2WithHmacSHA256`
  (platform `SecretKeyFactory`) at a high iteration count (600,000 is the current OWASP figure [MEM]); passphrase
  entered twice, at least 10 characters, never stored. Body: zip of JSON: agents and stats, providers **without
  keys**, models, AI app registrations, spaces with access, memories with links, provenance and history
  (embeddings are recomputed), settings with both operator switches off, and optionally conversations, finished
  objectives and the workspace (size shown before saving).
- **Never in the file, never restored:** API keys (a key goes only to the address it was entered for:
  STATUS.md:92-94; the owner enters it again), permission grants, apps authorised for screen control, shared
  folders (their grants belong to one install), requests, the activity log.
- **Restore** into an empty app, or "Replace everything" after an automatic backup of the current data, with a
  confirmation. The file is untrusted input: every record is validated like owner input, and nothing in it can
  grant a permission.
- **Owner rules.** It is the owner's own action and the file is encrypted, so it is allowed in local-only mode,
  like sharing a workspace file (LIMITATIONS.md:313-315), and logged. A reminder "Last backup: 23 days ago" on the
  Dashboard, no automatic upload.
- **Tests.** JVM: round trip, tampered chunk, reordered chunks, wrong passphrase, older format; Robolectric: Room
  round trip with `DatabaseEncryption.None`; emulator: save, uninstall, install, restore (whether the image has
  Android's document picker is not verified; an instrumented test can write to a file URI directly).

### R8. Storage nearly full

- `device/health/StorageWatch`: `StorageManager.getAllocatableBytes(getUuidForPath(filesDir))` (API 26) at start,
  before an objective or routine starts, and every 5 minutes while one runs. Under 500 MB: a Dashboard warning.
  Under 100 MB: new objectives and routines are refused with the reason, running ones pause ("Paused: the phone
  is almost full"), workspace writes are refused. Button: Android's `StorageManager.ACTION_MANAGE_STORAGE`. Settings
  > Device lists the app's own use (database, workspace with trash, cache) with "Empty the trash" (exists),
  "Delete activity older than…", "Delete finished objectives older than…".
- **Tests.** Robolectric `ShadowStorageManager`, `ShadowStatFs` [JAR]; JVM thresholds.

### R9. Upkeep without the app open (G15)

- A daily `JobScheduler` job (platform API; no WorkManager needed): `setRequiresDeviceIdle(true)`,
  `setRequiresBatteryNotLow(true)`, `setPeriodic(24 h)`, `setPersisted(true)`; runs `MemoryService.maintain()`,
  activity pruning, the routine alarm rebuild and the storage check. Bounded by job quotas, which is fine for this.
- **Tests.** Robolectric `ShadowJobScheduler` [JAR]; emulator `adb shell cmd jobscheduler run -f <pkg> <id>` [DOC uidt].

### R10. Continue an interrupted task instead of restarting it (optional, larger)

- Today a resumed task restarts from its brief (DefaultOrchestrator.kt:198), paying again for every model call.
  Option: when its stored transcript has at least one finished model turn, resume with it plus a framed note: "The
  app stopped during this task. A tool call without a result may or may not have happened: check before doing it
  again." Tool calls left without a result get that text as their result. Consequential actions still ask.
- **Risk.** Repeating a non-consequential action (a file written twice, a link opened twice). **Tests.** JVM, with
  the kill points used by the Phase 4 crash simulation (STATUS.md:289); committed recovery tests are in
  framework/src/test/.../orchestration/OwnerControlTest.kt, OwnerWaitRecoveryTest.kt, BoundedAutonomyTest.kt.

Cross-area notes (other readers' scope, listed so nothing is planned twice): scripts in a separate process (a
service with `android:process=":scripts"` whose process the app can end; it also turns an Android 17
memory-limit kill into the loss of that process only); the screen banner while an action waits
(`ScreenOperator.SESSION_IDLE_MS`, Screen.kt:326; ScreenSessions.kt:117); `ACCESS_LOCAL_NETWORK` before target 37.

---

## 5. Changes in one place

- **Manifest (app):** `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`,
  `RECEIVE_BOOT_COMPLETED`; receivers `BootReceiver` (disabled by default), `RoutineAlarmReceiver`,
  `ExactAlarmPermissionReceiver`; a `JobService` with `BIND_JOB_SERVICE`; `dataExtractionRules` and
  `fullBackupContent`; wider `specialUse` subtype text. Not `USE_EXACT_ALARM` (owner could not revoke it).
- **Schema 6:** `routines`, `routine_runs`, `usage_days`; columns `tasks.retry_at`, and either
  `conversations.turn_started_at` or a DataStore set. Migration 5 → 6 hand-written; golden names extended.
- **Settings (DataStore):** `resumeAfterRestart`, `routinesEnabled`, `maxModelCallsPerDay`, `maxTokensPerDay`,
  `lastSeenAt`, last processed exit timestamp, last backup time.
- **New classes:** `app/work/WakeHold`, `BootReceiver`, `RoutineAlarms`, `RoutineAlarmReceiver`, `UpkeepJobService`;
  `app/health/StartLedger`, `RecoveryScreen`; `device/health/ExitHistory`, `StorageWatch`,
  `BackgroundHealth`; `framework/routines/RoutineClock`, `RoutineRunner`; `framework/report/AwayReport`;
  `core` ports `RoutineRepository`, `OwnerRoutines`, `UsageLedger`; `data/backup/BackupWriter`, `BackupReader`.
- **Owner-only ports** (`OwnerRoutines`, backup restore) join the architecture tests' list of what agent-facing
  code must not reach.

## 6. Order and size (suggested)

1. A1 wake lock + elapsed-realtime limits, A2 chats in the service, A3 background check, R1 exit reasons, A6
   result notifications: small, and they make what exists dependable.
2. R5 Recovery screen, R6 extraction rules, R7 backup file, R2 safe start, R3 chat notice: the "never lose
   everything" set.
3. R4 provider trouble, R8 storage, A7 daily limits, R9 upkeep job.
4. A4 boot resume, A5 routines, A6 away report.
5. R10 only if time is left.

## 7. What can and cannot be checked here

- **JVM:** routine clock, routine runner rules, away report, daily-limit gateway checks, retry waits, circuit
  breaker, backup format, start ledger, chat notice, transcript continuation.
- **Robolectric:** wake lock, controller and service, receivers, alarms (`ShadowAlarmManager`), exit reasons
  (`ShadowActivityManager.addApplicationExitInfo`), battery/background states, jobs, storage, notifications,
  Recovery screen, screenshots.
- **Emulator (Android 14, slow):** `force-idle`, `set-standby-bucket`, `am kill`, `am force-stop`, `am crash`,
  `cmd activity stop-app`, BOOT_COMPLETED broadcast and one reboot, `dumpsys power` and `dumpsys alarm`, backup
  round trip, Recovery screen after deleting the sealed key (instrumented).
- **Only on the owner's phone:** whether the CPU stays awake and objectives finish with the screen off for an hour;
  maker battery managers; real exit reasons; xAI's behaviour under rate limits.

## 8. Assumptions and open points

- `System.nanoTime` stops in deep sleep on Android [MEM]; G6 rests on that.
- Cached-app freezing on the owner's phone and maker task killers [MEM, not checked].
- The size cap of `setProcessStateSummary`, the class SQLCipher uses for "disk full", and whether `am crash`
  exists on the emulator image: to check during implementation.
- PBKDF2 iteration count: chosen for a phone; measure on the emulator and keep under about 1 s.
- Whether the aosp_atd image has Android's document picker (affects the backup end-to-end test).

## 9. Sources

- Foreground service timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout (15+)
- Background start restrictions and exemptions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start (12+, 15 changes)
- Android 15 behaviour, target 35: https://developer.android.com/about/versions/15/behavior-changes-15 (dataSync timeout, BOOT_COMPLETED FGS types, SYSTEM_ALERT_WINDOW)
- Android 15 behaviour, all apps: https://developer.android.com/about/versions/15/behavior-changes-all (stopped state, background network)
- Android 16 behaviour, all apps: https://developer.android.com/about/versions/16/behavior-changes-all (job quotas)
- Android 16 behaviour, target 36: https://developer.android.com/about/versions/16/behavior-changes-16
- Android 17 behaviour, all apps: https://developer.android.com/about/versions/17/behavior-changes-all (memory limits)
- Android 17 behaviour, target 37: https://developer.android.com/about/versions/17/behavior-changes-17 (local network permission)
- Long-running workers: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- User-initiated data transfer: https://developer.android.com/develop/background-work/background-tasks/uidt (14+)
- Alarms: https://developer.android.com/develop/background-work/services/alarms
- Doze and battery optimisation: https://developer.android.com/training/monitoring-device-state/doze-standby
- Standby buckets: https://developer.android.com/topic/performance/appstandby ; limits: https://developer.android.com/topic/performance/power/power-details
- Task Manager stop: https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping (13+)
- ApplicationExitInfo: https://developer.android.com/reference/android/app/ApplicationExitInfo (30+, 37.2 additions)
- Auto Backup and data extraction rules: https://developer.android.com/identity/data/autobackup
- Wake locks with foreground services (March 2026): https://android-developers.googleblog.com/2026/03/battery-technical-quality-enforcement.html ; https://developer.android.com/google/play/vitals/excessive-wakelock
- AOSP (GitHub mirror of frameworks/base main): services/core/java/com/android/server/power/PowerManagerService.java (`setWakeLockDisabledStateLocked`), services/core/java/com/android/server/wm/ActivityTaskSupervisor.java (`killTaskProcessesIfPossible`)
- Earlier project research reused: /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/research/android-phone-operator.md §3
