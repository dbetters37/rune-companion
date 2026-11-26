# Phase 6 contracts: guide for the builders

WP1a, branch `p6/wp1a` (worktree `/home/user/p6-wp1a`, from 9c739d5). The contracts are in `:core` and `:testkit`;
`tools/gw --offline :core:test :testkit:test` passes, and every other module compiles and passes its tests on this
branch: 2,577 tests, 0 failed (core 128, testkit 163, framework 756, memory 107, providers 227, providers-anthropic 89, data 339, device 334, app 434), and `:app:assembleDebug` builds. Read the plan's OWNER DECISIONS and rules 1-34 first; this guide says, contract by
contract, what each type promises, which package builds it, which rule it serves and which suite checks it.

**Compatibility.** Every change to an existing type is additive with a default: new interface members have default
bodies (or no-op defaults where nothing can be derived), new data-class fields come last with defaults, new enum values
come last. Five additions cannot be made invisible to Kotlin's exhaustive `when`: `OwnerRequestKind.QUESTION`,
`OwnerDecision.Answer`, `MessageType.GUIDANCE`, `Participant.Space` and `LlmException.DailyLimitReached`. WP1a added the
one missing branch in each file that broke (9 files outside :core and :testkit, listed in
`scratchpad/phase6/handoff-wp1a.md`, each marked `// Phase 6 (WP1a)`); their owners replace those stand-ins with the
real behaviour in their own package.

**How to use a contract suite.** Each suite is an abstract JUnit 4 class in `testkit/src/main/.../testkit/contract/`.
Subclass it in your module's tests and return your implementation from its factory; the in-memory reference passes it
in `testkit/src/test/.../Phase6ContractsTest.kt`. Room implementations run every suite of their port (WP1b). The suites
wait for flows in real time (`collectIn`, `awaitUntil`), so Room may deliver on its own threads.

**Security fixes of wave 1 (branch `p6/secfix`, after `review-wave1-security.md` F1-F14).** The contracts below changed;
each change is marked "(F<n>)". What each wave-2 package must now do or must not do is in
`scratchpad/phase6/handoff-secfix.md`. In short: a pick-up rule needs a read-only folder (F1); restore takes settings only
through `RestoreSettings.merge` (F2); `ProviderManager` is owner-only and agent-facing work gets `ProviderRechecks`,
the owner's answer path `OwnerKeyCheck` (F3); pruning usage is `UsageHousekeeping`, never today (F4); the backup format is
FROZEN v1 with a key check, NFKC passphrases and test vectors (F5-F7, section 4); stricter `AnswerCheck` and
`QuestionRules` (F8, F9); the app walk has more roots, `answer` is an owner action, and a production-graph walk exists
(F10); upkeep and pick-up get narrow ports (F11); `ObjectiveOptions.screenActionsAllowed`/`approvalTimeoutMinutes` (F12);
`BackupStores` (F13); IPv4-compatible and 6to4 literals refused (F14).

**Owner-only ports.** Both architecture tests (framework `operator/OperatorDependencyTest.kt`, app
`di/OperatorDependencyTest.kt`) now list `OwnerConversation`, `OwnerModelPrices`, `OwnerUsage`, `OwnerBackup`,
`OwnerRecovery`, `OwnerRoutines`, `OwnerPickUpRules`, `RoutineRepository` and `PickUpRuleRepository`, and treat the
method names `startOver` and `clearHistory` (besides `letAskAgain`) as owner-only actions. The framework test also scans
`:core` and fails when a new `Owner…` interface is missing from its list (`OwnerRequestQueue`, `OwnerResult`,
`OwnerDecision` are the agent-side exceptions). Making `SettingsRepository` owner-only and adding `update` to the owner
actions is WP1c's (framework) and WP1d's (app) step, after the `SettingsView` switch. (F3, F4, F10) Both tests also list
`ProviderManager`, `SecretVault`, `OwnerKeyCheck` and `UsageHousekeeping`, and treat `answer` taking a `RequestId` (compiled
`answer-…` with a `String` first parameter) as an owner-only action. `ProviderConnections` is the one agent-facing class
allowed to keep the vault (in a field the walks skip; it offers no method returning the vault or a key). The app walk
(`app/src/test/.../di/AgentFacingWalk.kt`, shared by `OperatorDependencyTest` over `TestApp` and
`ProductionGraphDependencyTest` over `createProductionPorts` with an unencrypted `DataLayer`) starts from the executor,
the broker, every tool, `agentChat`, `modelGateway` and `registryDirectory`; WP8 adds the wave-2 roots.

---

## 1. Map: what is new, where, who builds it, what checks it

