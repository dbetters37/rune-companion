# WP6 spike: scripts in their own process. Verdict and evidence

Branch `p6/spike-scripts` (throwaway, never merged): commits 30a20a1, 0cbb76e, ae049d4 and 7c01d0d (a copy of this file
as spike/spike-scripts.md) on top of 9c739d5. Full suite on the branch: 2,485 tests, 0 failed.
Worktree /home/user/p6-spike. Device runs on 2026-11-25, 06:18 to 06:58 (emulator time). The emulator is stopped.

## Verdict

**Use the isolated design (`android:isolatedProcess="true"`).** All three deciding questions pass:

- **Q3 passes.** The watchdog's `Process.killProcess(Process.myPid())` ended the script process every time (12 of 12 kills).
  The app got `binderDied` and `onServiceDisconnected` 56-135 ms later. The app's process never changed.
- **Q7 passes.** A 200,000-character input and a 100,000-character result made the round trip through pipes 3 times
  while accessibility events flowed (63-138 events during each run). Two-way Strings of 300,000 characters still
  worked, but a oneway String of that size failed with `TransactionTooLargeException`.
- **Q10 passes.** SELinux `isolated_app` allows the script process to read and write pipes from
  `createReliablePipe()` and from `createPipe()`, in both directions, whichever side made the pipe. A `closeWithError`
  in the script process reached the app.

The shared-UID `:scripts` fallback is **not needed**. As a check, the spike also ran the fallback. It reads the app's
files ("main-secret"), connects to the network, and stays alive after `unbindService`.

The isolated design works only with **eight changes to the WP6 design**, listed under "Design changes". Four of them
are blocking: without them the design as written fails on the device.

1. **Instance names.** `"js-0"` is refused with "Illegal instanceName". Use `js0` and `js1`.
2. **Rhino in the script process.** Rhino must be initialised on the service's main thread. Otherwise every script
   fails with "Regular expressions are not available."
3. **Pipe status.** The sender's `close()` of a `createReliablePipe()` end it handed over eats the status. The
   receiver's `checkError()` then says "Remote side is dead" (10 of 10 runs). Use plain pipes with a length prefix.
4. **Isolated early return.** `AiControlCenterApp.onCreate` must return at once in the isolated process.
   `NotificationManager.cancel` throws `NullPointerException` there.

The other four:

5. After a death, unbind and then bind again. If the binding is kept, Android's restart waits grow 1 s → 4 s → 16 s,
   and reach 30 minutes after two crashes.
6. The watchdog works per run. The script process needs a quiet uncaught-exception handler.
7. Engine-start bounds on the software emulator need room. D2 is required.
8. Exit history: script exits share the app's per-package list.

## Setup (what ran)

- **Device.** Emulator AVD tcg34: Android 14 `UE1A.230829.036.A1`, aosp_atd userdebug, x86_64, software CPU
  emulation (TCG, no KVM), 4 vCPU, 3 GB, `dalvik.vm.heapgrowthlimit=192m`. Freezer on (`use_freezer=true`,
  `freeze_debounce_timeout=10000`).
- **Spike app.** New module `:spike` (`com.aicontrolcenter.spike`, debug build, minSdk 28, targetSdk 36), 10.9 MB. It
  depends on `:framework` and runs the real `JsSandbox` (Rhino 1.9.2).
  - `ScriptService`: `isolatedProcess="true"`, `process=":scripts"`, not exported. It has a hard self-stop after 480 s
    so that no endless script can outlive the spike.
  - `SharedScriptService`: `process=":scripts2"`, the fallback with the app's UID.
  - `SpikeA11y`: an accessibility service that counts events and reads the window root on each one.
  - `FrontActivity`: an activity in process `:front`.
  - `SpikeActivity`: the driver. It runs steps sent from adb and logs to logcat tag `SPIKE`.
  - AIDL: spike/src/main/aidl/.../ISpikeRunner.aidl and ISpikeResult.aidl.
