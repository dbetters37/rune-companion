# Phase 6 plan: critique of Android feasibility, scope and testability

Reviewed: scratchpad/phase6-plan.md (all 984 lines), the four reader files in scratchpad/phase6/, the code at
/home/user/rune-companion, and current Android documentation (Android 14 to 17). Nothing in the repository was changed
and no Gradle build was run. Only the 0.5.2 APK in the scratchpad was inspected, with aapt2, zipalign and readelf.

Tags: **[V]** read in code or an artifact at file:line. **[D]** official documentation, with the URL and the Android
version it applies to. **[K]** platform knowledge (AOSP behaviour) that is not confirmed here. **[A]** assumption.
Severity: **blocker** means the plan cannot be carried out as written, or would ship something broken. **major**
means wrong behaviour, a test that cannot pass, or a likely schedule failure. **minor** means a wording, citation or
process gap.

## 0. Summary

The core architecture of the plan is sound: a `specialUse` foreground service, a partial wake lock, platform
JobScheduler, an isolated script process with a fallback spike, one migration, and owner-only ports. Most Android
claims check out (section 6). The problems are of four kinds:

1. **The plan describes the wrong baseline.** HEAD is 0.5.2 with application id `com.aicontrolcenter.hq` and
   versionCode 8, so 0.6.0 must be versionCode 9. The 0.5.1→0.6.0 upgrade test and three adb commands cannot run (F1).
2. **Several Android mechanisms are underspecified in ways that break the acceptance tests.**
   - Binder sizes, death and restart of the script process (F4, F5).
   - Exits from the script process counting as app crashes (F6).
   - The force-stop exit reason (F7).
   - Timers that do not run while the phone sleeps (F8, F9).
   - Start over with a damaged keystore entry (F10).
   - How the upkeep job is put together (F3).
3. **Tests that cannot run here.**
   - Robolectric has only SDK 35 offline (F2).
   - The mock model server is "on this device", so daily limits and network-back rechecks never fire (F13).
   - The emulator has no launcher, so "Home" may not background the app (F14).
   - The debug merged manifest has a fourth exported component (F15).
   - Timing tripwires are flaky on the shared machine (F19).
4. **Scope and file ownership.**
   - Eleven size-L packages, against Phase 5's one feature family that needed five fix rounds (F12).
   - Overlapping and ownerless files, and the owner's typing bug (task #42) missing entirely (F11).

| ID | Sev. | Plan section | One line |
|---|---|---|---|
| F1 | blocker | header, §0, rule 19, WP14, §10 | Baseline is 0.5.2 / `.hq` / versionCode 8: 0.6.0 must be 9; the 0.5.1 upgrade and the `com.aicontrolcenter.app` adb commands fail |
| F2 | major | WP2 tests, §9 Android lens | Robolectric android-all exists offline only for SDK 35; the API 28/29/30 shadow tests cannot run |
| F3 | major | rule 1, WP2.12 | The upkeep job has no composition that avoids `AiControlCenterApp.graph`; rule 1's reachability test fails with the obvious implementation; `onStopJob` and the 10-minute and quota rules are missing |
| F4 | major | WP6 design | `oneway run(code, input)` is about 500 KB and `oneway done(result)` about 232 KB of UTF-16, far over IPC guidance |
| F5 | major | WP6 design | After a death Android restarts the bound service with growing back-off; "rebinds after a death" must unbind first and bound the bind |
| F6 | major | WP2.7-8, WP6 | Script-process crashes (a memory hog) appear in the package's exit history: false safe start and wrong "Interrupted" texts |
| F7 | major | WP5.8 (D7), K12 | `REASON_USER_STOPPED` means a multi-user stop; force stop is `REASON_USER_REQUESTED` (as is a swipe from Recents); use `ApplicationStartInfo.wasForceStopped()` (API 35) |
| F8 | major | rule 5, WP4.3, WP7.5 | Retry and recheck waits use delays that stop while the phone sleeps, and the wake lock is released while waiting, so "goes on by itself" stalls with the screen off |
| F9 | minor | WP2.2 (G6) | Passing an elapsed-realtime `TimeSource` does not make limits count sleep (`WaitClock.runDown`); decide what should count, or drop G6 |
| F10 | major | rule 9, WP2.9, K14, WP10 | Start over: the process cannot rebuild its graph; a damaged keystore alias blocks a new key; "Go back to the copy" can never work in 0.6.0; reading `user_version` needs the key |
| F11 | major | §5 ownership, §8 | Manifest owned by two packages in wave 2; vision fallback, chat images, components, attention and shell files have no owner; typing bug #42 is absent; WP8 3a is a one-agent big-bang integration |
| F12 | major | §2, §5, §8, §9 | Scope is about 4× Phase 5's plan; the cut order protects dependability but not the riskiest new attack surfaces |
| F13 | major | §10 steps 8-9 | The mock at 127.0.0.1 via `adb reverse` is "on this device": not limited, not affected by airplane mode |
| F14 | minor | §10 steps 4-5 | The aosp_atd image has no HOME activity; background the app with SIM Toolkit or KEYCODE_SLEEP |
| F15 | minor | rule 22 | The debug merged manifest has an exported `androidx.activity.ComponentActivity` from ui-test-manifest |
| F16 | major (if photos ship) | WP11 chat, WP7.9 | Base64 pictures inside `message_json` rows risk "Row too big to fit into CursorWindow" and are re-sent on every turn |
| F17 | minor | WP2.7 | `setProcessStateSummary`: at most 128 bytes, "do not include sensitive information", and redundant with the task store |
| F18 | minor | WP2.7 | Android 17.0 reports the memory limiter as `REASON_OTHER` with "MemoryLimiter:AnonSwap"; `REASON_MEMORY_LIMITER` exists only from 37.2 |
| F19 | minor | rule 16, §4 gate, WP9 | Wall-clock tripwires and a JVM benchmark without SQLCipher decide the `tasks` rebuild; use counts or the emulator |
| F20 | minor | §4, WP1b | `tasks` rebuild risk; column order of ALTER-added columns; the EXPLAIN pattern is cited from the wrong file; `:data` androidTest lacks room-testing and schema assets |
| F21 | minor | WP6 D2, §10 step 12 | Cold isolated process plus Rhino on the TCG emulator may exceed the 30 s engine-start bound |
| F22 | minor | WP6 AIDL | A shared `IScriptHost` keyed by `runId` lets an escaped script act as another run; use a binder per run |
| F23 | minor | WP6 | The watchdog kill ends the other concurrent run too; say so, or use one process per slot |
| F24 | minor | §8 | Worktrees lack `local.properties`; the Gradle lock depends on `TMPDIR`; disk budget |
| F25 | minor | rule 18 | A test that fails on `@Phase6Stub` turns the integrator's suite red for all of 3b |
| F26 | minor | WP6 tests | Instrumented names with spaces cannot be dexed below API 30; device-test hooks need a debug-only path |
| F27 | minor | §10 step 15 | The debug install mid-script invalidates steps 16-19; reorder and build both APKs before wave 5 |
| F28 | minor | rule 22, WP2.10, WP14 | R8 is off (say so); do not add `<cross-platform-transfer>`; release checklist gaps |
| F29 | minor | §1 goal 1, D25 | Battery optimisation off is a documented exemption for starting the service from the background; D25 can be improved rather than marked final |
| F30 | minor | WP3 | Restore of up to 1 GB in one transaction needs a storage pre-check; saving can be cut off if the owner leaves the app |