| Id | Contract (main types) | File(s) | Implemented by | Rules | Suite / tests |
|---|---|---|---|---|---|
| K1 | `TaskSummary`, `ObjectiveRow`, `ObjectiveCursor`, `PlanProgress`, `PlanSteps`, `SummaryText`; `TaskRepository.observeSummaries/observeObjectiveRows/observeRecentByAgent/observeProgress`; `TaskRecord.retryAt` | core/tasks/TaskSummaries.kt, TaskPorts.kt, Tasks.kt | Room overrides (WP1b); defaults for everyone else | 16, R4 | `TaskSummaryContract`; core `SummariesTest` |
| K2 | `MessageHeader`; `MessageRepository.observeHeaders/observeRecentHeaders`; `Participant.Space`; `MessageType.GUIDANCE` | core/tasks/Messages.kt, TaskPorts.kt | WP1b (Room, column `to_space_id`, kind `space`); WP7 uses Space and GUIDANCE | 16, 24 | `MessageHeaderContract`; `SummariesTest` |
| K3 | `OwnerRequestKind.QUESTION`; `OwnerRequest.question/options/answer` + invariants; `OwnerDecision.Answer`; `OwnerRequests.answer`; `OwnerAttention`; `AnswerCheck`, `AnswerRisk`; `QuestionRules` | core/operator/OwnerRequests.kt, OwnerPorts.kt, AnswerCheck.kt | WP1b (columns), WP5 (`OwnerQuestions`, `OwnerServices.answer`, caps), WP11a (screen) | 2, 13, 14, 32, 33 | `OwnerQuestionContract`; core `Phase6QuestionsTest` |
| K4 | `OwnerConversation` (+ `Unavailable`, limits, `textProblem`) | core/tasks/OwnerConversation.kt | WP7 (`OwnerNotes`), WP1d (wiring), WP11a (UI) | 24 | `Phase6DefaultsTest` |
| K5 | `PreparedAction.beforeOwnerAsked/callEnded`; `Workspace.reserveCommand/releaseReservation`; `CommandSlot` | core/tools/Tool.kt, core/operator/Files.kt | WP5 (broker, executor, `ScriptsRunShell`), WP5/WP8 (`LocalWorkspace`) | 15 (D11) | `Phase6DefaultsTest` (slot) |
| K6 | `WebAddressPolicy.literalRefusal(host)` | core/operator/WebReader.kt | WP5 (`WebReadPage.assess`, `AddressGuard`) | D8 | core `WebAddressLiteralTest` |
| K7 | `ScreenOperator.holdSession/releaseSession/frontState`; `FrontState`; `ScreenControl.switchedOffByAndroid` | core/operator/Screen.kt | WP5 (device operator, `ScreenSessions`), WP15 (runner holds) | 12 (D4, D6, D7) | `Phase6DefaultsTest`; `FakeScreenOperator` |
| K8 | `AgentChat.sendAgain/recoverInterruptedTurns`; `ConversationRepository.markTurnStarted/markTurnEnded/interruptedTurns/observeLatestSeq/entriesAfter/entriesBefore`; `InterruptedTurn`; `CallContext.conversationId` | core/chat/Conversation.kt, core/llm/LlmClient.kt | WP1b (Room, `turn_started_at`), WP7 (chat), WP15 (runner passes the id) | R3, P2 | `ConversationTurnContract` |
| K9 | `MemoryRepository.observeCount/observeCountsBySpace` | core/memory/MemoryRepository.kt | WP1b (COUNT queries) | 16 | `MemoryCountContract` |
| K10 | `ActivityFilter.text`; `ActivityCursor`; `ActivityLog.observePage`; `activityPageOf` | core/activity/ActivityEvent.kt | WP1b (keyset page, escaped LIKE), WP10/WP11 (UI) | D41, Tier B search | `ActivityPageContract`; `SummariesTest` |
| K11 | `ModelCallSample`, `UsageTotals`, `UsageRange`, `UsageLedger`, `UsageHousekeeping` (F4), `UsageReports`, `OwnerUsage`, `WorkKind`, `WorkUsage`, `ModelPrice`, `ModelPrices`, `OwnerModelPrices`, `CostEstimate`, `TokenCounts`, `DailyLimits`, `DailyLimitKind`, `ErrorClass`; `LlmException.DailyLimitReached` | core/usage/Usage.kt, core/llm/LlmClient.kt | WP1b (Room stores), WP4 (journal, limiter), WP7 (pause), WP10 (screens) | 6, 7, 29, Q2 | `UsageLedgerContract`, `ModelPricesContract`; core `UsageModelTest` |
| K12 | `ExitKind`, `ExitRecord`, `ExitHistory`, `BackgroundHealth`, `BackgroundState`, `StandbyBucket`, `StorageWatch`, `StorageLevel`, `NetworkStatus`, `TimedWaits` | core/health/AppHealth.kt | WP2 (device/health), WP4 (`AndroidNetworkStatus`), WP7/WP4 (`timedWaits`) | 1, 5, 31, R1, R8 | `Phase6DefaultsTest`; testkit `HealthFakes` |
| K13 | `OwnerBackup`, `BackupResult`, `BackupProblem`, `BackupSummary`, `BackupCounts`, `SettingChange`, `SaveReport`, `RestoreReport`, `BackupProgress`; `Passphrases`; `MaintenanceGate`, `LocalMaintenanceGate`; `BackupFormat`; (F2, F5-F7) `BackupKeys`, `BackupHeader`, `BackupPreamble`, `BackupChunks`, `BackupSettings`, `RestoreSettings`, `RestoredSettings`, `SettingKeptBack`; (F13) data `BackupStores` | core/backup/Backup.kt, BackupCrypto.kt, RestoreSettings.kt; data/DataLayer.kt | WP3 (`RoomOwnerBackup`, records), WP7 (gate checks), WP10 (screens) | 8, 21, 27, S24 | core `BackupContractsTest`, `BackupCryptoTest`, `RestoreSettingsTest`; app `BackupWiringTest`; WP3's own suite (section 4) |
| K14 | `Orchestrator.recoverInterrupted(cause, resumeAllowed)`, `Orchestrator.timedWaits`; `OwnerRecovery`, `DatabaseState`, `RecoveryResult` | core/tasks/TaskPorts.kt, core/health/Recovery.kt | WP7 (orchestrator), WP2 (`FileOwnerRecovery`), WP10 (screen) | 9, 10, 30, R1, R5 | `Phase6DefaultsTest`; `FakeOwnerRecovery` |
| K15 | `UnavailableReason.isTransient`; rule 25 in `resolveAvailability`; `ProviderRechecks` (agent side), `OwnerKeyCheck` (owner-only), split from `ProviderManager` (F3) | core/agents/AgentAvailability.kt, core/llm/ProviderRechecks.kt | core (rule 25, done); WP4 (rechecks, key check) | 25, 32, D28 | core `NoSilentMoveTest`, `Phase6DefaultsTest`; both architecture tests |
| K16 | `FrameworkSettings` Phase 6 fields + `dailyLimits`; `SettingsBounds`; `SettingsView`; `SettingsRepository : SettingsView`; `ReadOnlySettings` | core/settings/FrameworkSettings.kt | WP1b (codec), WP1c (holders), WP1d (wiring, app test views) | 3, Q2, Q3 | `SettingsRoundTripContract`; `Phase6DefaultsTest` |
| K17 | `ConsequentialControls.PROTECTED_HANDLER_ACTIONS` += 4 pickers; `QueriedIntent` entries; manifest `<queries>` | core/operator/ConsequentialControls.kt, device/platform/QueriedIntent.kt, device/src/main/AndroidManifest.xml | WP1a (done) | S26 | device `QueriesManifestTest`, `PickerHandlersTest`, `ScreenPlatformTest` |
| A-R | Routines: `RoutineId`, `RoutineRunId`, `RoutineSchedule` (`Daily`, `EveryHours`, `nextAfter`), `RoutineLimits`, `Routine` (+ `objectiveOptions()`, F12), `RoutineDraft`, `RoutineRun`, `RoutineRunOutcome`, `RoutineRepository`, `OwnerRoutines`; `ObjectiveOptions.routineId`, `screenActionsAllowed`, `approvalTimeoutMinutes` (F12); settings `routinesEnabled` | core/routines/Routines.kt, core/Ids.kt, core/tasks/Tasks.kt | WP1b (`routines`, `routine_runs`), WP17 (runner, alarms, owner port), WP10 (screen) | 1, 6, Appendix A | `RoutineRepositoryContract`, `OwnerRoutinesContract`; core `RoutineModelTest` |
| A-P | Folder pick-up: `PickUpRuleId`, `PickUpLimits`, `PickUpRule`, `PickedUpFile`, `PickUpRuleRepository`, `OwnerPickUpRules`, `PickUpSafety` and `PickUpFolders` (F1, F11); settings `folderPickUpEnabled` | core/pickup/PickUp.kt | WP1b (rule and import tables), WP17 (runner, owner port), WP10 (screen) | 1, 34-like, Appendix A | `PickUpRuleRepositoryContract`, `OwnerPickUpRulesContract`; `RoutineModelTest` |

New in `:testkit` (main): `InMemoryUsageStore` (ledger + reports + owner usage), `InMemoryModelPrices`,
`InMemoryRoutineRepository`, `ReferenceOwnerRoutines`, `InMemoryPickUpRuleRepository`, `ReferenceOwnerPickUpRules`,
`MutableTimedWaits`, `FakeNetworkStatus`, `FakeExitHistory`, `FakeBackgroundHealth`, `FakeStorageWatch`,
`FakeOwnerRecovery`; `InMemoryConversationRepository` keeps turn marks; `InMemoryOwnerRequestRepository` answers
questions; `FakeScreenOperator` keeps holds (`holds`, `releasedHolds`; Stop clears them), can hide the front window
(`frontHidden`) and reports `switchedOffByAndroid` through `asControl()`.

---

## 2. Contracts one by one

### K1. Task summaries (rule 16; R4)

Purpose: lists, the dashboard, notifications and the Team view read summaries, never whole task rows.

