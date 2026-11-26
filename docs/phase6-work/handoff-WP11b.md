# Hand-off from WP11b (wave 3, part 1: chat, models, agents, memory graph, tools)

Branch `p6/wp11b` (worktree `/home/user/p6-wp11b`), from d025d68. WP11b changed only files it owns (wave3-ownership.md
§5): `ui/chat/**`, `ui/models/**`, `ui/agents/**`, `ui/memory/graph/MemoryGraphViewModel.kt`, `ui/tools/**` and their
tests (`ui/chat/**`, `ui/models/**`, `ui/agents/**`, `ui/memory/graph/**`, `ui/tools/**`, `journeys/PlaceholderLabelsTest.kt`,
new `journeys/ChatCutOffJourneyTest.kt`, new `screenshots/CostAndChatScreenshotTest.kt`). All three `@Phase6Stub("WP11b")`
marks are gone (`ChatRoute`, `ModelEditorRoute`, `ToolsNavigation`); their FINAL signatures are kept. Head commit and test
numbers: end of this page.

## 1. What WP11b built (by the plan's items)

| Item | What | Where |
|---|---|---|
| Cost line per chat (goal 1, rule 29) | Under the transcript: "This chat so far: 120,000 tokens, about US$0.60." plus "Tokens are the pieces of words…" and `CostEstimate.DISCLAIMER`; without a price "…Enter the model's prices in Models to see what this chat costs."; money added per currency, never across; under half a cent says "less than US$0.01"; models without a price add tokens only ("…for the models with a price"). From `usageReports.observeWorkUsage(CHAT, id)` and `modelPrices.observeAll()`. | ui/chat/ChatCost.kt, ChatViewModel.kt, ChatScreen.kt (`CostLine`) |
| Limit sentence (rule 6) | A refusal for today's limit is stored by the chat as the limit's own sentence; the transcript shows it as "Today's limit is reached" with the sentence alone, a "Daily limits" button (`onOpenDailyLimits`) and "Send again". While today's limit is reached (`graph.todayUsage`) and the chat's text would leave the phone, the composer shows the same sentence with "Daily limits" (sending stays possible: a local model, or a raised limit). | ChatPresentation.kt (`NoticeKind.LIMIT`, `isLimitNotice`), ChatScreen.kt (`LimitLine`, `NoticeActions`) |
| Cut-off notice, "Send again" (R3) | `DefaultAgentChat.cutOff(name)` entries are `NoticeKind.APP_STOPPED` ("The app stopped"); under the last notice of any kind (failed, cut off, app stopped, limit), when the agent can answer, "Send again" → `agentChat.sendAgain(id)`; `NOTHING_TO_SEND_AGAIN` and `MaintenanceGate.RESTORING` (stored by nobody) are said once in the snackbar. | ChatPresentation.kt, ChatViewModel.kt (`sendAgain`, `canSendAgain`), TranscriptViews.kt |
| Newest 50 entries, "Show earlier" (P5, D31) | The chat reads its entries through `ConversationPages` (the store's `observeLatestSeq`/`entriesBefore`/`entriesAfter`, one seq query each): the newest 50, moved back to the start of their first turn (by at most a page) so a reply comes with the message that asked for it; "Show earlier messages" reads the page before; tool results whose call lies before the part are left out. The transcript is no longer rebuilt for every key typed into the composer. Auto-scroll follows only the end of the chat, so earlier messages come in above. | ui/chat/ConversationPages.kt, ChatViewModel.kt, ChatScreen.kt |
| D29 | `ModelNames.sameModel` replaces `ChatPresentation.sameModel` ("grok-4-latest" answered as "grok-4-0709" is not flagged; "gpt-4o" answered as "gpt-4o-mini" is). | ChatPresentation.kt |
| `tools_open` | Shown as `ToolMenu.TITLE` ("Open more tools") in the transcript and the live line ("Running tool: Open more tools"). Done inside `transcriptOf`/`toolInfoOf`, so WP11a's task transcript (`TaskDetailViewModel` calls `transcriptOf`) gets it too. | ChatPresentation.kt |
| Tool results as written | The owner always sees the stored text (scripts' fallback notes, words like `owner_note`, `&#38;`); never the model's neutralised copy. Pinned by a test. | ChatPresentationTest |
| Model editor price (rule 29) | Section "Price": "You enter these from your provider's price page; the app never looks them up.", the token explanation, Input / Output / Cached input per million tokens (amounts with a dot or a comma, up to six decimals, `amountOnly` filter), Currency (default US$, ≤ 8 characters); "Save price" (separate from the description, so a listed model stays updated by its provider), "Undo changes", "Remove price"; the typical cost line; without a price: "this model's tokens are counted but no cost is shown, and the daily money limit does not count it". `OwnerModelPrices.set`/`remove`; refusals and `ModelPrice.check` sentences as they are. Not shown for the simulator. Leaving with an unsaved price asks first. | ui/models/ModelPriceViewModelParts.kt (`ModelPriceFields`, parsing), ModelEditorViewModel.kt, ModelEditorScreen.kt |
| Reasoning and Coding made real | No "(test)"; one line each ("Agents on a stronger model are picked first for planning, comparing and deciding, and the New objective form warns when the Director's model is Basic." / "…for tasks about code, scripts and programs."). Vision keeps "(test)". `PlaceholderLabelsTest`: Vision is the only `testLabel` call in the source (scan) and on screen. | ModelEditorScreen.kt, journeys/PlaceholderLabelsTest.kt |
| "Speed and reliability" | Last 30 days of this model in words: answers from calls (failed, stopped, not sent), average whole answer and first words (5+ answers only, else "Average times are shown once it has given 5 answers."), failures by kind in words, "asked the app to slow down N times", answers without token counts; "See usage and cost" → `onOpenUsage`. | ModelPriceViewModelParts.kt (`speedOf`), ModelEditorScreen.kt (`SpeedCard`) |
| Cheaper model for helpers (Tier B) | Among enabled, tool-calling, priced (same currency), runnable models (provider on and listed, local-only respected, not the simulator) the cheapest for typical work (three parts read, one written). On its editor: "Cheaper model for helpers" naming the helpers on dearer priced models and "Use for helpers…" (the existing "Use for agents…" chooser with only those helpers ticked; the Director keeps its model unless ticked). On a dearer model's editor: "A cheaper model can use tools" with how much less. Helpers on a model without a price are left alone (it may be free). | ModelPriceViewModelParts.kt (`cheaperHelper`), ModelEditorViewModel.kt (`openUseForHelpers`) |
| Preset cost lines | The gallery and the "Add a provider" form show a preset's second description line apart, with the usage icon ("What it costs: …" for TalkBack). | PresetGalleryScreen.kt (`presetDescription`, `PresetCostLine`), ProviderFormViewModel.kt, ProviderFormScreen.kt |
| Agent editor: tiers, tool token line | The model picker (and the chosen model) shows "Reasoning: Strong · Coding: Good" (rated ones only). The Tools section says "These tools add about N tokens to every request this agent makes." or, above `ToolMenu.THRESHOLD_TOKENS`, "These tools would add about N tokens to every request. That is many, so they are offered in groups: each request carries only the groups the agent opens, and opening one may take one more step." (`ToolMenu.estimatedTokens` over `ToolMenu.definition`), with the token explanation. | AgentEditorViewModel.kt (`tiersOf`, `toolCostLine`), ModelPicker.kt, AgentEditorSections.kt |
| D38 | Saving an existing agent re-reads it and writes only the fields the owner changed over the stored profile; spaces ticked here are added only while they exist, unticked ones removed, a space deleted meanwhile stays gone (`AgentEditorViewModel.mergeEdit`). A new agent keeps only spaces that exist (or came with its template). | AgentEditorViewModel.kt |
| D40 | The "New agent" button shrinks to its icon once the list is scrolled (`ExtendedFloatingActionButton(expanded)`); the list keeps 96 dp under its end. | AgentsScreen.kt |
| D35 (agents) | The agent page counts private memories with the store's COUNT query (`MemoryRepository.observeCount`), not by reading them. | AgentDetailViewModel.kt (`memoryCount`), AgentDetailScreen.kt |
| D37 (rule 21) | The graph export is the owner's own act through the share sheet, so it is allowed in local-only mode; the snackbar says "Local-only mode keeps agents' work on this phone; this export is yours. The app you choose may send it off the phone."; the egress log adds "Local-only mode was on; this was your own export."; `GraphUiState.exportNote` carries the line for the tab (§2 item 1). | MemoryGraphViewModel.kt |
| D7 notice | Tools › Screen shows "Screen control was switched off" with `graph.screenSwitchedOffSentence` (else the generic stopped sentence) and "Open accessibility settings" (`graph.systemScreens.accessibilitySettings()`), while `screenControl.switchedOffByAndroid` is true. | ScreenControlViewModel.kt, ScreenControlTab.kt, ToolsSection.kt |
| Tools › Folders | A folder from another app's storage provider shows `OwnerFolders.otherAppStorageNote(appName, readOnly)` (warning style, recomputed when read-only changes); the app name comes from `graph.systemScreens.storageAppName(treeUri)` on `Dispatchers.IO`, once per folder (null → "another app"). Making a folder writable says "Its folder pick-up rule was switched off: pick-up reads only folders agents cannot change." (or "N … rules"), seen by comparing the rules before and after. A read-only folder without a rule offers "Pick up new files" (`onNewPickUpRule(folderId)`); the tab offers "Folder pick-up rules" (`onOpenPickUpRules`); both only while `graph.ownerPickUpRules` is not null. | FoldersViewModel.kt, FoldersTab.kt, ToolsSection.kt |
| Routines and questions in Tools | "Routines: objectives that start on a schedule" on the All tools tab (`onOpenRoutines`, only while `graph.ownerRoutines` is not null). In History a QUESTION opens `onOpenQuestion` (its own screen, rule 13), the kind filter says "Questions" (it said "Approvals"), and the intro mentions questions. | CatalogTab.kt, RequestsTab.kt, ToolsSection.kt |

## 2. Changes needed in files WP11b does not own

| Where | What | Why | Who |
|---|---|---|---|
| ui/memory/graph/MemoryGraphTab.kt (`ExportRow`) | Show `state.exportNote` ("The app you choose may send it off the phone.") as a small line under "Export graph". Today the line appears only as the snackbar of a local-only export. | rule 21, D37 | WP8 |
| ui/memory/MemoryScreen.kt / core `MemoryService` | D35 for the graph: `MemoryGraphViewModel` still observes the owner's whole memory list (to rebuild on any change and label scopes). A cheap change signal from the store (e.g. a core `observeLatestChange()` or Room's table invalidation as a `Flow<Unit>`) would let it stop; the view model already labels nodes from the snapshot when a record is missing. Not done: no such port exists, and counts alone would miss edits. | D35 | WP8 (or wave 4) |
| device `screen/SwitchedOffWatch` | Its `STOPPED` sentence is `internal`; `ScreenControlViewModel.SWITCHED_OFF` repeats the same words for when the device layer gives no sentence. Make it public, or keep both in step. | D7 | WP8 |
| core `DailyLimits.sentence` | "Today's limit of 1 model answers" (plural with 1). Cosmetic; seen in the screenshots with a limit of 1. | plain words | WP8 (core fixes) |
| An app-level "Calm" composition local | None exists yet (WP10b's `MotionSection` stores `calmMotion`). The D40 button's expand/shrink and the chat's auto-scroll use Material's own motion (`MotionDurationScale` follows Android's animator setting); once WP8/WP10b provide `LocalCalm`, the button can switch instantly under Calm. | Calm, rule 17 | WP8 / WP10b |

Nothing else outside WP11b's files was needed: every route, slot and navigation callback was already wired by WP8
(`ChatRoute(onOpenDailyLimits)`, `ModelEditorRoute(onOpenUsage)`, `ToolsNavigation(onOpenRoutines, onOpenPickUpRules,
onNewPickUpRule, onOpenQuestion)`). `ChatRoute`, `AgentDetailRoute` read `graph.ports.conversations` /
`graph.ports.memories` read-only (as `MemoryDetailScreen` already did); the view models get read-only interfaces or
functions (`ConversationPages`, `memoryCount`), never a writer.

## 3. Hand-off items addressed to WP11b: done and not done

Done: every row of wave3-ownership.md §6 "WP11b" (D29; `tools_open`; cut-off notice with "Send again" and
`NOTHING_TO_SEND_AGAIN`; newest 50 with "Show earlier"; cost line; limit sentence with the Daily limits link; script answers
as they are; price fields with the line; Reasoning and Coding without "(test)", one line each, `PlaceholderLabelsTest` Vision
only; "Speed and reliability"; cheaper-helper suggestion; agent editor tiers, the token line and the many-tools sentence;
preset cost lines; D7 with the accessibility button; Folders other-app note via `storageAppName` off the main thread,
recomputed on read-only; "pick-up rule switched off"; "Pick up new files", Routines and Folder pick-up entries hidden
when null; D35 (agent page), D37, D38, D40); handoff-wp4.md §2 row 4 and §4 WP11b; handoff-wp15.md §3.1 and §5 WP11b;
handoff-wp7.md §4 WP11b; handoff-wp5.md §3 WP11b (D7); handoff-wave2a-merge.md §3 WP11b; handoff-w2afix.md "Residual fixes"
(tool results as written); handoff-secfix.md "Second review" WP10 note on the Folders screen ("its pick-up rule was switched
off", now WP11b's per wave3-ownership); handoff-w2bfix.md's Folders table and its tests (writable with and without a name,
read-only, phone storage and the Downloads entry without a note); handoff-wave2b-merge.md §3 WP11b (WP2's chat service and
Stop need nothing on the chat screen; WP6: the scripts family says "sealed sandbox", no process claim, so no caveat was
needed).

Not done:
- D35 for the memory graph (§2 row 2): needs a change signal WP11b cannot add.
- wave2b-minors-left.md (optional): the Folders screen could suggest sharing a Downloads-entry folder through the phone's
  storage entry instead. Not done; the agents' refusal and LIMITATIONS say it.
- The chat's transcript is built where the state is collected (the main thread), not under `flowOn(Dispatchers.Default)`:
  see §6 deviation 1.
- The agent page's "Recent tasks" still read whole task trees (`observeObjectives` + `observeTree`), against rule 16's
  "lists read summaries"; `TaskRepository.observeRecentByAgent` gives summaries but not the objective titles the rows show.
  Not in WP11b's plan items; left for wave 4 (performance lens).

## 4. Device tests (appended to device-tests-wave3.md §WP11b)

See that file. In short: chat reply cut off by a force stop, then "Send again"; the cost line with a real provider and a
price; today's limit in a chat; the D7 notice after a force stop with "Open accessibility settings"; another app's folder
name (package visibility) and StrictMode; decimal keyboard with a comma; a long chat's speed; 360 dp / razr outer screen and
font scale 2.0.

## 5. Notes for part 2 and wave 4

- **WP10c (Routines, Folder pick-up):** Tools offers your screens: "Routines: objectives that start on a schedule" (All
  tools tab) and "Folder pick-up rules" plus per read-only folder "Pick up new files" → `onNewPickUpRule(folderId)` (Folders
  tab). Writable folders get no "Pick up new files" button (a rule needs a read-only folder, F1). A folder already read by
  a rule shows "A folder pick-up rule reads it: making it changeable switches that rule off." under Read-only.
- **WP9 (Team view):** `toolInfoOf(name, tools)` in ui/chat/ChatPresentation.kt names `tools_open` "Open more tools"; use
  it for tool calls in the node sheet. `transcriptOf` and `TranscriptNotice` now know `NoticeKind.APP_STOPPED` and `LIMIT`.
- **WP10b:** nothing in WP11b depends on Settings changes; the chat's "Daily limits" button opens WP10a's screen.
- **WP12 (review):** check that "Send again" cannot resend while a turn runs (`canSendAgain` needs no live turn; the chat
  refuses a second turn anyway); the window logic of `ConversationPages.window` (orphan tool results, a turn longer than two
  pages: its footer then counts only what is shown; the chat-wide cost line is from the usage rows and stays whole); the
  D38 merge (`mergeEdit`) against concurrent edits; the cheaper-helper choice (same currency only; local unpriced models
  never replaced); the price parser (`parseAmountMicros`: comma decimal, ≤ 6 decimals, ≤ 7 integer digits, then
  `ModelPrice.check`); that the D37 export in local-only mode is logged as egress; that `storageAppName` never runs on the
  main thread (`FoldersViewModel(io = Dispatchers.IO)`).
- **WP14 (docs):** STATUS "Known rough edges" fixed: D29, D31/P5, D35 (agent page), D37, D38, D40; Phase 4/2 lines
  ("New agent button covers the last card", "graph export refused in local-only mode", "space deleted while editing") can
  say "fixed in 0.6.0". LIMITATIONS: the chat's cost line and the model editor's prices are estimates from the owner's
  prices; "Speed and reliability" covers the last 30 days on this phone only.

## 6. Deviations from the plan (and why)

1. **The chat transcript is not built under `flowOn(Dispatchers.Default)`.** Other packages' tests (WP8's `FastTypingTest`,
   WP11a's `ApprovalJourneysTest` and `ApprovalViewModelsTest`) open the chat and read it at once on the test's main
   dispatcher; moving the build to another thread would make them flaky, and they are not WP11b's to change. Instead the
   build is small and rare: at most 50 entries (plus "Show earlier" pages), read off the main thread by the store's own
   dispatcher (Room), and never repeated for a key typed into the composer (the draft is combined after the transcript is
   built). If WP8 wants the move, `ChatViewModel` takes one more `computation` parameter and those tests wait with
   `waitUntilShown`.
2. **"Send again" is offered under the last notice of any kind**, not only the app-stopped one: a failed turn (network,
   time-out) and a limit refusal can be tried again just the same, with the same `sendAgain`.
3. **The limit sentence also shows above the composer** while today's limit is reached and the chat leaves the phone, so
   the owner sees it before sending; sending is not blocked (a model on the phone is not limited, and the limit may have
   been raised a moment ago).
4. **Prices are saved separately from the model's description** ("Save price"), because saving the description makes a
   listed model the owner's version (no longer updated by its provider); a price must not have that effect.
5. **The cheaper-model suggestion compares typical work** (three parts read, one written, `TokenCounts(750k, 250k)`) and
   only prices in the same currency; helpers on a model without a price are never offered a paid one.
6. **D35 is fixed on the agent page, not in the memory graph** (§2 row 2).

## 7. Checks run

- Head commit `fe32c4f` on `p6/wp11b` (four commits from d025d68: 52283a3 chat, 9500d61 models, 757a1c8 agents, memory graph
  and tools, fe32c4f journeys, semantics, screenshots). Not pushed, not merged.
- Whole suite in the worktree (`export TMPDIR=/tmp; ./tools/gw --offline test --continue`, then `./tools/test-summary`):
  **3,497 tests, 0 failed**: app 762, core 182, data 493, device 367, framework 1,095, memory 107, providers 229,
  providers-anthropic 89, testkit 173 (3,445 at d025d68: app +52). Only `:app` changed; the other modules' results are
  unchanged (from the build cache).
- New or changed app tests: `ui/chat/ChatViewModelTest` (+7: D29, cut-off and Send again, nothing to send again, newest 50
  and Show earlier with a read count, cost line with and without a price, today's limit), `ui/chat/ChatPresentationTest`
  (4: Open more tools, tool results as written, notice kinds, cost per currency), `ui/chat/ChatSemanticsTest` (3: 48 dp
  buttons, merged cost line), `ui/models/ModelEditorViewModelTest` (+5: price saved/shown/removed, refusals, amounts,
  speed and reliability, cheaper model for helpers), `ui/models/PriceTypingTest` (1, paused clock, rule 27),
  `ui/agents/AgentEditorViewModelTest` (+4: tiers, tool token line, D38 through the VM and the merge),
  `ui/memory/graph/MemoryGraphViewModelTest` (local-only export test replaced: D37), `ui/tools/ToolsViewModelsTest` (+3:
  other-app note incl. Downloads entry, pick-up rule switched off, D7), `journeys/PlaceholderLabelsTest` (Vision only, on
  screen and in the source), `journeys/ChatCutOffJourneyTest` (2: cut-off and Send again after a start; price → cost),
  `screenshots/CostAndChatScreenshotTest` (23 pictures).
- `./tools/gw --offline :app:assembleDebug`: BUILD SUCCESSFUL.
- Screenshots (`:app:recordRoborazziDebug --tests …CostAndChatScreenshotTest`), in `app/build/outputs/roborazzi/` (build
  output, not committed, as the project does): p6_wp11b_chat_cut_off(.png, _light, _wide, _360, _360_font2),
  chat_cost_limit, chat_cost_light, chat_cost_limit_360, chat_show_earlier, model_editor_1_cheaper_price,
  model_editor_2_speed, model_editor_3_tiers, model_editor_cost_light, model_editor_cost_wide, model_editor_cost_360,
  model_editor_price_360, model_editor_price_360_font2, model_editor_use_for_helpers, presets_cost_lines,
  agent_editor_tool_tokens, agent_editor_model_tiers, agent_editor_model_tiers_360, tools_screen_switched_off,
  tools_screen_switched_off_360, tools_folders_other_app, tools_folders_pick_up_light, tools_folders_wide. Reviewed by eye.