## 1. Findings in detail

### F1 (blocker). The plan is written against 0.5.1; the repository is at 0.5.2 under a new application id

- **What the repository says.**
  - Evidence [V]: `git log -1` = 9c739d5 "0.5.2: the app's Android name becomes com.aicontrolcenter.hq".
  - app/build.gradle.kts:13 `applicationId = "com.aicontrolcenter.hq"`, :16-17 `versionCode = 8`, `versionName = "0.5.2"`.
  - The 0.5.2 APK in the scratchpad (aapt2 dump badging) reports `package: name='com.aicontrolcenter.hq' versionCode='8'`.
  - docs/STATUS.md:422-430: 0.5.2 "does not update 0.5.0 or 0.5.1: it installs beside them".
- **Wrong in the plan.**
  1. Header :4-5, WP14 :711, §8 :823 and §10 step 1 :876-878 say "0.6.0 / versionCode 8". **8 is already 0.5.2's
     code**; 0.6.0 must be **versionCode 9**. (The task brief's checklist item "versionCode 8" is stale for the
     same reason.)
  2. §0 :32: "0.5.1 and 0.6.0 install over 0.5.0 and keep the data". This is false: 0.6.0 (`.hq`) installs only over
     0.5.2.
  3. Rule 19 :176 and §10 step 2 :880: "Install 0.5.1 … install 0.6.0 over it". Android treats these as two
     different apps. Use scratchpad/AIControlCenter-0.5.2.apk as the "before" build. Its schema is still 5
     (AiccDatabase.kt:100 `VERSION = 5`), so the migration test is unaffected.
  4. adb commands with the old name, at :885 (`deviceidle whitelist +com.aicontrolcenter.app`), :897 (`am crash
     com.aicontrolcenter.app`) and :931 (`cmd jobscheduler run -f com.aicontrolcenter.app`). All three must use
     `com.aicontrolcenter.hq`. Step 6 needs the component `com.aicontrolcenter.hq/com.aicontrolcenter.device.screen.OperatorAccessibilityService`
     for `settings put secure enabled_accessibility_services`.
  5. Citations into STATUS.md moved down by 10 lines. "Next: Phase 6" is now :521-527 (the plan says :511-517). The
     test count is at :441 (the plan says :431). The 13 permissions are at :445 (the plan says :435).
  6. Missing docs for the owner. A phone may now carry two apps named "AI Control Center": 0.5.0/0.5.1 under
     `.app`, and 0.5.2+ under `.hq`. Each has an accessibility service with the same label in Android's settings, so
     the non-developer owner can switch on the wrong one. WP14 (README and STATUS "Installing 0.6.0") must say:
     install over 0.5.2; uninstall any old "AI Control Center" that 0.5.2 did not replace; and which accessibility
     entry belongs to which app.
- **Fix.** Rebase the plan on 9c739d5 and replace every item above.

### F2 (major). Robolectric can run only SDK 35 offline

- **Evidence [V].**
  - The only android-all jar is ~/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-14663667-2-i7/.
  - app/src/test/resources/robolectric.properties says `sdk=35`.
  - Every `@Config` in the app, data and device tests is `sdk = [35]` (49 + 3 occurrences in :data/:device, plus
    MainActivityTest, the journeys and the work tests).
- **What fails.** WP2's tests (plan :358, "`ShadowActivityManager.addApplicationExitInfo` on API 28, 30, 35 shadows")
  and the Android lens (:848, "exit info on API 28/29 shadows") would try to download android-all for 28, 29 and 30,
  and fail offline.
- **Fix.**
  - Inject `sdkInt: Int = Build.VERSION.SDK_INT` into `AndroidExitHistory`, `AndroidBackgroundHealth`, the
    `wasForceStopped` path (F7) and the in-process fallback of `IsolatedJsEngine`.
  - Test each branch at SDK 35 with a forced `sdkInt`.
  - State in STATUS that the real behaviour on Android 9-13 and 15-17 is not verified. The emulator is 14 and
    Robolectric is 15.

