# Hand-off from WP9 (wave 3, part 2): the Team view and the Dashboard's network card

Branch `p6/wp9` (worktree `/home/user/p6-wp9`, from e79e164). Files: `ui/team/**` (all new or filled stubs),
`ui/dashboard/network/AgentNetwork.kt`, `AgentNetworkView.kt`, `ui/dashboard/NetworkCard.kt`, `DashboardViewModel.kt`;
tests `ui/team/**`, `ui/dashboard/DashboardViewModelTest.kt`, `DashboardOrchestrationTest.kt`, `NetworkTrafficTest.kt`, new
`ui/dashboard/NetworkCardTest.kt`, new `journeys/TeamViewJourneyTest.kt`, new `screenshots/TeamScreenshotTest.kt`, new
`testing/WP9Testing.kt`. No file of another package was edited. The three WP9 stubs are filled and unmarked
(`ui/team/TeamScreen.kt`, `ui/team/TeamViewModel.kt`, `NetworkCard(…, onOpenTeam)`).

## 1. Changes needed in files WP9 does not own (for WP8)

| # | Where | What | Why |
|---|---|---|---|
| 1 | `ui/dashboard/DashboardScreen.kt` (`DashboardRoute`, WP8) | Pass the two new defaulted parameters of `DashboardViewModel`: `memoryCount = graph.ports.memories::observeCount` (as `AgentDetailRoute` does) and `computation = LocalScreenWork.current` (or `LocalUsageWork.current`, the same pool). | P4 (D35 on the Dashboard): the memory count then comes from the store's `COUNT(*)` instead of reading every memory on every recall write; P2: the Dashboard's state is then built on the background pool with `conflate()`. Without them the view model keeps today's behaviour (reads the memories through `MemoryService` and counts them; builds on the caller's dispatcher), which is what every existing test and journey sees. Both are trailing parameters with defaults, so nothing breaks before or after. Test to add with it: none needed beyond `DashboardViewModelTest` "memories are counted by the store's count, not read (P4)" (already passes with an injected counter); the StrictMode device step in `device-tests-wave3.md` (WP9 section) checks the real wiring. |
| 2 | `app/src/test/.../ui/shell/Wave3RoutesTest.kt` (WP8's test), lines 64-65 | `open(TeamRoute(), "Team")` and `open(TeamRoute(objectiveId = "tsk_1"), "Team")` → `"Team view"` (`TeamWords.TITLE`, the screen's real title; the stub said "Team"). | The test asserts the stub's title with an exact match; with the real screen it is the only failing test of the whole suite on this branch (3,727 tests, 1 failed, before the last changes). Not edited here: WP8's file. |
| 3 | (optional) `ui/tasks/TaskLabels.kt` `WaitTexts.waitingFor` (WP11a's, now WP8's) | `replaceFirstChar(Char::uppercase)` turns "xAI is not answering" into "XAI is not answering". Consider not upper-casing when the first word already has a capital later in it, or leaving the framework's line as it is. | Cosmetic: provider names keep their own spelling. The Team view and the objective page both show the framework's provider-wait line through this helper; `TeamGraphBuilderTest` pins today's "XAI …" and would change with it. |

Nothing else: the routes, `TeamNavigation` (FINAL, unchanged), the Dashboard slot and `NetworkCard(…, onOpenTeam)` were in
place; `DashboardScreen` already passes `onOpenTeam = { navigation.onOpenTeam(null) }` and `ObjectiveRoute` its own
`onOpenTeam(id)`.

## 2. What the Team view does (for wave 4's reviewers)

- **Model (pure, JVM-tested):** `TeamGraphBuilder.build(snapshot, summaries, headers, requests, monitor, now, spaces,
  objective)` over K1 summaries, K2 headers and `OwnerRequests.pending` only (rule 16). "You" at the top (open requests and
  questions, oldest first; with "All current" also a chat's requests), the designated Director in the centre, agents on one
  or two rings, deleted participants as "A former team member", spaces along the bottom (shared-into first; `Participant.Space`,
  older `System` shares to "Shared memory"). Caps: 30 agents (`agentsLeftOut` counted, the List opens and says why), 6
  spaces, 120 links (newest kept). One link per pair; its kind is the newest message's (ASSIGNS, ASKS, DELEGATES, REVIEWS,
  REPORTS, SHARES, ASKS_YOU, TELLS_YOU, NOTES), with per-kind counts both ways (replies show the way back but are not counted).
