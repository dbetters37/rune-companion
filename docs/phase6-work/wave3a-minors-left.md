# Wave 3, part 1: design findings left open (or only partly fixed), with why

Written by the wave-3a design fixer (branch `p6/w3afix`, merged into `claude/ai-control-center-architecture-wjdfww`).
Everything else from the D3A list is fixed; the per-finding report is in this run's result. Each item says what, where
and why, and who should pick it up.

## D3A-16 (b), second half: the core reached-limit sentence still says "Settings > Daily limits"

- **What:** `DailyLimits.sentence` (core `usage/Usage.kt:578`) ends "…or you can raise it in Settings > Daily limits."
  The rest of the app writes "Settings › Daily limits".
- **Done here:** the Daily limits screen and the Settings section no longer show that sentence: they show
  `DailyLimitsViewModel.hereSentence` ("Today's limit of 200 model answers is reached. Raise a limit below, then tap
  Resume on each paused objective. The counts start again at midnight."). The chat and the Dashboard still show the core
  sentence as it is.
- **Why not all:** the sentence lives in `:core` (WP8's), is the message of `LlmException.DailyLimitReached`, and is pinned
  word for word by core `UsageModelTest`, framework `DailyLimitTest`, `DailyLimitPauseTest`, `ChatRecoveryTest`, app
  `DailyLimitsViewModelTest` and `WaitingRoutinesBannerTest`; the chat's `isLimitNotice` matches its ending
  (`ChatPresentation.LIMIT_ENDING`, derived from the sentence, so it follows a change by itself). Outside this fixer's files.
- **For WP8 (wave 4):** change ">" to "›" in `DailyLimits.sentence` and update those tests (mechanical); no stored data
  depends on it (0.5.2 has no daily limits).

## D3A-8, framework half: the routine checker names the money limit's currency its own way

- **What:** `framework/routines/RoutineChecks.kt:52` builds the reached sentence with `priced.firstOrNull()?.currencyLabel`;
  the limiter (`DailyLimiter.currencyOf`) and now every screen (`usage/TodayUsage.kt` `limitCurrencyOf`, carried as
  `TodayUse.currency`) use "the one currency every price shares, else US$".
- **Why not here:** framework file (WP8). One line: use the same rule (or call a shared helper in core).
- **Also, by design, not changed:** with prices in two currencies the limiter adds all priced costs into one number and
  labels it US$ (`DailyLimiter`), so the Today tile, Usage and Daily limits show that one number; Usage's per-model and
  per-agent figures, the chat line and now the objective and task lines (`costLineOf`, per currency, D3A-8) keep
  currencies apart. A money limit across currencies needs an owner decision (convert? one currency only?) — wave 4.

## D3A-12 (e): the framework's code warning still has straight quotes

- **What:** `DefaultOwnerRequests.CODE_ASK_AGAIN` (framework `operator/OwnerServices.kt:279`) says tap \"Send it anyway\".
- **Done here:** the question screen shows `QuestionTexts.CODE_WARNING`, the same sentence with curly quotes (derived from
  the framework constant, so the words cannot drift). Nothing else shows the constant.
- **For WP8 (wave 4):** curly quotes in the constant itself; then `CODE_WARNING` can become the constant.

## D3A-13, the rail: "Waiting for you" still takes two lines on the wide rail

- **What:** the rail item already allows two lines (`NavigationChrome.kt`, `maxLines = 2`); at font 1.0 it wraps as
  "Waiting for" / "you" and fills the 80 dp rail. Left as is: two lines are readable, "Waiting" alone loses what waits,
  and the rail is Material's fixed width. The bottom bar (at font scale 1.5 and up only the chosen item shows its name,
  shrunk to fit the item, `NavigationChrome.BarLabel`) and the avatar letters (size and line height from the circle,
  not the font scale) are fixed.

## D3A-14, the fixture: `Samples.provider(statusMessage = "ok")`

- **What:** testkit `Samples.provider` (WP8's `:testkit`) gives every sample provider the status message "ok".
- **Why left:** the Dashboard now shows a provider's status message only when it is an owner sentence
  (`Attention.ownerSentence`: starts with a letter, has a space, ends with a full stop, not "ok"), so "ok" never reaches
  the screen; changing the shared sample touches many contract tests for no owner-visible gain.

## D3A-20: one wording file for tokens, answers, the estimate disclaimer and "phone" vs "device" (wave 4, as planned)

- **Done here:** `ui/components/PlainWords.kt` exists and holds D3A-3's sentences (`LIMIT_PAUSES_OBJECTIVES`,
  `RESUME_AFTER_LIMIT`), pinned by `PlainWordsTest` (including a source scan for "pauses until midnight" and the like).
  The objective header and the new task cost line now use `TOKENS_IN_PLAIN_WORDS` instead of their own wording.
- **Left:** `chat/ChatCost.TOKENS_EXPLAINED` (same words as `TOKENS_IN_PLAIN_WORDS`, a second constant),
  `NewObjectiveViewModel`'s "(the pieces of text a model reads and writes)", and the chat's "Stays on this device" /
  "Leaves this device (cloud)" (`RegistryLabels.Locality.destinationPhrase` and `.label`, used on many screens and pinned
  by many tests). Moving them all into `PlainWords.kt` changes texts on several screens at once: wave 4 (WP12 lens), then
  WP8, as the finding says.

## D3A-19: only the Calm half is pinned by a JVM test

- `CalmMotionTest` shows that under Calm a growing block has its end size at once. That it still animates without Calm is
  not pinned: Robolectric's frame clock settles `animateContentSize` within a few frames, so the device run checks it
  (`device-tests-wave3.md` section 9).

## Files of WP8 this fixer changed (allowed for findings that needed them)

`ui/components/StatTile.kt` (caption on two lines), `ScreenScaffold.kt` (two-line title at large fonts), `AgentAvatar.kt`,
`Modifiers.kt` (`calmAnimateContentSize`), `RegistryLabels.kt` (`localityOf`, `onPhoneModels`), new `PlainWords.kt`;
`ui/shell/NavigationChrome.kt` (bar labels); `usage/TodayUsage.kt` (`TodayUse.currency`, `limitCurrencyOf`: the screens'
read model of today, so every screen names the money limit's currency the limiter's way, D3A-8); tests
`journeys/DashboardWiringJourneyTest.kt`, new `ui/components/PlainWordsTest.kt` and `CalmMotionTest.kt`. No core,
framework, data, device or testkit file was touched.

## Notes for wave 4 (not findings)

- `QuestionViewModel` shows `failure.message` when saving an answer throws ("Your answer could not be saved: …"): a
  storage exception's message could be technical. Pre-existing; worth a plain sentence.
- `ChatScreen.kt` imports `animateContentSize` without using it (pre-existing, harmless).
