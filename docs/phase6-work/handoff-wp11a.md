# Hand-off from WP11a (wave 3, part 1: tasks, approvals and questions)

Branch `p6/wp11a` (worktree `/home/user/p6-wp11a`), from d025d68. WP11a changed only its own files (wave3-ownership.md §5:
`ui/tasks/**`, `ui/approvals/**`, `ui/questions/**` and their tests, the journeys `TasksJourneysTest`,
`ApprovalJourneysTest`, new `QuestionJourneyTest`, `NotesJourneyTest`, the screenshots `TasksScreenshotTest`,
`ApprovalScreenshotTest`, new `QuestionScreenshotTest`) plus one helper of its own, `app/src/test/.../testing/WP11aTesting.kt`.
Every `@Phase6Stub("WP11a")` mark is gone (the question screen and view model, and the marked routes).

## 1. Changes needed in files WP11a does not own (for WP8)

| # | Where | What | Why |
|---|---|---|---|
| 1 | `MainActivity.kt` (`setContent`) | Wrap the root: `CompositionLocalProvider(LocalScreenWork provides Dispatchers.Default) { AiControlCenterRoot(graph) }` (`com.aicontrolcenter.app.ui.tasks.LocalScreenWork`). Optionally a `MainActivityTest` line that the local is the background pool there. | P1/P2/P7: the Tasks, objective, task, question and "Waiting for you" view models build their state `flowOn(LocalScreenWork.current)`, and long Markdown is parsed on it. The local's default is the caller's dispatcher (`EmptyCoroutineContext`) so that every Compose test through `AppRobot`/`TestApp` stays synchronous (no race between a background thread and `waitForIdle`, which would make WP8's and other packages' journeys flaky). Until this line exists the app builds those states on the main thread as before 0.6.0 (no regression, but not yet the P2 gain). |
| 2 | `testing/ObjectiveWorld.kt` (`notesObjective`, `lisbonObjective`) | Give the sample REVIEW tasks the status line the framework writes when a review ends: `statusDetail = "Verdict: revise."` (for `tsk_review`) and `"Verdict: approved."` etc. | The plan rows are now built from task summaries (P1, rule 16): a step's verdict badge ("Revision asked", "Approved · 2 reviews") comes from its review tasks' `statusDetailShort` ("Verdict: <decision>.", `ReviewSteps` line 164), not from the reviewed task's `reviews_json`, which summaries do not read. The sample's review task has no such line, so the screenshots of the sample objective show no verdict badge now. |
| 3 | framework `orchestration/TaskWaits.kt` (`ProviderWaits`, internal) and `ReviewSteps` | Optional: public constants for "Waiting:", "Was waiting:" and "Verdict: " (core or a public framework object). | The app recognises them in `ui/tasks/TaskLabels.kt` `WaitTexts` and `TaskPresentation.kt` `verdictOf`; `ObjectiveStatesTest` pins the literal words. A public constant would make a change in the framework fail at compile time instead of in that test. |
| 4 | `ui/tools/**` (WP11b), Tools › History | Optional: open a QUESTION with `ToolsNavigation.onOpenQuestion` instead of `onOpenRequest`. | Works already: `RequestCard` now draws a question as `QuestionCard` ("Question for you", the question quoted, "Answer"), and the Approval route hands a question to the question screen at once (rule 13). Direct routing saves one screen change. The same holds for a chat's inline requests (`TranscriptItem.Request`). |
| 5 | `work/OperatorNotifications…` / `work/ApprovalNotifications.kt` (WP2's) | Check only: the notification channel is still named "Approvals" (`OperatorNotificationsTest`); the shell and the list now say "Waiting for you". | Owner-visible wording; a channel rename is a user-visible Android setting, so WP8/WP14 decide. |

## 2. What WP11a built, by the plan and the hand-off items

- **Question screen (rule 13, rule 32; handoff-wp5.md §3, secfix "Wave 3 notes" and "Second review", w2afix SEC-W2A-3).**
  `ui/questions/QuestionScreen.kt`, `QuestionViewModel.kt` (constructor kept, plus `zone`, `ticker`, `computation`),
  `QuestionPresentation.kt`. Title "A question from <agent>"; the banner "This is not a permission. Your answer is only
  text for the agent." (+ "An answer gives no permission and approves nothing…"); the question and the agent's reason as
  quoted agent text with hidden characters made visible and warned about; suggestions as a quoted list, each with "Use
  this answer" (a text button that only fills the field; never a primary button; marked sensitive); the answer field
  `AppTextField(state = viewModel.answer)`; a live hint before sending (`AnswerHint`: KEY "…cannot be sent", CODE "…asked
  once more"); "Send" → `ownerQuestions.answer(id, text)`; `CODE_ASK_AGAIN` → "Is this a code?" banner and "Send it
  anyway" (outlined in the error colour, below Send) for exactly that text (`answer(…, sendAnyway = true)`; editing the
  text withdraws the offer); `KEY_REFUSED` has no second chance; a stored provider key is refused by the framework's
  `VaultKeyCheck` (the screen never sees keys); "Decide yourself" → `answer(id, null)`; `closeIfAbandoned` whenever the
  question is not in the pending list; other refusals (gone, answered, expired, nothing waiting) turn the screen into
  its closed state with the sentence; where the answer goes in plain words (the agent's model and whether it is on the
  phone, the owner's network or the internet) and the after-answer rule ("…asks you first before it next uses a tool
  that sends something off the phone"); time left; links to the task and the objective. `ObscuredTouchGuard`, answers
  marked `isSensitiveData`, 48 dp targets.
- **Entry points.** "Waiting for you" list (`ApprovalsRoute(onOpenQuestion)`, title "Waiting for you", header
  "Waiting for you · 1 request and 1 question, oldest first", `QuestionCard`); the notification and every other path that
  opens `ApprovalRoute` for a question (chat, Tools history) hand over with `onOpenQuestion` (the Approval screen draws no
  approval for a question, only "Open the question"); objective and task screens (`WaitingForYou(onOpenQuestion)`).
- **Objective states.** `PauseNoticeUi`: "Paused: today's limit is reached" (`DailyLimits.PAUSED_TEXT`) with Resume and
  "Daily limits" (`onOpenDailyLimits`); "Paused: the phone is almost full" (`WorkResult.STORAGE_PAUSED_DETAIL`); "The
  Director is waiting: <provider> …; trying again at 10:45. The objective goes on by itself." (planning wait: RUNNING +
  `retryAt` + "Waiting:"). Steps: "Waiting to try again" (QUEUED + `retryAt` + "Waiting:"), "Stopped" with the "Was
  waiting: …" line (INT-3), "Paused: today's limit", "Paused: phone almost full". The task screen uses the same labels.
  A task waiting for a question shows "Waiting for your answer: “<question…>”" (agent text quoted).
- **Objective header.** Cost line ("So far: about US$0.04 · 12,400 tokens · 1 model answer", or tokens alone with what a
  token is and "Enter your model's price in Models…"; `CostEstimate.DISCLAIMER`) from `usageReports.observeWorkUsage
  (OBJECTIVE, id)` and the owner's prices; "Team view" (`onOpenTeam(id)`); "Started by routine “<title>”" (a link,
  `onOpenRoutine`) and "Screens: not allowed" when `options.screenActionsAllowed` is false.
