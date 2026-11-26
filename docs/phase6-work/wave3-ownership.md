# Wave 3 ownership, routes, slots and stubs (binding for every 3b package)

Written by WP8, part 3a (branch `p6/wp8`, merged into `claude/ai-control-center-architecture-wjdfww` as d025d68, "Wave 3:
WP8 part 3a, routes, slots, stubs and shared files"; start each 3b worktree from d025d68). What WP8 did and did not do of
the hand-offs addressed to it: `scratchpad/phase6/handoff-wp8.md`. Paths below are under
`app/src/main/java/com/aicontrolcenter/app/` unless they start with a module name. Tests mirror them under
`app/src/test/java/com/aicontrolcenter/app/`.

Part 1 (built side by side now): **WP11a, WP11b, WP10a**. Part 2 (another run): **WP9, WP10b, WP10c, WP16**. WP8 integrates.

## 0. Rules for every 3b package

1. Edit only the files this page gives your package. Everything else is read-only for you. A change you need in a file
   you do not own goes into `scratchpad/phase6/handoff-<your package>.md` (what, where, why) for WP8. New files of your
   own (a pure helper, a read-only view, a private composable, an `AppIcons.X` extension val, a test helper
   `testing/<Package>Testing.kt`) are fine when they sit in your own folders.
2. Every stub of yours is marked `@Phase6Stub("<your package>")` (on the file, or on a function or class whose new
   parameters are not used yet). Fill it and **remove the mark in the same change**. `Phase6StubGateTest` lists what is
   left; after the part-1 merge it fails on any WP11a, WP11b or WP10a stub; the final gate (part 2) fails on any stub
   (`-Paicc.stubGate=final` asks for it at any time).
3. Signatures marked FINAL below are called from WP8's files (the navigation host, the Settings and Dashboard routes, the
   main activity). Keep them. If you need one more parameter, write it in your hand-off; WP8 adds it.
4. Constructor parameters of the stub view models are the narrow ports you may use. Do not reach `LocalAppGraph` outside
   a `…Route` composable, and never hand an owner-only port to agent-facing code (both architecture tests).
