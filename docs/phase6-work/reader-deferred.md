# Phase 6 reader: every deferred item and known rough edge

Repository read at /home/user/rune-companion, branch claude/ai-control-center-architecture-wjdfww, HEAD d7315fd
(0.5.1). Nothing in the repository was changed and nothing was built. Sources: docs/STATUS.md (every "Known
rough edges" list, "Still marked (test)", "Not verified", "Next: Phase 6"), docs/LIMITATIONS.md (whole), the
Phase 5 notes in the task, the Phase 5 verifier notes (scratchpad phase5-verify4-open.txt, phase5-round4.md),
and the code.

Tags: **[V]** verified in the code at the cited file:line (paths relative to /home/user/rune-companion),
**[D]** verified in official Android documentation (URL in section 6, Android version named), **[K]** platform
knowledge that could not be re-checked from this session (android.googlesource.com, gitlab.e.foundation and
firefox-source-docs.mozilla.org do not resolve here; those hosts would have to be allowed in the session's
network settings), **[A]** assumption.

Related readers already in this folder: reader-performance.md (P1-P10) and reader-background-recovery.md
(A1-A7, R1-R10). Where an item is theirs, this file gives a short row and points there.

About the owner's request "It needs an icon also": done in 0.5.1, HEAD d7315fd [V]. The launcher icon is an
adaptive icon with a one-colour layer for themed icons: app/src/main/res/mipmap-anydpi/ic_launcher.xml:2-6
(background, foreground, monochrome), ic_launcher_round.xml, and the three drawables in res/drawable. The
manifest names it at app/src/main/AndroidManifest.xml:26 and :29. Every notification already has its own small
icon: ApprovalNotifications.kt:57,62, ScreenSessionNotifications.kt:60,72, WorkNotifications.kt:39, and
device HandoverNotifier.kt:52. The accessibility service has no android:icon of its own, so Android settings
show the app icon. Nothing is left open here except looking at it on the owner's launcher (row D46).

---

## 0. The ten things that matter most

1. **JavaScript in its own process (D1).** Android can run the script engine in a service with
   `android:isolatedProcess="true"`. That process has its own kernel UID and no permissions [D], so the app
   cannot rely on `Process.killProcess(pid)` from the main process [D+A]. Three ways of ending it are used
   together: (a) a watchdog thread inside the isolated process calls `Process.killProcess(Process.myPid())`,
   which is always allowed [D]; (b) the app calls `unbindService`, after which the system kills an isolated
   process that has no service left [K]; (c) the app detects the death with `linkToDeath` and
   `onBindingDied` and binds again. The binder buffer is 1 MB per process [D], but scripts may move texts of
   up to 5,000,000 characters (JsSandbox.kt:45, ScriptTools.kt:108) [V]. Those texts have to travel through a
   pipe (`ParcelFileDescriptor`), not as binder arguments. `AiControlCenterApp.onCreate` also runs in the
   isolated process and must return at once there: `if (Process.isIsolated()) return` (API 28 [D]). Size L.
2. **The JavaScript time limit can be fixed first and on its own (D2, size S).** Start the clock when the
   script's own code starts (JsSandbox.kt:301), not when its thread starts (:222, :627). Give engine start-up
   its own bound and its own message.
3. **Screen calls right after apps_open (D5).** Today they fail at once: ScreenTools.front reads the app in
   front once (ScreenTools.kt:98) [V]. Fix: record each agent's launches. For 15 s after one, wait up to 10 s
   for the opened app before deciding.
4. **The banner during an approval (D4).** Start a "hold" on the agent's screen session when a screen tool's
   request starts waiting (AgentRunner.Waits, AgentRunner.kt:403-413) [V]. Release it when that tool call
   returns. The banner then reads "… waits for your answer to act in <app> · Stop".
5. **Permission screen named "No app window" (D6).** `activeWindow()` returns null when the front window
   has no root (AccessibilityScreenOperator.kt:161, :462) [V]. `AccessibilityWindowInfo` has no package-name
   method at all in API 37.2 (checked with javap on the SDK jar) [V]. So the fix names it "a window this app
   may not read" rather than naming its package.
6. **Private web addresses (D8).** Refuse a literal private address, `localhost` and `.local` names in
   `WebReadPage.assess` (WebTools.kt:50-61), before the permission request. A host name that resolves to a
   private address can only be refused after the permission, because looking it up would itself send the
   name off the phone.
7. **Shell R5 leftover (D11).** Add a hook that the broker calls after the grant check and before the
   approval request (PermissionBroker.kt:136-141). The hook reserves the one shell slot from "asked" to
   "ended". This removes both the pointless second approval and the "a command is running" leak to an agent
   that has no shell permission.
8. **A provider that failed one check blocks its agents for up to 10 minutes** (AppStartup.kt:175, :255)
   [V] (D28). This is the most likely daily annoyance for an owner on a phone network with one xAI key.
   Re-check on demand, and when Android reports the network is back.
9. **"(test)" fields (D26, D27).** Nothing reads Vision, Reasoning or Coding; the only uses are inside
   providers' estimators [V]. Making them real is M-sized: a photo button in chat gated by Vision, and
   tiers used by AgentSelector.score (AgentSelector.kt:85-92) and by the New objective form. The
   alternative is removing the three fields (S).
10. **No TODO or FIXME in the code [V]** (only "U+XXXX" in documentation comments and one test string). The only
    "(test)" labels are the three in ModelEditorScreen.kt:293, :299, :300, checked by
    PlaceholderLabelsTest.kt:56-58 [V].

---

## 1. Table

Verdict: **P6** fix in Phase 6; **P6?** worth doing if time allows; **keep** by design or an Android rule
(document it); **other** owned by another reader; **done**.

| ID | Item | Where (file:line) | Cause | Fix | Risk | Size | Test | Verdict |
|---|---|---|---|---|---|---|---|---|
| D1 | A JS loop of built-ins keeps a thread busy; after 2, no script starts until restart | framework/.../operator/JsSandbox.kt:203-210, 213-237, 668; OperatorToolset.kt:130 | Java threads cannot be stopped; runs are in-process threads | Isolated script process with watchdog, unbind, rebind; pipes for big texts (§2.A) | M-H | L | JsEngine contract (JVM); Robolectric engine tests; emulator ScriptProcessDeviceTest (endless loop ×3, then `1+1`) | P6 |
| D2 | JS limit includes engine start; message says "did not stop in time" | JsSandbox.kt:222-223, 287-301, 325-329, 627 | Clock starts at thread/Factory start | Start clock at user code; separate engine-start bound and message | L | S | JVM test with slow start hook; emulator one-liner at 5 s | P6 |
| D3 | JS memory limit measured on the whole app | JsSandbox.kt:352-361; LIMITATIONS.md:255-265 | One heap for app and script | Comes with D1 (per-process heap) | L | – | in D1 device test (memory hog kills only script process) | P6 (with D1) |
| D4 | Banner and notification end 30 s after last screen call, also while an action waits | Screen.kt:254-256, 326; AccessibilityScreenOperator.kt:642-679; app/.../operator/ScreenSessions.kt:116-123 | Idle timer knows nothing of owner waits | `holdSession`/`releaseSession` driven from AgentRunner.Waits; banner text "waits for your answer" | L | M | Robolectric session tests; AgentRunner test in virtual time; emulator SIM Toolkit tap approved after 45 s | P6 |
| D5 | screen_read / screen_wait_for right after apps_open refused | ScreenTools.kt:90-101; AppTools.kt:137-155; AndroidAppActions.kt:95-102 | Launch returns at startActivity; front read once | Record launches; bounded wait for the opened app in `front()` | L | M | FakeScreenOperator sequences; emulator apps_open then screen_read | P6 |
| D6 | Permission controller in front gives "No app window is in front" | AccessibilityScreenOperator.kt:157-164, 458-463; ScreenTools.kt:98 | package comes from `root?.packageName`; root hidden | `frontState()` with Hidden; plain message | L | S | Robolectric rootless window; emulator with the notification permission dialog | P6 |
| D7 | Force stop switches accessibility off | Android behaviour; LIMITATIONS.md:340-342 | Android disables services of a stopped package | Detect at start (stored "was ready" + ApplicationExitInfo on 30+), explain, button to accessibility settings | L | S | Robolectric with ShadowActivityManager exit info; emulator `am force-stop` | P6 |
| D8 | web_read_page of a private address asks permission first | WebTools.kt:50-61, 66-68; AddressGuard.kt:50-89; WebReader.kt:59-73 | Literal check only inside the reader, after authorisation | `WebAddressPolicy.literalRefusal` in :core, used in assess | L | S | Broker test: no OwnerRequest for 192.168.x / .local | P6 |
| D9 | Approval screen does not show what the call did | ApprovalScreen.kt:462-473; ToolExecutor.kt:268-317 (requestIds :310) | Outcome only in the activity log | Read the TOOL_CALL event naming the request id (no schema change) | L | S-M | VM test; journey approve and see "Done" | P6 |
| D10 | No team-wide cap on pending owner requests | PermissionBroker.kt:189-224; only per-agent asks capped (DefaultCapabilityRequester.kt:86, OwnerPorts.kt:194) | Not designed | Caps of 12 in all and 6 per objective, checked in `ask()` | L-M | S | Broker test 13th refused, nothing stored | P6 |
| D11 | Shell: two commands can be asked before either runs; busy refusal comes before the permission check | ScriptTools.kt:217-218, 613; LocalWorkspace.kt:239; PermissionBroker.kt:117-149 | Busy check only in assess, only "running" | `PreparedAction.beforeOwnerAsked()` reserves the shell slot; released when the call ends | M | M | Real-broker test like V7LockProbe G1-B / V8R5 (R5-B, R5-C) | P6 |
| D12 | Bulk-change 10-minute window runs on the clock; counts reset at restart | framework/.../operator/BulkChanges.kt:17-77 (prune :37) | Wall-clock window | Subtract owner-wait intervals of the same agent/task/objective (shared OwnerWaitLedger) | L | S-M | Unit test 15 + 11-min wait + 6 | P6 |
| D13 | Unanswered request refused after 30 min | FrameworkSettings.kt:32 | By design, setting | – | – | – | – | keep |
| D14 | During Android's install confirmation the task shows the old time-out moment | ToolExecutor.kt:242-243; LIMITATIONS.md:304-306 | Owner time stored after the tool returns | Status line "time limit paused while Android asks you" | L | S | Framework test with fake installer wait | P6? |
| D15 | Non-plain shell commands: best reading; some harmless commands refused | ScriptTools.kt:174-191; ShellCommands.kt | A shell language cannot be read completely | – | – | – | – | keep |
| D16 | A workspace change while a command waits stops it; Workspace waits while it runs | LocalWorkspace.kt (change counter and lock); LIMITATIONS.md:150-165 | The guarantee itself | – | – | – | – | keep |
| D17 | Undeletable home-folder leftovers stay in cache | device/.../commands/SystemShellRunner.kt:479-494 (clearPutAside) | Java File API cannot name very deep paths | Use `/system/bin/rm -rf --` on the app-built put-aside path only | M | S | androidTest ShellHomeFolderTest + very deep path case | P6? |
| D18 | Termux keeps running after the limit | TermuxRun, ScriptTools.kt:635-660 | Termux owns the process | – | – | – | – | keep |
| D19 | Objective screens reload whole tasks on any change (Phase 4, scheduled) | data TaskDao, ObjectiveViewModel.kt:181-193 (see reader-performance P1, P2) | `SELECT *` trees per observer; rebuild every second on main thread | reader-performance P1 (schema 6 column order + summary queries) and P2 (flowOn, ticker split) | M | M-L | per that reader | other (P6) |
| D20 | "Objective completed" can be logged twice after kills at the very end | ObjectiveDriver.kt:671-689 | Log written before the message whose id dedupes | Skip the log entry if one for that root and attempt exists | L | S | Crash-harness case: kill between log and message | P6 |
| D21 | Knowledge record numbers can skip ("final result 3") | ObjectiveDriver.kt:771 | Numbered by run index | Number by results that exist | L | S | Unit | P6? (cosmetic) |
| D22 | Owner-edited summary rewritten by another agent still called "your version" | KnowledgeKeeper.kt:166-169 | Compares owner edits only with the Director's | Owner's version only if the last content edit is the owner's | L | S | Unit with three edits | P6 |
| D23 | Knowledge refused (Director lost space access): logged, not in status | KnowledgeKeeper.kt:98-101, 121 | `failed()` drops MemoryPermissionException | `Saved.refused` and a status sentence | L | S | Orchestrator test | P6 |
| D24 | 2 tasks at once by default; reviews share the limit | FrameworkSettings.kt:18 | Setting | – | – | – | – | keep |
| D25 | Notification starts only with the app on screen; auto-resumed objective waits for the app | Android 12+ background FGS rules | Platform | see reader-background-recovery | – | – | – | other |
| D26 | "Vision (test)": nothing reads it | ModelEditorScreen.kt:292-297; Models.kt:174; Chat.kt:35,55 | No image input in the UI | Photo button in chat via Photo Picker, gated by Vision; strip images for non-vision fallbacks | L-M | M | VM, journey, adapter tests exist | P6 (or remove field) |
| D27 | "Reasoning (test)", "Coding (test)": nothing reads them | ModelEditorScreen.kt:299-300; Models.kt:178-184; AgentSelector.kt:85-92 | Not wired | Tier bonus in AgentSelector; Director warning on New objective | L | S-M | AgentSelector unit tests; NewObjective VM | P6 (or remove fields) |
| D28 | After a failed check, agents stay blocked until re-checked (start, 10 min, Check now) | app/.../di/AppStartup.kt:163-176, 255; DefaultProviderManager.kt:250-297 | Status is stored and only re-checked on those events | On-demand re-check (older than 60 s, not for rejected keys) and re-check when the network comes back | L | S-M | ProviderManager test with fake clock; Robolectric network callback | P6 |
| D29 | "Answered by another model" compares names (aliases flagged) | app/.../chat/ChatPresentation.kt:210-226 | Prefix rule only | Normalise "-latest", "-beta", "-preview" and date stamps before comparing | L | S | Unit: grok-4-latest vs grok-4-0709 | P6 |
| D30 | "Find servers on this phone" only checks this phone | ModelsViewModel.kt:163-177 | Loopback probe only | Keep; network servers are added by address. Do not scan the owner's network | – | – | – | keep |
| D31 | Conversations not paged | see reader-performance P5 | – | – | – | – | – | other |
| D32 | Code shrinking off, APK about 80 MB | app/build.gradle.kts:47-49 | Reflection (Anthropic SDK and Jackson, serialization, Room) | Not in Phase 6 | H | M | – | keep |
| D33 | On-device model runtime and Gemini Nano only "planned"; `ProviderKind.ON_DEVICE` has no client | core/.../registry/Models.kt:47-48; LIMITATIONS.md:50-51; no preset offers it | Never built | Not in Phase 6; say so in docs | – | L | – | keep (doc) |
| D34 | Web search by agents not built | WebTools.kt:21 (only read_page) | Needs a search API | Not in Phase 6 | – | M | – | keep (doc) |
| D35 | Memory counts load the full list | see reader-performance P4 | – | – | – | – | – | other |
| D36 | Memory upkeep only while the app is open | AppStartup.kt:39, 176-184, 258 | Foreground loop | see reader-background-recovery R9; use JobScheduler, because androidx.work is not in the offline Gradle cache [V] | – | – | – | other |
| D37 | Graph export refused in local-only mode, also when the owner exports | app/.../memory/graph/MemoryGraphViewModel.kt:201-212 | Gate treats the owner's own share like an agent's | Allow the owner's export (as the Workspace share does), or add "Save to the workspace" | L | S | VM test | P6 |
| D38 | Deleting a space while editing an agent's memory can lose one change | AgentEditorViewModel.kt:238-246 (and save) | Whole policy written from a stale draft | At save, drop unknown space ids and merge only edited fields | L | S | VM test with a concurrent delete | P6 |
| D39 | No "keep on this phone" mark for a memory or space | LIMITATIONS.md:317-320 | Not built | Space flag (and memory flag) excluded from recall and search for cloud models | M | M | Memory engine tests; recall tests | P6? |
| D40 | "New agent" button covers the last card at rest | AgentsScreen.kt:98-102, 132 (96 dp padding exists) | Standard FAB overlap | Collapse the button while the list is scrolled | L | S | Screenshot | P6? |
| D41 | Activity screen: newest 300, no paging or search | ActivityViewModel.kt:66-82, 121 | Fixed limit | Text search and "Show older" (performance in reader-performance P3) | L | M | VM, DAO tests | P6? |
| D42 | All text English and hard-coded | whole :app | Not localised | Not in Phase 6 (owner writes English) | – | L | – | keep |
| D43 | Public development key still used by CI without secrets | README.md:24-40; .github/workflows/build.yml:41-59 | The owner's secrets are not set | Owner action; README already says so | – | – | – | keep |
| D44 | Lost database key: no in-app reset | data/.../security/DatabaseKeyStore.kt:13, 53-60 | Exception only | see reader-background-recovery R5 | – | M | – | other |
| D45 | Android once reported the app not responding at a cold start (emulator) | MainActivity.kt:14-22; AiControlCenterApp.kt:44-55 | Unknown; the graph and ports are built on the main thread [V], whether they do I/O there is [A] | StrictMode in debug; record REASON_ANR at next start | L | S | Emulator cold start with StrictMode | P6 (investigate) |
| D46 | Launcher icon | res/mipmap-anydpi/ic_launcher.xml; AndroidManifest.xml:26,29 | – | Look at it on the owner's launcher | – | – | – | done |
| D47 | `onboardingCompleted` stored, never read: no first-run guide | core/.../settings/FrameworkSettings.kt:43 (only the contract test uses it) | Never built | Short first-run guide in plain words (model, provider, key) | L | M | Journey test | P6? |
| D48 | Web reader always connects directly | AddressGuard.kt:105-110 (ConnectGuard) | A proxy would hide the real address | – | – | – | – | keep |

---

## 2. Details

### A. Scripts

#### D1. JavaScript that can never be stopped: move scripts into a separate process

**What the code does now [V].**
- `JsSandbox.run` starts one daemon thread per script with its own stack (JsSandbox.kt:213-219). It waits
  `timeoutMs + GRACE_MS` (2 s, :657) for the result (:222). If no result has come, it marks the run ABANDONED,
  lowers the thread to `Thread.MIN_PRIORITY` and interrupts it (:225-237). While `MAX_STUCK = 2` threads are
  abandoned and alive, every new run is refused with "Two earlier scripts have not stopped …" (:203-210, :668).
- The budget is enforced from inside the interpreter: the instruction observer throws an uncatchable Error
  (:311-349), and every entry into a script function is charged (`COUNT_ENTRIES`, :714-719). A loop in which
  built-ins call only built-ins never reaches either, which the class comment admits (:174-183). An ART thread
  cannot be killed from outside: `Thread.stop` is not supported on Android [K]. So the only way to end such a
  loop is to end its process.
- One sandbox serves the whole app: `OperatorToolset.create` builds `JsSandbox(d.limits.js)` once
  (OperatorToolset.kt:128-131). `ScriptsRunJs` calls `sandbox.run(code, input, host, timeout)`
  (ScriptTools.kt:107-110).
- The memory check reads `Runtime` of the whole app (JsSandbox.kt:352-361, :623-626, :663). The script and the
  app share one heap, so a script can push the app towards being closed for lack of memory
  (LIMITATIONS.md:243-247).

**Android mechanics.**
1. `android:isolatedProcess="true"` on a `<service>`: "this service runs under a special process that is
   isolated from the rest of the system and has no permissions of its own. The only communication with it is
   through the Service API, with binding and starting" [D, service element; available on every Android
   version the app supports]. "No permissions" means no INTERNET, so no network. File access to the app's own
   data directories is also not available to the isolated UID [K]. This is defence in depth: even an escape
   from Rhino's ClassShutter could not read the database, the key vault or the workspace.
