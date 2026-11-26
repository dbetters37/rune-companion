# Device tests to run: wave 2 (2a and 2b)

Collected by the wave-2b merger from handoff-wp2.md §3, handoff-wp17.md §5, handoff-wp3.md §3, handoff-wp6.md §4 (wave 2a added
no instrumented test: handoff-wave2a-merge.md §4). All instrumented tests below compile on the merged branch
(`:app:assembleDebugAndroidTest`, `:data:assembleDebugAndroidTest`, `:device:assembleDebugAndroidTest`); none was run by a builder
or the merger (no emulator for builders). Run them on the emulator at the wave-2 device gate, with `export TMPDIR=/tmp` and
`./tools/gw --offline` only.

## 1. Instrumented tests (12 tests in 4 classes)

| Module | Class | Tests | What it proves | From |
|---|---|---|---|---|
| data | `com.aicontrolcenter.data.recovery.StartOverDeviceTest` | 3 | With the real AndroidKeyStore and SQLCipher: a removed sealed key is reported as missing and no key is invented (`sealedKeyRemovedIsReportedAsMissingAndNoKeyIsInvented`); a damaged sealed key gives KEY_DAMAGED, Start over gives a fresh, empty generation 2 under `aicc.database-key.v2`, and the set-aside copy still opens with generation 1's intact key, whose keystore entry stayed (`startOverWithTheRealKeystoreAndSqlCipherGivesAFreshStartAndKeepsTheOldCopyOpenable`); data, sealed key and vault written exactly as 0.5.2 wrote them open unchanged after the upgrade, with no generation note written and the sealed key not replaced (`generationOneDataWrittenThe052WayStillOpensAfterTheUpgrade`). Uses its own database name, generation note and settings file and removes what it made (generation-2 keystore entries included). | WP2 |
| data | `com.aicontrolcenter.data.backup.BackupVectorsDeviceTest` | 5 | The frozen backup format's section-4 vectors on the phone's own crypto provider: RFC 7914 PBKDF2, RFC 5869 HKDF, the non-ASCII passphrase vector (the phone's Unicode tables), the preamble / key check / digest and chunk-0 vectors (K_check and K_enc pinned through them, the phone's AES-GCM); and `BackupKeys.derive(…, 600_000)` on `Dispatchers.Default` takes at most 15 s (logcat tag `BackupVectors` gives the time). | WP3 |
| data | `com.aicontrolcenter.data.backup.BackupDeviceTest` | 1 | A SQLCipher database (device keystore) saved through a `file://` URI with the content resolver at 600,000 iterations and restored into a fresh SQLCipher database; secrets are not readable in the file; logcat tag `BackupDevice` gives save and restore times. Cleans up its databases, sealed keys, settings files and the file. | WP3 |
| app | `com.aicontrolcenter.app.scripts.ScriptProcessDeviceTest` | 6 | Scripts in their own isolated process (logcat tag `ScriptProcessDeviceTest` has every time; bounds for the emulator: bind 60 s, getting ready 90 s): `onePlusOneRunsInAnIsolatedProcessAndAWarmRunKeepsTheDefaultLimit` (1+1 in a process whose pid is not the app's, no fallback note; a warm 1+1 within the default 5 s limit, D2; a regular expression, F-B); `stuckScriptThreeTimesThenOnePlusOneWithinTheRestartBackOff` (three stuck runs each Stopped within the limit + 6 s, the app's pid unchanged, then 1+1 at once, Q6); `aFourMegabyteReadAndALargeRoundTrip` (a 4 MB workspace read; 200,000 characters in, 100,000 out, Q7); `aScriptMemoryHogEndsOnlyTheScriptAndAddsNoExitOfTheApp` (a script filling memory is stopped, the next run works, no main-process exit record added); `theScriptProcessIsIsolatedAndCannotReachTheAppsData` (through the debug probe: isolated, another pid and uid, process name `…:scripts…`, the app's data folder cannot be listed, a file in `filesDir` cannot be read, no network); `anOutOfMemoryErrorEndsTheScriptProcessQuietly` (a Java out-of-memory error in the probe ends its process, death seen within 60 s, no main-process exit record added on Android 11+). The stuck-script test's loop is bounded (machine-safety rule): on a fast phone it may end within the grace; it is built for the emulator. | WP6 |

Commands:

```
export TMPDIR=/tmp
./tools/gw --offline :data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.aicontrolcenter.data.recovery.StartOverDeviceTest
./tools/gw --offline :data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.aicontrolcenter.data.backup
./tools/gw --offline :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.aicontrolcenter.app.scripts.ScriptProcessDeviceTest
```

Earlier instrumented tests still in the tree (run them too if the gate runs whole modules): data `db/Encrypted*Test` (5 classes,
`EncryptedMigrationTest` included), data `security/AndroidKeystoreVaultCipherTest`, device
`operator/commands/ShellLeftoversDeviceTest`.

## 2. Manual device checks (no instrumented test)

### WP17: routines and folder pick-up (handoff-wp17.md §5; app/src/androidTest was WP6's, so WP17 wrote none)

1. Install; `adb shell dumpsys package com.aicontrolcenter.hq | grep -A3 RoutineAlarmReceiver` shows it disabled; no alarm in
   `adb shell dumpsys alarm | grep aicontrolcenter`. Proves: receivers stay disabled while no routine exists, both master
   switches off after installing.
2. (Needs WP10's Routines screen or a debug hook) a routine due in 2 minutes, routines switched on: receivers enabled, one alarm
   listed (exact only after "Alarms and reminders" is allowed on Android 14+). Screen off, `adb shell dumpsys deviceidle
   force-idle`: the objective starts within a minute of its time; its options say "started by routine", screens off unless
   ticked. Proves: the exact alarm → `RoutineAlarmReceiver` → `ObjectiveWorkService` (`ACTION_RUN_DUE_ROUTINES`) →
   `RoutinesAssembly.runDue()` path (WP2 + WP17 joined at this merge).
3. "Alarms and reminders" off: the alarm is inexact; when it fires, "A routine is waiting to start" appears (no buttons) and
   nothing starts, even with the battery exemption; tapping it opens the app; the banner shows (once WP8 places it on the
   Dashboard); Start asks once more; nothing started before that. Proves rule 23 / F12.
4. `adb reboot` with a routine: after boot `dumpsys alarm` lists the alarm again; nothing started at boot. Proves `BootReceiver`
   only re-arms.
5. Folder pick-up (needs WP10's screens, and works on a phone only since the wave-2b merger wired the folder reader): share a
   folder, make it read-only, make a rule, switch folder pick-up on, put a .txt file in it, then
   `adb shell cmd jobscheduler run -f com.aicontrolcenter.hq 6001` (WP2's upkeep job; it skips until a start of this version
   reached "ok"): the memory appears in the space as "Imported from outside this app", source "Picked up from <folder>". Make
   the folder writable: the rule switches off with the sentence.

### WP2: background work (handoff-wp2.md §3, "worth a look"; also plan §10, wave 5)

- `adb shell dumpsys power | grep aicc` while an objective plans with the screen off (wake lock "aicc:agents" held) and after
  Pause (released).
- `adb shell dumpsys alarm | grep com.aicontrolcenter`: one wake alarm during a long provider wait.
- `adb shell cmd jobscheduler run -f com.aicontrolcenter.hq 6001`: the upkeep job (logs nothing on success; skips until a start
  of this version reached "ok").
- The "Results" notification after an objective finishes with the app in the background.
- A chat answer with the app in the background: "<agent> is answering · Stop".
