# Device tests to run again: wave-2b review fixes (branch p6/w2bfix, merged as "Wave 2b: review fixes")

The fixes of SEC-W2B-1/2 and AND-W2B-1..5 were made and tested on the JVM and Robolectric (SDK 35) only; no emulator was
started. Every instrumented test below compiles on the merged branch. Run with `export TMPDIR=/tmp` and `./tools/gw --offline`
only. Everything in device-tests-wave2.md still applies; this list says what the fixes touched and what to look at again.

## 1. Instrumented tests to run again (changed code under them)

| Module | Class | Why again |
|---|---|---|
| app | `com.aicontrolcenter.app.scripts.ScriptProcessDeviceTest` (6) | SEC-W2B-1 rewrote `RunHost` (the binder the isolated process calls): deadline checked before and inside every call, a call's work holds the run until it has really ended, a time-out ends the run's workspace. The real binder and real pipes (`ParcelFileDescriptor.createPipe`) are only on a device. |
| data | `com.aicontrolcenter.data.backup.BackupDeviceTest` (1) | AND-W2B-4 moved the settings write of a restore after Room's commit. The SQLCipher round trip should still restore the Director and the settings. |

```
export TMPDIR=/tmp
./tools/gw --offline :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.aicontrolcenter.app.scripts.ScriptProcessDeviceTest
./tools/gw --offline :data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.aicontrolcenter.data.backup.BackupDeviceTest
```

## 2. Manual checks for the device-only parts of the fixes

1. **SEC-W2B-2, folder ids of the Downloads entry (not checked on a device).** In the system folder picker, pick a folder inside
   Download through the "Downloads" entry (Android 10+: a subfolder; Android 9-10: the Downloads root itself), and the same or an
   inner folder through the phone's storage entry. Log or inspect the stored tree URIs (`adb shell run-as com.aicontrolcenter.hq`
   is not possible on a release build; use a debug build and the Tools > Folders list, or logcat of the picker result). Expected
   forms: `content://com.android.providers.downloads.documents/tree/msf%3A<n>` or `.../tree/downloads`, and
   `content://com.android.externalstorage.documents/tree/primary%3ADownload%2F...`. Then: a pick-up rule on the inner folder is
   refused ("... inside another shared folder agents may change ...") while the Downloads one is writable; a write into the
   Downloads one is refused while the inner one is read-only ("Android does not tell this app where ..."). If the picker returns
   a `raw:` id instead, the exact path comparison applies (also covered).
2. **AND-W2B-1, wake lock per run.** An agent's task waits for an approval while the owner chats with the same agent, screen
   off: `adb shell dumpsys power | grep aicc` shows "aicc:agents" while the chat answer is written and not after it ends
   (the task still waiting). The notification no longer says "Keeping the phone awake while agents work" then.
3. **AND-W2B-3, approval reminder while asleep.** A question with the default 20-minute window, screen off, then
   `adb shell dumpsys deviceidle force-idle`: `adb shell dumpsys alarm | grep -A3 REMIND_APPROVALS` lists one RTC_WAKEUP alarm at
   15 minutes after the question (exact only when "Alarms and reminders" is allowed on Android 12+, else allow-while-idle). The
   reminder notification ("... is still waiting. About 5 minutes are left") appears near that time without touching the phone.
4. **AND-W2B-5, time-zone change.** A daily routine at a time 2 hours ahead, routines on; change the zone so that the stored
   instant comes earlier in local time (Settings > System > Date & time with the automatic zone off, or on the emulator
   `adb root && adb shell setprop persist.sys.timezone America/New_York`). At the
   old instant the phone wakes once and starts nothing; `dumpsys alarm` then lists the routine alarm at the routine's local time
   in the new zone, where the objective starts.
5. **AND-W2B-2, alarm while the data cannot be opened.** Hard to set up on a phone (needs a damaged sealed key: the
   `StartOverDeviceTest` setup). With a routine armed and the key damaged, let the alarm fire: no crash in
   `adb logcat -b crash`, the work notification goes away, and opening the app shows the Recovery screen.