2. **Its UID differs from the app's.** `ApplicationExitInfo.getRealUid()`: "except the case of isolated
   process, where this field identifies the kernel user identifier that this process is actually running
   with, while getPackageUid() identifies the kernel user identifier that is assigned at the package
   installation time" [D, API 30]. `Process.killProcess(pid)`: "the kernel will still impose standard
   restrictions on which PIDs you are actually able to kill. Typically this means only the process running
   the caller's packages/application and any additional processes created by that app; packages sharing a
   common UID will also be able to kill each other's processes" [D]. Inference [A]: a signal from UID 10xxx to
   the isolated UID (99000-99999 range [K]) is refused by the kernel, and `killProcess` ignores the error. So
   the main app cannot end the isolated process with `killProcess`. The emulator test must check this; do not
   build on it either way.
3. **How it is ended, all three together:**
   a. A **watchdog inside the isolated process.** `Process.myPid()` "can be used with killProcess" [D], and a
      process may always kill itself. The service starts one watchdog thread (normal priority; the script
      threads run at `THREAD_PRIORITY_BACKGROUND`, so the watchdog gets the CPU). When any run passes
      `timeoutMs + GRACE_MS`, the watchdog first reports Stopped for every run in that process to the app,
      then calls `Process.killProcess(Process.myPid())`.
   b. The app calls **`unbindService(conn)`** when a run is overdue by more than the grace period plus 3 s (for
      example if the watchdog itself is starved). Without bindings the service is destroyed, and ActivityManager
      kills an isolated process that has no service left ("isolated not needed",
      `ApplicationExitInfo.SUBREASON_ISOLATED_NOT_NEEDED` in OomAdjuster) [K: AOSP source; its unit tests assert
      this kill, but the source could not be fetched from this session].
   c. **Detecting the death:** `IBinder.linkToDeath` on the runner binder, plus
      `ServiceConnection.onBindingDied` (API 26 [K]) and `onServiceDisconnected`. The engine marks itself dead,
      and the next run binds again with `BIND_AUTO_CREATE`, which brings up a new process.
