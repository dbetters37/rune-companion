# Device tests for the wave-3 gate: carried over from the wave-2b device recheck

Written by the wave-2b device-recheck fixer (branch p6/w2bdev, merged as "Wave 2b: device recheck fixes"). The recheck ran at
76c48c0 on AVD tcg34 (Android 14 ATD, software CPU): 20 instrumented tests passed, manual checks 1, 3, 4 and 5 held, check 2
could not be done, and check 1 found F1 and F2 (both fixed here on the JVM, see section 1). Everything in device-tests-wave2.md
and device-tests-wave2-recheck.md still applies. Run with `export TMPDIR=/tmp` and `./tools/gw --offline` only.

## 0. What image to use

- The tcg34 ATD image has no system folder picker (DocumentsUI): `OPEN_DOCUMENT_TREE` resolves to nothing (`START ... result
  code=-91`) and the app says "Android's folder picker could not be opened on this phone, so no folder can be added."
  Section 1 needs a Google APIs (not ATD) Android 11+ image, or a real phone. Doze is off by default on ATD
  (`dumpsys deviceidle enable deep` turns it on).
- Section 2 needs a model provider with a key, or a local model (the built-in simulator only calls the time, date and team
  tools and never waits for an approval). It belongs with wave 5's end-to-end steps if no key is available at the wave-3 gate.

## 1. Folders picked through the "Downloads" entry (F1, F2 of the recheck; SEC-W2B-2)

What changed on p6/w2bdev (JVM and Robolectric tests, each failed before the fix; red logs in
scratchpad/phase6/w2bdev/red*.log):
- F1: Android 14's Downloads entry names a picked folder `msd:<n>` (a MediaStore folder), or `raw:<path>` before MediaStore has
  seen it; never `msf:<n>` (files). core `FolderTrees` now places `msd:<n>` like `msf:<n>`: "somewhere in a Download folder".
  Such a folder is unknown (counted as overlapping) only against folders in or around a Download folder, and apart from
  folders elsewhere (Documents/Notes). Before, it was unknown against every folder on the phone's storage.
  Tests: core `FolderTreesTest` (the gate's exact tree URIs), framework `PickUpRunnerTest`, `OwnerServicesTest`, device
  `SafSharedFoldersTest` (scenarios A and D).
- F2: folder pick-up no longer says "This folder is inside another shared folder agents may change, or holds one" when that
  cannot be told. New sentences in core `PickUpSafety`: `FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE` /
  `RUN_FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE` (one of the two came through the Downloads entry) and
  `FOLDER_PLACE_UNKNOWN_NEAR_WRITABLE` / `RUN_FOLDER_PLACE_UNKNOWN_NEAR_WRITABLE` (another profile, the media list). Both run
  sentences switch the rule off (`PickUpSafety.switchesRuleOff`). The certain case keeps `FOLDER_OVERLAPS_WRITABLE`.

### 1a. Set-up (root on an emulator; on a phone use the Files app)

```
adb root
adb shell mkdir -p /storage/emulated/0/Download/GateDL/Inner /storage/emulated/0/Documents/Notes
adb shell 'echo hello > /storage/emulated/0/Download/GateDL/a.txt'
# Optional, to see both id forms: list the Downloads entry before and after MediaStore has seen the folders.
adb shell content query --uri content://com.android.providers.downloads.documents/document/downloads/children
adb shell content call --uri content://media --method scan_volume --arg external_primary
adb shell content query --uri content://com.android.providers.downloads.documents/document/downloads/children
```

Before the scan the Downloads entry lists `document_id=raw:/storage/emulated/0/Download/GateDL`; after it,
`document_id=msd:<n>` (the recheck saw msd:1000000019, inner folder msd:1000000020).

### 1b. Pick the folders with the real picker

Needs WP10's Tools > Folders "Add folder" (or any wave-3 screen that starts `ACTION_OPEN_DOCUMENT_TREE` and calls
`graph.ownerFolders.add(uri)`).
1. Add GateDL through the picker's "Downloads" entry (left drawer > Downloads > GateDL > "Use this folder" > Allow).
2. Add Download/GateDL/Inner and Documents/Notes through the phone's storage entry (drawer > the phone's name > Download >
   GateDL > Inner; then Documents > Notes).
3. Read the tree URIs Android gave (rooted emulator; on a phone skip this, the sentences in 1c tell the form):
   `adb shell cat /data/system/urigrants.xml | grep com.aicontrolcenter.hq`
   Expected: `content://com.android.providers.downloads.documents/tree/msd%3A<n>` for GateDL (or `.../tree/raw%3A%2Fstorage
   %2Femulated%2F0%2FDownload%2FGateDL` if MediaStore had not seen it), and
   `content://com.android.externalstorage.documents/tree/primary%3ADownload%2FGateDL%2FInner` and `.../primary%3ADocuments%2FNotes`.
   Any other form (in particular `msf:`): record it in the gate result, it is a finding.

### 1c. Expected behaviour (exact sentences)

Writes need an agent, or the throwaway probe of the recheck (scratchpad/gate2b-recheck-probe/FolderGateProbe.kt,
`mode=folders`, copied into app/src/androidTest of a throwaway worktree, never committed). Careful: that probe first deletes
every stored folder record of the installed app, so run it only on a test install; give it the URIs read in 1b step 3.
Pick-up rules: WP10's Folder pick-up screen, or the same probe.

Scenario A: GateDL (Downloads entry, `msd:`) writable; Inner and Notes read-only.
- A pick-up rule on Notes is made. (Before the fix it was refused.)
- A pick-up rule on Inner is refused with: "This folder may be inside another shared folder agents may change, or hold one:
  Android does not tell this app where a folder shared through the folder picker's Downloads entry is. In Tools > Folders,
  make that other folder read-only too, or remove the folder from the Downloads entry and share it again through the phone's
  storage entry. Then try again."
- A write into GateDL is refused with: "Android does not tell this app where GateDL is, because it was shared through the
  folder picker's Downloads entry. GateDL may hold, or be inside, the read-only folder Inner, so nothing can be changed in it.
  Sharing GateDL through the phone's storage entry instead lets the app tell." It names Inner, never Notes.