### F3 (major). The upkeep job has no composition that keeps rule 1

- **Evidence [V].** The `AiControlCenterApp.graph` getter (AiControlCenterApp.kt:44-55) builds the whole `AppGraph`
  and also starts `ObjectiveWorkController` (:48), `ApprovalNotifier` (:50) and `ScreenSessionNotifier` (:52).
  Memory upkeep exists only inside the graph's start-up (AppStartup.kt `maintainMemory`, `memory.maintain()`).
- **What breaks.**
  - Rule 1's test (plan :104-107) walks everything reachable from `UpkeepJobService` and fails on `Orchestrator`,
    `ModelGateway`, `ToolExecutor` and `AgentChat`. It fails for the obvious implementation `(application as
    AiControlCenterApp).graph`.
  - A second `DataLayer` in the job is no way out either. Two Room instances on one SQLCipher file in one process
    have separate invalidation trackers, so open screens would miss the job's deletes.
  - The plan does not say how the job gets its stores. The files involved belong to WP8 (AiControlCenterApp.kt,
    AppGraph.kt), not to WP2.
- **What is fine.** Stale request notifications are not a risk: `OwnerRequests.pending` already drops requests
  nothing waits for (OwnerPorts.kt:43-47).
- **Fix.**
  - `AiControlCenterApp` gets a lazily shared `dataLayer`. Both `graph` and a small `UpkeepGraph` (memory engine over
    the same repositories, offline `HashingEmbedder`, activity, usage and check pruning) use it.
  - `UpkeepJobService` reaches only `UpkeepGraph`.
  - Implement `onStopJob`: cancel and return true. Keep the `JobParameters` and call `jobFinished`. Android 16 adds
    `STOP_REASON_TIMEOUT_ABANDONED` for jobs whose `JobParameters` were collected without `jobFinished`, and applies
    runtime quotas to jobs that run beside a foreground service [D]
    https://developer.android.com/about/versions/16/behavior-changes-all (Android 16, all apps).
  - Name the owner: the pre-inserted hook goes to WP1a in wave 1, or this item goes to WP8.

### F4 (major). Binder sizes in the script-process design

- **Evidence [V].**
  - Limits: JsSandbox.kt:32-33 (code 50,000 characters, input 200,000), :40 (result 100,000), :42 (log 16,000).
  - The AIDL design (plan :486-489; reader-deferred D1) sends code and `inputJson` as Strings in **`oneway`**
    `run(...)` and the outcome in `oneway done(Bundle)`. AIDL Strings are UTF-16, so `run` carries about 500 KB and
    `done` about 232 KB.
- **Why that is too much.**
  - `IBinder.getSuggestedMaxIpcSizeBytes()` (API 30): "Limit that should be placed on IPC sizes … to keep them
    safely under the transaction buffer limit" [D] https://developer.android.com/reference/android/os/IBinder (API 30+).
    AOSP returns 64 KB [K].
  - Oneway transactions may use only half of the receiver's buffer, about 508 KB [K: binder driver
    `free_async_space = buffer_size / 2`].
  - The buffer is shared "by all transactions in progress for the process" [D] TransactionTooLargeException. The app
    process also receives the accessibility service's event traffic.
- **What the spike misses.** It checks a 4 MB pipe, but not these two calls.
- **Fix.**
  - Send code, input and outcome through `ParcelFileDescriptor` pipes, as the plan already does for `workspace.*`.
  - Or make `run` and `done` two-way calls with each parcel at most 64 KB, and stream anything larger.
  - Add spike question Q7 (section 4).

### F5 (major). After a death Android restarts the service; the plan's "rebinds after a death" needs an explicit unbind

- **Evidence [D].** https://developer.android.com/reference/android/content/ServiceConnection (all versions):
  - `onServiceDisconnected`: "This does *not* remove the ServiceConnection itself -- this binding to the service will
    remain active, and you will receive a call to onServiceConnected … when the Service is next running."
  - `onServiceConnected`: "it's possible that your app will never receive this callback … such as the service
    crashing while being created."
- **Why it matters.** The watchdog kills its own process (plan :491-492). Repeated kills within a minute meet
  ActivityManager's crash-restart back-off: the restart delay grows ×4 when the service dies again within about
  60 s, and after `BOUND_SERVICE_MAX_CRASH_RETRY` (16) the service is brought down [K: ActiveServices]. The plan's
  own test "endless loop three times then `1+1`" (:505, §10 step 12) is exactly that pattern.
- **Fix.**
  - On `binderDied`, `onServiceDisconnected` or `onBindingDied`: call `unbindService` at once, mark the engine dead,
    and bind afresh at the next run.
  - Give `bindService` → `onServiceConnected` its own bound (for example 60 s) and its own message.
  - Spike question Q6: three kills within 60 s, then measure the time to the fourth run.

### F6 (major). Exits of the script process count as app exits

- **Evidence [D].** https://developer.android.com/reference/android/app/ApplicationExitInfo (API 30+):
  - `getRealUid()` covers "the case of isolated process".
  - `getProcessName()` is "the actual process name it was running with".
  - `getHistoricalProcessExitReasons(packageName, …)` returns records for the package's processes [D, ActivityManager].
  - Exits of isolated processes are attributed to the package that owns them [K: AppExitInfoTracker isolated-uid
    records].
- **What breaks.**
  - A memory-hog script that ends in OutOfMemoryError is recorded as `REASON_CRASH`.
  - Three such scripts within 15 minutes trigger safe start (plan :329-330), and a later start says the app "stopped
    because of an error in the app" (:898).
  - The memory-hog device test (:507, test 7) produces exactly this.
