# Hand-off from WP5 (broker, questions and phone-operator fixes)

Branch `p6/wp5` (worktree `/home/user/p6-wp5`), from 90e1d70. WP5 changed only files it owns (framework/operator/*
except JsSandbox/JsEngine/JsTools, framework/tools/*, framework/orchestration/TeamTools.kt, providers/web/AddressGuard.kt,
device/screen/*, app/operator/ScreenSessions.kt, app/di/wiring/OperatorWiring.kt) and their tests, plus one line group
in the framework architecture test (roots += the question's agent side, as the secfix hand-off allows). Nothing in a
shared file was touched. Test numbers and the head commit are in the report at the end of this file.

## 1. What WP5 built (plan items)

| Item | What | Where |
|---|---|---|
| 1 C1 questions (Tier B) | `team.ask_owner(question, options?)`: `AskOwnerTool` (TeamTools.kt; `TeamTools.registerAskOwner`), registered by `OperatorAssembly.teamReady` (so every work task offers it while `agentQuestionsEnabled`, through WP1c's `WorkSteps.teamTools`). `OwnerQuestions` (agent side): only the assignee of a RUNNING (or WAITING_FOR_OWNER) WORK task of a RUNNING objective; `QuestionRules.questionProblem`; rule 13's refusals under the shared lock (a PERMISSION/APPROVAL of the same task open; 1 open / 2 per task / 4 per objective, counted from the objective's QUESTION messages, so they survive a restart; near-identical; 5/3 agent-initiated); QUESTION request (SAFE, no constraint, no scope, capability `team.communicate`, toolId `team.ask_owner`, expiry `ownerQuestionTimeoutMinutes` in 5..240); waits exactly as an approval waits (`OwnerRequestWait`, below); the answer comes back as "The owner answered, in their own words:" + `DataBlock.frame`; decide yourself; time-out "The owner did not answer within N minutes. Make the most reasonable choice, say which one in your result, and go on."; the QUESTION (agent → Owner) and ANSWER (Owner → agent) messages are stored in the objective. Owner side: `DefaultOwnerRequests.answer(id, text, sendAnyway)` with `AnswerCheck` (KEY always refused, CODE refused unless `sendAnyway`) and `OwnerKeyCheck.matchesStoredKey` (also after "Send it anyway"); refused and closed when nothing waits any more ("No longer waiting…"); goes through the store's compare-and-set; logged without the text. After any answer (text or "decide yourself"), the task's (or a task under it's) next tool call with `sendsDataOffDevice` asks an APPROVAL whose first detail line is `PermissionBroker.AFTER_ANSWER` ("…after you answered a question…"); `AnsweredQuestions`, one flag per task, shared by the broker and the questions, cleared once such an approval is given | framework/operator/OwnerQuestions.kt, OwnerServices.kt, PermissionBroker.kt; orchestration/TeamTools.kt |
| 2 C3 + D10 | `OwnerAttentionGate`: the one lock under which the broker, the capability requester and the questions check and raise every request; caps of `OwnerAttention` over the live pending requests (call requests 12 / 6 per objective, rule 14; agent-initiated 5 / 3 per objective); refusal texts tell the model to assume, say so and go on (`ASSUME_AND_GO_ON`); the owner is told once per task (or chat) by a WARNING "Not asked: too many requests wait for you" | framework/operator/OwnerAttentionGate.kt, PermissionBroker.kt, DefaultCapabilityRequester.kt |
| (extraction) | The broker's wait (old `ask`/`answerOf`/`markWaiting`/`resume`) is now `OwnerRequestWait.ask`, used by the broker and `OwnerQuestions`; behaviour of the broker unchanged (every Phase 5 broker test green) | framework/operator/OwnerRequestWait.kt |
| 3 D11 shell slot (rule 15) | `PermissionBroker.authorize` calls `prepared.beforeOwnerAsked()` after the permission step and before the APPROVAL (or before the call runs); `ToolExecutor` calls `callEnded()` in a `finally`; `LocalWorkspace.reserveCommand/releaseReservation` over core's `CommandSlot`; `ScriptsRunShell` no longer refuses in `assess` (an agent without the permission is asked for it first and learns nothing); `ShellSlot` takes the slot (also inside the action when no broker asked) and gives it back. Text `ShellSlot.SLOT_TAKEN` | framework/operator/ShellTool.kt, LocalWorkspace.kt, OperatorToolSupport.kt (`PreparedCall` gained `beforeAsked`/`ended`), PermissionBroker.kt; framework/tools/ToolExecutor.kt |
| 4 D12 | `OwnerWaitLedger` (start/finish, ongoing waits count up to now) filled by `OwnerRequestWait`; `RecentFileChanges(ownerWaits = …)` ages a change by its time less the owner waits of its agent, task or objective; `ToolExecutor` logs `ownerWaitMs` in every TOOL_CALL entry | OwnerWaitLedger.kt, BulkChanges.kt, ToolExecutor.kt, OperatorWiring.kt |
| 5 D4 device side, S22 | `AccessibilityScreenOperator.holdSession/releaseSession` by hold id; a held session shows "<agent> waits for your answer to act in <app>" on the banner; neither `endSession` (any run) nor the idle time-out ends it; it ends with its last hold when its run ended meanwhile; Stop ends every hold; a hold while stopped is not taken. `ScreenSessions` passes holds on and mirrors them (`ScreenSession.waitingForOwner`) | device/screen/AccessibilityScreenOperator.kt; app/operator/ScreenSessions.kt |
| 6 D5 | `AppsOpen` (package), `AppsOpenLink` and `BrowserOpen` (null package) record launches; `ScreenTools.front(…, agent)` waits every `screenPollMs` for up to `screenOpenWaitMs` when this agent launched within `launchRecentMs` (Stop ends the wait) | AppTools.kt, ScreenTools.kt |
| 7 D6 | `AccessibilityScreenOperator.frontState()` answers `Hidden` for an active window without a readable root; screen tools refuse with `ScreenTools.HIDDEN_WINDOW` | AccessibilityScreenOperator.kt, ScreenTools.kt |
| 8 D7 | `SwitchedOffWatch` (fed every service state; `ReadyFlagStore` in SharedPreferences `screen_service`/`was_ready`; `sdkInt` and `StartRecord` list injected; NOT_ENABLED or RESTRICTED as the first decisive state with the flag set → `switchedOffByAndroid` true until READY; "force-stopped" only on 35+ when `ApplicationStartInfo.wasForceStopped()`); `SwitchScreenControl.switchedOffByAndroid`; `ScreenOperatorLayer.switchedOffSentence` (the owner's sentence) | device/screen/SwitchedOff.kt, StopSwitch.kt, ScreenOperatorLayer.kt |
| 9 D8 | `WebReadPage.assess` refuses `WebAddressPolicy.literalRefusal(host)` before authorisation ("The page was not read: …"); `AddressGuard.literalRefusal(url)` calls the same function first, then this device's own addresses | WebTools.kt; providers/web/AddressGuard.kt |
| 10 S15 | `DeviceFacts.screenOn()` (`PowerManager.isInteractive`, unknown = off); the gate and the re-check right before acting refuse with `ScreenResult.NotSupported(AccessibilityScreenOperator.SCREEN_OFF)` ("The phone's screen is off. Screen actions wait until you use the phone.") | ScreenAvailability.kt, AccessibilityScreenOperator.kt |
| secfix | `DefaultCapabilityRequester` refuses a screen ask in an objective whose `screenActionsAllowed` is false or whose record cannot be read, and uses the objective's `approvalTimeoutMinutes`; the broker (now also an `EarlyRefusal`) refuses a routine's screen call **before** the tool's `assess`, so not even the window is read (WP15's optional item 4); the key check reaches only `DefaultOwnerRequests` (`OperatorAssembly(…, ownerKeyCheck)`) | DefaultCapabilityRequester.kt, PermissionBroker.kt, ToolExecutor.kt (`EarlyRefusal`), OperatorWiring.kt |

## 2. Changes needed in files WP5 does not own

| Where | What | Why | Who |
|---|---|---|---|
| app/work/ApprovalNotifications.kt | A QUESTION now appears in `OwnerRequests.pending` and gets today's "Question waiting" notification with "Researcher: A question from Researcher" (app-written; the agent's question is never in it). Use the planned wording "A question from Researcher. Open the app to read it.", no actions, private with the public version; never `request.question`, `options` or the task's `statusDetail` (which holds the question's first characters, see §4). | rule 4 | WP2 |
| app/work/ScreenSessionNotifications.kt | Show `ScreenSession.waitingForOwner` as "<agent> waits for your answer to act in <app> · Stop" (Stop stays the only action). | D4, rule 12 | WP2 |
| app/di/Ports.kt, ProductionPorts.kt, AppGraph.kt | Optional: pass `ScreenOperatorLayer.switchedOffSentence` (owner-only) to the owner's Tools › Screen control screen; `AppGraph.screenControl.switchedOffByAndroid` already reports the boolean. | D7 | WP8 |
| app/src/test/.../di/OperatorDependencyTest.kt, ProductionGraphDependencyTest.kt | Nothing is required: `AskOwnerTool` is registered in the tool registry (teamReady), so "every tool" already walks `OwnerQuestions`, the gate, the wait and the ledger (both app walks are green). WP8 may name `OwnerQuestions` explicitly among the roots. | rule 3 | WP8 |
| app/ui/approvals/ApprovalPresentation.kt (WP1a stand-in) | The QUESTION screen (rule 13), see §3 WP11a. | C1 | WP11a |
| framework/orchestration/ObjectiveJournal.kt | Nothing: the question's messages are written by `OwnerQuestions` itself (deviation 1), and their log lines never carry the owner's words. If WP7 changes how ANSWER messages are logged, `OwnerQuestions.send` already matches it. | S28 | WP7 (check only) |
| docs | §5 below. | | WP14 |

## 3. Notes for wave 2b and wave 3

- **WP2 (background):** a task waiting for a question is WAITING_FOR_OWNER exactly like one waiting for an approval; a
  question's expiry is a coroutine timeout in this process (as an approval's), not a `TimedWaits` entry, so it may fire
  late after Doze; the owner's answer wakes it. Questions are agent-initiated requests: `OperatorTaskHooks.taskEnded`
  does not withdraw them (they have a live call; the call's cancellation closes them as CANCELLED).
- **WP17 (routines):** a routine objective's screen tools are refused before `assess` (nothing of the window is read)
  and in the broker; its permission asks use the routine's time-out; screen asks are refused.
- **WP3 (backup):** requests, questions and answers are never in the file (rule 8). The answer text lives in the
  request row (`owner_requests.answer`) and in the objective's ANSWER message; messages are conversation text, which the
  frozen format v1 decides on (WP3/WP1a).
- **WP9 (Team view):** "who waits for you" = `OwnerRequests.pending`, kinds PERMISSION, APPROVAL and QUESTION. The
  task's `statusDetail` while it waits for a question is "Waiting for the owner's answer: <question…>" (agent text, cut
  at 300): show it as quoted agent text, never in a notification.
- **WP10 (settings):** "Questions from agents" (`agentQuestionsEnabled`, default on, Q3) and "Question time-out"
  (`ownerQuestionTimeoutMinutes`, `SettingsBounds.QUESTION_TIMEOUT_MINUTES` 5..240, default 20).
- **WP11a (question screen, rule 13):** title "A question from <agent>", the line "This is not a permission. Your answer
  is only text for the agent."; `request.question` shown as quoted agent text; `request.options` as a quoted list with
  "Use this answer" (puts the option into the answer field; never a primary button); the answer field an
  `AppTextField(state = …)` in the view model plus a paused-clock typing test; "Send" → `ownerQuestions.answer(id,
  text)`; "Decide yourself" → `answer(id, null)`; on `OwnerResult.Refused(DefaultOwnerRequests.CODE_ASK_AGAIN)` show it
  and offer "Send it anyway" → `answer(id, text, sendAnyway = true)`; `KEY_REFUSED` has no second chance; other refusals
  (`QUESTION_GONE`, `NOT_WAITING_NOTE`, expired, answered) close the screen with the sentence. Call
  `closeIfAbandoned(id)` when the screen opens, as the approval screen does. After an answer, the next off-phone call of
  that task arrives as an APPROVAL whose first detail line is `PermissionBroker.AFTER_ANSWER`: show the details as
  today. The history lists questions with ActivityType.MESSAGE entries ("You answered Researcher's question", "No answer
  in time: …", "Question withdrawn: …"), never with the answer's text.
- **WP11b (Tools › Screen control):** the D7 notice: when `screenControl.switchedOffByAndroid` is true, show the sentence
  (`ScreenOperatorLayer.switchedOffSentence`, or the generic `SwitchedOffWatch.STOPPED` wording) with a button to
  `ScreenOperatorLayer.accessibilitySettingsIntent()`.
- **WP8:** `AppGraph.ownerQuestions` (= `ownerRequests`) is the owner's answer port; `OperatorAssembly.ownerQuestions` is
  the agent side (never hand it to a screen).
- **WP12 (review):** attack the question path with concurrent asks (one lock: `OwnerAttentionGate`), a near-identical
  question in other words, approval words in options (core `QuestionRules`), answers with keys and codes, an off-phone
  call right after an answer (and through a delegated task), a hold left open (Stop clears it; `releaseSession` is in
  WP15's `finally`), and the shell slot (a call cancelled while it waits, a refused approval, a command run without the
  broker: all give the slot back).

## 4. Device tests to run

None: WP5 added no instrumented tests (the device side is covered by Robolectric at SDK 35, as the plan asks). Section
10 (wave 5) should check on the emulator: a screen tool right after `apps_open` (D5); a permission dialog in front
(D6); force-stop of the app with screen control on, then the notice (D7, Android 14: generic wording); screen off
(`adb shell input keyevent KEYCODE_SLEEP`) refuses screen actions with the rule-12 sentence; a question answered from
the app once WP11a's screen exists.

## 5. Docs (WP14)

- LIMITATIONS: questions leave the phone with the next model call (a cloud model receives the answer); a question's
  time-out and the bulk window's owner-wait pause are in memory (reset by a restart); D8: names that resolve to a
  private address are still refused only when the page is read; D7: before Android 15 a force stop cannot be told from
  a swipe from Recents, so the notice says "stopped"; a hold taken before the stored Stop was read at start-up is not
  taken (the next screen call reads it).
- STATUS rough edges fixed: D4, D5, D6, D7 (explained and linked), D8, D10, D11, D12 (counts still reset at restart), S15.

## 6. Deviations from the plan (and why)

1. **Question messages are not written through `ObjectiveJournal.send`.** `ObjectiveJournal` is WP7's file and logs the
   first line of every message body, which for an ANSWER would put the owner's words into the activity log (S28) until
   WP7's change lands. `OwnerQuestions.send` stores the same `AgentMessage` (QUESTION agent → Owner, ANSWER Owner →
   agent, task and objective set) and logs "Question: Researcher → Owner" with the question's first line, and "Answer:
   Owner → Researcher" with "You answered a question" only.
2. **The after-answer approval also follows "decide yourself"** (the rule says "after any answer"), and also covers
   calls of tasks delegated under the answered task (their parent chain is walked, at most 16 steps). A refused approval
   keeps the flag.
3. **A question may be asked from a task that is WAITING_FOR_OWNER** (as well as RUNNING), so that rule 13's own refusal
   ("a permission or an approval of this task waits for the owner"; "your earlier question still waits") is what the
   model reads, instead of "only while working on a task".
4. **The caps count only requests a live call of this process waits for** (`RequestWaiters`), not stale rows a crash
   left pending (start-up closes those).
5. **`ScriptsRunShell` takes the slot inside its action too** when no broker asked (an authorizer other than the broker,
   or a direct `execute` in tests), and gives it back at the end of the action in that case, so a slot can never be held
   without a `callEnded` to come.
6. **`OwnerWaitLedger` records ongoing waits** (start/finish), not only finished ones: otherwise another agent's bulk
   check during an 11-minute wait would prune the waiting agent's changes before the wait is recorded.
7. **Screen-off refusal is `ScreenResult.NotSupported(SCREEN_OFF)`** (core's `ScreenResult` has no dedicated case and
   core is frozen in wave 2); the lock screen keeps its existing `WrongWindow` result.
8. **D7 treats RESTRICTED like NOT_ENABLED**: a sideloaded app's removed service is reported as RESTRICTED (Android 13+),
   which is the owner's case.
9. **Not done (optional in WP15's hand-off):** shorter `permissions.request` description.

## 7. State at hand-off

Head commit `092e452` on `p6/wp5` (three commits from 90e1d70: c39ff8b framework, 8502ea8 device and app, 092e452
objective-level tests). Whole suite in the worktree (`./tools/gw --offline test --continue`, after the last change):
**2,829 tests, 0 failed** (app 476, core 164, data 430, device 348, framework 817, memory 107, providers 227,
providers-anthropic 89, testkit 171); before WP5 2,775. New: framework 38 (OwnerQuestionsTest 16, OwnerAttentionCapsTest 4,
ShellSlotProbesTest 5, BulkOwnerWaitTest 4, PhoneOperatorFixesTest 6, AskOwnerObjectiveTest 3), device 13
(ScreenPhase6Test), app 3 (ScreenSessionHoldsTest). Adapted (behaviour change of D8/D11, assertions only): framework
ShellRound4Test, ShellWorkspaceLockTest, Round4MeetingPointsTest, AppMediaWebToolsTest; OperatorDependencyTest roots.
`:app:assembleDebug` builds.
