# Phase 6 reader report: performance monitoring

Area: performance monitoring (what is measured today, what the owner should see, and the app's own
performance problems). Source read: /home/user/rune-companion at release 0.5.1 (schema 5). Nothing under
the repository was changed, no build was run.

Legend: **[V]** verified in the code or in the cited Android reference page during this session.
**[A]** assumption or estimate, with the reason. **[K]** platform fact from documentation knowledge that
could not be re-fetched here (sqlite.org is not reachable from this environment).

---

## 0. Summary

1. Every model call already produces exactly one `MODEL_CALL` activity event with model, provider,
   agent, task, tokens in/out/cached, total duration, attempts, answered model and an error class
   [V]. That is the richest source, but it lives only as JSON in `activity_events.data_json`, is
   deleted by the activity retention (default 30 days) and by "Clear the activity log", and cannot be
   summed in SQL.
2. Lifetime per-agent counters exist in `agent_stats` [V]; per-task token counters in
   `tasks.usage_json` [V]; per-reply tokens in `conversation_entries.usage_json` [V]; approval answer
   times are derivable from `owner_requests.created_at/decided_at` [V]. Health-check latency is measured
   and thrown away [V].
3. Missing: time to first words, retry wait vs. real answer time, HTTP status of failures, cost,
   any time window (today / 7 days), per-model reliability, tool time without the owner's wait,
   recall time, app memory, database and workspace size, exit reasons, health-check history.
4. Proposal: seven small numeric tables in schema 6 (hourly rollups for model calls, tool calls and
   agent runs; provider checks; owner-entered model prices; app-health samples; Android exit
   records), written at the three places that already write the activity events, kept 2 years
   (rollups) / 14-90 days (samples), a few MB at most. A "Usage" tab in Activity, a "Today" tile
   on the Dashboard, speed/reliability cards on model, provider and agent screens, and
   "This app on your phone" in Settings.
5. Biggest performance problem [V]: the Tasks list, the agent screen, the Dashboard and the
   foreground service each observe whole task trees with `SELECT *`, one Room flow per objective;
   every write to `tasks` re-reads and JSON-decodes every row (results included) of every observed
   objective. Second: the objective screen rebuilds its whole state every second on the main thread.
   Fix: summary projections with the big text columns moved to the end of the row (or to a side
   table), one aggregate query for the list, state building on `Dispatchers.Default`.

---

## 1. What the app records today

### 1.1 Model calls: `MODEL_CALL` activity events [V]

Written by `ModelCallJournal` (framework/.../llm/ModelCallJournal.kt), called by `DefaultModelGateway`
(framework/.../llm/DefaultModelGateway.kt). Exactly one event per gateway call
(DefaultModelGateway.kt:46-49, ModelCallJournal.kt:109-111 `recorded` guard).

| Field in `data_json` | Written at | Notes |
|---|---|---|
| `modelId`, `requestedModel`, `providerId`, `provider` | ModelCallJournal.kt:116-119 | `blocked()` (policy refusal before a route exists) writes only `modelId` + `outcome` (ModelCallJournal.kt:49-58) |
| `durationMs` | ModelCallJournal.kt:120, value from `elapsed()` DefaultModelGateway.kt:270 | Wall clock (`clock.now()`), from `ModelCall.startedAt` (set in `callTo`, DefaultModelGateway.kt:162, after `connections.acquire`, :166-174) to the end. Includes every retry and the back-off `delay(pause)` (:88-89, :126-127). No per-attempt time |
| `attempts`, `streamed`, `promptedTools`, `messages`, `tools` | ModelCallJournal.kt:121-125 | |
| `outcome` ok/failed/blocked/cancelled | :76, :91, :101, :106 | |
| `answeredModel`, `modelSubstituted`, `finishReason` | :77-79 | substitution test is a name prefix comparison (:63-66); STATUS Phase 2 rough edge |
| `inputTokens`, `outputTokens`, `cachedInputTokens` | :80-84 | only when the provider reported usage (`ChatResponse.usage` nullable, core/.../llm/Chat.kt:156-163) |
| `error` (exception simple class name), `retryable` | :92-93 | the `LlmException` subclasses are the taxonomy (core/.../llm/LlmClient.kt:32-88). `InvalidRequest.status` and `Server.status` (LlmClient.kt:60, :64) and `RateLimited.retryAfterMs` (:41) are **not** logged |
| event `agent_id`, `task_id`, `root_task_id` | :114 from `CallContext` | `CallContext` (LlmClient.kt:136-142) has no conversation id and no "what kind of step"; a chat call has an agent and no task. The only constructor call is AgentRunner.kt:194 |
| `egress_json` approx bytes × attempts | :128-130 | |

All agent model calls are streamed: `AgentRunner.callModel` uses `gateway.chatStream` (AgentRunner.kt:262);
the only non-streamed `chat()` path is `DefaultModelGateway.chat` (:68-94), not used by the runner.
xAI goes through the OpenAI-compatible adapter (ProviderPresets.kt:21, :43), which asks for usage in streams
(`stream_options.include_usage`, OpenAiRequests.kt:117) and parses `prompt_tokens`, `completion_tokens` and
`prompt_tokens_details.cached_tokens` (OpenAiReplyAssembler.kt:250-261). Reasoning tokens are inside
`completion_tokens` and not split out (comment at :251).

### 1.2 Per-task usage counters [V]

`TaskUsage(modelCalls, toolCalls, inputTokens, outputTokens, messagesSent)` (core/.../tasks/Tasks.kt:87-98),
stored as `tasks.usage_json` (TaskEntity.kt:59). Added after each step through `ctx.account(...)`:
WorkSteps.kt:98, PlanningStep.kt:67, ReviewSteps.kt:76, SynthesisStep.kt:54, TeamDesk.kt:108 and :186; the
step's numbers come from `StepRunner` (StepRunner.kt:130). Shown on the task screen as one line
(TaskDetailViewModel.kt:234-242). No time stamp per call, no cost, objective totals only by summing the tree.

### 1.3 Per-agent lifetime track record [V]

Table `agent_stats` (AgentStatsEntity.kt:18-36): tasks assigned/completed/failed/timed out/cancelled,
reviews passed/revisions/rejected, model calls/errors, tool calls/errors, tokens in/out, total task
duration, last active. Written by:
- `AgentRunner.saveStats()` (AgentRunner.kt:419-428) in the run's `finally` (:128-133), for chats and tasks;
  `modelErrors` counts runs that ended on a failed call (:232-235), not failed calls; `toolErrors` (:354).
