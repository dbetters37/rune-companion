# Hand-off from WP1b (schema 6 and the Room side of every contract)

Branch `p6/wp1b` (from `p6/wp1a`), worktree `/home/user/p6-wp1b`. WP1b changed files under `data/**` only.

## 1. What is there for the other packages

- **Schema 6** (`AiccDatabase.VERSION = 6`, `data/schemas/.../6.json`, one `Migration5To6` in
  `data/db/AiccMigrations.kt`): `tasks.retry_at`, `tasks.result_chars` (+ the one backfill `UPDATE`),
  index `tasks(assignee_id ASC, updated_at DESC, id ASC)`, `agent_messages.to_space_id`,
  `owner_requests.question/options_json/answer`, `conversations.turn_started_at`; new tables `usage_hours`,
  `work_usage`, `model_prices`, `routines`, `routine_runs`, `pickup_rules`, `pickup_imports`. 25 tables in all.
- **DataLayer members** (all `by lazy`, on the one database):
  `usageLedger: UsageLedger`, `usageReports: UsageReports`, `ownerUsage: OwnerUsage` (owner-only),
  `modelPrices: ModelPrices` (a read-only view: it implements `ModelPrices` only, so it cannot be cast to the
  writer), `ownerModelPrices: OwnerModelPrices` (owner-only, same store), `routines: RoutineRepository` and
  `pickUpRules: PickUpRuleRepository` (both owner-capable: only the owner's ports and WP17's runners may hold them).
  `DataLayer` gained two constructor parameters with defaults, `clock: Clock = WallClock` and
  `zone: () -> ZoneId = { ZoneId.systemDefault() }` (stamping prices; "today" for `ownerUsage.clearHistory()`).
- Room overrides of every WP1a default: `RoomTaskRepository.observeSummaries/observeObjectiveRows/
  observeRecentByAgent/observeProgress`, `RoomMessageRepository.observeHeaders/observeRecentHeaders`,
  `RoomConversationRepository.markTurnStarted/markTurnEnded/interruptedTurns/observeLatestSeq/entriesAfter/
  entriesBefore` (upsert keeps the mark), `RoomMemoryRepository.observeCount/observeCountsBySpace`,
  `RoomActivityLog` text filter (escaped `LIKE`) and `observePage` (keyset), `RoomOwnerRequestRepository`
  QUESTION/options/answer through the existing compare-and-set; `deleteTree`, `deleteConversation` and
  `deleteByAgent` also delete their `work_usage` rows in the same transaction.
- The two WP1a stand-ins in `:data` are replaced: `MessageMappers` stores `Participant.Space`; the
  `OwnerDecision.Answer` branch of `RoomOwnerRequestRepository` writes the `answer` column.

## 2. Changes needed in files WP1b does not own

