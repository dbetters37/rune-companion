# Hand-off from WP6 (scripts in their own process)

Branch `p6/wp6` (worktree `/home/user/p6-wp6`), from `6fb816d` (wave 2a merged). Head: see section 6.
Plan section "#### WP6", rules 11 and 31, D1-D3; the spike's verdict (`spike-scripts.md`) is followed where it differs
from the plan (section 2). The app now runs every agent script in an isolated process of its own, keeps every limit
that protects the app in the app's process, lets go of a script process that does not stop (Android then ends it), and
falls back to the in-process sandbox only when Android refuses to start the process at all, which the answer says.

## 1. What WP6 built (plan items)

| Plan item | What | Where |
|---|---|---|
| D2 (ships on its own) | A script's time starts at its own code: `JsSandbox` waits for the code to begin within `JsLimits.engineStartMs` (new, default 30 s; "The script engine did not get ready within N s; the phone may be busy. Nothing of the script ran."), then the time limit plus the 2 s grace from there. The instruction budget still counts the sandbox's setup (only the clock moved). `JsLimits.bindTimeoutMs` (new, default 30 s) bounds starting the process. `JsRunEvents` (`codeStarted`, `abandoned`) and a public `run(code, input, host, timeoutMs, events)` let the script process report both. Test hook: an internal `JsSandbox(limits, beforeCode)` | framework/operator/JsSandbox.kt |
| Spike F-B | `JsSandbox.prepareOnThisThread()` (Rhino loaded on the service's main thread); every script thread also gets the sandbox's class loader as its context class loader | JsSandbox.kt |
| Fallback said in the answer | `JsOutcome.note` (last field, default null) and `withNote`; `scripts.run_js` adds the note in parentheses after the console | JsEngine.kt, JsTools.kt |
| AIDL (rule 11, F4, F22, S9, S19) | `IScriptRunner { oneway run(in ParcelFileDescriptor request, IScriptHost host, IScriptResult result); int pid(); }`, `IScriptResult { oneway started(); oneway done(int status, in ParcelFileDescriptor outcome); }`, `IScriptHost { ParcelFileDescriptor read(String); String list(String); String write(String, in ParcelFileDescriptor); }` | app/src/main/aidl/com/aicontrolcenter/app/scripts/ |
| Pipes and frames (spike F-D) | Plain `createPipe()` ends only, never a reliable pipe's status. Length-prefixed binary frames: the request (header of limits, flags, then code and input), the outcome, workspace replies and texts. Texts are UTF-16 units with a count, so a lone surrogate arrives as written; every count is checked against its cap before a byte of the text is read; nothing nested, nothing escaped. Outcome cap from `JsLimits`: 64 + 2 × (max(result, 2,000) + console chars) + 4 × console lines bytes = 234,064 at the defaults; read on IO within 10 s | app/scripts/ScriptFrames.kt, ScriptPipes.kt |
| Host binder per run (F22) | `RunHost`: one call at a time (a call that comes while the previous reply is still being closed waits up to 1 s; a third is refused at once, so a script process cannot tie up the app's binder threads); write access; at most 20 new files (successes counted); texts ≤ `maxStringChars` both ways; paths ≤ 4,096; listing ≤ 32,768 characters; work on IO, never on the binder thread (a read answers at once with a pipe; list and write wait until the run's time is up); `end()` refuses every later call, interrupts waiting work and closes the run's pipes. The engine ends it the moment the script process says `done`, before any later call of its | app/scripts/RunHost.kt |
| Script process | `ScriptProcessService` (isolated, `:scripts`, manifest entry from WP1d): Rhino prepared in `onCreate`; the quiet end (`Thread.setDefaultUncaughtExceptionHandler`: tells every run its status, then `killProcess(myPid())`) installed only when `Process.isIsolated()`. `ScriptRunner`: each run on its own thread, the request read with caps, the sandbox kept per limits, `started()` at the script's code, the outcome through a fresh pipe with "its thread has ended" | app/scripts/ScriptProcessService.kt, ScriptRunner.kt (with `RemoteJsHost`) |
| Watchdog (spike F-G) | Per run, normal priority, in the script process: deadline = getting ready (`engineStartMs`) until the code begins, then the time limit, plus grace 2 s + 3 s; a run not ended cleanly by then is told `TIMED_OUT` and the process ends itself | app/scripts/ScriptWatchdog.kt |
| Engine (D1, D3) | `IsolatedJsEngine`: two slots, one process each on Android 10+ (`bindIsolatedService`, instance names `js0`/`js1`, spike F-A), one shared process on Android 9 (a run ended for the other run says so); a third run waits for a slot. Bounds in the app: bind (`bindTimeoutMs`, "The script engine did not start; the phone is busy. Nothing of the script ran."), getting ready (`engineStartMs` + 6 s), the script's time (+ 6 s), the answer frame (10 s); past one, the app unbinds. Death (`binderDied`, `onServiceDisconnected`, `onBindingDied`, `onNullBinding`), an answer saying the thread still runs, a cancelled caller, a quiet end (`OUT_OF_MEMORY`/`FAILED`), an answer over the caps: the app unbinds at once and the next run binds afresh (F5). A process that died while idle (`DeadObjectException` at hand-over) is replaced once and the run goes ahead. Android refusing the bind (false, or an exception): the in-process `JsSandbox` with its MAX_STUCK rule, with `FALLBACK_NOTE` in the answer | app/scripts/IsolatedJsEngine.kt, ScriptProcessLauncher.kt (`AndroidScriptLauncher`, callbacks on their own thread) |
| Wiring | `ScriptsWiring.production(context)` returns `ScriptsPorts(engine = { IsolatedJsEngine(app, it) })`; nothing binds until a script runs. `@Phase6Stub` removed from `ScriptsWiring` and `ScriptProcessService`. `TestApp` keeps the in-process sandbox (no engine) | app/di/wiring/ScriptsWiring.kt |
| F26 | Debug-only `ScriptProbeService` (isolated, `:scripts`, not exported, in app/src/debug/AndroidManifest.xml) with `IScriptProbe` (isolated, pid, uid, process name, try the app's data, try the network, exhaust memory with the quiet end) | app/src/debug/ |
| Contract | `JsEngineContract` (what protects the app, for every engine including a hostile one) and `HonestJsEngineContract` (scripts run as written) in testkit | testkit/src/main/.../contract/JsEngineContract.kt |

Texts the owner may meet (in a task's tool results), all app-written: `IsolatedJsEngine.FALLBACK_NOTE`, `BIND_SLOW`,
`PROCESS_ENDED`, `OTHER_RUN_ENDED`, `OUT_OF_MEMORY`, `FAILED`, `BAD_REQUEST`, `UNREADABLE`, `NO_ANSWER`;
`JsSandbox.engineSlow(ms)` and `JsSandbox.ABANDONED_TEXT`; `RunHost.OVER`, `BUSY`, `READ_ONLY`, `TOO_MANY_FILES`,
`TIME_UP`, `NO_TEXT` (these reach the script as errors).

## 2. Deviations from the plan (and why)

1. **`IScriptResult.started()` added.** D2 moves the script's clock to its own code; the app keeps the bounds itself
   (rule 11), so it must learn when the code began. Oneway, carries nothing.
2. **Frames, not JSON** (spike F-D overrides the plan's "code, input and limits as JSON ≤ 600 KB"): a small binary
   header, then code and input as raw parts, capped in characters as `JsLimits` caps them. UTF-16 rather than UTF-8 so a
   lone surrogate in a script is not turned into "?" (which would change the script). Nothing the app reads from the
   script process is parsed as JSON (a nested frame could recurse); the folder listing the app sends is JSON, read by the
   script process.
3. **Outcome cap from the limits** (234,064 bytes at the defaults) instead of a fixed 300 KB: the same order, exact per
   field; a frame over any cap is not used ("The script's process sent an answer the app could not read, so it was not
   used.") and its process is let go of.
4. **The app's bound is the limit + 6 s** (spike design change 6), not "overdue by grace + 6 s" (= limit + 8 s). The
   same margin applies to getting ready. The watchdog in the script process acts at the limit + 5 s.
5. **The app lets go at once of a process whose run was given up** (the answer says its thread still runs), instead of
   waiting for the watchdog: the process stops burning the processor 3 s earlier and the next run never meets it. The
   watchdog stays as the backstop (tested).
6. **One call at a time waits up to 1 s** for the previous call's reply to be closed (the contract test found that a
   script process that had read the whole reply could call again before the app's writer had finished, and was refused);
   a call that comes while another waits is refused at once.
7. **`JsEngineContract` speaks its own small types** (`ScriptEngineUnderTest`, `ScriptHostUnderTest`, `ScriptEnded`,
   `ScriptLimitsUnderTest`), because `JsEngine` is in :framework and :testkit depends only on :core
   (testkit/build.gradle.kts is nobody's in wave 2). Each module passes its engine through an adapter of 40 lines
   (framework `JsContractAdapter`, app `ScriptTesting.kt`). Split in two: `JsEngineContract` (holds for a hostile engine)
   and `HonestJsEngineContract`.
8. **D2 moves the clock only.** Resetting the instruction count at the script's code too broke `JsRound4Test`, whose
   budget is meant to include the sandbox's setup ("A budget that the sandbox's own setup fits several times").
9. **The device test's "endless iterator loop" is bounded** (the workflow's machine-safety rule: no endless loop in a
   test): `new Set(o)` over an iterator whose `next` is the built-in `pop` bound to a one-million-element array. No
   function of the script is entered, so the sandbox cannot reach it (checked on the JVM: 200,000-instruction budget,
   ~0.7 s, not stopped); it runs tens of seconds on the emulator, far past the 1 s limit and the grace, and ends by itself
   if nothing ended it. On a fast phone it might end within the grace; the device gate runs on the emulator.
10. **Listing cap 32,768 characters** (rule 11's 64 KB of UTF-16): a folder of more than a few hundred entries is listed
    in part, without a marker for the script.
11. **The memory-hog device check** runs two hogs: a script hog (the sandbox stops it, the process stays: spike Q8c) and
    a Java out-of-memory error in the debug probe (the quiet end ends the process; no exit of the app's own process is
    added).

## 3. Changes needed in files WP6 does not own

None is needed for the build or the tests. For the docs (WP14, or WP8 if earlier):

- **docs/LIMITATIONS.md, "The JavaScript sandbox" (about :247-262).** Replace the end ("In both cases the script is
  reported as stopped at its time limit while its thread goes on … closing the app ends it …") with: "In both cases the
  script is stopped at its time limit plus a few seconds: scripts run in a sealed process of their own (Android's
  isolated process: no access to the app's files, database, keys or network), and the app lets go of that process,
  which Android then ends; the next script starts in a fresh one. While the app is not on screen and holds no
  foreground service, Android may freeze the script process, which then costs nothing; while it is the app you used
  last, a runaway script can use a full processor core for up to its time limit plus 5 seconds. If Android refuses to
  start the script process on a phone, the script runs inside the app as before, and its answer says so; there two
  scripts that do not stop still keep new ones from starting until the app is closed. On Android 9 both scripts share one
  process, so a script that does not stop ends one running beside it, whose answer says so."
- **docs/STATUS.md, Known rough edges (:489-492 and :504-507).** Remove "Moving scripts into a separate process that
  can be ended is planned for Phase 6" and the one-line-JavaScript-on-the-emulator item: D2 fixes it (the limit no
  longer includes starting the engine; tested on the JVM, and on the emulator by `ScriptProcessDeviceTest`).
- **docs/ARCHITECTURE.md, Scripts (:249).** Add: "JavaScript runs in an isolated process (`ScriptProcessService`,
  `:scripts`) behind `IsolatedJsEngine`; the app keeps every limit (a fresh host binder per run, frames with caps,
  bounds on starting, getting ready and running) and unbinds a process that does not stop."
- **WP8, architecture walks (optional).** `ScriptsWalkTest` (app/src/test/.../scripts) walks the production engine and a
  `RunHost` with `AgentFacingWalk`; `ProductionGraphDependencyTest` already reaches the engine through the
  `scripts.run_js` tool. A `RunHost` exists only during a run, so it cannot be a root of the graph walks; nothing to add
  there unless WP8 wants one built in the test.
- **WP8, merged-manifest tests.** The debug variant now also declares `.scripts.ScriptProbeService` (isolated,
  `:scripts`, not exported). The release merged-manifest test of rules 22 and 23 is unaffected; a debug one would list it.

## 4. Device tests to run (device gate of wave 2b)

`./tools/gw --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.aicontrolcenter.app.scripts.ScriptProcessDeviceTest`
(logcat tag `ScriptProcessDeviceTest` has every time: cold bind and first run, warm run, each stuck run, the 4 MB read,
the 200,000/100,000 round trip, the hogs). Bounds for the emulator: bind 60 s, getting ready 90 s.

| Test | Checks |
|---|---|
| `onePlusOneRunsInAnIsolatedProcessAndAWarmRunKeepsTheDefaultLimit` | `1+1` in a process whose pid is not the app's, no fallback note; a warm `1+1` with the default 5 s limit (D2); a regular expression (F-B) |
| `stuckScriptThreeTimesThenOnePlusOneWithinTheRestartBackOff` | three runs of the bounded uncounted loop, each Stopped within the limit + 6 s, the app's pid unchanged; then `1+1` at once (no restart back-off, Q6) |
| `aFourMegabyteReadAndALargeRoundTrip` | a 4 MB (4,194,304 characters) workspace read; 200,000 characters in, 100,000 out (Q7) |
| `aScriptMemoryHogEndsOnlyTheScriptAndAddsNoExitOfTheApp` | a script that fills memory is stopped, the next run works, no main-process exit record added |
| `theScriptProcessIsIsolatedAndCannotReachTheAppsData` | through the debug probe: isolated, another pid and uid, process name `…:scripts…`, the app's data folder cannot be listed and a file in `filesDir` cannot be read, no network |
| `anOutOfMemoryErrorEndsTheScriptProcessQuietly` | a Java out-of-memory error in the probe ends its process (death seen within 60 s), no main-process exit record added (Android 11+) |

## 5. Notes for wave 2b and wave 3

- **WP2 (exit history, safe start).** Script processes are named
  `com.aicontrolcenter.hq:scripts:com.aicontrolcenter.app.scripts.ScriptProcessService:js0` and `…:js1` (Android 10+),
  `com.aicontrolcenter.hq:scripts` (Android 9); debug builds also have `…ScriptProbeService:probe0/probe1`. Every unbind
  files a `REASON_OTHER` (subreason 17, "isolated not needed") record in the app's per-package list; the watchdog's and
  the quiet end's own kills are filed under the isolated uid and never reach the app. Main-process filtering (rule 31)
  covers all of them.
- **WP9, WP10, WP11a, WP11b (screens).** Nothing to bind: scripts have no owner-facing setting. A script's answer in a
  task transcript or a chat's tool call may carry the texts of section 1 (in parentheses for the fallback note); show
  them as they are. If a Tools > Scripts card describes JavaScript, it may say "Scripts run in a sealed process of their
  own", and must not say so without the fallback caveat ("unless the phone refuses; the answer then says so").
- **WP8.** `ScriptsWiring.production` is the only wiring; `OperatorAssembly` already builds the toolset with
  `ports.scripts.engine?.invoke(limits.js) ?: JsSandbox(limits.js)`. The stub gate: `ScriptsWiring` and
  `ScriptProcessService` are no longer `@Phase6Stub`.
- **WP12 (review).** Points worth a look: `RunHost` (every limit), `ScriptFrames.readOutcome` (caps), the engine's
  bounds and `letGo` paths, the quiet end. The hostile script process of the tests (`HostileRunner`) is a ready tool.
- **WP14 (docs, LIMITATIONS).** Section 3 above; the spike's item 11 (a runaway script while the app is the previous
  app uses a core until the limit + 5 s) is in the proposed text.

## 6. Checks run

- Head `d49d6bd` on `p6/wp6` (five commits on `6fb816d`). Nothing uncommitted.
- Whole suite in the worktree (`./tools/gw --offline test --continue`): **3,167 tests, 0 failed**: app 582, core 165,
  data 432, device 352, framework 1,039, memory 107, providers 229, providers-anthropic 89, testkit 172.
- WP6's own tests: framework `JsSandboxStartTest` (7, D2 with the slow-start hook, events, class loader, note),
  `JsSandboxContractTest` (18, the honest contract against the in-process sandbox); app (all Robolectric at SDK 35 but
  `ScriptFramesTest`): `IsolatedJsEngineContractTest` (18), `SlowDyingScriptProcessContractTest` (18),
  `HostileScriptProcessContractTest` (6), `IsolatedJsEngineTest` (16), `IsolatedJsEngineBindingTest` (6, over
  `ShadowApplication.setComponentNameAndServiceForBindService`: overdue → unbind and rebind, death mid-run, third run
  waits, host calls after the run refused, Android 9 binding and refused-bind fallback), `RunHostTest` (7),
  `ScriptRunnerTest` (5, per-run watchdog), `ScriptFramesTest` (8), `ScriptsPartsTest` (5: service, quiet end, wiring,
  the source scan that app/scripts never names the graph, the data layer or the application, and the AIDL type rule),
  `ScriptsWalkTest` (1). Every existing JS test passes unchanged (`JsSandboxTest`, `JsRound3Test`, `JsRound4Test`,
  `JsGivenUpScriptTest`, `JsEngineSeamTest` …), as do `ProductionGraphDependencyTest` (with the isolated engine wired)
  and `ManifestRulesTest`.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest` build (the device test compiles; it was not run: no emulator
  for builders).