4. `bindIsolatedService(intent, flags, instanceName, executor, conn)`, **API 29** [D]: "allows the caller to
   generate multiple instances of a service from a single component declaration … bring up multiple
   concurrent processes hosting their own instances". It is optional: one process per slot instead of one
   shared process, on API 29 and later only, since minSdk is 28. `BIND_SHARED_ISOLATED_PROCESS` (API 34) and
   `BIND_PACKAGE_ISOLATED_PROCESS` (API 35) [D] are not needed.
5. **App zygote, `ZygotePreload`, API 29** [D]: it preloads code "shared by all isolated services that have the
   useAppZygote attribute"; "The Application Zygote will be stopped when all isolated services that use it are
   stopped" [D]. This could preload the Rhino classes to cut start-up time. Leave it for later: it adds a
   second special process and has restrictions of its own [K].
6. **Binder size:** "The Binder transaction buffer has a limited fixed size, currently 1MB, which is shared by
   all transactions in progress for the process" [D]. Code (50,000 characters), input (200,000), result
   (100,000) and console output (16,000) fit. `workspace.read` and `workspace.write` texts of up to
   5,000,000 characters (JsSandbox.kt:45; host built with `d.limits.js.maxStringChars` at ScriptTools.kt:108)
   are about 10 MB as UTF-16 and must not cross as a String. Stream them through
   `ParcelFileDescriptor.createReliablePipe()` [K, API 19], with the size cap enforced on both ends.