- `ObjectiveJournal.countFinished` (ObjectiveJournal.kt:122-136): task outcomes and
  `totalTaskDurationMs = finishedAt - startedAt`, which **includes time paused and time waiting for the
  owner** (:124).
- `countStats` (:138-140) from WorkSteps.kt:64 (assigned) and ReviewSteps.kt:147 (review outcomes).
Smoothed reliability: `AgentStats.reliability` (core/.../agents/AgentProfile.kt:139-146), used by
`AgentSelector` (AgentSelector.kt:90). Shown in `AgentTrackRecord` (app/.../agents/AgentTrackRecord.kt:62-102).
Lifetime only: no "last 7 days", no trend.

### 1.4 Per-reply usage in transcripts [V]

`conversation_entries.model_name` and `usage_json` (ConversationEntryEntity.kt:35-37), appended per
assistant message by DefaultAgentChat.kt:396-397 (chats) and TaskTranscripts.kt:58-60 (task transcripts).
Shown under each chat reply (ChatPresentation.kt:230). No timing.

### 1.5 Tool calls: `TOOL_CALL` activity events [V]

`ToolExecutor.record` (framework/.../tools/ToolExecutor.kt:268-316): function, call id, tool id, capability,
sendsDataOffDevice, risk, outcome (`ToolCallEnd`), `durationMs`, argument names, output size, the owner
request ids. `durationMs` runs from `startedAt` (:125) to the end (:143) and **includes the owner's
answering time**. The paused time is measured (`measuringPauses`, :132; `pauses.pausedMs()`, used in
`storeOwnerTime` :157-169) but not logged.

### 1.6 Provider health checks [V]

`probeHealth` measures `latencyMs` with `System.nanoTime` (providers/.../http/Health.kt:14-31).
`DefaultProviderManager.check` (:226-236) stores only status and message (`ProviderConnections.storeStatus`,
ProviderConnections.kt:135: `status`, `statusMessage`, `lastCheckedAt` on `providers`, ProviderEntity.kt:25-27).
Latency is shown once as "answered in N ms" (app/.../models/ProviderPresentation.kt:206) and lost. A
REGISTRY event "Provider checked" is written only for providers off the device, with the outcome text and
no latency (`recordContact`, DefaultProviderManager.kt:275-292); "Provider status changed" only when the
status changes (:229-233). Checks run at start (all providers, cloud included: AppStartup.kt:211-219 calls
`checkAllProviders()`), when the app returns after 10 minutes (:174-175, `PROVIDER_RECHECK_MS` :255), or
on "Check now".

### 1.7 Approvals and permission requests [V]

`owner_requests` (OwnerRequestEntity.kt:37-63): `created_at`, `expires_at`, `decided_at`, `status`,
`kind`, `agent_id`, `tool_id`, `capability`, `task_id`, `root_id`, `conversation_id`, `asked_again`.
Answer time = `decided_at - created_at` for decided rows; expired and cancelled rows are kept. Rows are
never deleted (OwnerRequestEntity.kt:9-11). Indexed by `(created_at, id)` (:34), enough for a 30-day scan.

### 1.8 Task timing [V]

`tasks.started_at`, `finished_at`, `deadline_at`, `attempt`, `revision_round`, `status`
(TaskEntity.kt:44-66). Deadlines move later when the owner is waited on (`StoredDeadlines.moveLater`,
ToolExecutor.kt:163), but the total owner time per task is not stored anywhere.

### 1.9 Other activity event types [V]

`ActivityType` = SYSTEM, USER_ACTION, REGISTRY, MESSAGE, MODEL_CALL, TOOL_CALL, PERMISSION, APPROVAL, TASK,
MEMORY, DATA_EGRESS, ERROR (core/.../activity/ActivityEvent.kt:35-48). Writers with numbers that matter here:
TASK state changes with kind/status/attempt/revision (ObjectiveJournal.kt:80-91), "Limit reached" (:100-110),
MESSAGE per agent message (:45-77), MEMORY "Memories recalled" with count only, no duration
(AutoRecall.kt:142-157), ERROR from start-up steps (AppStartup.kt:236-249), PERMISSION/APPROVAL from the
broker (PermissionBroker.kt, OwnerServices.kt). Table `activity_events` (ActivityEventEntity.kt:21-48) has
the primary key plus **eight** indexes (unique `id` and seven `(column, at)` indexes, :23-32).
Retention: `activityRetentionDays` default 30 (FrameworkSettings.kt:14), choices 7/30/90/365/forever
(app/.../settings/OrchestrationLimit.kt:77). Pruned only when the process starts and when the setting
changes (AppStartup.kt:147-151, :221-224), never periodically in a long-lived process.

### 1.10 Device and app numbers [V]

- `AndroidDeviceInspector.inspect` (device/.../report/AndroidDeviceInspector.kt:40-60): phone RAM total and
  available, low-RAM flag, storage (StatFs), chip, features. Phone-level only; nothing about this app.
- `JsSandbox` reads `Runtime.totalMemory/freeMemory/maxMemory` for its memory budget (JsSandbox.kt:344-360).
- `LocalWorkspace.usedBytes()` exists (LocalWorkspace.kt:723) but no screen calls it.
- WorkManager is in the version catalog (gradle/libs.versions.toml:17, :49) but no module depends on it.

### 1.11 What is missing for monitoring

| Missing | Why it matters | Where it would be captured |
|---|---|---|
| Time to first words (TTFT) | "Which model answers fastest?" A long answer is slow overall even from a fast model | `DefaultModelGateway.chatStream`, first `emit(chunk)` (:113-115) of the attempt that succeeded |
| Real answer time vs. retry wait | `durationMs` mixes both (:88-89, :126-127) | per-attempt start in the retry loops (:72-75, :100-103) |
| Writing speed | tokens per second = output tokens / (end - first chunk) | same |
| Failure reason with HTTP status / retry-after | "Is it my key, the provider being busy, or my connection?" | `ModelCallJournal.failed` (:88-95) |
| What kind of step (chat, plan, work, review, final document, teammate question) | "Which part of the work costs most?" | new optional field on `CallContext` (LlmClient.kt:136) |
| Cost | the owner pays per token | owner-entered prices × tokens, computed on read |
| Any time window | everything except the activity log is lifetime; the log is pruned | hourly rollups |
| Per-model reliability | `agent_stats` is per agent | rollup keyed by model |
| Tool time without the owner's answer time | a "slow" tool is usually the owner | `pauses.pausedMs()` in ToolExecutor (:132-143) |
| Task time without pauses and owner waits | `totalTaskDurationMs` includes both (ObjectiveJournal.kt:124) | agent-run rollup from AgentRunner, or subtract owner time |
| Health-check history and latency | "Is the provider getting slower / flaky?" | `DefaultProviderManager.check` (:226-236) |
| Recall time | memory upkeep cost at scale (STATUS Phase 3 "not verified") | AutoRecall.kt:125-140 |
| App memory, DB size, workspace size, cache, CPU time, exits by Android | "Is the app slowing my phone?" | new `AppHealth` probe in :device |