- **Fix.**
  - `AndroidExitHistory` keeps only records whose `processName` equals the main process name (the package name).
  - In `ScriptProcessService`, set a default uncaught-exception handler that reports "the script used too much
    memory" to the app and then calls `Process.killProcess(Process.myPid())`. That avoids a CRASH record and a
    possible "keeps stopping" dialog.
  - Test: a fake exit list with three `:scripts` crashes gives no safe start.

### F7 (major). The force-stop cause is mapped to the wrong reason

- **What the plan says.** D7 at :460-461: "say 'force-stopped' only for USER_STOPPED on Android 14+". K12 at :242 lists
  `USER_STOPPED`.
- **What the documentation says** [D] ApplicationExitInfo (API 30+):
  - `REASON_USER_STOPPED`: "the user it is running as on devices with multiple users, was stopped".
  - `REASON_USER_REQUESTED`: "user clicked the 'Force stop' button … or removed the application away from Recents".
  - Before 34, `REASON_USER_REQUESTED` was also used for updates.
- **Consequence.** Exit reasons cannot tell Force stop from a swipe away from Recents.
- **Fix.**
  - On API 35+, use `ApplicationStartInfo.wasForceStopped()` (present in android-37.2.jar [V, javap];
    `getHistoricalProcessStartReasons`, API 35).
  - Otherwise say "Android switched screen control off after the app was stopped", without naming the cause.
  - Rename the ExitKind to `USER_REQUESTED`.
  - Test at SDK 35 with a fake start-info list (F2).

### F8 (major). Waiting work stalls while the phone sleeps

- **What the plan does.**
  - Rule 5 (:120-123) and WP2.1 (:297-300) hold the wake lock only while a task is RUNNING or IN_REVIEW.
  - Tasks waiting for `retryAt` (WP7.5, 30 s then 2 min) and `ProviderRechecks` (WP4.3, 1, 2, 5 and 10 minutes)
    therefore wait without it.
  - Those waits are coroutine delays. Kotlin's dispatchers time delays with the monotonic clock, which does not
    advance in suspend [K, Linux `CLOCK_MONOTONIC`].
- **Consequence.** With the screen off, an objective waiting for xAI resumes only when something else wakes the CPU.
  Goal 5 ("goes on by itself when it answers again") fails in exactly goal 1's pocket scenario. The TCG emulator
  does not suspend, so §10 step 8 cannot show it either.
- **Fix.**
  - Count "a retry or recheck due within 2 minutes" as computing for the wake lock.
  - For longer waits, set `AlarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, …)` to the app's own
    non-exported receiver, which wakes the driver. It needs no permission and Doze throttles it to about once every
    9 minutes [D] https://developer.android.com/develop/background-work/services/alarms (Android 6+).
  - Or document it: "with the screen off, waiting work goes on when the phone next wakes".

### F9 (minor). G6 does not do what it says

- **What the code does [V].** `withPausableTimeout` → `WaitClock.runDown` (core/tasks/WaitClock.kt:67-77) waits
  `withTimeoutOrNull(left)` on the dispatcher. After a full `left` with no pause, it returns "whatever the time
  source says" (:74-75).
- **Consequence.** An elapsed-realtime `TimeSource` (WP2.2, :304-306) does not make limits count deep sleep. Counting
  sleep would also time out objectives that could not run.
- **Fix.** Decide what should count. Recommended: keep `TimeSource.Monotonic`, drop G6, and with it
  `device/platform/ElapsedRealtimeTimeSource.kt`. If the plan keeps G6, change `runDown` to wait in bounded slices
  and test it.

### F10 (major). Start over, the safety copy and the keystore

1. **The process cannot rebuild its graph [V].** `DataLayer.database` is `by lazy` (DataLayer.kt:89-93). The graph is
   a single instance (AiControlCenterApp.kt:44-46). After `startOver()` or a restore, the process still holds Room
   objects for moved files. Fix: both actions end with a process restart: start `MainActivity` with
   `FLAG_ACTIVITY_NEW_TASK|CLEAR_TASK` from the visible activity, then `Process.killProcess(myPid())`. The Recovery
   path must never touch `graph`.
2. **A damaged key alias blocks a new key [V].** `KeystoreVaultKey.keyForEncryption` throws when the alias exists but
   cannot be loaded (KeystoreVaultKey.kt:44-50). `DatabaseKeyStore.loadOrCreate` (DatabaseKeyStore.kt:53-60) always
   uses `aicc.database-key.v1` (AndroidKeystoreVaultCipher.kt:61). So "Start over" cannot create a new database key
   without deleting that entry, and deleting it makes the set-aside copy unopenable for good, against rule 9. Fix: a
   generation-numbered database key alias and sealed-key file (v2, v3 …) stored beside the key. Start over creates
   the next generation and leaves the old alias in place.
3. **"Go back to the copy" can never work in 0.6.0.**
   - A Room migration runs inside one transaction, so a failed migration leaves version 5 untouched [K, SQLite
     transaction].
   - A restored version-5 copy is migrated again by the same 0.6.0.
   - A downgrade install is refused.
   - The button therefore has no working effect, which the owner's "(test)" rule forbids.
   - Fix: keep at most a copy for a later fixed build to retry from, with no button; or drop SafetyCopy.
4. **The safety copy cannot read `user_version` cheaply.** SQLCipher encrypts page 1, so `user_version` (WP2.9, :337)
   cannot be read without the key. Fix: write a plain "last opened schema/app version" marker into
   `noBackupFilesDir` after a successful open, and compare against it. Also check free space with
   `getAllocatableBytes` before copying.

### F11 (major). File ownership: overlaps, gaps, and the missing typing bug