7. **Application in every process:** `Application.onCreate` runs in the isolated process too [K].
   `AiControlCenterApp.onCreate` cancels a notification (AiControlCenterApp.kt:31-36), and an isolated process
   may not reach the notification service [K/A]. Add `if (Process.isIsolated()) return` (API 28 [D]) before
   anything else. The graph is lazy (AiControlCenterApp.kt:44-55), so nothing else starts there unless it is
   touched. The service must never touch `graph`; an architecture test can check that its source does not
   mention `AiControlCenterApp.graph`. Content providers are installed only in the main process [K].
8. **Tooling:** AIDL needs `buildFeatures { aidl = true }`, added to app/build.gradle.kts:60-63, which today
   has compose and buildConfig only [V]. The aidl tool exists in /opt/android-sdk/build-tools/37.0.0 [V]. No
   new library is needed. A `Messenger` would also work, but synchronous host calls are easier with AIDL.

**Design.**
- `:framework` adds `interface JsEngine { suspend fun run(code: String, inputJson: String?, host: JsHost?,
  timeoutMs: Long): JsOutcome }` (new file framework/.../operator/JsEngine.kt). `JsSandbox` implements it
  unchanged: it is the in-process engine for JVM tests, and the code that runs inside the script process.
  `OperatorToolset.create(d, js: JsEngine = JsSandbox(d.limits.js))` (OperatorToolset.kt:128-131),
  `ScriptTools.create(d, js)` (ScriptTools.kt:25) and `ScriptsRunJs(d, js)` (:75).
- `:app` adds:
  - `app/src/main/aidl/com/aicontrolcenter/app/scripts/IScriptRunner.aidl`: `oneway void run(long runId, String
    code, String inputJson, long timeoutMs, in Bundle limits, IScriptHost host, IScriptResult result)` and
    `int pid()`.
  - `IScriptHost.aidl`: `ParcelFileDescriptor read(long runId, String path)` (the app writes the text into a
    pipe), `String list(long runId, String path)` (JSON), `String write(long runId, String path,
    in ParcelFileDescriptor text)`.
  - `IScriptResult.aidl`: `oneway void done(long runId, in Bundle outcome)` and `oneway void
    engineReady(long runId)` (for D2).
  - `app/src/main/java/com/aicontrolcenter/app/scripts/ScriptProcessService.kt`, which runs in the isolated
    process. `onBind` returns the runner; each run calls `JsSandbox.run` with a `JsHost` that forwards to
    `IScriptHost`; the watchdog is as in 3a.
  - `IsolatedJsEngine.kt` in the app process. It binds on first use and keeps the process warm for 5 minutes
    after the last run, then unbinds so the memory comes back. At most 2 runs go at once; a third waits for a
    slot. Overdue runs and process death are handled as in 3b-3c. Runs that die with the process get:
    "The script was ended together with the script process, because a script there did not stop. Nothing
    else was done; run it again."
  - Manifest entry in app/src/main/AndroidManifest.xml: `<service android:name=".scripts.ScriptProcessService"
    android:isolatedProcess="true" android:process=":scripts" android:exported="false" />`.
- **Guarantees to keep.** JsSandbox.kt:185-189 promises: "a host function that still waits gives up and does
  nothing afterwards, and a run that is over makes no further host calls". The app-side `IScriptHost` therefore
  keeps a `runOver` flag and a `Job` per host call for each runId. When the run ends for the app (result,
  overdue or death), it cancels those jobs before reporting. A write still waiting for the workspace's change
  lock (a shell command) is then never made. The existing "WAITED_FOR_HOST" text stays (:666-667).
- **Prewarming.** `ScriptsRunJs.assess` (ScriptTools.kt:91-114) asks the engine to bind if it is not bound. The
  process then starts while the owner or the broker decides. This sends nothing anywhere.
- **Fallback.** If binding fails (an unusual phone), use the in-process `JsSandbox`, say so in the answer, and
  keep today's MAX_STUCK rule.
