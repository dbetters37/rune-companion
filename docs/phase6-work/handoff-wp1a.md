# Hand-off from WP1a (contracts in :core and :testkit)

Branch `p6/wp1a`, worktree `/home/user/p6-wp1a`. Builder's guide: `scratchpad/phase6-contracts.md`.

## 1. Edits WP1a made in files it does not own, and why

The plan assumed every Phase 6 addition could be made invisible to existing code with defaults. Five additions cannot:
Kotlin requires every `when` over an enum or a sealed type to name every case, so a new enum value or sealed subtype
breaks each `when` that lists the old ones. The plan itself requires these additions (K2, K3, K11):
`OwnerRequestKind.QUESTION`, `OwnerDecision.Answer`, `MessageType.GUIDANCE`, `Participant.Space`,
`LlmException.DailyLimitReached`. Without a branch in each breaking file, `:framework`, `:data` and `:app` would not
compile and WP1b and WP1c could not start from this branch. WP1a therefore added the one missing branch, keeping
today's behaviour, in each file below; each is marked `// Phase 6 (WP1a)`. The integrator should keep them at merge;
the package named in the last column replaces the stand-in with the real behaviour.

| File | Branch added | Stand-in behaviour | Replaced by |
|---|---|---|---|
| framework/src/main/kotlin/com/aicontrolcenter/framework/llm/LlmFailures.kt (`withoutSecret`) | `is LlmException.DailyLimitReached` | re-created with the redacted text, like every other failure | WP4 (keeps it) |
| framework/src/main/kotlin/com/aicontrolcenter/framework/operator/OwnerServices.kt (decision log text) | `is OwnerDecision.Answer` | logs "Owner answered a question" (no answer text) | WP5 (`answer`, rule 32) |
| framework/src/main/kotlin/com/aicontrolcenter/framework/operator/PermissionBroker.kt (`newRequest` details) | `OwnerRequestKind.QUESTION` | `error(...)`: the broker never raises a question | WP5 (may keep it) |
| framework/src/main/kotlin/com/aicontrolcenter/framework/orchestration/ObjectiveJournal.kt (`name`) | `is Participant.Space` | "A shared memory space" | WP7 (name the space) |
| framework/src/main/kotlin/com/aicontrolcenter/framework/orchestration/ObjectiveJournal.kt (`label`) | `MessageType.GUIDANCE` | "Note from the owner" | WP7 (logs GUIDANCE without the body, S28) |
| data/src/main/java/com/aicontrolcenter/data/db/mapper/MessageMappers.kt (`toColumns`) | `is Participant.Space` | throws: a space recipient is stored only from schema 6 | WP1b (kind `space`, `to_space_id`) |
| data/src/main/java/com/aicontrolcenter/data/repository/RoomOwnerRequestRepository.kt (`decideInTransaction`) | `is OwnerDecision.Answer` | APPROVED with `answer` in the domain object (no column yet) | WP1b (question, options and answer columns) |
| app/src/main/java/com/aicontrolcenter/app/ui/approvals/ApprovalPresentation.kt (`title`, `closedTitle`, `choicesFor`, consequences) | `OwnerRequestKind.QUESTION` | "Question waiting" / "Question"; no answer buttons; no consequences | WP11a (question screen, rule 13) |
| app/src/main/java/com/aicontrolcenter/app/ui/tasks/TaskLabels.kt (`label`, `icon`) | `MessageType.GUIDANCE` | "Your note", the Forum icon | WP11a |
| app/src/main/java/com/aicontrolcenter/app/ui/tasks/TaskPresentation.kt (`AgentFaces.of`) | `is Participant.Space` | shown as `ParticipantUi.System` (as older shares) | WP11a (name the space) |

Tests WP1a changed outside :core and :testkit:

