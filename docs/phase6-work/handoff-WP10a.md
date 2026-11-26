# Hand-off from WP10a (wave 3, part 1): usage, cost, daily limits, the Activity log and the Dashboard parts

Branch `p6/wp10a` (worktree `/home/user/p6-wp10a`), from d025d68. WP10a changed only files wave3-ownership.md gives it:
`ui/activity/**`, `ui/dashboard/TodayTile.kt`, `GettingStartedCard.kt`, `PhoneAttention.kt`, `SafeStartBanner.kt`,
`DashboardExtrasViewModel.kt`, `DashboardSections.kt` (`AttentionRow` made reusable), `ui/settings/DailyLimitsSection.kt`,
`DailyLimitsScreen.kt`, `DailyLimitsViewModel.kt`, `UsageHistorySection.kt`, `UsageHistoryViewModel.kt`; new files of its own
(`ui/activity/UsageWords.kt`, `ui/activity/ActivityWork.kt`); tests under `ui/activity/**`, `ui/dashboard/` (its files),
`ui/settings/` (its files), new `journeys/UsageJourneyTest`, `DailyLimitJourneyTest`, `GettingStartedJourneyTest`, new
`screenshots/UsageScreenshotTest`, and the helper `testing/WP10aTesting.kt`. Every `@Phase6Stub("WP10a")` is gone
(`Phase6StubGateTest` lists none for WP10a). `Attention.kt` is unchanged.

## 1. What WP10a built (plan WP10, first half)

