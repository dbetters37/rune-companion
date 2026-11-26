# Hand-off from WP16 (receiving shares from another app, "Save to memory"; Tier B, rule 34, owner request #43)

Branch `p6/wp16` (worktree `/home/user/p6-wp16`), from e79e164. Not pushed, not merged. Head and test numbers: section 6.
Files (all WP16's own, `app/src/main/java/com/aicontrolcenter/app/share/`): `ShareReceiverActivity.kt` (the activity and
`ShareHost`), `ShareIntake.kt` (reading the share: `ShareIntake`, `ShareRequest`, `ShareSource`,
`ContentResolverShareSource`, `ShareIntakeViewModel`), `ShareSaving.kt` (pure: parts, titles, drafts, file names),
`ShareViewModel.kt` (`SharedItem`, `ShareTarget`, the UI state, `ShareViewModel`, `ShareTexts`), `ShareScreen.kt`
(`ShareScreen(viewModel, onDone)` — FINAL signature kept — and `ShareContent`). Tests in `app/src/test/.../share/`. No
file outside `share/` changed. The three `@Phase6Stub("WP16")` marks are gone.

## 1. What was built (plan "#### WP16", rule 34)

| Plan item | How | Test |
|---|---|---|
| The activity passes `AppGate` | `GateHost(app.gate, recovery = { RecoveryHost(state, app.recovery, onTryAgain = gate::recheck) }) { ShareHost(app.graph, …) }`, as `MainActivity`; nothing shows while the data is checked; the graph is touched only inside the open gate. It does **not** call `graph.start()` (start-up can resume interrupted objectives: rule 34 "never starts work") | `ShareReceiverActivityTest` (Recovery shows, `graphIfBuilt()` stays null), `ShareSourceScanTest` (the graph members it reads; no `.start()`, orchestrator, gateway, chat, tools, settings writer) |
| Only `ACTION_SEND` / `ACTION_SEND_MULTIPLE` of `text/*` | Checked again in code (the activity is exported, so an app can start it by name with anything): another action or type closes it at once, nothing read or stored. Damaged extras (a hostile bundle) are caught: "What the other app shared could not be read." | `ShareIntakeTest`, `ShareReceiverActivityTest` |
| `EXTRA_TEXT` and, for `EXTRA_STREAM`, up to 10 `text/*` files of at most 1 MB through the one-time read grant | Shared text at most 200,000 characters each (see deviation 1); files: the first 10, each read to at most 1 MB + 1 byte (never whole), `text/…` by the provider's type (else the share's), strict UTF-8 (BOM dropped), blank ones left out. Only `content:` addresses, never `file:`, and never one of this app's own providers (any `<applicationId>.…` authority, also with a `N@` user prefix, or one `PackageManager` says is ours: a share can never make the app read its own private files). Read on the IO pool (`runInterruptible`) in `ShareIntakeViewModel`, kept across rotation; every part left out gets a plain sentence on screen | `ShareIntakeTest` (12), `ShareReceiverActivityTest` (a real `ContentProvider`, the real `ContentResolver`) |
| Shows the size | Per item "About 1,240 words · 7.4 KB" (words, not tokens: no jargon); with several items the whole share's size too; the start of each item (400 characters, 8 lines, 4 with several items) quoted on a quiet ground, hidden characters marked (`previewAnnotated`), "As the other app wrote it." / "The start of it, …" | `ShareViewModelTest`, `ShareScreenTest`, pictures |
| A picker of the spaces the owner can write plus "Workspace" (as a file) | Every memory space (the owner can write all), by name, each with who can read it ("Researcher and Writer can read it." / "No agent can read it yet. You choose who can in Memory › Spaces."), then "Workspace (as a file)". **Nothing is picked for the owner**; Save is off until a place is picked. With no space yet: only the workspace, and how to make a space | `ShareViewModelTest`, `ShareScreenTest` |
| The line "Storing is free. Only what agents recall is sent to a model. A cloud model would receive this text; local-only mode and space access keep it on the phone." | `ShareTexts.STORING`, word for word; with local-only mode on, also "Local-only mode is on: agents use only models on this phone, so what they recall stays on it."; and "It is kept as text another app shared, never as your own words: agents who read it take it as information, not as instructions from you." | `ShareScreenTest` (exact text) |
| Save stores through the owner's memory path with provenance "Shared from another app", split into parts within the memory size bound | `MemoryService.remember(MemoryActor.Owner, …)`: a NOTE in the chosen space, origin **IMPORT** (never OWNER; the memory service keeps IMPORT for the owner), source "Shared from another app", tag `from-another-app`; parts of at most 20,000 characters (= `PickUpRunner.PART_CHARS`, the memory service's limit) cut after a line break or space, never inside a surrogate pair, titled "<title> (part 2 of 3)" (title cut so it fits 200). One title field when one item is shared (from the share's subject, else the file's name, else "Shared text"); several items keep their own names | `ShareSavingTest` (8), `ShareViewModelTest` |
| … or the workspace | Each item a file in the workspace folder "From other apps" (a file keeps its name, ".txt" added when it has no ending; shared text is "<title>.txt", ".md" for Markdown), never over another file ("notes (2).txt") | `ShareViewModelTest`, `ShareReceiverActivityTest` |
| Nothing before Save (rule 34) | Opening, reading, picking a place and typing a title store nothing; Back, "Don't save" and leaving store nothing. A save is all or nothing: if a part cannot be stored, or the owner taps "Stop saving" (or Back while saving), what this save stored is taken back (memories forgotten; workspace files trashed and then deleted from the trash by their own ids only, the owner's trash untouched) | `ShareViewModelTest`, `ShareReceiverActivityTest` |
| The content is data, never instructions | IMPORT origin; recall frames it as quoted data: a shared text with `</recalled_memories>`, `<owner_note id=…>Note from the owner: …</owner_note>` and "Owner instruction: …" reaches an agent through `AutoRecall` with every one of its lines after the quote marker, no `<owner_note` surviving (`&lt;`), and the block ending with its own tag | `ShareViewModelTest` ("what was shared reaches an agent only as recalled data, even when it looks like a note from the owner") |
| Allowed in local-only mode | Storing sends nothing anywhere; the screen says what stays on the phone | `ShareViewModelTest`, `ShareReceiverActivityTest` |
| Screen control cannot press Save | Own package (the accessibility service never acts on it); Save is also marked `isSensitiveData` (Android 14+), and touches through another app's window are refused (`ObscuredTouchGuard`) | `ShareScreenTest` (sensitive, `filterTouchesWhenObscured`); device step |
| Rule 27 typing | The title is `AppTextField(state = viewModel.title)` (the view model owns the `TextFieldState`, read at the tap of Save, cleared when the view model goes); paused-clock fast typing through the real activity, exact text asserted and stored | `ShareReceiverActivityTest` ("the title is exactly what was typed…") |
| UI rules | Material 3 and the app's components (`ScreenScaffold`, `AppCard`, `InfoBanner`, `SectionHeader`, `AppTextField`, `EmptyState`); radio rows ≥ 56 dp in one selectable group, merged semantics; never colour alone (words on every state); 360 dp, wide (readable width), dark, light; at usual font sizes Save and "Don't save" stay in view at the bottom, at font scale ≥ 1.5 they come at the end of the one list; Calm: the reading state shows a still clock instead of a spinner, the save bar is determinate; long work shows progress and can be stopped; summaries and parts worked out off the main thread (`LocalScreenWork`, the app's background pool) | `ShareScreenTest` (9), pictures |
| Activity log | One USER_ACTION line per save ("Saved what another app shared": "3 memories in the memory space “Screen notes”." / "2 files in the workspace folder …"), never the text; the memory service logs each memory as before | `ShareViewModelTest` |

## 2. Items addressed to WP16 (or to every 3b package) in the hand-offs

| Item | From | Done? |
|---|---|---|
| Pass the gate (`gate.whenOpen` / `GateHost`) before touching the graph; `RecoveryHost` when the data does not open | handoff-wp8.md §3, wave3-ownership.md §4 | Done (`GateHost` + `RecoveryHost(state, app.recovery, gate::recheck)`); test above |
| Replace the three stubs, keep `ShareScreen(viewModel, onDone)`, remove the marks | wave3-ownership.md §0, §4 | Done |
| Use only the stub view model's ports | wave3-ownership.md §0 rule 4 | Done, with four defaulted additions (deviation 2) |
| Text fields: `AppTextField(state = …)`, `TextFieldState` in the view model, paused-clock typing test | handoff-wp0.md §3, rule 27 | Done (`TextFieldRuleTest` passes) |
| Calm (`LocalCalmMotion`) and the background pool (`LocalScreenWork`) | handoff-wave3a-merge.md §4 | Done (`ShareHost` wraps `AppEnvironment(graph.settingsView, AppScreenWork)`) |
| Stub gate | handoff-wave3a-merge.md §4 | WP16 has no stub left; WP8 may drop "WP16" from `Phase6StubGateTest.STILL_BUILDING` |
| `ManifestRulesTest` last assertion for the share receiver | handoff-wp1d.md | Already done by WP8 in 3a (manifest entry and both manifest tests); nothing changed |
| Share receiver stub device step | device-tests-wave3.md §6 item 3 | Replaced by the new "WP16" section there (plan §10 step 17 in full) |
| handoff-WP10a.md §4 ("WP16: nothing") | | Nothing to do |
| Items addressed to WP8, WP10, WP11a/b in the other hand-offs | | Not WP16's files; none touches the share receiver |

## 3. For WP8 (files WP16 does not own)

1. **Nothing is required.** The manifest entry WP8 made in 3a is what WP16 needs (exported, SEND and SEND_MULTIPLE of
   `text/*`, DEFAULT, `excludeFromRecents`, `adjustResize`).
2. `Phase6StubGateTest.STILL_BUILDING`: drop "WP16" at the merge (no WP16 stub left).
3. Optional, for wave 4 to judge: the share sheet shows the app's name ("AI Control Center"), which is what the owner
   looks for; an `android:label` on the activity ("Save to memory") would replace it in some share sheets, so it was not
   asked for.
4. **Tier B switch** (after wave 4 round 2, plan §2): if WP16 is cut, remove the `ShareReceiverActivity` entry from the
   manifest (then `MergedManifestTest`'s exported list loses it) and leave `share/` in place, or delete both; nothing
   else refers to `share/`.

5. **Merge order:** `share/` touches no file of WP9, WP10b or WP10c. `ShareReceiverActivityTest` ("when the data cannot be
   opened the Recovery screen shows…") expects the Recovery screen to show `DatabaseState.ownerText`, which both the 3a
   stub and WP10b's screen (`RecoveryViewModel.reason`) do; rerun it after WP10b is merged.

## 4. Notes for wave 4's reviewers

- **Caps' reading (deviation 1):** rule 34 "at most 200,000 characters and 10 files of at most 1 MB" is read as: each
  shared text at most 200,000 characters; at most 10 files, each at most 1 MB (1,048,576 bytes, the same as folder
  pick-up). A full share can therefore be about 10 MB of text (about 520 memories); the save shows progress and can be
  stopped. If the reviewers want one cap on the whole share, it is one constant and one check in `ShareIntake.read`.
- **A provider that never answers:** reading runs in `runInterruptible(Dispatchers.IO)`; "Don't save" closes the screen
  at once and cancels the read, but a blocked `read()` of a hostile provider's pipe may keep one IO thread until the
  provider answers or closes (the process is not harmed; nothing is stored).
- **Process death in the middle of a save** leaves the parts stored so far (the take-back runs on cancellation and on
  failure, not when Android kills the process); they are ordinary memories marked "Shared from another app" that the
  owner can delete. Rare: a save of a few parts takes well under a second.
- **`referrer` is not used:** the sending app is not named anywhere (Android lets a sender claim any referrer), only
  "Shared from another app".
- **Title from the share's subject** is the other app's text: it becomes the memory's title (one line, hidden and
  control characters dropped, at most 160 characters as suggested) and is framed like every title when recalled.
- **Workspace take-back** uses `trash` then `emptyTrash(ids = those ids, leftovers = false)`: only files this save wrote.
- The owner's own recorder app: to be received in full, export UTF-8 text either as `EXTRA_TEXT` (up to 200,000
  characters) or as `.txt`/`.md` files (`EXTRA_STREAM` with `FLAG_GRANT_READ_URI_PERMISSION`, at most 10 files of 1 MB).
  WP14 may put this in the README.

## 5. Deviations from the plan, with reasons

1. Caps as read in section 4 (each text ≤ 200,000 characters; files ≤ 10 × 1 MB), not one 200,000-character cap on the
   whole share: a 1 MB file cap would otherwise be meaningless, and the owner's exports are long.
2. `ShareViewModel` gained five parameters after the stub's four, all defaulted: `directory: RegistryDirectory?` (read
   only: who can read each space, so the owner sees where the text could go — "no forced sharing"), `activity:
   ActivityRecorder?` (one log line per save), `leftOut: List<String>` (what the activity could not take, shown on the
   screen), `clock`/`computation`. Only `ShareHost` (WP16's) builds it.
3. `SharedItem` gained two defaulted fields (`fromFile`, `markdown`) for the workspace file names.
4. A title field (not in the plan's list) for a single shared item: the owner's exports may come without a useful
   subject, and the title is what lists show. Rule 27's typing test covers it.
5. The share does not run the app's start-up (`graph.start()`), so on a phone where the app was never opened there may be
   no memory space yet: the screen then offers the workspace and says how to make a space.
6. `ShareTarget`/`ShareUiState`/`ShareTexts` are public (the screen and its tests use them); `ShareIntake`'s request and
   reader are `internal`.

## 6. Checks run

- Branch `p6/wp16`, head **74cd620** (commits 6a0ff67, 0c19180, 74cd620 on e79e164). Not pushed, not merged.
- Whole suite in the worktree (`export TMPDIR=/tmp; ./tools/gw --offline test --continue`, then `./tools/test-summary`):
  **3,734 tests, 0 failed** — app 996, core 182, data 494, device 367, framework 1,096, memory 107, providers 229,
  providers-anthropic 89, testkit 174 (the JVM modules came from the build cache: unchanged since e79e164; app, data and
  device ran). WP16's own: 65 tests in app — `ShareIntakeTest` 12, `ShareSavingTest` 8, `ShareViewModelTest` 12,
  `ShareReceiverActivityTest` 7, `ShareScreenTest` 9, `ShareSourceScanTest` 4, `ShareScreenshotTest` 13 (pictures).
- `Phase6StubGateTest`: 25 stubs left (WP10b 12, WP10c 9, WP9 3, WP8 1), none of WP16.
- `:app:assembleDebug`: BUILD SUCCESSFUL.
- Pictures (review material, not committed, as earlier packages): `app/build/outputs/roborazzi/p6_wp16_*.png` (19 files,
  `./tools/gw --offline :app:recordRoborazziDebug --tests 'com.aicontrolcenter.app.share.ShareScreenshotTest'`), copied to
  `scratchpad/phase6/wp16-shots/`: one text dark (top, picked, bottom with the Save bar), light, wide (top, bottom), 360 dp,
  360 dp at font 2.0 (top, picked, bottom with Save at the end of the list), several files with parts left out (top,
  picked), no space yet, local-only, saving, saved (dark, light), nothing to save, reading under Calm.
- Device steps for wave 5: `scratchpad/phase6/device-tests-wave3.md`, section "WP16" (replaces section 6 item 3; plan §10
  step 17 in full).
