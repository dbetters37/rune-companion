# Hand-off from WP10c (wave 3, part 2): Settings › Routines and folder pick-up, the Routines list and editor, the "Tap to start" step, Folder pick-up

Branch `p6/wp10c` (worktree `/home/user/p6-wp10c`), from e79e164. Spec: plan OWNER DECISIONS (Q1 = "Yes, both", Tier B),
Appendix A, wave3-ownership.md (binding), handoff-wp17.md §2/§6, handoff-secfix.md (second review: PickUpSafety,
"Make it read-only", the Start button is WP17's banner), handoff-w2bfix.md (Downloads-entry and other-app folders),
handoff-wave3a-merge.md §3/§4, handoff-wp11a/WP11b/WP10a notes for WP10c.

WP10c changed only files it owns (wave3-ownership §5: `ui/routines/**`, `ui/pickup/**`, `ui/settings/RoutinesSection.kt`,
`RoutinesSettingsViewModel.kt`, `routines/**`) and added test files of its own (`ui/routines/**`, `ui/pickup/**`,
`routines/ExactAlarmsTest.kt`, `ui/settings/RoutinesSettingsViewModelTest.kt`, `ui/settings/RoutinesSectionTest.kt`,
`journeys/RoutinesJourneyTest.kt`, `journeys/PickUpJourneyTest.kt`, `screenshots/RoutinesScreenshotTest.kt`,
`testing/WP10cTesting.kt`). All nine `@Phase6Stub("WP10c")` marks are gone (`Phase6StubGateTest` lists none of WP10c).

## 1. What is built

| Item | Where | What the owner gets |
|---|---|---|
| Settings › Routines and folder pick-up | `ui/settings/RoutinesSection.kt`, `RoutinesSettingsViewModel.kt` | The two master switches (both off after installing), each saying in plain words what it does and that the work then runs by itself while nobody looks; switching **on** asks once more (guarded `ConfirmDialog`: the owner's rule on consequential actions), switching off is at once; "No routines yet." / "3 routines, 2 of them switched on." (+ "None runs while routines are off." while the switch is off); "Open routines", "Open folder pick-up". A part whose port is null stays hidden; the whole section draws nothing when both are null. |
| Routines list | `ui/routines/RoutinesScreen.kt`, `RoutinesViewModel.kt` (`RoutinesViewModel`) | Each routine: title, schedule in words ("On weekdays at 07:30", "Every 3 hours from 08:00, every day"), status word with icon (On / Off / Paused, never colour alone), "Next: tomorrow at 07:00" (or "Does not run while routines are off in Settings."), the pause note, failures in a row, "No screens" / "May use other apps' screens"; "3 of 10 routines", "New routine" (off at 10, with why); the master switch's state with "Open Settings"; "1 routine is waiting for you to tap Start." with "See what is waiting" (→ `WaitingRoutinesRoute`); "How routines start": "Alarms and reminders" state (not allowed: "Routines may start late" + what happens without it, a "Tap to start" notification, + "Allow alarms and reminders" → Android's page; allowed; Android 9-11: always), late runs (30 min / 3 h), safe start (routines wait for a tap), the limits, the clock changes. Read again on return from Android's page (`LifecycleResumeEffect`). |
| Routine editor | `ui/routines/RoutineEditorScreen.kt`, `RoutinesViewModel.kt` (`RoutineEditorViewModel`), `RoutineWords.kt` | Name (≤ 80, counter) and "What the team should do" (≤ 4,000) as `AppTextField(state = …)` over the VM's `TextFieldState`s, read when Save is tapped (rule 27); "Who works on it" (the Director plans and hands parts to the team); schedule: "Once a day" / "Every few hours" (radio chips with a check mark), hour and minute buttons, "How often" 1-12 h, day chips (full day names for TalkBack) + Every day / Weekdays / Weekends; the schedule in one sentence, the times of a day, "With this schedule it next runs …", the next clock change that touches it ("On Sunday 28 March the clocks go forward: 02:30 does not happen that night, so it runs at 03:30.") and the general rule; "Have results reviewed"; "May use other apps' screens" off by default, switching on asks once more (`widens`, guarded) and shows the screen-on-and-unlocked warning; how long requests wait (As usual, 15 min … 4 h, plus a stored odd value). Existing routine: Switched on (next time, failures) or, when paused, "It switched itself off" + the note + "Switch it on again"; the run history (20 newest) with each outcome in handoff-wp17 §6's words and icon, the app's note, "Open the objective" for a started run; Delete (asks; objectives are kept). Every refusal of `OwnerRoutines` is shown as it is ("You can have at most 10 routines. Delete one first."). |
| "Tap to start" step | `ui/routines/WaitingRoutinesScreen.kt`; `routines/WaitingRoutinesBanner.kt` (extended) | WP17's banner (Start asks once more, then exactly that run starts) under a line saying nothing starts until Start and confirm; "Nothing is waiting to start" when empty; after a confirmed start, "Open the objective" (new `WaitingRoutinesUiState.started`, `WaitingRoutinesBanner(…, onOpenObjective = null)` defaulted: the Dashboard's call is unchanged). |
| Exact alarms state | `routines/ExactAlarms.kt` (new) | `ExactAlarmState` (ALLOWED / NOT_ALLOWED / ALWAYS), `ExactAlarmAccess` (`state()`, `settingsIntent()`), `AndroidExactAlarmAccess(context, sdkInt)` = the same `canScheduleExactAlarms()` check `AndroidRoutineAlarms.exact()` makes, and `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` with `package:<this app>` (this app only). |
| Folder pick-up list | `ui/pickup/PickUpRulesScreen.kt`, `PickUpViewModels.kt` (`PickUpRulesViewModel`) | Each rule: "From "Inbox" into "Inbox notes"", On / Off / Stopped (with icon), "Last looked today at 03:12" or "Not looked at yet", the last result in the runner's own words (which name skipped and refused files and why), the 10 newest files ("Picked up today at … · 42 bytes" or "Left out: it was already in the folder when the rule was switched on."); Change, Switch on (again) (refusals shown as they are), Open the space; master switch state with "Open Settings"; "2 of 10 rules", "New rule"; "How pick-up works": read-only only, text files only ≤ 1 MB each and 5 MB a day, files already there left out, sub-folders not read, about once a day while charging and idle, nothing sent to a model by picking up and agents that may read the space can recall it; "Open shared folders". |
| Folder pick-up rule editor | `ui/pickup/PickUpRuleEditorScreen.kt`, `PickUpViewModels.kt` (`PickUpRuleEditorViewModel`) | New rule: every shared folder as a radio row with "Read-only for agents" / "Agents may change files in it" and "Not usable yet: choose it to see why."; the chosen folder's reason exactly as `PickUpSafety.folderProblem` gives it (FOLDER_NOT_READ_ONLY, FOLDER_OVERLAPS_WRITABLE, FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE, FOLDER_PLACE_UNKNOWN_NEAR_WRITABLE, FOLDER_OTHER_APP_NEAR_WRITABLE, FOLDER_GONE; "has a rule already") with **"Make it read-only"** (through `OwnerFolders.setReadOnly(id, true)`) when the folder itself is writable, else "Open shared folders"; the spaces as radio rows (the only space chosen by itself); the no-model sentence; Make the rule (off until a usable folder and a space are chosen); refusals of `OwnerPickUpRules` shown as they are (FOLDER_UNREADABLE, TOO_MANY, …). Existing rule: on/off switch (switching on is checked again), the folder, another space, Delete (asks; memories kept). The folder preselected when opened from Tools › Folders "Pick up new files" (`folderId`). |

