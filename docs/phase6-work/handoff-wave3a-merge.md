# Wave 3, part 1 merge (WP8 as integrator): what was merged, what was applied, what is left

Main checkout `/home/user/rune-companion`, branch `claude/ai-control-center-architecture-wjdfww`, from d025d68 (WP8 part 3a).
Part 2's packages (WP9, WP10b, WP10c, WP16) start from the head named in section 6 and read this page together with
`wave3-ownership.md` (still binding: the file table, routes, slots and FINAL signatures are unchanged, with the additions
in section 3 below).

## 1. Merges

| Merge commit | Branch | Conflicts | Suite after it |
|---|---|---|---|
| a7092fe | p6/wp11a (tasks, approvals, questions) | none (no file touched by two branches) | 3,513 tests, 0 failed |
| 7e3814b | p6/wp11b (chat, models, agents, memory graph, tools) | none | 3,565 tests, 0 failed |
| ad9b958 | p6/wp10a (usage, cost, daily limits, Activity log, Dashboard parts) | none | 3,635 tests, 0 failed |

No merge broke anything, so there is no "fix after merging" commit.

## 2. Hand-off items applied (each with its test)

| Item | From | What changed | Test |
|---|---|---|---|
| Screens build their state off the main thread | handoff-wp11a §1 item 1; handoff-WP10a 2.2 | `AiControlCenterRoot(graph, darkTheme, screenWork)`; `ui/shell/AppEnvironment.kt` gives `screenWork` to both `LocalScreenWork` (WP11a) and `LocalUsageWork` (WP10a); `MainActivity` passes `AppScreenWork` (= `Dispatchers.Default`). `null` (every test) keeps what an outer caller provided, so `TestApp` journeys stay synchronous and `WP11aTesting.launchWithWork` still works | `ui/shell/AppEnvironmentTest` (4), `MainActivityTest` (new: the real activity, background pool, Tasks still loads) |
| Calm reaches every screen | handoff-WP11b §2 ("Calm" row) | `ui/theme/Motion.kt`: `LocalCalmMotion` (Boolean) and `calmMotion(setting, animatorsEnabled)`; the root provides it from `FrameworkSettings.calmMotion` (read only, `graph.settingsView`) or Android's `ValueAnimator.areAnimatorsEnabled()` (read again on every resume) | `AppEnvironmentTest` |
| Dashboard Today tile gets reports, prices, zone, waits and the pool | handoff-WP10a 2.1 | `DashboardRoute` passes `reports = graph.usageReports, prices = graph.modelPrices, zone = graph.zone, waits = graph.orchestrator.timedWaits, computation = LocalUsageWork.current`: the tile names models used today without a price ("No cost shown: Llama 3.3 70B has no price yet."), shows the month line, and the phone items can say "Work tries again at …" | new `journeys/DashboardWiringJourneyTest` |
| "Add its price" opens that model | handoff-WP10a 2.3 | `DashboardNavigation.onOpenModel(ModelId)` (wired to `ModelEditorRoute` in `AppNavHost`); `TodayTile`: one unpriced model → its page, several → the Models list | `DashboardExtrasScreenTest` (changed: asserts the model opened) |
| Getting started's simulator step | handoff-WP10a 2.4 | `DashboardNavigation.onTrySimulator: (() -> Unit)?`; `DashboardRoute` shares the "Try with the simulator?" dialog's state with `DashboardScreen` (new parameter `confirmSimulator`) and sets `onTrySimulator` while some agent has no model; the step's button then says "Try it now" and opens the dialog; once every agent has a model it says "Open agents" as before. Step text: "Use it for the agents that have no model yet, or choose it on an agent's page." | `GettingStartedJourneyTest` (changed: Try it now → dialog → Use the simulator → every agent has a model → Open agents) |
| The attention card's empty line | handoff-WP10a 2.5 | "Nothing needs your attention." → "Your agents, models and apps need nothing from you." (phone items follow right under it) | `KeyJourneysTest` (changed) |
| Cost per agent | handoff-WP10a 2.6 (optional) | core `AgentModelUsage`, `UsageReports.byAgentAndModel(range)` (default empty); Room `UsageDao.byAgentAndModel` (`GROUP BY agent_id, model_id`, no schema change); `InMemoryUsageStore`; the Usage tab's agent rows now say "about US$0.60", "about US$0.60 for the models with a price", "No price entered" or "Free: on this phone" (rule 29: never a cost without a price); header line "Each agent's cost is estimated from the prices of the models it used." | testkit `UsageLedgerContract` (new case, runs on the in-memory store and on Room), `UsageViewModelTest` (+1 test, +1 assertion) |
| "1 model answer" | handoff-WP10a 2.7, handoff-WP11b §2 | core `DailyLimits.sentence`: singular for a limit of 1 (answers and tokens) | core `UsageModelTest`, `DailyLimitsScreenTest` (changed) |
| Graph export says where the file may go | handoff-WP11b §2 row 1 (rule 21, D37) | `MemoryGraphTab.ExportRow` shows `state.exportNote` under the description | `MemoryJourneysTest` (asserts the line before the tap) |
| One wording for "switched off by Android" | handoff-WP11b §2 row 3 (D7) | device `ScreenSwitchedOffSentences` (public; `SwitchedOffWatch` uses it) | new `operator/SwitchedOffWordsTest` (Tools › Screen's `SWITCHED_OFF` equals the device's words) |
| Sample review tasks carry their verdict lines | handoff-wp11a §1 item 2 | `testing/ObjectiveWorld.kt`: `tsk_review` "Verdict: revise."; the Lisbon steps 2 and 3 get review tasks "Verdict: approved." | new `testing/ObjectiveWorldTest` (plan rows from summaries show "Revision asked" and two "Approved") |
| Public status-line words | handoff-wp11a §1 item 3 (optional) | framework `orchestration/TaskStatusLines` (`WAITING`, `WAS_WAITING`, `VERDICT`), used by `ProviderWaits`, `ReviewSteps` and the app's `WaitTexts` / `verdictOf` | new framework `TaskStatusLinesTest`; app `ObjectiveStatesTest` still pins the words |
| Notification channel "Waiting for you" | handoff-wp11a §1 item 5 (check) | `ApprovalNotifications.ensureChannel`: name "Waiting for you", description mentions questions; a channel made as "Approvals" by 0.5.x is renamed in place (Android keeps the owner's sound, importance and on/off for it) | `OperatorNotificationsTest` (+1 test, one assertion changed) |
| Stub gate after part 1 | task | `Phase6StubGateTest.STILL_BUILDING` = WP8, WP9, WP10b, WP10c, WP16 | `Phase6StubGateTest` (+1 test) |

## 3. Not applied, with owner and reason

| Item | From | Owner | Why not now |
|---|---|---|---|
| A deep link to Settings › Working in the background (the phone attention items open the top of Settings) | handoff-WP10a 2.8 | **WP10b + WP8, part 2** | The Background section is still WP10b's stub. Once it exists, WP8 adds a scroll target to `SettingsRoute` (for example `SettingsRoute(scrollTo = BACKGROUND)` or a `BringIntoViewRequester` keyed by slot) and `DashboardNavigation.onOpenSettings` uses it. WP10b: please use the attention items' words ("battery saving", "background use is restricted", "Android rarely lets it run"). |
| One shared wording file for money, seconds and the token explanation | handoff-WP10a 2.9 (optional) | wave 4 (WP12 simplification lens), then WP8 | `ui/activity/UsageWords.kt` and WP11b's `ui/chat/ChatCost.kt` / `ui/models/ModelPriceViewModelParts.kt` overlap; each package's tests pin its own sentences, so merging them changes texts on several screens at once. Worth doing with a review, not in a merge. |
| D35 for the memory graph (`MemoryGraphViewModel` observes the whole memory list to rebuild) | handoff-WP11b §2 row 2 | wave 4 (WP12 performance lens), then WP8 | Needs a new core change signal (e.g. `MemoryRepository.observeLatestChange()` from Room's invalidation tracker), a Room and in-memory implementation and a contract. D35's counts are fixed (WP1b's COUNT queries, WP11b's agent page); the graph's full list is a rebuild trigger, bounded by the graph's own node cap. WP9 checks the Dashboard's memory counts in part 2. |
| The chat transcript built `flowOn(Default)` | handoff-WP11b §6 deviation 1 | wave 4 (WP12) | `LocalScreenWork` now carries the pool in the app, so `ChatViewModel` could take a `computation` parameter; WP8's `FastTypingTest` and WP11a's `ApprovalJourneysTest`/`ApprovalViewModelsTest` read the chat at once and would need `waitUntilShown`. The chat's build is bounded (50 entries a page, never rebuilt per key typed). |
| The agent page's "Recent tasks" read whole task trees (rule 16) | handoff-WP11b §3 | wave 4 (WP12 performance lens) | Not in WP11b's plan; `TaskRepository.observeRecentByAgent` gives summaries but not the objective titles the rows show; needs a small summary join. |
| The D40 "New agent" button under the owner's own Calm setting | handoff-WP11b §2 ("Calm" row, second half) | wave 4 (small; `ui/agents/AgentsScreen.kt`) | `LocalCalmMotion` now exists. With Android's animations off the button's change is already instant (Material follows the animator scale); under the owner's in-app Calm it could stay expanded: `expanded = atTop || LocalCalmMotion.current`. Cosmetic; no screen test exists for that file to extend safely in a merge. |
| A question in a chat's inline request opens the question screen directly | handoff-wp11a §1 item 4 (optional) | nobody (works) | Tools › History already does (WP11b). In a chat the request card hands over through `ApprovalRoute(onOpenQuestion)`, which replaces the Approval screen with the question screen at once (rule 13); `ChatRoute`'s FINAL signature has no `onOpenQuestion`, and adding one saves one screen change only. |
| The Conversation tab reads whole messages | handoff-wp11a "Not done" | wave 4 (WP12) | Bounded at 200 messages per objective by the settings; the tab shows and expands full bodies, so headers would need a second read per expansion. |
| Suggest sharing a Downloads-entry folder through the phone's storage entry | handoff-WP11b §3 / wave2b-minors-left (optional) | WP14 (docs) or wave 4 | The agents' refusal and LIMITATIONS already say it. |
| Work-item costs on the Usage tab; a waiting-routines count in the attention list | handoff-WP10a §3 items 3-4 (optional) | WP10c may add the count in part 2; work-item costs in 0.6.x | No listing of work rows by period exists; the objective header and the chat show their own cost. WP17's banner already shows each waiting run. |
| `OwnerQuestions`' messages through `ObjectiveJournal`; shorter `permissions.request` text | earlier hand-offs (declined in handoff-wp8.md §2) | WP12 to judge | Unchanged; see handoff-wp8.md §2. |

## 4. For part 2 (WP9, WP10b, WP10c, WP16)

- **Calm:** read `LocalCalmMotion.current` (`ui/theme/Motion.kt`) wherever something travels or pulses (WP9's live dots and
  the network card). It is true when the owner's Calm is on or Android's animations are off. WP10b's `MotionSection` only
  writes `calmMotion`; the root already reads it.
- **Background pool:** WP9's `TeamViewModel` (and any other new view model) can build `flowOn(LocalScreenWork.current)`
  (read in the `…Route` composable and passed in, as WP11a's routes do); tests stay synchronous.
- **Status lines:** use `TaskStatusLines` (framework) for "Waiting:", "Was waiting:" and "Verdict: ", or WP11a's
  `WaitTexts` / `verdictOf`.
- **Cost per agent:** `graph.usageReports.byAgentAndModel(range)` plus `graph.modelPrices` give each agent's estimated cost
  (see `UsageViewModel.agentCost`); WP9's node sheet may reuse it.
- **Dashboard:** `DashboardNavigation` gained `onOpenModel(ModelId)` and `onTrySimulator`; `DashboardScreen` gained
  `confirmSimulator: MutableState<Boolean>` (defaulted). WP9 owns `DashboardViewModel`/`NetworkCard` only; the slots and the
  route stay WP8's.
- **Stub gate:** `STILL_BUILDING` = WP8, WP9, WP10b, WP10c, WP16 (28 stubs: WP10b 12, WP10c 9, WP9 3, WP16 3, WP8 1, the
  stand-in `StubScreen`). After part 2 the final gate allows none (`-Paicc.stubGate=final`), and WP8 deletes `StubScreen.kt`.
- **Notification channel:** the requests-and-questions channel is now named "Waiting for you"; WP10b's background section,
  if it lists channels, should use that name.
- **The attention card** now says "Your agents, models and apps need nothing from you." when it lists nothing.

## 5. Device checks

Appended to `device-tests-wave3.md` section 8 (the background pool under StrictMode, Calm from Android's animation
switch, the channel rename over 0.5.2, the simulator step, "Add its price", the agent cost line).

## 6. Checks

- Head after this pass: **3a7a9d4** (the base for part 2). Not pushed. Worktrees `/home/user/p6-wp11a`, `p6-wp11b`,
  `p6-wp10a` removed; branches `p6/wp11a`, `p6/wp11b`, `p6/wp10a` kept.
- Whole suite, every test task really executed (`test --continue --rerun`, then `:app:testDebugUnitTest --rerun
  :data:testDebugUnitTest --rerun :device:testDebugUnitTest --rerun`, since `--rerun` on the `test` lifecycle task leaves
  the Android modules' unit-test tasks up to date): **3,649 tests, 0 failed** — app 911, core 182, data 494, device 367,
  framework 1,096, memory 107, providers 229, providers-anthropic 89, testkit 174 (3,635 after the three merges; +14 from
  the hand-offs). `:app:assembleDebug`: BUILD SUCCESSFUL.
- Screenshots of the merged state (WP11a's, WP11b's, WP10a's and the Tasks pictures, re-recorded with
  `:app:recordRoborazziDebug`, review material only): `scratchpad/phase6/wave3a-merged-shots/` (122 files). The sample
  objective's plan now shows "Revision asked" again.