---

## 2. The performance-monitoring feature

### 2.1 Questions it answers, in the owner's words

| Question | Answer shown (example wording) | Data |
|---|---|---|
| How much did my agents use today? | "Today: 37 answers from models, 58,200 tokens (the pieces of text models count, roughly ¾ of a word each). Most by Researcher: 31,000." | `usage_hours`, summed from local midnight |
| What did it cost? | "About $0.14 today, from the prices you entered." / "Add the price of Grok 4 in Models to see costs." | `usage_hours` × `model_prices` |
| Which model is fastest for me? | "Grok 4 fast: first words after about 0.8 s, a full answer usually within 6 s, writes about 60 words a second (last 7 days, 42 answers)." Shown only with at least 5 answers in the period | TTFT and duration histograms |
| Which model fails most, and why? | "Grok 4: 3 of 120 answers failed this week. 2 × the provider was busy (rate limit), 1 × no connection." | error columns |
| Which agent fails most? | "Writer: 9 of 10 tasks finished this month. 1 stopped at its time limit." | `agent_run_hours`, `tasks` outcomes |
| Is a provider getting worse? | "xAI answered 47 of 48 checks in 7 days, usually in 0.4 s." | `provider_checks` |
| Am I the bottleneck? | "You answered 12 questions this week, usually within 2 minutes. 1 was refused because nobody answered within 30 minutes." | `owner_requests` |
| Which tools take long? | "Read web page: 14 uses, usually 1.2 s (not counting the time you took to answer)." | `tool_usage_hours` |
| Is the app slowing my phone? | "No sign of trouble. The app uses about 210 MB of memory, its data takes 64 MB, and Android has not had to close it this week. While 2 objectives ran for 1 h 10 min, the phone's battery went from 82% to 71% (Android does not tell apps their own share; see Android's battery settings)." | `app_health_samples`, `app_exits` |

### 2.2 Storage (schema 6, all new tables, purely additive)

Design rules: numbers only, never message content or secrets (same rule as `ModelCallJournal`, :43-46);
one row per (hour, key) updated in place, so size grows with the hours the owner actually used the app, not
with the number of calls; nothing points by foreign key to agents/models, so history survives deletion
(the rollup row keeps the last seen name, like `owner_requests.agent_name`).

**`usage_hours`** (model calls). Primary key `(hour_start, model_id, agent_id, source)`.
- `hour_start` INTEGER: UTC epoch ms truncated to the hour. `agent_id` TEXT, `''` when none. `source` TEXT:
  `CHAT` when `CallContext.taskId == null`, else `OBJECTIVE` (optionally the finer `CallKind` of 2.3).
- labels: `model_label`, `provider_id`, `provider_label`, `locality`.
- counts: `calls`, `ok`, `failed`, `cancelled`, `blocked`, `retries` (attempts − 1), `substituted`,
  `truncated` (finish LENGTH), `refused`, `no_usage` (calls without reported usage).
- failures by reason: `err_auth`, `err_rate_limited`, `err_overloaded`, `err_timeout`, `err_network`,
  `err_server`, `err_invalid`, `err_context`, `err_not_found`, `err_malformed`, `err_other` (one per
  `LlmException` subclass family, LlmClient.kt:37-88).
- tokens: `input_tokens`, `output_tokens`, `cached_input_tokens`.
- time: `duration_ms_sum`, `answer_ms_sum` (last attempt only), `ttft_ms_sum`, `ttft_count`,
  `gen_ms_sum`, `gen_tokens` (for words per second), `ttft_hist`, `answer_hist` (TEXT, 12 comma-separated
  counts with bucket edges 250 ms, 500 ms, 1, 2, 3, 5, 8, 13, 21, 34, 55 s, open end; medians and 90th
  percentiles are read from these).
- `bytes_sent`.
- Indexes: `(model_id, hour_start)`, `(agent_id, hour_start)`.
- Size [A]: 2,000 active hours a year × ~6 (model, agent, source) combinations ≈ 12,000 rows ≈ 2 MB a year.

**`tool_usage_hours`**: key `(hour_start, tool_id, agent_id)`; `tool_label`; counts per `ToolCallEnd`
(OK, FAILED, DENIED, TIMED_OUT, REJECTED, cancelled) (ToolExecutor.kt:55-67); `run_ms_sum` (duration −
owner wait), `owner_wait_ms_sum`, `owner_waited` (calls that waited), `run_hist`.

**`agent_run_hours`**: key `(hour_start, agent_id, source)`; counts per `RunEnd` (COMPLETED, TRUNCATED,
REFUSED, MODEL_CALL_LIMIT, TOOL_CALL_LIMIT, TIMED_OUT, FAILED, STOPPED_BY_OWNER; AgentRun.kt:118-139) plus
`cancelled` (a run that ended by cancellation reaches the `finally` with no ending, AgentRunner.kt:127-133);
`run_ms_sum`, `owner_wait_ms_sum`; task outcomes from `ObjectiveJournal.countFinished` (completed, failed,
timed out, cancelled) and review outcomes from ReviewSteps.kt:147 under the same key, so "which agent fails
most" has a time window. `agent_stats` stays as it is (the selector depends on it).

**`provider_checks`**: `id`, `provider_id`, `at`, `status`, `latency_ms`, `message` (already redacted,
DefaultProviderManager.kt:262), `trigger` (START, FOREGROUND, OWNER). Keep 90 days and at most 500 rows
per provider.

**`model_prices`** (owner-entered): `model_id` PK, `input_per_mtok_micros`, `output_per_mtok_micros`,
`cached_input_per_mtok_micros` (nullable), `currency_label` (free text, default "$"), `updated_at`. A
separate table on purpose: `ModelListingMerge` rewrites model rows on refresh, and the gateway refuses a
retry when `model.capabilities` changed (DefaultModelGateway.kt:207-208), so prices must not live in the
model record.

