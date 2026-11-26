# Hand-off from WP4 (usage, cost, daily limits and provider trouble)

Branch `p6/wp4` (worktree `/home/user/p6-wp4`), from 90e1d70. WP4 changed only files it owns: framework/llm/*,
framework/registry/*, the new device/net/AndroidNetworkStatus.kt, app/di/wiring/ModelsWiring.kt, their tests, and the
one line the security hand-off allows in framework `operator/OperatorDependencyTest.kt` (`agentFacingRoots()` +=
`DefaultProviderRechecks`). Nothing needs applying in a file WP4 does not own for the build or the suite to pass; the
items in section 2 are what later packages should pick up.

## 1. What is there (by the plan's WP4 items)

| Item | Where | What |
|---|---|---|
| 1 Capture | framework/llm/ModelCallJournal.kt, DefaultModelGateway.kt | `ModelCall` gains `attemptStartedAt`, `firstChunkAt`, `firstTextAt`, `answeredIn`, `retryWaitMs`, `httpStatus`, `retryAfterMs`, `rateLimitedWaits`, `lastFailure`, `ticket`, `requestBytes`, measured on the gateway's `timeSource`. Every MODEL_CALL event adds `answerMs`, `ttftMs` (streamed only), `firstTextMs`, `retryWaitMs`, `httpStatus`, `retryAfterMs`, `rateLimitedWaits` when they apply. Every call is recorded once in `UsageLedger` (best effort, never fails the call) with agent, task, objective and chat from `CallContext`, source OBJECTIVE (task or objective) / CHAT (conversation, no task) / OTHER, tokens and cached tokens as reported, retries, substitution (D29), truncation (`FinishReason.LENGTH`), bytes of every attempt. A call refused before it sent anything is BLOCKED; one that sent a request is OK, FAILED (with `ErrorClass.of`) or CANCELLED. |
| 2 Daily limits | framework/llm/DailyLimiter.kt | `DailyLimiter(reports, prices, settings, clock, zone)`: `reserve(model, locality, held)` before every attempt (first attempts reserve a place, under one `Mutex`; retries check again under their own place), `settle(ticket, model, tokens, at)` when the call ends, `today(): DailyUse`. `DailyUse.of(byModel rows, prices)` is the same count for screens. Refusal: `LlmException.DailyLimitReached(DailyLimits.sentence(kind, currency))`, e.g. "Today's limit of 200 model answers is reached. It starts again at midnight, or you can raise it in Settings > Daily limits." Q2 defaults are on from the first start (they are `FrameworkSettings`' defaults). |
| 3 Provider trouble | framework/llm/ProviderOutages.kt, DefaultProviderRechecks.kt, ProviderChecks.kt; device/net/AndroidNetworkStatus.kt | Three calls in 5 minutes that ended with Network/Timeout/Server/Overloaded store OFFLINE "Not answering since 10:42" (the first of them, owner's zone), never over AUTH_ERROR and never rewriting an existing OFFLINE (`ProviderConnections.storeStatusUnless`). `DefaultProviderRechecks` (holds no provider manager, no vault, no provider store) re-checks OFFLINE/ERROR providers at 1, 2, 5, 10, 20 … 60 minutes after it noticed them (`RecheckSchedule`), at once when `NetworkStatus` reports a network again, skips off-phone ones while there is no network, never AUTH_ERROR, disabled, or off-phone in local-only mode; `recheckIfStale(id, olderThanMs)` bounded at 10 s (`RecheckSchedule.CHECK_BOUND_MS`), restarts the hour, shares one check among concurrent callers; `timedWaits.nextDueAt` = wall-clock time of the next check, `wake()` runs what is due by the wall clock. The check itself (`ProviderChecks`) is shared with `DefaultProviderManager`. |
| 4 429 | RetryPolicy.kt (unchanged), gateway | Retry-After waited for (up to 20 s, as before); after the last attempt, or for a longer Retry-After at once, the failure stays `LlmException.RateLimited(retryAfterMs)`. Every 429 counts in `rate_limited_waits` (the gateway's waits and the caller's last one) and in `err_rate_limited` when the call fails. |
| 5 Names (D29) | framework/llm/ModelNames.kt | `ModelNames.base(name)`, `sameModel(a, b)`: vendor folder, `:latest`, `@date`, then repeatedly `-latest`, `-beta`, `-preview` and plausible date stamps (YYYY-MM-DD, YYYYMMDD, MM-DD, MMDD or YYMM) stripped, case ignored. The journal's `modelSubstituted` uses it. |
| 6 Key check | framework/llm/VaultKeyCheck.kt | `VaultKeyCheck(vault) : OwnerKeyCheck`: every `provider/…` key (current or left over, ≥ 8 characters) compared with every window of the text in constant time (`MessageDigest.isEqual`, all windows always compared); returns, logs and caches nothing. |
| 7 P8 | framework/registry/DefaultRegistryDirectory.kt | `sharing: CoroutineScope?`: `observe()` is one `shareIn(scope + Dispatchers.Unconfined, WhileSubscribed(5 s, replayExpiration 0), replay 1)`; `snapshot()` always reads the stores afresh (read-your-writes). |
| 8 Cost lines | framework/llm/ProviderPresets.kt | Every preset description has a second line on cost; every cloud service's line says "Check …'s page; terms change." |
| 9 Retention | framework/llm/UsageRetention.kt | `UsageRetention(housekeeping, settings, clock, zone).prune(): Pruned(hourRows, workRows)`: hour rows before `UsageRange.lastDays(usageRetentionDays, now, zone).fromMs` (0 or less keeps all), then orphaned work rows; the store clamps anything inside today. |
| 10 Wiring | app/di/wiring/ModelsWiring.kt | `ModelsAssembly(…, zone = { ZoneId.systemDefault() })` builds the shared registry (on `appScope`), `dailyLimiter` (public), the gateway with `ports.usageLedger` and the limiter (over `ports.usageReports` and the read-only `ports.modelPrices`), the provider manager, `providerRechecks = DefaultProviderRechecks(…).also { it.start() }` on `appScope` with `network`, and `ownerKeyCheck = VaultKeyCheck(ports.secretVault)`. `ModelsWiring.network(context) = AndroidNetworkStatus(context)` (no longer `@Phase6Stub`). |

## 2. Changes needed in files WP4 does not own (for the merger and later packages)

| Where | What | Why | Who |
|---|---|---|---|
| app `di/OperatorDependencyTest.kt` roots, `ProductionGraphDependencyTest.kt` roots | Add `graph.providerRechecks` as an agent-facing root once WP7 hands it to the orchestrator and chat. WP4's `ModelsWiringTest` and `NetworkWiringTest` already walk it (test app and production) with `AgentFacingWalk` and find nothing. | security hand-off, wave 3 notes | WP8 |
| app `di/AppGraph.kt` (call of `ModelsAssembly`) and `testing/TestApp.kt` | Optional: pass the owner's zone to `ModelsAssembly(zone = …)` from one place (e.g. a `Ports.zone`), so the limiter's day and `DataLayer`'s/`InMemoryUsageStore`'s day are the same object. Production already agrees (both `ZoneId.systemDefault()`); `TestApp`'s usage store uses UTC while the limiter uses the machine's zone, which matters only to a test that records calls around midnight. | one zone for "today" | WP8 |
| app `work/UpkeepGraph.kt` | Run `UsageRetention(ports.usageHousekeeping, ports.settings, ports.clock, ports.zone).prune()` as the usage step (instead of calling `UsageHousekeeping` directly). | plan item 9, security hand-off | WP2 |
| app `ui/chat/ChatPresentation.kt` `sameModel` | Replace with `ModelNames.sameModel(answered, assigned)` (D29), so the chat and the activity log agree ("grok-4-latest" answered as "grok-4-0709" is not another model; "gpt-4o" answered as "gpt-4o-mini" is). | D29 | WP11b |
| docs/LIMITATIONS.md, ARCHITECTURE.md, STATUS.md | See section 5. | docs say what is built | WP14 |

No architecture-test list must grow for WP4: `VaultKeyCheck` is an `OwnerKeyCheck` (listed) and holds the vault (listed);
`UsageRetention` holds `UsageHousekeeping` (listed), so any agent-facing holder of either is caught by both walks.

## 3. Device tests to run

None: WP4 wrote no instrumented test. `AndroidNetworkStatus` is covered by Robolectric at SDK 35
(`device/src/test/.../net/AndroidNetworkStatusTest.kt`). On the phone (§10, wave 5) worth a look: turning flight mode on
and off while a cloud provider is OFFLINE triggers one "Provider checked" entry within seconds.

## 4. Notes for wave 2b and wave 3 packages

**WP7 (orchestration, chat).**
- The gateway throws `LlmException.DailyLimitReached` from `chat` and from `chatStream` (as the flow's exception) before
  any request is sent, and also when a retry is refused because a limit was reached while it waited (the call had
  already sent one attempt). Its `message` is the owner's sentence; it can also be `DailyLimiter.USE_UNREADABLE` or
  `PRICES_UNREADABLE` (today's use or the prices could not be read: nothing was sent). Pause on the type, show the message.
- `LlmException.RateLimited` after the gateway's retries carries `retryAfterMs` when the provider sent one (it can be
  longer than 20 s: the gateway did not wait for it); use it for `retryAt`.
- `ProviderRechecks.recheckIfStale(id, 60_000)` returns the status after a check, or null when nothing was checked
  (unknown, disabled, AUTH_ERROR, local-only, or checked less than `olderThanMs` ago): then read the registry as usual.
  It takes at most 10 s. A provider marked OFFLINE by the gateway says "Not answering since 10:42" in `statusMessage`.
- `providerRechecks.timedWaits` is published for WP2's wake lock; the orchestrator's own `timedWaits` is separate.

**WP5 (broker, questions).** `OperatorAssembly(…, ownerKeyCheck)` now receives the real `VaultKeyCheck`. Give it to
`DefaultOwnerRequests`/`OwnerServices.answer` only.

**WP15 (lean requests).** Pass `CallContext.conversationId` for chat calls: the journal then files the call under the
chat's work row and source CHAT (a call with a task or objective id is filed under them and OBJECTIVE instead).

**WP2 (background).** `AppGraph.providerRechecks.timedWaits.nextDueAt` is a wall-clock time; schedule the wake alarm for
it and call `wake()` from the receiver: due checks then run by the wall clock even when the phone slept through the
timer. The re-checks' own timer runs on a monotonic clock; changing the phone's clock moves nothing.

**WP17 (routines).** The routine runner can check today's limits before starting a run with
`DailyUse.of(usageReports.byModel(UsageRange.day(now, zone)), prices)` against `settings.dailyLimits.reachedBy(...)`;
the gateway refuses anyway.

**WP9 (Team view, dashboard).** `registryDirectory.observe()` is now one shared flow in the app; collecting it in several
places costs one set of store queries.

**WP10 (Usage tab, Daily limits, Dashboard Today tile).**
- "Today: 37 of 200 answers" and the Today tile's limit line: `DailyUse.of(usageReports.byModel(UsageRange.day(now, zone)),
  prices.associateBy { it.modelId })` gives exactly what the limiter counts: calls to providers off the phone that sent a
  request (answered, failed or stopped), their reported tokens, and money at the current prices (`priced` false: no model
  used today has a price, show no money). Calls to providers on the phone are in the usage records but not in these counts.
- Speed and reliability: `UsageTotals.averageAnswerMs` (answered calls), `averageTtftMs` (streamed answers only),
  `errors` by class, `rateLimitedWaits`, `noUsage` ("the provider did not report tokens for N answers"), `retries`.
- Cost per work item: `usageReports.workUsage(WorkKind.TASK/OBJECTIVE/CHAT, id)` with `CostEstimate.of(rows, prices)` and
  `CostEstimate.DISCLAIMER`.
- A provider stopped by the outage rule shows `statusMessage` "Not answering since 10:42"; it clears on the next answer
  or a successful check.

**WP11a (New objective form).** The daily-limit line uses `DailyUse` as above with `settings.dailyLimits`.

**WP11b (Models).** Preset descriptions now have two lines (what it is; what it costs). The gallery already shows the
whole description. `ModelNames.sameModel` for the chat footer (section 2).

## 5. For WP14 (docs)

- LIMITATIONS: the cost lines in the provider gallery come from the providers' pages as seen by the owner's note (November
  2026) and could not be checked from the app or the emulator; they tell the owner to check the provider's page.
- LIMITATIONS: daily limits count the tokens a provider reports; a provider that reports none is limited by answers (and
  by money only through its reported tokens). Models on this phone are not limited and do not use up the limits. In a
  time zone with a half or quarter hour (India, Nepal), the calls of the last 30 to 45 minutes before midnight count for
  that day and again for the next one (the usage records cut days at whole UTC hours): the limits may count a call twice,
  never miss one. When the app cannot read today's use or the prices, calls off the phone are refused with a sentence
  that says so.
- LIMITATIONS: a provider that stopped answering is re-checked for an hour; after that only when work waits for it, when
  the network comes back, at start, when the app comes back after ten minutes away, or with "Check now".
- ARCHITECTURE: the gateway's limiter (reserve before every attempt under one lock, settle at the end), usage recording,
  the outage rule, `DefaultProviderRechecks` (agent side, no manager or vault), `VaultKeyCheck` (owner side), the shared
  registry snapshot.

## 6. Deviations from the plan (and why)

1. **Names from the security hand-off:** `DefaultProviderRechecks` (the plan's `ProviderRechecks` class) and
   `UsageRetention` (the plan's `UsageHousekeeping.prune(now, settings)`), as handoff-secfix.md requires.
2. **What the limits count.** One call is one answer, however many attempts it took (a retry is checked again but is not
   a second answer); a call counts once it sent a request (answered, failed or stopped); a refused call does not. Calls to
   providers on this phone are recorded but neither limited nor counted ("counted, not limited" read as: in the usage
   records, not in the limits), so free local use never blocks cloud work.
3. **Retries after a sent attempt that are refused** (by policy or by a limit reached meanwhile) are recorded as FAILED
   with the last attempt's error class, not BLOCKED, so the counts read after a restart match the live ones.
4. **Today** is the owner's local date (the counts start again at local midnight); the counts read from the records use
   `UsageRange.day` (whole UTC hours). In half-hour zones this counts the last stretch before midnight for both days
   (tested in `DailyLimitTest`).
5. **Fails closed** when today's use or the prices cannot be read (`DailyLimitReached` with its own sentence), rather than
   letting calls through uncounted.
6. **Outage rule:** "retryable failures" read as no connection, time-out, server error and "too busy"; a 429 is not an
   outage (the provider answers and asks to wait). An existing OFFLINE status keeps its "since".
7. **Re-check timing:** the schedule runs on a monotonic time source and publishes wall-clock times; `wake()` runs what is
   due by the wall clock. While there is no network, scheduled checks of providers off the phone are skipped (the
   network's return checks them at once). Missed slots are not made up.
8. **Cost lines on every preset**, not only the six named (the others say costs depend on the model and the text sent,
   or that a local server is free and private), each as the description's second line; no core change was needed.
9. **`ModelNames`** strips a 4- or 8-digit stamp only when it reads as a date, so context-size suffixes such as 8192 stay.
   The journal no longer notes a dated version of the requested model as another model (ModelGatewayTest updated).
10. **The shared registry snapshot runs unconfined** in the app scope (it adds no dispatch, and in tests it does not
    become background work the test scheduler skips); `snapshot()` stays unshared so a caller sees its own change.
11. **`ModelsAssembly`** gained `zone` (default `ZoneId.systemDefault()`, so AppGraph's call is unchanged) and a public
    `dailyLimiter`; `ProviderConnections` gained `storeStatusUnless` (a conditional status write under its record lock).

## 7. Checks run

Whole suite in the worktree (`./tools/gw --offline test --continue`): 2,850 tests, 0 failed (app 484, core 164, data 430,
device 339, framework 839, memory 107, providers 227, providers-anthropic 89, testkit 171; 2,775 before, 75 new).
`:app:assembleDebug` builds. New tests: framework `llm/DailyLimitTest` (16), `UsageCaptureTest` (10),
`ProviderRechecksTest` (10), `ProviderOutageTest` (6), `VaultKeyCheckTest` (5), `ModelNamesTest` (3),
`ProviderPresetsCostTest` (3), `UsageRetentionTest` (3), `registry/SharedRegistrySnapshotTest` (4), `ModelGatewayTest`
(substitution test updated to D29); device `net/AndroidNetworkStatusTest` (4, Robolectric SDK 35); app
`di/wiring/ModelsWiringTest` (6), `UsageRetentionRoomTest` (3, Room through `RoomUsageHousekeeping`), `NetworkWiringTest`
(2, including a walk of the production re-checks). The framework walk (`OperatorDependencyTest`, root
`DefaultProviderRechecks` added), the app walks (`OperatorDependencyTest`, `ProductionGraphDependencyTest`) and
`AssemblyMoveTest` stay green with the real limiter, re-checks and key check wired.