- **Two owners for one file in wave 2.** AndroidManifest.xml has "the WP2 block" (:295) and "the WP6 block" (:478),
  against the plan's own rule "one owner per wave" (:217-219). WP6's device test cannot run in wave 2 without the
  service entry and the `Process.isIsolated()` line in AiControlCenterApp.kt, which WP8 owns from wave 3.
  Fix: WP1a (wave 1, already owner of app/build.gradle.kts) pre-inserts both manifest entries, the early-return line
  and empty wiring hooks.
- **The vision-aware fallback has no owner.**
  - WP4.4 (:411-412) needs `resolveAvailability` (core/agents/AgentAvailability.kt:41-56). Its callers are
    framework/chat/DefaultAgentChat.kt and framework/registry/DefaultRegistryDirectory.kt [V, grep].
  - None of these is WP4's. `:core` has no owner in wave 2, and framework/registry has none at all.
  - Fix: WP1a adds `resolveAvailability(..., needsVision: Boolean = false)`; WP7 owns the callers.
- **Chat images cross into WP5's files.** WP7.9 sends images through AgentRunner.kt (WP5) and AgentPromptComposer
  (no owner). Assign it.
- **UI files with no owner in wave 3.**
  - ui/components/* (AppTextField.kt, TestLabel.kt).
  - ui/dashboard/Attention.kt and DashboardSections.kt. The "phone attention items" would have to feed the badges
    through `attentionItems()`, ShellViewModel.kt:42-43.
  - ui/shell/ShellViewModel.kt and NavigationChrome.kt.
  - ui/models/ProviderDetailScreen.kt, where WP10's ProviderHistoryCard must be placed.
- **The owner's typing bug is missing.** Task #42 ("typing in 'Your objective' lags, reorders and drops keys … Fix
  all text fields to synchronous TextFieldState") is not in the plan. It touches AppTextField.kt (:29-51,
  `value`/`onValueChange` round trip [V]) and every form in WP10 and WP11 files. Fix: its own small package in wave
  3a, before any 3b agent starts, or a WP8 3a item.
- **Wave 3a is a big-bang integration by one agent.** WP8 3a wires about 20 new ports, the manifest, start-up and both
  architecture tests before any UI work. Any integration defect blocks four agents. Fix: per-package wiring files
  (`di/wiring/WpN.kt`), called from one-line hooks pre-inserted by WP1a, so each wave-2 package wires and
  integration-tests its own part.
- **WP1b waits on WP1a longer than the plan says.** WP1b's Room contract runs (:283) need WP1a's :testkit suites, not
  just its "first commit". Fix: the contract suites go into WP1a's first commit.

### F12 (major). Scope

- **Size [V].** phase6-plan.md has 13,685 words against phase5-plan.md's 3,191. It has 11 L packages, plus WP3 (M-L)
  and WP13/14 (M).
- **Phase 5 for comparison [V, artifact timestamps].** Phase 5 was one feature family. Contracts were done on 11-23
  at 17:16, the review on 11-23 at 22:14, then four fix rounds to 11-24 at 15:24, pass 4b, and the emulator run from
  11-24 19:52 to 11-25 02:33. That is about 33 hours, about 70% of it in review and fix rounds, which found 32
  problems (STATUS.md:447).
- **What the cut list protects (:96-99).** WP2, WP5 and WP6. It does not protect the newest and riskiest attack
  surfaces: the backup crypto format (WP3), questions inside the broker (C1), and daily limits in the gateway.
- **Fix.** Use three tiers (section 2), with a written rule that a Tier B item still under review at the end of
  wave 4, round 2 ships switched off or is removed.

### F13 (major). The emulator's model server counts as "on this device"

- **Evidence [V].** Phase 5 reached the scripted server with `adb reverse tcp:8765 tcp:8765` at `127.0.0.1:8765`
  (e2e-result.json). That is Locality "on this device".
- **What fails.**
  - Rule 6 (:124-127) limits only providers off the phone, so step 9's "the 6th chat message gets 'Today's limit…'"
    cannot happen.
  - Airplane mode does not affect loopback, so step 8's network-back recheck tests nothing.
- **Fix.** For steps 8-10, register the mock as `http://10.0.2.2:8765`, which is "local network". Phase 5 step 5g
  reached that address [V].

### F14 (minor). Backgrounding the app on aosp_atd

- **Evidence [V].** The image's only LAUNCHER activities are com.android.stk/.StkMenu and the app (e2e-result.json),
  and it has no launcher. KEYCODE_HOME may leave the app in front [A].
- **What fails.** Steps 4-5 rely on "Home" to get the result notification ("only while the app is not on screen",
  :320) and the chat in the background.
- **Fix.** Use `am start -n com.android.stk/.StkMenu` or `input keyevent KEYCODE_SLEEP`. Confirm with
  `dumpsys activity activities` that the app is stopped. Dismiss crash dialogs after `am crash` (step 7) with
  `input keyevent KEYCODE_BACK`.

### F15 (minor). The merged-manifest test sees a debug-only exported activity

- **Evidence [V].**
  - app/build.gradle.kts:104 has `debugImplementation(libs.androidx.compose.ui.test.manifest)`.
  - ui-test-manifest-1.13.0.aar declares `androidx.activity.ComponentActivity` with `android:exported="true"`.
  - `QueriesManifestTest` reads the source manifest (device/build.gradle.kts:22-23), so it is no model for a merged
    test.
- **What fails.** Rule 22's "exactly three exported" (:185-188) fails under `testDebugUnitTest`.
- **Fix.** Assert in `testReleaseUnitTest` (the merged manifest comes from AGP's `test_config.properties` when
  `isIncludeAndroidResources = true`), and repeat the check with aapt2 on the signed APK in step 1. Today's APK has
  13 `uses-permission` [V, aapt2 dump permissions].

### F16 (major if photos ship). Pictures stored as Base64 inside conversation rows

- **Evidence [V].** `ImageContent` is Base64 inside `ChatMessage` (core/llm/Chat.kt:35, :57-63). Conversation entries
  store `message_json`.
- **What breaks.**
  - A 1,568-px JPEG is about 0.3-0.8 MB of Base64. Two or three in one message pass the 2 MB CursorWindow row limit
    [K]. The chat then fails to load with SQLiteBlobTooBigException.
  - Every later turn re-sends every picture.
- **Fix.** Allow one picture per message, capped at about 700 KB Base64; send only the latest picture. Add an
  instrumented test that reads a maximum-size row back through SQLCipher. Or store pictures as files referenced by id.

### F17 (minor). `setProcessStateSummary` is the wrong tool

- **Evidence [D].** https://developer.android.com/reference/android/app/ActivityManager (API 30+): "Maximum length is
  128 bytes", "DO NOT include sensitive information", and it is "not suggested to rely on this to restore the
  applications previous UI state … only meant for analyzing application healthy status".
- **Why it is not needed.** The task store already knows which tasks were RUNNING; recovery marks them.
- **Fix.** Cut `ProcessNote` and the `state_summary` column. Take task titles from the database.

### F18 (minor). The memory-limiter reason on Android 17.0

- **Evidence [D].** https://developer.android.com/about/versions/17/behavior-changes-all (Android 17, all apps): "the
  exit reason will be REASON_OTHER and the description will contain the string 'MemoryLimiter:AnonSwap'".
  `REASON_MEMORY_LIMITER` (17) and `REASON_ANOMALY` (18) were "Added in version 37.2".
- **Fix.** Map all three to MEMORY_LIMIT or OTHER as appropriate.

### F19 (minor). Flaky and misleading measurements

- **What the plan uses.** `TeamTimelineTest` under 250 ms (:622), "a benchmark tripwire" (:162), and
  `TaskSummaryBenchmarkTest` as the gate for the `tasks` rebuild (:195).
- **Why they mislead.** The 4-core machine is shared by parallel agents and the emulator, which pins about one core
  (run-emulator.sh header). The gate runs on Robolectric SQLite without SQLCipher's per-page decryption, which is
  the very cost the rebuild targets.
- **Fix.** Assert counts: builds, rows and pages read, `EXPLAIN QUERY PLAN` steps. Decide the rebuild with a relative
  instrumented measurement on SQLCipher, or skip it (see F20).

### F20 (minor). Schema details

- **The `tasks` rebuild.** Create, copy, drop, rename on the owner's whole result history (5.json: `instructions` is
  column 13 [V]) is the riskiest migration so far. Projections plus `ALTER TABLE ADD result_chars` give most of the
  benefit. Recommended: no rebuild in 0.6.0.
- **Column order.** Declare every ALTER-added field last in its entity, so that new installs and upgraded databases
  have the same column order.
- **Wrong citation.** The `EXPLAIN QUERY PLAN` pattern is in RoomActivityLogQueryTest.kt:105-114 and
  RoomMemoryBehaviourTest.kt:169-194, not SchemaExportTest.kt:135 (:162).
- **`EncryptedMigrationTest` needs setup.** Add `androidTestImplementation(libs.androidx.room.testing)` (present in
  the offline cache [V]) and the schemas as androidTest assets. data/build.gradle.kts:22-23 adds them to `test` only.

### F21 (minor). Engine start on the TCG emulator

- **Evidence [V].** In-process engine start already exceeded 5 s on the emulator (STATUS.md:504-506). The app's cold
  start there takes about 50 s (run-emulator.sh header).
- **What may fail.** A cold isolated process plus Rhino may pass the plan's 30 s `ENGINE_START_MS` (:499), and step 12's
  "`1+1` with the default 5 s finishes" would then fail on TCG.
- **Fix.** Separate the bind bound from the engine-start bound and make both `JsLimits` fields. The device test logs
  them and uses generous values. Acceptance: the default 5 s holds for a warm second run.

### F22 (minor). One host binder per run

- **What the plan does.** `IScriptHost` methods take `runId` (:486-489).
- **Risk.** If Rhino's ClassShutter were escaped (the premise of isolation), code holding the host binder could call
  another run's `runId`, for example a write from a read-only run.
- **Fix.** Create a fresh `IScriptHost` binder per run whose methods ignore any id, and refuse calls after `runOver`.

### F23 (minor). The watchdog ends both runs

- **What happens.** At most 2 runs share one process (:494). A runaway script makes the watchdog kill the process,
  ending the other agent's well-behaved run too.
- **Fix.** Say so in the tool's answer and in LIMITATIONS, or use `bindIsolatedService(…, instanceName, …)` (API 29+)
  for one process per slot, with a shared process on API 28.

### F24 (minor). Worktree mechanics

- **Missing SDK location [V].** `local.properties` is gitignored (.gitignore) and `ANDROID_HOME` is unset. A new
  `git worktree` therefore fails with "SDK location not found". Copy `local.properties` into each worktree.
- **Gradle lock [V].** tools/gw:6 locks `${TMPDIR:-/tmp}/aicc-gradle.lock`. Every agent must have the same `TMPDIR`.
- **Disk [V].** Each worktree builds about 1.2 GB (app/build 642 MB and more); 21 GB are free.

### F25 (minor). The `@Phase6Stub` test

- **Problem.** A test that fails while any stub exists (:170) keeps the suite red throughout 3b, so "full suite after
  each merge" stops meaning anything.
- **Fix.** Make it a wave-3 gate task, not part of `test`.

### F26 (minor). Instrumented tests in :app

- **Names [V].** device/build.gradle.kts:60-61 and data/build.gradle.kts:62 note that method names with spaces cannot be
  dexed below API 30.
  Instrumented tests must use camelCase names.
- **Hooks.** `ScriptProcessDeviceTest`'s checks inside the isolated process (`isIsolated`, listing the data folder)
  need a debug-only AIDL method or a test-only service. Do not put them in the release interface.

### F27 (minor). Order of the emulator script

- **Problem.** Step 15 installs a debuggable build over the release build. Steps 16-19, including the cold-start
  timings, then run on debug.
- **Fix.** Move step 15 to the end. Build both APKs before wave 5, since §8 says no Gradle runs during wave 5.

### F28 (minor). Release checklist

- **R8 [V].** Code shrinking is off (app/build.gradle.kts:49 `isMinifyEnabled = false`), so the AIDL stubs, the job
  service and the isolated service need no keep rules. Say so in WP14, and leave proguard-rules.pro alone or mark it
  unmaintained.
- **`<cross-platform-transfer>`.** Do not add it. It requires `platform="ios"` plus `<platform-specific-params
  bundleId teamId contentVersion>` [D] https://developer.android.com/identity/data/autobackup (Android 16 QPR2,
  API 36.1). Without a matching iOS app nothing can be transferred. Document this instead of leaving it [A] (:347, :957).
- **Version.** WP14 must set versionCode 9 (F1).

### F29 (minor, an opportunity). Starting the service from the background

- **Evidence [D].** https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
  (Android 12+, target 31+) lists among the exemptions: "The user turns off battery optimizations for your app."
- **Opportunity.** The A3 check already leads the owner to that setting. Change `ObjectiveWorkController.shouldRun`
  (ObjectiveWorkController.kt:53) to `running && (inForeground || ignoringBatteryOptimizations)`. An objective
  resumed automatically after a crash then gets its service without the app being opened. D25 (:756) should say
  "fixed when battery optimisation is off", not "final".

### F30 (minor). Backup size and interruption

- **Restore.** A restore of up to 1 GB or 200,000 memories in one Room transaction (:379-382) grows the WAL by roughly
  the database size. Check `getAllocatableBytes` (about twice the file size) before starting, and show progress.
- **Save.** A save is cut off if the owner leaves the app and the process is cached. Truncation is detected, but the
  screen should say "keep the app open while saving".
- **Key derivation.** PBKDF2 at 600,000 iterations may take tens of seconds on TCG [A]. Add a fixed PBKDF2-SHA256
  test vector, checked both on the JVM and on the device.

## 2. Recommended re-cut (three tiers)

**Tier A: ship in 0.6.0, never cut.**
- **Background.** Wake lock (with F8), chats in the service, background check, result notifications, scope exception
  handler.
- **Recovery.** The Recovery screen with "Start over" only (with F10), extraction rules.
- **Upkeep.** The upkeep job (with F3).
- **Scripts.** The isolated script process (with F4-F6, F21-F22) and D2.
- **Phone operator fixes.** D4-D8, D10-D12.
- **Collaboration.** C3 caps, C4, C7.
- **Recovery behaviour.** R3, R4 parts 1-2 (D28), D20-D23, D29.
- **Speed.** P1, P2, P5, P7, P8, using projections only (no `tasks` rebuild).
- **Owner-reported.** Typing bug #42.
- **Usage.** `usage_hours` with daily limits and the Usage tab (counts, tokens, reliability).
- **Live network view.** Team view live, without replay; dashboard N3/N6.
- **Small fixes.** Activity paging (D41 "Show older"), D9, D35-D38, D40, Reasoning and Coding made real.

**Tier B: ship only if converged by wave 4, round 2; otherwise switched off or removed and documented.**
- C1 questions, with Q3 defaulting to off if not converged.
- C2 notes.
- WP3 backup file: save, and restore into a fresh app. Freeze the format and have it reviewed in wave 1.
- Activity text search.
- Getting started card.
- Safe start: the "two starts without ok" rule only, filtered per F6.

**Tier C: cut now and document as not built.**
- Team view replay.
- Speed histograms (`ttft_hist`, `answer_hist`).
- The `provider_checks` table and connection history.
- Model prices and cost.
- The `app_exits` table, `setProcessStateSummary` and "times Android closed the app". Read the exit history live
  when needed: the system keeps it.
- SafetyCopy and "Go back to the copy".
- The `tasks` rebuild.
- "This app on your phone" footprint and the problem report.
- G6.
- The photo button. Vision keeps "(test)", which the owner's rule allows.

This removes three of the five new tables (`provider_checks`, `model_prices`, `app_exits`) and about a third of the
review surface. It keeps every promise in ARCHITECTURE.md §8 and STATUS "Next: Phase 6".

## 3. Corrections to the emulator script (§10)

| Step | Change |
|---|---|
| 1 | versionCode 9; assert 16 `uses-permission` and the same three exported components with aapt2 on the signed APK |
| 2 | "Before" = AIControlCenter-0.5.2.apk (`.hq`), not 0.5.1 |
| 3, 7, 17 | `com.aicontrolcenter.hq` |
| 4-5 | Background the app with `am start -n com.android.stk/.StkMenu` or KEYCODE_SLEEP, not Home; check `dumpsys activity` |
| 6 | Accessibility component `com.aicontrolcenter.hq/com.aicontrolcenter.device.screen.OperatorAccessibilityService` |
| 7 | Dismiss the crash dialog after each `am crash`; check that the `:scripts` exits from step 12 do not count (F6) |
| 8-10 | Provider at `http://10.0.2.2:8765` so limits apply and airplane mode cuts it |
| 12 | Record cold bind and engine-start times; the 5 s default is asserted on a warm run; three kills then `1+1` within the restart back-off (F5) |
| 15 | Move to the end; both APKs built before wave 5 |

## 4. Additions to the WP6 spike (wave 1)

The plan's five questions (:480-485) are good. Add:

- **Q6.** Three watchdog kills within 60 s: when does the fourth run start? Does the restart back-off apply after
  `unbindService` plus a fresh `bindService`? (F5)
- **Q7.** A round trip with a 200,000-character input and a 100,000-character result through the chosen transport,
  while an accessibility event stream is active. (F4)
- **Q8.** After a memory-hog OOM in the isolated process: which `processName` and reason appear in
  `getHistoricalProcessExitReasons`, and is a crash dialog shown? (F6)
- **Q9.** Cold bind plus Rhino start time on TCG, against the 30 s bound. (F21)
- **Q10.** Can the isolated process read a `createReliablePipe()` descriptor passed from the app, or does SELinux
  `isolated_app` deny it?

## 5. Ownership matrix: changes needed

| File or area | Plan | Problem | Proposed owner |
|---|---|---|---|
| AndroidManifest.xml (wave 2) | WP2 block + WP6 block | Two owners | WP1a pre-inserts both entries in wave 1 |
| AiControlCenterApp.kt `isIsolated` line, `dataLayer`, `UpkeepGraph` hook | WP6 line, WP8 rest | Needed in wave 2 | WP1a pre-inserts in wave 1 |
| core/agents/AgentAvailability.kt (vision) | WP4 item | Not WP4's | WP1a (signature) + WP7 (callers) |
| framework/registry/* | none | P8 and vision callers | WP7, or WP8 for P8 |
| framework/agents/AgentPromptComposer.kt | none | Images | WP5 |
| ui/components/* | none | Typing bug #42, shared components | New WP "Text input" in 3a |
| ui/dashboard/Attention.kt, DashboardSections.kt | none | Attention items, badges | WP10 |
| ui/shell/ShellViewModel.kt, NavigationChrome.kt | none | "Waiting for you" label, badge | WP8 |
| ui/models/ProviderDetailScreen.kt | none | Placing ProviderHistoryCard | WP11, or cut (Tier C) |
| Per-package wiring | WP8 3a | Big-bang integration | `di/wiring/*` per package, hooks from WP1a |

## 6. Claims checked and found correct

- `specialUse` has no time limit. Only `dataSync` and `mediaProcessing` (6 h in 24 h, target 35+) and `shortService`
  are limited [D] https://developer.android.com/develop/background-work/services/fgs/timeout (Android 15+).
- Battery optimisation off is an exemption for starting the service from the background, alongside notification
  actions and leaving a visible state [D, restrictions-bg-start, Android 12+].
- On Android 16, jobs running beside a foreground service count against job quotas [D, Android 16 all apps]. That
  supports keeping objectives in the app's own service, as the plan does.
- `ApplicationExitInfo`, `getHistoricalProcessExitReasons` and `setProcessStateSummary` exist from API 30. So do
  `REASON_FREEZER` (33), `REASON_PACKAGE_UPDATED` (34) and `REASON_MEMORY_LIMITER` (37.2) [D; V javap
  android-37.2.jar].
- These APIs exist with the stated levels [V javap]: `Process.isIsolated()` (28),
  `ServiceConnection.onBindingDied` (26), `onNullBinding` (28), `bindIsolatedService` (29),
  `ParcelFileDescriptor.createReliablePipe`, `StorageManager.getAllocatableBytes` / `getUuidForPath`,
  `StorageStatsManager.queryStatsForPackage`, `ValueAnimator.areAnimatorsEnabled` (26),
  `JobInfo.Builder.setPersisted` / `setRequiresDeviceIdle`.
- A non-persisted job needs no `RECEIVE_BOOT_COMPLETED`. Rule 22's three added normal permissions are sufficient. The
  APK has 13 today [V aapt2].
- WorkManager is not in the offline cache [V reader]. Lint 32.5.2, matching AGP 9.5.2, is cached [V], so the plan's
  lint check of the extraction XML can run offline.
- The native libraries in the 0.5.2 APK (libsqlcipher, libdatastore_shared_counter, libandroidx.graphics.path) have
  0x4000 LOAD alignment and pass `zipalign -c -P 16` [V]. That is 16 KB page-size ready, so Android 16's
  compatibility dialog is not a risk.
- Workspace and graph shares already set `FLAG_GRANT_READ_URI_PERMISSION` explicitly (ProductionPorts.kt:112-116,
  GraphExportFiles.kt:41-47 [V]). Android 18's end of implicit URI grants for `ACTION_SEND` [D, Android 17 all-apps
  page] therefore does not affect the app.
- Android 17's per-app Keystore limit (50,000 / 200,000 keys) is irrelevant at 2 keys, even with F10's generations.

## Sources

- https://developer.android.com/reference/android/app/ApplicationExitInfo (API 30+; 37.2 constants)
- https://developer.android.com/reference/android/app/ActivityManager (setProcessStateSummary, getHistoricalProcessExitReasons; API 30+)
- https://developer.android.com/reference/android/content/ServiceConnection (all versions; onBindingDied API 26)
- https://developer.android.com/reference/android/os/IBinder (getSuggestedMaxIpcSizeBytes, API 30)
- https://developer.android.com/develop/background-work/services/fgs/timeout (Android 15+)
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start (Android 12+)
- https://developer.android.com/about/versions/16/behavior-changes-all (Android 16)
- https://developer.android.com/about/versions/17/behavior-changes-all (Android 17)
- https://developer.android.com/identity/data/autobackup (Android 12+; cross-platform transfer Android 16 QPR2)
- https://developer.android.com/develop/background-work/services/alarms (Android 6+, Doze limits)