**`app_health_samples`**: `at`, `pid`, `process_uptime_ms`, `pss_kb`, `java_heap_kb`, `native_heap_kb`,
`cpu_ms` (cumulative for the process), `net_rx_bytes`, `net_tx_bytes` (cumulative since boot, own UID),
`db_bytes`, `workspace_bytes`, `cache_bytes`, `battery_pct`, `charging`, `thermal_status`,
`objectives_running`, `screen_session`, `fg_service`. Every 15 minutes while the process lives, plus at
start and when an objective ends; deltas are computed between samples of the same `pid`. Keep 14 days
(≤ 1,400 rows).

**`app_exits`**: `at`, `reason`, `importance`, `pss_kb`, `rss_kb`, `description`, `state_summary`
(bytes set by `setProcessStateSummary`). Keep the newest 100.

Migration 5 → 6: CREATE TABLE statements only (plus the task-table change of section 3, P1). Optional
one-time backfill of `usage_hours` and `tool_usage_hours` from the `MODEL_CALL` / `TOOL_CALL` events still
in the log, run after start-up in the background (not inside the migration, which must stay fast), so the
owner sees up to 30 days of history right after updating.

### 2.3 Capture: exact changes

1. **Model calls.** Add `UsageLedger` (core port, see 2.6) to `DefaultModelGateway`'s constructor and to
   `ModelCallJournal`. In `ModelCall` (ModelCallJournal.kt:18-41) add `attemptStartedAt`, `firstChunkAt`,
   `firstTextAt` (a reasoning model sends `ChatChunk.Reasoning` first, Chat.kt:169), `retryWaitMs`. Set
   them in `chatStream` (DefaultModelGateway.kt:100-118: attempt start before `route.client.chatStream`,
   first chunk inside the `else` branch at :113-116) and in `chat` (:72-89). Measure on a monotonic
   `TimeSource` (as ToolExecutor does, :117) rather than `clock.now()`, so a clock change cannot produce
   odd durations (today hidden by `coerceAtLeast(0)`, :270). In `ModelCallJournal.record` (:109-132) also
   put `ttftMs`, `answerMs`, `httpStatus`, `retryAfterMs` into the event and call
   `ledger.modelCall(sample)`, best effort like the rest of the book-keeping (:272-280).
2. **Optional `CallKind`.** `CallContext.kind: CallKind = CallKind.OTHER` (CHAT, PLAN, WORK, REVIEW,
   SYNTHESIS, TEAMMATE, ARBITRATION), set by `DefaultAgentChat`, `StepRunner` callers and `TeamDesk`.
   Additive with a default, so every existing call site compiles.
3. **Tool calls.** In `ToolExecutor.execute` pass `pauses.pausedMs()` into `record` (call at :143 is inside
   the `measuringPauses` lambda, :132); log `ownerWaitMs` in the event and call
   `ledger.toolCall(toolId, agentId, end, durationMs - ownerWaitMs, ownerWaitMs)`.
4. **Agent runs.** In `AgentRunner`'s `finally` (:128-133), next to `saveStats()`, call
   `ledger.agentRun(agentId, source, ending?.end, runMs, ownerWaitMs)`; the run already knows its owner
   waits through `Waits` (:403-413).
5. **Task outcomes.** `ObjectiveJournal.countFinished` (:122-136) and `countStats` callers also add to
   `agent_run_hours`, with the task time minus the owner time the task's deadlines were moved by.
6. **Provider checks.** `DefaultProviderManager.check` (:226-236) appends a `provider_checks` row with
   `health.latencyMs` (all providers, not only cloud ones; the row holds no address and no key).
7. **Approvals.** Nothing to write: query `owner_requests` by `created_at` (index at
   OwnerRequestEntity.kt:34).
8. **Recall.** Optional: `durationMs` in the "Memories recalled" event (AutoRecall.kt:149-155).
9. **App health.** New `AndroidAppHealthProbe` in :device (2.5) and a `HealthSampler` in :app started by
   `AppStartup`.

### 2.4 Aggregation, retention, housekeeping

- Rollups are written per call inside one short write transaction (read row, add, upsert), the same
  pattern as `TaskDao.update` (TaskDao.kt:52-58). One extra small write per model call or tool call; network
  calls take seconds, so the cost is negligible [A].
- Days in the owner's time zone are computed on read from UTC hours. Exact for whole-hour time zones; in
  half-hour zones up to 30 minutes of use can land on the neighbouring day [A, stated in the UI help].
- `UsageHousekeeping` (new, :app, started from `AppStartup.start`): at start and then every 24 hours while
  the process lives: delete `usage_hours`, `tool_usage_hours`, `agent_run_hours` older than "Keep usage
  history for" (new setting `usageRetentionDays`, default 730, choices 90/365/730/forever); delete
  `app_health_samples` older than 14 days, `provider_checks` older than 90 days or beyond 500 per provider,
  `app_exits` beyond 100. Also move the activity-log prune (AppStartup.kt:221-224) into this daily loop,
  which fixes the "pruned only at start" gap.
- Rollups are independent of the activity retention and of "Clear the activity log"; "Clear usage
  history" is its own confirmed action. Each call's detail stays one tap away: "See these calls in the log"
  opens Activity with `ActivityFilter(types = {MODEL_CALL}, agentId = …)` (ActivityEvent.kt:63-78), as long
  as the log keeps them.
- Database compaction: SQLite does not shrink the file after deletes unless vacuumed, and nothing in the
  data layer sets `auto_vacuum` or runs `VACUUM` [V: no match in data/src/main for VACUUM/auto_vacuum; the
  SQLite behaviour is K]. Show reclaimable space (`PRAGMA freelist_count × page_size`) and offer
  "Compact the database" when it is over 25% and over 20 MB, run only by the owner, only while no objective
  runs, on the data layer's IO dispatcher. `VACUUM` needs free space about the size of the database [K];
  check `getAllocatableBytes` first.

### 2.5 App health: what Android lets an app read about itself