| Item | What | Where |
|---|---|---|
| Usage and cost tab | Today / 7 days / 30 days / This month (`UsageRange.day/lastDays/month` in `graph.zone`); answers, tokens and estimated cost (per currency) as tiles; "Today's limits count N answers of 200 and T tokens of 500,000, and US$x of US$1.00" (exactly `DailyUse.of`, the limiter's count); models on this phone said apart ("cost nothing, and the daily limits do not count them"); calls the provider asked to slow down for said as such and never counted as answers (`DailyUse.counted`; stopped 429-only calls, FAILED + RATE_LIMITED, included); models without a price named, with "Add a price for <model>" (`ActivityNavigation.onOpenModel`); by model: where it runs, answers · tokens · "about US$…" / "No price entered" / "Free: on this phone", reliability in words (answered, failed by reason, stopped, not sent, slow-downs, retries, cut-off answers, another model answered, no token counts), average time to first words and to the whole answer for models with 5+ answers; by agent: answers and tokens; "Waiting for you": per kind (actions to approve, permission requests, questions) how many you answered, average and longest wait, how many ran out of time, how many wait now; "Estimated from the prices you entered; your provider's bill is what counts."; plain words for tokens and answers. Every model and agent row opens the Log filtered (model: searched for "<model> via <provider>", as each call's entry says; agent: `ActivityFilter.agentId`); the waiting card opens the Log searched for "Owner". | ui/activity/UsageViewModel.kt, UsageScreen.kt, UsageWords.kt |
| Activity log paging (D41) and search (Tier B) | Newest page of 100 live (`observePage(filter, null, 100)`); "Show older events" reads the next page after the last event shown (`ActivityCursor.after`), one page per tap; once older pages are shown the list holds still and "N new events since you looked further back · Show the newest" counts what arrived; the end says "That is every one of the N events."; a filter or a search starts again from the newest page. Search field `AppTextField(state = viewModel.search)` → `ActivityFilter.text`; Clear filters empties it; "Only <agent> ×" chip for an agent filter. `ActivityRoute(initialTab, initialAgent, initialText, navigation)` (FINAL signature kept) opens the log filtered. | ui/activity/ActivityViewModel.kt, ActivityScreen.kt |
| Settings › Daily limits | Section: today's use against each limit in one phrase each ("Answers: 37 of 200 today", "Reached." in words and with an icon, never colour alone), the limiter's sentence when reached, what happens ("A running objective pauses instead of failing; raise a limit, then tap Resume on it"), "Limits stop work, not cost already spent" (wave2a-minors-left SEC-W2A-4), "Change daily limits". Screen: Today card; fields "Model answers a day" (digits), "Tokens a day" (digits, with the plain explanation of tokens), "Limit money too" switch and "Money a day (estimated)" (digits and one decimal point; a comma counts as one); Save stores exactly what the fields hold or names the problem next to the field (`DailyLimits.CALLS_RANGE`, `TOKENS_RANGE`, `SPEND_RANGE_MICROS`); "Use the usual limits (200 answers, 500,000 tokens, US$1.00)"; after raising a reached limit: "Saved. Paused objectives do not go on by themselves: open each one and tap Resume."; "When a limit is reached" card. | ui/settings/DailyLimitsSection.kt, DailyLimitsScreen.kt, DailyLimitsViewModel.kt |
| Settings › Usage history | "Keep usage numbers for" 90 days / 1 year / 2 years / Always (`usageRetentionDays`, `SettingsBounds.USAGE_RETENTION_DAYS`); "Clear usage history" behind a confirm dialog → `OwnerUsage.clearHistory()`; "Today's numbers always stay: today's limits count them." (S29). | ui/settings/UsageHistorySection.kt, UsageHistoryViewModel.kt |
| Dashboard Today tile | Answers, tokens and estimated cost against today's limits ("22 Answers, of 200"; "reached" in words), the limiter's sentence when reached, "Usage and cost" and "Daily limits" links, the month line ("This month: N answers · T tokens · US$x"); the cost only when every model used today off the phone has a price, otherwise "No cost shown: <model> has no price yet." with "Add its price". The strict rule and the month line need the reports (item 2.1 below); without them the tile shows today's numbers and says "The cost counts only the models you entered a price for." | ui/dashboard/TodayTile.kt, DashboardExtrasViewModel.kt |
| Getting started (Tier B, D47) | Four steps: what a model, a provider and an agent are ("Learn more" → Concepts); add your key, with the cheaper ways ("Custom OpenAI-compatible server" with Groq, OpenRouter, Gemini's OpenAI-compatible address and a model on the owner's computer; "check the provider's page, terms change"), done once a real provider exists; the daily limits that are on from the start; the simulator. "Done, hide this" sets `onboardingCompleted` (its first reader). | ui/dashboard/GettingStartedCard.kt |
| Phone attention items | Under the registry's "Needs attention", headed "On this phone · N": notifications off; battery saving / background restricted / rare standby bucket in one item (→ Settings); storage LOW and CRITICAL (no link: nothing in the app frees room); objectives paused by today's limit (`statusDetailShort == DailyLimits.PAUSED_TEXT`, newest 50 objectives, at most 3 named, → the objective); "Work tries again at 10:42" from `TimedWaits.nextDueAt` (needs item 2.1). | ui/dashboard/PhoneAttention.kt, DashboardExtrasViewModel.kt |
| Safe-start banner (Tier B) | "Safe start is on", `reason.ownerText`, "Interrupted work waits for you: nothing starts again by itself until you choose…", "Back to normal" → `SafeStartControl.leave()`. WP17's `WaitingRoutinesBanner` sits right under it (WP8's placement; seen in the picture `p6_wp10a_dashboard_safe_start_and_waiting_routine.png`). | ui/dashboard/SafeStartBanner.kt |

Hand-off items addressed to WP10 / WP10a and done: wave3-ownership §6 WP10a (all but the two marked open in section 3);
handoff-wp4 §4 WP10 (`DailyUse.of`, speed and reliability from `UsageTotals`, provider "Not answering since" already in the
attention list); handoff-w2afix (Today tile and Usage tab through `DailyUse.of`; stopped 429-only calls among slow-downs);
wave2a-minors-left SEC-W2A-4 (the one line); handoff-wp7 §4 WP10 (objectives paused by the limit; "trying again at" once
wired); handoff-wp2 §4 WP10 (safe-start banner, storage LOW/CRITICAL on the Dashboard); handoff-wp0 §3 (every new field
`AppTextField(state = …)` with a view-model-owned `TextFieldState`, paused-clock typing tests); handoff-wp8 (screens read
"today" through `graph.todayUsage` / `graph.zone`). Not WP10a's (part 2): Recovery, Backup, Working in the background
settings, question time-out, routines and folder pick-up screens (WP10b, WP10c).

## 2. Changes needed in files WP10a does not own (for WP8)

