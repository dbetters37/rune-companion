# Hand-off from the wave-2b review fixes (branch p6/w2bfix, merged into claude/ai-control-center-architecture-wjdfww)

Fixed: SEC-W2B-1, SEC-W2B-2, AND-W2B-1, AND-W2B-2, AND-W2B-3, AND-W2B-4, AND-W2B-5 (one commit each, each with a test that
failed before). Device checks: device-tests-wave2-recheck.md. Limits left: wave2b-minors-left.md.

## What changed that wave 3 builds on

| Where | Change | For |
|---|---|---|
| framework `agents/DefaultAgentActivityMonitor` | Per run: `runStarted(agentId, detail, taskId)` returns a `DefaultAgentActivityMonitor.Run`; `report(run, activity, detail)` and `runEnded(run)` take it. `report(agentId, activity, detail, taskId)` stays for code speaking for a run by its task (the team desk); it is ignored when the agent has no live run on that task. The agent shows the most active of its live runs (WORKING > WAITING_FOR_APPROVAL > NEEDS_ATTENTION > WAITING_FOR_AGENT), the latest report among equals; when a run ends the others' last reports show. | Anyone driving the monitor (only `AgentRunner` and `TeamDesk` today). The Dashboard and network screens read `states` as before. |
| app `work/ObjectiveWorkService` | Uses the graph at once only when it is built; otherwise waits for `AiControlCenterApp.gate`, does what it was started for once the data opened, and stops while it cannot be opened. Its scope has the process's exception handler; its log lines cannot fail it. | WP8 (no change needed), WP10 (Recovery). |
| app `AiControlCenterApp` | `internal fun installGate(gate: AppGate)` for tests (replaces the gate without a graph). | Tests. |
| app `work/ApprovalNotifications` | `ApprovalNotifier(..., reminderAlarm: ReminderAlarm = ReminderAlarm.Android(context), wakes: Flow<Long> = WakeSignals.signals)`: reminders by wall-clock time, one RTC_WAKEUP alarm at the next (`WakeReceiver.ACTION_REMIND`, request code 4402). | WP8 (GraphServices unchanged). |
| app `work/WakeReceiver` | Takes `ACTION_REMIND` as well (only for a graph that exists; holds 30 s; pokes `WakeSignals`). No manifest change (same receiver, no filter). | ManifestRulesTest unchanged. |
| data `backup/RoomOwnerBackup` | The restore writes the merged settings after Room's commit (NonCancellable). A failed settings write leaves the restore done, with no setting changes in its report and the note `BackupTexts.SETTINGS_NOT_RESTORED`. | WP10 Restore screen: show the note. |
| framework `agents/DefaultAgentManager.ensureDefaults` | At every start (also when seeded): a designated Director that no longer exists is replaced by the first Director agent, or none; logged as "Director designated". | WP8 / WP9 (nothing to do). |
| core `operator/FolderTrees` | Places trees of the phone's storage: storage provider by path ("home:" = Documents; case ignored), downloads provider "raw:" by path, "downloads"/"msf:<n>" as "somewhere in a Download folder" (unknown against folders in or around Download, apart from others), other ids of these providers as unknown; other providers stay apart. | WP10 Folders and Folder pick-up screens: the refusals now also come for Downloads-entry folders. |
| device `operator/files/SafSharedFolders` | A write is refused when overlap with a read-only folder is true or unknown; the unknown case has its own sentence ("Android does not tell this app where <folder> is ..."). | WP10. |
| app `scripts/RunHost` | Nothing is served once the run's workspace time is up (the RunHost ends); a call's work holds the run until it has really ended; the 20-file cap is checked and counted where the file is made; `RunHost.IN_FLIGHT` for a write given up inside the workspace. | WP12 (attack list). |
| framework `routines/RoutineClock` | `realigned` also moves a due time that has come when it is not one of the routine's local due times in the zone of now; `isDueTime(schedule, at)`. | WP8 (the optional TIMEZONE_CHANGED row of handoff-wave2b-merge.md §3 is now only a saving). |

No architecture test list had to grow. Shared files touched: `AiControlCenterApp.kt` (installGate), core `FolderTrees.kt`,
device `SafSharedFolders.kt`, framework `DefaultAgentActivityMonitor.kt`, `AgentRunner.kt`, `DefaultAgentManager.kt` (each for its
finding).

## Final checks

- p6/w2bfix (from 46947c5, 7 commits): whole suite `./tools/gw --offline test --continue`: 3,390 tests, 0 failed (3,363 before:
  +27 new tests). `:app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest :device:assembleDebugAndroidTest`:
  BUILD SUCCESSFUL.
