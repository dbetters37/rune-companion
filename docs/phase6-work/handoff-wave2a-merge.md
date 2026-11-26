# Hand-off from the wave-2a merge (WP4, WP15, WP7, WP5)

Branch `claude/ai-control-center-architecture-wjdfww` in `/home/user/rune-companion`, from 90e1d70. Not pushed. The
builders' worktrees (`/home/user/p6-wp4`, `p6-wp15`, `p6-wp7`, `p6-wp5`) are left in place; their branches are fully merged.

## 1. Merges

| Commit | What | Conflicts | Whole suite after it |
|---|---|---|---|
| 4d7ec10 | merge p6/wp4 (c15f9da), usage, cost, daily limits, provider trouble | none | 2,850, 0 failed |
| 31ed963 | merge p6/wp15 (11ca914), lean requests | none | 2,874, 0 failed |
| 899826d | merge p6/wp7 (ba668cf), orchestration, teamwork, chat | none | 2,926, 0 failed |
| a034977 | merge p6/wp5 (092e452), broker, questions, phone-operator fixes | framework `operator/OperatorDependencyTest.agentFacingRoots()`: WP4 added `DefaultProviderRechecks`, WP5 the question's agent side (`OwnerQuestions`, `AskOwnerTool`, `OwnerAttentionGate`, `OwnerRequestWait`, `AnsweredQuestions`, `OwnerWaitLedger`) on the same line; both kept | 2,980, 0 failed |

No merge broke the build or a test, so there is no "fix after merging" commit. The final numbers (forced re-run) are in
section 4.

## 2. Hand-off items applied by the merger (each with its test)