| # | File | Change | Why |
|---|---|---|---|
| 2.1 | ui/dashboard/DashboardScreen.kt (`DashboardRoute`) | Pass the new defaulted parameters of `DashboardExtrasViewModel`: `reports = graph.usageReports, prices = graph.modelPrices, zone = graph.zone, waits = graph.orchestrator.timedWaits, computation = LocalUsageWork.current`. | The Today tile's month line and the plan's rule "cost only when every used model has a price, otherwise say which models have none" need the rows by model; "Work tries again at …" needs the waits; the state is then built off the main thread. Without them the tile degrades (today's numbers, cost with "counts only the models you entered a price for"). FINAL constructor unchanged: the five are trailing parameters with defaults. |
| 2.2 | MainActivity.kt | `CompositionLocalProvider(LocalUsageWork provides Dispatchers.Default)` around the app (with WP11a's `LocalScreenWork`), or merge the two locals into one in ui/components and point both packages at it. | The Activity log and Usage view models build their state `flowOn` this context (rule 16, P7); the default is the caller's dispatcher so Compose tests stay synchronous. |
| 2.3 | ui/dashboard/DashboardScreen.kt (`DashboardNavigation`) + ui/shell/AppNavHost.kt | Add `onOpenModel: (ModelId) -> Unit` (wired like `ActivityNavigation.onOpenModel`), then in `TodayTile.kt` (WP10a's) use it for "Add its price" when one model is unpriced. | Today the button opens the Models list; the plan says "link to them". |
| 2.4 | ui/dashboard/DashboardScreen.kt | Give `GettingStartedCard` a way to the existing "Try with the simulator?" dialog (`confirmSimulator = true`), e.g. a lambda in `DashboardSlots` or a parameter, and WP10a's card calls it in the simulator step. | Today the step opens Agents ("Choose it as an agent's model on the agent's page"). |
| 2.5 | ui/dashboard/DashboardSections.kt (WP10a's) + journeys/KeyJourneysTest.kt:49 (WP8's) | Together: change `AttentionSection`'s empty line "Nothing needs your attention." to "Your agents, models and apps need nothing from you." and the test's expectation. | With phone items listed right under it ("On this phone · 2"), "Nothing needs your attention" reads as a contradiction. Not changed in WP10a so that the suite stays green on this branch. |
| 2.6 | core usage/Usage.kt, data RoomUsage (DAO), testkit InMemoryUsageStore | Optional: `UsageReports.byAgentAndModel(range): List<AgentModelUsage>` (`GROUP BY agent_id, model_id`; default empty), then the Usage tab's agent rows can show an estimated cost. | `byAgent` has tokens but not which model they went to, so a cost per agent cannot be estimated; the tab says "The cost is shown for each model above." |
| 2.7 | core `DailyLimits.sentence` | "Today's limit of 1 model answers" (plural with 1); also in WP11b's hand-off. | Plain words (seen in WP10a's tests with a limit of 1). |
| 2.8 | Settings deep link (WP8 / WP10b) | Phone items say "Fix it with one tap in Settings › Working in the background" and open the Settings section at its top; a route or scroll anchor to the background section would make it one tap. | Goal 5. |
| 2.9 | ui/components (optional) | `ui/activity/UsageWords.kt` (money, seconds, failure words, "Tokens are the pieces of words…") repeats WP11b's helpers (`ui/chat/ChatCost.kt`, `ui/models/ModelPriceViewModelParts.kt`) word for word where they overlap; one shared file after the part-1 merge. | One wording. |

## 3. Open, and deviations from the plan (and why)

1. **Cost by agent is not shown** (plan: "how much … estimated cost by agent and by model"): the usage reports have no agent ×
   model rows (item 2.6). By agent shows answers and tokens.
2. **The Today tile's month line and the strict "every used model priced" rule wait for item 2.1** (the FINAL constructor
   had no reports or prices). The degraded tile never shows a cost that pretends to be the whole day: it says what it covers.
3. **"Trying again at" waits for item 2.1** (`waits`); the routines-waiting count was not added to the attention list (WP17's
   banner at the top already shows each waiting run; optional in the plan).
4. **Work-item costs** (`usageReports.workUsage` + `CostEstimate`) are not on the Usage tab: there is no listing of work rows
   by range, and the objective header and the chat show their own cost (WP11a, WP11b).
5. **Getting started's simulator step opens Agents** (item 2.4); "Add its price" opens the Models list (item 2.3).
6. **Search has no delay**: each change of the search text re-runs the page query (`flatMapLatest` cancels the previous one),
   rather than a debounce whose timer would make the Compose tests wait. One `LIKE` page of 100 per key on Room.
7. **"Show older" holds the list still** instead of keeping every page live (no event can slip between pages while new
   ones arrive); a line offers "Show the newest".
8. **`ActivityViewModel`'s constructor changed** (only WP10a's files use it): `zone: () -> ZoneId`, `pageSize` (was `limit`,
   `PAGE_SIZE` = 100, was `DEFAULT_LIMIT` = 300), `initialAgent`, `initialText`, `computation`; `ActivityUiState.truncated`
   became `canShowOlder`, `loadingOlder`, `newer`.