- Merged into claude/ai-control-center-architecture-wjdfww (head was f75a592, the wave-2 device gate's commit on 46947c5) as
  2757d1f "Wave 2b: review fixes", no conflicts. There: every test task named with `--rerun` (all nine executed): 3,390 tests,
  0 failed; the four assemble tasks: BUILD SUCCESSFUL. Not pushed. Worktree /home/user/p6-w2bfix left in place.

## Added by the wave-2b residual fixes (branch p6/w2bres, two rounds): screen work for WP10

SEC-W2B-2-R and B1. A folder from another app's storage provider (a cloud drive, a file manager: any provider but the
phone's storage, downloads and media providers) counts as apart from the folders on the phone's storage for changes
(core `FolderTrees.overlap` = false), because Android does not tell whether that app also shows the phone's own files, and
counting it as overlapping would stop every change in such folders whenever any folder is read-only. Exception: an app
whose tree ids are paths on the phone's storage ("/storage/emulated/0/...", "/sdcard/...", as many file managers give) is
placed by that path and compared like the phone's own folders. Folder pick-up is stricter (`FolderTrees.mayShareFiles`, see
below). Instead of blocking changes, the Folders screen warns, on both sides:

| Where | What | WP10 |
|---|---|---|
| core `OwnerFolders.fromOtherAppStorage(folder: SharedFolder): Boolean` (default method; every implementation has it) | True for any folder, read-only or not, whose tree comes from another app's storage provider. | Folders tab (`FoldersViewModel`/`FolderUi`): add a field, e.g. `otherAppNote: String?`, set for each folder where `folders.fromOtherAppStorage(folder)` is true, to `OwnerFolders.otherAppStorageNote(appName, folder.readOnly)`, and show it under the folder's name (warning style, like the read-only explanation). Recompute it when the owner switches read-only: the sentence changes. |
| core `OwnerFolders.otherAppStorageNote(appName: String?, readOnly: Boolean): String` (companion) | Writable: "This folder comes from <app>. If <app> shows your phone's own files, agents could change files there even in folders you made read-only. Make this folder read-only, or share folders from the phone's storage instead." Read-only: "This folder comes from <app>. If <app> shows your phone's own files, agents could still change files in it through another shared folder that they may change. If it does, share this folder from the phone's storage instead." With `null` (or a blank name): "another app" / "that app". The name is cut to one line of at most 60 characters. | Show it exactly as returned. |
| core `FolderTrees.otherAppProvider(treeUri: String): String?` | The provider's authority (without a "<n>@" profile prefix), or null for the system providers and non-tree URIs. | For the app name: `packageManager.resolveContentProvider(authority, 0)?.applicationInfo?.loadLabel(packageManager)` (or the provider's own label), on a background dispatcher, through the phone layer's one-line bound; null when Android does not tell it (package visibility, a provider that went away). Not on the main thread. |
| core `PickUpSafety.FOLDER_OTHER_APP_NEAR_WRITABLE`, `RUN_FOLDER_OTHER_APP_NEAR_WRITABLE` | New refusal and last-result sentences of folder pick-up: the pick-up folder, or a folder agents may change, comes from another app and the other folder from somewhere else ("this app cannot tell whether that app shows the same files as the other folder"). Rule creation and switch-on are refused, every run switches the rule off, and sharing or making writable such a folder switches it off (`PickUpSafety.runProblemBeside`). | Folder pick-up screen: nothing new, it shows the refusal and `lastResult` as before. `PickUpSafety.switchesRuleOff(sentence)` says which last results mean "switched off". |
| device `SafSharedFolders` | The refusal for a folder whose place cannot be told now names the folder Android does not place. A folder from the picker's Downloads entry: "Android does not tell this app where <folder> is, because it was shared through the folder picker's Downloads entry. ... Sharing <folder> through the phone's storage entry instead lets the app tell." Otherwise (another profile, the media list, another app): "This app cannot tell whether <a> holds, or is inside, the read-only folder <b> (one of them comes from another profile on this phone, the media list or another app), so nothing can be changed in it." | Nothing; agents and the activity log show it. |

Tests WP10 writes: a view-model test with a writable folder of a third-party provider (the writable note, with and without
an app name), the same folder read-only (the read-only note), and folders of the phone's storage and the Downloads entry
(no note).
The sentences are also in docs/LIMITATIONS.md section 6 ("Read-only folders and other apps' folders", "Folders from other
apps that name their folders their own way", "Folder pick-up is stricter").

Also from p6/w2bres, nothing for WP10 to do: `raw:` paths of the downloads provider under `/storage/self/primary` are the
phone's own storage; `raw:/storage/emulated/<n>` of another profile, `content://<n>@<system provider>/...` trees and the
media provider's trees are placed conservatively (unknown, counted as overlapping, where they could be the same place). The
Downloads root ("downloads", a folder only on Android 9 and 10) may hold downloads saved anywhere, so it is unknown against
every folder of the phone's storage (B1-DL; LIMITATIONS "The Downloads top folder on Android 9 and 10").