- **Notes (C2, Tier B).** Conversation tab: "Write to the team" (folded to one button; opens to the recipient chips
  "The Director, <name>" and "<agent> · <task>" for each unfinished work task, `AppTextField(state = viewModel.note)`,
  "Notes reach the team from their next step. N of 10 notes used.", "Add note" → `ownerConversation.addNote(rootId,
  toTaskId?, text)`, refusals shown as they are, "A note … allows nothing and answers no request. It leaves the phone with
  the next request…"). GUIDANCE messages read "Your note"; filter "Your notes".
- **Reviewer line.** The statuses' " (same model as the author)" is shown on the plan row and the task screen (tested).
- **D9.** Approval screen of an allowed or approved request: "What happened" from the TOOL_CALL entry naming the request
  (`data.requestIds`): "Done.", "It failed: …", "Not run: …", "Stopped before it finished…", "Still running…",
  "Not in the activity log…" (`whatHappened`, pure).
- **New objective.** "Today: 37 of 200 answers · 12,400 of 500,000 tokens · US$0.12 of US$1.00" (`graph.todayUsage`; money
  only when priced) with "Daily limits"; D27 "<Director> plans the work with <model>, which is rated Basic at reasoning…";
  A3 "When you leave the app, Android may stop the work: <reasons>…" with "Open Settings"; R8: the free room is measured
  when the form opens (banner) and again at Start, which refuses at
  `storageWatch.check() == CRITICAL` with "The phone is almost full. Free some room before starting an objective.";
  `MaintenanceGate.RESTORING` → "The app is restoring a backup. Start the objective once the restore has finished."
- **P1/P2/P7.** Objectives list: one `observeObjectiveRows(51)` (50 shown + one to know "Show older"), +50 per "Show
  older objectives"; no trees. Objective screen: `observeSummaries` (shared, one read), the objective's record and the
  final result's instructions re-read only when their summaries change; the plan from summaries (verdict from review
  summaries, see §1 item 2); the elapsed line in its own `StateFlow` (`elapsed`), so the 1-second ticker never rebuilds
  the plan, messages or log (test: 10 ticks, 0 builds); `flowOn(computation)` + `conflate()`. Task screen: summaries +
  the task's own record. The final document parsed once per change in the view model and shown one lazy item per block
  (`markdownItems`); `MarkdownText` parses texts over 4,000 characters off the main thread (`produceState` on
  `LocalScreenWork`). Messages are still read whole for the Conversation tab (bounded by `maxMessagesPerObjective`, 200):
  the tab shows and expands bodies.