- **Not chosen:** (1) `androidx.javascriptengine` (V8 in WebView's isolated process). It is not in the
  offline Gradle cache: ~/.gradle/caches/modules-2/files-2.1 has no androidx.javascriptengine [V]. It depends
  on the installed WebView, which the aosp_atd emulator image may not have [A]. And it would drop the Rhino
  guards and the `workspace.*` semantics. (2) A plain `android:process=":scripts"` (same UID). The app could
  then `killProcess` it directly [D] and it would be simpler, but that process can read the app's files and
  use the network. Keep it only as the fallback if isolated binding fails on the emulator.

**Start-up and memory [A].** On a phone, a fresh process (zygote fork, ART start, Application, Rhino class
loading) costs about 0.3-1.5 s the first time. On the software emulator it costs much more: even in-process, a
one-line script needed over 5 s (STATUS.md:494-496). Keeping the process warm and prewarming at assess time
hide most of it. Memory: a second ART process, mostly shared with the zygote; its exact cost is unknown and
should be measured on the emulator with `dumpsys meminfo <pkg>:scripts`. With its own heap, the script process
makes the 96 MB growth rule and the "nearly full" rule apply to the script alone (D3). A script that fills
that heap ends only the script process.

**Risks.** The isolated service on vendor phones [A]. Android 14+ freezes cached processes, and a frozen
process that receives a synchronous binder call is killed (`REASON_FREEZER` [D]); scripts run while the app is
in front or holds its foreground service, so this should not arise [A]. The AIDL code is new, and so are its
failure paths.

**Tests.**
- JVM: a `JsEngineContract` suite in :testkit run against `JsSandbox`, and against a fake remote engine that
  can be made to die mid-run and to be slow to start.
- Robolectric (:app): `IsolatedJsEngine` with `ShadowApplication.setComponentNameAndServiceForBindService`:
  overdue run leads to unbind and a new bind; death mid-run gives the message; the third concurrent run waits;
  host calls after the run is over are refused.
- Emulator (androidTest in :app, `ScriptProcessDeviceTest`):
  (1) `1+1` gives Finished;
  (2) the endless loop `var o={}; o[Symbol.iterator]=function(){return {next:Object}}; new Set(o)` three times
      in a row: each ends as Stopped within timeout + grace + 3 s, and then `1+1` works (today the third is
      refused);
  (3) the app's own pid is the same before and after;
  (4) inside the service, `Process.isIsolated()` is true and the app's data directory cannot be listed;
  (5) a 4 MB `workspace.read` works through the pipe;
  (6) log the engine start-up time;
  (7) a memory-hog script ends the script process only.

#### D2. The JavaScript time limit counts engine start-up

**Cause [V].** The tool's wait starts before the thread exists (JsSandbox.kt:222). Inside the run, `start`
is taken when the `Factory` is constructed (:627), and that happens before `initSafeStandardObjects`, the
`console`, `input` and `workspace` set-up and the evaluation of the GUARDS script (:287-300). The heap
collection for `heapBase` (:623-626) is also on that path. Only then does the owner's code run (:301). An
abandoned run is reported as "did not stop within its time limit" (:223), also when it never reached its own
code. ScriptsRunJs passes this on as "The script was stopped: …" (ScriptTools.kt:132).

**Fix (works with or without D1).**
- `ScriptRun` gets `@Volatile var userStart: Long = 0` and a `CompletableDeferred<Unit> ready`. They are set and
  completed immediately before `cx.evaluateString(scope, code, …)` (:301).
- `check()` (:325-329) uses `userStart` when it is set. Before that, only `ENGINE_START_MS = 30_000` applies,
  with its own message.
- `run()` waits first `withTimeoutOrNull(ENGINE_START_MS) { ready.await() }`. If that runs out: Stopped("The
  script engine did not start within 30 seconds; the phone is busy. Nothing of the script ran. Try again.").
  Then it waits `withTimeoutOrNull(timeoutMs + GRACE_MS) { done.await() }`.
- Keep the instruction count from the start; it is cheap. The `timeout_ms` text in the tool description
  ("Time limit. Default 5000.", ScriptTools.kt:88) gains "counted from when your code starts".

**Tests.** JVM: inject a hook that sleeps 300 ms before user code with timeoutMs = 100; the script finishes.
With a script that spins for 200 ms after start, it is stopped with the time-limit message. Emulator: a
one-liner with the default 5 s finishes. Risk low, size S.

### B. Screen control

#### D4. Keep the banner while one of the agent's screen actions waits for the owner

**Cause [V].** The device's session ends `SESSION_IDLE_MS = 30_000` after the last call (Screen.kt:326;
timer at AccessibilityScreenOperator.kt:664-667). The app's notification copy has the same timer
(ScreenSessions.kt:116-123). Neither knows that a request is waiting. After the approval, `call()` begins a
new session (AccessibilityScreenOperator.kt:405), but the banner is drawn asynchronously on the main thread
(:695-706). So the action can happen before the banner is back, and the owner sees no banner while deciding.

**Fix.**
- Add to `ScreenOperator` (Screen.kt:262) `suspend fun holdSession(agentName: String, packageName: String) {}`
  and `fun releaseSession(agentName: String) {}`, with default bodies, so `FakeScreenOperator` keeps compiling.
- AccessibilityScreenOperator: `holdSession` does nothing when Stop is pressed or the app is not authorised
  (`authorised(pkg)`, :450-455). Otherwise it calls `beginCall(agent, label)` (:642-652) with a new flag
  `Session.waitingForOwner = true`. `releaseSession` calls `endCall` (:654-671). `applyIndicator` (:700-704)
  writes "<agent> waits for your answer to act in <app> · Stop" for a waiting session.
- ScreenSessions (app) mirrors the counting in `tracked()` (:84-104) and adds `waiting` to `ScreenSession`, so
  the notification says the same.
- AgentRunner: `Waits.waiting(request)` (AgentRunner.kt:404-407) calls `screen?.holdSession(agent.name, pkg)`
  when `request.toolId` starts with `SCREEN_TOOL_PREFIX` (:443) and `request.constraint` is
  `CapabilityConstraint.App` (CapabilityConstraint.kt:56), and sets `usedScreen`. The release goes in a
  `finally` around the tool call at :350. The hold therefore also covers `afterOwnerAnswered`, where the call
  waits up to `screenReturnMs = 30 s` for the app to be in front again (ScreenTools.kt:143-163). It must not end
  in `answered()`.
- Stop is unchanged: `onOwnerStop` clears every session, holds included (:681-688), and the action is refused
  by the gate anyway.

**Tests.** Robolectric (device): hold shows the banner with the waiting text; release starts the 30 s timer;
Stop clears it. AgentRunner test in virtual time: a screen tool call whose approval takes 60 s keeps
`ScreenSessions.sessions` non-empty throughout. Emulator: approve a SIM Toolkit tap after 45 s and take a
screenshot during the wait. Risk low, size M.

#### D5. Screen calls that wait for an app that is still opening

**Cause [V].** `apps.open` returns as soon as `startActivity` returned: AndroidAppActions.open :95-102 →
`handOver`, and the answer is "<app> is open" (AppTools.kt:151). Every screen tool reads the app in front
once in `ScreenTools.front` (ScreenTools.kt:90-101). It gets this app (protected, :77) or no window (:98), and
the call is refused while the opened app is still coming up. On the emulator that took several seconds
(LIMITATIONS.md:347-349).

**Fix.**
- `OperatorToolDependencies` gets `val launches: RecentLaunches = RecentLaunches()`, an in-memory map per agent
  of (package or null, time), bounded. `OperatorLimits` gets `screenOpenWaitMs = 10_000` and
  `launchRecentMs = 15_000` (OperatorToolset.kt:54-88).
- `AppsOpen`'s prepared action records the package on success (AppTools.kt:150-152). `AppsOpenLink` and
  `BrowserOpen` (:158-230) record "an app" with a null package.
- `ScreenTools.front` takes the agent id; callers are at :277, :313, :336 and :378. After the state checks, if
  this agent launched within `launchRecentMs`, and the front window is null or this app (`d.ownPackage`) or not
  the launched package, it polls every `screenPollMs` (250 ms) for up to `screenOpenWaitMs`. It stops waiting
  when the launched package (or, for a link, any app other than this one) is in front, or on Stop. Then it
  goes on with whatever is in front, so all refusals stay as today.
- The wait happens in `assess`, before authorisation, which is outside `ToolLimits.timeoutMs`
  (ToolExecutor.kt:202-237). It is bounded by `screenOpenWaitMs` itself and counts against the run's own time
  limit. That is right: it is the agent's time, not the owner's.
- The tool description gains a sentence: "After apps_open, screen tools wait up to 10 seconds for the app to
  come up."

**Tests.** FakeScreenOperator scripted fronts [own app, null, target] in virtual time: screen_read after
apps_open succeeds; without a recent launch the refusal comes at once, as now; Stop during the wait refuses.
Emulator: apps_open SIM Toolkit followed at once by screen_read (failed in the 0.5.0 end-to-end run). Risk low,
size M.

#### D6. A protected window in front is called "no app window"

**Cause [V].** `front()` takes the package from `window.root?.packageName` (AccessibilityScreenOperator.kt:458-
463). Android hides the root of windows a service that is not an accessibility tool may not read (the service
declares `isAccessibilityTool="false"`, device/src/main/res/xml/operator_accessibility_service.xml). So the
package is null and `activeWindow()` returns null (:161). ScreenTools then says "No app window is in front"
(ScreenTools.kt:98). `AccessibilityWindowInfo` has no package-name method in API 37.2: javap of
/opt/android-sdk/platforms/android-37.2/android.jar lists getId, getType, getTitle, getRoot, isActive,
isFocused and others, but no package [V]. So the package of such a window cannot be named.

**Fix.**
- `ScreenOperator.frontState(): FrontState` (new, in Screen.kt next to `ActiveWindow` at :32): `None`,
  `Hidden`, or `App(ActiveWindow)`. `activeWindow()` stays as it is for its existing callers.
- The device returns `Hidden` when an active or focused window exists but its root is null.
- `ScreenTools.front` (:98) answers Hidden with "The window in front is one Android does not let this app
  read, such as its permission screens. Nothing was read. Try again once it is gone."
- `frontChange` (:117-120) and `afterOwnerAnswered` (:146) say "a window this app cannot read" instead of
  "unknown".

**Tests.** Robolectric: a `ScreenHost` window with a null root gives Hidden; ScreenTools gives the message.
Emulator: raise the app's own notification permission dialog and call screen_read. Risk low, size S.

#### D7. Force stop switches screen control off

This is Android's behaviour (LIMITATIONS.md:340-342); the app can only notice it and explain.
`ApplicationExitInfo.REASON_USER_REQUESTED`: "user clicked the Force stop button of the application in the
Settings, or removed the application away from Recents"; before Android 14 it was also used for an update
[D, API 30+].
- Store `screenServiceWasReady` (device SharedPreferences) whenever `refreshState` sees READY
  (AccessibilityScreenOperator.kt:140-142).
- At start, if the state is NOT_ENABLED and the flag is set, show a notice in Tools > Screen and in the
  Dashboard's "Needs attention": "Android switched screen control off after the app was stopped. Turn it on
  again in Android's accessibility settings." On API 30+, read
  `ActivityManager.getHistoricalProcessExitReasons(pkg, 0, 1)` and say "force-stopped" only for
  REASON_USER_REQUESTED on Android 14 and later.
- The button opens the accessibility settings page; that is the owner's own tap, and the operator never
  drives Settings.
- Clear the flag when the owner switches Screen control off in the app.

This shares the exit-reason reading with reader-background-recovery R1. Tests: Robolectric with shadow exit
info; emulator `am force-stop` then start. Size S.

### C. Broker, approvals, shell

#### D8. A private web address is refused only after the permission

**Cause [V].** `WebReadPage.assess` checks only the scheme and that a host exists (WebTools.kt:50-61 →
AppTools.link :104-111). The private-address checks live in the reader (AddressGuard.literalRefusal
AddressGuard.kt:50-62; GuardedDns :92-99; ConnectGuard :105-110) and run at `d.web.read` (WebTools.kt:66-68), after
`authorize` (ToolExecutor.kt:202-218).

**Fix.**
- Move the literal parse (`dottedQuad`, the IPv6 literal branch, AddressGuard.kt:50-89) into :core as
  `WebAddressPolicy.literalRefusal(host)` next to `refusal` (WebReader.kt:59-73). Add the names `localhost`,
  `*.localhost`, `*.local`, `*.internal`, `*.home.arpa` and names without a dot.
- `WebReadPage.assess` throws `ToolCallRejected` with that reason. The reason depends only on the argument,
  so telling the model before authorisation reveals nothing. AddressGuard keeps using the same function as
  its last check.
- A host name that resolves to a private address is still refused only at read time: resolving it in assess
  would send the name to the resolver before the owner allowed anything. LIMITATIONS.md says so in one
  sentence.

Test: real broker, `http://192.168.1.10/` and `http://router.local/` raise no OwnerRequest;
`https://example.com` raises the permission request as before. Size S.

#### D9. Show what an approved call did

**Today [V].** The Approval screen shows only the decision (ApprovalScreen.kt:462-473). `ToolExecutor.record`
already writes a TOOL_CALL event with `outcome`, the failure text (up to 300 characters) and `requestIds`
(ToolExecutor.kt:277-311).

**Fix without a schema change.** `ApprovalViewModel` reads
`activityLog.observe(ActivityFilter(types = setOf(TOOL_CALL), agentId = request.agentId), 200)` and takes the
first event whose `data.requestIds` holds the id. It shows "What happened" as:
- "Done." (OK);
- "It failed: <detail>" (FAILED or TIMED_OUT);
- "Not run: <detail>" (DENIED, which includes "changed since you approved");
- "Still running…";
- "No longer in the activity log." (after retention, 30 days by default, FrameworkSettings.kt:14).

If schema 6 happens anyway (reader-performance P1), outcome columns on `owner_requests` are an alternative.
They would be written through a narrow port that only fills them for closed requests and cannot change access.
Tests: VM test with a fake log; journey test that approves `workspace.empty_trash` and checks for "Done.".
Size S-M.

#### D10. A team-wide cap on questions to the owner

**Today [V].** Only agent-initiated asks are capped: `MAX_PENDING_ASKS_PER_AGENT = 3` (OwnerPorts.kt:194;
DefaultCapabilityRequester.kt:86). Tool calls of many tasks can raise any number of PERMISSION and APPROVAL
requests (PermissionBroker.ask, :189-224).

**Fix.** In `ask()`, before `newRequest`: read `requests.observePending().first()`. Refuse without asking when
12 requests are pending in all, or 6 for the same `rootId`. The constants go in the `OwnerRequest` companion
next to `REFUSAL_COOLDOWN_MS`. The text for the model: "Many questions already wait for the owner. Nothing was
asked and nothing was run. Ask again after the owner has answered some." Log it as a WARNING. No action escapes
confirmation: a refused call simply does not run. Tests: broker test, 13th refused and nothing stored. Size S.

#### D11. Shell: the slot is taken from "asked" to "ended"

**Today [V].** The only busy check is in `ScriptsRunShell.assess`: `if (d.workspace.commandRunning())`
(ScriptTools.kt:217-218, text :613; LocalWorkspace.kt:239). The verifier recorded two leftovers in
phase5-verify4-open.txt (R5, remainingProblem):
(1) two commands asked before either runs both reach the owner, and approving the second while the first runs
    has no effect (R5-B);
(2) the refusal comes before the permission check, so an agent refused the shell permission learns that some
    command is running (R5-C).

**Fix.**
- Add `suspend fun beforeOwnerAsked(): String? = null` and `fun callEnded() {}` to `PreparedAction`
  (core/.../tools/Tool.kt:98-114).
- `PermissionBroker.authorize` calls `beforeOwnerAsked()` after step 4 and before the APPROVAL `ask`
  (PermissionBroker.kt:136-141), and refuses with its reason without asking.
- `ToolExecutor.carryOut` calls `callEnded()` in a `finally` (around :202-249).
- ScriptsRunShell moves the check out of assess. It reserves the one slot with a new `Workspace.reserveCommand(
  token)` in LocalWorkspace. A reservation counts as busy for other calls; `runCommand` takes it over, and
  `callEnded` frees it.
- The new text: "Another shell command is running or waits for the owner's answer. Nothing was asked and
  nothing was run. Ask again when it has ended."

Both leftovers go: the second command is never put to the owner, and an agent without the grant gets its
PERMISSION request first. Tests: re-run the V7LockProbe G1-B and V8R5 probes through the real broker and
executor; add a case for an agent with no grant. Risk M (contracts of the broker and executor), size M.

#### D12. The bulk-change window ignores owner waits

**Cause [V].** `RecentFileChanges.count` drops entries older than `windowMs` on the wall clock
(BulkChanges.kt:36-46, prune :37). The counts live in memory (:32).

**Fix.** A shared `OwnerWaitLedger` (bounded list of agent, task, root, from, to). It is filled in
`PermissionBroker.ask`'s `finally` (:213-222 already has startedAt and at) and read by `RecentFileChanges`. An
entry's age is then `now - at - (owner-wait time of the same agent, task or objective since at)`. Both objects
are wired in AppGraph next to the toolset (AppGraph.kt:306). Restart reset stays (LIMITATIONS.md:148-149).
Test: 15 changes, an 11-minute approval wait, then 6 changes: the call asks the owner (today it does not).
Size S-M.

#### D14, D17

- **D14.** While Android's own install or uninstall confirmation is open, the task shows "time limit paused
  while Android asks you". The paused time can be reported when it starts, not only after the tool returns
  (ToolExecutor.kt:242-243). Low value; P6?.
- **D17.** For put-aside home folders that `clearPutAside` (SystemShellRunner.kt:494) cannot delete, run
  `/system/bin/rm -rf -- <path>`. Only for a path the app built itself under the cache's put-aside folder,
  never one taken from command output. toybox walks directories relative to open folders, so depth should not
  matter [A]. Test it with a very deep path in the androidTest suite. P6?.

### D. Orchestration

- **D19 (scheduled for Phase 6 in STATUS.md:326-328).** See reader-performance P1 (rebuild `tasks` with large
  columns last, summary queries, `observeTaskText` only for the Result tab and the task screen) and P2
  (`flowOn(Dispatchers.Default)`, split the 1 s ticker). This reader adds nothing.
- **D20.** In `announce` (ObjectiveDriver.kt:671-689) the log entry is written before the message whose id
  (`announcementId`) says whether the end was announced. Fix: before `taskChanged(done, "Objective
  completed")`, look in the activity log (read-only port in the orchestrator context; `ActivityFilter` has
  `rootTaskId`, ActivityEvent.kt:63-77) for a TASK event of that root whose title matches and whose
  `data.attempt` equals `done.attempt`. Skip if it is there. Add a test point to the existing crash harness.
- **D21.** `finalResult(round)` numbers by run (ObjectiveDriver.kt:771), so numbers skip after a run that
  ended without a result. Cosmetic; P6? only.
- **D22.** `ownersVersion` (KnowledgeKeeper.kt:166-169) compares the owner's last content edit only with the
  Director's last. Fix: the owner's version only when the last content edit by anyone is the owner's.
- **D23.** `failed()` ignores `MemoryPermissionException` (KnowledgeKeeper.kt:99-101), so `Saved.error` is
  null and the status is silent. Add `Saved.refused: String?` and the status sentence "Not saved: the Director
  may no longer write to <space>. Allow it in Memory > Spaces."

### E. Models and providers

#### D26, D27. The three "(test)" fields

**Facts [V].**
- `ModelCapabilities.vision`, `reasoning` and `coding` (Models.kt:170-184) are filled by providers'
  estimators: ModelCapabilityEstimator.kt:45-52, :85-111; OpenAiModels.kt:73; Anthropic ModelTraits.kt:89-90.
  Nothing in :framework or :app reads them.
- `AgentSelector.score` uses skills, words, reliability and load only (AgentSelector.kt:85-92).
  `AgentAvailability.Ready` carries the model id (AgentAvailability.kt:15), so the model's capabilities can be
  looked up in `RegistrySnapshot.models`.
- `ChatMessage.images` exists (Chat.kt:35, :44, :55), and every adapter already sends images:
  OpenAiRequests.kt:145-157, GeminiRequests.kt:215, OllamaRequests.kt:99, Anthropic WireMessages.kt:56. The
  chat has no way to add one; ChatViewModel has no image path.

**To make Vision real (M).**
- A "Add a photo" button in the chat composer using Android's Photo Picker (`PickVisualMedia`, from
  activity-compose already in the cache [V]; no permission needed).
