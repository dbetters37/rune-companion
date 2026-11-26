# Hand-off from WP10b (wave 3, part 2): Settings background, questions, Calm and Backup; the backup screens; the Recovery screen

Branch `p6/wp10b` (worktree `/home/user/p6-wp10b`), from e79e164. WP10b changed only files wave3-ownership.md gives it:
`ui/settings/BackgroundSection.kt`, `BackgroundSectionViewModel.kt`, `QuestionTimeoutSection.kt`, `QuestionSettingsViewModel.kt`,
`MotionSection.kt`, `MotionSettingsViewModel.kt`, `BackupSection.kt`; `ui/backup/**` (the three stubs and the new
`BackupParts.kt`); `ui/recovery/**` (the two stubs and the new `SecureWindow.kt`). Tests: `ui/settings/WP10bSettingsViewModelsTest`,
`WP10bSettingsScreenTest`, `ui/backup/**`, `ui/recovery/**`, new `journeys/BackgroundCheckJourneyTest`, `BackupJourneyTest`,
`RecoveryJourneyTest`, new `screenshots/WP10bScreenshotTest`, and the helper `testing/WP10bTesting.kt`. Every
`@Phase6Stub("WP10b")` is gone (12 marks). `SettingsViewModel.kt`, `DeviceSection.kt`, `OrchestrationLimit.kt` (also WP10b's)
are unchanged.

## 1. What WP10b built (plan WP10, second half)

| Item | What | Where |
|---|---|---|
| Settings › Working in the background (A3) | A summary line ("Android lets the app work in the background, so agents keep working with the screen off." / "Android may stop agents working in the background. Fix each item below with one tap: it opens Android's own screen.", tone and icon, never colour alone) and four items, each in words with an icon and read as one phrase: Notifications (Allowed / Off → "Turn on notifications", the app's page), Battery saving ("Battery saving applies to the app" → "Let the app run in the background", `BackgroundScreens.batteryExemption()`), Background use ("Background use is restricted" → the app's page), How often Android lets it run ("Android rarely lets it run" → the battery request); what Android does not say is "Android did not say." (never a warning). The words are the Dashboard phone items' (WP10a). Android is asked again whenever the section comes back on screen (`LifecycleResumeEffect` → `BackgroundHealth.refresh()`), so returning from Android's screen shows the change; "Check again" too. A missing screen (no intent, `ActivityNotFoundException`, `SecurityException`) shows `BackgroundSettingsIntents.NO_SUCH_SCREEN` and offers "Open the app's page in Android"; if that is missing too, "Open Android's Settings, then Apps, then AI Control Center." Switches "Keep the phone awake while agents work" (`keepAwakeWhileAgentsWork`, what it holds and when it lets go) and "Tell me when work ends" (`notifyWhenWorkEnds`). | ui/settings/BackgroundSection.kt, BackgroundSectionViewModel.kt |
| Settings › Questions from agents (Q3, Tier B) | "Agents may ask me questions" (`agentQuestionsEnabled`): what a question is and is not ("never a permission: your answer is only text for the agent"), what Off does (no new questions; one waiting can still be answered). "Time to answer" as chips 5, 10, 20 (usual), 30 minutes, 1, 2, 4 hours (`ownerQuestionTimeoutMinutes`, `SettingsBounds.QUESTION_TIMEOUT_MINUTES`; a stored value between them is shown in its place; outside 5..240 ignored), with what happens at the time-out and that waiting does not count against the task's time limit (as `OwnerQuestions` does it). | ui/settings/QuestionTimeoutSection.kt, QuestionSettingsViewModel.kt |
| Settings › Appearance: Calm (rule 17) | "Calm" (`calmMotion`): nothing moves by itself (Team view dots, network pulses, sliding panels); with Android's animations off (`ValueAnimator.areAnimatorsEnabled()`, read again on every resume) the card says the app is calm already. The root already reads the setting (`LocalCalmMotion`). | ui/settings/MotionSection.kt, MotionSettingsViewModel.kt |
| Settings › Backup (Tier B) | What a backup is, that keys are not in it, "Save a backup", "Restore from a backup", "A backup can only be restored into a fresh app…". Shown only where `graph.ownerBackup != null` (WP8's slot). | ui/settings/BackupSection.kt |
| Save a backup (Tier B; rules 8, 21, 27) | FLAG_SECURE while shown (`SecureWindow`). What is in the file and what is not (`BackupTexts.WHAT_IS_IN`, `WHAT_IS_NOT`, `OBJECTIVES_NOT_IN`). The generated passphrase (`SavePassphrase`, 100 bits) shown once in its five groups, large and monospaced, spelled out for TalkBack group by group, with `WRITE_IT_DOWN`, which letters it never uses, and a tick "I have written down this passphrase" before Save; "Make a new one"; "Use my own passphrase": two hidden fields (`SecretTextField`: password keyboard, no suggestions, nothing learned, no copy) with `Passphrases.problemForSave`'s own plain reason under the first, "The two passphrases are not the same." under the second, and a count of characters typed (nothing else of the text is shown). `CHOOSE_PLACE` (rule 21) and `KEEP_OPEN`; "Choose where to save it" opens Android's document picker (`ACTION_CREATE_DOCUMENT`, `application/octet-stream`, "AI Control Center backup <date>.aiccbackup"). The save runs on `Dispatchers.Default` (never the main thread); its steps (`BackupTexts.STEP_*`) show with a bar (determinate when measured; the key step has no measure: a moving bar, or under Calm a clock and "Working on it…"), "This takes a few seconds on most phones and can take a few minutes on slower ones…", and Cancel (Back asks "Stop saving?" first). A save that does not finish (failed, refused, cancelled) removes the document the picker made (`DocumentsContract.deleteDocument`). Done: "Backup saved", what the file holds and its size, `SaveOutcome.notes`, and the passphrase is gone from the screen. The passphrase goes to the port exactly through `SavePassphrase.forSave` (generated: as both passphrase and `generatedShown`; own: `generatedShown = null`). Both fields and the generated passphrase are forgotten (text and undo history) when the screen closes. | ui/backup/BackupSaveScreen.kt, BackupViewModels.kt, BackupParts.kt |
| Restore a backup (Tier B; rules 8, 27, 30) | FLAG_SECURE. `RESTORE_ONLY_FRESH` + `STARTER_REPLACED`. 1. The file (`ACTION_OPEN_DOCUMENT`, any type; its name read off the main thread). 2. Its passphrase (`SecretTextField`, `PASSPHRASE_ENTRY`). "Open the backup" runs `preview` (inspect; "changes nothing on this phone") on `Dispatchers.Default` with the steps and Cancel. A wrong passphrase and a damaged file give their own sentences (`BackupProblem.WrongPassphrase` / `Damaged`); a wrong one empties the hidden field to be typed again. The summary: what it holds (`BackupTexts.counts`), when and by which version it was made, its size, the notes; "Settings that change on this phone" (every `SettingChange`, "Now: … · After the restore: …", the Director by name: the agent on this phone now is named from the directory "(on this phone now)", an agent the backup does not hold is `RestoreSettings.UNKNOWN_AGENT`, never an id); `BackupTexts.ALWAYS_OFF`; "Kept back from the backup" (every `SettingKeptBack` by name, file value and value after the restore); "Never in a backup" (`WHAT_IS_NOT`, `KEYS_AGAIN`, `OBJECTIVES_NOT_IN`). A refusal known in advance (`RestorePreview.refusal`: not fresh, too little room, a restore running) is shown and there is no Restore button. Restore is off, with "An objective is running. Try again when it has finished." / "An agent is answering in a chat. …" (core's `BackupProblem.Busy` wording) while `orchestrator.running` or `agentChat.liveTurns` holds something. "Restore this backup" asks once more (what is replaced, keys again, the restart, keep the app open), then `restoreWithNotes` with the steps; Cancel while the passphrase is checked or the file read, never while records are written. Done: "Backup restored", what was restored and every note (`SETTINGS_NOT_RESTORED` included), "Start the app again" → `RecoveryActions.restartApp` (rule 30); Back, or leaving the screen any other way, also restarts (once). | ui/backup/BackupRestoreScreen.kt, BackupViewModels.kt |
| Recovery screen (R5; rules 9, 27, 30) | FLAG_SECURE. Title "The app cannot open your data"; `DatabaseState.ownerText` and what to try first, per state (missing/damaged key: try once, then Start over; keystore: restart the phone; damaged file: Start over keeps it aside; failed update: look for an update; other: try again, restart the phone). "Try again" (the gate's `recheck`, WP8's `onTryAgain`; when the screen comes back: "Looked again: the data still does not open."). "Close and try later" (`Activity.finish()`; "Closing changes nothing…"). "Start over" (only with an `OwnerRecovery`): in words what is set aside (data, its key, saved provider keys, in a folder of the app's private storage, nothing deleted, the Files app cannot see it, uninstalling removes it), what is set back (starter team and Getting started, the Director, both phone-control switches; shared folders let go), what is kept (other settings), and that a backup can be restored afterwards; the word START OVER typed in a hidden field (`SecretTextField`, `OwnerRecovery.confirms`, a count of characters typed); Start over → `OwnerRecovery.startOver()`; Done → `RecoveryActions.restartApp` (rule 30); Refused → its reason as it is. Nothing in `ui/recovery` names the graph (`RecoveryPathTest`'s scan still passes). | ui/recovery/RecoveryScreen.kt, RecoveryViewModel.kt, SecureWindow.kt |

## 2. Hand-off items addressed to WP10 / WP10b

Done:
- handoff-secfix.md "Wave 3 notes" and "Second review" (WP10): the restore inspect screen lists `changes`, `keptBack` and the always-off line, the Director by name, never the id fields; the passphrase goes only through `problemForSave` (via `SavePassphrase`). (The pick-up screen items are WP10c's.)
- handoff-wp0.md §3: every new field is `SecretTextField(state = …)` over a view-model `TextFieldState` (`ownPassphrase`, `ownPassphraseAgain`, `passphrase`, `confirmWord`), password keyboard, no suggestions; FLAG_SECURE on the three screens; a paused-clock typing test for each (`BackupScreensTest`, `RecoveryScreenTest`, `RecoveryJourneyTest`, `BackupJourneyTest`).
- handoff-wp1d.md WP10: `RecoveryHost(state, recovery, …)` gets `recovery` from the application, never the graph.
- handoff-wp2.md §4 WP10 (screens): Recovery (confirm word, `startOver` + restart, `Refused.reason` as it is, "Try again" → `recheck`, FLAG_SECURE, `ownerText`, the old data kept in private storage); background check (state, `refresh()` on return, the two buttons, `NO_SUCH_SCREEN` with the other button); keep awake and tell me when work ends. (Storage and safe start were WP10a's.)
- handoff-wp3.md §4 WP10: everything listed (SavePassphrase, WRITE_IT_DOWN, OWN_PASSPHRASE_RULE, forSave, CHOOSE_PLACE, WHAT_IS_IN/NOT, OBJECTIVES_NOT_IN, KEEP_OPEN, CREATE_DOCUMENT with the extension, delete a partly written document, SaveOutcome.notes, forget + clearText on close; OPEN_DOCUMENT, PASSPHRASE_ENTRY, preview with counts, setting changes, keptBack, ALWAYS_OFF, notes, RESTORE_ONLY_FRESH, RESTARTS, refusal without a Restore button, restoreWithNotes, restartApp; progress steps, indeterminate key step with Cancel, callbacks into a StateFlow; FLAG_SECURE on both).
- handoff-wp5.md §3 WP10 (settings): "Questions from agents" and "Question time-out" (5..240, default 20).
- handoff-wave2b-merge.md §3 (WP10 rows for Recovery, background check, Backup) and handoff-wave2a-merge.md §3 (WP10's Settings questions row).
- wave2b-minors-left.md WP10: `BackupTexts.SETTINGS_NOT_RESTORED` is shown with the other notes on the restore result.
- handoff-w2bfix.md: the restore's settings note (as above).
- handoff-WP10a.md §4 WP10b: the background section uses the phone items' words ("battery saving", "background use is restricted", "Android rarely lets it run").
- handoff-wave3a-merge.md §4: Calm is only written here (`MotionSection`); the root reads it. The background section lists no notification channel.
- handoff-wp11a.md §3 WP10b: the switch's text says open questions can still be answered when questions are off.

Not done here (needs a file WP10b does not own), see section 3: the deep link from the phone items to the background section
(handoff-wave3a-merge.md §3 row 1, handoff-WP10a.md 2.8).

## 3. Changes needed in files WP10b does not own (for WP8)

| # | File | Change | Why |
|---|---|---|---|
| 3.1 | ui/settings/SettingsScreen.kt (`SettingsRoute`), ui/shell/AppNavHost.kt, ui/dashboard/DashboardScreen.kt | The deep link of handoff-wave3a-merge.md §3: e.g. `SettingsRoute(scrollTo: SettingsAnchor? = null)` with a `BringIntoViewRequester` passed to `BackgroundSection(background, modifier = Modifier.bringIntoViewRequester(requester))` (the FINAL signature already takes `modifier`), requested once when the route opens with `BACKGROUND`; the phone items' `onOpenSettings` opens it so. | Goal 5: one tap from the Dashboard item to the fix. Today the item opens the top of Settings. |
| 3.2 | testing/TestApp.kt (optional) | `BackgroundPorts(…, screens = TestBackgroundScreens())` and, if wanted, a backup over the in-memory stores (`testing/WP10bTesting.kt` `wp10bGraph` shows how: `BackupStores` over the test app's stores with a pass-through `Transactions`, `BackupWiring.production`). | WP10b's journeys build a second `AppGraph` over `TestApp.ports` (as `FastTypingTest` does) because TestApp has neither. With them in TestApp, `wp10bGraph` can go. |
| 3.3 | MainActivity.kt (optional) | When an activity is created while the gate is `NeedsRecovery` (the owner reopened the app after "Close and try later" in the same process), call `gate.recheck()` once. | Reopening then looks again by itself; the Recovery screen's line would then become "the app looks again when you open it" (today it says "Open the app again later and tap Try again", which is true either way). |
| 3.4 | Tier B switches (after wave 4 round 2) | If C1 (questions) is cut: `SettingsSlots.questions = null` with `agentQuestionsEnabled` forced off. If the backup is cut: `BackupWiring.production` returns null (the section and both screens are then unreachable; the screens show "This version of the app has no backup." if reached). | plan §2 Tier B. |
| 3.5 | Phase6StubGateTest | WP10b leaves no stub: `STILL_BUILDING` can drop WP10b at the merge. | stub gate |

## 4. Notes for wave 4's reviewers and wave 5's end-to-end run

- **Rule 27.** FLAG_SECURE comes from `ui/recovery/SecureWindow.kt` (`DisposableEffect` on the activity's window; cleared when the
  screen goes; dialogs are separate windows: no passphrase is shown in a dialog). Passphrases live only in view-model
  `TextFieldState`s (never in a `uiState`), are cleared with their undo history in `onCleared`, after a save or a restore, and a
  wrong passphrase empties the field. The generated passphrase is in `BackupSaveUiState.generated` while the screen shows (it
  must be shown); it survives a rotation (same view model) and is gone after a save or when the screen closes.
- **Rule 30.** Start over and a finished restore restart through `RecoveryActions.restartApp`. The restore result waits for the
  owner's "Start the app again" so its notes are read; Back and leaving the screen any other way (the wide rail) also restart,
  once (`AtomicBoolean` guard; the restart's own `finishAffinity` disposes the screen). Attack: navigate elsewhere between the
  restore's end and the tap on a wide screen.
- **Never the main thread.** Save, open and restore run on `Dispatchers.Default` whatever `LocalScreenWork` says (the key
  derivation is CPU work). The own passphrase's rule (`problemForSave`) runs in composition per key typed (at most 256
  characters, word and pattern checks only).
- **Restore while work runs.** The screen refuses while an objective runs or a chat answer is under way; the backup itself refuses
  an app that is not fresh and re-checks inside the transaction (WP3).
- **Cancel.** A cancelled save removes the picker's document; a cancelled opening writes nothing; Cancel is not offered once the
  records are written (Room's transaction would roll back, the in-memory test stores would not).
- **Background check.** Nothing here changes what Android allows; every button opens Android's own screen. Item rows are
  `clearAndSetSemantics` with one phrase each; their buttons are separate 48 dp targets.
- **Device steps:** `device-tests-wave3.md`, section "WP10b".

## 5. Deviations from the plan (and why)

1. **A tick "I have written down this passphrase" before saving with the generated passphrase**, and **the own passphrase typed
   twice** (a second hidden field). The fields hide every character; one slip would lock the owner out of their own file for good.
2. **The restore result is shown before the restart** ("Start the app again"), not a restart at once: the notes after a restore
   (`SETTINGS_NOT_RESTORED`, memories left out) would otherwise never be read. Leaving the screen in any way restarts.
3. **Cancel during a restore only while the passphrase is checked or the file read**, not while records are written.
4. **Time to answer as seven chips** instead of a free number or a stepper: fewer taps, readable at font 2.0; a stored value
   between them is kept and shown.
5. **Defaulted trailing constructor parameters** on the backup view models (`work`, `passphrase`; `work`, `busy`, `zone`) and on the
   two screens (`restart`, `close`) for tests and the route; the FINAL calls compile unchanged. `RecoveryHost`'s signature is unchanged.
6. **The journeys use their own graph** (`wp10bGraph`, a second `AppGraph` over `TestApp.ports` with the real backup and test
   background screens) because TestApp has no backup and no background screens (3.2). The Recovery journey composes the same
   `GateHost(gate, recovery = { RecoveryHost(…, gate::recheck) }) { AiControlCenterRoot(…) }` as `MainActivity` with a real
   `AppGate` over `FakeOwnerRecovery`, instead of launching `MainActivity` (whose `app.recovery` is the real SQLCipher-backed one).
7. **`SecureWindow` lives in `ui/recovery`** (WP10b's folder) and the backup screens use it; WP8 may move it to `ui/components`.
8. **"Waiting for you does not count against the task's time limit"** is said under the time-out (verified in `OwnerQuestions`:
   the wait is outside every time limit and moves the task's deadline).

## 6. Checks run

- Head `3f0d399` on `p6/wp10b` (5 commits from e79e164; not pushed, not merged).
- Whole suite in the worktree (`./tools/gw --offline test --continue`, then `./tools/test-summary`): **3,741 tests, 0 failed** — app
  1,003, core 182, data 494, device 367, framework 1,096, memory 107, providers 229, providers-anthropic 89, testkit 174. The app
  module was run again in full after the last two small changes (Back during a restore's writing, the keys line): 1,003, 0 failed.
  New in WP10b, all in app (72): `WP10bSettingsViewModelsTest` 10, `WP10bSettingsScreenTest` 5, `BackupViewModelsTest` 13,
  `BackupScreensTest` 7, `RecoveryViewModelTest` 5, `RecoveryScreenTest` 4, `BackgroundCheckJourneyTest` 3, `BackupJourneyTest` 2,
  `RecoveryJourneyTest` 3, `screenshots/WP10bScreenshotTest` 20. Still green and relevant: `RecoveryPathTest` (the Recovery path
  never names the graph), `TextFieldRuleTest`, `Phase6StubGateTest`, `Wave3RoutesTest`.
- `./tools/gw --offline :app:assembleDebug`: BUILD SUCCESSFUL.

## 7. Screenshots

Review material, not committed (as WP10a's): `app/build/outputs/roborazzi/p6_wp10b_*.png` in the worktree, copied to
`scratchpad/phase6/wp10b-shots/` (Settings background, questions, backup, appearance in dark, light, wide, 360 dp and font 2.0;
Save: generated, own with its reason, the key step under Calm, saved, light, wide, 360 dp font 2.0; Restore: empty, wrong
passphrase, summary, restored, wide, 360 dp font 2.0 light; Recovery: key damaged, Start over, keystore light, wide, 360 dp,
360 dp font 2.0).