- Remove Inner: the write into GateDL now works (gate-probe.txt or the agent's file appears in Download/GateDL).

Scenario B: GateDL through the Downloads entry before the scan (`raw:` id). Exact comparison: the rule on Inner is refused
with "This folder is inside another shared folder agents may change, or holds one. ...", the rule on Notes is made, and the
write into GateDL is refused with "GateDL holds, or is inside, the read-only folder Inner, so nothing can be changed in it."

Scenario D: GateDL (`msd:`) read-only; Inner and Notes writable.
- A write into Notes works. (Before the fix it was refused.)
- A write into Inner is refused with "Android does not tell this app where GateDL is, ... Inner may hold, or be inside, the
  read-only folder GateDL, so nothing can be changed in it. ..."
- A pick-up rule on GateDL is refused with the Downloads-entry sentence of scenario A. With Inner removed (Notes still
  writable) the rule on GateDL is made.
- With that rule on, add Inner again (writable): the rule switches off at once and its last result reads "Nothing was picked
  up: this folder may be inside another shared folder agents may change, or hold one, because Android does not tell this app
  where a folder shared through the folder picker's Downloads entry is. So the rule was switched off. Make that other folder
  read-only in Tools > Folders, or remove the folder from the Downloads entry and share it again through the phone's storage
  entry, then switch the rule on." The activity log has "Folder pick-up switched off: GateDL".

Pass: every refusal above, and the Notes rule and Notes write go through. No read-only folder may change in any scenario
(check `adb shell ls -la /storage/emulated/0/Download/GateDL/Inner /storage/emulated/0/Documents/Notes` before and after).
Clean up: remove the folders in the app, then `adb shell rm -r /storage/emulated/0/Download/GateDL
/storage/emulated/0/Documents/Notes` and the scan command of 1a.

## 2. Wake lock per run while a task waits for the owner (AND-W2B-1; recheck check 2, not possible there)

Covered on the JVM by the real `ObjectiveWorkService` with `WakeHold` under Robolectric (fixer's ObjectiveWorkServiceTest,
verifier's VerifyWakeLockProbeTest). On a device:
1. A provider with a key (or a local model). Settings: "Keep the phone awake while agents work" on. An agent whose tools
   include "Ask the owner" (`team.ask_owner`), for example the Director.
2. Start an objective that makes it ask first, e.g. "Before you start, ask me whether I want the answer short or long, then
   write three lines about tea." Wait for the notification "A question from <agent>". Do not answer it.
3. `adb shell dumpsys power | grep -i "aicc:agents"`: no line (the task only waits). The work notification does not say
   "Keeping the phone awake while agents work".
4. Open a chat with the same agent and send "Write a 300-word story about a lighthouse." Turn the screen off at once
   (`adb shell input keyevent KEYCODE_SLEEP`). While the answer is written, repeat the `dumpsys power` line every 2 s
   (`for i in $(seq 1 30); do adb shell dumpsys power | grep -c "aicc:agents"; sleep 2; done`): it shows 1 (a
   PARTIAL_WAKE_LOCK "aicc:agents" held).
5. When the chat answer has ended (the task still waits for the question): the count goes back to 0 within a few seconds,
   and the notification no longer says "Keeping the phone awake while agents work".
6. Answer the question: the lock comes back while the task works, and goes when the objective ends.
Fail: the lock still held after step 5, or held in step 3.

## 3. Backup times on the software-CPU emulator (device gate wave 2, finding 2; confirmed by the recheck)

`BackupDeviceTest` measured about 5 minutes to save (316,394 ms, 3,587 bytes) and about 3 minutes to restore (188,792 ms) on
tcg34, nearly all of it key derivation. For WP10's Save and Restore screens on this emulator: the key-derivation step shows
its progress text and Cancel for the whole time; Cancel during it stops the save (no partly written file left) or the restore
(nothing changed); no ANR while it runs (`adb logcat -d | grep -E "ANR in com.aicontrolcenter.hq|Input dispatching timed
out"` stays empty); the screen may turn off and the save still finishes. On a real phone, write down the save and restore
times (expected seconds, not minutes).

## 4. First cold launch after a fresh boot (recheck observation, not a finding yet)

The recheck saw one input-dispatch ANR ("Waited 5005ms for FocusEvent") on the very first cold launch of the debug build
after boot; later launches showed the first screen after about 15-17 s with no ANR. Most likely the software CPU. At the
wave-3 gate, after a fresh boot and install: `adb shell am start -W -n com.aicontrolcenter.hq/com.aicontrolcenter.app.MainActivity`,
then `adb logcat -d | grep -E "ANR in com.aicontrolcenter.hq|Input dispatching timed out"`. Once: note it. Again on a second
fresh boot, or on a real phone: a finding for WP8 (main-thread work at start; see the StrictMode row of
handoff-wave2b-merge.md section 3).

## 5. Instrumented tests

p6/w2bdev changed no instrumented test and no code under one (core `FolderTrees`/`PickUpSafety` are pure JVM; the device
module's `SafSharedFolders` is only tested under Robolectric). Run the modules whole as usual: app
`scripts.ScriptProcessDeviceTest` (6), data (31, among them `recovery.StartOverDeviceTest` 3, `backup.BackupDeviceTest` 1,
`backup.BackupVectorsDeviceTest` 5), device `operator.commands.ShellLeftoversDeviceTest` (10), plus whatever wave 3 adds.

## 6. Added by WP8, part 3a (wave 3; branch p6/wp8)

Nothing here needs an instrumented test file; these are manual checks for the wave-5 run (or the wave-3 gate if an
emulator is free). Run with `export TMPDIR=/tmp` and `./tools/gw --offline` only.

1. **Cold start after a fresh boot (D45, section 4 again).** The gate now builds the graph off the main thread before it
   opens, and `Application.onCreate` no longer asks for `noBackupFilesDir`. After a fresh boot and install of the debug
   build: `adb shell am start -W -n com.aicontrolcenter.hq/com.aicontrolcenter.app.MainActivity`, note `TotalTime`; then
   `adb logcat -d | grep -E "ANR in com.aicontrolcenter.hq|Input dispatching timed out"` (expected: empty). Repeat after
   `am force-stop` twice. Proves: no input-dispatch ANR at the first cold launch.
2. **StrictMode (debug build only).** `adb logcat -d -s StrictMode` after the cold start of item 1 and after opening
   Dashboard, Settings and a chat: every line is a finding for WP8 (main-thread disk or network work, a leak). Expected:
   none from `com.aicontrolcenter` frames at start; record any with its first app frame. The release build logs nothing.
3. **Share receiver stub (WP16 not built yet).** `adb shell am start -a android.intent.action.SEND -t text/plain --es
   android.intent.extra.TEXT "hello" -n com.aicontrolcenter.hq/com.aicontrolcenter.app.share.ShareReceiverActivity`: the
   activity closes at once; nothing is stored (Memory shows no new item). An `image/png` SEND is not offered to the app
   (`adb shell cmd package query-activities -a android.intent.action.SEND -t image/png | grep aicontrolcenter`: empty).
   When WP16 ships, section 10 step 17 of the plan replaces this.
4. **"Tap to start" while the app is open on another screen.** With a routine waiting for a tap (routines switched on,
   "Alarms and reminders" off so the alarm is inexact, let it fire), open the app's Tasks section first, then tap the
   "A routine is waiting to start" notification: the app shows "Routines waiting to start" once (Back returns to Tasks);
   Start asks once more; nothing started before the confirmation. A cold start from the notification shows the
   Dashboard's banner instead.
5. **"Waiting for you" on the phone (Motorola razr) at font scale 2.0.** The rail is shown only on wide screens; on the
   phone, a pending question gives the strip "1 question waits for your answer"; TalkBack reads "1 question waits for your
   answer. Review".

## 7. Added by WP11a (wave 3, part 1; branch p6/wp11a): tasks, approvals and questions

No instrumented test file was added; these are manual checks for the wave-5 run (or the wave-3 gate if an emulator is
free). They need a model that calls `team_ask_owner` (a provider with a key, or a local model; the simulator never asks).
Run with `export TMPDIR=/tmp` and `./tools/gw --offline` only.

1. **A real question, answered from its notification (Q3, rule 13; handoff-wp5.md §4).** Give an objective whose brief
   leaves a choice open ("Plan a weekend away; ask me where"). When "A question from <agent>. Open the app to read it."
   appears, tap it with the app in the background: the app opens the question screen ("A question from <agent>", "This
   is not a permission. Your answer is only text for the agent."), never the Approval screen; Back returns to where the
   app was. Type an answer, Send: the task goes on, its next request carries the answer (mock server log, or the task's
   transcript), and the next tool call that sends anything off the phone first asks an APPROVAL whose first detail line
   says "after you answered a question" (rule 32). Proves: the notification entry and the hand-over to the question screen.
2. **Code and key answers on the phone keyboard (rule 32).** On the same kind of question, paste "482913" and Send: "Is
   this a code?" and "Send it anyway" appear; nothing is sent until that second tap. Paste a provider key you stored in
   Models (exactly as stored): "Not sent: this looks like a key or a password…", no second chance; the request stays
   pending (`adb shell run-as com.aicontrolcenter.hq` is not needed: the task still says "Waiting for your answer").
3. **TalkBack on the question screen.** With TalkBack on, swipe through: title, "Not a permission" banner, "<agent> asks"
   heading, the question in quotes, each "Use this answer: <option>", the answer field ("Your answer"), Send, "Decide
   yourself", the time left. Double-tap a "Use this answer": it only fills the field (no announcement of sending).
   Proves: the reading order and the labels; no suggestion reads like an approval.
4. **Font scale 2.0 and 360 dp (Motorola razr outer screen if available).** Settings › Display › Font size largest: the
   question screen, the "Waiting for you" list and the objective header (pause banner, cost line, "Team view") scroll
   without clipped buttons; every button is at least one finger high.
5. **Objective paused by today's limit (Q2).** Settings › Daily limits: answers 1. Start an objective on a cloud model:
   after the first answer the objective shows "Paused: today's limit is reached" with "Daily limits" and Resume; raise the
   limit, Resume: it goes on. Proves: the pause banner and the link, with the real limiter.
6. **Phone almost full (R8).** Fill the emulator's storage to under the critical bound (`adb shell fallocate` into the
   app's cache is not enough; use `adb shell dd if=/dev/zero of=/sdcard/Download/fill bs=1m count=<n>`), open New
   objective: "The phone is almost full" banner; Start refuses with "The phone is almost full. Free some room before
   starting an objective."; delete the file, Start works.
7. **Large final document (P7).** An objective whose final document is long (for example a 40 KB Markdown result from
   a local model): open its Result tab; scrolling stays smooth and `adb logcat -s StrictMode` shows no main-thread
   parse (once WP8 provides `LocalScreenWork` = `Dispatchers.Default` in `MainActivity`, handoff-wp11a.md item 1).

## WP11b (chat, models, agents, memory graph, tools): manual steps for wave 5

Branch `p6/wp11b`; hand-off `scratchpad/phase6/handoff-WP11b.md`. Module `:app`; nothing instrumented was added (every
check below is manual on the emulator or the owner's razr).

1. **A chat reply cut off by the app stopping (R3).** Chat with an agent on a slow model (a local model, or a cloud one
   with a long question). While "Writing the reply" shows, `adb shell am force-stop com.aicontrolcenter.hq`. Open the app
   and the chat: "The app stopped" with "The app stopped before <agent> finished answering…" and "Send again". Tap it: the
   reply arrives; the owner's message appears once (not twice). Proves: `recoverInterruptedTurns` at start and
   `sendAgain` with the real store (Room, SQLCipher).
2. **The cost line with a real provider (goal 1, rule 29).** Enter the model's price in Models › the model › Price
   ("Save price"; try "0,15" with a comma keyboard: it is read as 0.15). Chat once: under the reply "This chat so far:
   N tokens, about US$…" with the estimate sentence. Compare N with the provider's usage page. Proves: work usage of
   source CHAT is recorded and read through Room.
3. **Today's limit in a chat (rule 6).** Settings › Daily limits: answers 1. Chat twice with a cloud model: the second
   message shows "Today's limit is reached" with the limit's sentence alone, "Daily limits" opens the limits screen, and
   the composer says the same while the limit holds. Raise the limit, "Send again": the answer comes.
4. **Android switched screen control off (D7).** With Screen control on and the service Ready, force-stop the app from
   Android's App info. Open Tools › Screen: in "Set up phone control", step 4 "Turn on AI Control Center in
   Accessibility" is Not done with Android's sentence under it ("…after the app was stopped" on Android 14,
   "force-stopped" on 15+); tapping the step opens Android's page (the owner's own tap; the operator never drives it).
5. **Another app's folder (Tools › Folders).** Share a folder through the picker from a third-party storage app (a file
   manager or a cloud drive that offers folders): the card says "This folder comes from <that app's name>…" (if Android
   hides the name by package visibility: "another app"); switch Read-only on and off: the sentence changes. With
   `adb logcat -s StrictMode` running, no disk read on the main thread appears while the tab opens (the name is read on IO).
6. **Pick-up rule switched off.** With folder pick-up on (Settings), make a rule for a read-only folder ("Pick up new
   files" on its card), then switch Read-only off: the message says the rule was switched off, and Folder pick-up shows it
   off.
7. **A long chat (P5).** A conversation of a few hundred entries (several long chats): it opens at the end within a
   moment; "Show earlier messages" at the top adds earlier ones above without jumping; typing in the composer stays
   smooth (the transcript is not rebuilt per key).
8. **Model editor and agent editor at 360 dp / font scale 2.0 (razr outer screen if available).** Models › a model:
   Price fields, "Save price"/"Remove price", "Speed and reliability" and "Cheaper model for helpers" fit without clipped
   buttons; Agents › edit › "Choose/Change model" lists "Reasoning: … · Coding: …"; the Tools section's token sentence is
   readable. TalkBack reads the chat's cost line as one item with its explanation, and "Send again", "Daily limits", "Show
   earlier messages" as buttons.
9. **Graph export in local-only mode (D37, rule 21).** Turn local-only mode on, Memory › Graph › Export graph: the share
   sheet opens, the snackbar says "…The app you choose may send it off the phone."; Activity lists "Memory graph exported"
   with "Local-only mode was on; this was your own export.".

## WP10a (usage, cost, daily limits, Activity log, Dashboard parts): manual steps for wave 5

Branch `p6/wp10a`; hand-off `scratchpad/phase6/handoff-WP10a.md`. Module `:app`; nothing instrumented was added (every
check below is manual on the emulator or the owner's razr). Steps 1-3 need a provider off the phone (a key, or a server on
the owner's network); the simulator is on the phone and is never limited.

1. **Usage and cost against the provider's own page (goal 1, rule 29).** Enter the model's price (Models › the model ›
   Price). Run one objective and one chat. Activity › Usage and cost › Today: the model's row shows "N answers · T tokens ·
   about US$…", "Today's limits count …", and the by-agent rows; compare T with the provider's usage page (it should match
   what the provider reports; "The provider did not say how many tokens …" appears when it does not). Tap the model's row:
   the Log opens searched for "<model> via <provider>" and lists its "Model call" entries. Proves: Room's `usage_hours`
   sums, the keyset/`LIKE` search over the real SQLCipher database, and the words.
2. **A day's limit end to end (rule 6, Q2; DailyLimitJourneyTest's device twin).** Settings › Daily limits › Change daily
   limits: answers 2, Save ("Saved."). Start an objective: it pauses with "Paused: today's limit is reached"; the Dashboard
   shows "Today's limit is reached" in the Today tile and "Paused by today's limit: <title>" under "On this phone"; tap it,
   raise the limit to 50 in Settings, Resume on the objective: it finishes. After midnight (or with the phone's date moved
   one day on the emulator) the tile shows 0 again without restarting the app (`DayChanges.daily`).
3. **Usage history keeps today (S29).** With usage from today and from an earlier day (emulator: move the date back a day,
   chat once, move it forward), Settings › Usage history › Clear usage history › Clear history: "Usage before today was
   cleared …"; Activity › Usage and cost › 7 days now shows only today's numbers, and Settings › Daily limits still counts
   today's answers.
4. **"Show older" over a long log (D41).** With a few thousand events (a day of objectives, or `adb` a debug build's
   sample data), Activity › Log: the first page opens at once; "Show older events" at the end adds 100 more each time with
   no stutter; while older pages are shown, a new event makes "1 new event since you looked further back" appear at the top
   with "Show the newest". The search field takes fast typing (gboard, swipe typing) without dropping letters.
5. **TalkBack.** Dashboard: the Today tile's numbers read as "22 Answers, of 200", "Estimated cost …"; Getting started
   reads "Step 2, done"; the safe-start banner (turn it on by crashing the app three times within 15 minutes, e.g.
   `adb shell am crash com.aicontrolcenter.hq` three times) reads its reason, then "Back to normal". Daily limits: each
   limit reads as one phrase ("Answers: 2 of 2 today. Reached."); the fields read their labels and their help lines.
6. **Font scale 2.0 and the razr's outer screen (360 dp class).** Dashboard (Today tile stacks its three numbers),
   Activity › Usage and cost (period chips wrap to two lines, tab title on two lines), Daily limits (fields and buttons
   unclipped), Settings › Usage history (chips at least one finger high).
7. **Off the main thread.** With `adb logcat -s StrictMode` running, open Activity › Usage and cost on 30 days and
   This month with a few months of usage: no disk read on the main thread from the screen (once WP8 provides
   `LocalUsageWork` = `Dispatchers.Default` in `MainActivity` and passes `computation` to the Dashboard extras, hand-off
   item 2).

## 8. Added by WP8 at the part-1 merge (wave 3; branch claude/ai-control-center-architecture-wjdfww)

1. **Screens build their state on the background pool (handoff-wave3a-merge.md §2, row 1).** Debug build, `adb logcat -s
   StrictMode` running. Open Tasks (with 50+ objectives), an objective with a long final document, Waiting for you, a
   question, Activity › Log and › Usage and cost (30 days, This month) and the Dashboard, quickly one after another: no
   `DiskReadViolation` or `DiskWriteViolation` whose stack names `ObjectiveViewModel`, `ObjectivesViewModel`,
   `QuestionViewModel`, `UsageViewModel`, `ActivityViewModel` or `DashboardExtrasViewModel`; no ANR; scrolling the
   objective's Result tab stays smooth. (Proves `AppScreenWork` reaches `LocalScreenWork` and `LocalUsageWork` in the real
   activity.)
2. **Calm from Android's own switch.** Developer options › Animator duration scale › Animation off, then return to the
   app: `LocalCalmMotion` is on (visible once WP9's Team view and the network card read it: nothing pulses). Turn it back to
   1x, return: motion again. Also with battery saver on (it turns animations off on most phones). Robolectric cannot show
   the real scale.
3. **Notification channel renamed over 0.5.2.** Install 0.5.2, raise one approval so the "Approvals" channel exists, set
   its sound to silent in Android's settings, then install this build over it and open the app: Settings › Apps › AI
   Control Center › Notifications lists "Waiting for you" (not "Approvals"), still silent, with the new description; one
   channel only.
4. **Getting started's simulator step.** Fresh install, no provider: Dashboard › Getting started › step 4 says "Try it now";
   tapping it asks "Try with the simulator?"; "Use the simulator" gives every agent without a model the simulator; the step's
   button then says "Open agents". At font scale 2.0 on the razr's 360 dp screen the dialog's buttons are unclipped.
5. **"Add its price" opens that model.** With usage today on one cloud model without a price, the Dashboard's Today tile
   says "No cost shown: <model> has no price yet." and "This month: …"; "Add its price" opens that model's page, whose Price
   section takes the price; with two such models it opens the Models list.
6. **Cost per agent.** With prices for some models: Activity › Usage and cost › By agent shows "about US$…" per agent,
   "… for the models with a price" for an agent that also used an unpriced cloud model, "No price entered" for one that used
   only unpriced models, "Free: on this phone" for the simulator; TalkBack reads each row as one phrase.

## 9. Wave 3 part 1 design fixes (p6/w3afix)

| Module / class or manual step | What it proves |
|---|---|
| Manual, owner's razr at Android font size largest (about 2.0) and display size default: Dashboard, Usage and cost, Waiting for you | The bottom bar shows only the chosen section's name, shrunk to fit (no "Dash"/"Activi"), every item still read by TalkBack with its full name; avatar letters sit inside their circles; the question title "A question from Researcher" takes two lines instead of losing the name (D3A-11, D3A-13). Robolectric draws text with its own font metrics, so the real fonts must be looked at once. |
| Manual: Settings › Appearance › Calm on (WP10b's switch, part 2) with Android animations on: open an objective, tap the long objective text; open a chat and expand a tool step; scroll the Agents list | The text blocks show their end size at once and the "New agent" button keeps its label (D3A-19). `CalmMotionTest` pins the modifier; the device run checks the real animator. |
| Manual: a model served by Ollama on the phone (127.0.0.1) | Its page shows "Free on this phone" instead of price fields; a chat on it says "free on this phone"; it is never offered as the cheaper model for helpers (D3A-10). |
| Manual: run an objective on a cloud model, then clear the log (Activity › Log, or wait for pruning) | The objective header says "N model answers went to models off this phone; the details are no longer in the log." — never "Nothing has left this phone" (D3A-5). |

## WP10b (Settings: background, questions, Calm, Backup; Save and Restore a backup; the Recovery screen): manual steps for wave 5

Branch `p6/wp10b`. No new instrumented test: the data side of the backup and of Start over is covered by
`data/.../backup/BackupVectorsDeviceTest`, `BackupDeviceTest` and `data/.../recovery/StartOverDeviceTest` (device-tests-wave2.md).
On the JVM: `journeys/BackgroundCheckJourneyTest`, `BackupJourneyTest`, `RecoveryJourneyTest`, `ui/backup/BackupScreensTest`,
`ui/recovery/RecoveryScreenTest`, `ui/settings/WP10bSettingsScreenTest`.

| Module / class or manual step | What it proves |
|---|---|
| Manual (razr, Android's own screens): Settings › Working in the background with the app battery-optimised (the default after install) | The item reads "Battery saving applies to the app" with a warning icon; "Let the app run in the background" opens Android's "Let app always run in background?" dialog; after Allow and Back the item reads "Does not hold the app back" at once (the section asks Android again on return) and the Dashboard's "Android may stop agents working in the background" item is gone. |
| Manual: Android Settings › Apps › AI Control Center › Notifications off, then back in the app | "Notifications: Off" with "Turn on notifications", which opens the app's page in Android; after switching them on and coming back the item reads "Allowed". |
| Manual: the app's battery use set to "Restricted" in Android's app page; `adb shell am set-standby-bucket com.aicontrolcenter.hq rare` | "Background use is restricted" and "Android rarely lets it run", each with its button; the buttons open Android's screens; `am set-standby-bucket … active` clears the second after Check again. |
| Manual: a phone (or an emulator image) whose Settings app has no battery-optimisation request screen | The tap shows "This phone does not have that screen. Open the app's page in Android's settings instead." and the "Open the app's page in Android" button, which opens it (no crash). |
| Manual: "Keep the phone awake while agents work" off, then an objective running with the screen off; `adb shell dumpsys power \| grep aicc` | No `aicc:agents` wake lock while it is off; with it on, the lock is held while an agent works (WP2's rule 5). "Tell me when work ends" off: no "Finished: …" notification. |
| Manual: Questions from agents off, then an objective whose agent would ask | No question reaches Waiting for you; a question already waiting can still be answered. Time to answer 5 minutes: an unanswered question ends after 5 minutes with "The owner did not answer this question within 5 minutes." |
| Manual: Appearance › Calm on (Android animations on), open the Team view and the Dashboard's network card; then Android's "Remove animations" on | Nothing travels or pulses under Calm; with Android's animations off the Appearance card says "Android's animations are off on this phone, so the app is calm already…". |
| Manual: Settings › Backup › Save a backup, try a screenshot and look at the recent-apps picture | Android refuses the screenshot ("security policy") and the recent-apps picture is blank (FLAG_SECURE) on Save, on Restore and on the Recovery screen. |
| Manual: save with the generated passphrase into Downloads through the picker; time the "Locking the file with your passphrase" step | The step shows a moving bar (a clock and "Working on it…" under Calm) and Cancel; on the razr it takes seconds (the emulator's software CPU: see section 3); the rest of the app keeps responding (open another section meanwhile). Cancel during that step removes the half-made file from Downloads. Local-only mode on: saving is still allowed, with "The app you choose to keep the file may send it off the phone." |
| Manual: "Use my own passphrase" with a weak one ("summer2024"), then two different ones, then a good one twice | The plain reason under the field, "The two passphrases are not the same.", then Save is offered; the keyboard is the password keyboard (no suggestions, nothing learned, no copy). |
| Manual: fresh install (or Clear storage), then Settings › Backup › Restore from a backup, the file from Downloads or a cloud drive | A wrong passphrase: "The passphrase does not open this file…"; a file cut short (copy only part of it): "This file is damaged or incomplete…"; the right one: what it holds, every setting that changes (the Director by name), what is kept back, what is never in a backup; Restore → confirm → "Backup restored" with its notes → "Start the app again" restarts into the restored team; provider keys must be entered again. Back on the result also restarts. |
| Manual: Restore on a phone that is not fresh (own agent or chat), and while a chat answer is being written | "This backup cannot be restored now: A backup can only be restored into a fresh app…"; while a chat answer runs: "An agent is answering in a chat. Try again when it has finished." and Restore stays off. |
| Manual: Restore summary at font size largest on the razr (360 dp) with TalkBack | Each changed setting is read as one phrase ("Local-only mode: now Off, after the restore On"); nothing is cut off; every button is at least 48 dp. |
| Manual (emulator, root): remove the sealed database key of the current generation from `noBackupFilesDir/database-keys/` and start the app | The real `MainActivity` shows the Recovery screen ("The key that unlocks your data is missing on this phone.") instead of the app, with a secure window; Try again says "Looked again: the data still does not open."; typing START OVER in the hidden field (password keyboard) enables Start over, which restarts into a fresh app with the starter team; the old files are in `noBackupFilesDir/set-aside-<date>/`. Close and try later closes the app; opening it again shows the Recovery screen (the same process remembers; Try again looks again). |

## WP9 (Team view, Dashboard network card): manual steps for wave 5

| Module / class or manual step | What it proves |
|---|---|
| Manual (plan §10 step 13), simulator team: start an objective, open the Dashboard, scroll to the network card, tap "Open Team view"; then from the objective's header tap "Team view" | Both entries open the Team view: "You" at the top, the Director in the centre, the agents on a ring, "Team knowledge" along the bottom; "All current (1)" and the objective's chip; the objective's card with "Open objective" |
| Manual: while it runs, raise an approval (an Operator task that deletes a file in a shared folder) and an agent question | The agent shows a thick amber ring and "Waiting for you"; "You" shows the number; tap "You": the request and the question are listed oldest first; the question opens "A question from …" (never the Approval screen), the approval opens the Approval screen; after answering, the ring goes |
| Manual: watch the picture while the team works (animations on, Calm off) | A dot travels once along a line each time a message is sent (about 1.2 s), never loops; nothing moves when no message is sent (Android Studio's layout inspector or "Profile GPU rendering": no frames drawn while idle) |
| Manual: `adb shell settings put global animator_duration_scale 0`, return to the app; then 1 again and switch Settings › Appearance › Calm on | No dot travels in the Team view or the Dashboard card in either case; lines that carried a message in the last minute are still drawn thicker; the legend ("?" in the top bar) says motion is off |
| Manual: TalkBack on, open the Team view | The List comes first ("Picture" can still be chosen); in Picture, swiping goes You, Director, then the ring clockwise from the top, then the spaces, then the lines' signs; each member is read as "Researcher, Specialist. Working. … Connections: …"; the TalkBack actions menu offers "Answer the question" / "Open the request" and "Show messages with …"; the summary line is announced at most every 10 s and only when the numbers change |
| Manual: razr (2025) at font size largest (about 2.0) and the outer screen | The List comes first; state words wrap instead of being cut; the sheets scroll; at 360 dp and more than 8 agents the picture shows monograms only and stays readable through the sheets |
| Manual: a team of 12+ agents (add copies of the starter agents), one objective with all of them | Two rings, no avatar over another, every member a 48 dp target; with 31 agents the view opens on the List and says "The team is too big for the picture, so the list shows everyone." |
| Manual: pinch, drag, the zoom buttons and "Show the whole picture" | One finger always pans the picture (the screen does not scroll in Picture mode); zoom stays between 1x and 3x; nothing is lost off-screen |
| Manual: StrictMode log (`adb logcat -s StrictMode`) while the Team view and the Dashboard are open during an objective, once WP8 passes `computation = LocalScreenWork.current` and `memoryCount` to `DashboardViewModel` (handoff-WP9.md item 1) | No disk read on the main thread from `TeamViewModel` or `DashboardViewModel`; the Dashboard's memory count no longer reads every memory (P4) |
| Manual: Profile GPU rendering / frame stats while 8 dots travel at once (a burst of messages, for example a Director assigning five steps) | Smooth on the razr; the frame loop stops when the last dot arrives (no frames drawn afterwards). Not measured on the emulator (software rendering says nothing about the phone) |

## WP10c (Settings › Routines and folder pick-up, the Routines list and editor, "Tap to start", Folder pick-up): manual steps for wave 5

| Module / class or manual step | What it proves |
|---|---|
| Manual (plan §10 routine steps; handoff-wp17.md §5): fresh install, Settings › Routines and folder pick-up | Both switches are off; turning one on asks "Switch routines on?" / "Switch folder pick-up on?" first; Cancel leaves it off; the lines under them say how many routines and rules exist and "None runs while routines are off." |
| Manual, Android 14 (emulator image or razr) right after installing: Tools › Routines: objectives that start on a schedule | "Routines may start late" with "Allow alarms and reminders" (exact alarms are denied by default on Android 14+); the button opens Android's "Alarms & reminders" page for AI Control Center only (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, `package:com.aicontrolcenter.hq`); after allowing and coming back the card reads "Alarms and reminders: allowed" without reopening the screen. On the aosp_atd image (no Settings app) the tap shows "Android's "Alarms and reminders" page could not be opened on this phone…" and nothing crashes |
| Manual: make a routine "every day" two minutes ahead (hour and minute buttons), master switch on, "Alarms and reminders" allowed; screen off; `adb shell dumpsys alarm \| grep aicontrolcenter` | The editor's "With this schedule it next runs today at HH:MM" matches; the list shows "Next: today at HH:MM"; one alarm is set; at the time the objective starts and the routine's history shows "Started" with "Open the objective" (WP17's device step 2, now through the screens) |
| Manual: the same with "Alarms and reminders" taken away, app in the background | At the time a "Tap to start" notification comes (no buttons); tapping it opens the app; on any screen but the Dashboard the app goes to "Routines waiting to start" by itself, on the Dashboard the banner shows; nothing started before Start and "Start now"; after it, "Started: the team is working on …" with "Open the objective" (on the "Routines waiting to start" screen) |
| Manual: "Not now" on a waiting run | The run is "Missed" in the history ("You chose not to start it."); no objective started; the step then says "Nothing is waiting to start" |
| Manual: a routine whose objective fails three times (e.g. a Director without a model), master switch on | The list shows "Paused" with the note; the editor shows "It switched itself off" and "Switch it on again", which switches it on, clears the failures and sets the next time from now |
| Manual: phone time zone set to Europe/Berlin, a routine at 02:30 | The editor says "On Sunday 28 March the clocks go forward: 02:30 does not happen that night, so it runs at 03:30." (the date of the next change); 07:30 shows no such line |
| Manual: "May use other apps' screens" in the editor | Off by default; switching it on asks "Let this routine use other apps' screens?" (the confirm button refuses touches while another app draws over it); the warning "Screen actions work only while the phone's screen is on and unlocked…" shows while it is on |
| Manual: Tools › Folders › share a folder of the phone's storage, switch Read-only on, "Pick up new files"; choose a memory space; Make the rule. Put a .txt and a .jpg in the folder with the Files app; `adb shell cmd jobscheduler run -f com.aicontrolcenter.hq <upkeep job id>` (WP2's job, charging and idle not needed with -f) | Settings › Open folder pick-up: "From "<folder>" into "<space>"", "Last looked today at …", "Picked up 1 file."; the .txt is listed "Picked up …", a file that was in the folder before the rule "Left out: it was already in the folder when the rule was switched on."; the .jpg is not listed (only text files); the memory is in the space with "Picked up from <folder>" |
| Manual: Tools › Folders: switch Read-only off for that folder | Tools says the pick-up rule was switched off; Folder pick-up shows "Stopped" with "Nothing was picked up: agents may change this folder again…"; "Switch on again" answers "Make this folder read-only for agents first…" until Read-only is on again |
| Manual: New rule with a folder shared through the picker's Downloads entry while a writable folder sits in Download; and with a cloud drive's folder (another app's storage) beside a writable phone folder | The editor shows the Downloads-entry sentence (FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE) and the other-app sentence (FOLDER_OTHER_APP_NEAR_WRITABLE) as they are, with "Open shared folders"; Make the rule stays off |
| Manual: razr (2025) at font size largest (about 2.0), outer screen, TalkBack | The time and "How often" labels sit above their buttons; day chips read "Monday… selected"; the schedule choices and the timeout choices are read as radio buttons; each routine card is read as one item with its status word; folders and spaces are radio buttons; nothing is cut off; targets 48 dp |

## WP16 (receiving shares from another app, "Save to memory"; Tier B, rule 34): manual steps for wave 5

Branch `p6/wp16`. These replace section 6 item 3 (the share receiver stub) and are plan §10 step 17 in full. Package
`com.aicontrolcenter.hq`, component `com.aicontrolcenter.hq/com.aicontrolcenter.app.share.ShareReceiverActivity`.

| Module / class or manual step | What it proves |
|---|---|
| Manual (plan §10 step 17): `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "Tuesday: the boiler engineer comes at nine." --es android.intent.extra.SUBJECT "Screen recorder export" -n com.aicontrolcenter.hq/com.aicontrolcenter.app.share.ShareReceiverActivity`; look at Memory › Explorer before and after; then pick "Team knowledge" and Save | "Save to memory" shows the text's start, "7 words · 43 bytes", the Title "Screen recorder export", the spaces (each with who can read it) and "Workspace (as a file)", the line "Storing is free. Only what agents recall is sent to a model. A cloud model would receive this text; local-only mode and space access keep it on the phone."; Save is off until a place is picked; nothing new in Memory before Save; after Save "Saved in “Team knowledge” as 1 memory, marked as shared from another app."; the memory's detail says "Imported from outside this app", source "Shared from another app", tag `from-another-app`; the activity log has "Saved what another app shared" and the memory's own "Memory saved" line, never the text |
| Manual: the same `am start`, then Back; and again, then "Don't save" | The screen closes; nothing stored |
| Manual: `adb shell am start -a android.intent.action.VIEW -t text/plain --es android.intent.extra.TEXT x -n …/.share.ShareReceiverActivity` and `… -a android.intent.action.SEND -t image/png …` | The activity closes at once without a screen; nothing stored. `adb shell cmd package query-activities -a android.intent.action.SEND -t image/png \| grep aicontrolcenter`: empty (the share sheet never offers the app for pictures) |
| Manual: from the Files app (or the owner's own recorder app once its Export button exists), share one .txt, then select three .txt files and one .jpg and share them together | One file: its name is the title. Several: "3 files · about N words · N KB", each with its name, size and start; the .jpg is never offered (or, if the sharing app labels the whole set text/*, is listed "“photo.jpg” was left out: it is not a text file."); Workspace (as a file) → Save: the files are in Tools › Workspace › "From other apps" with their own names ("notes (2).txt" when the name is taken) |
| Manual: share a text file larger than 1 MB, and 12 small files at once | "“big.txt” was left out: it is larger than 1 MB, the most one file may be."; "2 more files were left out: at most 10 texts or files can be saved at once." |
| Manual: share a long text (100,000+ characters, e.g. a long export) into a space | "Saves 6 memories in “…”. Long text is split into parts of up to 20,000 characters, one memory each."; the progress bar and "Saving 3 of 6… Stopping takes back what was saved so far."; "Stop saving" (or Back) during the save leaves no part in Memory; a full save lists "… (part 1 of 6)" to "(part 6 of 6)" |
| Manual: Settings › Local-only mode on, then share text | The share works; the screen adds "Local-only mode is on: agents use only models on this phone, so what they recall stays on it." |
| Manual: Recovery path: `run-as com.aicontrolcenter.hq` move the sealed database key away (as plan §10 step 20), then share text to the app | The Recovery screen shows instead of the share screen; nothing stored (and the app's own screen shows Recovery as before) |
| Manual: TalkBack on; razr (2025) at font size largest (about 2.0); landscape | The places are read as radio buttons ("Memory space “Team knowledge”, Researcher can read it, radio button, not selected"); Save is read as "Save, button, disabled" until a place is picked; at usual font sizes Save and "Don't save" stay at the bottom of the screen; at the largest font size they come at the end of the list; nothing is cut off; targets 48 dp |
| Manual: with the accessibility service of the app on (screen control), have an agent try to operate the share screen (or `uiautomator dump` it) | Screen control does not act on the app's own windows (ConsequentialControls, own package); Save carries "sensitive" for accessibility on Android 14+ |
| Manual: StrictMode log (`adb logcat -s StrictMode`) while sharing a 1 MB file | No disk read of the shared file on the main thread (the files are read on the IO pool, the parts worked out on the background pool) |
| Manual: share from an app that offers a `file:///…` address (an old app, or `adb shell am start -a android.intent.action.SEND -t text/plain --eu android.intent.extra.STREAM file:///data/data/com.aicontrolcenter.hq/shared_prefs/x.xml -n …`) and a `content://com.aicontrolcenter.hq.workspace/…` address | "A file was left out: this app only reads files that Android's share screen hands over." / "A file was left out: it points into this app's own storage, which a share cannot hand over."; neither is read |

## 10. Added by WP8 at the part-2 merge (wave 3; branch claude/ai-control-center-architecture-wjdfww)

| Module / class or manual step | What it proves |
|---|---|
| Manual: Android Settings › Apps › AI Control Center › Notifications off; open the app; on the Dashboard tap "Notifications are off" (under "On this phone") | Settings opens scrolled so "Working in the background" is at the top of the screen, with "Turn on notifications"; tapping it opens Android's own page; coming back to Settings later from the navigation starts at the top and does not jump again (handoff-WP10b.md 3.1) |
| Manual: Tools › Routines with routines off › "Open Settings"; the same from Folder pick-up | Settings opens at "Routines and folder pick-up" with the two switches in view |
| Manual: New objective with battery saving on (the background warning) › "Open Settings" | Settings opens at "Working in the background" |
| Manual (rooted emulator, as plan §10 step 20): move the sealed database key away; open the app (Recovery shows); "Close and try later"; put the key back; open the app again from the launcher without killing the process (`adb shell dumpsys activity processes \| grep aicontrolcenter` still lists it) | The app looks again by itself and opens normally; the Recovery screen's line says "When you open the app again, it looks again by itself."; rotating while the Recovery screen shows does not look again (handoff-WP10b.md 3.3; JVM: `health/ReopenRechecksTest`) |
| Manual: StrictMode log (`adb logcat -s StrictMode`) while the Dashboard is open and an objective runs | No main-thread work from the Dashboard's state (it is built on the background pool) and the memory tile follows the store's count (handoff-WP9.md item 1; JVM: `journeys/DashboardWiringJourneyTest`) |
| Manual: a routine waiting to start, app opened from the launcher (Dashboard): Start › "Start now" › "Open the objective" | The objective that started opens ("Started by routine “…”"); Back to the Dashboard: the message is gone |
| Manual: Models › a model › Price: no currency field, "Prices are in US dollars (US$) …"; on a 0.6.0 install restored from a backup that holds a price in € (or one typed before), open that model | "This price is in another currency. Enter it in US$ to count it. Stored now: €… in, €… out per million tokens."; empty fields; Activity › Usage and cost shows the same sentence on the model's row and leaves its tokens out of the money; the Today tile and the money limit never add it; after typing the price again and Save, the model's cost is shown in US$ (D3A-8) |
| Manual: a provider waiting to try again (xAI answering 429) | The objective page and the Team view say "xAI is not answering …" (never "XAI") |

## 11. Added by the wave-3 security review (adversarial, all of wave 3)

| Module / class or manual step | What it proves |
|---|---|
| Manual: save a backup to Files › Downloads; then Settings › Backup › Save a backup › "Choose where to save it", tap the earlier `.aiccbackup` file in Android's picker, confirm "Overwrite …?", tick, and tap Cancel › Stop while "Locking the backup" shows (or save while a restore runs) | Whether the earlier backup file is still there afterwards. At 88060fa it is deleted (`DocumentsContract.deleteDocument` on the picked, existing document) although the screen says "Stopped. Nothing was saved."; JVM reproduction: `ui/backup/SecProbeBackupOverwriteTest` (copy in scratchpad/phase6/review3-security-probes/). After the fix it must still be there, unchanged |
| Manual: `adb shell am start -n com.aicontrolcenter.hq/.share.ShareReceiverActivity -a android.intent.action.SEND_MULTIPLE -t text/plain` with an `EXTRA_TEXT` list of a few thousand short texts (a small test app is easiest; `am` cannot send a CharSequence list) | How many texts one share takes, and whether the share screen stays responsive (frame timing, no ANR). At 88060fa every text is taken and composed at once (JVM: `share/SecProbeShareCapsTest`, `share/SecProbeShareScreenCountTest`, copies in scratchpad/phase6/review3-security-probes/) |

## 12. Added by the wave-3 integration review (adversarial, all of wave 3)

| Module / class or manual step | What it proves |
|---|---|
| Manual: Developer options › "Don't keep activities" on (or `adb shell am kill com.aicontrolcenter.hq` with the app in the background); type a title and a long text in Tools › Routines › New routine, a limit in Settings › Daily limits, a note in an objective's "Write to the team", an answer on a question's screen and a New objective; switch to another app and back | What each form keeps after Android ended the process. At 88060fa none of the new forms keeps its text (their `TextFieldState`s live in view models without a saved state; only the agent editor keeps a draft); a rotation keeps every one (JVM by code reading only: no `SavedStateHandle` outside `AgentEditorScreen`) |
| Manual: a daily limit of 2 answers on a cloud model, an objective that pauses at it, then Resume twice without raising the limit; read the objective's header | The header's "model answers" and "In all, N model answers came from models off this phone" against Dashboard › Today and Settings › Daily limits. At 88060fa the header counts every call the limit refused (nothing sent) as an answer, and says the log "no longer holds the rest" (JVM: scratchpad/phase6/review3-integ-probes ProbeAnswersAgreeTest, ProbeResumeAtLimitTest) |
| Manual: Activity › Usage and cost left open on "Today" across local midnight while an agent works | Whether "Today's limits count …" moves to the new day as the Today tile does. At 88060fa it keeps the old day until the screen is left (JVM: ProbeUsageMidnightTest) |