| What | API | Android version and rule | Use |
|---|---|---|---|
| Own memory (PSS, Java heap, native heap) | `Debug.getMemoryInfo(Debug.MemoryInfo)`; `MemoryInfo.getTotalPss()` (API 5), `getMemoryStat("summary.java-heap" / "summary.native-heap" / "summary.total-pss")` (API 23) [V] https://developer.android.com/reference/android/os/Debug.MemoryInfo | All supported versions (minSdk 28). Reads the process's own mappings; costly enough to keep to every 15 minutes on a background thread [A] | memory line, samples |
| Other processes' memory | `ActivityManager.getProcessMemoryInfo(int[])` [V] https://developer.android.com/reference/android/app/ActivityManager#getProcessMemoryInfo(int[]) | Since Android 10 (Q): only the caller's own UID, and "the sample rate allowed by this API is significantly limited, if called faster the limit you will receive the same data as the previous call". Do not use; `Debug.getMemoryInfo` gives the same for the own process | — |
| How much memory the app may use | `ActivityManager.getMemoryClass()`, `getLargeMemoryClass()`, `isLowRamDevice()` | API 1 / 11 / 19 [K] | "of about 512 MB the app may use" |
| Memory pressure signals | `ComponentCallbacks2.onTrimMemory` [V] https://developer.android.com/reference/android/content/ComponentCallbacks2 | From API 34 apps receive only `TRIM_MEMORY_UI_HIDDEN` and `TRIM_MEMORY_BACKGROUND`; `RUNNING_*`, `MODERATE`, `COMPLETE` are "not notified since API level 34" and deprecated in 35; `onLowMemory` "since API level 34 is never called" | count `BACKGROUND` events; not a running-low signal on 14+ |
| Why Android closed the app | `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, maxNum)` → `ApplicationExitInfo` [V] https://developer.android.com/reference/android/app/ApplicationExitInfo | API 30+ (Android 11). Own package needs no permission (another UID needs DUMP). Reasons: LOW_MEMORY (only where `isLowMemoryKillReportSupported()`, API 30; otherwise SIGNALED + SIGKILL), ANR, CRASH, CRASH_NATIVE, EXCESSIVE_RESOURCE_USAGE, USER_REQUESTED (Force stop / swiped away), FREEZER (API 33), PACKAGE_UPDATED and PACKAGE_STATE_CHANGE (API 34), MEMORY_LIMITER and ANOMALY (37.2), others. `getPss/getRss` are the last sample. Ring buffer: read at every start and store new ones | `app_exits`; links to crash recovery ("Your objective was interrupted because Android needed memory") |
| What the app was doing when it died | `ActivityManager.setProcessStateSummary(byte[])` [V] same page | API 30+. "System might decide to throttle the calls ... excessive calls ... could result a RuntimeException": call only when the running set changes, catch the exception | e.g. `{"o":2,"sh":0,"scr":1}` |
| Start-up time | `ActivityManager.getHistoricalProcessStartReasons(int)`, `addApplicationStartInfoCompletionListener` → `ApplicationStartInfo` [V] same ActivityManager page | API 35+ (Android 15). Below 35: `Process.getStartElapsedRealtime()` (API 24) [K] to first frame | "The app took 1.8 s to open" |
| Processor time | `Process.getElapsedCpuTime()` [K] https://developer.android.com/reference/android/os/Process#getElapsedCpuTime() | API 1; own process | "used 3 minutes of processor time in the last hour" |
| Network bytes | `TrafficStats.getUidRxBytes/getUidTxBytes(Process.myUid())` [V] https://developer.android.com/reference/android/net/TrafficStats | Since Android 7 (N) only the calling UID; `UNSUPPORTED` (-1) for others or where unsupported; reset at reboot | delta per sample |
| App's storage on the phone | `StorageStatsManager.queryStatsForUid(StorageManager.UUID_DEFAULT, myUid)` → app/data/cache bytes [V] https://developer.android.com/reference/android/app/usage/StorageStatsManager | API 26+; "no permissions are required when calling these APIs for your own package or UID"; "may take several seconds ... only be called from a worker thread" | "The app takes 312 MB: app 82, data 210, cache 20" |
| Database / workspace / cache sizes | `getDatabasePath("aicc.db")` + `-wal` + `-shm` file lengths; `PRAGMA page_count/freelist_count`; workspace running total (P6); cache folder walk | any | breakdown |
| Battery | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` (API 21), `BATTERY_PROPERTY_CURRENT_NOW` / `CURRENT_AVERAGE` (µA, API 21), `isCharging()` (API 23) [V] https://developer.android.com/reference/android/os/BatteryManager | Phone-wide, not per app. Unsupported returns `Integer.MIN_VALUE` for apps targeting 28+ (this app targets 36), `Long.MIN_VALUE` for long properties | "battery went from 82% to 71% while agents worked" |
| Per-app battery share | none for ordinary apps [A: no public API found; per-app battery statistics are system-only] | — | Say so, and open Android's own screen with `Intent.ACTION_POWER_USAGE_SUMMARY` or the app's details page (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`) [K] |
| Battery restrictions on this app | `ActivityManager.isBackgroundRestricted()` (API 28) [V]; `PowerManager.isIgnoringBatteryOptimizations` (API 23), `isPowerSaveMode` (API 21) [V] https://developer.android.com/reference/android/os/PowerManager; `UsageStatsManager.getAppStandbyBucket()` (API 28, no permission for the calling app; RESTRICTED bucket API 30) [V] https://developer.android.com/reference/android/app/usage/UsageStatsManager | "If true ... jobs and alarms will not execute and foreground services cannot be started unless an app activity is in the foreground" (isBackgroundRestricted) | warnings that explain slow or stopped background runs |
| Phone temperature | `PowerManager.getCurrentThermalStatus()` / `addThermalStatusListener` (API 29), `getThermalHeadroom(int)` (API 30; `NaN` if unsupported or called more than about once a second) [V] | 29+/30+ | "The phone is hot; Android is slowing it down" |
| Screen jank (optional) | AndroidX JankStats (`androidx.metrics:metrics-performance`) [A: new dependency, version not checked] | — | defer unless the owner reports stutter |

None of these needs a new manifest permission [V for the rows marked V; the per-app battery row is A].

### 2.6 Ports and modules

- `:core` `core/usage/Usage.kt`: `UsageLedger` (write side; framework-facing: `modelCall`, `toolCall`,
  `agentRun`, `providerCheck`), `UsageReports` (read side; UI: `observeModels(range)`,
  `observeAgents(range)`, `observeTools(range)`, `observeToday()`, `observeProviderChecks(providerId, limit)`,
  `observeOwnerAnswers(range)`), `ModelPrices` (read) and `OwnerModelPrices` (set/clear, owner-only, added
  to the owner-only list the architecture tests walk, so no agent tool can change a price).
  `core/device/AppHealth.kt`: `AppHealthProbe` (`sample()`, `newExits(since)`, `markState(bytes)`).
- `:data`: entities and `UsageDao` (upsert-by-transform, range sums), `RoomUsageLedger`,
  `RoomUsageReports`; schema 6 export; migrations 5 → 6, 1 → 6 step by step and 1 → 6 directly tested as in
  earlier phases (STATUS Phase 5 verification table).