- **Smaller items.** `tools_open` shown as `ToolMenu.TITLE` ("Open more tools") in the task transcript; a memory shared
  into a space names the space ("Space “Team notes”"); question and request kinds have their own glyph (a question mark for
  a question); "Approvals" → "Waiting for you" in the notification explanation and the notifications-off banner.

## 3. Notes for part 2 (WP9, WP10b, WP10c, WP16) and for wave 4

- **WP9 (Team view).** Reuse: `QuestionCard`/`RequestCard` (a question opens with `onOpenQuestion`), `taskStatusLabel`,
  `WaitTexts`, `PauseNoticeUi`/`pauseOf`, `costLineOf`, `planRowsOf`/`participantsOfSummaries` over `TaskSummary`. A task
  waiting for a question: `ownerFacing(statusDetail)` quotes the agent's words. `LocalScreenWork` for its own builds.
- **WP10b (Settings › Questions from agents).** The question screen says nothing about the switch; when questions are
  switched off, no new question is raised, and open ones can still be answered.
- **WP10c (Routines).** The objective header links to `RoutineEditorRoute` through `onOpenRoutine`; the routine's title
  comes from `ownerRoutines.observeAll()`.
- **WP12 (review).** Attack: a question whose text imitates the app ("This is a permission…"), hidden characters and
  look-alikes in options (shown marked), a code under a non-code question (second tap), a key typed in groups, the
  "Send it anyway" offer surviving an edit (it must not: pinned by `QuestionViewModelTest`), a question opened through
  the Approval route (never drawn as an approval), the notes composer with 10 notes (framework refusal shown), the D9 line
  for a call whose log entry was pruned. Design choices to judge: the verdict badge read from review status lines (§1
  item 2), the folded composer, a refusal that closes the question keeps the screen open in its closed state (rather than
  leaving it).

## 4. Device tests

Appended to `scratchpad/phase6/device-tests-wave3.md` section 7 (a real question from its notification, code and key
answers on the phone keyboard, TalkBack order, font scale 2.0 at 360 dp, paused by the limit, phone almost full, a long
document).

## 5. Deviations from the plan (and why)

1. **Off-main building waits for one line in `MainActivity`** (§1 item 1): the composition local defaults to the caller's
   dispatcher so the whole app suite stays deterministic; the view models and Markdown already take the context.
2. **Plan verdicts come from review tasks' status lines** (§1 item 2), because summaries carry no reviews.
3. **Messages stay whole for the Conversation tab** (bounded at 200 per objective by settings); headers would need a
   second read to expand a message.
4. **A refusal that closes the question** (gone, answered, run out, nothing waiting) shows the question closed with the
   sentence, instead of leaving the screen: the owner sees why and what happened.
5. **"Started by routine “<title>”"** uses the app's curly quotes, not straight ones.

## 6. Checks run

- Whole suite in the worktree (`./tools/gw --offline test --continue`, then `./tools/test-summary`) at the state of
  7357e17 minus one late change (the New objective form measures the free room when it opens): **3,513 tests, 0 failed**
  (app 778, core 182, data 493, device 367, framework 1,095, memory 107, providers 229, providers-anthropic 89,
  testkit 173; 3,445 at d025d68, +68, all in app). After the late change: the app's WP11a, journey, screenshot,
  component and shell tests again (284, 0 failed), `:app:assembleDebug` BUILD SUCCESSFUL, and the screenshots recorded.
- New tests: `ui/questions/QuestionViewModelTest` 12, `QuestionScreenSemanticsTest` 4, `AnswerAndNoteTypingTest` 2
  (rule 27, paused clock), `ui/tasks/ObjectiveStatesTest` 14 (incl. 10 ticks build nothing, no tree observed, Show
  older), `NewObjectiveWarningsTest` 4, `ui/approvals/WaitingForYouTest` 4 (incl. D9), `journeys/QuestionJourneyTest`
  5, `NotesJourneyTest` 2, `screenshots/QuestionScreenshotTest` 21. Changed: `journeys/ApprovalJourneysTest` (title
  "Waiting for you").
- Screenshots (review material, not committed, as before): `app/build/outputs/roborazzi/p6_*.png` (28 files: the
  question in dark, light, wide w900dp, 360 dp, font 1.5 and 2.0, beside an approval, the code's second tap; the
  "Waiting for you" list; an objective paused by today's limit; the notes composer folded and open; the New objective
  warnings), copied with the re-recorded `p4_*`/`p5_approvals*` pictures to `scratchpad/phase6/wp11a-shots/`.
- Head commit: ac8e36a on `p6/wp11a` (7357e17 plus a comment naming this file; not pushed).