- It is offered only when the agent's resolved model has `vision = true`. Otherwise the text reads "This
  agent's model cannot look at pictures. If it can, switch on Vision for it in Models."
- Downscale to 1,568 px on the long side and JPEG before base64 [A: a size every adapter accepts].
- Skip fallbacks without vision for a request that holds images: the text-only fallback is not used and the
  reply says why.
- Local-only mode and the egress log already apply.
- Then remove `testLabel` at ModelEditorScreen.kt:293 and PlaceholderLabelsTest.kt:56.
- Owner value: Grok 4 models are estimated as vision models (ModelCapabilityEstimator.kt:102) [V].

**To make Reasoning and Coding real (S-M).**
- (a) AgentSelector: if the task's skills or words mention coding ("code", "script", "program", "debug") add
  0.75 per Coding step above BASIC for the agent's model. Do the same for Reasoning ("analyse", "plan",
  "compare", "calculate", "decide"). UNKNOWN adds nothing.
- (b) The New objective form warns in plain words when the Director's model is rated Basic for reasoning:
  "The Director's model is rated Basic at reasoning, so its plans may be weak. You can give the Director a
  stronger model in Agents."
- (c) The agent editor's model picker shows the tier.
- Then remove the labels at :299-300 and PlaceholderLabelsTest.kt:57-58.