| Where | What | Why | Who |
|---|---|---|---|
| app `di/Ports.kt`, `di/ProductionPorts.kt`, `di/AppGraph.kt` | Wire `data.usageLedger`, `data.usageReports`, `data.modelPrices`, `data.ownerModelPrices`, `data.ownerUsage` (and, for WP17's stub, `data.routines`, `data.pickUpRules`) instead of the `None` stand-ins. Hand agent-facing code (the gateway's limiter) `data.modelPrices` only, never `ownerModelPrices`. If the app's clock or zone is injectable in tests, pass them to `DataLayer(clock = …, zone = …)`. | K11, rule 3, Appendix A | WP1d |
| both architecture tests | `RoutineRepository`, `PickUpRuleRepository`, `OwnerUsage`, `OwnerModelPrices` are already owner-only (WP1a). Nothing new is needed for WP1b's classes, which implement only those ports; `RoomModelPrices` implements both `ModelPrices` and `OwnerModelPrices` and must stay out of agent-facing holders (use `DataLayer.modelPrices`). | rule 3 | WP1c/WP1d (check only) |
| testkit `InMemoryUsageStore` (`byModelIn`, `byProvider`) | Optional: take a model's or provider's labels from the row with the newest call time (`at`), as Room does (`usage_hours.last_call_at`), instead of the last recorded row. The two differ only when calls are recorded out of time order; no contract test covers it. | keep the reference and Room identical | integrator / WP8 |
| testkit `contract/SummaryContracts.kt`, `MessageHeaderContract` "an open observer of headers sees new messages" (line 171) | Write the wait condition as `recent.last().singleOrNull()?.id == MessageId("msg_3")` (or let `awaitUntil` treat an exception in its condition as "not yet"). Today `recent.last().single()` throws `NoSuchElementException` when the headers flow has caught up and the recent-headers flow has not emitted since its first, empty list: Room updates the two flows independently, so the Room run failed once in five runs. Until then `RoomMessageHeaderContractTest` retries that one failure once, on a fresh database (`data/src/test/.../testing/RetryOnceOnEmptyListRule.kt`); remove the rule when the contract is fixed. | a race in the suite, not in a store | integrator / WP8 |
| docs/ARCHITECTURE.md (storage section), docs/STATUS.md | Schema 6: 25 tables; the columns and tables above; `ParticipantKind` `SPACE`; usage holds numbers, ids and labels only (rule 7). | docs say what is built | WP14 / integrator |
| WP3 (backup format, section 4 of the builder's guide) | Usage tables are not in the backup (rule 7); `model_prices` is (`modelPrice` records). Routines and pick-up rules (Q1, Appendix A) are not in format v1: decide at the format review. A pick-up rule names a shared folder, whose grant is never in the file (rule 8), so a restored rule could not run. | rule 8, Q1 | WP3 + security reviewer |

## 3. Gate (wave 1)

`EncryptedMigrationTest` (data/src/androidTest/.../db/EncryptedMigrationTest.kt) is written and its sources
compile (`./tools/gw --offline :data:assembleDebugAndroidTest`); it was not run (no emulator for WP1b). It
creates an encrypted version-5 database with a row in each of the 18 tables of schema 5 (SQLCipher, key sealed by
the keystore exactly as `DataLayer` does it), opens it the way the app does (Room runs `Migration5To6` under
SQLCipher), checks every row, uses the new tables and columns, checks that no file holds text in the clear, and
reopens. On the emulator:

```
export TMPDIR=/tmp
./tools/gw --offline :data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.aicontrolcenter.data.db.EncryptedMigrationTest
```

`data/build.gradle.kts` now packages the schemas as androidTest assets and adds `room-testing` to androidTest.

## 4. Deviations from the plan (and why)

1. **Participant kind `SPACE`, not `space`.** The plan writes `to_kind = "space"`; the stored kinds are the
   upper-case names of `ParticipantKind` (`OWNER`, `AGENT`, `SYSTEM`), so the new one is `SPACE`, appended last
   and pinned by `StoredNamesGoldenTest`. (`Participant.Space`'s JSON serial name stays `space`; no JSON column
   stores participants.) A space can only receive: storing a message *from* a space is refused.
2. **`usage_hours.last_call_at`** (INTEGER, not in the plan's column list): the time of the newest call in the
   row. WP1a's contract says a report's labels are those of the newest sample; SQL cannot tell which row was
   written last without it. It always lies inside the row's hour, so the reports pick the labels from the row
   with the greatest `last_call_at` (SQLite's documented "bare column with one `MAX()`" rule). A number, allowed
   by rule 7 (pinned in `UsageColumnsTest`).
3. **Index `tasks(assignee_id ASC, updated_at DESC, id ASC)`** instead of `tasks(assignee_id, updated_at)`: the
   listing orders by `updated_at DESC, id ASC`; with `id` in the index in that order SQLite needs no sort and
   `LIMIT` stops the read (checked by `EXPLAIN QUERY PLAN` in `SummaryQueriesTest`).
4. **Pick-up tables** named `pickup_rules` (unique index on `folder_id`) and `pickup_imports` (primary key
   `(rule_id, content_sha256)`, the de-duplication; indexes `(rule_id, imported_at DESC, content_sha256 ASC)` and
   `(imported_at)`); `routine_runs` has the index `(routine_id, due_at, id)`. None of the seven new tables has a
   foreign key: the stores delete runs and imports with their routine or rule in one transaction, and usage
   outlives what it counts.
5. **Objective rows** are one aggregate page query plus one participant query over the page's objectives (the
   participants must keep task-creation order, which `group_concat` does not guarantee on every SQLite the app
   runs on). The page is keyed by `(created_at, id)` as WP1a's `ObjectiveCursor` (WP1a's own deviation).
6. **The backfill** is `UPDATE tasks SET result_chars = COALESCE(length(result), 0)`. SQLite's `length()` counts
   characters up to a NUL; `SummaryText.length` counts every code point. They differ only for a result holding a
   NUL character, which no model answer or tool result stores; every later write sets the column from Kotlin.
7. **Activity pages**: the cursor's event is looked up once when the page flow starts (events are never
   rewritten); if it is gone or does not match the filter, the page starts below the cursor's time, as
   `activityPageOf` does. A text filter whose `LIKE` pattern would exceed 10,000 bytes is matched with `instr` on
   lower-cased text instead (SQLite refuses `LIKE` patterns over 50,000 bytes).
8. **Extra test runs**: the reference `OwnerRoutines`/`OwnerPickUpRules` of `:testkit` run their contract suites
   over the Room stores (`RoomBackedOwner…ContractTest`), so the caps under concurrency are known to hold on Room
   before WP17 builds the real ports.
