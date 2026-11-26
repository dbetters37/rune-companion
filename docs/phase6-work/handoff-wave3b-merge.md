# Wave 3, part 2 merge (WP8 as integrator): what was merged, what was applied, what is left

Main checkout `/home/user/rune-companion`, branch `claude/ai-control-center-architecture-wjdfww`, from e79e164 (the part-1
design fixes). Wave 4's reviewers read this page with `wave3-ownership.md`, `handoff-wave3a-merge.md`,
`wave3a-minors-left.md` and the four part-2 hand-offs (`handoff-WP9.md`, `handoff-WP10b.md`, `handoff-WP10c.md`,
`handoff-WP16.md`). Wave 3 is now complete: every screen of the plan is built, and the final stub gate passes.

## 1. Merges (in the order the task gave)

| Merge commit | Branch (head) | Conflicts | Suite after it (`test --continue`) |
|---|---|---|---|
| 5559f20 | p6/wp10b (3f0d399): Settings background, questions, Calm, Backup; backup screens; Recovery | none | 3,741 tests, 0 failed (the tree equals WP10b's own, so the app task came from the build cache of WP10b's run) |
| 458a7c1 | p6/wp10c (c1eeeb3): Routines, Tap to start, Folder pick-up | none | 3,831 tests, 0 failed |
| 5ff55eb | p6/wp9 (c7ce67b): Team view, Dashboard network card | none | 3,891 tests, 1 failed: `ui/shell/Wave3RoutesTest` (the stand-in's title "Team", as WP9 said) |
| 81dc8f8 | fix after merging WP9 | — | `Wave3RoutesTest` expects "Team view" (handoff-WP9.md item 2): 2 of 2 pass |
| 935b4ff | p6/wp16 (74cd620): receiving shares | none | 3,956 tests, 0 failed |
| e859d7b | fix after merging WP16 | — | a race in WP16's `ShareReceiverActivityTest` that the merged branch showed (2 failures in 3 runs): it tapped Save right after picking a place while the screen works out Save on the background pool. The test now waits until Save is on after each pick; 3 reruns pass |

No two branches touched the same file (checked before merging), and every branch kept to its own files
(`testing/<Package>Testing.kt` helpers included), so no merge had a conflict.

## 2. Hand-off items applied (each with its test)

| Item | From | What changed | Test |
|---|---|---|---|
| The Dashboard is built off the main thread and counts memories with the store's count (P2, P4/D35) | handoff-WP9.md item 1 | `DashboardRoute` passes `computation = LocalUsageWork.current`, `memoryCount = graph.ports.memories::observeCount`, and `zone = graph.zone()` (the owner's one zone, for the activity's times) | `journeys/DashboardWiringJourneyTest` (new case): with a pool that runs only when the test says so, the Dashboard builds nothing before it runs, then shows the store's count; fails without the wiring |
| "Team view" title in WP8's route test | handoff-WP9.md item 2 | `Wave3RoutesTest` | itself |
| A provider's name keeps its spelling ("xAI is not answering", never "XAI") | handoff-WP9.md item 3 (optional) | `WaitTexts.waitingFor` upper-cases the first letter only when the first word has no capital later in it | `TeamGraphBuilderTest`, `ObjectiveStatesTest` (+2 assertions) |
| One tap from a phone item to the fix in Settings | handoff-WP10b.md 3.1; handoff-wave3a-merge.md §3 row 1; handoff-WP10a.md 2.8 | `SettingsRoute` is a data class with `anchor: SettingsAnchor?` (BACKGROUND, ROUTINES); `SettingsRoute(navigation, scrollTo)` brings that section into view once, the first time it has a size (saved, so coming back does not jump again). The Dashboard's phone items and the New objective form's background warning open BACKGROUND; Routines' and Folder pick-up's "Open Settings" (master switch off) open ROUTINES; Settings from the navigation still starts at the top | new `journeys/SettingsAnchorJourneyTest` (2): from the navigation the background part is off screen; from the Dashboard item it is in view with "Turn on notifications"; Routines › Open Settings shows the routines' switches |
| Reopening the app after "Close and try later" looks again | handoff-WP10b.md 3.3 (optional) | `MainActivity.onCreate`: a new activity (no saved state) while the gate is `NeedsRecovery` calls `gate.recheck()`; a rotation does not. The Recovery screen's line: "Closing changes nothing: your data stays as it is. When you open the app again, it looks again by itself." | new `health/ReopenRechecksTest` (fails without the recheck; also: no graph is built) |
| The Dashboard's Tap-to-start banner offers the objective after a start | handoff-WP10c.md §2 row 1 (optional) | `WaitingRoutinesBanner(waiting, onOpenObjective = navigation.onOpenObjective)`; following "Open the objective" clears the message (banner, WP10c's file) | `DashboardWiringJourneyTest` (new case): Start › Start now › Open the objective › "Started by routine “…”"; back on the Dashboard the message is gone |
| The test robot scrolls the All sections sheet | handoff-WP10c.md §2 row 3 | `AppRobot.openSectionViaMore` scrolls to the section first (at 360 dp and font 2.0 Settings is below the sheet's fold) | new `ui/shell/MoreSheetLargeFontTest` (fails without the scroll) |
| "Tools › Folders" in the pick-up sentences; "Settings › Daily limits" in the limit sentence | handoff-WP10c.md §2 row 4; wave3a-minors-left D3A-16(b) | core `PickUpSafety` (every sentence) and `DailyLimits.sentence` use "›" | core `UsageModelTest`, framework `DailyLimitTest`, `DailyLimitPauseTest`, `ChatRecoveryTest`, testkit `RoutineContracts`, app `DailyLimitsViewModelTest`, `WaitingRoutinesBannerTest` (updated) |
| Curly quotes in the code warning | wave3a-minors-left D3A-12(e) | framework `DefaultOwnerRequests.CODE_ASK_AGAIN` says tap “Send it anyway”; `QuestionTexts.CODE_WARNING` is that constant | framework `OwnerQuestionsTest` (+1 assertion), app `QuestionViewModelTest` |
| **One currency, US$** (the lead's decision; the owner may change it later) | wave3a-minors-left D3A-8; task item 4 | core: `ModelPrice.counts` ("US$", "USD" or "$", any case, spaces ignored), `ModelPrice.OTHER_CURRENCY` ("This price is in another currency. Enter it in US$ to count it."), `Iterable<ModelPrice>.counted()` and `ModelPrices.counted()` (only prices that count, each labelled US$); `CostEstimate.of` is null for a price in another currency (so nothing can add it into a US$ total); `DailyLimits.sentence` has no currency parameter (US$). Framework: the limiter and `UsageDailyLimitCheck` read only counted prices and say US$; `DailyUse.priced` likewise. App: `AppGraph.modelPrices` is the counted view (TodayUsage, the Dashboard, chat, objective and task cost lines); `AppGraph.enteredModelPrices` (as stored) is read only by the model editor and Usage and cost, to say a price in another currency does not count; the limiter (ModelsWiring) and the routine check (RoutinesWiring) get the counted view too; `TodayUse` lost its own currency; Usage, chat, objective and task money is one US$ figure. Model editor: no currency field, "Prices are in US dollars (US$), as your providers' price pages show them.", a stored price in another currency shows the sentence with "Stored now: €3.00 in, …", empty fields, and Save stores US$. Usage and cost: that model's row says the sentence and offers "Add its price". Daily limits: the money field says US dollars | core `UsageModelTest` (new), framework `DailyLimitTest` (rewritten: euros never added; USD counts with a US$ sentence), `RoutineRunnerTest` (+euros ignored), app `ModelEditorViewModelTest` (new case; the old "Say which currency" case removed), `PriceTypingTest` (no currency field, stored US$), `UsageViewModelTest` (+row sentence), `TodayUsageTest` (+euros), `ChatPresentationTest`, `ObjectiveStatesTest`, `NewObjectiveWarningsTest`, `AssemblyMoveTest` |
| The final stub gate | task item 5; handoff-WP10b.md 3.5; handoff-WP16.md §3 item 2 | `Phase6StubGateTest.STILL_BUILDING` is empty; the stand-in `ui/shell/StubScreen.kt` (the last stub) is deleted; the gate also checks that nothing shows a stand-in screen | `Phase6StubGateTest` (with and without `-Paicc.stubGate=final`): "Phase 6 stubs left: 0" |

## 3. Not applied, with owner and reason

| Item | From | Owner | Why not now |
|---|---|---|---|
| TestApp gets test background screens and a backup | handoff-WP10b.md 3.2 (optional) | wave 4 (WP12 simplification lens), then WP8 | WP10b's journeys build their own graph over TestApp's ports (`testing/WP10bTesting.kt` `wp10bGraph`) and pass; adding a backup to the `TestApp` that ~1,250 tests share changes every journey's graph for no owner-visible gain. Worth doing with a review. |
| Tier B switches (questions, notes, routines, pick-up, backup, share) | handoff-WP10b.md 3.4, handoff-WP16.md §3 item 4, handoff-WP10c.md (routines and pick-up stay Tier B) | WP8 after wave 4 round 2 | Decided by the review's outcome (plan §2): questions off → `SettingsSlots.questions = null` and `agentQuestionsEnabled` forced off; backup cut → `BackupWiring.production` returns null; share cut → remove the `ShareReceiverActivity` manifest entry (and `MergedManifestTest`'s exported list); routines / pick-up cut → master switches forced off and entries hidden. |
| A waiting-routines count in the Dashboard's attention list | handoff-WP10c.md §2 row 2, handoff-wave3a-merge.md §3 (optional) | wave 4 to judge | The Dashboard's top already shows WP17's banner with each waiting run (and now its "Open the objective"); a count would repeat it. |
| `android:label="Save to memory"` on the share activity | handoff-WP16.md §3 item 3 (optional) | wave 4 to judge | The share sheet then shows that label instead of the app's name in some launchers; the owner looks for "AI Control Center". |
| `SecureWindow` moved to `ui/components` | handoff-WP10b.md §5 deviation 7 (optional) | wave 4 (WP12) | Works where it is; moving it only renames an import in four files. |
| Cost per agent in the Team view's member sheet | handoff-WP9.md §4 (optional) | wave 4 | Needs `UsageReports` and `ModelPrices` in `TeamViewModel`; Activity › Usage and cost shows it per agent. |
| One shared wording file (money, tokens, "phone"/"device") | handoff-wave3a-merge.md §3, wave3a-minors-left D3A-20 | wave 4 (WP12), then WP8 | Unchanged from part 1. The money words are simpler now (one currency): `UsageWords.costWords`/`agentMoney` and `ChatCost.moneyOf` each add one US$ figure. |
| D35 for the memory graph; the chat transcript off the main thread; the agent page's Recent tasks; the Conversation tab's whole messages; D40 "New agent" under the in-app Calm | handoff-wave3a-merge.md §3 | wave 4 (WP12) | Unchanged from part 1 (reasons there). |
| `SafSharedFolders`' "The owner can add it again in Tools > Folders." | (noticed while doing D3A-16) | wave 4 to judge | An agent-facing tool result, not an owner sentence; left with ">". |
| The rail's two-line "Waiting for you"; the `Samples.provider(statusMessage = "ok")` fixture; D3A-19's "still animates without Calm" | wave3a-minors-left D3A-13, D3A-14, D3A-19 | as written there | Left as decided there (device check for D3A-19). |
| `QuestionViewModel` shows `failure.message` when saving an answer throws | wave3a-minors-left, notes for wave 4 | wave 4 | Pre-existing; a storage exception's message could be technical. |

## 4. For wave 4's reviewers (what changed in shared behaviour)

- **Settings deep links.** `SettingsRoute` is now `data class SettingsRoute(val anchor: SettingsAnchor? = null)` (was a
  data object); `Section.SETTINGS.route` is `SettingsRoute()`. The anchor is a navigation argument only: nothing else
  in an intent or a route selects a screen. The scroll runs once per back-stack entry (`rememberSaveable`).
- **Gate recheck on reopening.** Only in `MainActivity.onCreate` without a saved state, only while the gate is
  `NeedsRecovery`; nothing builds the graph while the data does not open (pinned).
- **One currency.** Attack ideas: a backup carrying a price labelled "usd" or " US $ " (counts, normalised); one labelled
  "CA$" or "€" (never added, the editor says so); a stored price labelled "$" (counts as US$: the owner's providers price
  in US$ — if the owner ever uses another dollar currency, this is the one label to revisit, `ModelPrice.isUsDollars`).
  `CostEstimate.of` is the single guard: every money figure goes through it.
- **Dashboard on the pool.** `DashboardViewModel` builds with `flowOn(LocalUsageWork).conflate()` in the app (tests keep
  their dispatcher); StrictMode on the razr is in `device-tests-wave3.md` §10.

## 5. Device checks

Appended to `device-tests-wave3.md` section 10 (the Settings deep links, reopening after Close and try later, the
Dashboard under StrictMode, the banner's objective link, a price in another currency, "xAI").

## 6. Checks

- Head after this pass: **88060fa** ("Wave 3: the final stub gate, no stub left and the stand-in screen deleted"). Not
  pushed. Commits of this pass, after the four merges and two merge fixes: 365a3d8, 2eb2652, e74d127, 60e7aa0, 02880ac,
  70baedf, 5767a30, 40e2031, 88060fa.
- Final suite, every test task really executed (`test --rerun :app:testDebugUnitTest --rerun :data:testDebugUnitTest
  --rerun :device:testDebugUnitTest --rerun --continue -Paicc.stubGate=final`; all nine test tasks ran, none up to date
  or from the cache): **3,964 tests, 0 failed** — app 1,225, core 183, data 494, device 367, framework 1,096, memory 107,
  providers 229, providers-anthropic 89, testkit 174 (3,956 after the four merges; +8 from the hand-offs: core +1,
  app +7 new cases, others changed in place). `Phase6StubGateTest`: "Phase 6 stubs left: 0".
- `./tools/gw --offline :app:assembleDebug :app:assembleRelease`: BUILD SUCCESSFUL (the release build is signed with the
  committed development key, as without the owner's private key; no private key was needed or used).
- Worktrees `/home/user/p6-wp10b`, `p6-wp10c`, `p6-wp9`, `p6-wp16` removed after the clean run; branches `p6/wp10b`,
  `p6/wp10c`, `p6/wp9`, `p6/wp16` kept. The builders' pictures are in `scratchpad/phase6/wp9-shots/`, `wp10b-shots/`,
  `wp10c-shots/`, `wp16-shots/` (review material; the merged state was not re-recorded in this pass).