- **Host.** `spike/run-step.sh STEP` sends `am start ... --es q STEP`, waits for `DONE STEP`, saves the whole logcat
  (`STEP.logcat`) and the SPIKE lines (`STEP.spike`), and polls the focused window every 4 s (`STEP.focus`).
- **Steps run.** PROBE (3 times; the first two runs exposed findings F-A and F-B), PIPES, KILL, WATCHDOG_KEEP and
  WATCHDOG_FRESH (twice each), OOM, EXITS (twice), FREEZE, an hq launch on top, UNFREEZE, RACE, BIG (4 times).
- **Evidence.** All logs are in `scratchpad/phase6/spike-logs/`. The quotes below are from them; times are
  `elapsedRealtime` in ms unless the line says otherwise.
- **JVM side first.** framework/src/test/.../SpikeJvmSizesTest.kt (bounded scripts only) printed:
  `cold first run 323 ms; warm 59 ms`; `200k/100k run 99 ms; inputChars=199998 resultChars=100000; as AIDL Strings
  (UTF-16): run(code,input) ~390 KB, done(result) ~195 KB; ... Largest JsLimits inputs: code 50000 + input 200000
  chars = 488 KB UTF-16; result 100000 + log 16000 chars = 226 KB UTF-16 (UTF-8 up to 3 bytes a char: 732 KB request)`.

## Answers

### Q1. Can the app's `Process.killProcess` end the isolated process? **No.**

KILL.spike: `Q1 isolated pid=4186: Process.killProcess then Os.kill(SIGKILL): errno=1 (EPERM) kill failed: EPERM
(Operation not permitted); 3 s later pid() -> 4186; died event=null`. `killProcess` fails silently. The kernel refuses
the signal because the UIDs differ (99009 against 10109). For comparison, the fallback with the app's UID was killed:
`Q1 shared-UID pid=4040: Process.killProcess -> died after 162 ms`. Android then restarted it 1000 ms later, because the
binding was still held.

### Q2. Does `unbindService` end it, and how fast? Does a frozen cached isolated process stop using the processor? **Yes, 94-594 ms. Yes.**

- **Idle process.** Unbind to `binderDied`: 187, 94, 113, 109 (plain `bindService`), 271, 213, 287 and 594 ms. In
  other steps: 141-311 ms.
- **While an endless script spins.** `Q2 spinning unbind->binderDied=144 ms`. ActivityManager logged:
  `Killing 4186:com.aicontrolcenter.spike:scripts:com.aicontrolcenter.spike.ScriptService:js_kill/u0a109i9 (adj 0):
  isolated not needed`. The exit record is `reason=13 (OTHER KILLS BY SYSTEM) subreason=17 (ISOLATED NOT NEEDED)`.
- **The fallback is not ended by unbind.** `shared-UID unbind->binderDied=none within 20 s (process stays, cached)`;
  `onDestroy` ran, but the process stayed. In the fallback the app would have to call `killProcess` itself.
- **Freezer, part 1 (FREEZE.monitor).** An endless script ran in the script process. `FrontActivity` (another process
  of the app) covered the driver, so the app became the "previous app": `oom_score_adj` 700 for the app and the script
  process. Nothing was frozen (`cgroup.freeze=0`) for 150 s. The script used a full core the whole time: CPU ticks went
  from 1442 to 21497, about 99 ticks a second at 100 ticks a second.
- **Freezer, part 2 (FREEZE2.monitor).** Opening another app on top (`com.aicontrolcenter.hq`) made the spike cached:
  `oom_score_adj` 900 for the app and 910 for the script process. Both were frozen (`cgroup.freeze=1`) by the next
  sample, under 20 s later. The script's CPU ticks then stayed at 27242 for 90 s.
- **Freezer, part 3 (UNFREEZE).** Back in front, the script process was thawed: `pid() call -> 4957 in 30 ms`. Then
  `unbind->died 378 ms`.
- **What it means.** A frozen script costs nothing. While the app is the previous app, or holds its foreground
  service, it is not frozen, and only the watchdog or the app's unbind stops a runaway script.