| File | Change | Why |
|---|---|---|
| framework/src/test/kotlin/com/aicontrolcenter/framework/operator/OperatorDependencyTest.kt (WP1c's in wave 1) | lists the Phase 6 owner-only ports and the routine and pick-up stores; `startOver`, `clearHistory` as owner actions; new test "every owner-only port the core declares is on the list" (scans :core) | the WP1a task: owner-only ports must be unreachable from agent-facing code. WP1c still adds `SettingsRepository`, `update` and the new roots |
| app/src/test/java/com/aicontrolcenter/app/di/OperatorDependencyTest.kt (WP1d's in wave 1) | the same list and owner actions | same; WP1d still adds `SettingsRepository`, `update`, `ReadOnlySettings` in `views` |
| device/src/test/kotlin/com/aicontrolcenter/device/QueriesManifestTest.kt | the spelled-out list of queried intents gains the four pickers | K17: the plan keeps `QueriedIntent` and `<queries>` "in step by QueriesManifestTest"; the test lists them one by one |
| device/src/test/kotlin/com/aicontrolcenter/device/platform/PickerHandlersTest.kt (new) | the pickers' real filters are resolved and protected | K17 |
| data/src/test/java/com/aicontrolcenter/data/StoredNamesGoldenTest.kt (WP1b's) | appended `GUIDANCE` (MessageType), `QUESTION` (OwnerRequestKind), the `routineId` key of ObjectiveOptions (its JSON now ends `,"routineId":null`) and the 11 Phase 6 settings keys; the settings text check pins the Phase 5 run and the new last key | golden lists of stored names fail on any appended value; WP1b still adds the `space` participant kind and the schema-6 names |
| data/src/test/java/com/aicontrolcenter/data/settings/SettingsCodecTest.kt (WP1b's) | 20 → 31 fields; the 11 new fields fall back to `customised` (which keeps their defaults) | the codec is descriptor-driven and already stores every new field; only the test's counts were fixed |
| data/src/test/java/com/aicontrolcenter/data/settings/DataStoreSettingsRepositoryTest.kt (WP1b's) | `endsWith(…"memorySeedVersion":1})` → `contains(…"memorySeedVersion":1,)` | `memorySeedVersion` is no longer the last stored key |

## 2. What each package must pick up from WP1a

- **WP1b:** run every Phase 6 suite against Room (list in the guide, section 3), including `OwnerQuestionContract` (a
  separate suite, not inside `OwnerRequestRepositoryContract`, so the Room suite stayed green before schema 6) and
  `SettingsRoundTripContract` (the DataStore codec must store the 11 new settings, `maxSpendPerDayMicros` nullable).
  `upsertConversation` must keep `turn_started_at` (`ConversationTurnContract`); the activity text filter must escape
  `%`, `_` and `\` in its LIKE (`ActivityPageContract`); usage sums use `UsageRange` hour bounds exactly
  (`UsageLedgerContract`, including a half-hour zone). Schema 6 also needs the routine and pick-up tables (Q1); their
  columns are the fields of `Routine` (schedule as JSON of `RoutineSchedule`, which is serialisable), `RoutineRun`,
  `PickUpRule`, `PickedUpFile` (unique rule + hash).
- **WP1c:** `SettingsView`/`ReadOnlySettings` exist in core; switch the holders, then make `SettingsRepository`
  owner-only in the framework test (the list already has the other Phase 6 ports).
- **WP1d:** wire `LocalMaintenanceGate`, `OwnerConversation.Unavailable`, `UsageLedger.None`/`UsageReports.None` until
  WP1b's members exist, and put `ReadOnlySettings` in the app test's `views`.
- **WP5:** `OwnerAttention`, `AnswerCheck`, `QuestionRules`, `CommandSlot`, `WebAddressPolicy.literalRefusal`,
  `FrontState`, holds; replace the stand-ins listed above.
- **WP17:** implement `OwnerRoutines` and `OwnerPickUpRules` and run `OwnerRoutinesContract` and
  `OwnerPickUpRulesContract`; `RoutineSchedule.nextAfter` is the due-time arithmetic (DST rules in its KDoc).

## 3. Rule 25 and the old fallback tests

Rule 25 is in `resolveAvailability` (core). No existing test encoded the old behaviour: `:core`, `:framework`
(including RegistryDirectoryTest, ModelGatewayTest, ProviderManagerTest) and `:app` pass unchanged. Nothing to hand over.
