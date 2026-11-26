# Hand-off from the wave-2b merge (WP2, WP17, WP3, WP6)

Branch `claude/ai-control-center-architecture-wjdfww` in `/home/user/rune-companion`, from 6fb816d. Not pushed. The builders'
worktrees (`/home/user/p6-wp2`, `p6-wp17`, `p6-wp3`, `p6-wp6`) are left in place; their branches are fully merged. All four builders
finished, committed, and left clean worktrees; nothing was left out.

## 1. Merges

| Commit | What | Conflicts | Whole suite after it |
|---|---|---|---|
| 6ed6c0f | merge p6/wp2 (6bc5797), background work and recovery | none | 3,129, 0 failed (app 540, core 165, data 453, device 360, framework 1,014, memory 107, providers 229, providers-anthropic 89, testkit 172) |
| e7343f6 | merge p6/wp17 (e0e2e95), routines and folder pick-up | none | 3,201, 0 failed (app 569, framework 1,057; others unchanged) |
| de84a16 | merge p6/wp3 (d33fc65), backup file | none | 3,244, 0 failed (app 576, data 489; others unchanged) |
| 2c901c2 | merge p6/wp6 (d49d6bd), scripts in their own process | none | 3,359, 0 failed (app 666, framework 1,082; others unchanged) |

No two branches touched the same file, and no merge broke the build or a test, so there is no "fix after merging" commit.
The points where the packages meet were checked by reading and by their tests:
- WP2's `ObjectiveWorkService` handles WP17's `RoutinesWiring.ACTION_RUN_DUE_ROUTINES` (foreground first, then
  `graph.routinesAssembly.runDue()` while the service is held up; `ObjectiveWorkServiceTest`), which is exactly what WP17's
  `RoutineAlarmReceiver` sends (`RoutineAndroidTest`). WP17's hand-off row for `ObjectiveWorkService` is therefore done.
- WP2's `UpkeepGraph` runs WP17's pick-up step after its own steps and under its checks.
- WP17 edited the two WP17 lines of WP1d's shared `AssemblyMoveTest` (the stub it replaced); kept as merged.
- WP3's restore and WP17's runner, WP2's upkeep job and WP7's start of work all use the process's one `MaintenanceGate`.
- WP6's isolated process returns from `AiControlCenterApp.onCreate` before WP2's hooks; WP2's exit history and crash notes count
  the main process only (rule 31).

## 2. Hand-off items applied by the merger (each with its test)

| Commit | Item | From | Test |
|---|---|---|---|
| 9ae47d2 | `AiControlCenterApp.upkeep`: folder pick-up reads the owner's folders through Android's folder access, `PickUpFoldersView(dataLayer.sharedFolders, SharedFolderFilesView(<the phone layer's SAF reader>))` instead of `PickUpFolders.None` (which found every folder gone), and logs its runs (`ActivityRecorder`). Built by `AiControlCenterApp.pickUpStepPorts(...)` (internal companion): no graph, no system call; only the phone layer's folder reader is kept (option (b) of the hand-off, so no `:device` file changed). | WP17 §2 row 1 (secfix WP17) | app `di/UpkeepPickUpWiringTest` (3): a new text file in a read-only shared folder becomes an IMPORT memory through a documents provider and the real `ContentResolver`, files already there are left out, the run is logged; a folder whose access was withdrawn is "unreadable", not "gone", nothing stored; the application's upkeep holds these ports and builds no graph. Fails with `PickUpFolders.None` (mutation checked). |
| 46947c5 | `AppGraph` init: `BackupWiring.useToolTitles(ownerBackup, toolRegistry)`, so a saved chat says "A tool was used here: Current time." instead of the function name. The backup gets the look-up only. | WP3 §2 item 1 | app `di/BackupToolTitlesWiringTest` (1): on the graph as production builds it, every registered tool's function name maps to its title, `tools_open` to "Open more tools", an unknown name stays. Fails without the line (mutation checked). |

`UpkeepIsolationTest`, `UpkeepJobTest` (the rule-1 walk from the job, now reaching the SAF reader behind the view),
`ProductionGraphDependencyTest`, `OperatorDependencyTest` and `AssemblyMoveTest` stay green.