### Q3. Does the watchdog's `Process.killProcess(Process.myPid())` end it, and does the app see `binderDied`? **Yes and yes.**

- **Every kill worked.** 12 watchdog kills in WATCHDOG_KEEP and WATCHDOG_FRESH, two runs each, plus 2 in BIG; each
  ended the process.
- **What the app saw.** Kill to `binderDied`: 64, 75, 67, 128, 84, 105, 105, 108, 135, 84, 66 and 67 ms. Kill to
  `onServiceDisconnected`: 56-112 ms. In BIG run 1, under load: 427 ms.
- **Callbacks never seen.** `onBindingDied` and `onNullBinding`.
- **The app's process never changed.** Every step logged `samePid=true`.
- **Timing of one overdue run.** Runs used `timeoutMs=1000`. `JsSandbox` reported "did not stop within its time limit
  and was abandoned" after 3.9-4.6 s (the time includes engine start, D2). The watchdog killed the process 2.6-2.8 s
  later, at its deadline of limit + grace 2 s + 3 s.
- **Exit record.** The kill is filed as `reason=2 (SIGNALED) status=9` under the isolated UID. The app does not see it
  (see F-F).

### Q4. A 4 MB transfer through a pipe. **Works in every direction, 0.27-0.82 s on the emulator, CRC equal.**

PIPES.spike, 4,194,304 bytes, CRC 2447366648. Each case names who made the pipe and who read it.

| Case | Reliable pipe | Plain pipe |
|---|---|---|
| A: app made it, script reads | 268 ms | 508 ms |
| B: app made it, script writes | 581 ms (with `closeWithError`: 447 ms) | 818 ms (with `closeWithError`: 612 ms) |
| C: script made it, app reads | 473 ms | 438 ms |
| D: script made it, app writes | 276 ms | 313 ms |

### Q5. Process start time. **Bind to connected: 2.0-3.9 s when the emulator is quiet; 6.1-8.7 s just after boot or under load.**

- **Isolated, `bindIsolatedService`.**
  - After boot (PROBE runs 1-2): 8066, 6125 and 7811 ms.
  - Later: 6584 (first after reinstall), 2179, 2062, 3511, 2981, 2771, 3239, 2801, 2256, 2772, 3576, 3857 and
    3014 ms.
  - In BIG, with accessibility events and animation running: 6819, 7185 and 8664 ms.
- **Plain `bindService` (the API 28 path).** 6340 and 2550 ms.
- **Fallback with the app's UID.** 11712 and 2138 ms.
- **Breakdown, PROBE k=2 (quiet).** Bind call 953252, process forked 954075 (+823), `Application.onCreate` 954670
  (+595), service `onCreate` 954800 (+130), then Rhino initialised on the main thread (≈600 ms), `onBind` 955407,
  `onServiceConnected` 955431. 2179 ms in all.

### Q6. Three watchdog kills within 60 s, then the time to the fourth run. **Kept binding: about 19 s of waiting, then the run. Fresh bind: no waiting.**

**Kept binding** (`BIND_AUTO_CREATE` held; Android restarts the service by itself):

- **Waits Android chose.** ActivityManager logged
  `Scheduling restart of crashed service ...ScriptService:js_k in 1000ms`, then `in 4000ms`, then `in 16000ms`.
- **Restarts the app saw** (death to `onServiceConnected`): 3990, 6486 and 18322 ms in run 1; 4094, 6125 and 18929 ms
  in run 2. The three kills fell at 6.9, 18.0 and 31.2 s.
- **Fourth run.** Third death to the fourth result: 22.7 s in run 1 (1 s limit) and 27.7 s in run 2 (30 s limit; the
  cold `1 + 1` took 8.7 s).

