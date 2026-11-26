# Hand-off from WP8, part 3a (wave 3: routes, slots, stubs and shared files)

Branch `p6/wp8` (worktree `/home/user/p6-wp8`, removed after the merge), from cae8706. Merged into
`claude/ai-control-center-architecture-wjdfww`. The binding plan for the 3b packages is
`scratchpad/phase6/wave3-ownership.md`; this page says which hand-off items addressed to WP8 were done (with their tests)
and which were not, and why.

## 1. Done, each with its test

| Item | From | What changed | Test |
|---|---|---|---|
| Routes, slots, stubs, ownership | plan WP8 3a, task | `ui/shell/Destinations.kt` (Team, Activity as a data class with tab and filters, Question, Daily limits, Backup save/restore, Routines, Routine editor, Waiting routines, Pick-up rules, Pick-up rule editor), `AppNavHost` wiring, `SettingsSlots`/`SettingsNavigation`, `DashboardSlots`, stubs with final signatures (wave3-ownership.md §4) | `ui/shell/Wave3RoutesTest` (every route opens in its section), `Phase6StubGateTest` |
| "Waiting for you" and the badge counting approvals and questions | plan WP8 3a | rail item renamed; `ShellUiState.pendingQuestions`; `waitingText(count, questions)` | `ui/shell/ShellWaitingTest` (wording, counts, the strip with a real question, the rail) |
| "Tap to start" confirmation step | handoff-wp17.md §2, §3, §6; task | `WaitingRoutinesRoute` (WP10c's screen, working now with WP17's banner); the shell opens it once per waiting run this process has not shown, only when the app is on screen and not on the Dashboard; never from an intent (rule 23) | `ShellWaitingTest` (shown once, a new run again) |
| `AppGraph.waitingRoutines` and the catch-up in `onAppForegrounded` | WP17 §2, wave-2b merge §3 | `appScope.launch { routinesAssembly.catchUp() }`, failures logged | `di/Wave3WiringTest` (a passed due time becomes a waiting run; nothing starts) |
| Dashboard banner placement | WP17 §2, wave-2b merge §3 | `DashboardSlots.waitingRoutines` at the top | `Wave3RoutesTest` / existing `WaitingRoutinesBannerTest` |
| `AppGraph.safeStart` | WP2 §2 row 3 | `= ports.background.safeStart`; `TestApp.safeStart` (`TestSafeStart`) | `Wave3WiringTest` |
| Safe start and routines (decision) | wave-2b merge §3 | **Decided:** while safe start is on, a routine due on time waits for the owner's tap (`RoutineRunner(safeStartOn)`, `SAFE_START_NOTE`, the waiting notice), never starts by itself; the owner's tap still starts it | framework `RoutineRunnerTest` (safe start), `Wave3WiringTest` |
| Routines' collectors under `SafetyCollectors` (rule 26) | wave-2b merge §3 | `RoutinesAssembly` supervises "Keeping the routines' alarm in step" and the "routine waiting to start" notice | `routines/RoutinesAssemblyTest` (a failing step is logged and starts again) |
| Pause with a reason | WP2 §2 row 1 | core `Orchestrator.pause(rootId, reason)` (default: the owner's pause); `DefaultOrchestrator` stores the reason on the objective and its tasks, sends it from the system, logs no owner action; the storage pause uses it (`WorkResult.STORAGE_PAUSED_DETAIL`) | framework `OwnerControlTest` (new test), app `ObjectiveWorkServiceTest` (storage pause) |
| Lifecycle-aware `whenOpen`; the gate's "Try again" | WP2 §2 row 4 | `AppGate.whenOpen(owner, action)`; `MainActivity` uses it; `RecoveryHost(state, recovery, onTryAgain = gate::recheck)` | `health/StartPathOffMainTest` |
| Read ports for the screens | task, WP17 §6, WP3 §2 | `AppGraph.todayUsage` (`app/usage/TodayUsage.kt`, the limiter's counts + limits, a midnight tick in production only), `pickUpImports` (`PickUpImports` read view), `zone`, `screenSwitchedOffSentence` (D7, owner-only, from the device layer through `OperatorPorts`), `SystemScreens.storageAppName(treeUri)` (the other app's name for the Folders note; PackageManager, off the main thread), `Ports.backup`/`AppGraph.ownerBackup` typed `DetailedOwnerBackup?` | `usage/TodayUsageTest`, `Wave3WiringTest` |
| One owner zone | WP4 §2, wave-2a merge §3 | `Ports.zone` → `ModelsAssembly(zone)`, the orchestrator (`TeamWiring`), `AppGraph.zone`; `TestApp.zone` (UTC) shared by its usage store, routines and the graph | `Wave3WiringTest` |
| Architecture walks | secfix "Wave 3 notes", wave-2a/2b merges | app walk: `WaitingRoutines`, `RoutineRunner`, `PickUpRunner`, `DefaultOwnerRoutines`, `DefaultOwnerPickUpRules`, `PickUpImports`, `SafeStartControl` owner-only; the **script host** (`RunHost`, a binder made per run) is a root of `ProductionGraphDependencyTest`; the **upkeep job** is walked there too with rule 1's list (also no settings writer) | `di/ProductionGraphDependencyTest` (3 tests), `OperatorDependencyTest` |
| Release merged-manifest test (rules 22, 23) and the debug one | plan WP8, WP1d §2, WP6 §3 | `app/build.gradle.kts` hands both merged manifests to the unit tests (AGP 9 builds unit tests for debug only, so the release manifest is passed as a task input) | `MergedManifestTest` (18 permissions; exported: main activity, accessibility service, profile installer, share receiver; MainActivity's one filter; the share receiver's SEND/SEND_MULTIPLE text filters; isolated script service; the debug build adds only Compose's test and preview activities and the script probe) |
| Share receiver manifest entry | plan WP8, task | `.share.ShareReceiverActivity`: exported, SEND and SEND_MULTIPLE of `text/*`, DEFAULT, `excludeFromRecents`; the stub closes at once and stores nothing | `ManifestRulesTest`, `MergedManifestTest` |
| D45 StrictMode | plan WP8 | `DebugStrictMode`: thread policy `detectAll().penaltyLog()`, VM policy (leaks, file-URI exposure, content URIs without permission, unsafe intents, wrong contexts) `penaltyLog()`; debug builds only, never under Robolectric | `StartPathOffMainTest` |
| Main-thread work at a cold start | device-tests-wave3.md §4, WP2 §4, wave-2b merge §3 | (1) the gate builds the graph on the app scope **before** it opens (`AppGate(prepare = { graph })`), so the main thread no longer wires every service at a cold start (class loading of the whole graph: the most likely cause of the "Waited 5005ms for FocusEvent" ANR on the first launch after boot); (2) the crash notes' folder in `Application.onCreate` and the start ledger's folder in `BackgroundWiring.production` (both on the main thread) are asked for on first use (`getNoBackupFilesDir()` creates the folder) | `StartPathOffMainTest` (prepared off the main thread before queued work; nothing prepared for data that does not open; a failed preparation still opens; folders asked once at first use) |
| Labels of the newest call in the in-memory usage store | WP1b §2 | `InMemoryUsageStore` picks a model's/provider's labels by the row's newest call time (Room's `last_call_at`), not the recording order | testkit `UsageLedgerContract` (new test, runs against the in-memory store and Room) |

Checked and already done before wave 3 (nothing to apply): the `MessageHeaderContract` race (WP1b §2; the contract waits
with `singleOrNull()`, the retry rule is gone); the walk roots `providerRechecks` and `OwnerQuestions` (wave-2a merge);
`BackupWiring.useToolTitles` (wave-2b merge).

## 2. Not done, and why

| Item | From | Why not |
|---|---|---|
| `UpkeepPorts(databaseCheck = dataLayer.databaseHealth)` | WP2 §2 row 5 (optional) | `DatabaseHealth`'s open lambda holds the whole `DataLayer`, which the rule-1 walks forbid in the upkeep (`UpkeepJobTest`, `UpkeepIsolationTest`, the new walk in `ProductionGraphDependencyTest`). The default probe opens the same database through the activity log's store and sorts failures the same way. |
| `TIMEZONE_CHANGED` / `TIME_SET` receiver for routines | WP17 §2, wave2b-minors-left (optional) | Since AND-W2B-5 the pass after a zone change realigns and starts nothing; a receiver would only save the one wake at the old instant, and a filter that starts the service at every zone change would add a background start for no gain. |
| `OwnerQuestions`' messages through `ObjectiveJournal.send` | WP7 §3.2 (a), wave-2a merge §3 (optional) | Tidiness only: `OwnerQuestions.send` already stores the same messages and logs without the owner's words (S28), and rule 24's source scan pins where ANSWER is made. Moving it would touch the rule-24 scan for no behaviour change; left for WP12 to judge. |
| Shorter descriptions of `permissions.request` and the memory tools | WP15 §3.6, wave-2a merge §3 (optional, owner request #43) | `permissions.request` is long because it lists every permission's id and title, which the model needs to ask for the right one; the memory tools' texts are already short. Cutting model-facing contracts without a measured gain risks wrong calls; the performance lens of WP12 can measure it with `LeanRequestBudgetTest`. |
| Tier B switches (questions, notes, routines, pick-up, backup, share) | wave-2a/2b merges | Decided after wave 4 round 2 (plan §2); nothing to do before. |
| `RestoreChecks` agreement | WP3 §2 item 5 | Informational: whoever changes a validator changes `RestoreChecks` with it (`RestoreChecksAgreementTest`); no validator changed here. |
| A system pause button or Settings text for safe start's effect on routines | — | WP10a's safe-start banner says "Interrupted work waits for you"; WP10c's Routines screen may add one line that routines wait for a tap in safe start. |

## 3. For the 3b packages (also in wave3-ownership.md)

- Tests that relied on the rail's "Approvals" label now use "Waiting for you" (`screenshots/ApprovalScreenshotTest.kt`,
  changed here). WP11a renames the list screen's title; `journeys/ApprovalJourneysTest` still asserts the old title
  "Approvals" and must change with it.
- `ActivityRoute` is a data class; navigate with `ActivityRoute()` / `ActivityRoute(ActivityTab.USAGE)`.
- Screens reading "today" use `graph.todayUsage` and `graph.zone`, never `ZoneId.systemDefault()` directly.
- WP11b: call `graph.systemScreens.storageAppName` on a background dispatcher.
- WP16: the activity must pass the gate (`(application as AiControlCenterApp).gate.whenOpen(this) { … }`) and show
  `RecoveryHost` when the data does not open, as `MainActivity` does.

## 4. Device tests

Appended to `scratchpad/phase6/device-tests-wave3.md` section 6 (cold start after the graph moved off the main thread,
StrictMode log, the share receiver stub, the waiting-routine confirmation step).

## 5. Checks run

- Worktree (`p6/wp8`, head d0378e5): every test task and `:app:assembleDebug`: 3,445 tests, 0 failed (one failure on the
  way, `ApprovalViewModelsTest`, came from the supervised routine collectors reading the test app's auto-advancing clock;
  their restarts are now timed on the real clock).
- Merged into `claude/ai-control-center-architecture-wjdfww` as d025d68 ("Wave 3: WP8 part 3a, routes, slots, stubs and
  shared files"), no conflicts. There, every test task with `--rerun` (all nine executed) and `:app:assembleDebug`:
  **3,445 tests, 0 failed** (app 710, core 182, data 493, device 367, framework 1,095, memory 107, providers 229,
  providers-anthropic 89, testkit 173; 3,413 before: +32). BUILD SUCCESSFUL. Not pushed. Worktree removed, branch kept.