## 3. Items not applied, with their owner

### Wave 3

| Owner | Item | Source |
|---|---|---|
| WP8 | `AppGraph`: `val waitingRoutines: WaitingRoutines get() = routinesAssembly.waitingRoutines`, and in `onAppForegrounded()` `appScope.launch { routinesAssembly.catchUp() }` (catch and log failures). Do it together with the Dashboard banner (next row): a waiting run nobody can see is not useful, and the banner's view model already calls `catchUp()` when created. | WP17 §2 |
| WP8 / WP10 | Dashboard: `WaitingRoutinesBanner(viewModel { WaitingRoutinesViewModel(graph.waitingRoutines, graph.clock) })` at the top (draws nothing when no run waits); WP10's Routines screen links to it. "Tap to start" opens `MainActivity` with no extra, so the banner is the only place where a waiting run can be started. Consider the "waiting to start" count in the attention list. | WP17 §2, §6; secfix second review |
| WP8 (decision) | Safe start and routines: `RoutineRunner` does not look at safe start (`graph.ports.background.safeStart.state`). Recommended: while safe start is on, `runDue()` starts nothing by itself (a due run waits for the owner's tap, like a late one), so an app that crashed three times does not start unattended work. A crash loop is already bounded (an interrupted objective of the routine makes the next due time SKIPPED_STILL_RUNNING), so this is a safety margin, not a hole. | WP2 §4 (note to WP17), merge |
| WP8 | `RoutinesAssembly`'s two collectors (`keepAndroidInStep`, the "none waiting" notice) run on `appScope` directly, not under WP2's `SafetyCollectors` (rule 26): a failing database read there stops keeping the alarm and receivers in step until the next start (the scope handler logs it). Run them under `SafetyCollectors` from `GraphServices`, or restart them in the assembly. | merge (rule 26) |
| WP8 (framework/core, WP7's files) | `Orchestrator.pause(rootId, reason)` (or a system pause) so the storage pause stores "Paused: the phone is almost full" as the objective's status text instead of "Paused by the owner." and is not journalled as the owner's action. The activity log and the result notification already say the right thing. | WP2 §2 row 1 |
| WP8 | `AppGraph.safeStart: SafeStartControl = ports.background.safeStart` for WP10's banner (until then `graph.ports.background.safeStart`). | WP2 §2 row 3 |
| WP8 / WP10 | `MainActivity`: run queued `gate.whenOpen` work only while the activity exists (lifecycle-aware `whenOpen(owner, action)`; harmless today), and pass the gate to `RecoveryHost` so "Try again" calls `gate.recheck()`. | WP2 §2 row 4 |
| WP8 (optional) | `UpkeepPorts(databaseCheck = dataLayer.databaseHealth)` in `AiControlCenterApp.upkeep` instead of the default probe through the activity log (same classification). | WP2 §2 row 5 |
| WP8 | Architecture walks: `UpkeepJobService` as a root (the rule-1 walk is in `app/src/test/.../work/UpkeepJobTest.kt` with its own forbidden list) and the script host (`ScriptsWalkTest` walks the production engine and a `RunHost` built in the test; a `RunHost` exists only during a run, so it cannot be a graph root). Optional: list `WaitingRoutines`, `RoutineRunner`, `PickUpRunner` and the two `Default…` owner ports as owner-only in both walks (they already hold owner-only stores, so a holder fails today). | WP2 §2 row 6, WP6 §3, WP17 §2, wave-2a merge §3 |
| WP8 (optional) | `Ports.backup` and `AppGraph.ownerBackup` typed `DetailedOwnerBackup?` (data/backup) so WP10's view models need no cast; `AssemblyMoveTest`'s "no backup in wave 1" message for `TestApp` updated. Or core's `BackupSummary`/`SaveReport`/`RestoreReport` gain `notes`/`keptBack` (additive). | WP3 §2 items 2-3 |
| WP8 | `RestoreChecks` (data/backup) copies the editors' rules (`ProviderDraftValidator`, `MemoryRules`, agent and space bounds); `RestoreChecksAgreementTest` fails when they drift. Whoever changes a validator changes `RestoreChecks` with it. | WP3 §2 item 5 |
| WP8 (optional) | Manifest: a `TIMEZONE_CHANGED`/`TIME_SET` filter for routines. Not a manifest line alone: `BootReceiver` only re-arms the remembered instant; realigning needs the runner (`RoutinesAssembly` realigns at every pass and graph start). Today, after a zone change, the alarm armed for the old instant fires once at the old time. | WP17 §2 |
| WP8 (optional) | A debug merged-manifest test would now list `.scripts.ScriptProbeService` (isolated, `:scripts`, not exported, debug only). The release test of rules 22 and 23 is unaffected. | WP6 §3 |
| WP8 | StrictMode: the main-thread file touches left are `noBackupFilesDir` look-ups (crash notes' folder in `Application.onCreate`, the ledger's path in `BackgroundWiring.production`). | WP2 §4 |
| WP10 | Recovery screen: `RecoveryHost(state, recovery)`; the confirm word in `OutlinedSecureTextField` with a view-model-owned `TextFieldState` and a typing test, checked with `OwnerRecovery.confirms`; `RecoveryActions.startOverAndRestart(activity, recovery)`; `RecoveryResult.Refused.reason` shown as it is; "Try again" → `gate.recheck()`; FLAG_SECURE; `DatabaseState.ownerText`; say the old data is kept in the app's private storage. | WP2 §4 |
| WP10 | Background check (`graph.backgroundHealth.state`, `refresh()` on return; `graph.backgroundScreens.batteryExemption()` / `appDetails()`, `ActivityNotFoundException` → `BackgroundSettingsIntents.NO_SUCH_SCREEN`); storage (`graph.storageWatch.level`: LOW Dashboard warning, CRITICAL "running objectives were paused"); safe start banner (`SafeStartState(on, reason)`, `reason.ownerText` plus "Interrupted work waits for you", "Back to normal" → `leave()`; Tier B); settings "Keep the phone awake while agents work" and "Tell me when work ends" (both fully working); the activity lines WP2 writes. | WP2 §4 |
| WP10 | Routines screen (`graph.ownerRoutines`; fields with `AppTextField(state = …)` and typing tests; `pausedNote`, `consecutiveFailures`, `nextRunAt`, master switch, run history with the outcome words of handoff-wp17.md §6; "Alarms and reminders" link from the screen on Android 12+; Tier B) and Folder pick-up screen (`graph.ownerPickUpRules`; the refusals `FOLDER_NOT_READ_ONLY` with "Make it read-only", `FOLDER_OVERLAPS_WRITABLE`, `FOLDER_UNREADABLE`, `FOLDER_GONE`; `lastRunAt`/`lastResult`; imports need a small owner-only read view of `PickUpRuleRepository.observeImports`, WP8; the master switch; "files already in the folder are left out, only text files, 5 MB a day"). Optional, "(test)" until it works: importing the files seen at switch-on by ticking them. | WP17 §6 |
| WP10 | Backup Save and Restore screens (Tier B): API and texts in handoff-wp3.md §4 (`SavePassphrase`, `SecretTextField(state = …)` owned by the view model with a paused-clock typing test, FLAG_SECURE on both, `ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT`, delete a partly written document after a failed save, indeterminate key-derivation step with Cancel, preview's `refusal`, the Director by name, `RecoveryActions.restartApp(activity)` after a restore). | WP3 §4 |
| WP11a | New objective: `graph.storageWatch.check()`; at `StorageLevel.CRITICAL` refuse with "The phone is almost full. Free some room before starting an objective." A routine's objective: "Started by routine '<title>'" and "Screens: not allowed" when `screenActionsAllowed` is false. The question notification opens the Approval route with `EXTRA_REQUEST_ID` (unchanged). | WP2 §2 row 2, §4; WP17 §6 |
| WP11b | A chat answer keeps the service and the "<agent> is answering · Stop" notification; Stop cancels like the chat's own Stop. Script answers may carry the texts of handoff-wp6.md §1 (fallback note in parentheses): show them as they are; a Tools > Scripts card may say "Scripts run in a sealed process of their own" only with the caveat "unless the phone refuses; the answer then says so". | WP2 §4, WP6 §5 |
| WP9 | "Started by routine" on a routine's objective; script answers shown as they are. Nothing else new. | WP17 §6, WP6 §5 |

The wave-3 rows of handoff-wave2a-merge.md §3 that are not repeated here are still open.

### Waves 4-6

| Owner | Item | Source |
|---|---|---|
| WP12 | Attack lists: routines (an objective text asking for something consequential: the broker still asks; a folder made writable and back between runs; a file named like a memory heading; flooding: 5 MB/day, 1 MB/file, text only); backup (tampered file, a file changing between check and write, an app that stops being fresh in between, a provider record naming a vault alias, hostile strings, 2,000,000 iterations, cancellable); scripts (`RunHost` limits, `ScriptFrames.readOutcome` caps, the engine's bounds and `letGo` paths, the quiet end; `HostileRunner` is a ready tool). Known limits: a save holds the encoded records in memory and does not dry-run its own file through the restore checks. | WP17 §6, WP3 §4, WP6 §5 |
| WP12 / WP8 | Tier B cut: if routines or folder pick-up are cut after round 2, force `routinesEnabled`/`folderPickUpEnabled` off and hide their entry points; if the backup is cut, `BackupWiring.production` returns null again. | plan, WP3 §5 |
| WP13 (§10) | The manual device checks of device-tests-wave2.md §2 (routines: receivers disabled until a routine exists, exact alarm in Doze, "Tap to start" without "Alarms and reminders", reboot re-arms, folder pick-up through the upkeep job 6001; WP2: wake lock in `dumpsys power`, one wake alarm, the upkeep job, result notifications, a chat answer in the background). | WP17 §5, WP2 §3 |
| WP14 | Docs: WP2 (key generations and file names, Start over and set-aside folders, safe start, crash notes without message text, wake lock and alarm rules, result notifications, storage pause, upkeep job: idle, charged, daily, not after a phone restart until the app is opened; "Android 9 and 10 do not say why"; only SDK 35 tested on the JVM); WP3 (what is and is not in the backup, keys entered again, restore only into a fresh app which replaces the starter team, what is off after a restore, re-indexing at the next start, the passphrase is the only key); WP6 (the proposed LIMITATIONS, STATUS and ARCHITECTURE texts in handoff-wp6.md §3: scripts in an isolated process, fallback said in the answer, Android 9 shares one process; remove the "planned for Phase 6" and the one-line-JavaScript-on-the-emulator rough edges); WP17 (Android may delay routines; "Alarms and reminders"; master switches off after installing; limits of Appendix A; folder pick-up only through the daily upkeep job). | WP2 §2, WP3 §4, WP6 §3, WP17 §6 |

## 4. Device tests to run

All in `scratchpad/phase6/device-tests-wave2.md` (module, class, what it proves, commands): data `recovery/StartOverDeviceTest` (3),
data `backup/BackupVectorsDeviceTest` (5) and `backup/BackupDeviceTest` (1), app `scripts/ScriptProcessDeviceTest` (6): 12 tests;
plus the manual checks of WP17 and WP2. None was run (no emulator for builders or the merger).

## 5. Final checks

Head after the merge: `46947c5` (four merge commits, two hand-off commits; no fix commit was needed).

- Whole suite, forced real run (every test task named with `--rerun`: `:app:testDebugUnitTest`, `:data:testDebugUnitTest`,
  `:device:testDebugUnitTest`, `:core:test`, `:framework:test`, `:memory:test`, `:providers:test`, `:providers-anthropic:test`,
  `:testkit:test`; all nine executed), then `./tools/test-summary`: **3,363 tests, 0 failed**: app 670, core 165, data 489,
  device 360, framework 1,082, memory 107, providers 229, providers-anthropic 89, testkit 172. At 6fb816d it was 3,052: WP2 +77,
  WP17 +72, WP3 +43, WP6 +115, merger +4 (app: UpkeepPickUpWiringTest 3, BackupToolTitlesWiringTest 1).
- `./tools/gw --offline :app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest
  :device:assembleDebugAndroidTest`: BUILD SUCCESSFUL (every module with instrumented tests).
- Not pushed. Worktrees `/home/user/p6-wp2`, `p6-wp17`, `p6-wp3`, `p6-wp6` left in place.