**`unbindService` at once, then a fresh `bindIsolatedService` with the same instance name** (the plan's F5 rule):

- **No wait.** Each new bind connected after 2.3-3.2 s. ActivityManager still logs "Scheduling restart ... in 1000ms"
  for every death, but the unbind cancels it.
- **Fourth run.** Third death to the fourth result: 7.5 s in run 1 and 12.6 s in run 2 (including the 8.7 s cold run).
  The three kills fell at 6.9, 17.3 and 26.6 s.

After two *crashes* (as opposed to kills; see Q8) a kept binding waits **30 minutes**.

### Q7. A 200,000-character input and a 100,000-character result through pipes while accessibility events flow. **Works.**

BIG.spike, final run, with `SpikeA11y` enabled and a label redrawn every 16 ms:

| Run | Request read by script | Accessibility events during the run | Outcome read by app | Round trip | Result |
|---|---|---|---|---|---|
| k=1 | 200,095 bytes in 116 ms | 112 | 100,155 bytes in 183 ms | 33.4 s | `Finished`, `resultChars` 100000, not truncated |
| k=2 | 125 ms | 80 | 126 ms | 22.4 s | the same |
| k=3 | 293 ms | 63 | 114 ms | 18.0 s | the same |

- **Totals.** 491 events and 980 window nodes read in the step. Run 2 of BIG (BIG-run2-stalewatchdog) gave the same
  result twice, with 138 and 88 events.
- **The script itself is slow on the emulator.** Its work took 17-42 s on the emulator against 99 ms on the JVM. With
  the default 5 s limit it was abandoned, and the watchdog then ended the process (BIG-run1-5s). The device test needs
  a large limit for this case.
- **Binder Strings for comparison (F4).** Two-way calls of 50,000, 200,000 and 300,000 characters (585 KB UTF-16)
  worked.
  - A oneway call of 300,000 characters failed: `TransactionTooLargeException: data parcel size 600132 bytes`.
  - 600,000 characters failed both ways (`data parcel size 1200104 bytes`).
  - `BpBinder: Large outgoing transaction of 400104 bytes` was logged for 200,000 characters.

### Q8. After an out-of-memory error in the isolated process: which `processName` and reason, and is there a crash dialog? **`<process>:<service class>:<instance>`, `REASON_CRASH`, no dialog. A quiet handler leaves no record.**

The script process's heap limit is 192 MB.

**a) Uncaught `OutOfMemoryError`, Android's default handler (OOM.spike, exit-info).**
- logcat shows `FATAL EXCEPTION: spike-hog` and DropBox `data_app_crash`.
- The record the app gets from `getHistoricalProcessExitReasons(packageName, 0, 0)`:
  `process=com.aicontrolcenter.spike:scripts:com.aicontrolcenter.spike.ScriptService:js_oom_a reason=4 status=0
  importance=100 desc=crash`, with `realUid=99018` and `packageUid=10109`.
- **No crash dialog.** The focused window stayed `SpikeActivity`, and no "Application Error" window appeared.
- **Back-off.** First crash: `Scheduling restart ... in 1000ms`. Second crash, 5 s later:
  `... in 1800000ms` (30 minutes; `service_crash_restart_duration=1800000`). The service was not restarted within 90 s.

**b) The F6 fix: `Thread.setDefaultUncaughtExceptionHandler` that reports, then `killProcess(myPid())`.**
- The app got `quiet handler: OutOfMemoryError on spike-hog` after 258-265 ms. The process died after 321-329 ms.
- No DropBox entry.
- The exit is filed as `SIGNALED status=9` under the *isolated* UID and is **not returned to the app**.
- The back-off stayed normal: 1000 ms, then 4000 ms. The runtime still prints a `FATAL EXCEPTION` log line; that line
  is only a log.

**c) A JavaScript memory hog inside `JsSandbox`.** `Stopped(reason=The script used too much memory.)` after 40.9 s on
the emulator. The process stayed alive (`pid()` afterwards 4826). The memory check now measures the script process
only (D3).

### Q9. Cold bind plus Rhino start time on the software emulator, against the 30 s bound. **Bind ≤ 8.7 s. A first run is 6.3-8.7 s when quiet and 25.7-29.2 s under load. Too close to 30 s for the emulator test.**