**Alternative (S).** Remove the three fields from the editor and keep the estimates internal, if the owner
prefers fewer controls.

#### D28. One failed check blocks a provider's agents

**Cause [V].** Providers are re-checked at start, when the app returns after `PROVIDER_RECHECK_MS = 10 min`
(AppStartup.kt:175, :255), or on "Check now". A network error stores OFFLINE (DefaultProviderManager.kt:257,
:296).

**Fix.**
- (1) When an agent would be refused only because its provider is OFFLINE or ERROR and that provider's last
  check is older than 60 s, run one health check (bounded at 10 s) first. Do not do this for rejected keys;
  those need the owner.
- (2) `ConnectivityManager.registerDefaultNetworkCallback` (API 24 [K]) re-checks OFFLINE providers on
  `onAvailable`.
- (3) While an objective runs, re-check failed providers after 2, 5 and 10 minutes.

Tests: provider manager with a fake clock; Robolectric shadow network callback. Size S-M.

#### D29, D30, D32-D34

- **D29.** `sameModel` (ChatPresentation.kt:222-226) flags "grok-4-latest" answered as "grok-4-0709". Strip
  `-latest`, `-beta`, `-preview` and date stamps (`-\d{4}-\d{2}-\d{2}`, `-\d{8}`, `-\d{4}`) from both names
  before comparing. The flag is information only, so a missed difference costs little.