5. Rule 27: every new text field is `AppTextField(state = …)` or `SecretTextField(state = …)` over a `TextFieldState` your
   view model owns (the stubs already declare them), with a paused-clock typing test (copy `FastTypingTest`'s helpers).
6. Plain words only; "(test)" on anything that does not fully work (today only Vision); 48 dp targets; TalkBack
   descriptions and merged semantics; never colour alone; 360 dp, wide, dark, light, font scale 1.5 and 2.0; Calm and
   `areAnimatorsEnabled()`; heavy work off the main thread (`flowOn(Dispatchers.Default)`).
7. Screenshots are review material, not golden files (`ScreenshotTest` KDoc): record into `build/outputs/roborazzi/` with
   `captureRoboImage`, in a new screenshot test file of your own (dark, light, wide `w900dp-h600dp-xhdpi`, 360 dp
   `w360dp-h740dp-xhdpi`).
8. Run `export TMPDIR=/tmp` and Gradle only through `./tools/gw --offline` in your own worktree.

## 1. Routes (ui/shell/Destinations.kt, WP8) and where each screen sits

Placement follows the plan's table: settings-like switches in Settings; lists of the owner's things in Tools; the Team
view in Tasks.

| Route | Section | Screen composable (owner) | Reached from |
|---|---|---|---|
| `TeamRoute(objectiveId: String? = null)` | Tasks | `ui/team/TeamScreen.kt` `TeamRoute(objectiveId: TaskId?, navigation: TeamNavigation)` (WP9) | Dashboard network card (`NetworkCard(onOpenTeam)`, `DashboardNavigation.onOpenTeam(null)`); objective header (`ObjectiveRoute(onOpenTeam)`) |
| `ActivityRoute(tab: ActivityTab = LOG, agentId: String? = null, text: String? = null)` (now a data class; `Section.ACTIVITY.route` = `ActivityRoute()`) | Activity | `ui/activity/ActivityScreen.kt` `ActivityRoute(initialTab, initialAgent, initialText, navigation: ActivityNavigation)` (WP10a) | the section; Dashboard Today tile (`onOpenUsage` → `ActivityRoute(USAGE)`); Model editor (`onOpenUsage`) |
| `QuestionRoute(requestId: String)` | Tools | `ui/questions/QuestionScreen.kt` `QuestionRoute(requestId, onBack, onOpenTask, onOpenObjective)` (WP11a) | the Waiting for you list (`ApprovalsRoute(onOpenQuestion)`); an Approval route opened for a QUESTION (its notification) hands over with `ApprovalRoute(onOpenQuestion)`, which **replaces** the Approval screen on the back stack; objective and task screens (`onOpenQuestion`); Tools History (`ToolsNavigation.onOpenQuestion`); Team view |
| `DailyLimitsRoute` | Settings | `ui/settings/DailyLimitsScreen.kt` `DailyLimitsRoute(onBack)` (WP10a) | Settings › Daily limits; Dashboard Today tile and attention; New objective's limit line; Objective paused by the limit; Chat's limit sentence; Activity › Usage |
| `BackupSaveRoute` | Settings | `ui/backup/BackupSaveScreen.kt` `BackupSaveRoute(onBack)` (WP10b) | Settings › Backup only (rule 23) |
| `BackupRestoreRoute` | Settings | `ui/backup/BackupRestoreScreen.kt` `BackupRestoreRoute(onBack)` (WP10b) | Settings › Backup only (rule 23) |
| `RoutinesRoute` | Tools | `ui/routines/RoutinesScreen.kt` `RoutinesRoute(navigation: RoutinesNavigation)` (WP10c) | Settings › Routines (`SettingsNavigation.onOpenRoutines`); Tools (`ToolsNavigation.onOpenRoutines`, WP11b places the entry) |
| `RoutineEditorRoute(routineId: String? = null)` | Tools | `ui/routines/RoutineEditorScreen.kt` `RoutineEditorRoute(routineId: RoutineId?, onClose, onSaved, onOpenObjective)` (WP10c) | Routines list; objective "Started by routine" (`ObjectiveRoute(onOpenRoutine)`) |
| `WaitingRoutinesRoute` | Tools | `ui/routines/WaitingRoutinesScreen.kt` `WaitingRoutinesRoute(onBack, onOpenObjective)` (WP10c) — the **"Tap to start" confirmation step** | the shell opens it by itself once per waiting run when the app is on screen and not on the Dashboard (below); Routines list (`onOpenWaiting`) |
| `PickUpRulesRoute` | Tools | `ui/pickup/PickUpRulesScreen.kt` `PickUpRulesRoute(navigation: PickUpNavigation)` (WP10c) | Settings › Routines and folder pick-up; Tools (`ToolsNavigation.onOpenPickUpRules`) |
| `PickUpRuleEditorRoute(ruleId: String? = null, folderId: String? = null)` | Tools | `ui/pickup/PickUpRuleEditorScreen.kt` `PickUpRuleEditorRoute(ruleId, folderId, onClose, onSaved, onOpenFolders)` (WP10c) | Folder pick-up list; Tools › Folders "Pick up new files" (`ToolsNavigation.onNewPickUpRule(folderId)`, WP11b places it) |
| Recovery | outside navigation | `ui/recovery/RecoveryScreen.kt` `RecoveryHost(state, recovery, onTryAgain)` (WP10b; `onTryAgain` = the gate's `recheck`) | `AppGate` instead of the shell |
| Share | outside navigation | `share/ShareReceiverActivity.kt`, `ShareScreen(viewModel, onDone)` (WP16) | other apps' share sheets |

### "Tap to start" (handoff-wp17.md §2, §3, §6; F12; rule 23)

- The notification opens `MainActivity` with **no extra, no action, no data** (WP17's intent contract, unchanged). Nothing
  in an intent selects a screen.
- A cold start lands on the Dashboard, whose top slot is WP17's `WaitingRoutinesBanner` (Start asks once more, then
  starts exactly that run).
- A warm start (the activity existed, any screen): `ShellViewModel` watches `WaitingRoutines.observeWaiting()`; the first
  waiting run this process has not shown yet opens `WaitingRoutinesRoute` once (`AppShell`, `routineToShow` /
  `routineShown`), unless the Dashboard or that screen is already showing. The decision comes from what waits, never from
  the intent; the screen starts nothing without the owner's Start and confirmation.
- `AppGraph.onAppForegrounded()` now runs `routinesAssembly.catchUp()` (passed due times become waiting runs; starts
  nothing). The banner's view model also calls it when created.
- The lock screen: no `showWhenLocked`; a tap there asks Android to unlock first.

## 2. Slots

### Settings (ui/settings/SettingsScreen.kt, WP8)

`SettingsScreen(state, actions, slots: SettingsSlots = SettingsSlots())`; `SettingsRoute(navigation: SettingsNavigation)`
builds each section's view model and fills the slot. Order on screen: Privacy, **Daily limits**, **Usage history**,
Phone operator, **Questions from agents**, **Working in the background**, **Routines and folder pick-up**, Memory,
Orchestration limits, **Backup**, **Appearance**, Device, About.

| Slot | Composable (FINAL signature) | View model (FINAL constructor) | Owner |
|---|---|---|---|
| `dailyLimits` | `DailyLimitsSection(viewModel: DailyLimitsViewModel, onOpenDailyLimits: () -> Unit, modifier)` | `DailyLimitsViewModel(settings: SettingsRepository, today: TodayUsage)`; fields `answers`, `tokens`, `money: TextFieldState` | WP10a |
| `usageHistory` | `UsageHistorySection(viewModel: UsageHistoryViewModel, modifier)` | `UsageHistoryViewModel(settings: SettingsRepository, ownerUsage: OwnerUsage)` | WP10a |
| `questions` | `QuestionTimeoutSection(viewModel: QuestionSettingsViewModel, modifier)` | `QuestionSettingsViewModel(settings: SettingsRepository)` (`agentQuestionsEnabled`, `ownerQuestionTimeoutMinutes`) | WP10b |
| `background` | `BackgroundSection(viewModel: BackgroundSectionViewModel, modifier)` | `BackgroundSectionViewModel(health: BackgroundHealth, screens: BackgroundScreens, settings: SettingsRepository)` | WP10b |
| `routines` (only when the build has routines or pick-up) | `RoutinesSection(viewModel: RoutinesSettingsViewModel, onOpenRoutines, onOpenPickUpRules, modifier)` | `RoutinesSettingsViewModel(settings: SettingsRepository, routines: OwnerRoutines?, pickUpRules: OwnerPickUpRules?)` (both master switches) | WP10c |
| `backup` (only when `graph.ownerBackup != null`) | `BackupSection(onSaveBackup: () -> Unit, onRestoreBackup: () -> Unit, modifier)` | none | WP10b |
| `motion` | `MotionSection(viewModel: MotionSettingsViewModel, modifier)` | `MotionSettingsViewModel(settings: SettingsRepository)` (`calmMotion`) | WP10b |

`SettingsNavigation(onOpenDailyLimits, onSaveBackup, onRestoreBackup, onOpenRoutines, onOpenPickUpRules)`.

### Dashboard (ui/dashboard/DashboardScreen.kt, WP8)

`DashboardScreen(state, navigation, …, slots: DashboardSlots = DashboardSlots())`; `DashboardRoute` builds
`DashboardExtrasViewModel` once and fills the slots. Placement: top = `safeStart`, then `waitingRoutines`; notice area =
`gettingStarted` before the first-run quick start; below the summary = `today`; right after the attention list =
`phoneAttention` (wide screens: the same, in the left column).

| Slot | Composable (FINAL signature) | Owner |
|---|---|---|
| `safeStart` | `SafeStartBanner(extras: DashboardExtrasViewModel, navigation: DashboardNavigation, modifier)` | WP10a |
| `waitingRoutines` | `WaitingRoutinesBanner(viewModel { WaitingRoutinesViewModel(graph.waitingRoutines, graph.clock, graph.zone) })` (exists, app/routines) | WP17's; changes by WP10c |
| `gettingStarted` | `GettingStartedCard(extras, navigation, modifier)` | WP10a |
| `today` | `TodayTile(extras, navigation, modifier)` | WP10a |
| `phoneAttention` | `PhoneAttention(extras, navigation, modifier)` | WP10a |

`DashboardExtrasViewModel(today: TodayUsage, settings: SettingsRepository, directory: RegistryDirectory, tasks:
TaskRepository, backgroundHealth: BackgroundHealth, storage: StorageWatch, safeStart: SafeStartControl, clock: Clock)`
(FINAL). `DashboardNavigation` gained `onOpenUsage`, `onOpenDailyLimits`, `onOpenSettings`, `onOpenConcepts`,
`onOpenObjective(TaskId)`, `onOpenTeam(TaskId?)`. `NetworkCard(…, onOpenTeam: () -> Unit = {})` (WP9 wires it).

### Shell (ui/shell, WP8)

- The rail's first item is now **"Waiting for you"** (was "Approvals"); its badge and the phone's strip count every
  pending request, approvals, permission requests **and agents' questions** (`OwnerRequests.pending`).
- The strip says what waits: "1 request waits for your answer", "1 question waits for your answer", "2 requests and 1
  question wait for your answer" (`waitingText(count, questions)`).
- The Waiting for you **list screen** (`ui/approvals/ApprovalsScreen.kt`) is WP11a's: rename its title and section texts
  to "Waiting for you" there, and open questions with `onOpenQuestion`.

## 3. Read ports the screens get (AppGraph, WP8)

| Member | What | For |
|---|---|---|
| `graph.todayUsage: TodayUsage` (app/usage/TodayUsage.kt) | `observe(): Flow<TodayUse>`, `now()`: `DailyUse` as the limiter counts it, plus `DailyLimits`; `TodayUse.reached`. Moves on at midnight in production (`DayChanges.daily`) | WP10a (Today tile, Daily limits), WP11a (New objective's "Today: 37 of 200 answers") |
| `graph.zone: () -> ZoneId` | the one owner zone ("today" everywhere; `Ports.zone`, TestApp's `zone`) | every screen that cuts days |
| `graph.usageReports`, `graph.modelPrices`, `graph.ownerModelPrices`, `graph.ownerUsage` | as before | WP10a, WP11b (prices in the model editor, cost lines) |
| `graph.safeStart: SafeStartControl` | `state` (`SafeStartState(on, reason)`), `leave()` | WP10a |
| `graph.waitingRoutines: WaitingRoutines` | the runs waiting for a tap | WP10c, the Dashboard banner |
| `graph.pickUpImports: PickUpImports?` (di/wiring/RoutinesWiring.kt) | `observeImports(ruleId, limit)`, read only | WP10c |
| `graph.ownerRoutines`, `graph.ownerPickUpRules`, `graph.ownerFolders` | owner ports (null = hide) | WP10c |
| `graph.ownerBackup: DetailedOwnerBackup?` | typed as the data module's detailed port now (`Ports.backup` too): `saveWithNotes`, `preview`, `restoreWithNotes` | WP10b |
| `graph.screenSwitchedOffSentence: StateFlow<String?>` | D7: why Android switched screen control off (owner-only; device layer's sentence) | WP11b (Tools › Screen) |
| `graph.systemScreens.storageAppName(treeUri): String?` | the name of the app whose storage provider a folder comes from (PackageManager; **call off the main thread**); `TestSystemScreens.storageApps` in tests | WP11b (Tools › Folders note, `OwnerFolders.otherAppStorageNote`) |
| `graph.ownerQuestions` | answering a question (`answer`, `closeIfAbandoned`) | WP11a |
| `graph.ownerConversation` | notes to the team | WP11a |
| `graph.backgroundHealth`, `graph.backgroundScreens`, `graph.storageWatch`, `graph.exitHistory` | as before | WP10a (attention), WP10b (Settings), WP11a (storage refusal) |
| `graph.orchestrator.pause(rootId, reason)` | new: an app pause with its own sentence (the storage pause uses it) | nobody on screen; Resume as before |

TestApp: `app.zone` (UTC), `app.safeStart` (`TestSafeStart`), `app.systemScreens.storageApps`, as well as the stores before.

## 4. Stubs and their packages (every file carries `@Phase6Stub`)

| Package | Files (stub or marked) |
|---|---|
| WP11a | `ui/questions/QuestionScreen.kt`, `ui/questions/QuestionViewModel.kt` (`QuestionViewModel(requestId, questions: OwnerRequests, directory: RegistryDirectory, clock)`, `answer: TextFieldState`); marked functions: `ApprovalsRoute(…, onOpenQuestion)`, `ApprovalRoute(…, onOpenQuestion)`, `ObjectiveRoute(…, onOpenQuestion, onOpenTeam, onOpenDailyLimits, onOpenRoutine)`, `TaskDetailRoute(…, onOpenQuestion)`, `NewObjectiveRoute(…, onOpenDailyLimits, onOpenSettings)` |
| WP11b | marked: `ChatRoute(…, onOpenDailyLimits)`, `ModelEditorRoute(…, onOpenUsage)`, class `ToolsNavigation(…, onOpenRoutines, onOpenPickUpRules, onNewPickUpRule, onOpenQuestion)` |
| WP10a | `ui/activity/UsageScreen.kt` (`UsageTab(viewModel, navigation, onOpenLog, modifier)`), `ui/activity/UsageViewModel.kt` (`UsageViewModel(reports, prices, directory, recentRequests: (Int) -> Flow<List<OwnerRequest>>, clock, zone)`), marked `ActivityRoute(initialTab, initialAgent, initialText, navigation)`; `ui/dashboard/TodayTile.kt`, `GettingStartedCard.kt`, `PhoneAttention.kt`, `SafeStartBanner.kt`, `DashboardExtrasViewModel.kt`; `ui/settings/DailyLimitsSection.kt`, `DailyLimitsScreen.kt`, `DailyLimitsViewModel.kt`, `UsageHistorySection.kt`, `UsageHistoryViewModel.kt` |
| WP9 | `ui/team/TeamScreen.kt` (`TeamRoute`, `TeamNavigation`), `ui/team/TeamViewModel.kt` (`TeamViewModel(objectiveId, tasks, messages, directory, pendingRequests: Flow<List<OwnerRequest>>, monitor, memory: MemoryService, settings: SettingsView, clock)`); marked `NetworkCard(…, onOpenTeam)` |
| WP10b | `ui/settings/BackgroundSection.kt`, `BackgroundSectionViewModel.kt`, `QuestionTimeoutSection.kt`, `QuestionSettingsViewModel.kt`, `MotionSection.kt`, `MotionSettingsViewModel.kt`, `BackupSection.kt`; `ui/backup/BackupSaveScreen.kt`, `BackupRestoreScreen.kt`, `BackupViewModels.kt` (`BackupSaveViewModel(backup: DetailedOwnerBackup)`, `ownPassphrase`; `BackupRestoreViewModel(backup, directory)`, `passphrase`); `ui/recovery/RecoveryScreen.kt` (`RecoveryHost(state, recovery, onTryAgain)`), `ui/recovery/RecoveryViewModel.kt` (`RecoveryViewModel(state, recovery)`, `confirmWord`) |
| WP10c | `ui/routines/RoutinesScreen.kt` (`RoutinesRoute`, `RoutinesNavigation`), `RoutineEditorScreen.kt`, `RoutinesViewModel.kt` (`RoutinesViewModel(routines: OwnerRoutines, waiting: WaitingRoutines, settings: SettingsView, clock, zone)`, `RoutineEditorViewModel(routineId, routines, clock, zone)` with `title`, `objective`), `WaitingRoutinesScreen.kt` (works now: shows WP17's banner); `ui/pickup/PickUpRulesScreen.kt` (`PickUpRulesRoute`, `PickUpNavigation`), `PickUpRuleEditorScreen.kt`, `PickUpViewModels.kt` (`PickUpRulesViewModel(rules, imports: PickUpImports, folders: OwnerFolders, memory, settings: SettingsView, clock)`, `PickUpRuleEditorViewModel(ruleId, folderId, rules, folders, memory)`); `ui/settings/RoutinesSection.kt`, `RoutinesSettingsViewModel.kt` |
| WP16 | `share/ShareReceiverActivity.kt` (closes at once, stores nothing), `share/ShareViewModel.kt` (`ShareViewModel(items: List<SharedItem>, memory: MemoryService, workspace: Workspace, settings: SettingsView)`), `share/ShareScreen.kt` (`ShareScreen(viewModel, onDone)`). The manifest entry is in place (exported; SEND and SEND_MULTIPLE of `text/*`, category DEFAULT; `excludeFromRecents`); `ManifestRulesTest` and `MergedManifestTest` pin it. It must pass `AiControlCenterApp.gate` (`whenOpen(this) { … }`; Recovery when the data does not open) before touching the graph |
| WP8 | `ui/shell/StubScreen.kt` (the stand-in screen; WP8 deletes it with the last stub) |

## 5. File → package table (wave 3)

| Files | Package |
|---|---|
| `ui/tasks/**` (incl. `markdown/`), `ui/approvals/**`, `ui/questions/**` | WP11a |
| `ui/chat/**`, `ui/models/**`, `ui/agents/**`, `ui/memory/graph/MemoryGraphViewModel.kt`, `ui/tools/**` (incl. the Folders warning for another app's storage, D7 in Screen) | WP11b |
| `ui/activity/**` (incl. `ActivityTab.kt`; `ActivityTab`'s two values and `ActivityNavigation`'s fields are FINAL), `ui/dashboard/TodayTile.kt`, `GettingStartedCard.kt`, `PhoneAttention.kt`, `SafeStartBanner.kt`, `DashboardExtrasViewModel.kt`, `Attention.kt`, `DashboardSections.kt`, `ui/settings/DailyLimitsSection.kt`, `DailyLimitsScreen.kt`, `DailyLimitsViewModel.kt`, `UsageHistorySection.kt`, `UsageHistoryViewModel.kt` | WP10a |
| `ui/team/**`, `ui/dashboard/network/**`, `ui/dashboard/NetworkCard.kt`, `DashboardViewModel.kt`, `DashboardUiState.kt`, `EcosystemCard.kt` | WP9 |
| `ui/settings/BackgroundSection*.kt`, `QuestionTimeoutSection.kt`, `QuestionSettingsViewModel.kt`, `MotionSection.kt`, `MotionSettingsViewModel.kt`, `BackupSection.kt`, `SettingsViewModel.kt`, `DeviceSection.kt`, `OrchestrationLimit.kt`; `ui/backup/**`, `ui/recovery/**` | WP10b |
| `ui/routines/**`, `ui/pickup/**`, `ui/settings/RoutinesSection.kt`, `RoutinesSettingsViewModel.kt`, `routines/**` (WP17's app files: the banner, alarms, receivers, notifications) | WP10c |
| `share/**` | WP16 |
| Everything else: `AiControlCenterApp.kt`, `MainActivity.kt`, `DebugStrictMode.kt`, `Phase6Stub.kt`, `di/**` (Ports, ProductionPorts, AppGraph, AppStartup, LocalAppGraph, every `di/wiring/*`), `health/**`, `work/**`, `scripts/**`, `operator/**`, `usage/**`, `ui/shell/**`, `ui/settings/SettingsScreen.kt`, `ui/dashboard/DashboardScreen.kt`, `ui/components/**`, `ui/theme/**`, `ui/icons/**` (new `val AppIcons.X` extensions in your own files are fine), `ui/memory/**` (but the graph view model), `ui/workspace/**`; the manifest, `build.gradle.kts`, resources; `:core`, `:testkit`, `:framework`, `:memory`, `:data`, `:device`, `:providers*` | WP8 |

Tests:

| Test files | Package |
|---|---|
| `ui/tasks/**`, `ui/approvals/**`, `ui/questions/**`; `journeys/TasksJourneysTest.kt`, `journeys/ApprovalJourneysTest.kt`, new `QuestionJourneyTest`, `NotesJourneyTest`; `screenshots/TasksScreenshotTest.kt`, `ApprovalScreenshotTest.kt` | WP11a |
| `ui/chat/**`, `ui/models/**`, `ui/agents/**`, `ui/memory/graph/**`, `ui/tools/**`; `journeys/ModelsAndChatJourneysTest.kt`, `ToolsJourneysTest.kt`, `MemoryJourneysTest.kt`, `PlaceholderLabelsTest.kt` (Vision only), new `ChatCutOffJourneyTest`; `screenshots/ModelsAndChatScreenshotTest.kt`, `ToolsScreenshotTest.kt`, `MemoryScreenshotTest.kt` | WP11b |
| `ui/activity/**`, `ui/dashboard/` tests of WP10a's files, `ui/settings/` tests of WP10a's files; new `UsageJourneyTest`, `DailyLimitJourneyTest`, `GettingStartedJourneyTest`; new screenshot files | WP10a |
| `ui/team/**`, `ui/dashboard/DashboardViewModelTest.kt`, `DashboardOrchestrationTest.kt`, `NetworkLayoutTest.kt`, `NetworkTrafficTest.kt` | WP9 |
| `ui/settings/SettingsViewModelTest.kt`, `ui/backup/**`, `ui/recovery/**`, `health/RecoveryPathTest.kt`; new `BackgroundCheckJourneyTest`, `RecoveryJourneyTest`, `BackupJourneyTest` | WP10b |
| `ui/routines/**`, `ui/pickup/**`, `routines/**` | WP10c |
| `share/**` | WP16 |
| Everything else (`testing/**` incl. `TestApp.kt` and `AppRobot.kt`, `di/**`, `work/**`, `scripts/**`, `operator/**`, `usage/**`, `ui/shell/**`, `ui/components/**`, `ManifestRulesTest`, `MergedManifestTest`, `Phase6StubGateTest`, `MainActivityTest`, `journeys/KeyJourneysTest.kt`, `screenshots/ScreenshotTest.kt`, `LauncherIconTest`, `docs/**`) | WP8 |

## 6. Hand-off items for the 3b packages (from the wave-2 hand-offs; full texts there)

- **WP11a:** question screen of rule 13 (handoff-wp5.md §3; `CODE_ASK_AGAIN` → "Send it anyway", `KEY_REFUSED` no second
  chance, `closeIfAbandoned` on open; the new `AnswerCheck` and approval words, handoff-secfix.md "Wave 3 notes" and
  "Second review"); `tools_open` shown as `ToolMenu.TITLE`; waiting tasks (`QUEUED` + `retryAt`, `ProviderWaits.WAITING`)
  and "Was waiting: …" when stopped (w2afix INT-3); planning waiting; paused by the limit (`DailyLimits.PAUSED_TEXT`,
  Resume = `orchestrator.resume`, link to Daily limits); notes composer (`graph.ownerConversation.addNote`, refusals as
  they are); "(same model as the author)"; "Started by routine '<title>'" and "Screens: not allowed"; New objective:
  `graph.storageWatch.check()` at CRITICAL refuses with "The phone is almost full. Free some room before starting an
  objective.", `MaintenanceGate.RESTORING`, the daily-limit line with `graph.todayUsage`, the Director reasoning warning
  (D27), the background warning (A3, link to Settings); D9 "What happened" on the Approval screen; the "Waiting for you"
  list title; questions that ran out of time show `OwnerQuestions.NO_ANSWER_PREFIX`'s line; P1/P2/P7 (one
  `observeObjectiveRows(50)` plus "Show older", summaries, the ticker in its own flow, `flowOn(Default)`, `conflate()`,
  MarkdownView off the main thread).
- **WP11b:** `ChatPresentation.sameModel` → `ModelNames.sameModel` (D29); `tools_open` as `ToolMenu.TITLE`; cut-off notice
  with "Send again" (`DefaultAgentChat.cutOff(name)` entries → `agentChat.sendAgain(id)`, `NOTHING_TO_SEND_AGAIN`), newest 50
  entries with "Show earlier" (P5), cost line per chat, the limit sentence alone (link `onOpenDailyLimits`); script answers
  shown as they are; model editor price fields ("you enter these from your provider's price page; the app never looks
  them up"), Reasoning and Coding lose "(test)" with one line each (`PlaceholderLabelsTest`: Vision only), "Speed and
  reliability" card, the cheaper-helper suggestion (Tier B); agent editor: tiers in the model picker, "These tools add
  about N to every request" (`ToolMenu.estimatedTokens`) and the many-tools sentence above `ToolMenu.THRESHOLD_TOKENS`;
  presets' cost lines; D7 notice in Tools › Screen (`graph.screenSwitchedOffSentence`, else `SwitchedOffWatch.STOPPED`,
  button `ScreenOperatorLayer.accessibilitySettingsIntent()` via `graph.systemScreens.accessibilitySettings()`); Tools ›
  Folders: `OwnerFolders.fromOtherAppStorage(folder)` → `OwnerFolders.otherAppStorageNote(appName, folder.readOnly)` with
  `graph.systemScreens.storageAppName(folder.treeUri)` read off the main thread, recomputed when read-only changes; when the
  owner makes a folder writable, say that its pick-up rule was switched off; a "Pick up new files" entry
  (`ToolsNavigation.onNewPickUpRule`) and entries to Routines and Folder pick-up (`onOpenRoutines`, `onOpenPickUpRules`)
  where they fit (hidden when `graph.ownerRoutines`/`ownerPickUpRules` is null); D35 counts, D37 graph export in local-only
  mode with "The app you choose may send it off the phone" (rule 21), D38, D40.
- **WP10a:** Usage and cost tab (`DailyUse.of` rules, `UsageTotals` speed and reliability, `workUsage` + `CostEstimate`,
  "estimated from the prices you entered; your provider's bill is what counts", stopped 429-only calls listed among
  rate-limited ones); Log paging (D41, `ActivityLog.observePage`) and search (Tier B, `ActivityFilter.text`); Daily limits
  (one line "Limits stop work, not cost already spent"); Usage history (keeps today); Today tile; Getting started (D47,
  `onboardingCompleted`); phone attention (background, storage LOW/CRITICAL, objectives paused by the limit,
  `orchestrator.timedWaits.nextDueAt` for "trying again at"; optionally routines waiting to start); safe-start banner
  (`reason.ownerText` + "Interrupted work waits for you", "Back to normal" → `leave()`).
- **Part 2 (WP9, WP10b, WP10c, WP16):** as handoff-wave2b-merge.md §3 and handoff-wave2a-merge.md §3 list for them, plus
  `RestoreChecks` stays in step with the editors (WP10b reads the agreement test if a validator changes).

## 7. After the part-1 merge (WP8, integrator)

WP11a, WP11b and WP10a are merged and their hand-offs applied; what was applied and what is left (with owners) is in
`scratchpad/phase6/handoff-wave3a-merge.md`, which part 2's packages read with this page. Everything above stays binding
for WP9, WP10b, WP10c and WP16. Additions they can use: `LocalCalmMotion` (`ui/theme/Motion.kt`, Calm from the owner's
setting or Android's animation switch); `LocalScreenWork` / `LocalUsageWork` now carry `Dispatchers.Default` in the app
(`AppScreenWork`); framework `TaskStatusLines`; `UsageReports.byAgentAndModel`; `DashboardNavigation.onOpenModel` and
`onTrySimulator`. `Phase6StubGateTest.STILL_BUILDING` is now WP8, WP9, WP10b, WP10c, WP16.

## 8. After the part-2 merge (WP8, integrator)

WP10b, WP10c, WP9 and WP16 are merged and their hand-offs applied; what was applied and what is left (with owners) is in
`scratchpad/phase6/handoff-wave3b-merge.md`. The final stub gate passes: `STILL_BUILDING` is empty, no `@Phase6Stub` is
left and `ui/shell/StubScreen.kt` is gone. Changes to the tables above: `SettingsRoute` is a data class with
`anchor: SettingsAnchor?` (BACKGROUND, ROUTINES) and `SettingsRoute(navigation, scrollTo)` scrolls to it; prices are in one
currency, US$ (`AppGraph.modelPrices` is the counted view, `AppGraph.enteredModelPrices` the prices as entered, read only
by the model editor and Usage and cost; `TodayUse` has no currency of its own). From wave 4 on, every file of the app is
WP8's again unless a wave-4 package says otherwise.