- **Rhino's `Context` class initialised on the service main thread.** 473-1636 ms.
- **First `JsSandbox.run("1 + 1")` in a new process.** 8122 and 7592 ms. Run k=3 had the default 5 s limit and was
  stopped: "The script ran longer than 5000 ms." The fourth runs of Q6 took 8651 and 8739 ms. With accessibility
  events and the animation running (BIG warm-up): 26020, 29190 and 25719 ms.
- **Warm `JsSandbox.run("1 + 1")`.** 3710-5173 ms; once over 5 s it was stopped.
- **Where the warm time goes.** In-process phases: `gc=38-66 ms initSafeStandardObjects=349-472 ms
  eval(regex,1+1)=85-105 ms`. The rest, about 3 s, is the guards and helpers prelude that `JsSandbox` evaluates on
  every run, and all of it counts against the script's limit.
- **For reference.** In the main process: 10.9-28.5 s first, 5.0-7.2 s warm. On the JVM: 323 ms and 59 ms.
- **Cold bind plus first run.** 8.4-14.8 s when quiet; about 35 s under load (7.2 s bind and 26-29 s first run).

### Q10. Can the isolated process read and write a `createReliablePipe()` descriptor from the app? **Yes.**

- **Data.** PIPES cases A and B passed, with both reliable and plain pipes.
- **Status from the script process.** B with `closeWithError` gave the app
  `checkError=IOException: Remote error: spike: the script process reports an error`.
- **Pipes made in the script process.** The script process can also make pipes, reliable or plain, and hand them to
  the app (cases C and D).
- **SELinux.** The only denial logged was harmless, at process start:
  `avc: denied { find } ... name=content_capture scontext=u:r:isolated_app`.

## Further findings that change the WP6 design

**F-A (blocking). The plan's instance names are refused.**
`bindIsolatedService(..., "js-0", ...)` throws `IllegalArgumentException: Illegal instanceName`, from
`ActivityManagerService.validateServiceInstanceName` (run1-hyphen-PROBE.spike; PROBE.spike `instanceName 'js-0':
refused`). Names with letters, digits and `_` work, such as `js_p1` and `js_big`. Use `js0` and `js1`.

**F-B (blocking). Rhino's regular expressions vanish in the script process.**
- **Cause.** Rhino 1.9.2 loads its RegExp engine once per process in `Context`'s static initialiser:
  `ScriptRuntime.loadOneServiceImplementation` calls `ServiceLoader.load(RegExpLoader.class)`, which uses the thread's
  context class loader (checked with javap on the jar; `META-INF/services/org.mozilla.javascript.RegExpLoader` is in
  the APK). Binder threads have `contextClassLoader=null` (`binderThreadContextLoader=null`).
- **Effect.** When the first `JsSandbox` run started from a binder call, every script failed:
  `ScriptError(message=Regular expressions are not available., line=295)` (run2-regex-PROBE.spike).
- **Fix that worked.** Call `org.mozilla.javascript.Context.getCurrentContext()` once in the service's `onCreate`, on
  the main thread, whose context loader is the app's `PathClassLoader`. It costs 0.5-1.6 s on the emulator.
  Alternative: set `Thread.currentThread().contextClassLoader` on every thread that runs scripts before first use.
- **In the main app.** Today's first use there worked (`Q9 in main process ... Finished(2)`). WP6's
  `JsEngineContract` should still include a regular-expression script.

**F-C (blocking for WP1d). `AiControlCenterApp.onCreate` would crash every script process.**
`Application.onCreate` runs in the isolated process (process name
`com.aicontrolcenter.spike:scripts:com.aicontrolcenter.spike.ScriptService:js_p1`, `isolated=true`, uid 99000). There,
`NotificationManager.cancel()` throws
`NullPointerException: Attempt to invoke interface method '...INotificationManager.cancelNotificationWithTag...' on a
null object reference`. `ScreenSessionNotifications.cancel(this)` is the first line of today's `onCreate`, so
`if (Process.isIsolated()) return` must come before it, as the very first statement after `super.onCreate()`.