- `TaskSummary.of(task)`: every field a list needs. `statusDetailShort` = `statusDetail ?: error`, cut to 200 code points
  (`SummaryText.cut`, which is what SQLite's `substr(x, 1, 200)` returns, never splitting an emoji). `resultChars` =
  code points of `result` (0 without one) = SQLite `length(result)`, which is what the schema-6 `result_chars` column and
  its backfill `UPDATE` hold. `sortOrder` is `TaskRecord.order`.
- `PlanSteps.isStep`: a WORK or SYNTHESIS task whose parent is the objective. The app's `ObjectiveProgress`
  (app/work/ObjectiveProgress.kt) uses the same rule; WP9/WP10 may switch it to `PlanSteps`.
- `ObjectiveRow.of(tree)`: planned and completed steps; participants = the objective's assignee first, then every other
  assignee once, by task creation (created time, then id).
- `observeObjectiveRows(limit, olderThan)`: newest first exactly as `observeObjectives` (created time descending, id
  ascending); `olderThan` is the last row of the previous page (`ObjectiveCursor.after(row)`).
- `observeRecentByAgent(agentId, limit)`: every kind (objectives count for the Director), updated time descending, id ascending.
- `observeProgress(rootIds)`: per existing objective, `PlanProgress(total, done, running = RUNNING or IN_REVIEW,
  waiting = isWaiting, failed = FAILED or TIMED_OUT)`; ids without an objective are left out.
- Every flow emits at once, re-emits only on a change of what it emits (`distinctUntilChanged`).
- `TaskRecord.retryAt: Long? = null` (declared last): the driver skips the task until then (WP7).
- Room (WP1b) overrides all four with projections and one aggregate query; the defaults (derived from
  `observeTree`/`observeObjectives`) stay for fakes. `TaskSummaryContract` compares against `TaskSummary.of` etc.

### K2. Message headers (rule 16; rule 24)

- `MessageHeader.of(message)`: all fields but the body; `preview` = first 120 code points of the body.
- `observeHeaders(rootId, limit)`: the newest `limit` messages of the objective, in sending order (time, then id).
  `observeRecentHeaders(limit)`: newest first across objectives, as `observeRecent`.
- `Participant.Space(spaceId)`, serial name `"space"`: memory shared into a space (WP7, WorkSteps.kt:88-91). Room stores
  kind `space` and `to_space_id` (WP1b); older shares stay `System`. Until WP1b, `MessageMappers` refuses to store one.
- `MessageType.GUIDANCE` (last): only `OwnerNotes` creates it, from `Participant.Owner` (rule 24, source scan in WP7).

### K3. Questions to the owner (rules 2, 13, 14, 32, 33; Tier B)

- `OwnerRequestKind.QUESTION` (last). `OwnerRequest` gains `question`, `options`, `answer` (last, defaults null/empty/null).
- Invariants (constructor): only a QUESTION has `question` (and it always has it); only a QUESTION has options or an
  answer; a QUESTION is SAFE, has `CapabilityConstraint.None`, no scope, never DENIED; question 1..1,000 chars; at most 4
  options, each 1..120 chars, distinct, none mixing alphabets in one word (`QuestionRules.mixesAlphabets`: "Cоnfirm" with a
  Cyrillic "о"), none using an approval word (`QuestionRules.approvalWordIn` on the option's `skeleton`: NFKC, Cyrillic and
  Greek look-alikes read as their Latin letter, accents removed, lower case; F9: allow, approve, permit/permission, grant,
  always, once, deny, confirm, proceed, ok/okay, accept, agree, authorise/authorize, consent, pay/paid, buy/bought,
  purchase, install, uninstall, send/sent, delete/deletion, erase, and (second review) order, transfer, wire, remove,
  wipe, trash, bin, overwrite, submit/submission, post, publish, share, email/mail, reply, message, text, call, subscribe,
  book, sign, renew, continue, with their forms; the phrases "yes do it", "do it", "go ahead", "go for it", "go on",
  "carry on", "let's go", "check out"/"checkout" and "top up"; "Yes" and "No" alone stay allowed); an answer only when
  APPROVED, 1..2,000 chars; APPROVED without an answer = "decide yourself".
- `OwnerDecision.Answer(text: String?)` (sub-interface `ForQuestion`, `note` always null). `problemWith`: a QUESTION takes
  only `Answer` (Approve, Deny, Grant are NotAccepted), and `Answer` fits only a QUESTION; blank or over-long text is
  NotAccepted. `OwnerRequestDecisions.decide(id, Answer, …)` closes it APPROVED with the answer through the same
  compare-and-set, writes no grant (`Decided.grant == null`).
- Rule 33 is structural: `grantFor` requires PERMISSION; `holdsBackAt`, `lastRefusal`, `letAskAgain` and
  `countAskedAgain` concern refused PERMISSIONs only, and a QUESTION is never DENIED. `OwnerQuestionContract` checks
  each, plus "answering a question leaves every other request as it was" (rule 2) and one winner among 20 concurrent answers.
- `OwnerRequests.answer(id, text, sendAnyway = false): OwnerResult<Unit>`: owner-only (an owner-only action in both
  architecture tests, F10), default refuses (`QUESTIONS_UNAVAILABLE`). WP5 implements it in `OwnerServices` with
  `AnswerCheck` and `OwnerKeyCheck.matchesStoredKey` (F3; never `ProviderManager`).
- `AnswerCheck.classify(question, answer)` (F8): KEY for a known prefix matched **without regard to case** at a word start
  and followed by at least 12 key characters (`sk-ant-`, `sk-or-`, `sk-proj-`, `sk-`, `xai-`, `AIza`, `ya29.`, `gsk_`,
  `ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`, `github_pat_`, `glpat-`, `sk_live_`, `sk_test_`, `rk_live_`, `rk_test_`, `xoxb-`,
  `xoxp-`, `xoxa-`, `xoxr-`, `xapp-`, `hf_`) or by a key typed in groups (second review: at least four groups of key
  characters split by one or two spaces, all but the first and last of one length of 3 or more, 12 key characters in
  all: "sk-proj-abcd efgh ijkl …"), an AWS access key id (`AKIA`/`ASIA` + 16 capitals or digits, exact case, also with its
  spaces removed), or any run of 32+ base64/hex characters holding a letter and a digit (as written; not with spaces
  removed, which would make any sentence with a digit one run); the answer is NFKC'd first; CODE for an answer that is
  **nothing but** 4-8 digits (figures of any script, number words; separators `[\s.,_-]` between them ignored) **whatever
  the question says**, or that holds such a run (any one to three non-letters, or an "x", between digits) or a 5-8
  character letter-and-digit code with at least two digits ("K7Q2M9") when the question **or the answer** mentions a code
  word (code, SMS, text message, message, verif…, PIN, password, passcode, OTP, one-time/one time, digit, number, 2FA,
  two-factor, authenticat…, security, login/log in, sign in, bank, confirm: a pasted "Your verification code is 482913");
  a run of more than 8 digits (a phone number) is not a code; KEY wins over CODE. Prices, years and dates the owner
  types under a code question need the second tap.
- `OwnerAttention`: 5 open agent-initiated requests in the app, 3 per objective; 12 pending call requests, 6 per
  objective; questions 1 open per task, 2 per task, 4 per objective; default time-out 20 minutes.
  `isAgentInitiated(request)` = QUESTION or toolId null. WP5 checks them under one lock (rule 13 "concurrent asks").
- `QuestionRules.nearlySame(a, b)`: same words ignoring case and punctuation, or 80% of the longer question's words shared.
- WP1a stand-ins: the broker never raises a QUESTION (`error`), `OwnerServices` logs "Owner answered a question",
  the approvals UI offers no buttons for one; WP5 and WP11a replace these.

### K4. Owner notes (rule 24; Tier B)

`OwnerConversation.addNote(rootId, toTaskId, text): OwnerResult<MessageId>`; refused when the objective is missing or
finished, the task is not its unfinished task, the text is blank or over 1,000 chars (`textProblem`), or 10 notes exist.
`OwnerConversation.Unavailable` refuses everything (the AppGraph default until WP7, and when C2 is switched off).

### K5. One shell command at a time (rule 15, D11)

- `PreparedAction.beforeOwnerAsked(): String?` (suspend, default null): the broker calls it once per authorised call,
  after the grant check, before it raises the APPROVAL (or before the call runs when none is needed). A sentence = refuse,
  nothing asked or run. `callEnded()` (not suspend, must not throw): the executor calls it in a `finally`.
- `Workspace.reserveCommand(token): String?`: null = the slot is now `token`'s (free, or already its); else
  `Workspace.COMMAND_SLOT_TAKEN`. Holds back other reservations only, never file changes. `releaseReservation(token)`
  frees it if `token` holds it. `runCommand` does not look at reservations. Default: refuse only while
  `commandRunning()`. `CommandSlot(running)` is the reference a workspace can embed.

### K6. Literal web addresses (D8)

`WebAddressPolicy.literalRefusal(host)`: no lookup ever (an IPv6 literal must start with a hex digit or a colon, as
Java parses it without DNS only then). Refused: empty; `localhost`, `*.localhost`; `*.local`, `*.internal`,
`*.home.arpa`, `*.lan`; dotless names; literal addresses `refusal()` refuses (mapped, NAT64, and (F14) IPv4-compatible
`::a.b.c.d` (::/96) and 6to4 `2002:AABB:CCDD::` forms included, and (second review) the IPv4-translated
`::ffff:0:a.b.c.d`, ISATAP `…:0:5efe:a.b.c.d` / `…:200:5efe:a.b.c.d` and Teredo `2001:0::/32` (server or inverted client
address) forms, and the whole local-use NAT64 prefix `64:ff9b:1::/48`, in `literalRefusal` and in `refusal(InetAddress)`); every
number not written as a plain `a.b.c.d` (decimal, octal, hex, shortened, leading zeros) with `NOT_A_USUAL_ADDRESS`.
Case, brackets and trailing dots ignored. WP5 calls it in `WebReadPage.assess` before authorisation and in `AddressGuard`.

### K7. Screen holds, hidden windows, Android switching control off (rule 12; D4, D6, D7)

- `holdSession(holdId, agentName, packageName)` / `releaseSession(holdId)`: holds counted per id (the request id);
  `endSession(agentName)` never ends a held session; the idle time-out does not either; Stop ends every hold; same id =
  same hold. Defaults no-op. WP15 calls them around a waiting screen action; WP5 implements them on the device.
- `frontState()`: `App(window)`, `Hidden` (a window Android does not let the app read), `None`. Default from `activeWindow()`.
- `ScreenControl.switchedOffByAndroid: StateFlow<Boolean>`, default a read-only `false`.

### K8. Cut-off chat turns and long chats (R3, P2)

- `markTurnStarted(id, at)` (replaces an earlier mark; unknown conversation: nothing), `markTurnEnded(id)`,
  `interruptedTurns()` (oldest mark first, then id). Only these change the mark: `upsertConversation` keeps it (Room must
  not reset `turn_started_at` on upsert), `deleteConversation`/`deleteByAgent` remove it. Defaults are no-ops (only so
  older fakes compile).
- `observeLatestSeq(id)` (null while empty), `entriesAfter(id, seq)`, `entriesBefore(id, seq, limit)` (the newest
  `limit` below `seq`, in order). Defaults read every entry; Room uses `seq` queries.
- `AgentChat.sendAgain(id)` (default: Failed "not available"), `recoverInterruptedTurns(): Int` (default 0); the text
  WP7 stores is in the KDoc. `CallContext.conversationId` (last field) feeds the CHAT work row (K11).

### K9. Memory counts (rule 16)

`observeCount(filter)` = number of memories `filter` covers; `observeCountsBySpace()` = memories per shared space,
expired ones included, empty spaces absent.

### K10. Activity text search and pages (D41; Tier B search)

- `ActivityFilter.text`: title or detail contains it, case ignored (ASCII at least; SQLite LIKE folds ASCII only);
  `%`, `_` and `\` mean themselves (Room must `ESCAPE`); blank = no filter.
- `ActivityCursor(at, id)` = the last event shown; `observePage(filter, before, limit)` = the events after it in the
  log's order (newest first; same time: latest recorded first); if that event is gone, the events older than `at`.
  `activityPageOf` is the reference cut.

### K11. Usage, cost, daily limits (rules 6, 7, 29; Q2)

- `ModelCallSample` (numbers, ids and labels only): `errorClass` exactly when FAILED; `answerMs` exactly when OK;
  `ttftMs` only when OK; non-negative counts; cached tokens are part of input tokens. `hourStart` = UTC hour.
  `workItems`: TASK (`taskId`) and OBJECTIVE (`rootTaskId`) when the call belongs to an objective, else CHAT
  (`conversationId`), else none.
- `UsageTotals`: the `usage_hours` counters (calls, ok, failed, cancelled, blocked, retries, substituted, truncated,
  noUsage = answered calls without token counts, rateLimitedWaits, `errors` by `ErrorClass` (non-zero only), tokens,
  answerMsSum (answered calls), ttftMsSum/ttftCount, bytesSent); `plus`, `of(sample)`, averages.
- `ErrorClass.of(LlmException)` maps to the `err_*` columns (DailyLimitReached, BlockedByPolicy, MalformedResponse → OTHER;
  InvalidRequest, ModelNotFound, Unsupported → INVALID).
- `UsageRange(fromMs, untilMs)`: hours whose start is in [from, until). `day/month/lastDays(now, zone)` cut the owner's
  local day/month at the start of the UTC hour that holds local midnight (exact for whole-hour zones; up to 59 minutes
  early for half/quarter-hour zones). Room and the in-memory store therefore sum exactly the same rows.
- `UsageLedger.record(sample)`: one transaction: the hour row (hour, model, agent or "", source; labels and locality
  become the sample's) and the work rows (per item and model; `updatedAt` = the latest call time). `totalsSince(fromMs)`;
  `None`. Agent-facing code may hold it (the gateway's journal and limiter): it adds and sums, and deletes nothing (F4).
- `UsageHousekeeping` (F4; owner-only in both architecture tests; held only by the upkeep side, `UpkeepPorts`):
  `pruneHours(beforeMs)` deletes hour rows before `min(beforeMs, UsageRange.day(now, zone).fromMs)`, so today's rows (the
  daily limits' seed) never go whatever it is asked (work rows kept); `pruneOrphanedWork()` (Room: one statement; in-memory:
  0); `None`. Room: `RoomUsageHousekeeping(database, clock, zone)`, `DataLayer.usageHousekeeping`. `UsageLedgerContract`
  pins the clamp ("pruning never deletes today's usage"); `UsageStores` has a fourth member `housekeeping`.
- `UsageReports`: `totals`, `observeTotals`, `byModel`, `observeByModel`, `byAgent`, `byProvider` (= reliability and
  speed per provider), `workUsage(kind, id)`, `observeWorkUsage`; lists by calls descending, then id (no agent last);
  labels from the newest hour; `None`.
- `OwnerUsage.clearHistory()`: deletes hour rows before today's range and work rows last updated before it; today stays
  (S29). Returns hour rows deleted.
- `ModelPrice` (micros per million tokens, `cachedInputPerMTokMicros` null = input price, currency label ≤ 8 chars,
  ≤ 10,000 per Mtok, `check` gives the owner's sentence); `ModelPrices` (read, by model id; `None`);
  `OwnerModelPrices.set` (stamps `updatedAt` = now) / `remove`.
- `CostEstimate.of(tokens, price)`: `(input − cached) × in + cached × cachedOrIn + output × out`, divided by 1,000,000,
  rounded half up, saturating; null without a price; `of(workRows, prices)` adds priced rows only (null if none).
  `DISCLAIMER` is the rule-29 sentence.
- `DailyLimits(maxCalls, maxTokens, maxSpendMicros)` (null = no limit), `DEFAULT` = (200, 500,000, 1,000,000 = US$1.00)
  — the owner's Q2 — and `FrameworkSettings.dailyLimits`; `reachedBy(calls, tokens, spend)` (reached at the limit: the
  200th call goes, the 201st does not); `stricter(other)` for restore (rule 8); `sentence(kind)` is the message of
  `LlmException.DailyLimitReached` and the chat; `PAUSED_TEXT`; `money(micros, currency)`; Settings ranges.

### K12. App health (rules 1, 5, 31; R1, R8)

`ExitKind` (each with an app-written `cause` phrase for "Interrupted: …"), `ExitRecord` (numbers only; Android's
description is never stored), `ExitHistory.newSince(ms)` (main process only, oldest first, each once; `None` on 9-10),
`BackgroundState` (+ `standbyWarning` for RARE/RESTRICTED, `allClear`, `UNKNOWN`), `BackgroundHealth` (`state`,
`refresh`, `None`), `StorageLevel.of(bytes)` (LOW < 500 MB, CRITICAL < 100 MB), `StorageWatch` (`level`, `check`,
`None`), `NetworkStatus` (`available`, `AlwaysOn`), `TimedWaits` (`nextDueAt`, `wake`, `None`,
`KEEP_AWAKE_WITHIN_MS` = 2 minutes).

### K13. Backup, passphrases, maintenance gate (rules 8, 21, 27; S24; Tier B)

- `OwnerBackup.save/inspect/restore` return `BackupResult` (`Done` or `Failed(BackupProblem)`); every problem has a plain
  sentence. Restore runs inside `MaintenanceGate.runExclusive`, into an empty app only, all or nothing.
- `Passphrases.generate(random)`: 20 symbols of Crockford base32 (100 bits), grouped `XXXX-XXXX-XXXX-XXXX-XXXX`;
  `normalise` (F6: NFKC, lower then upper case without locale rules, NFKC again, whitespace, invisible format characters
  (Cf) and dashes (Pd, U+2043, U+2212) removed, O→0, I/L→1; the key is derived from its UTF-8 bytes); `isGeneratedForm`
  (formatting only); `check(own)` (F7: ≥ 16 normalised characters or ≥ 4 real words; accepted with ≥ 4 different real
  words (`realWords`: ≥ 4 letters, ≥ 3 different letters, not a common password, keyboard walk or alphabet run; second
  review: words count as different only when, accents removed, they differ in at least two letters) **and**
  `strengthBits` ≥ 55 (`MIN_WORDS_BITS`), or `strengthBits` ≥ 60; refused when mostly a common password or keyboard walk
  (rows and columns, "1qaz2wsx3edc4rfv…"), or over 256 characters; `strengthBits` removes accents and counts a year
  1900-2099, a digit with the symbol above it ("1!") and a repeated stretch as one symbol each);
  `problemForSave(input, generatedShown)` is the only gate of a save (second review, NEW-1: `generatedShown` counts only
  when it is exactly in `generate()`'s grouped form). `OwnerBackup.save(passphrase, generatedShown, open, progress)` (F7:
  the screen passes the passphrase it generated and showed, or null). A character-class estimate cannot tell English
  words from random letters ("Seventeen chars!!" passes): the generated passphrase stays the default (rule 27).
- `MaintenanceGate.busy`, `runExclusive(block): OwnerResult<T>` (refused while another runs; `busy` false again however
  the block ends). `LocalMaintenanceGate` is the process's one gate (WP1d wires it; WP7 refuses `startObjective` and
  `send` while busy with `MaintenanceGate.RESTORING`).
- The file format is section 4 of this guide (FROZEN v1); `BackupFormat` holds its constants, `BackupKeys`,
  `BackupHeader`, `BackupPreamble` and `BackupChunks` implement it, `BackupSettings`/`RestoreSettings` its settings (F2).

### K14. Recovery (rules 9, 10, 30)

- `Orchestrator.recoverInterrupted(cause, resumeAllowed)`: default calls the old one (ignores both); WP7 overrides it
  and makes the no-argument form call `(null, true)`. `Orchestrator.timedWaits` default `None`.
- `DatabaseState` (+ `ownerText`, `needsRecovery`), `RecoveryResult` (`Done(setAsideFolder)`, `Refused(reason)`),
  `OwnerRecovery.state()/startOver()`, `CONFIRM_WORD` "START OVER" and `confirms(typed)` (case and spaces ignored).

### K15. No silent move farther from the phone (rule 25; D28; rule 32)

- `UnavailableReason.isTransient` = PROVIDER_OFFLINE only.
- `resolveAvailability`: when the primary's problem is transient, a fallback is used only if its provider's
  `effectiveLocality` is no farther than the primary provider's (ON_DEVICE < LOCAL_NETWORK < CLOUD); otherwise the
  primary's reason is returned and the work waits. Owner-made reasons keep the fallback order. Done in WP1a; no
  framework test encoded the old behaviour (the framework suite stays green).
- (F3) Split out of `ProviderManager`, which is now owner-only (it adds, repoints and removes providers and replaces their
  keys): `ProviderRechecks` (core/llm/ProviderRechecks.kt; agent side): `recheckIfStale(id, olderThanMs): ConnectionStatus?`,
  `timedWaits`, `None`; and `OwnerKeyCheck` (owner-only): `matchesStoredKey(text)` (suspend, constant time), `None` (false).
  WP4 builds `DefaultProviderRechecks` (holding neither `ProviderManager` nor the vault) and the key check over the vault
  in `ModelsAssembly` (`providerRechecks`, `ownerKeyCheck`, now `None`); `TeamAssembly` takes `providerRechecks`,
  `OperatorAssembly` takes `ownerKeyCheck`.
- (F3, second review) The registry's stores are owner-only in both architecture tests: `AgentRepository`,
  `ProviderRepository`, `ModelRepository`, `AiAppRepository` (core/registry/Repositories.kt). Agent-facing code declares
  the reading interfaces `AgentDirectory` (or `AgentStatsLog` = reads + `updateStats`, for the runner and the
  orchestrator), `ProviderDirectory`, `ModelDirectory`, `AiAppDirectory`, and is wired with `ReadOnlyRegistry.agents/
  providers/models/apps(store)` (separate objects; the app walks allow a full store only inside them). The memory service
  keeps a view plus `SpaceAccessRevoker` (`ReadOnlyRegistry.spaceRevoker`: takes a deleted space away from agents, nothing
  else). `ProviderConnections` is the one agent-facing holder of `ProviderRepository` (and of the vault): it reads a
  record with its key and stores a status (`storeStatus`), never changes a record; it uses a key only when the record's
  alias is one of its own id's (`KeyAliases.belongsTo`: `provider/<id>` or `provider/<id>/<n>`; an id with "/" has none).
  `ProviderLease.exposes`/`redact` are `internal` to `:framework` (the gateway's failure mapping); a `ProviderLease` held
  anywhere but `ProviderConnections` and `DefaultModelGateway` fails both walks. The framework walk reads a field's generic
  type (`Lazy<X>`, `() -> X`, `List<X>`, `StateFlow<X>`, `AtomicReference<X>`) and fails on a field typed `Any` (locks
  aside); the app walk reads through `AtomicReference`, `java.lang.ref.Reference`, `StateFlow.value`, a completed
  `Deferred` and `Optional`, and fails on Room's database, open helper or a DAO held by anything but a Room store
  (`com.aicontrolcenter.data.repository.…`) or `DataLayer.RoomTransactions` (F4/F10, second review; `DataLayer.database`
  is `internal`).

### K16. Settings (rule 3; Q2; Q3; Appendix A)

New fields (last, defaults): `keepAwakeWhileAgentsWork = true`, `notifyWhenWorkEnds = true`, `maxModelCallsPerDay = 200`,
`maxTokensPerDay = 500_000`, `maxSpendPerDayMicros: Long? = 1_000_000`, `agentQuestionsEnabled = true`,
`ownerQuestionTimeoutMinutes = 20` (`SettingsBounds.QUESTION_TIMEOUT_MINUTES` 5..240), `usageRetentionDays = 730`
(90/365/730/0), `calmMotion = false`, `routinesEnabled = false`, `folderPickUpEnabled = false`; derived `dailyLimits`.
`SettingsView` (read), `SettingsRepository : SettingsView` (+ `update`), `ReadOnlySettings(source)` (private field,
no writer, not castable). `SettingsRoundTripContract` makes the DataStore codec store every new field (WP1b).

### K17. Protected system pickers (S26)

`PROTECTED_HANDLER_ACTIONS` += `android.intent.action.OPEN_DOCUMENT`, `OPEN_DOCUMENT_TREE`, `CREATE_DOCUMENT`,
`android.provider.action.PICK_IMAGES`. `QueriedIntent` gains `OPEN_DOCUMENT`, `CREATE_DOCUMENT`, `PICK_IMAGES` with
type `*/*` (an intent of every type matches any typed filter) and `OPEN_DOCUMENT_TREE` without one (DocumentsUI's tree
filter lists no type); the manifest `<queries>` lists the same four. `QueriesManifestTest` (its spelled-out list now has
the four) and the new `PickerHandlersTest` (DocumentsUI, an OEM folder picker and the photo picker are resolved and
protected; a plain viewer is not) check it.

### Appendix A contracts: routines

- `RoutineSchedule.Daily(minuteOfDay, days)` / `EveryHours(hours 1..12, startMinuteOfDay, days)` (restarts each day, so
  runs are never under an hour apart); `minutesOn(date)`; `nextAfter(afterMs, zone)`: first due time strictly after, in
  local time; a skipped local time runs when the hour has been skipped (02:30 → 03:30), a doubled one the first time.
  Serialisable (`daily`, `every_hours`) for storage.
- `Routine` (title ≤ 80, objective ≤ 4,000, approval time-out 1..240 or null, `allowScreenActions = false` by default,
  `nextRunAt`, `consecutiveFailures`, `pausedNote` only when off); `RoutineDraft.problem()`.
- `RoutineRunOutcome` (STARTED, SKIPPED_STILL_RUNNING, SKIPPED_DAILY_LIMIT, SKIPPED_LOCAL_ONLY,
  SKIPPED_DIRECTOR_UNAVAILABLE, WAITING_FOR_TAP, MISSED; only SKIPPED_DIRECTOR_UNAVAILABLE `countsAsFailure`; a started
  objective that ends FAILED or TIMED_OUT also counts, decided by WP17). `RoutineRun` (startedAt and objectiveId together,
  only for STARTED).
- `RoutineRepository` (owner-capable store): routines oldest first; runs newest first (due desc, id desc); `update`,
  `updateRun` atomic; `appendRun` ignores a repeated id and refuses an unknown routine; `delete` removes runs;
  `pruneRuns(beforeMs)`.
- `OwnerRoutines`: `create` (on, due by `nextAfter(now)`, refused when invalid or 10 exist, the cap holds under
  concurrency), `change` (next due recomputed when on), `setEnabled` (on: next due from now, failures and pause note
  cleared; off: no next due), `delete`, `observeRuns`. `RoutineLimits` holds the Appendix A bounds.
- `ObjectiveOptions.routineId` records "Started by routine …"; the master switch is `FrameworkSettings.routinesEnabled`.
- (F12) `ObjectiveOptions.screenActionsAllowed` (default `routineId == null`, second review: options that name a routine
  but were not built by `Routine.objectiveOptions()`, or stored without the key, allow no screens; objectives the owner
  starts by hand allow them) and `approvalTimeoutMinutes` (null = the owner's usual). `Routine.objectiveOptions()` = the routine's options with
  `routineId`, `screenActionsAllowed = allowScreenActions` and its approval time-out: WP17 starts every run with exactly
  these. The broker (`PermissionBroker.authorize`) reads the objective's options once per call and refuses `screen.read`
  and `screen.operate` tools with `ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED` when false (before anything is asked),
  and when the call names an objective whose record cannot be read (second review: fail closed), and raises requests
  with the objective's time-out (brought into 1..240). This makes the broker stricter, never laxer, than before (the owner
  decision's "broker unchanged" is kept for everything else). WP5: `DefaultCapabilityRequester` refuses a
  `screen.read`/`screen.operate` permission request in an objective whose `screenActionsAllowed` is false, with the same
  sentence. Tests: framework `RoutineObjectiveBrokerTest`, data `StoredNamesGoldenTest` (the stored default).
- Not decided here (WP17): alarms, the runner's checks at run time. Constraints (F11, F12): `RoutineAlarmReceiver` and
  `BootReceiver` only signal (they never name the application, the graph or the data layer: `UpkeepIsolationTest`); the
  alarm starts the foreground service with `RoutinesWiring.ACTION_RUN_DUE_ROUTINES`, which calls `RoutinesAssembly.runDue()`;
  "Tap to start" opens the app (no new extra, rule 23) where the owner confirms; no notification action starts work.

### Appendix A contracts: folder pick-up

- `PickUpLimits`: `text/…` only (`accepts`, parameters and case ignored), 5 MB a day, 1 MB per file, 10 rules.
- `PickUpRule(folderId, spaceId, enabled, lastRunAt, lastResult, folderReadOnlySince)`: files directly in the folder (not
  sub-folders); `folderReadOnlySince` (F1, second review) is the folder's `SharedFolder.readOnlySince` when the rule was
  made or last switched on.
- `PickedUpFile(ruleId, contentSha256 (64 lower-case hex), documentName, sizeBytes, importedAt, memoryId, seenOnly)`:
  `seenOnly` (F1, second review) marks a file that was already in the folder when the rule was made or switched on: never
  imported by a run, not counted by `bytesImportedSince`; only the owner, ticking it on a list, may have it imported (WP17).
- `SharedFolder.readOnlySince` (schema 6 column `shared_folders.read_only_since`): set by `OwnerFolders.setReadOnly(id,
  true)` to the time, cleared by `false`; switching on a folder that is already read-only keeps the time.
- `PickUpRuleRepository`: one rule per folder (upsert and update refuse a second); `recordImport` atomic and
  de-duplicating per rule (false if the hash is there); `bytesImportedSince(fromMs)` over all rules; `delete` removes
  the import records (memories stay).
- `OwnerPickUpRules`: `create(folderId, spaceId)` (both must exist; (F1) the folder must be read-only for agents, else
  refused with `PickUpSafety.FOLDER_NOT_READ_ONLY`, and (second review) no other shared folder agents may change may be
  the same place, inside it or around it (`FolderTrees.overlap`; unknown counts), else `FOLDER_OVERLAPS_WRITABLE`; one per
  folder; cap 10 under concurrency), `changeSpace`, `setEnabled` ((F1) switching on refused the same ways; switching off
  always works), `delete`. Making a rule and switching one on store it off, record the files in the folder as seen
  (`PickUpSafety.baseline`; `FOLDER_UNREADABLE` when it cannot be read), check the folder again, then switch it on under
  the folder's `readOnlySince`. Master switch: `FrameworkSettings.folderPickUpEnabled`.
- (F1, second review) `DefaultOwnerFolders` (framework) gets the pick-up rules: `setReadOnly(id, false)` and `add` (a new
  folder is writable) switch off, in the same locked step, every rule whose folder is that folder or overlaps it, with
  `RUN_FOLDER_NOT_READ_ONLY` / `RUN_FOLDER_OVERLAPS_WRITABLE` as its last result. The device's SAF gateway refuses any
  change in a folder that is the same place as, inside or around another stored read-only folder.
- (F1) `PickUpSafety`: `folderProblem(folder, allFolders)`; `runProblem(rule, folder, allFolders, masterSwitchOn)` — the
  runner reads the rule and every folder afresh at **every run**, and again after reading the files and before storing
  anything, and imports nothing when it gives a sentence (stored as `lastResult`; when the folder is no longer read-only,
  was writable since the rule was switched on (`readOnlySince` differs), or overlaps a writable folder, the rule is also
  switched off); it skips every file whose `contentHash` the rule has recorded (imported or seen); `provenance(folder, at)` = `Provenance(origin = IMPORT, source =
  "Picked up from <folder>")`, no agent; `memoryDraft(rule, folder, title, text, at)` (a NOTE in the rule's space, tag
  `picked-up`, written through `MemoryActor.Owner` so the space accepts it and the memory service keeps IMPORT; never
  origin OWNER). `OwnerPickUpRulesContract` pins all of it (a writable folder refused; a folder made writable later cannot be
  switched on and its run check refuses; a folder made writable and read-only again between runs imports nothing written
  meanwhile; files already there are seen, never imported; a rule under or around a writable folder is refused and stops).
- (F11) `PickUpFolders` (read-only: `folder(id)`, `all()`, `files(folder)`, `read(folder, path, maxBytes)`; `None`); the app's
  `PickUpFoldersView(store, files)`. The pick-up step gets `RoutinesWiring.PickUpStepPorts(rules, folders, memory,
  settings, clock, zone, gate)` and nothing wider.

---

## 3. Who does what next

- **WP1b (data):** Room implementations and Room runs of `TaskSummaryContract`, `MessageHeaderContract`,
  `OwnerQuestionContract`, `ConversationTurnContract`, `ActivityPageContract`, `MemoryCountContract`,
  `UsageLedgerContract`, `ModelPricesContract`, `SettingsRoundTripContract`, `RoutineRepositoryContract`,
  `PickUpRuleRepositoryContract`; schema-6 tables `routines`, `routine_runs`, pick-up rules and imported files (columns
  as the types above); replace the two WP1a stand-ins in data (Space mapping, Answer branch) with the columns.
- **WP1c (framework):** `SettingsView` holders, then make `SettingsRepository` owner-only and add `update` in the
  framework architecture test (the Phase 6 owner ports are already listed).
- **WP1d (app):** Ports fields `usageLedger`, `usageReports`, `modelPrices`, `ownerModelPrices`, `ownerUsage`; AppGraph
  vals (`ownerConversation = OwnerConversation.Unavailable`, `maintenanceGate = LocalMaintenanceGate()`, …);
  `ReadOnlySettings` in the app test's `views`; `RoutinesWiring.kt` stub; manifest entries for WP17.
- **WP17 (wave 2):** `OwnerRoutines` and `OwnerPickUpRules` implementations pass `OwnerRoutinesContract` and
  `OwnerPickUpRulesContract`.
- **Every wave-2 package:** read `scratchpad/phase6/handoff-secfix.md` first (what the security fixes require of each).

---

## 4. K13 and the backup file: format `aicc-backup` version 1 — FROZEN v1

**Status: FROZEN v1** (after the wave-1 security review, findings F2, F5, F6, F7 and F13, and its second review: the
record schemas, the salt's spelling, one size bound, the passphrase's case folding; branch `p6/secfix`). Changing any
byte, field, label or rule below is a format version 2. Everything but streaming the chunks and the records inside them
is implemented and tested in `:core`: `core/backup/BackupCrypto.kt` (`BackupKeys`, `BackupHeader`, `BackupKdf`,
`BackupPreamble`, `BackupChunks`), `core/backup/Backup.kt` (`Passphrases`, `BackupFormat`), `core/backup/RestoreSettings.kt`
(`BackupSettings`, `RestoreSettings`), `core/backup/BackupRecords.kt` (`BackupRecord` and its record classes,
`BackupCounts`, `EntryAuthor`, `BackupRecords`: JSON, encode/decode and the save and restore rules of each record); tests
`core/src/test/.../backup/BackupCryptoTest.kt`, `RestoreSettingsTest.kt`, `BackupContractsTest.kt`, `BackupRecordsTest.kt`
(every key path and enum spelling pinned). WP3 implements the records in `data/backup/*` over `DataLayer.backupStores` and calls these
functions; it changes none of them.

**Layout** (integers big-endian, unsigned):

| Part | Bytes | Content |
|---|---|---|
| magic | 8 | ASCII `AICCBKUP` (`41 49 43 43 42 4B 55 50`) |
| version | 2 | `00 01` |
| header length | 4 | n, 1..4,096 |
| header | n | UTF-8 JSON, written with exactly these keys in this order (a reader ignores unknown keys): `{"format":"aicc-backup","version":1,"schemaVersion":6,"appVersion":"0.6.0","appVersionCode":9,"createdAt":<ms>,"kdf":{"name":"PBKDF2WithHmacSHA256","iterations":600000,"salt":"<standard base64 with padding of 16 bytes: exactly 24 characters ending in ==; any other spelling is Damaged>"},"cipher":"AES-256-GCM","chunkBytes":1048576}` (`BackupHeader.JSON`: `encodeDefaults`, `explicitNulls`, `ignoreUnknownKeys`) |
| key check | 32 | HMAC-SHA256(K_check, ASCII `aicc-backup v1 key check` ‖ H) |
| digest | 32 | SHA-256 of every byte from the magic to the end of the key check (unkeyed: it tells accidental damage from a wrong passphrase; it authenticates nothing) |
| chunk 0..k | | flags (1 byte; bit 0 = last chunk, other bits 0) · ciphertext length (4) · nonce (12, from `SecureRandom`, fresh per chunk) · AES-256-GCM ciphertext with its 16-byte tag |

- **H** = SHA-256(magic ‖ version ‖ header length ‖ header): what the key check covers and what every chunk's AAD starts with.
- **Passphrase bytes** P = UTF-8 of `Passphrases.normalise(passphrase)`: Unicode NFKC (`java.text.Normalizer`), then lower
  case and upper case by Unicode's full mappings without locale rules (`lowercase(Locale.ROOT).uppercase(Locale.ROOT)`: ß
  and ẞ both become SS; a Greek or Turkish letter typed in either case gives one text), then NFKC again, then every
  whitespace character (`Char.isWhitespace`), every invisible format character (Unicode category Cf: soft hyphen,
  zero-width space and joiners, byte-order mark) and every dash (category Pd, and U+2043, U+2212) removed, then `O`→`0`,
  `I`→`1`, `L`→`1`. P must not be empty. (Unicode-version differences between the phone's and the JVM's tables affect only characters added in
  recent Unicode versions.)
- **Keys** (`BackupKeys.derive`): M = PBKDF2-HMAC-SHA256(P, salt, iterations, 32 bytes) per RFC 8018, computed over P's
  exact bytes with one `Mac("HmacSHA256")` loop that checks for cancellation every 4,096 rounds (never `PBEKeySpec`, whose
  character-to-byte conversion is the provider's); PRK = HMAC-SHA256(key = salt, M) (HKDF-Extract, RFC 5869);
  K_enc = HKDF-Expand(PRK, ASCII `aicc-backup v1 encryption key`, 32); K_check = HKDF-Expand(PRK, ASCII
  `aicc-backup v1 check key`, 32). One salt (16 random bytes) and so one pair of keys per file. GCM is not key-committing;
  the key check makes the file committed to one passphrase.
- **Chunks** (`BackupChunks`): AAD of chunk i = H (32) ‖ i (u64) ‖ flags (u8). Every chunk but the last holds exactly
  1,048,576 plaintext bytes (ciphertext length exactly 1,048,592); the last holds 1..1,048,576 (length 17..1,048,592).
  At most 1,024 chunks (`BackupFormat.MAX_CHUNKS`: 1 GiB of body, `MAX_BODY_BYTES`). After the chunk flagged last the
  file must end. **One size bound:** a file is at most `BackupFormat.MAX_FILE_BYTES` = 1,024 × (1,048,576 + 33) + 78 + 4,096
  = 1,073,779,790 bytes (1,024 full chunks after the largest preamble); a larger file is `TooLarge` before anything is read.
- **Reading, in this order, and what each failure is** (`BackupPreamble.read`, `BackupPreamble.unlock`, `BackupChunks.open`):
  1. another magic → `NotABackup`; version above 1 → `NewerFormat(version)`; version 0 → `Damaged`;
  2. header length outside 1..4,096, or the file ends anywhere before the first chunk → `Damaged`;
  3. digest wrong → `Damaged` (one flipped bit anywhere before the chunks, including the salt, the iteration count and
     the key check, is damage, never "wrong passphrase");
  4. header not UTF-8 JSON, or not every `BackupHeader` field present and of its type (it is parsed in full here, unknown
     keys ignored) → `Damaged`. **Before the key check only these header fields are used:** `format` = `aicc-backup`,
     `version` = 1, `kdf.name` = `PBKDF2WithHmacSHA256`, `kdf.iterations` in **100,000..2,000,000** (`BackupFormat.MAX_ITERATIONS`;
     a file may not make the phone work for minutes), `kdf.salt` = the padded base64 of 16 bytes, exactly; otherwise `Damaged`, before any KDF work;
  5. the keys are derived on `Dispatchers.Default` (the screen shows an indeterminate progress with Cancel: `derive` reports
     no progress; cancelling stops the KDF within a few thousand rounds);
     the key check is compared in constant time (`MessageDigest.isEqual`); wrong → `WrongPassphrase`, nothing more is read;
  6. only now the other header fields: `cipher` = `AES-256-GCM`, `chunkBytes` = 1,048,576 exactly, `schemaVersion` ≥ 1,
     `appVersionCode` ≥ 1, `createdAt` ≥ 0, `appVersion` 1..32 characters; otherwise `Damaged`;
  7. chunks in order: unknown flag bits, a wrong length, a failed tag (a changed, moved, repeated or swapped chunk, a moved
     "last" flag), a cut inside or after any chunk, a file without a chunk flagged last, bytes after it → `Damaged`;
     more than 1,024 chunks → `TooLarge`.
- **Version handling:** the fixed version field and the header's `format`/`version` must agree.
- **Body** (the chunk plaintexts joined): records, each a length (4 bytes, 1..8 MiB) and a UTF-8 JSON object with `"type"`,
  written and read with `BackupRecords.encode`/`decode` (`BackupRecords.JSON`: class discriminator `"type"`, defaults and
  nulls written, unknown keys ignored). The JSON of each record is that of its class in `core/backup/BackupRecords.kt`
  and of the core classes it holds; `BackupRecordsTest` pins every key path and every enum spelling (a key added with a
  default stays v1 once pinned there and here; a rename, removal, type change or new enum spelling is v2). Times are ms
  since 1970 UTC, money is millionths of the currency unit, sizes are bytes. In order:
  - `{"type":"manifest","counts":{…},"settings":{…}}`: `counts` exactly `BackupCounts.KEYS` (`agents`, `providers`,
    `models`, `modelPrices`, `aiApps`, `spaces`, `memories`, `memoryLinks`, `conversations`, `conversationEntries`, all
    integers); `settings` below;
  - `agent`: `{"agent": AgentProfile, "stats": AgentStats}`; `provider`: `{"provider": ModelProvider}` as
    `BackupRecords.providerForSave` leaves it; `model`: `{"model": ModelDescriptor}`; `modelPrice`: `modelId`,
    `inputPerMTokMicros`, `outputPerMTokMicros`, `cachedInputPerMTokMicros` (or null), `currencyLabel`, `updatedAt`;
    `aiApp`: `{"app": AiApp}` as `aiAppForSave` leaves it; `space`: `{"space": KnowledgeSpace}`; `memory`:
    `{"memory": MemoryRecord}` (scope `{"type":"agent","agentId"}` or `{"type":"shared","spaceId"}`, provenance and
    history; no embeddings: the phone indexes it again); `memoryLink`: `{"link": MemoryLink}`; `conversation`:
    `{"conversation": Conversation}`; `entry`: `conversationId`, `seq`, `author` (`"owner"` or `"agent"`), `text`, `at`,
    `modelName` (agent entries only, else null);
  - last `{"type":"end","records":<number of records before it, the manifest included>}`.
  Unknown record types are skipped (`decode` gives null) and unknown fields ignored; a missing `end`, a wrong count, a
  record over its size, a malformed known record, or **any plaintext byte after the end record** is `Damaged`.
- **Settings in the manifest (F2):** exactly the keys of `BackupSettings` (`BackupSettings.KEYS`): `localOnlyMode`,
  `directorAgentId`, `maxModelCallsPerDay`, `maxTokensPerDay`, `maxSpendPerDayMicros` (null = no money limit),
  `notifyWhenWorkEnds`, `calmMotion`, `onboardingCompleted`, `seedVersion`, `memorySeedVersion`; a save writes
  `BackupSettings.of(current settings)`; a reader ignores any other key and reads a missing one as its default.
  **A restore takes the settings only through `RestoreSettings.merge(current, file, agentsAfterRestore)`:** local-only =
  now OR file; daily limits = the file's values clamped into the Settings ranges (`DailyLimits.CALLS_RANGE`,
  `TOKENS_RANGE`, `SPEND_RANGE_MICROS`), then the stricter of now and the file, limit by limit; the Director = the
  file's if that agent exists after the restore, else the current one if it still exists, else none; `seedVersion` and
  `memorySeedVersion` from the file; "getting started" done = now OR file; `notifyWhenWorkEnds` and `calmMotion` from the
  file (`agentsAfterRestore` is a map of agent id to name: the inspect screen shows the Director by name, or "an agent not
  in this backup" (`RestoreSettings.UNKNOWN_AGENT`), never by id; the ids travel in `SettingChange.agentFrom/agentTo` and
  `SettingKeptBack.agentInFile/agentAfterRestore`); **forced off whatever the file says: routines, folder pick-up, the phone operator, screen control and resuming
  interrupted tasks by themselves**; every other setting (agents' questions included) keeps its current value. The inspect
  screen lists `RestoredSettings.changes` (every setting that changes on this phone), `keptBack` (every file value not
  taken as it is) and the line "After the restore these are off: Phone operator, Screen control, Resume interrupted
  tasks, Routines, Folder pick-up. Switch them on again in Settings if you want them."
- **What a save leaves out of each record (F13)**, as the pure functions of `BackupRecords` do it (WP3 calls them and adds
  no rule of its own; `BackupRecordsTest` pins each):
  - `provider` (`providerForSave`): no `apiKeyAlias` (null), status UNKNOWN, no status message or check time. Extra
    headers: only those named `HTTP-Referer` or `X-Title` (case ignored) whose value `AnswerCheck.looksLikeKey` does not
    flag; every other header is left out (`headersLeftOut` counts them), and the save report and the inspect screen say
    `BackupRecords.HEADERS_LEFT_OUT`. A provider one part of whose address (between `/ ? # & = ; @`) looks like a key is
    left out entirely (`record == null`; `PROVIDER_LEFT_OUT`), and its models with it.
  - `aiApp` (`aiAppForSave`): no `signingCertSha256`, `firstInstallTime` (null), `verifiedByUser` or
    `accessibilityAuthorized` (false): they describe the install on this phone.
  - `memory` (`memoryForSave`, `memoryInFile`): only Agent and Shared scope; a Task-scope memory (it belongs to an
    objective, and objectives are not in the file) is never written. Memories a folder pick-up made are ordinary memories
    of their space and are written with their IMPORT provenance.
  - `entry` (`entryForSave(entry, toolTitle)`): the owner's messages and the agent's own reply text only; each tool call
    becomes the line "A tool was used here: <tool title>. Its result is not in the backup." (`toolLine`); instructions to
    the model, tool results, images, reasoning and provider data are left out, and an entry left with no text is not
    written.
- **What a restore does with each record (rule 8, F13):** grants nothing; `BackupRecords.providerForRestore`:
  `provider.apiKeyAlias = null` always (also when the file's provider has the id of a built-in or existing provider whose
  key is in the vault), status UNKNOWN, locality = the farther of the file's and `Locality.infer(baseUrl)` (ON_DEVICE <
  LOCAL_NETWORK < CLOUD), only `HTTP-Referer`/`X-Title` headers taken (and re-validated by `ProviderDraftValidator`);
  `aiAppForRestore`: `verifiedByUser = false`, `accessibilityAuthorized = false`, no signing certificate or install time
  (the registry re-scans the phone); a `modelPrice` that `Price.toPrice()` rejects is dropped and counted; a memory whose agent or
  space is not in the file, or with Task scope, is dropped and counted; agents' space lists are cut to spaces in the file;
  memory ids and scopes kept; every record validated like owner input; writes only into an empty app, checked inside the
  one transaction (`BackupStores.transactions.run`); writes nothing when the passphrase is wrong or any chunk was changed,
  moved or cut off.
- **Never in the file (rule 8, F2):** API keys, key aliases, the database key, grants, owner requests, authorised screen
  apps, shared folders and their grants, the activity log, usage (prices are in), tool calls and tool results, objectives,
  **routines, routine runs, folder pick-up rules and picked-up file records**. A pick-up rule names a shared folder, whose
  grant is never in the file, so a restored rule could not run; the owner makes rules again.
- **Passphrase (F7):** a save takes the passphrase only through `Passphrases.problemForSave(input, generatedShown)`: the
  exact passphrase the app generated and showed (compared after `normalise`; `generatedShown` counts only in exactly
  `generate()`'s grouped form), or an own one that passes `Passphrases.check` (at least 4 different real words of 4 or
  more letters with 3 or more different letters, different by at least two letters once accents are removed, none a
  common password, keyboard walk or alphabet run, and an estimated strength of at least 55 bits; or an estimated strength
  of at least 60 bits; never mostly a common password or walk; at most 256 characters). `isGeneratedForm` is for
  formatting input only, never a reason to accept.
- **What the backup code can reach (F13):** `BackupWiring.production(stores: BackupStores, gate, freeBytes)`. `BackupStores`
  (in `data/DataLayer.kt`, built by `DataLayer.backupStores`) holds agents, providers, models, model prices (read and
  owner's), AI apps, memories, conversations, tasks (read for the empty-app check), settings, the activity log and one
  transaction; never the vault, grants, owner requests, shared folders or authorised apps (`BackupWiringTest`).
- **Bounds:** file ≤ `BackupFormat.MAX_FILE_BYTES` (1,073,779,790 bytes; see Chunks), ≤ 500 agents, ≤ 200,000 memories;
  restore checks free space ≥ 2 × file size first.

**Test vectors** (pinned in `BackupCryptoTest`; computed independently with Python `hashlib`, `hmac`, `unicodedata` and
`cryptography`; script `scratchpad/secfix/vectors.py`):

| What | Input | Output (hex) |
|---|---|---|
| PBKDF2-HMAC-SHA256 (RFC 7914 §11) | P `passwd`, S `salt`, c 1, 64 bytes | `55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783` |
| PBKDF2-HMAC-SHA256 (RFC 7914 §11) | P `Password`, S `NaCl`, c 80,000, 64 bytes | `4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d` |
| HKDF-SHA256 (RFC 5869 case 1) | IKM 22×`0b`, salt `00..0c`, info `f0..f9`, L 42 | PRK `077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5`, OKM `3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865` |
| normalise, non-ASCII | `Crème brûlée façade ﬁ１` (NFC or NFD) | text `CRÈMEBRÛ1ÉEFAÇADEF11`, P `4352c3884d454252c39b31c389454641c387414445463131` |
| M | that P, salt `000102…0f`, 100,000 | `4283eba06607c47dc1d914b31444c4e4addd311d6954a7275510302cc4c360a3` |
| K_enc / K_check | M, salt as above | `fd0ad21ad5d371eed014dc05b8be33868ae47f78b1bf919f9bb997448626ff5e` / `63b9823dadc2b1230b96f192e0a59f51c3c5f4c5e89da9abc7f51a3e5d0b6302` |
| preamble | header with `schemaVersion` 6, `appVersion` `0.6.0`, `appVersionCode` 9, `createdAt` 1795000000000, 100,000 iterations, that salt | 334 bytes; H `5936edd39b6644fcdc3d58cf6c4a5f9237e7f8875971517035fe7bb078b2e709`; key check `c1bc63d379669b21290f44c1134d3bfe2021373f069ace7a48cfe8a3858c0cb1`; digest `0c2eabb116ccdd0110a6ca58a3cd1ba82c27363158953ac3cead8ce89ff41cfb`; SHA-256 of the preamble `67c2d0156cb22315c813a63679305d112a29f56b4ec0edc8714a786592dcd21c` |
| chunk 0 | last, plaintext `hello, backup`, nonce `6465…6f` (100..111) | `010000001d6465666768696a6b6c6d6e6ffa4fe7b6ed30aaabdbf3d5a46aa4815f1d6b8774d8b8bfee9faa23809b` |

**Tests WP3 owes** (the core pins the crypto, the record keys and the record rules; WP3 tests the file it writes and
reads): JVM round trip of a whole app through `BackupRecords.encode`/`decode` (no record class of its own); a plaintext
byte after the end record → `Damaged`;
one flipped bit in each chunk → `Damaged`; reordered, duplicated and missing chunks; cut after each chunk; bytes after the
last; wrong passphrase → `WrongPassphrase` without reading chunk 0; a header asking 10,000,000 iterations → `Damaged`
before any KDF; version 2 → `NewerFormat`; hostile strings; every rule-8 exclusion (a `screen.read` result is absent
from the decrypted body; no `apiKeyAlias`); the F13 drops (an `X-Token` header value, an AI app's certificate and a
Task-scope memory are absent from the decrypted body); the restore rules (vault holds `provider/p1/1`, file has provider
`p1` at `https://other.example` with that alias → stored alias null and zero requests carry the key; locality the farther
one; private memories of A unreadable by B; settings merged with `RestoreSettings`); cancelling a save and a restore
during the KDF; Robolectric Room round trip with `DatabaseEncryption.None`; **instrumented**: the vectors above through the
phone's own crypto provider (`data/src/androidTest/.../backup/BackupVectorsDeviceTest`, F6: the non-ASCII vector, and
`BackupKeys.derive` at 600,000 iterations on `Dispatchers.Default` finishing within 15 s on the emulator, its time logged)
and a round trip through a file URI against SQLCipher.
