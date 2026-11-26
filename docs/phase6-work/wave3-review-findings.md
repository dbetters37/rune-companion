# Wave 3 design and review findings (NOT YET FIXED)

Found at 88060fa by the wave-3 design reviewer and the security and integration reviewers. The fix run was stopped before it committed anything; all of these are open.

## design:wave3b

### D3B-1 (major) Team view at 360 dp: the Picture/List switch and the hint are cut in half by the picture
- **package:** WP9
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/team/TeamScreen.kt, TeamScreen(): narrow layout, `Column(Modifier.fillMaxWidth().heightIn(max = headerMax).verticalScroll(...)) { controls() }` with `val headerMax = maxHeight * 0.45f`, followed by TeamPicture(... Modifier.weight(1f))
- **problem:** On a 360 dp phone the header (objective chips, summary, objective card, 'not in this objective', Picture/List, hint) is capped at 45% of the height and scrolls inside itself. The cap falls in the middle of the Picture/List segmented button and the 'Tap anyone…' hint, so only the top half of the switch shows. The header is a scroll area placed right above a drag-to-pan picture, and nothing shows that it scrolls. The owner may never find 'List'. A paused or stopped objective, whose card is taller, or a font scale of 1.3 makes it worse.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp9_team_paused_light_360.png (the switch is cut through its middle and the hint is not visible); p6_wp9_team_objective_360.png (the hint is cut mid-line: 'Drag to move, pinch to…')
- **fix:** Pin the one-line summary and the Picture/List switch outside the capped area, directly above the picture, so they always show in full. Let only the objective card and its notices scroll, or collapse the card to one line ('Paused: today's limit · Open objective') with the reason behind a 'Why?' expander. Alternatively, make the whole screen one vertical scroll in which the picture has a fixed height (max(360 dp, 60% of the screen)). Add a semantics or screenshot test at w360dp-h740dp with the paused card that asserts the 'List' button is fully displayed.

### D3B-2 (major) Names disappear from the picture at modest team sizes; 'Researcher' and 'Reviewer' then both read 'R', told apart only by colour
- **package:** WP9
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/team/TeamLayout.kt arrange(): labels are tried only with `maxGrowth = 1f` (line ~70), then the layout falls back to `labels = false` with growth up to 4x
- **problem:** As soon as the labels do not fit the visible height without growing the picture, every name is dropped, although the picture is allowed to grow 4x and pan once names are gone. With 12 agents the picture is only initials: H1…H7, two 'R' avatars (Researcher and Reviewer from the starter team) and an 'A'. The owner cannot tell who is who without tapping each one, and the two 'R's differ only by colour, which breaks 'never colour alone'. At 30 agents (the cap) it becomes a field of identical 'H1'/'H2' circles.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp9_x_team_12_agents.png (recorded for this review: 12 agents, no names), p6_wp9_team_30_agents.png, p6_wp9_x_team_30_agents_360.png, p6_wp9_x_team_31_agents.png
- **fix:** Try labelled layouts with growth first (for example up to 2.5x the height: the picture already pans) before dropping names. When names must go, show them again from a zoom of 1.5x (labels scale with the viewport). Make avatar text unique within one picture, for example 'Re'/'Rv' or the first letters of two words, so no two members share the same text. Add a TeamLayoutTest case that 12 agents at 411x700 dp keep their labels, and that no two nodes share avatar text.

### D3B-3 (major) Share screen: what leaves the phone is below the fold, generic, and never tied to the chosen place
- **package:** WP16
- **where:** app/src/main/java/com/aicontrolcenter/app/share/ShareScreen.kt Form(): order WhatWasShared → AS_DATA → title → WhereToSave → PrivacyAndCost(STORING [+ LOCAL_ONLY_ON]); SaveArea pinned at the bottom (pinned = fontScale < 1.5); ShareViewModel.kt ShareTexts.STORING
- **problem:** At normal font size Save is pinned and enabled as soon as a place is picked, but the privacy line ('Storing is free. Only what agents recall is sent to a model. A cloud model would receive this text…') sits under the picker, below the fold. The owner can save shared bank, train and message text without ever seeing it. When the owner does see it, it is conditional and abstract ('would'): it never says whether the agents who can read the chosen space (for example 'Researcher and Writer can read it') use a cloud model. With local-only on, the first sentence still says a cloud model would receive the text, and the local-only line comes after it, also off-screen. The local-only picture is byte-identical to the normal one.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp16_share_text_dark_2_picked.png (Save enabled, privacy line not on screen); p6_wp16_share_text_dark_3_bottom.png; p6_wp16_share_local_only.png (identical to _2_picked, md5); p6_wp16_x_share_local_only_bottom.png (local-only line still off-screen after scrolling to STORING)
- **fix:** Put the consequence for the chosen place into the pinned save line, computed from the directory and RegistryLabels locality (read only). For example: 'Saves 1 memory in “Screen notes”. Researcher and Writer can recall it; Researcher's model is in the cloud (xAI), so what it recalls from this is sent there.' For 'No agent can read it yet', say 'Stays on this phone until you let an agent read it.' Move STORING above 'Where to save it'. In local-only mode, replace the cloud sentence with LOCAL_ONLY_ON instead of adding a second line. Pin the exact text with ShareScreenTest.

### D3B-4 (minor) Several review pictures do not show the state they are named after, and one changes between runs
- **package:** WP10b, WP10c, WP16, WP9 (tests)
- **where:** app/src/test/java/com/aicontrolcenter/app/screenshots/WP10bScreenshotTest.kt (heading(...).performScrollTo() puts only the section header at the bottom; recoveryKeyDamaged's second shot); RoutinesScreenshotTest.kt editorNew, editor360LargeFont, pickUpList (scrollTo on already partly visible nodes does not move); share/ShareScreenshotTest.kt localOnly; TeamScreenshotTest.kt linkSheet / youSheet (fixture turn limit of 1 s)
- **problem:** Some states were never reviewed by their builders. settings_background_clear_light, settings_background_360_font2 and settings_background_wide show the Questions section, with only the 'WORKING IN THE BACKGROUND' header at the bottom. settings_backup and settings_backup_360 show the limits card. settings_appearance shows Backup and never Calm. These pairs are byte-identical: recovery_1 and recovery_2_start_over (the Start over button never pictured); editor_new_1 and editor_new_2_when; editor_360_font2_2_when and _3_time; pickup_1_list, pickup_2_stopped and pickup_3_how; share_local_only and share_text_dark_2_picked. team_sheet_link differs between the builder's run ('Answer from Researcher.') and the merged run ('Could not answer: Stopped: the turn reached its time limit of 1 second.'), because the fixture's 1-second turn limit races the test. The 12/30-agent pictures also carry a hard-coded summary ('2 working…') that disagrees with the picture.
- **evidence:** md5 duplicates in /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/ (pairs listed above); p6_wp9_team_sheet_link.png versus /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/wp9-shots/p6_wp9_team_sheet_link.png; TeamScreenshotTest.stateOf hard-codes `summary = …"2 working, 0 waiting for you, 1 waiting for a teammate"`. The missing states were recorded for this review as p6_wp10b_x_*.png, p6_wp10c_x_*.png and p6_wp16_x_*.png in the same folder.
- **fix:** Scroll to the section's last control ("Check again", "Restore from a backup", the Calm switch, the "Start over" button, "Switch on again", "How pick-up works") or use SemanticsActions.ScrollBy. Assert in each test that consecutive shots differ (compare hashes). Give the Team fixture a long turn limit or a fake clock so the link sheet is deterministic. Compute the fixture summary with TeamWords.summary(graph counts) instead of a literal.

### D3B-5 (minor) Team sheets cut message previews mid-word with no '…' ('→ Anal')
- **package:** WP9
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/team/TeamViewModel.kt:402 `preview = header.preview` (core MessageHeader.preview is the first 120 code points, no ellipsis); TeamSheets.kt:226 MessageHeaderRow
- **problem:** The 'You' sheet shows the Director's plan as '2. Compare the three best apps → Anal'. The stored preview stops at 120 characters mid-word, and nothing marks it as cut, so it reads as a finished (and odd) message. The same happens in the member sheet's 'Latest messages'.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp9_team_sheet_you.png, p6_wp9_x_team_sheet_you_360.png
- **fix:** In TeamViewModel.row, when the preview's code-point length equals MessageHeader.PREVIEW_MAX_CHARS, cut back to the last space and append '…'. Pin this in TeamViewModelTest. Optionally make the row open the link sheet that shows the full text.

### D3B-6 (minor) The summary always reads out zeros ('0 working, 0 waiting for you, 0 waiting for a teammate, 2 paused')
- **package:** WP9
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/team/TeamWords.kt summary() (lines ~37-43): the first three parts are always added
- **problem:** With a paused objective the owner and TalkBack (it is the live region) hear three zeros before the one fact that matters. With nothing running the whole line is zeros.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp9_team_paused_light_360.png (the fixture uses the same wording the function produces)
- **fix:** List only the non-zero parts, 'waiting for you' first. When all parts are zero, say 'Nobody is working right now.' Update TeamGraphBuilderTest and TeamViewModelTest.

### D3B-7 (minor) The Team view and the Dashboard card name the same states differently, and their lines mean different things
- **package:** WP9
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/team/TeamGraph.kt:62 `IDLE("Free", …)` and the legend's 'Cannot run (it says why)'; ui/dashboard/NetworkCard.kt:160-163 ('Ready', 'No model yet', 'Unavailable', 'Disabled'); ui/components/RegistryLabels.kt:38
- **problem:** An idle agent is 'Ready' on the Dashboard card, the Agents list and the ecosystem card, but 'Free' one tap later in the Team view. On the card a solid line means 'both can run' and a dashed line 'one cannot'. In the Team view a solid line means 'work handed out' and a dashed line 'questions'. The 'Open Team view' button connects two pictures whose legends contradict each other.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp9_dashboard_network_card.png versus p6_wp9_team_legend.png
- **fix:** Use 'Ready' for IDLE in the Team view. In the card's legend, say 'Lines here show who reports to the Director; the Team view shows who sent what to whom', or draw the card's availability with the avatar badge only (no dashes).

### D3B-8 (minor) The zoom buttons crowd the 'You' avatar at 360 dp and float over the picture
- **package:** WP9
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/team/TeamPicture.kt ZoomButtons (aligned TopEnd over the picture) and TeamLayout (no band reserved for them)
- **problem:** At 360 dp the zoom-out button sits about 5 dp from the 'You' avatar and its badge. The 48 dp targets almost touch, and after a pan, members can slide under the buttons.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp9_team_objective_360.png, p6_wp9_x_team_30_agents_360.png
- **fix:** Reserve a 56 dp band at the top of TeamLayout (start 'You' below it), or move the zoom row to the bottom end on a small surface. Keep TeamLayoutTest's 52 dp separation rule for the buttons too.

### D3B-9 (minor) The 'Time to answer' chips show the choice by fill only and read as checkboxes; the routine editor's chips have a tick and the radio role
- **package:** WP10b (also WP10a's Usage history, part 1)
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/settings/QuestionTimeoutSection.kt:56-62 (FilterChip without leadingIcon or Role.RadioButton, FlowRow without selectableGroup); compare ui/routines/RoutineEditorScreen.kt:409-423 ChoiceChip; same pattern in UsageHistorySection.kt:61 and SettingsScreen.kt:274
- **problem:** '20 minutes (usual)' differs from its neighbours only by a pale fill: a pale blue in the light theme. TalkBack hears a checkbox, not one choice out of seven. The routine editor, a sibling screen in the same wave, uses ticks and radio semantics, so the app has two styles for the same control.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10b_settings_background_clear_light.png and p6_wp10b_settings_questions_360_font2.png versus p6_wp10c_editor_new_3_while.png
- **fix:** Move WP10c's ChoiceChip into ui/components (WP8's file) and use it here and for the retention chips, with Modifier.selectableGroup() on the FlowRow. Add a semantics test that the chosen chip has Role.RadioButton and is selected.

### D3B-10 (minor) Restore: 'This backup cannot be restored now' comes at the very end of a long summary and offers no next step
- **package:** WP10b
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/backup/BackupRestoreScreen.kt:201 (refusal banner placed after the summary sections)
- **problem:** An owner who tries a restore on a phone in use reads the counts, 'Settings that change on this phone', 'Kept back' and 'Never in a backup'. Only then, at the bottom, do they learn none of it can happen. Nothing says what to do instead.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10b_x_restore_refused_top.png and p6_wp10b_x_restore_refused_bottom.png (recorded for this review with BackupProblem.NotEmpty)
- **fix:** Show the refusal directly under 'Open the backup', before the summary, with what to do. For example: 'This phone already has your own agents, chats or memories. A backup goes only into a fresh app: install the app on the new phone and restore there first.' Fold the summary under 'See what the file holds'.

### D3B-11 (minor) Restore summary: the current Director can read 'Now: an agent not in this backup'
- **package:** WP10b
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/backup/BackupViewModels.kt:428-429 (falls back to change.from = RestoreSettings.UNKNOWN_AGENT when the directory has no name)
- **problem:** UNKNOWN_AGENT describes an agent of the backup. When it appears on the 'Now' side it describes the phone's own Director as something 'not in this backup', which is confusing on the screen where the owner decides about a restore.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10b_restore_4_summary_changes.png
- **fix:** For the 'Now' side, fall back to 'the Director on this phone now'. Keep UNKNOWN_AGENT for the 'After the restore' side only. Add a case to BackupViewModelsTest.

### D3B-12 (minor) The restore result says the app 'starts again now' but waits for a tap; the save result does not name the file
- **package:** WP10b
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/backup/BackupRestoreScreen.kt:311; BackupSaveScreen.kt:277-278
- **problem:** 'The app starts again now so that it uses them.' sits above a 'Start the app again' button, and Back also restarts the app without saying so. 'Backup saved — The file holds 6 agents, 2 memories (2.0 KB)' does not say what the file is called or where it went, so the owner may not find it later.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10b_restore_5_restored.png, p6_wp10b_save_4_saved.png
- **fix:** Restore: 'Tap Start the app again so that it uses them. Leaving this screen also starts it again.' Save: add the document's display name (already read for the picker) and 'in the place you chose', e.g. 'Saved as “AI Control Center backup 26 Nov 2026.aiccbackup”.'

### D3B-13 (minor) Own passphrase: errors in red while the owner is still typing, and the rule is said twice
- **package:** WP10b
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/backup/BackupSaveScreen.kt (own-passphrase fields; problemForSave shown per key, mismatch shown as soon as the second field has text)
- **problem:** After 6 of 10 characters in the second field it already says 'The two passphrases are not the same.' in red. The first field's error repeats the card's lead sentence with the clauses swapped. Errors that appear before the owner has finished typing feel like scolding on a screen that already asks for care.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10b_save_2_own_with_reason.png
- **fix:** Show the mismatch only once the second field is at least as long as the first, or loses focus. Show the first field's problem when it loses focus or when Save is attempted. Make the error specific rather than a repeat of the rule, e.g. 'Too short: 10 of 16 characters' or 'These look like a pattern'.

### D3B-14 (minor) The Start over confirmation word is typed blind (dots)
- **package:** WP10b
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/recovery/RecoveryScreen.kt StartOverCard: SecretTextField(state = viewModel.confirmWord, …)
- **problem:** 'START OVER' is not a secret, but the field masks it, so the owner cannot see a typo and only has '10 characters typed' to go on. The window is already FLAG_SECURE and the keyboard does not learn, so masking adds no safety on the screen where a nervous owner must get it right.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10b_x_recovery_start_over_button.png, p6_wp10b_recovery_1_key_damaged.png
- **fix:** Show the typed characters: keep no suggestions, no learning and FLAG_SECURE, but drop the password transformation, or add a show/hide eye. Keep the count line only if masking stays.

### D3B-15 (minor) Two identical 'Let the app run in the background' buttons in one card
- **package:** WP10b
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/settings/BackgroundSection.kt (Battery saving and How often Android lets it run both offer BackgroundScreens.batteryExemption())
- **problem:** When both items are held back, the card shows the same button twice. The owner wonders which one to tap, or taps both.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10b_settings_background_2_switches.png, p6_wp10b_x_background_360_font2_c.png
- **fix:** When both need the same fix, show the button once under 'How often Android lets it run', and under 'Battery saving' write 'The button below fixes this too.'

### D3B-16 (minor) With routines switched off in Settings, every routine still shows a green 'On'
- **package:** WP10c
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/routines/RoutinesScreen.kt RoutineCard status chip (RoutinesViewModel row status)
- **problem:** 'Morning news — On (green check) — Does not run while routines are off in Settings.' The green chip says it will run, the line under it says it will not.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10c_routines_list_off.png
- **fix:** While the master switch is off, show the chip in the neutral/disabled tone with 'On, waits for Settings' (or 'Will not run'). Keep green only for routines that will really start.

### D3B-17 (minor) A routine that may use other apps' screens looks like one that may not
- **package:** WP10c
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/routines/RoutinesScreen.kt RoutineCard ('No screens' / 'May use other apps' screens' in the same grey bodySmall)
- **problem:** The most consequential property of an unattended routine is shown in the same quiet grey as 'No screens'.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10c_routines_1_list.png, p6_wp10c_routines_wide.png
- **fix:** Give 'May use other apps' screens' the screen-control icon and the attention tone, with the short reason: 'May use other apps' screens (asks before taps that are hard to undo)'. Keep 'No screens' quiet.

### D3B-18 (minor) Pick-up editor: the overlap reason does not name the other folder, and the banner sends the owner to Tools › Folders next to a one-tap button
- **package:** WP10c (sentence in core PickUpSafety, WP8)
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/pickup/PickUpRuleEditorScreen.kt (shows PickUpSafety.folderProblem as it is); PickUpRulesScreen.kt Stopped card (only 'Switch on again', 'Change')
- **problem:** 'This folder is inside another shared folder agents may change, or holds one … make that folder read-only too' never says which folder, so the owner must guess. For a writable folder the banner says 'in Tools › Folders, switch on "Read-only"' while a 'Make it read-only' button sits right under it. The Stopped rule on the list says 'Make the folder read-only in Tools › Folders' but offers no button.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10c_pickup_editor_2_overlaps.png, p6_wp10c_pickup_editor_1_not_read_only.png, p6_wp10c_x_pickup_list_end.png
- **fix:** Name the other folder in the overlap sentence ('“Letters” is inside “Documents”, which agents may change'). This needs the name in core's PickUpSafety (handoff to WP8). Where the button is offered, word the banner 'Tap Make it read-only below.' Offer 'Make it read-only' on the Stopped rule's card as well.

### D3B-19 (minor) Pick-up editor does not say who can read each space, though the share screen does
- **package:** WP10c
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/pickup/PickUpRuleEditorScreen.kt space radio rows (name only); compare share/ShareScreen.kt target rows
- **problem:** Pick-up keeps importing files by itself into the chosen space, and every agent that can read that space can recall them into its model. The rows show only 'Team knowledge' and 'Inbox notes'. The share screen in the same wave shows 'Researcher and Writer can read it.' / 'No agent can read it yet.', which is the 'do not force all agents to share all memories' information the owner needs here too.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10c_pickup_editor_3_space.png versus p6_wp16_share_text_dark_3_bottom.png
- **fix:** Add the same reader line under each space. Give PickUpRuleEditorViewModel a defaulted, read-only `directory: RegistryDirectory?` (as WP16 did) and share the wording helper with ShareViewModel.

### D3B-20 (minor) A paused routine says 'Switch it on again when the problem is fixed' without saying what the problem was
- **package:** WP10c
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/routines/RoutineEditorScreen.kt (paused banner)
- **problem:** The cause is only in the run history at the bottom of a long editor. The big 'Switch it on again' button invites a retry that will fail the same way.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10c_editor_paused.png
- **fix:** Put the last failed run's note in the banner ('Last time: xAI is not answering.') and add a 'See recent runs' text button that scrolls to the history.

### D3B-21 (minor) Straight quotes in routine and pick-up texts; the rest of the wave uses curly quotes
- **package:** WP10c (WaitingRoutinesBanner is WP17's file, extended by WP10c)
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/routines/RoutinesScreen.kt ('Alarms and reminders', 'Tap to start'); routines/WaitingRoutinesBanner.kt ('Routine "Morning news" is waiting…', 'Start "Morning news" now?', '"Not now"'); core PickUpSafety ('"Read-only"')
- **problem:** The app decided on curly quotes (D3A-12(e)), and the Team view, the share screen and the pick-up list use them. These owner sentences show straight quotes.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10c_routines_1_list.png, p6_wp10c_waiting_step.png, p6_wp10c_waiting_step_question.png, p6_wp10c_pickup_editor_1_not_read_only.png
- **fix:** Use “ ” in these strings and update the tests that pin them (WaitingRoutinesBannerTest, RoutineScreensTest, core PickUp tests via WP8).

### D3B-22 (minor) 'Every few hours' never says how many runs a day that is, or that each run costs money
- **package:** WP10c
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/routines/RoutineEditorScreen.kt schedule summary ('At 08:00, 11:00, 14:00, 17:00, 20:00 and 23:00.'); RoutinesSection confirm dialog
- **problem:** Cost is a first-class goal: the owner spent US$1.50 on Grok without noticing. An every-hour routine starts up to 16 unattended objectives a day. The editor lists the times but never says 'six runs a day, each using your models'. The 'Switch routines on?' dialog says 'count towards today's limits' but not that it costs money.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10c_editor_screens_on.png, p6_wp10c_settings_confirm_routines.png
- **fix:** Under the times, add 'That is 6 runs a day. Each run uses your models and can cost money; today's limits (now US$1.00) stop them when reached.' using the limit from TodayUsage/DailyLimits. In the dialog, add 'Each run can cost money.'

### D3B-23 (minor) A preview cut by lines ends 'works.…' and loses its closing quote
- **package:** WP16
- **where:** app/src/main/java/com/aicontrolcenter/app/share/ShareScreen.kt:243-266 (quotes added around item.preview; Text maxLines with Ellipsis)
- **problem:** When maxLines cuts the text, Compose's ellipsis lands after the full stop ('Saturday still works.…') and the opening “ is never closed. This is the screen where the owner checks exactly what another app handed over.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp16_share_text_dark_1_top.png, p6_wp16_share_saving.png
- **fix:** Draw the quote marks outside the clipped Text (a leading and a trailing glyph in a Row). When cut, trim trailing punctuation before the '…'.

### D3B-24 (minor) Team view shows framework jargon to the owner ('the turn reached its time limit'; 'Working · Waiting for the model')
- **package:** WP9 (wording source is the framework, WP8)
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/team/TeamWords.kt stepOf / message rows (text shown as stored)
- **problem:** The link and 'You' sheets show 'Could not answer: Stopped: the turn reached its time limit of 1 second.' and 'Limit reached: …: Stopped: the turn reached…'. 'Turn' is jargon. A member reads 'Working' with 'Waiting for the model' under it, which sounds contradictory.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp9_team_sheet_link.png (merged run), p6_wp9_x_team_sheet_you_360.png, p6_wp9_team_list_1.png
- **fix:** Ask WP8 to reword the framework's time-limit status ('Stopped: it took longer than its time limit (15 min).'). In TeamWords.stepOf, map 'Waiting for the model' to 'Waiting for its model's answer'.

### D3B-25 (minor) 'API keys' on the backup screens, 'Provider keys' in Settings
- **package:** WP10b
- **where:** data BackupTexts.WHAT_IS_NOT (shown by BackupSaveScreen and BackupRestoreScreen) versus ui/settings/BackupSection.kt
- **problem:** The same thing has two names a few taps apart, and 'API keys' is jargon for this owner.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10b_save_1_generated.png ('Not in the backup: API keys, …') versus p6_wp10b_x_backup_section.png ('Provider keys are not in the backup')
- **fix:** Use 'provider keys (such as your xAI key)' everywhere. The text is in :data, so this is a handoff to WP8; update the pinned tests.

### D3B-26 (minor) Restore step 2: a wrong passphrase is titled 'Nothing was restored', and 'Open the backup' stays primary after opening
- **package:** WP10b
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/backup/BackupRestoreScreen.kt:198 (failure banner title) and the step-2 Button
- **problem:** The owner only tapped 'Open the backup', so 'Nothing was restored' answers a question they did not ask. Once the file is open, the filled 'Open the backup' still competes with 'Restore this backup' further down.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10b_restore_2_wrong_passphrase.png, p6_wp10b_restore_3_summary_top.png
- **fix:** Title the opening failures 'The backup did not open'. After a successful open, show 'Opened' with a tick and make the button a TextButton 'Open again'.

### D3B-27 (minor) With two rings, lines to the outer ring run straight through inner-ring avatars
- **package:** WP9
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/team/TeamLayout.kt placeRing for outer/inner (no angular stagger) and LinkLine straight segments
- **problem:** Director→Researcher (outer ring) passes over an inner-ring avatar (H1). It looks as if the line goes to H1, or that H1 links on to Researcher. The memory line to the space crosses another avatar the same way.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp9_team_30_agents.png, p6_wp9_x_team_12_agents.png (the Director→Writer memory line over H5's area)
- **fix:** Offset the inner ring by half an angular step so radial lines pass between inner avatars, or bow a line when its straight path comes within 30 dp of another avatar. Add a TeamLayoutTest check that no straight line crosses a non-endpoint avatar's 24 dp circle.

### D3B-28 (minor) Two sibling lists break differently at font 2.0
- **package:** WP10c
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/pickup/PickUpRulesScreen.kt header row versus ui/routines/RoutinesScreen.kt
- **problem:** At 360 dp and font 2.0, Routines moves 'New routine' under the header, but Folder pick-up keeps 'New rule' beside it and squeezes the header to 'YOUR / RULES' on two lines.
- **evidence:** /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/p6_wp10c_pickup_360_font2.png versus p6_wp10c_routines_360_font2.png
- **fix:** Use the Routines breakpoint, with the button below the header at font scale ≥ 1.5 or width < 400 dp, in one shared helper for both screens.

**Checked and held:** Method. I made a throwaway worktree of 88060fa at /home/user/p6-design3b (with local.properties). I recorded TeamScreenshotTest, WP10bScreenshotTest, RoutinesScreenshotTest and ShareScreenshotTest with ./tools/gw --offline :app:recordRoborazziDebug: 117 pictures, BUILD SUCCESSFUL. I compared them with the builders' copies: 27 differ after the merge (Recovery's new closing line, the "›" sentences, US$, the timing-dependent link sheet). I looked at every picture of WP9, WP10b, WP10c and WP16, and glanced at the part-1 pictures in design3a-fixed-shots for consistency.

Extra pictures. Many states were missing or duplicated, so I added review-only shots in the throwaway worktree (never committed) and recorded 24 more:
- the background section all clear, at 360 dp font 2.0 and wide
- Appearance/Calm and the Backup section
- the Save passphrase at 360 dp font 2.0
- the Start over button
- a refused restore
- the end of the routine editor (new and existing), a scrolled 360 dp font 2.0 editor, and the end of the Routines list
- the scrolled pick-up list
- the local-only share bottom
- Team view with 12, 30 (at 360 dp) and 31 agents, and the You sheet at 360 dp

One extra (an empty Team view) could not be reached: the Dashboard has no "Open Team view" without a running objective. All 141 pictures (117 merged plus 24 extra) are kept in /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/design3b-shots/. The worktree is removed. /home/user/rune-companion is untouched and clean at 88060fa.

What looked right:
- WP9 Team view with one objective at 411 dp, dark and light. The layout reads well: You at the top, the Director in the centre, a ring, spaces at the bottom; line signs at 48 dp with glyphs. The legend says everything in words; status badges have icons; a waiting-for-you ring has both a thicker ring and a warning badge.
- WP9 list mode. It opens first at font 1.5 and 2.0 and reads well at 360 dp font 2.0. The member, link and You sheets are clear, and RequestCard routes answers to their own screens, so nothing in a sheet answers anything.
- WP9 motion and code. The Calm picture has no dots; the dots-in-flight picture is fine. The picture is a traversal group with ordered members, custom actions, sign descriptions and zoom button descriptions; the live region is throttled. The Dashboard card has the new "Open Team view" (48 dp) and the waiting-for-you and waiting-for-a-teammate legend.
- WP10b background section. Each item has an icon, a state in words and a one-tap fix that opens Android's screen. "Android did not say." stays neutral, and the all-clear state is calm and green.
- WP10b questions and Calm. The questions switch text is good ("never a permission"). Calm is clearly worded.
- WP10b Save. The generated passphrase is large, monospaced and grouped; it wraps well at 360 dp font 2.0. The tick gates Save; the key step under Calm shows a clock with "Working on it…" and Cancel. The keep-the-app-open banner is good.
- WP10b Restore. Steps 1 and 2 are clear. The summary lists setting changes, kept-back values, always-off switches and "Never in a backup". It asks once more before restoring, and the result keeps its notes until "Start the app again".
- WP10b Recovery. A plain reason per DatabaseState and a sensible order (Try again, Close and try later, then Start over). "Only if trying again does not help." Set-aside, reset and kept are stated in words. The Start over button is a red outlined button, enabled only after the word. FLAG_SECURE is set by SecureWindow on Save, Restore and Recovery and cleared on dispose.
- WP10c Routines. The master switches confirm before switching on and explain that work runs while nobody looks. "Allow screens" uses the attention colour with a second confirmation and the screen-on warning. The waiting step is clear, with Start then "Start now" naming the objective and saying whether screens may be used. The time picker and day chips work at 360 dp font 2.0. The run history speaks plain words, with Missed and Skipped icons. Delete is red and outlined.
- WP10c pick-up. Folders are radio rows with read-only state words; Make it read-only is offered; files left out are explained one by one; "How pick-up works" is good.
- WP16 share. The size is in words and KB; nothing is preselected and Save stays off until a place is picked. Each space says who can read it. The "kept as text another app shared, never as your own words" line is good. The bottom bar line says what Save will do; saving shows progress with "Stop saving takes back what was saved so far". The saved, no-space and left-out banners are good, and at large fonts Save comes at the end of the list.
- Shared rules. Everything is Material 3 in the app's own components (ScreenScaffold, AppCard, InfoBanner, SectionHeader, SwitchRow). No "(test)" label appears on these screens, and every control here works. Touch targets are ≥ 48 dp in the code (heightIn(min = 48.dp) on buttons and chips, Material min interactive size). Dark and light contrast is fine on every picture.

**Not checked:** Not checked, because no device or emulator was used in this run:
- TalkBack reading order and custom actions on a real device, beyond reading the semantics code.
- FLAG_SECURE actually blanking the recents picture and screenshots.
- Calm with Android's animator duration scale at 0, and frame timing with 8 dots in flight.
- StrictMode on the Dashboard.
- The razr's real width at font 1.3.
- The share sheet's label in the real launcher.
- Android's document pickers, the exact-alarm settings page, and the battery-exemption dialog.

All of these are already listed by the builders in /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/phase6/device-tests-wave3.md (WP9, WP10b, WP10c and WP16 sections, plus §10).

No picture could be produced for these states:
- The Team view with no objective: there is no route without a running objective.
- The amber "Questions for you" line to You, because the fixture raises the question without the QUESTION message production sends.
- Restore busy ("An objective is running").
- Save failure.
- Recovery after Start over is refused.
- The routines "Alarms and reminders allowed" state.
- Pick-up with folder pick-up switched off but rules present.
- The wide Team view sheets.

Landscape on a phone (as opposed to the 900 dp wide window) was not recorded.

I did not re-run the unit test suite: this was a design pass on unchanged code, and the integrator's run at 88060fa reports 3,964 tests with 0 failed.

The adversarial review of all of wave 3 is a later step of this workflow and was not part of this design pass.

## review3:security

### SEC-W3-1 (critical) Stopping or a refused backup save deletes the existing file the owner chose to replace
- **package:** WP10b
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/backup/BackupSaveScreen.kt:92 (CreateDocument picker, MIME application/octet-stream) and :314-321 (discardDocument calls DocumentsContract.deleteDocument on the picked document); app/src/main/java/com/aicontrolcenter/app/ui/backup/BackupViewModels.kt, BackupSaveViewModel.save (the CancellationException branch and the BackupResult.Failed branch both call discardQuietly(discard))
- **problem:** The save screen assumes Android's picker always makes a new, empty document, so it deletes the picked document whenever a save does not finish. Android's own file picker (DocumentsUI) also lets the owner tap an existing file in save mode. It then asks "Overwrite …?" and hands back that file's address. Every earlier .aiccbackup file can be picked this way, because its type is application/octet-stream. Any other file of that type can be picked too, such as another app's backup or a password database. RoomOwnerBackup works out the key before it opens the file. So when the owner taps Stop during "Locking the backup" (which takes seconds to minutes on a phone), the old file is untouched, but the screen then deletes it. The same happens when the backup refuses before writing anything: a restore is running (Busy), the backup is too large, or the stream cannot be opened. The screen then says "Stopped. Nothing was saved." or the reason for the refusal. It never says that the owner's earlier backup is now gone. This breaks the owner rule "Require confirmation before deleting important files", and the file lost is the one the owner most needs when something goes wrong.
- **evidence:** Probe app/src/test/.../ui/backup/SecProbeBackupOverwriteTest.kt (copy in scratchpad/phase6/review3-security-probes/), run with ./tools/gw --offline :app:testDebugUnitTest --tests '*SecProbe*' --rerun at 88060fa: the real BackupSaveScreen, with PickerResults returning Uri.fromFile(an existing file). Output: "PROBE before Stop: exists=true content='THE OWNER'S PREVIOUS BACKUP, 1 MB OF ENCRYPTED DATA'" then "PROBE after Stop: exists=false screen says 'Stopped. Nothing was saved.'". With the real RoomOwnerBackup (wp10bGraph) while the maintenance gate is busy: "result=Failed(problem=Busy(what=The app is restoring a backup.)) opened=false". The file was never opened, yet BackupSaveViewModel deletes on every Failed result; the existing test BackupViewModelsTest 'a save that fails says why and removes the file the picker made' pins that. The picker handing back an existing document comes from AOSP DocumentsUI's create mode (PickActivity.onDocumentPicked sets the replace target, then OverwriteConfirmFragment). It is not checked on a device yet: device-tests-wave3.md section 11 has the manual step.
- **fix:** Delete only a document this save created and that holds nothing but this save's bytes. When the picker returns, read OpenableColumns.SIZE (and, where offered, the last-modified time) off the main thread, and remember whether the document is new and empty. Call discard only for such a document, and only if the save opened it. Otherwise leave the file as it is. If a save fails after it has started writing into a file that already existed, say so plainly (for example: "The file you chose to replace was emptied; your earlier backup in it is gone."); never say "Nothing was saved" there. Consider an app-specific MIME type so that the picker does not offer unrelated octet-stream files for replacing. Tests: a pre-existing picked file stays byte-for-byte the same after Stop during the key step and after a Busy refusal; a new empty document is still removed.

### SEC-W3-2 (major) One share can carry any number of texts and more than 200,000 characters; the share screen composes every one at once
- **package:** WP16
- **where:** app/src/main/java/com/aicontrolcenter/app/share/ShareIntake.kt, requestOf (the EXTRA_TEXT list of ACTION_SEND_MULTIPLE is taken whole; only streams are capped at MAX_FILES) and read (MAX_TEXT_CHARS is checked per text, not per share); app/src/main/java/com/aicontrolcenter/app/share/ShareScreen.kt:235 (state.items.forEach { SharedItemCard } inside a verticalScroll Column)
- **problem:** Rule 34 caps one share at 200,000 characters and 10 files. ShareReceiverActivity is exported, and any app can send it ACTION_SEND_MULTIPLE with a CharSequence list in EXTRA_TEXT. Each entry becomes its own item, and the 200,000 cap applies to each entry, not to the share. Thousands of short texts fit easily inside Android's limit for one intent. The screen then builds one card per item on the main thread, with no lazy list, and a single tap on Save writes one memory (and one log line) per item. Nothing is stored before Save, so this fails safe for storage. But the rule-34 cap is broken by crafted input on the app's one new exported surface, and a large share can stall the share screen.
- **evidence:** Probes copied to scratchpad/phase6/review3-security-probes/, run at 88060fa. SecProbeShareCapsTest: "PROBE 5,000 texts -> items=5000 leftOut=[]" and "PROBE 3 x 150,000 chars -> items=3 totalChars=450000 leftOut=[]". SecProbeShareScreenCountTest (ShareContent at 360 dp): "PROBE 1,500 shared texts: cards composed without scrolling=1500, last one present=1".
- **fix:** Cap the share as a whole in ShareIntake.read. Count characters across all shared texts (at most 200,000 in total) and the number of items (for example at most 10 texts and 10 files). Leave the rest out, each with its plain sentence, as is already done for files. Show the items in a LazyColumn, or show the first few cards with "and N more". Tests: SEND_MULTIPLE with 11 or more texts, and with 2 texts of 150,000 characters each; a count-based Compose test showing that offscreen cards are not composed.

### SEC-W3-3 (minor) "What happened" shows the call's own output (agent-influenced) as the app's sentence, and cuts at the first dash
- **package:** WP11a
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/approvals/ApprovalPresentation.kt, whatHappened (call.detail.substringAfter(" — ") shown as "It failed: …" / "Not run: …" in an InfoBanner); the detail is written by framework/tools/ToolExecutor.kt record() as "<assessment summary> — <failure output>"
- **problem:** The doc comment says that nothing shown there is text the owner has not already seen. That is not so. For a FAILED call, the text after the first " — " is the tool's own output, for example the error output of a shell command the agent wrote, or text the agent put in an argument. It is shown unquoted, as the app's own sentence, inside the app's banner under "What happened". If the assessment summary itself contains " — ", which can come from an agent's argument, the cut lands inside the summary. Hidden characters are marked, so this is wording that can mislead, not an action.
- **evidence:** Probe SecProbeWhatHappenedTest (copy in scratchpad/phase6/review3-security-probes/), run at 88060fa: "PROBE What happened: 'It failed: exit 1. Done: nothing was deleted, your files are safe.' tone=UNAVAILABLE" and "PROBE What happened (dash in the agent's argument): 'It failed: Approved by the owner — timed out'".
- **fix:** Show the app's verdict on its own ("It failed." / "Not run."). Put the tool's words under it as quoted tool text, with PreviewTextView and a line such as "What the tool reported, as it said it", and cut it short. Take the failure text by removing the known prefix (request.summary + " — ") rather than splitting at the first dash, or have the executor log the failure text in its own data field (a framework change for WP8 or wave 4).

**Checked and held:** All ran at 88060fa in the throwaway worktree /home/user/p6-review3-security, which I removed when done. No device was used.

(1) Owner-only ports against agent-facing code.
- The app's OperatorDependencyTest and ProductionGraphDependencyTest pass: 12 tests, 0 failed.
- The owner-only list in AgentFacingWalk.kt contains the new wave-3 ports: WaitingRoutines, RoutineRunner, PickUpRunner, DefaultOwnerRoutines, DefaultOwnerPickUpRules, PickUpImports, SafeStartControl, OwnerRoutines, OwnerPickUpRules, OwnerBackup, OwnerRecovery, OwnerConversation, OwnerModelPrices, OwnerUsage, SettingsRepository.
- The screens hold owner ports only in their own view models. ShareViewModel holds only SettingsView (read only), MemoryService used as the owner, Workspace, RegistryDirectory and ActivityRecorder.

(2) Question screen (rules 13 and 32).
- QuestionViewModel sends only through graph.ownerQuestions, which is DefaultOwnerRequests wired with VaultKeyCheck (ModelsWiring.kt:124, OperatorWiring.kt:259).
- A key is refused even after "Send it anyway"; matchesStoredKey runs after the code check.
- "Send it anyway" is offered and accepted only for the exact text the code warning was given for (codeText == typed.trim()).
- Suggested answers are TextButtons that only fill the field. choicesFor(QUESTION) is empty, and the Approval route hands a question to QuestionRoute and pops itself.
- Agent text is quoted, with visible() and inWords(); the screen has ObscuredTouchGuard, and its buttons are marked sensitive.
- The answer goes only to the route's own request id.

(3) Notes (rule 24). The only UI that writes notes is ObjectiveViewModel → OwnerConversation.addNote, with the owner's typed text. The recipient is filtered to tasks of the same objective, and no agent text is ever put into the note field.

(4) Backup and Recovery.
- FLAG_SECURE (SecureWindow) is set on the Save, Restore and Recovery screens, including the Recovery screen shown inside ShareReceiverActivity.
- Passphrases live only in view-model TextFieldStates, which are cleared with their undo history on leave. They are never in rememberSaveable and never logged; wave-3 code adds no Log, println or printStackTrace.
- Start over needs OwnerRecovery.confirms(word).
- Restore is refused while an objective runs or a chat turn is live, and the summary lists every SettingChange and KeptBack the data layer reports.

(5) Routines and pick-up.
- Turning either master switch on needs a guarded ConfirmDialog; turning it off is immediate.
- "May use other apps' screens" needs a guarded confirmation, and Routine.objectiveOptions() maps allowScreenActions to screenActionsAllowed.
- The Tap-to-start screen and the shell's automatic navigation start nothing: a run starts only after Start and a guarded "Start now". The notification only opens the app.
- Pick-up rules go through OwnerPickUpRules, which refuses folders agents may change.

(6) Share receiver.
- The release merged-manifest test pins it to SEND and SEND_MULTIPLE of text/*.
- It accepts only content: addresses, refuses this app's own providers whatever user prefix is given, caps files at 10 of 1 MB each, and checks UTF-8 and the type.
- Items are stored as IMPORT with provenance "Shared from another app", never as the owner's words. Nothing is stored before Save, and Save is guarded against overlays.
- It passes the AppGate and shows Recovery when the data cannot open. That is by design and fails safe (Start over needs the typed word), but the comment in MergedManifestTest saying no other activity opens Recovery from outside is inaccurate.

(7) Limits and prices.
- DailyLimitsViewModel checks every field against DailyLimits ranges, and the money input allows at most 2 decimals.
- Prices are bounded by ModelPrice.check (0 to 10,000 per million tokens). Since D3A-8 a price counts only in US$, and the model editor, Usage screen and Today tile name models that have no price.

(8) Notifications and the shell badge. Wave 3 only renamed the channel. ApprovalsStrip and the badges show counts only.

(9) Existing tests. A targeted run of the Question, Share, Recovery, Backup, MergedManifest, Routine, PickUp, Note, Approval and DailyLimit tests completed 355 tests with 1 failure; that failure was my own probe's timing, since fixed. The 350 existing tests in the run passed.

I added section 11 to scratchpad/phase6/device-tests-wave3.md with the device steps for SEC-W3-1 and SEC-W3-2.

**Not checked:** - No emulator or device was used. Still unchecked on a phone: that DocumentsUI's "Overwrite …?" really returns the existing document (AOSP behaviour, step in device-tests-wave3.md §11), FLAG_SECURE blocking screenshots and Recents, how many texts fit in one SEND_MULTIPLE intent, real ContentResolver grant enforcement for shared addresses (Robolectric enforces none), and lock-screen behaviour of the routine and question notifications.
- I did not run the whole suite or testReleaseUnitTest; I read the merged-manifest test but did not run its release variant.
- Wave-2 framework code that the screens call I only spot-checked, not re-attacked: OwnerServices.answer, AnswerCheck, DataBlock framing, RoomOwnerBackup restore rules, the routine and pick-up runners.
- Not reviewed for security in depth: the Team view's layout and picture code, the Usage and Activity screens' paging and search, and the chat's "Show earlier" and cost line, beyond what they show.
- Not reported because it is outside this lens and I ran no reproduction: some screens show raw exception messages ("…could not be saved: ${failure.message}") in QuestionViewModel, ObjectiveViewModel notes, the model price parts and chat paging. That is jargon the owner may see, not a security problem.

## review3:integration

### INT3-1 (major) Objective and task headers count calls the daily limit refused, and slow-down calls, as "model answers". The privacy line then claims answers came from off the phone and that the log lost them.
- **package:** WP11a (objective and task cost lines, privacy line from the D3A-5 fix); counting rule in data UsageMappers and testkit InMemoryUsage (WP8 shared files)
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/tasks/ObjectiveViewModel.kt:647-667 (costLineOf: calls = rows.sumOf { it.calls }) and :543-549 (egressOf: offPhone.sumOf { it.calls }); ui/tasks/ObjectiveHeader.kt:250-261 (egressWords); ui/tasks/TaskDetailViewModel.kt:211-213 (the same costLineOf); data/src/main/java/com/aicontrolcenter/data/db/mapper/UsageMappers.kt:94-103 and testkit/src/main/kotlin/com/aicontrolcenter/testkit/InMemoryUsage.kt:90 (work row calls + 1 for every sample, including BLOCKED and RATE_LIMITED)
- **problem:** Work rows add 1 to `calls` for every usage sample. That includes the BLOCKED sample the gateway writes when today's limit refuses a call before anything is sent, and a call that ended on "slow down" (429). The header shows this sum as "N model answers". The Today tile, Settings › Daily limits, the New objective line and the limiter count with DailyUse.counted, which leaves both out. So exactly when the owner checks limits, the objective shows more answers than Today. Each Resume tapped while the limit is still reached adds one more phantom answer.

The privacy line uses the same sum. It says "In all, N model answers came from models off this phone; the log no longer holds the rest." That is false twice: those calls never left the phone, and the log holds every entry.
- **evidence:** Probes in /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/review3-integ-probes/ (output in probe-output.txt), run on 88060fa.

(1) ProbeAnswersAgreeTest: teamOnCloud, maxModelCallsPerDay = 2, objective pauses. Output: "today.calls=2 requestsSent=2 objectiveWorkRows=[WorkUsage(... calls=3, inputTokens=20, outputTokens=10 ...)]" and header "So far: 30 tokens · 3 model answers", "2 model calls left this phone, to api.example.test. See the Log. In all, 3 model answers came from models off this phone; the log no longer holds the rest." Assertion failed: Expected <2>, actual <3>.

(2) ProbeResumeAtLimitTest: tap Resume once without raising the limit. The objective pauses again and sends nothing (sentAfter=2). The header now reads "4 model answers … In all, 4 model answers came from models off this phone" while today=2.

(3) A slow-down sample (FAILED/RATE_LIMITED, no tokens) recorded for an objective: Today calls=0, objective line "So far: 0 tokens · 1 model answer".
- **fix:** Count a work row's calls with the limiter's rule: +1 only for OK, CANCELLED and FAILED except RATE_LIMITED, never for BLOCKED. Apply it in UsageMappers.addedTo(item, …) and InMemoryUsage, sharing DailyUse.counted's rule; no schema change. In egressWords, compare against these counted answers. Say "the log no longer holds the rest" only when the objective's log was actually pruned (its start is older than the log's retention). Add a journey: limit 2 → pause → Resume → the header says "2 model answers" and has no "In all" sentence.

### INT3-2 (major) After the usual 30-day log pruning and "Clear usage history", an objective that sent requests to a cloud model says "Nothing has left this phone for this objective."
- **package:** WP11a (privacy line, D3A-5 fix) with WP10a (Settings › Usage history › Clear)
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/tasks/ObjectiveViewModel.kt:543-549 (egressOf); ui/tasks/ObjectiveHeader.kt:250-261 (egressWords, the else branch); ui/settings/UsageHistoryViewModel.kt clearHistory → OwnerUsage.clearHistory, which also deletes work rows (data/.../db/dao/UsageDao.kt:97-104 deleteWorkUpdatedBefore; testkit InMemoryUsage.kt:118-127)
- **problem:** The privacy line falls back to the objective's usage work rows once the activity log no longer holds its calls. The log keeps 30 days by default (activityRetentionDays=30). Retention pruning keeps work rows, but the owner's "Clear usage history" deletes every work row last updated before today. For any objective older than 30 days, both records are then empty. The line then makes a positive privacy claim, "Nothing has left this phone for this objective.", although the objective's requests went to the cloud. That misleads the owner about what left the phone. Nothing on the Clear button or its result says this will happen.
- **evidence:** ProbeEgressAfterClearTest (scratchpad review3-integ-probes), on 88060fa. An objective completes on a cloud model (3 requests sent). The clock moves 31 days, activityLog.prune(now − 30 days) runs as the upkeep does, then graph.ownerUsage.clearHistory() (the Settings button). Opening the objective prints "PROBE-E objective privacy line=[Nothing has left this phone for this objective.]", and the assertion fails.
- **fix:** Preferred: keep work rows on "Clear usage history". They are the objective's or chat's own record and are deleted with it already; clear only usage_hours. Otherwise, make egressWords say "What this objective sent is no longer recorded." whenever the objective started before the log's retention window and no usage rows remain. Keep "Nothing has left this phone" only when the objective's whole life is inside the kept log. Add the probe as a regression test.

### INT3-3 (minor) Usage and cost's "Today" stays on yesterday when the screen is left open across midnight, so it disagrees with the Today tile and the limiter
- **package:** WP10a (Activity › Usage and cost)
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/activity/UsageViewModel.kt:160-168 (range = selected.range(clock.now(), zone()) is computed once per period selection)
- **problem:** The Today tile moves to the new day at local midnight (TodayUsage gets DayChanges.daily). The Usage tab works out its range only when a period is picked or the flow restarts. While it stays open past midnight, "Today's limits count …" and the totals keep showing yesterday, even though the limiter already counts the new day. It corrects itself once the owner leaves the screen for more than 5 seconds.
- **evidence:** ProbeUsageMidnightTest (JVM, TestApp stores). One call is recorded at 23:40 and the view model is subscribed. At 00:30 two more calls come on the new day. Output: "usage today answers=1 lines=[Today's limits count 1 answer of 200 and 1,500 tokens of 500,000., …] limiter today=2". Assertion: Expected <2>, actual <1>.
- **fix:** Pass the graph's day-change flow (AppGraph.dayChanges / DayChanges.daily) into UsageViewModel. Work out the range again on merge(period, dayChanges), as TodayUsage.observe does. Add the probe as a view-model test.

### INT3-4 (minor) The Team view's visible summary line is the 10-second-throttled TalkBack summary, so it contradicts the rows under it
- **package:** WP9 (Team view)
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/team/TeamScreen.kt:250 (text = liveSummary.ifEmpty { state.summary }); TeamViewModel.kt:279-284 (liveSummary uses atMostEvery(SUMMARY_EVERY_MS = 10 s))
- **problem:** The plan throttles the polite live announcement, but the same throttled string is drawn as the screen's visible summary. Right after the owner acts (pause, answer a question), the top line keeps the old numbers for up to 10 seconds. It can say "1 waiting for you" next to "Nothing waits for you" and members marked "Paused".
- **evidence:** ProbeTeamStatesTest. A real objective's Researcher asks the owner, and the Team view opens in List. Then orchestrator.pause runs. One second later the screen shows "0 working, 1 waiting for you, 1 waiting for a teammate" together with "Nothing waits for you", "Director … Paused by you" and "Researcher … Paused by you". Only after 10 s of the main looper's clock does it read "0 working, 0 waiting for you, 0 waiting for a teammate, 2 paused".
- **fix:** Draw state.summary, which is current, as the visible text. Keep the throttled liveSummary only for the live region's announcement, for example as the polite region's contentDescription, or in a separate invisible node.

### INT3-5 (minor) A withdrawn question's sentence is technical and talks about "the owner" on the owner's own screen
- **package:** WP11a (question screen)
- **where:** app/src/main/java/com/aicontrolcenter/app/ui/questions/QuestionPresentation.kt:180 (shows decisionNote verbatim), framework/.../operator/PermissionBroker.kt:383 (CANCELLED_NOTE)
- **problem:** When the owner pauses an objective while a question waits, the question screen reads: "This question was withdrawn before you answered: the call that asked was stopped before the owner answered". It uses jargon ("the call"), repeats itself, refers to the reader as "the owner", and does not say what happens next (the agent may ask again after Resume).
- **evidence:** ProbeQuestionLifeTest: after the pause, the withdrawn question's screen texts include "Withdrawn" and "This question was withdrawn before you answered: the call that asked was stopped before the owner answered".
- **fix:** Map CANCELLED_NOTE (and the other framework notes) to owner words in questionOutcome. For example: "This question was withdrawn: the work stopped before you answered (for example, the objective was paused). If the agent still needs to know, it asks again after you resume." Pin it in QuestionPresentation tests.

**Checked and held:** Flakiness: every test task executed three times, none from cache. app, data and device ran in runs 1, 3 and 4; core, framework, memory, providers, providers-anthropic and testkit ran in runs 2, 3 and 4. Each run: 3,964 tests, 0 failed (logs: scratchpad review3-integ-run1..4.log). Nothing flaked, including ShareReceiverActivityTest after e859d7b.

Today's numbers agree:
- The Today tile (TodayUsage), Settings › Daily limits, the New objective line, the Usage tab's Today summary, the routine check (UsageDailyLimitCheck) and the limiter all use DailyUse.of over today's rows with the counted US$ prices.
- ProbeAnswersAgreeTest: todayUsage.now().calls = 2, which is the 2 requests the limit let through.
- A slow-down call gives 0 answers on Today.
- No money is shown on any line without a price; the tile says "Not known".

Daily limit:
- DailyLimitJourneyTest (pause → raise → Resume → completed) passed in all three runs.
- ProbeResumeAtLimitTest: Resume while the limit is still reached pauses again with "Paused: today's limit is reached. Resume after midnight or raise the limit in Settings." and sends no request.

Questions end to end (ProbeQuestionLifeTest):
- team.ask_owner from a real task gives a pending QUESTION and the strip "1 question waits for your answer", with a 20-minute window.
- A pause withdraws it (CANCELLED). The strip goes, and the question screen shows "Withdrawn" with no Send.
- After Resume the agent asks again, since a withdrawn question does not count. The owner's answer reaches the agent as "The owner answered, in their own words:" and the objective completes.

Question settings (ProbeQuestionSettingsTest):
- The "5 minutes" chip gives a 5-minute window.
- Switching "Agents may ask me questions" off keeps the waiting question answerable (APPROVED).
- The notification deep link opens the question screen, never an approval (QuestionJourneyTest).

Team view (ProbeTeamStatesTest):
- The asking agent shows "Waiting for you" with the question in quotes.
- After a pause the members show "Paused by you" and You shows "Nothing waits for you".
- The frame loop runs only while a dot is in flight, and Calm or a motion scale of 0 skips the dots (PulseClock code).

Routines and pick-up:
- RoutinesJourneyTest and PickUpJourneyTest passed in all three runs.
- WaitingRoutines.startWaiting checks again that the routine is on, the master switch is on and the tap window is open (RoutineRunner:339-350).
- Switching the master switch on moves due times forward (RoutinesWiring:169).

Backup, Recovery and share:
- Backup save → restore through the screens: BackupJourneyTest passed in all three runs.
- Recovery wording for each DatabaseState, Try again and Start over: RecoveryJourneyTest passed in all three runs.
- The share receiver goes through the gate: the Recovery screen shows and the graph is never built (ShareReceiverActivityTest, all three runs).
- Restore refuses while an objective or a chat answer runs.
- Start over says what it keeps, and the settings it keeps live in DataStore.

Navigation:
- All 12 new or changed routes have a destination and at least one entry point.
- The Settings anchors work (SettingsAnchorJourneyTest).
- Notifications carry only EXTRA_OBJECTIVE_ID or EXTRA_REQUEST_ID, and a question goes through ApprovalRoute to QuestionRoute.

"(test)": testLabel is used only for Vision (PlaceholderLabelsTest).

Rotation:
- Every new form keeps its TextFieldState and dialog flags in its view model, so a rotation keeps them.
- The new screens have only 3 plain remember states, all cosmetic.

Main thread and other settings:
- Markdown over 4,000 characters is parsed off the main thread, and the Dashboard is built on the pool.
- keepAwakeWhileAgentsWork and notifyWhenWorkEnds are read by the service.
- The Calm note reads Android's animation switch again on resume.

I appended section 12 to scratchpad/phase6/device-tests-wave3.md.

**Not checked:** Process death: no new form keeps its text when Android ends the process, because there is no SavedStateHandle outside AgentEditorScreen; the pre-wave-3 forms behave the same way. I found this by reading the code only and could not reproduce it in Robolectric, so it is not reported as a finding. It is a manual step in device-tests-wave3.md §12.

A question running out of time, tested through TestApp: TestApp's FakeClock moves on 37 s per read, which makes short windows artificial, so I relied on the framework's OwnerQuestions tests.

Not examined again here:
- Routine starts from the alarm while nobody watches, and pick-up on a real document provider.
- The backup's key step timing and SQLCipher.
- FLAG_SECURE, TalkBack, font scale, 360 dp, landscape, and the screenshots (design and device lenses).

Not measured: the Team view with 30 agents and 1,000 messages, and frame timing.

Not re-reviewed: the known open part-1 items (chat transcript built off the main thread, D35 for the memory graph), and wave-2 framework and data internals beyond what the screens read.

All probes are in /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/review3-integ-probes/ with probe-output.txt. They were run in the throwaway worktree /home/user/p6-review3-integration at 88060fa, which has since been removed. Nothing was committed, and /home/user/rune-companion was not touched.