9. **The money limit is shown in the currency of the owner's prices when they all share one**, else US$ (the limiter adds
   the cost of every priced model whatever its currency).
10. **Defaulted trailing parameters** were added to `UsageViewModel` and `DashboardExtrasViewModel` (`computation`; and the
    four of item 2.1): the FINAL calls compile unchanged.
11. Calm and `areAnimatorsEnabled()`: nothing on WP10a's screens travels or pulses (only Material's progress spinners while
    loading); FLAG_SECURE is not needed on them (no secret is shown or typed).

## 4. Notes for part 2 (WP9, WP10b, WP10c, WP16) and for wave 4's reviewers

- **WP9:** `DashboardSections.kt` and `Attention.kt` stay WP10a's; `AttentionRow(tone, title, detail, onClick, clickLabel)`
  is now internal and shared with the phone items. The Dashboard order on a phone: safe start, waiting routines, Getting
  started, quick start, summary, Today, Needs attention, On this phone, Working now, network.
- **WP10b:** the phone items lead to the Settings section (item 2.8); the background section should use the same words
  ("battery saving", "background use is restricted", "Android rarely lets it run"). The Settings section order puts Daily
  limits and Usage history right after Privacy (WP8's slots).
- **WP10c:** the safe-start banner says "Interrupted work waits for you"; a line on the Routines screen that routines wait for
  a tap in safe start fits it (handoff-wp8.md).
- **WP16:** nothing.
- **Wave 4 reviewers:** rule 7 (the Usage tab shows only numbers and the model/provider labels stored with them; the log
  search text is the owner's and is not stored); rule 29 (every money figure is "estimated" with the disclaimer, and none
  is shown without a price); rule 6/Q2 (the Today tile and Daily limits screens read `graph.todayUsage`, i.e. `DailyUse.of`,
  the limiter's count; DailyLimitJourneyTest pauses at exactly 2 answers); rule 3 (only `SettingsRoute` and
  `DailyLimitsRoute` hold `SettingsRepository`; `UsageHistoryViewModel` holds `OwnerUsage`; `UsageViewModel` gets
  `OwnerRequests.observeRecent` as a function, read only); rule 27 (`DailyLimitsScreenTest`, `ActivityScreenTest` type with
  the frame clock paused); S29 (`UsageHistoryViewModelTest`: clearing keeps today and today's limit count).

## 5. Checks run

- Head `1768c6d` on `p6/wp10a` (not pushed, not merged). Whole suite in the worktree (`./tools/gw --offline test --continue`,
  then `./tools/test-summary`): **3,515 tests, 0 failed** (app 780, core 182, data 493, device 367, framework 1,095, memory
  107, providers 229, providers-anthropic 89, testkit 173); 3,445 at d025d68, +70, all in app: `ActivityViewModelTest` 9
  (was 5), `UsageViewModelTest` 8, `DailyLimitsViewModelTest` 6, `UsageHistoryViewModelTest` 2,
  `DashboardExtrasViewModelTest` 8, `ActivityScreenTest` 3, `DailyLimitsScreenTest` 3, `DashboardExtrasScreenTest` 6,
  `UsageJourneyTest` 2, `DailyLimitJourneyTest` 1, `GettingStartedJourneyTest` 1, `screenshots/UsageScreenshotTest` 26.
  `:app:assembleDebug` BUILD SUCCESSFUL.
- Screenshots (review material, not
committed, as before): `app/build/outputs/roborazzi/p6_wp10a_*.png` (37 files), copied to `scratchpad/phase6/wp10a-shots/`.
Device steps for wave 5: `scratchpad/phase6/device-tests-wave3.md`, section "WP10a".