| Commit | Item | From | Test |
|---|---|---|---|
| 7a46331 | `PromptedToolsFormat.quote` also runs `DataBlock.neutralise`: on the prompted-tools path a tool result cannot carry the `owner_note` tag or the words "note from the owner" (rule 24) | WP7 §3.1 (WP4's file) | framework `llm/PromptedToolsOwnerNoteTest` (2) |
| 2851f92 | Usage source of a task's call that also carries the task transcript's conversation id: OBJECTIVE, filed under the task and the objective, never CHAT. The journal already decided it this way (task/objective first); now pinned | WP15 §3.3 (WP4 at merge) | framework `llm/UsageSourceTest` (2) |
| e1c96dd | End-to-end check that WP4's real limiter and WP7's pause meet: agents on a model off the phone, limit 2 answers; the third call is refused before anything is sent, the objective is PAUSED with `DailyLimits.PAUSED_TEXT`, no attempt is used, the limit's sentence is logged; after the limit is raised Resume finishes it | WP4 §4 (note to WP7), merge check | framework `orchestration/DailyLimitGatewayPauseTest` (1) |
| 99dbb4a | Both app walks (`di/OperatorDependencyTest.roots()`, `di/ProductionGraphDependencyTest`) also walk `graph.providerRechecks` (DefaultProviderRechecks) and `graph.operatorAssembly.ownerQuestions` (OwnerQuestions, the agent side); green in the test app and in production | WP4 §2 row 1, WP5 §2 row 4, secfix "Wave 3 notes" | the two walks themselves |

Checked at merge and found already done (nothing to apply):
- WP15 §3.4 (screen tools assessed before the routine refusal): WP5 built `EarlyRefusal`; a routine's screen call is refused before `assess`.
- WP15 §3.5 (optional, no screen family when `screenActionsAllowed` is false): WP7 built it (`StepRunner.run(screens = …)`, `ScreenOfferTest`, which accepts WP15's menu).
- WP15 §5 / WP5 §2 (`ScreenSessions` passes `holdSession`/`releaseSession` on to the device): WP5 built it (`ScreenSessionHoldsTest`).
- WP4 §4 to WP5 (`ownerKeyCheck` only to the owner's answer path): `OperatorAssembly(…, modelsAssembly.ownerKeyCheck)` hands it to `DefaultOwnerRequests` only.
- WP7 §3.2 (b) source scan of rule 24: WP5 makes the owner's ANSWER only in `OwnerQuestions.kt`, which the scan allows; GUIDANCE only in `OwnerNotes.kt` and `ObjectiveJournal.kt`. `OwnerNotesTest` is green after the merge.
- WP7 §3.2 (c) (frame the owner's answer with `DataBlock.frame`): WP5 does ("The owner answered, in their own words:" + `DataBlock.frame(words, "The owner's answer")`).
- WP7 §3.2 (d) (no question while the objective is PAUSED): `OwnerQuestions` requires a RUNNING objective.
- WP7's work-brief line for `team_ask_owner` and WP5's tool use the same id (`TeamTools.ASK_OWNER_ID` = `team.ask_owner`, function `team_ask_owner`).

## 3. Items not applied, with their owner

### Wave 2b

| Owner | Item | Source |
|---|---|---|
| WP2 | `app/work/UpkeepGraph.kt`: run `UsageRetention(ports.usageHousekeeping, ports.settings, ports.clock, zone).prune()` as the usage step (never `UsageHousekeeping` directly). | WP4 §2, secfix |
| WP2 | Wake hold and wake alarm: read both `AppGraph.providerRechecks.timedWaits.nextDueAt` (wall-clock time of the next re-check) and `orchestrator.timedWaits.nextDueAt` (tasks, planning, synthesis, delegated work waiting for a provider); schedule the alarm for the earlier one; `WakeReceiver` calls `wake()` on both. An objective waiting for a provider stays RUNNING (its waiting tasks are QUEUED with `retryAt`), so the service keeps running. | WP4 §4, WP7 §3.3 |
| WP2 | `ResultNotifications`: an objective paused by today's limit has status PAUSED and `statusDetail == DailyLimits.PAUSED_TEXT` exactly (a STATUS message from the system says the same). | WP7 §3.3 |
| WP2 | `app/work/ApprovalNotifications.kt`: a QUESTION already gets today's "Question waiting" notification ("Researcher: A question from Researcher", app-written, safe). Use the planned wording "A question from Researcher. Open the app to read it.", no actions, private with a public version; never `request.question`, `options` or the task's `statusDetail`. | WP5 §2 (rule 4) |
| WP2 | `app/work/ScreenSessionNotifications.kt`: show `ScreenSession.waitingForOwner` as "<agent> waits for your answer to act in <app> · Stop" (Stop stays the only action). | WP5 §2 (D4, rule 12) |
| WP2 | A question's expiry is a coroutine time-out in this process, not a `TimedWaits` entry (may fire late after Doze; the owner's answer wakes it); questions are not withdrawn by `OperatorTaskHooks.taskEnded`. Nothing to change; keep in mind for the wake lock. | WP5 §3 |
| WP3 | `BackupRecords.entryForSave(entry) { name -> title }`: map `ToolMenu.FUNCTION_NAME` ("tools_open") to `ToolMenu.TITLE` ("Open more tools"). Requests, questions and answers are never in the file (rule 8). | WP15 §3.2, WP5 §3 |
| WP17 | A routine objective that meets the daily limit is PAUSED (not failed): "pause after 3 failures" must not count it. Start runs with `routine.objectiveOptions()`. The runner may check today's limits before a run with `DailyUse.of(usageReports.byModel(UsageRange.day(now, zone)), prices)` against `settings.dailyLimits`. A routine's screen tools are refused before `assess` and not offered. | WP7 §4, WP4 §4, WP5 §3 |
| WP6 | Nothing from wave 2a. WP8 adds the script host as a root of both app walks once WP6 wires it. | secfix |

### Wave 3

| Owner | Item | Source |
|---|---|---|
| WP8 | App walk roots still to add: the script host (WP6) and `UpkeepJobService` (WP2), as the plan's WP8 section says. `providerRechecks` and `OwnerQuestions` are done (section 2). | plan WP8, secfix |
| WP8 (optional) | One owner zone for "today": pass a single zone to `ModelsAssembly(zone = …)` (and `TeamAssembly`'s orchestrator zone, today `ZoneId.systemDefault()`) from `AppGraph`/`TestApp`, e.g. a `Ports.zone`. Production agrees already (system zone everywhere); `TestApp`'s `InMemoryUsageStore` uses UTC while the limiter uses the machine zone (UTC in this container), which matters only to an app test that records calls around midnight on a machine in another zone. | WP4 §2 |
| WP8 (optional) | Pass `ScreenOperatorLayer.switchedOffSentence` (owner-only) through `Ports`/`ProductionPorts`/`AppGraph` for the D7 notice on the Screen control screen (`AppGraph.screenControl.switchedOffByAndroid` already reports the boolean). | WP5 §2 (D7) |
| WP8 (optional, framework owner in wave 3) | Write `OwnerQuestions`' QUESTION/ANSWER messages through `ObjectiveJournal.send` (WP7's journal now logs an ANSWER as "You answered a question", never its text). Today `OwnerQuestions.send` stores the same messages and logs without the owner's words, so this is tidiness, not safety. If done, the rule-24 source scan in `OwnerNotesTest` must keep allowing ANSWER only where it is made. | WP7 §3.2 (a), WP5 deviation 1 |
| WP8 (optional) | Shorter descriptions for `permissions.request` (401 estimated tokens, framework/operator/PermissionRequestTool.kt) and the memory tools (518 for two, framework/memory/MemoryTools.kt), carried by every agent on every call (owner request #43). Re-check `LeanRequestBudgetTest` and `AgentsWiringTest` afterwards. | WP15 §3.6, WP5 §6.9 |
| WP8 / WP12 | If C1 (questions, Tier B) is cut after wave 4 round 2, force `agentQuestionsEnabled` off and hide its entry points. If C2 (notes) is cut, `TeamAssembly.ownerConversation` becomes `OwnerConversation.Unavailable`. | WP5 open item 1, WP7 §1 item 3 |
| WP11a | Question screen (rule 13): API and texts in handoff-wp5.md §3 (title "A question from <agent>", "This is not a permission…", `AppTextField(state = …)` with a paused-clock typing test, "Send", "Decide yourself", "Send it anyway" on `CODE_ASK_AGAIN`, `closeIfAbandoned`). Until it exists an unanswered question runs out after 20 minutes and the agent decides itself. | WP5 §3 |
| WP11a | Task transcript: show `tools_open` as `ToolMenu.TITLE` ("Open more tools"), e.g. "Opened more tools: workspace". Waiting tasks (`QUEUED` with `retryAt`, `statusDetail` starting with `ProviderWaits.WAITING`) as waiting; planning waiting (objective RUNNING with `retryAt`); paused by the limit (`DailyLimits.PAUSED_TEXT`, Resume = `orchestrator.resume`); notes composer (`graph.ownerConversation.addNote(rootId, toTaskId?, text)`, refusals shown as they are); New objective: `startObjective` throws `IllegalStateException(MaintenanceGate.RESTORING)` during a restore; daily-limit line with `DailyUse.of(...)` and `settings.dailyLimits`. | WP15 §3.1, WP7 §4, WP4 §4 |
| WP11b | Chat: show `tools_open` as `ToolMenu.TITLE` (`ChatPresentation.toolCallUi`, `LiveTurn.toUi`, via `ChatViewModel.toolInfo`); replace `ChatPresentation.sameModel` with `ModelNames.sameModel` (D29); "Send again" for a cut-off reply (`DefaultAgentChat.cutOff(name)` error entry → `agentChat.sendAgain(id)`, `NOTHING_TO_SEND_AGAIN`); a limit refusal shows the limit's sentence alone. Agent editor: "These tools add about N to every request" with `ToolMenu.estimatedTokens(...)`, and the many-tools sentence above `ToolMenu.THRESHOLD_TOKENS`. Models: preset descriptions have a cost line. Screen control: the D7 notice with `ScreenOperatorLayer.accessibilitySettingsIntent()`. | WP15 §3.1/§5, WP4 §2/§4, WP7 §4, WP5 §3 |
| WP9 | Team view: `tools_open` as `ToolMenu.TITLE`; waiting tasks and notes (GUIDANCE from "You"); memory shares now go to `Participant.Space(spaceId)` (older ones `System`); "who waits for you" = `OwnerRequests.pending` (PERMISSION, APPROVAL, QUESTION); a task waiting for a question has `statusDetail` "Waiting for the owner's answer: <question…>" (agent text: quoted, never in a notification); `registryDirectory.observe()` is one shared flow. | WP15 §3.1, WP7 §4, WP5 §3, WP4 §4 |
| WP10 | Usage tab, Daily limits, Today tile with `DailyUse.of(...)` (counts exactly what the limiter counts: off-phone calls that sent a request); Speed and reliability from `UsageTotals`; cost per work item with `usageReports.workUsage` + `CostEstimate`; provider "Not answering since 10:42"; Dashboard: objectives paused by the limit, `orchestrator.timedWaits.nextDueAt` for a "trying again at" line; Settings: "Questions from agents" (`agentQuestionsEnabled`) and "Question time-out" (5..240, default 20); the lean-request numbers (WP15 §2) as the owner-visible cost story. | WP4 §4, WP7 §4, WP5 §3, WP15 §5 |

### Waves 4-6

| Owner | Item | Source |
|---|---|---|
| WP12 | Attack list: `tools_open` (foreign family, forged history, repeated opening; rule 2 via `ToolMenuRunTest`); questions (concurrent asks, near-identical questions, approval words in options, keys and codes in answers, an off-phone call right after an answer and through a delegated task, a hold left open, the shell slot); owner notes. Also look at: (1) native tool results are passed to the model unframed, so `owner_note` text in a native tool result is not neutralised (only the prompted-tools path and `DataBlock` are; the brief's fixed text lists the real note ids, and notes grant nothing); (2) `DataBlock.neutralise` does not touch the heading "## Notes from the owner" (plural), which tool output on the prompted-tools path can still carry. | WP15 §5, WP5 §3, merge |
| WP13 (§10, emulator) | Operator's first request with exactly 3 tools (`clock_now`, `memory_search`, `tools_open`), about 710 estimated tokens of definitions; flight mode on and off while a cloud provider is OFFLINE gives one "Provider checked" within seconds; D5 (screen call right after opening an app), D6 (permission dialog in front), D7 (force-stop notice), screen off refuses screen actions, a question answered once WP11a's screen exists. | WP15 §4, WP4 §3, WP5 §4 |
| WP14 | Docs lists in handoff-wp4.md §5, handoff-wp15.md §5, handoff-wp7.md §3.5, handoff-wp5.md §5; plus: rule 24 now also covers tool output on the prompted-tools path (this merge). | all four |

## 4. Device tests to run

None: no package in wave 2a and no merge commit added an instrumented test.

## 5. Final checks

Head after the merge: `99dbb4a` (four merge commits, four hand-off commits; no fix commit was needed).

- Whole suite, forced real run (`./tools/gw --offline test --continue --rerun`, then `:data:testDebugUnitTest --rerun`,
  because `--rerun` on the `test` selector did not reach the data module's unit-test task, which was up to date):
  **2,985 tests, 0 failed**: app 491, core 164, data 430, device 352, framework 954, memory 107, providers 227,
  providers-anthropic 89, testkit 171. At 90e1d70 it was 2,775: WP4 +75, WP15 +24, WP7 +52, WP5 +54, merger +5
  (framework: PromptedToolsOwnerNoteTest 2, UsageSourceTest 2, DailyLimitGatewayPauseTest 1; the app walks gained roots,
  not tests).
- `./tools/gw --offline :app:assembleDebug`: BUILD SUCCESSFUL.
- Both app walks (`OperatorDependencyTest` with the new roots, `ProductionGraphDependencyTest`), the framework walk
  (`OperatorDependencyTest`, roots from WP4 and WP5) and `AssemblyMoveTest` are green.