The Application object is also *constructed* in the script process, so its field initialisers run there before
`onCreate`. That includes the planned
`appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + AppProcessHooks.scopeContext(this))`. They must
touch no system service, file or database; make anything that does `by lazy`.

**F-D (blocking). Reliable-pipe status is eaten by the sender's own `close()` (RACE.spike, 10 runs each).**

| Variant | Clean `checkError` |
|---|---|
| The script made a reliable pipe, handed the read end over in a oneway `in` parameter, and closed its own copy at once | 0/10 ("Remote side is dead") |
| The same, with the copy closed 3 s later | 10/10 |
| A plain `createPipe()` with a 4-byte length prefix | 10/10, frame complete |
| The app made a reliable pipe, handed the read end over in oneway `runJs`, and closed its copy at once | 0/10 |

- **The data was always complete** (CRC equal). Only the status check fails.
- **Cause [K: AOSP ParcelFileDescriptor].** `close()` → `writeCommStatusAndClose()` first "reads off any remote
  status" on the shared comm socket, so the sender takes the status meant for the receiver. Ends handed over as AIDL
  return values are closed with `SILENCE` (`PARCELABLE_WRITE_RETURN_VALUE`), which is why cases C and D were clean.
- **Rule for WP6.** Use plain `createPipe()` with a length prefix (or length-prefixed parts). The reader enforces its
  caps and a timeout. If EOF comes before the announced length, the answer is "the script process ended before it
  answered". Use `createReliablePipe` only where the sending process hands its end over as a return value. Never rely
  on `checkError()` after handing an end over as an `in` parameter.
- **The request.** Do not put the input inside the request JSON as an escaped string: escaping can double it, or
  multiply it by 6 for control characters. Send a small header (limits, lengths), then the code and the input as raw
  UTF-8 parts. Cap them in characters as `JsLimits` does (50,000 and 200,000) or in bytes at 3× that. The plan's
  600 KB byte cap is too low for non-ASCII text; the worst case is 732 KB from the JVM computation.

**F-E. Process names.**
- `bindIsolatedService` gives `<process>:<service class>:<instance>`
  (`com.aicontrolcenter.spike:scripts:com.aicontrolcenter.spike.ScriptService:js_p1`). For the real app that is
  expected to be `com.aicontrolcenter.hq:scripts:com.aicontrolcenter.app.scripts.ScriptProcessService:js0`.
- Plain `bindService` gives `<pkg>:scripts`.
- The fallback gives `<pkg>:scripts2`.

None equals the main process name (`com.aicontrolcenter.spike`), so rule 31's filter
`processName == applicationInfo.processName` works.

**F-F. Exit history (affects WP2 and rule 31).**
- **Kills by Android are in the app's history.** "Isolated not needed" after an unbind, and crashes, are filed under
  the package UID. `getHistoricalProcessExitReasons(pkg, 0, 0)` returns them, in the same per-package list as the main
  process's own exits. The call returned 16 records each time (EXITS-1.spike and EXITS.spike: `count=16`), so script
  exits push older main-process records out.
- **Self-kills are invisible to the app.** The watchdog's kill and the quiet handler's kill are filed under the
  isolated UID (exit-info-2.txt: `Historical Process Exit for uid=99010` ...).
- **What WP2 should do.** Read the history at every start with `maxNum = 0`, filter by process name, and keep the "last
  processed" timestamp, as planned. A session's last main-process exit is followed by at most the exits of the script
  processes Android ends with it, so it stays in the list.

**F-G. The watchdog must be per run.**
- **What went wrong.** The spike's first watchdog killed the process when *any* script thread was alive at an earlier
  run's deadline. In BIG-run2 the watchdog of run k=1 ended the process during a legitimate run k=3:
  `watchdog: script threads [aicc-js-4] still running at 2261852; killing own pid=5968`.