- **States, first that applies:** waiting for you (a request or question of the agent, the task's WAITING_FOR_OWNER, or the
  monitor's WAITING_FOR_APPROVAL), working, waiting for a teammate (also "Waiting for its work to be checked"), waiting to
  try again (R4 provider wait), cannot run (availability words), paused (plain reason: today's limit, phone almost full, by
  you, by the app), stopped ("Was waiting: …" after a pause or interruption, INT-3: never "waiting with a time";
  "Interrupted: …"), waiting for earlier steps, up next, could not finish, done, free. The live monitor wins over a task row
  that still says RUNNING while its run waits (N3).
- **Objectives:** "All current" (up to 5 running, paused or stopped objectives) or one; chips also offer the 5 latest finished
  ones. One objective shows a card: title, status, "Started by routine “<title>”" (from `ownerRoutines.observeAll()`, read
  only), the pause or stop reason with what to do, "Open objective". With "All current", each paused or stopped objective
  has a notice that selects it.
- **Picture:** `TeamLayout` (positions in dp, depend only on who is in the picture; equal spacing along each ring by length;
  every 48 dp target ≥ 52 dp from every other; the picture grows taller and pans rather than shrinking targets; names hidden
  when they would touch). Lines in their kind's style (solid, thin, dashed, dotted, double, dash-dot, long dashes) with
  arrowheads for the directions used; the newest 12 lines with room get a 48 dp sign with the kind's 20 dp glyph
  (`LinkSigns`, never on a member or another sign); lines that carried a message in the last minute are thicker (static, also
  under Calm). One finger pans; pinch or the three buttons zoom (1x..3x).
- **Dots:** `PulseScheduler` (pure): a dot only for a message new to the screen, once, 1.2 s, at most 8 in the air, later
  ones wait ≤ 2 s then are dropped; nothing for what was there when the screen opened or the objective was switched.
  `rememberPulseClock`: one `withFrameMillis` loop only while a dot is in the air, the frame time read only in the draw
  lambda (N1), paths built once per layout in `drawWithCache` (N7), dots drawn above everything; none under Calm
  (`LocalCalmMotion`, which includes Android's animation switch) or a motion duration scale of 0 (rule 17, N9). The same
  clock drives the Dashboard card (N2: no looping newest-four, no work pulses).
- **Accessibility:** the picture is a traversal group: You, Director, the ring clockwise from the top, spaces, then the
  signs; each member's description says name, role, state, what about, task and connections ("Connections: was asked by
  Analyst; got work from Director."); custom actions "Answer the question" / "Open the request" and "Show messages with …";
  signs read "Questions between Analyst and Researcher, 2 messages". The List comes first with TalkBack
  (`isTouchExplorationEnabled`), font scale ≥ 1.5 or more than 30 agents, with the same members and the same sheets; the
  picture offers "Show the list" then. One polite live region: the summary, changed at most every 10 s and only when the
  numbers change (`atMostEvery`).
- **Sheets:** a member (state, task, model and where its text goes, its requests as `RequestCard`s that open their own
  screens, buttons Open agent / task / objective / space, its links, its 10 latest messages as headers); a space (who may
  read and write it, as set on the agents' pages, never offered wider); a link (every message as stored, read with
  `MessageRepository.list` only once that sheet opens, shown with WP11a's `MessageRow`); "You" (what waits, oldest first;
  what the team and the app told you; your links). Nothing in a sheet answers anything (rule 13).
- **Threads:** the state is built `flowOn(LocalScreenWork.current)` with `conflate()`, one shared build for the screen and
  the spoken summary; a sheet never rebuilds the picture (`builds` hook, pinned).

## 3. The Dashboard card (N3, N6, P2, P4, plus N1, N2, N7, N9)

- `DashboardViewModel`: running objectives as `observeSummaries` (not `observeTree`), messages as `observeRecentHeaders(60)`
  (not `observeRecent`) (N6); memory count through `memoryCount` when given (P4, item 1); `flowOn(computation).conflate()`
  (P2, item 1); the "Working now" step text through `TeamWords.stepOf` (shared with the Team view).
- `toAgentNetwork`: an agent whose run waits for you is "Waiting for you" (attention tone, thicker ring), for a teammate
  "Waiting for a teammate", never "Working" (N3); `pulses` are the newest 12 agent-to-agent messages of running objectives in
  sending order, of which the card sends a dot only for new ones (N2).
- `AgentNetworkView`: `rememberInfiniteTransition` removed; the shared pulse clock; curves built once per layout; Calm.
- `NetworkCard`: "Open Team view" (48 dp) with one line on what it shows; legend items "Waiting for you" and "Waiting for a
  teammate"; the legend's dot sentence follows Calm. The node descriptions and "Tap an agent to see its role and status."
  are unchanged (`ScreenshotTest` taps them).

## 4. Hand-off items addressed to WP9, done and not done

| Item | From | Done? |
|---|---|---|
| "Who waits for you" = `OwnerRequests.pending`, PERMISSION, APPROVAL and QUESTION; a question's task line shown as quoted agent text, never in a notification | handoff-wp5.md §3, wave-2a merge §3 | Done ("Asks you: “…”"; `ownerFacing` for the task line) |
| Waiting tasks (`QUEUED` + `retryAt` + "Waiting:") shown as waiting to try again; notes are GUIDANCE from "You" (`LinkKind.NOTES`); memory shares to `Participant.Space` (older ones `System`) | handoff-wp7.md §4 | Done |
| Waiting work after a pause or interruption shown as stopped ("Was waiting: …"), never as waiting with a time | handoff-w2afix.md INT-3 | Done (pinned in `TeamGraphBuilderTest` and `TeamViewModelTest`) |
| The "did not answer in time" line (`OwnerQuestions.NO_ANSWER_PREFIX`) may be shown | handoff-w2afix.md INT-2 | Done: it is a STATUS from the app to the agent, listed in that agent's sheet under "Latest messages" ("Status · The app to Analyst"); not drawn as a line, since the app itself is not in the picture. |
| `listOfTypes(rootId, OwnerNotes.TYPES)` for notes | handoff-w2afix.md INT-4 (optional) | Not needed: notes come with the headers already read (`observeHeaders`), so no extra read. |
| Tool results shown as the tool's own text; never the model's copy | handoff-w2afix.md residuals | Done by construction: the Team view shows message headers and bodies, never tool results or the model's copy. |
| `tools_open` shown as `ToolMenu.TITLE` | handoff-wp15.md §3.1 | Done: the monitor never reports `tools_open` (the runner opens the family before reporting), and `TeamWords.toolNames` maps it anyway in any step text. |
| `toolInfoOf` for tool calls in the node sheet | handoff-WP11b §5 (optional) | Not needed: the node sheet shows no tool calls (tool-family nodes were the reader's proposal, not the plan's WP9 text). |
| `registryDirectory.observe()` is one shared flow | handoff-wp4.md §4 | Used as is. |
| Per-run activity monitor (`runStarted` returns a `Run`) | handoff-w2bfix.md | Nothing to change: the screens read `states`; the "most active live run" rule is why the monitor wins over a stale task row (N3). |
| "Started by routine '<title>'" on a routine's objective | handoff-wave2b-merge.md §3, handoff-wp17.md §6 | Done (card; curly quotes as WP11a). |
| Script answers shown as they are | handoff-wp6.md §5 | Done by construction (message text shown as stored). |
| Calm: read `LocalCalmMotion` wherever something travels or pulses (Team view and network card) | handoff-wave3a-merge.md §4 | Done (both). |
| Background pool: `flowOn(LocalScreenWork.current)` | handoff-wave3a-merge.md §4 | Done in the Team view; the Dashboard needs item 1. |
| Status lines through `TaskStatusLines` / `WaitTexts` | handoff-wave3a-merge.md §4 | Done (`WaitTexts.WAITING`, `WAS_WAITING`, `waitingFor`, `taskStatusLabel`). |
| Cost per agent in the node sheet (`byAgentAndModel`) | handoff-wave3a-merge.md §4 (optional) | Not done: the node sheet shows the model and where its text goes; the cost per agent is on Activity › Usage and cost. Adding it needs `UsageReports` and `ModelPrices` in `TeamViewModel` (a trailing parameter); left for wave 4 if wanted. |
| Reuse `QuestionCard`/`RequestCard`, `taskStatusLabel`, `WaitTexts`, `pauseOf`, `costLineOf`, `planRowsOf` | handoff-wp11a.md §3 | `RequestCard` (which shows a question as `QuestionCard`), `taskStatusLabel`, `WaitTexts`, `ownerFacing`, `MessageRow`, `messagesUiOf` reused; `pauseOf` needs a whole record, so the same words are produced from the summary and the record by `TeamWords.pauseReason`/`pauseAdvice`. |
| "WP9 checks the Dashboard's memory counts in part 2" (D35) | handoff-wave3a-merge.md §3 | Done in the view model (`memoryCount`), wiring is item 1. |
| Generic screen rules (plain words, "(test)" only on Vision, 48 dp, TalkBack, never colour alone, 360 dp, wide, dark, light, font 1.5/2.0, Calm, off the main thread) | task, wave3-ownership.md §0 | Done; no control here is partly working, so no "(test)". No text field in the Team view, so no rule-27 typing test. FLAG_SECURE is not needed (nothing secret). |

## 5. Notes for wave 4 (reviewers) and wave 5 (end-to-end)

- **Performance lens:** the Team view observes `observeObjectiveRows(30)`, then per shown objective (at most 5)
  `observeSummaries` and `observeHeaders(id, 1000)`; the objective's own record is read again only when its summary changes
  (for options.routineId and the full status line). Message bodies are read once per opened link (`MessageRepository.list` of
  the shown objectives, filtered by the link's ids). `TeamGraphBuilder.build` is linear in headers plus O(agents²) in the
  layout's fit check (≤ 45 points). The frame loop stops when the last dot arrives (`DotsInFlight` semantics pinned in
  `TeamScreenSemanticsTest`, `TeamMotionOffTest`, `NetworkCardTest`).
- **Design choices to judge:** one link per pair styled by its newest kind (not one line per kind); the "Free" word for idle
  agents; CANNOT_RUN ranks below working and waiting but above paused/stopped/done (an agent without a model in a finished
  objective reads "No model yet"); waiting-for-you rings are static (no single pulse when a request appears); with "All
  current", requests raised in chats count for "You" too.
- **Device (wave 5):** `scratchpad/phase6/device-tests-wave3.md`, section "WP9" (plan §10 step 13, TalkBack order and
  actions, Calm and `animator_duration_scale 0`, font 2.0 on the razr, 12+ and 31 agents, StrictMode after item 1, frame
  stats with 8 dots).

## 6. Deviations from the plan (and why)

1. **`TeamViewModel`'s constructor** (not FINAL; built in WP9's own `TeamRoute`): `settings: SettingsView` dropped (Calm comes
   from `LocalCalmMotion`, which already joins the owner's Calm and Android's `areAnimatorsEnabled()`); added read-only
   `zone`, `routines` (`ownerRoutines.observeAll()`, for "Started by routine"), `capabilityInfo` (the request cards' words),
   `computation` (`LocalScreenWork`) and `summaryEveryMs`. No owner-only port and no settings writer reach it.
2. **"All current"** shows running, paused and stopped objectives (not running only), so a paused objective's reason is
   visible; finished ones are chips of their own.
3. **The "recent" afterglow is static** (a thicker line for a minute, recomputed with the data), not a 60 s fade: nothing
   animates but the dots, and it reads the same under Calm. Waiting-for-you rings are static too (no single pulse).
4. **One line per pair**, styled by its newest message's kind, with every kind and count in its sheet and description,
   instead of one line per kind (fewer crossings on a phone).
5. **No tool-family nodes or "Show tools"** (the reader's proposal; not in the plan's WP9 text): the Team view reads no
   activity log.
6. **The List also opens first above 30 agents** (the reader's "opens in the list mode and says why").
7. **The Dashboard's P2 and P4 need item 1**: the view model is ready; the route is WP8's.
8. **One WP8 test fails on this branch** (`Wave3RoutesTest` pins the stub's title "Team"): item 2. Checked: with "Team view"
   in a copy of that test, both of its tests pass on this branch (the copy was deleted, not committed).

## 7. Checks

- Head: see the report (branch `p6/wp9`, not pushed, not merged).
- Whole suite in the worktree (`./tools/gw --offline test --continue`): 3,727 tests, 1 failed (`Wave3RoutesTest`, item 2);
  then, after the last changes, `:app:testDebugUnitTest` again: app 991 tests, 1 failed (the same); other modules unchanged
  since: core 182, data 494, device 367, framework 1,096, memory 107, providers 229, providers-anthropic 89, testkit 174 —
  3,729 in all, 1 failed. `:app:assembleDebug`: BUILD SUCCESSFUL.
- New or changed tests: `ui/team/TeamGraphBuilderTest` 13, `TeamLayoutTest` 5, `PulseSchedulerTest` 3, `TeamViewModelTest` 6,
  `TeamScreenSemanticsTest` 8, `TeamMotionOffTest` 1; `ui/dashboard/NetworkCardTest` 3 (new), `DashboardViewModelTest` +2 (one
  renamed), `NetworkTrafficTest` 3 (two rewritten), `DashboardOrchestrationTest` 1 (changed); `journeys/TeamViewJourneyTest` 3;
  `screenshots/TeamScreenshotTest` 16 (pictures).
- Pictures (review material, not committed, as earlier packages): `app/build/outputs/roborazzi/p6_wp9_*.png` (19 files),
  copied to `scratchpad/phase6/wp9-shots/`.