- `:testkit`: `InMemoryUsageLedger` and a `UsageLedgerContract` suite run against both, as for every port
  (ARCHITECTURE.md section 2 rules).
- `:device`: `AndroidAppHealthProbe` with version guards (`Build.VERSION.SDK_INT >= 30` for exit info,
  `>= 35` for start info).
- `:app`: `UsageViewModel`, `AppHealthViewModel`, `HealthSampler`, `UsageHousekeeping`, UI.

### 2.7 Where it appears (8 sections, plain language)

- **Dashboard: "Today" tile** (new card in DashboardSections.kt next to the existing cards): answers from
  models, failed answers, tokens, estimated cost when every used model has a price. Tap: Activity > Usage.
  Reads `observeToday()` only (a few rows).
- **Activity: two tabs, "Log" (today's screen) and "Usage"**. Usage has period chips Today / 7 days /
  30 days and sections: How much was used (by agent, by model), Cost (if prices), Speed (per model, only
  with 5+ answers), Reliability (per agent and per model, failures by reason in words: "the provider was
  busy", "the key was refused", "no connection", "the request was too long for the model"), Waiting for you
  (answer times, expired requests), Tools. Each row links to the log filtered accordingly. Activity is
  "what happened"; usage is its summary, so no ninth section is needed.
- **Models > model detail: "Speed and reliability" card** plus price fields "Price per million tokens: for
  text sent __, for text written __ (you enter these from your provider's price page; the app never looks
  them up)". Changing a price changes past estimates too, and the card says so.
- **Models > provider detail: "Connection history"**: "Answered 47 of 48 checks in 7 days, usually in
  0.4 s", last failures with their message.
- **Agents > agent detail**: `AgentTrackRecord` (AgentTrackRecord.kt:30-103) gains "Last 30 days": tasks
  finished/failed/timed out, time per task not counting waits for you, tokens, most common failure.
- **Settings > "This app on your phone"** (extends `DeviceSection`, app/.../settings/DeviceSection.kt:36):
  memory, storage breakdown, reclaimable database space with "Compact the database", times Android closed
  the app and why, battery restrictions and temperature, "Keep usage history for", "Clear usage history".
- Owner rules: every estimate says "about" or "estimated"; nothing here is a half-working control, so no
  "(test)" label is needed if all of it is built. If JankStats or the backfill are left out, no control for
  them is shown. Prices are never fetched: manual entry only in Phase 6 (a fetch would be data leaving the
  phone and is not needed). Usage data never leaves the phone; an optional "Share usage as a table" is an
  owner action through the share sheet, like the graph export, and blocked in local-only mode the same way.
  Agents get no tool that reads usage or prices.

### 2.8 Tests

JVM: gateway records TTFT/answer/retry wait with a scripted streaming client and the test scheduler's
`TimeSource`; error mapping to columns; histogram medians; time-zone day sums (including a half-hour zone);
ToolExecutor owner-wait split; AgentRunner run outcomes on every `RunEnd` and on cancellation. Contract
suite for the ledger (in-memory and Room). Robolectric for `AndroidAppHealthProbe` on API 28, 30 and 35
shadows (exit info and start info are absent below 30 / 35). Screenshots of the tile, the Usage tab, the
model card and the Settings card in both themes and at 360 dp. Emulator: one objective with the simulator,
then the tile and the Usage tab show non-zero numbers and `app_exits` records a Force stop as
USER_REQUESTED.

---

## 3. Known performance problems, ranked

Ranking = (how often it runs) × (how much it reads or computes) × (whether it grows without bound).
Sizes are assumptions [A]: a typical objective has 6-10 tasks with results of 2-20 KB and a final document
of 5-30 KB; the cap is 24 tasks (FrameworkSettings.kt:22) and 1 MB of text per task
(RoomTaskRepository.kt:119). During a run the orchestrator writes `tasks` from 34 call sites
(`tasks.update/upsert/upsertAll`, `ctx.account`, `moveLater` in framework/.../orchestration and tools) [V],
i.e. several writes per step.

How Room re-runs queries [K, Room behaviour]: a `Flow` query is re-run whenever any table it reads is
written, whatever row changed. So every observer of `tasks` re-runs on every task write anywhere.

### P1 (highest). Whole task trees observed with `SELECT *`, one flow per objective

Code [V]:
- `TaskDao.OBJECTIVES` and `TREE` are `SELECT *` (TaskDao.kt:69-70); `RoomTaskRepository.observeTree` /
  `observeObjectives` decode every row, seven JSON columns each (TaskMappers.kt:54-70), then
  `distinctUntilChanged` compares the full lists, big strings included (RoomTaskRepository.kt:49-53).
- `ObjectivesViewModel.trees` (app/.../tasks/ObjectivesViewModel.kt:76-77): observes every objective ever
  made, then one `observeTree` flow per objective. With 50 objectives that is 51 full queries per task write
  while the Tasks tab is open (and 5 s after, `WhileSubscribed(5_000)`, :112). Grows without bound with
  the number of objectives.
- `AgentDetailViewModel.recentTasks` (app/.../agents/AgentDetailViewModel.kt:141-150): 20 full trees
  (`RECENT_OBJECTIVES = 20`, :245) to show 6 tasks.
- `DashboardViewModel.orchestration` (app/.../dashboard/DashboardViewModel.kt:84-86): the full trees of every
  running objective, to show titles of current tasks.
- `ObjectiveWorkService.notices` (app/.../work/ObjectiveWorkService.kt:94-98): the full trees of running
  objectives, **also with the screen off**, for a notification that needs two counts
  (`WorkNotice.of`, ObjectiveProgress.kt:53-64; `ObjectiveProgress.of`, :22-25).
- `ObjectiveViewModel.stored` (ObjectiveViewModel.kt:181) and `TaskDetailViewModel.stored`
  (TaskDetailViewModel.kt:160-162): one full tree each (expected, but the result document and every task's
  result are re-read per write although only the Result tab and the task's own section show them).
- Column order makes projections alone insufficient: in `tasks` the big columns `instructions` and `result`
  sit in the middle, before `reviews_json`, `usage_json` and all the time columns (schema 5 `createSql`,
  data/schemas/.../5.json) [V]. SQLite stores a row as one record; a value too large for the page spills into
  a chain of overflow pages, and reading a column stored after it means walking that chain [K]. With
  SQLCipher every page read is also decrypted and authenticated [K]. So even `SELECT id, status, updated_at`
  reads through the results.

Fix:
1. Migration 5 → 6 rebuilds `tasks` with the small columns first and the large ones last (`instructions`,
   `result`, `error`, `status_detail`, `criteria_json`, `reviews_json` at the end), the usual
   create-copy-drop-rename migration, indexes recreated. Alternative with the same effect: a side table
   `task_texts(task_id PK, instructions, result, reviews_json)` joined only where needed. The rebuild keeps
   one table and the existing contract suite; prefer it.
2. Add plain columns written by `TaskMappers.toEntity`: `review_count`, `last_verdict`, `result_bytes`,
   `has_result`, `status_detail_short` (first 300 chars). Backfill in the migration with Kotlin code reading
   the JSON (no dependence on SQLite's JSON functions).
3. New `TaskRepository` reads (core/.../tasks/TaskPorts.kt): `observeObjectiveRows(limit)` as one aggregate
   query:
   ```sql
   SELECT o.id, o.title, o.status, o.status_detail_short, o.created_at, o.started_at, o.finished_at,
          SUM(CASE WHEN t.parent_id = o.id AND t.id <> o.id AND t.kind IN ('WORK','SYNTHESIS') THEN 1 ELSE 0 END) AS planned,
          SUM(CASE WHEN t.parent_id = o.id AND t.id <> o.id AND t.kind IN ('WORK','SYNTHESIS')
                   AND t.status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed
   FROM tasks o JOIN tasks t ON t.root_id = o.id
   WHERE o.kind = 'OBJECTIVE' GROUP BY o.id ORDER BY o.created_at DESC, o.id ASC LIMIT :limit
   ```
   (equivalent to `isPlanStep`, ObjectiveProgress.kt:33-34), plus participants from
   `SELECT root_id, assignee_id, MIN(created_at) FROM tasks WHERE assignee_id IS NOT NULL GROUP BY root_id, assignee_id`;
   `observeTreeSummary(rootId)` (plan rows: id, parent, kind, status, assignee, order, attempt, revision,
   needs_review, depends_on_json, review_count, last_verdict, short detail, times); `observeTaskText(id)`
   for the Result tab and the task screen only; `observeRecentByAgent(agentId, limit)` with an index
   `(assignee_id, updated_at)`; `observeProgress(rootIds)` for the notification and the Dashboard.
4. Objectives list: newest 50 with "Show older" (keyset on `created_at, id`), not Paging 3 (not a
   dependency today).
5. Keep `getTree` full for the orchestrator (it needs everything, and runs once per step, not per write).

Expected effect [A]: a write during a run re-reads a few KB instead of up to MBs per observer.

### P2. Objective screen rebuilt every second on the main thread

Code [V]: `ObjectiveViewModel.uiState` combines the stored tree, messages, registry, settings, the
objective's log (200 events, `LOG_LIMIT` :346), local state, a 1-second ticker while live
(`ticks`, :184; `everySecond`, :350-355) and requests, and calls `build()` (:186-193) on every emission. `build`
recomputes plan rows (`planRows`, TaskPresentation.kt:125-151), all messages (`messagesUi`, TaskPresentation.kt:79; up to 200
per objective, FrameworkSettings.kt:24), 200 log rows with time formatting, egress, limits (ObjectiveViewModel.kt:222-293). There
is no `flowOn`, so `combine` runs in `viewModelScope` on the main thread [V: no `flowOn`/`Dispatchers` in
ObjectiveViewModel.kt; the only view model with a computation dispatcher is MemoryGraphViewModel.kt:119].
The same pattern without a ticker: ActivityViewModel (300 rows, :73-84, grouped at :101-118),
DashboardViewModel (:101-132), TaskDetailViewModel (:170-171).

Fix: give the ticking parts (elapsed time, time left) their own small `StateFlow` that the header reads;
keep `build()` for data changes only; add `.flowOn(Dispatchers.Default)` (injectable for tests) before
`stateIn` in these four view models; `conflate()` the inputs so a burst of writes builds once. Test: a VM
test asserting that 10 ticks cause no plan/messages rebuild (count calls through a test hook).

### P3. Activity log: re-queried on every insert, grows, pruned only at start

Code [V]: `RoomActivityLog.observe` (RoomActivityLog.kt:42-48) runs a fresh query and decodes `data_json`
and `egress_json` of every row (ActivityMappers.kt:39-55) on each insert; open observers are the Dashboard (5
rows, DashboardViewModel.kt:103), the objective log (200) and the Activity screen (300). Every model call,
tool call, message and task change inserts, into a table with eight secondary indexes
(ActivityEventEntity.kt:23-32), so each insert updates nine B-trees. Pruning only at start and on setting
change (AppStartup.kt:147-151); retention "forever" offered (OrchestrationLimit.kt:77); no `VACUUM`; no paging
or text search (STATUS Phase 1 rough edge).
Size [A]: up to about 2,000 events for a maximal objective (24 tasks × up to 24 model and 40 tool calls,
FrameworkSettings.kt:22-26, plus messages); typically 100-300. Ten objectives a day for 30 days at 1 KB each
is tens of MB.

Fix: daily prune (2.4); with the usage rollups the log no longer has to be kept long for statistics, so
default retention can stay 30 days; "Load older" keyset paging on the Activity screen; `conflate()` +
`sample(500 ms)` on the Activity and objective-log flows while a run is live; consider dropping the
`(has_egress, at)` and `(severity, at)` indexes only after measuring (they serve existing filters; keep
unless a write benchmark shows they matter).

### P4. Memory counts load every memory, and recall rewrites the table every turn

Code [V]: the Dashboard counts memories by observing all of them (`service.observe(MemoryActor.Owner)`,
DashboardViewModel.kt:75-77); the agent screen counts its private memories the same way
(AgentDetailViewModel.kt:136-137); `RoomMemoryRepository.observe` has no limit (RoomMemoryRepository.kt:68-72)
and reads content, provenance and history JSON. Each recall writes `access_count`/`last_accessed_at`
(MemoryRecall.kt:55-56 → RoomMemoryRepository.kt:154-157 → MemoryDao.kt:66, :74), which invalidates every
`memories` observer, so during an objective every agent turn makes the open Dashboard re-read the whole
memory store. Grows with the store (STATUS Phase 3 rough edge "Counts ... load the full memory list").

Fix: `MemoryRepository.observeCount(filter)` as `SELECT COUNT(*)` (and per-space counts with `GROUP BY`);
move the access counters into a separate small table `memory_access(memory_id, count, last_at)` so recall
writes do not invalidate list observers (recall ranking reads it with a join), or batch them and write at
most every 30 s.

### P5. Open conversations re-read in full on any transcript append

Code [V]: `observeEntries` (RoomConversationRepository.kt:57-58) runs `ENTRY_ROWS`
(ConversationDao.kt:104-108) for the whole conversation, up to 256 KB of message head per entry
(ConversationEntryRow.kt:27), on every insert into `conversation_entries`, which holds both chats and every
task transcript. So an open chat or task screen re-reads its entire transcript each time any running agent
appends a message. `ChatViewModel` also observes all conversations to find one (ChatViewModel.kt:110).
STATUS Phase 2 rough edge "Conversations are not paged".

Fix: append-only entries allow incremental reads: keep the loaded list in the view model and fetch only
`seq > lastSeq` when a cheap `SELECT MAX(seq)` flow for this conversation changes; show the newest 50
entries with "Show earlier"; `observeConversation(id)` for the chat header.

### P6. Workspace size walks the whole tree on every change

Code [V]: `treeSize(base())` (LocalWorkspace.kt:1209-1224, a `Files.walkFileTree`) runs in `quotaProblem`,
`recordProblem` and `trashRoomProblem` (:762-785) and at :268, :654, :677, :712 — on every write, trash and
replace. Cost is linear in the number of files; harmless at 100 files, noticeable at tens of thousands [A].

Fix: a running total kept under the existing change lock, recomputed at start, after every shell command
(which changes files outside the app's own accounting and already holds the lock, `Workspace.runCommand`)
and when `changedOutside()` (:708) reports a change. Also feeds `workspace_bytes` in 2.5 cheaply.

### P7. The final document is one non-lazy block

Code [V]: the Result tab puts the whole document into one LazyColumn item (ObjectiveScreen.kt:138-140);
`MarkdownText` parses on the main thread (`remember(source) { Markdown.parse(source) }`, MarkdownView.kt:48)
and lays out every block in a plain `Column` (:53-59). A long document composes and measures all of it at
once.

Fix: parse on `Dispatchers.Default` (`produceState`), emit one lazy item per block with a stable key
(block index + hash).

### P8. Registry snapshot not shared, re-emitted after every run

Code [V]: every `directory.observe()` call builds its own five Room flows plus settings
(DefaultRegistryDirectory.kt:26-45); most screens subscribe; `agent_stats` is written at the end of every run
(AgentRunner.kt:419-428) and of every finished task (ObjectiveJournal.kt:122-136), so all open screens
rebuild. Tables are small, so this is cheap per event but multiplied by screens.

Fix: one shared snapshot in AppGraph (`shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1)`).

### P9. Tables that only grow

`owner_requests` is never deleted (OwnerRequestEntity.kt:9-11); tasks, agent messages and transcripts stay
until the owner deletes the objective. Growth is bounded by human activity, so low priority. Offer
"Delete finished objectives older than …" in Settings next to the usage retention, with a confirmation that
names how many objectives it removes; delete owner requests older than a year whose objective is gone.

### P10. Small items

- Lazy lists already have keys everywhere [V: all 29 `items(`/`itemsIndexed(` calls in app/ui pass `key =`].
- JSON decoding runs off the main thread in the repositories (`flowOn(dispatcher)`, e.g.
  RoomTaskRepository.kt:49-53; the two exceptions found pass through helpers) [V for tasks, activity,
  memory, conversations]; the main-thread cost is in the view models (P2).
- The agent-network pulse animation runs only while there are pulses (AgentNetworkView.kt:96-107) [V].

### Ranking table

| Rank | Problem | Runs | Grows with | Fix size |
|---|---|---|---|---|
| 1 | P1 task trees `SELECT *`, N+1 flows, big columns mid-row | every task write, 4 screens + foreground service (screen off too) | objectives × results | migration + 5 DAO queries + 5 VMs |
| 2 | P2 per-second main-thread rebuild | every second while an objective screen is open | messages, log | VM-only |
| 3 | P3 activity log churn and growth | every event | days × activity | housekeeping + paging |
| 4 | P4 memory counts + recall writes | every agent turn while Dashboard open | memories | count query + access table |
| 5 | P5 transcript re-read | every transcript append | conversation length | incremental read + paging |
| 6 | P6 workspace walks | every file change | files | running total |
| 7 | P7 document rendering | opening the Result tab | document length | UI-only |
| 8 | P8 unshared registry | every run end | screens | one line in AppGraph |
| 9 | P9 never-deleted rows | — | owner activity | owner setting |

---

## 4. Open points and what must be checked on the phone

- Whether xAI reports usage in every streamed reply (the adapter asks for it, OpenAiRequests.kt:117; the
  live behaviour is not verified: no real provider was contacted, STATUS Phase 2). If not, `no_usage`
  counts it and the UI says "the provider did not report tokens for N answers".
- `Debug.getMemoryInfo` cost on the owner's phone (sample interval may need to grow).
- `isLowMemoryKillReportSupported()` on the owner's phone (otherwise low-memory kills appear as SIGKILL).
- The SQLite overflow-page reasoning in P1 is from file-format knowledge [K]; add a JVM/Robolectric
  benchmark test (1,000 tasks with 50 KB results, time `observeTreeSummary` before and after the rebuild) so
  the gain is measured, not assumed.
- Coordination: the background-execution and recovery readers may also add tables in schema 6; there must be
  one 5 → 6 migration.

## 5. Sources

Android reference pages read in this session (November 2026; the API levels are as the pages state them):
- https://developer.android.com/reference/android/app/ActivityManager (getProcessMemoryInfo, getHistoricalProcessExitReasons, getHistoricalProcessStartReasons, addApplicationStartInfoCompletionListener, isBackgroundRestricted, isLowMemoryKillReportSupported, setProcessStateSummary)
- https://developer.android.com/reference/android/app/ApplicationExitInfo
- https://developer.android.com/reference/android/os/Debug.MemoryInfo
- https://developer.android.com/reference/android/content/ComponentCallbacks2
- https://developer.android.com/reference/android/os/BatteryManager
- https://developer.android.com/reference/android/net/TrafficStats
- https://developer.android.com/reference/android/app/usage/StorageStatsManager
- https://developer.android.com/reference/android/app/usage/UsageStatsManager
- https://developer.android.com/reference/android/os/PowerManager
Not re-fetched [K]: https://developer.android.com/reference/android/os/Process (getElapsedCpuTime,
getStartElapsedRealtime), Room invalidation behaviour (https://developer.android.com/training/data-storage/room),
SQLite file format and VACUUM (sqlite.org is not reachable from this environment).