- **What works.** Each run has its own deadline and its own "ended cleanly" flag. Every outcome except "abandoned" sets
  the flag. This works (BIG final run).

**F-H. The fallback reuses its process across binds.**
The `:scripts2` process stayed cached after unbind and was reused 80 s later (`connected ... sinceBindCall=327`), with
whatever an escaped script had left in it. This is one more reason against the fallback.

## Design changes for WP6 (wave 2) and the packages it touches

1. **Manifest** (WP1d or WP8): `<service android:name=".scripts.ScriptProcessService" android:isolatedProcess="true"
   android:process=":scripts" android:exported="false"/>`. Bind it with `bindIsolatedService(intent,
   BIND_AUTO_CREATE, "js0"/"js1", executor, conn)` on API 29+, and with `bindService` on API 28 (F-A).
2. **`AiControlCenterApp.onCreate`** (WP1d): `super.onCreate(); if (Process.isIsolated()) return` before anything
   else (F-C).
3. **`ScriptProcessService.onCreate`**: initialise Rhino on the main thread (F-B). Install the quiet uncaught-exception
   handler: report "the script used too much memory" or "the script process failed", then `killProcess(myPid())`. With
   it there is no crash record, no DropBox entry, no 30-minute back-off, and nothing in the app's exit history (Q8b).
4. **Transport** (rule 11): plain `createPipe()` with length-prefixed frames in both directions, and no reliance on
   `checkError` (F-D). Two-way String parameters up to 64 KB stay fine (rule 11). Oneway parcels fail at about
   600 KB (Q7).
5. **Death** (F5): on `binderDied` or `onServiceDisconnected`, `unbindService` at once and bind afresh at the next run
   (Q6: 2.3-3.2 s instead of 1, 4, 16 s or 30 minutes). Neither `onBindingDied` nor `onNullBinding` was seen, but keep
   handling them.
6. **Watchdog**: per run (F-G). In the script process: deadline = limit + grace 2 s + 3 s, then `killProcess(myPid())`
   (Q3: the app learns within about 0.1 s). In the app: unbind when the limit + 6 s has passed (Q2: the process ends
   within 0.6 s).
7. **Time bounds.** The bind bound of 30 s is fine (8.7 s at worst seen). On the emulator, a first run can take 29 s
   under load, so `engineStartMs` 30 s is too tight for the device test: use 90 s there and keep the 30 s default for
   phones. D2 is required for the plan's "5 s default on a warm second run" check, because a warm run's prelude alone
   takes 3.7-5.2 s on the emulator. With D2 the clock starts at the user's code. The 200,000/100,000 round trip needs a
   limit of 60 s or more on the emulator.
8. **`ScriptProcessDeviceTest`** (F26): the spike's driver steps map onto the planned test.
   - `1+1`: PROBE.
   - Endless loop three times, then `1+1`: WATCHDOG_FRESH.
   - The app's pid unchanged: `samePid` in every step.
   - `isIsolated` and no data folder: PROBE's probe.
   - A 4 MB read: PIPES.
   - Bind and engine-start times: PROBE.
   - A memory hog adds no main-process exit: OOM plus EXITS.

   Add a regular-expression script (F-B) and a status check after handing a pipe over (F-D).
9. **LIMITATIONS** (WP14): when the app is the "previous app" (no foreground service, another app opened), a runaway
   script uses a full core until the watchdog ends it, at most limit + 5 s. A frozen script process costs nothing.

## Limits of this evidence

- One emulator image: Android 14 with software CPU emulation. All times are emulator times, about 30-50× slower than a
  phone (JVM against emulator: 59 ms against 3.7 s for a warm run).
- API 28's single shared process and vendor phones were not tested.
- The spike app is a debug build without the real app's content providers. That providers do not start in the
  isolated process [K] was not checked with the real APK.
- The AOSP reasons quoted with [K] (the comm-status read in `ParcelFileDescriptor.close`, the crash-count back-off) are
  explanations. The measured behaviour above is the evidence.