- **D30.** Keep "Find servers on this phone". Scanning the owner's network would touch every device on it;
  servers there are added by address.
- **D32.** Keep shrinking off (app/build.gradle.kts:47-49). The Anthropic SDK uses Jackson reflection, and the
  risk is high for little value to this owner.
- **D33, D34.** Not in Phase 6. Since Phase 6 is the last phase, LIMITATIONS.md:50-51 and :63 should no longer
  say "planned" but "not built". `ProviderKind.ON_DEVICE` (Models.kt:47-48) has no client and no preset [V];
  leave it unused or remove it.

### F. Memory

- **D35, D36: other readers.** For D36, note that androidx.work is listed in libs.versions.toml (`work =
  "2.12.0"`) but is not in the offline Gradle cache and is used by no module [V], so background upkeep should
  use the platform `JobScheduler`.
- **D37.** MemoryGraphViewModel.kt:201-212 refuses the owner's own export in local-only mode, while sharing a
  workspace file from the Workspace screen is allowed (LIMITATIONS.md:312-315). Make them consistent: allow
  the owner's export with the line "The app you choose may send it off the phone." Or offer "Save to the
  workspace", which never leaves.
- **D38.** At save, keep only space ids that exist now, and write only the fields the owner changed (re-read
  the profile in the update lambda). Today `setSpaceReadable` and `setSpaceWritable` edit the draft's full
  policy (AgentEditorViewModel.kt:238-246).
- **D39.** A "Keep on this phone" switch on a space (and, as a second step, on a memory). Memories in such a
  space are left out of recall and `memory_search` for an agent whose resolved model is not on the device.
  This needs a column (schema 6, together with reader-performance P1). It fits the owner's rule "Do not force
  all agents to share all memories". P6?.

### G. App shell and other

- **D40.** Already 96 dp of bottom padding (AgentsScreen.kt:132), so scrolling to the end shows the last card.
  The remaining overlap at rest is the usual one; collapse the button while scrolled.
- **D41.** Text search with LIKE on title and detail, and "Show older" with a keyset on (time, id). Query costs
  are in reader-performance P3.
- **D45.** The first `graph` access builds `AppGraph` and the production ports on the main thread
  (MainActivity.kt:14-22 → AiControlCenterApp.kt:44-55 → ProductionPorts.kt:33-91) [V]. Whether that does
  disk or keystore work there is [A]. Turn on StrictMode in debug builds and run a cold start on the
  emulator. On the phone, record REASON_ANR with its trace at the next start (API 30+, `getTraceInputStream`
  [D]); shared with reader-background-recovery R1.
- **D47.** `onboardingCompleted` is never read (FrameworkSettings.kt:43). A short first-run guide would help
  this owner, in three plain cards:
  1. "A model is the AI that thinks; a provider is the company or server it runs on; an agent is a helper
     you set up here that uses a model."
  2. "Add your xAI key."
  3. "Try it with the simulator."

---

## 3. Scans

- **TODO, FIXME, XXX, HACK** in *.kt, *.kts and *.xml outside build folders: none [V]. The matches are "U+XXXX"
  in documentation comments (ApprovalPresentation.kt:424-493, PreviewText.kt:33-124) and the text of one test
  command (ShellCommandsTest.kt:348).
- **"(test)" labels:** `testLabel(` is used only at ModelEditorScreen.kt:293 (Vision), :299 (Reasoning) and
  :300 (Coding) [V]. The rule and the helper are in app/.../components/TestLabel.kt:1-21, and the check is
  PlaceholderLabelsTest.kt:56-58 [V].
- **Other placeholder wording** ("not built yet", "coming soon", "Phase N") in UI strings: none [V].
- **Settings that nothing reads:** `onboardingCompleted` only (D47) [V]. Every other field of
  FrameworkSettings is read outside the settings screen [V].

---

## 4. Suggested order for Phase 6

1. **Small and independent, one pass:** D2, D6, D8, D20, D22, D23, D29, D37, D38. Each is S, with unit or
   Robolectric tests.
2. **Daily-use reliability:** D28 (provider re-check), D5 (screen after apps_open), D4 (banner hold),
   D7 (force-stop notice).
3. **Broker:** D10 (cap), D11 (shell slot), D12 (owner-wait-aware window), D9 (outcome on the Approval
   screen).
4. **D1, the isolated script process.** Do it after D2, and on the emulator. Budget several 7-minute boots: the
   device test needs one boot plus installs.
5. **The "(test)" fields:** D26 and D27, or remove them (decision for the owner, in plain words).
6. **With the other readers:** D19 (reader-performance P1/P2); D25, D36 and D44 (reader-background-recovery).
   Do D39 and D9's schema variant only together with the schema-6 migration.
7. **Document as final in STATUS.md and LIMITATIONS.md:** D13, D15, D16, D18, D24, D30, D32, D33, D34, D42,
   D43, D48. After Phase 6, "planned" should not appear for anything that will not be built.

## 5. Cannot be checked here

- Whether `Process.killProcess` from the main app fails on the isolated process, and how quickly the system
  kills an isolated process after `unbindService`. This needs the emulator test in D1.
- Start-up time and memory of the script process on a real phone.
- Vendor phones' behaviour with isolated services and with the accessibility service after force stop.
- Grok's model names as the provider reports them (D29), and how well a real model uses screen tools after
  the D5 wait.

## 6. Sources

- `<service>` element, `android:isolatedProcess`, `android:process` (all versions):
  https://developer.android.com/guide/topics/manifest/service-element
- `android.os.Process`: `killProcess` restrictions, `myPid`, `isIsolated` (API 28), `isIsolatedUid` (API 34):
  https://developer.android.com/reference/android/os/Process
- `Context.bindIsolatedService` (API 29; BindServiceFlags overload API 34), `BIND_SHARED_ISOLATED_PROCESS`
  (API 34), `BIND_PACKAGE_ISOLATED_PROCESS` (API 35), `bindService`:
  https://developer.android.com/reference/android/content/Context
- `ZygotePreload` (API 29): https://developer.android.com/reference/android/app/ZygotePreload
- `R.styleable` `AndroidManifestApplication_zygotePreloadName`:
  https://developer.android.com/reference/android/R.styleable
- `ApplicationExitInfo` (API 30; `REASON_USER_REQUESTED` and its pre-Android 14 meaning, `getRealUid` for
  isolated processes, `REASON_FREEZER` API 33): https://developer.android.com/reference/android/app/ApplicationExitInfo
- Binder buffer 1 MB per process: https://developer.android.com/reference/android/os/TransactionTooLargeException
- SDK jars inspected with javap: /opt/android-sdk/platforms/android-36/android.jar and android-37.2/android.jar
  (`AccessibilityWindowInfo` methods, `Process`, `Context.bindIsolatedService`, `ApplicationExitInfo` reasons).
- Not reachable from this session (they would have to be allowed in the session's network settings):
  android.googlesource.com (OomAdjuster "isolated not needed"), gitlab.e.foundation (an AOSP mirror),
  firefox-source-docs.mozilla.org (GeckoView isolated processes).