Plain words everywhere; no "(test)" control (everything works; the optional "import the files seen at switch-on by ticking them"
was not built, so it has no label either). 48 dp targets; merged semantics per card and per history row; radio roles; status
words with icons; 360 dp, wide, dark, light, font 2.0 checked in pictures. Lists build `flowOn(LocalScreenWork.current)`.

## 2. Changes needed in files WP10c does not own (for WP8)

| File | Change | Why |
|---|---|---|
| `ui/dashboard/DashboardScreen.kt` (DashboardRoute's `waitingRoutines` slot) | Optional: `WaitingRoutinesBanner(viewModel, onOpenObjective = navigation.onOpenObjective)` | After "Start now" on the Dashboard banner the message could then offer "Open the objective" as the "Tap to start" step does. Without it the Dashboard shows the message with OK only (unchanged behaviour). |
| `ui/dashboard/Attention.kt` (WP10a's) | Optional (handoff-wave3a-merge §3 row "a waiting-routines count in the attention list"): an item "1 routine is waiting for you to tap Start" leading to `WaitingRoutinesRoute` | Not done: the file is WP10a's and the Dashboard already shows WP17's banner at its top, so the count would repeat it. |
| `testing/AppRobot.kt` | Optional: `openSectionViaMore` could `performScrollTo()` the section in the "All sections" sheet before clicking it | At 360 dp and font 2.0 "Settings" is below the sheet's fold and the click does nothing; `RoutinesScreenshotTest.section()` does the scroll itself. |
| core `pickup/PickUp.kt` (WP8, wave 4) | Optional: "Tools > Folders" → "Tools › Folders" in the `PickUpSafety` sentences | Same as wave3a-minors-left D3A-16 for `DailyLimits.sentence`: the screens show the core sentences as they are, with ">". Pinned by core and framework tests. |

No change to Ports, ProductionPorts, AppGraph, TestApp, the manifest, build files or the architecture tests was needed.
`RoutinesViewModel`, `RoutineEditorViewModel`, `PickUpRulesViewModel` and `PickUpRuleEditorViewModel` keep the stub
constructors' ports and add only defaulted non-port parameters (see §4, deviations 1-2).

## 3. Tests (all green in the worktree)

New (app): `RoutineWordsTest` 5, `RoutinesViewModelTest` 7, `RoutineEditorViewModelTest` 10, `RoutineScreensTest` 5
(paused-clock typing into Name and "What the team should do" with Save storing it exactly; the 80-character cap; TalkBack
semantics; 48 dp; the empty "Tap to start" step), `PickUpViewModelsTest` 9 (picked up / left out with reasons; a folder
made writable stops its rule; FOLDER_NOT_READ_ONLY + Make it read-only; FOLDER_OVERLAPS_WRITABLE, FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE,
FOLDER_OTHER_APP_NEAR_WRITABLE, FOLDER_UNREADABLE, TOO_MANY, CHOOSE_SPACE; change space, on/off, delete), `PickUpScreensTest` 2,
`RoutinesSettingsViewModelTest` 5, `RoutinesSectionTest` 2, `ExactAlarmsTest` 3 (Robolectric `ShadowAlarmManager`),
`RoutinesJourneyTest` 5 (Settings → on after the question → New routine → Save → the run waits → the app opens the step by
itself → Start asks → Start now starts exactly that run with the routine's options → history "Started" → Open the objective
shows "Started by routine "Morning news""; Not now → Missed, nothing started; "Alarms and reminders" opens Android's page and
is read again; Switch it on again; screens need a second yes), `PickUpJourneyTest` 2 (Tools › Folders "Pick up new files" →
rule; pick-up on after the question; only the new text file is imported, as IMPORT; writable folder refused, Make it
read-only, rule made, made writable again → Stopped and Switch on again refused), `RoutinesScreenshotTest` 35 cases (49 pictures).
Existing `WaitingRoutinesBannerTest` (WP17) still passes with the banner's new optional link.

Whole suite in the worktree (`./tools/gw --offline test --continue`, then `./tools/test-summary`), at head c1eeeb3:
**3,759 tests, 0 failed** — app 1,021, core 182, data 494, device 367, framework 1,096, memory 107, providers 229,
providers-anthropic 89, testkit 174 (app +90 from WP10c's new test cases, 35 of them pictures; every other module unchanged).
The first full run failed one WP8 test, `ui/shell/Wave3RoutesTest` ("RoutineEditorRoute(rtn_1) shows "Routine""): the page
of an existing routine is now titled "Routine" (it said "Change routine"), as the stub was; no WP8 file was changed.
`:app:assembleDebug`: BUILD SUCCESSFUL. Commits on `p6/wp10c`: 37f45a7, 908d32f, 106e1fe, 19b7e98, a6aef53, f64c304, c1eeeb3
(head). Not pushed, not merged.

## 4. Deviations from the plan, and why

1. **`RoutinesViewModel` has two more defaulted parameters**: `exactAlarms: ExactAlarmAccess` (the "Alarms and reminders"
   state the task asks for has no graph port; it is this app's own permission, read through `AlarmManager` in
   `routines/ExactAlarms.kt`) and `computation` (the background pool, as WP11a's view models). Neither is an owner or agent port.
2. **`RoutineEditorViewModel`, `PickUpRulesViewModel`, `PickUpRuleEditorViewModel` gained `computation`**, and
   `PickUpRulesViewModel` also `zone` ("today at 03:12" needs the owner's zone; the stub had `clock` only).
3. **"Which agents"**: routines have no agent field in the core contract (`RoutineDraft` has options, not a team): the editor says
   that the Director plans each run and hands parts to the team, as for any objective. Naming the Director would need the
   registry, which the editor's constructor does not have.
4. **"Save what was learned to memory" is not offered for routines** (only "Have results reviewed"): whether the Director may
   write a space needs the registry; a routine keeps whatever its stored options say. 
5. **Time is chosen with hour and minute buttons (minutes in steps of 5), not a clock dial**: no text field and no Material
   time picker (whose internal fields are not rule 27's `state` fields and whose dial does not fit 360 dp at font 2.0);
   TalkBack reads "Hour, 07" and "Increase Hour".
6. **The approval time-out is a set of choices** (As usual, 15 min, 30 min, 1 h, 2 h, 4 h, plus a stored odd value), not a
   number field: no typing needed, and every value is inside 1..240.
7. **The pick-up list shows imports per rule on the list** (10 newest each), not on the editor: `PickUpImports` is a port of
   the list's view model only (wave3-ownership §4).
8. **Skipped and refused files are shown through the run's last result** ("1 file was larger than 1 MB, so not picked up."),
   not one by one: the store records only imported and seen files (WP17's design); non-text files are never listed.

## 5. Notes for wave 4's reviewers and wave 5

- Save on an existing routine or rule with nothing changed goes back without calling the port (no "Routine changed" log
  line, and the failures in a row, which WP17 counts since the owner's last change, are not started afresh).
- Rule 3: only `SettingsRoute` → `RoutinesSettingsViewModel` holds `SettingsRepository` (the two master switches); the other
  view models hold the owner's ports (`OwnerRoutines`, `WaitingRoutines`, `OwnerPickUpRules`, `PickUpImports`, `OwnerFolders`,
  `MemoryService`) and `SettingsView` read only. No agent-facing code is touched.
- F12 / rule 23: nothing starts work from these screens but the banner's Start + "Start now"; the step is reached by the
  shell from what waits (WP8) or from the list's link; no intent selects a screen.
- F1: "Make it read-only" only ever makes a folder *more* restricted; a rule is made only through `OwnerPickUpRules.create`
  (baseline, `readOnlySince`), and every refusal sentence is the core's.
- Attack ideas: a routine title or objective with direction marks or very long words (shown as typed, max 80/4,000); a
  folder's display name with control characters (shown as stored; `PickUpSafety.source` cuts it for memories); switching
  the master switch on while a run is due (WP17's `masterSwitchedOn` moves stale due times on).
- Device steps: `device-tests-wave3.md`, section "WP10c".
- Pictures (review material, not committed): `app/build/outputs/roborazzi/p6_wp10c_*.png`, copied to
  `scratchpad/phase6/wp10c-shots/`.
