# Phase 6 plan: dependable and affordable every day (recovery, background work, monitoring, teamwork, team view)

Project: "AI Control Center", /home/user/rune-companion, branch claude/ai-control-center-architecture-wjdfww,
**HEAD 9c739d5 (0.5.2, application id `com.aicontrolcenter.hq`, versionCode 8, schema 5)** [V: git log;
app/build.gradle.kts:13, :16-17]. Phase 6 is the last planned phase (docs/ARCHITECTURE.md:364). Release: **0.6.0,
versionCode 9**. 0.6.0 installs over 0.5.2 only; 0.5.0 and 0.5.1 (`com.aicontrolcenter.app`) are another app to Android
(docs/STATUS.md:422-430).

**Revision 2.** Rewritten after the security critique (scratchpad/phase6/critique-security.md, S1-S29, N1-N13) and
the feasibility critique (scratchpad/phase6/critique-feasibility.md, F1-F30), and with two owner requests that arrived
after revision 1: the typing bug (scratchpad/phase6/owner-typing-bug.md, task #42) and model cost plus importing another
app's exports (scratchpad/phase6/owner-cost-and-import.md, task #43). What changed, finding by finding, is in the
"Critique log" at the end. In short: rebased on 0.5.2; scope cut into three tiers; cost control made a first-class goal;
13 rules added; wave 1 split into five packages so that wave 2 works in separate files with its own wiring; the
Android mechanisms the critics found underspecified are now specified.

**OWNER DECISIONS (2026-11-25; they override anything below that says otherwise).**
- **Q1 = "Yes, both".** Routines (scheduled objectives) AND automatic folder pick-up are IN SCOPE, as package **WP17**
  in wave 2 (size L), specified in Appendix A, **Tier B** (they act while nobody watches: they ship switched on only
  if no critical or major finding is open on them after wave 4 round 2; otherwise their switch is forced off and their
  entry points hidden). Consequences: WP1a adds the routine and folder-pick-up contracts (core `Routine`,
  `RoutineSchedule`, `RoutineRepository`, owner-only `OwnerRoutines`, and an owner-only folder pick-up rule contract
  with its repository), with in-memory fakes and contract suites; WP1b adds the schema-6 tables `routines`,
  `routine_runs` and the pick-up tables (rule + imported-file content hashes for de-duplication) in the same single
  migration; WP1d pre-inserts the manifest entries WP17 needs (exact-alarm permission `SCHEDULE_EXACT_ALARM`, the
  alarm receiver and the boot receiver, both disabled until a routine exists) and a `di/wiring/RoutinesWiring.kt`
  stub; WP17 owns framework/routines/*, app/routines/*, its wiring file, and the matching tests. Wave-2 merge order:
  WP4, WP15, WP7, WP5, WP2, **WP17**, WP3, WP6. WP10 (wave 3) adds the Routines and folder pick-up owner screens;
  section 10 gains routine and pick-up steps. All Appendix A limits stay (master switch off after installing, at most
  10 routines, at least 1 h apart, no overlap, pause after 3 failures, daily limits and local-only checked at run
  time, screen actions refused unless ticked per routine, the broker unchanged; pick-up: `text/*` only, content-hash
  de-duplication, at most 5 MB a day, nothing sent to a model by importing, into one owner-chosen space).
- **Q2 = "Yes, $1 a day" (the recommended default).** Daily limits on by default: 200 model answers, 500,000 tokens,
  and US$1.00 of estimated cost once the owner has entered a price; whichever comes first; a running objective pauses,
  limits raised in Settings.
- **Q3 = "Yes".** Agents may ask the owner questions (C1), 20-minute answer window, Tier B as planned (ships switched
  on if review round 2 leaves nothing critical or major open on it).

Read first: docs/ARCHITECTURE.md, docs/STATUS.md (every "Known rough edges" list; "Next: Phase 6" at :521-527),
docs/LIMITATIONS.md, scratchpad/phase5-plan.md (all its rules stay in force), the four reader reports in
scratchpad/phase6/ (reader-background-recovery.md A1-A7, R1-R10, G1-G16; reader-performance.md P1-P10;
reader-network-collaboration.md N1-N11, C1-C7; reader-deferred.md D1-D48), the two critiques and the two owner notes.

Evidence tags: **[V]** read in the code or an artifact at file:line (by a reader, a critic, or re-checked for this
revision); **[D]** official Android documentation (URL and Android version given); **[K]** platform behaviour known
from AOSP but not confirmed here; **[A]** assumption, to be confirmed where the plan says so. Paths: app files are under
`app/src/main/java/com/aicontrolcenter/app/`, framework under `framework/src/main/kotlin/com/aicontrolcenter/framework/`,
core under `core/src/main/kotlin/com/aicontrolcenter/core/`, device under
`device/src/main/kotlin/com/aicontrolcenter/device/`, data under `data/src/main/java/com/aicontrolcenter/data/`,
memory under `memory/src/main/kotlin/com/aicontrolcenter/memory/`. Test paths mirror them under `src/test`.

The owner's rules, verbatim, still in force:
"Require confirmation before deleting important files, installing applications, making purchases, sending
communications, or performing other consequential actions. Never bypass Android security restrictions or attempt to
access another application's private data without an authorized integration." and "Do not force all agents to share
all memories." Only official integrations, supported intents, APIs, or explicitly authorised accessibility.

## 0. The icon ("It needs an icon also")

Done, and shipped in the build the owner can install today:
- 0.5.1 (d7315fd) added the adaptive launcher icon: app/src/main/res/mipmap-anydpi/ic_launcher.xml and
  ic_launcher_round.xml with background, foreground and a one-colour `monochrome` layer for Android 13+ themed icons,
  named by the manifest (app/src/main/AndroidManifest.xml:26, :29), checked against the 66 dp safe zone by
  app/src/test/java/com/aicontrolcenter/app/screenshots/LauncherIconTest.kt [V].
- 0.5.2 (9c739d5) carries it under the new name [V: `aapt2 dump badging` of scratchpad/AIControlCenter-0.5.2.apk shows
  `package: name='com.aicontrolcenter.hq' versionCode='8'` and `application: label='AI Control Center' icon=…`].
- Every existing notification has its own small icon (res/drawable/ic_stat_approval, ic_stat_director, ic_stat_pause,
  ic_stat_stop, ic_stat_touch) [V].

**Most likely reason the owner still misses it:** 0.5.0 had no icon and is a different app to Android from 0.5.2, so a
phone can show two "AI Control Center" entries, the old one with Android's plain default icon. WP14 writes "Removing
the old app" for the owner (switch off the old app's screen control in Android's accessibility settings, then
uninstall it; its agents and keys cannot be moved and must be entered again).

Phase 6 adds icons only where it adds notifications: `ic_stat_done`, `ic_stat_stopped`, `ic_stat_question` (WP2), each
checked by a LauncherIconTest-style test (one colour, vector, inside 24 dp). §10 step 1 checks the icon in the signed
APK with aapt2. The emulator image has no launcher, so only the owner's phone shows the launcher icon.

## 1. Goals for the owner (plain language)

After 0.6.0 you can:

1. **Spend less, and see what you spend.** Each request to a model carries only the tools the agent needs right now
   instead of all of them, so the same work costs much less. You can type in your model's price; every chat, task
   and objective then shows its tokens and estimated cost, and the Dashboard shows today's and this month's total.
2. **Set a daily limit.** The app stops asking models for the rest of the day once your agents used a set amount
   (answers, tokens, or money once prices are entered). A running objective pauses instead of failing, and you can
   raise the limit.
3. **Give the team an objective and put the phone away.** With the screen off, agents keep working until done; the
   phone is kept awake only while an agent is actually working or about to try again. When the work ends you get a
   notification: "Finished" or "Stopped" with the reason.
4. **Leave a chat while an agent answers.** The answer still arrives; the notification says "Researcher is answering
   · Stop".
5. **See in one place whether Android lets the app work in the background** and fix each item with one tap that opens
   Android's own screen.
6. **Know what happened when something stopped.** "Interrupted: Android closed the app to free memory." A chat reply
   that was cut off says so and offers "Send again".
7. **Ride out a provider that stops answering or asks you to slow down.** The work waits and goes on by itself.
8. **Recover when the app cannot open its data:** a Recovery screen instead of a stuck app.
9. **Watch the team at work** in a Team view: who works, who waits for whom, who waits for *you*. A list version works
   well with TalkBack; "Calm" switches motion off.
10. **Type normally.** Text fields no longer drop or reorder letters.
11. **Use faster screens** for objectives with many large results, long chats and a long activity log.
12. **Have the rough edges gone** (section 7), including scripts that never stop, screen control while an app is
    still opening, and the banner while an action waits for you.
13. **Reasoning and Coding in the model editor do something real.** Vision keeps "(test)": showing photos to a model
    is not in this version.

Only if they pass the review in time (Tier B, section 2): **ask-and-answer** (an agent asks you a question while it
works), **notes to the team** during an objective, **a backup file** you can restore on a new phone, **sharing text
from another app into a memory space**, Activity text search, a "Getting started" card, safe start after repeated
crashes, and a "cheaper model for helpers" suggestion.

## 2. Scope: three tiers and the non-goals

**Tier A: ships in 0.6.0, never cut.**
- Cost: lean requests (WP15), usage and cost records with owner-entered prices, daily limits that pause objectives,
  429 waits (WP4, WP7), the Usage tab and Dashboard totals.
- Typing bug #42 (WP0).
- Background: wake lock with short-wait cover and wake alarms, chats in the service, background check, result
  notifications, collector supervision, battery-exempt service start (WP2).
- Recovery: exit reasons read live and filtered to the app's own process, interrupted texts, Recovery screen with
  "Start over", device-transfer exclusions, the upkeep job (WP1d, WP2, WP10).
- Scripts in their own process, and D2 (WP6).
- Phone operator fixes D4-D8, D10-D12, screen-on rule, per-request holds, protected system pickers (WP1a, WP5, WP15).
- Teamwork: C3 caps, C4 reviewer, C7 framing; recovery behaviour R3, R4 (parts 1-2), D20-D23, D29 (WP5, WP7).
- Speed: P1, P2, P3, P4, P5, P7, P8 with projections only (no `tasks` rebuild).
- Team view live (no replay); dashboard N3/N6 (WP9).
- Small fixes: Activity "Show older" (D41), D9, D35-D38, D40; Reasoning and Coding made real (D27).

**Tier B: ships only if no critical or major finding is open on it after wave 4, round 2; otherwise it is switched off
(its setting forced off and its entry points hidden) or removed, and documented.**
- C1 questions to the owner (Q3), with rules 13, 32, 33.
- C2 notes to the team, with rule 24.
- WP3 backup file (save; restore into an empty app), with rule 8. Format frozen and reviewed at the end of wave 1.
- WP16 receiving shares from another app into a memory space, with rule 34.
- Activity text search; Getting started card; safe start (two starts without "ok", main-process exits only);
  "cheaper model for helpers" suggestion; shorter role protocols for the Director's steps.

**Tier C: cut now, documented as "not built".**

| Not in 0.6.0 | Why |
|---|---|
| Routines, resuming after a phone restart without opening the app, and **automatic import from a shared folder** (A4, A5, owner note §2 "folder pick-up") | Work or data intake that starts while nobody watches; unreliable starts on Android (exact-alarm permission, battery exemption, force stop cancels alarms on 15+). Owner decision Q1 can bring them back (Appendix A). Sharing from the other app (Tier B) covers the owner's export button |
| Team view replay; speed histograms (`ttft_hist`, `answer_hist`) | Polish; averages from sums answer "how fast" |
| `provider_checks` table and connection history; `app_exits` table, `setProcessStateSummary` (128 bytes, "do not include sensitive information" [D] https://developer.android.com/reference/android/app/ActivityManager, API 30+), "times Android closed the app" | The system keeps the exit history; read it live at start (F17, S20) |
| Pre-upgrade safety copy and "Go back to the copy" | Room migrations run in one transaction, a downgrade install is refused, and `user_version` cannot be read under SQLCipher without the key, so the button could never work (F10) |
| `tasks` table rebuild | Projections plus an added `result_chars` give most of the gain without rewriting the owner's whole result history (F20) |
| "This app on your phone" footprint card and the problem report | Breadth; removes S21's attack surface |
| One clock for limits (G6) | `WaitClock.runDown` waits on dispatcher delays (core/tasks/WaitClock.kt:67-77 [V]); a new time source alone changes nothing (F9) |
| Photo button in chat (D26) | Base64 pictures in `message_json` rows risk the CursorWindow limit and are re-sent each turn (F16); vision routing moves data (S14). Vision keeps "(test)", which the owner's rule allows |
| Per-provider spending caps; monthly cap; fetching prices from the web | Overall daily caps cover the need; prices are typed by the owner and never fetched |
| "While you were away" card; continuing an interrupted task from its transcript (R10); mid-run fallback to another model (R4 part 3); asking during planning; notes reaching a running step (C2 stage 2); C5, C6; P6, P9; "keep on this phone" mark (D39); web search, on-device runtime, Gemini Nano, translations, code shrinking, network scan (D30, D32-D34, D42) | As in revision 1: breadth, untested protocol changes, or unchanged decisions |
| Answering approvals by typing or from a notification; a team memory every agent reads by default; free agent-to-agent chat; re-planning mid-run | Against the owner's rules or unbounded |
| targetSdk 37 | Would need `ACCESS_LOCAL_NETWORK` for LAN model servers (Android 17, target 37+ [D] https://developer.android.com/about/versions/17/behavior-changes-17); stays 36 |
| A ninth section | Everything fits the eight |

## 3. Non-negotiable rules (all eight Phase 5 rules stay; each rule below has a named test)

1. **No work starts that the owner did not start; background entry points are inert (N11).** Background execution only
   keeps alive work the owner started. The upkeep job (`UpkeepJobService`) reaches only `UpkeepGraph` (shared
   `DataLayer` stores, the shared memory service with the offline `HashingEmbedder`, pruning), never
   `AiControlCenterApp.graph`, never a model, a tool or a provider check; it does nothing while the database check is
   not OK, during a restore, or when the last successful start was by another versionCode (so it never opens the
   database first after an update). `WakeReceiver` only signals a graph that already exists. The accessibility
   service touches neither database nor graph (device/screen/OperatorAccessibilityService.kt:27-34 [V]). Tests:
   reflection walk from `UpkeepJobService` fails on `AppGraph`, `Orchestrator`, `ModelGateway`, `ToolExecutor`,
   `AgentChat`, `ProviderManager`; Robolectric runs the job with a recording HTTP layer: zero requests; a fresh app with
   no objectives starts no foreground service and schedules only the upkeep job.
2. **One gate stays one gate.** Every new path to a tool or a model goes through `PermissionBroker` and
   `DefaultModelGateway`. `team.ask_owner` and `tools_open` are SAFE, never grant, never approve, never change a
   setting. Tests: `OwnerRequest` init refuses a QUESTION with a `decisionScope` or a grant; answering "yes, do it" to a
   question leaves every pending approval PENDING; a tool reached through `tools_open` is authorised exactly as before.
3. **Owner-only ports, and only the owner writes settings (N1).** `SettingsRepository` (writer) becomes owner-only;
   agent-facing code holds the read-only `SettingsView` through the `ReadOnlySettings` wrapper. New owner-only ports:
   `OwnerRequests.answer`, `OwnerConversation`, `OwnerModelPrices`, `OwnerUsage`, `OwnerBackup`, `OwnerRecovery`.
   Both architecture tests (app/src/test/.../di/OperatorDependencyTest.kt ownerOnly at :62-68 and roots at :162-164;
   framework/src/test/.../operator/OperatorDependencyTest.kt ownerOnly at :34-38 and roots at :73-77 [V]) list them,
   add `update` to `ownerOnlyActions`, and add the roots `DefaultModelGateway`, `DefaultAgentChat`,
   `DefaultRegistryDirectory`, `DefaultToolAuthorizer`, `OwnerQuestions`, the script host and `ProviderRechecks`.
4. **Notifications never decide, and carry no agent-written text (N4).** No notification action answers a request,
   approves or answers a question. Allowed actions: Pause (objectives) and Stop (a chat turn, screen control). Question
   and result notifications carry app-written text plus the owner's own objective title ("A question from
   Researcher. Open the app to read it."; "Finished: <objective title>"; "Stopped: today's limit is reached"); private
   on the lock screen with a public version; `setAllowSystemGeneratedContextualActions(false)` as approvals already do
   (app/work/ApprovalNotifications.kt:73 [V]). Test: Robolectric `ShadowNotificationManager` lists every action and
   PendingIntent target; a QUESTION and a task error carrying a marker string never put it in any notification field.
5. **The phone is kept awake only while agents work or are about to try again.** A partial wake lock is held iff the
   foreground service runs and (an agent is WORKING per `AgentActivityMonitor`, or a live chat turn is not waiting for
   the owner, or `TimedWaits.nextDueAt` is within 2 minutes); 10-minute timeout per acquire, renewed; released in
   `onDestroy`. For longer waits the service sets one `AlarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` to
   its own non-exported `WakeReceiver` (no permission; Doze may delay it to about once per 9 minutes [D]
   https://developer.android.com/develop/background-work/services/alarms, Android 6+). Test: Robolectric
   `ShadowPowerManager` and `ShadowAlarmManager` over a table of states.
6. **Daily limits are checked before every attempt** that would reach a provider not on this phone (local network
   and cloud), retries included, with reserve-then-commit under one mutex so two concurrent attempts at 199 cannot both
   pass; on-device providers are counted, not limited; clearing usage history never clears today. A refused attempt
   raises `LlmException.DailyLimitReached`; a running objective pauses with "Paused: today's limit is reached"; a chat
   shows the same sentence. Tests: fake clock and zone; the 200th call goes, the 201st and its retry are refused; two
   concurrent attempts at 199; midnight in the owner's zone resets; the money cap counts only priced models.
7. **Usage records hold numbers and ids only**: no message content, keys or addresses; they never leave the phone (usage is not in the
   backup; only the owner's prices are); no agent tool reads them. Tests: a column allow-list test over `usage_hours`, `work_usage`, `model_prices`; the tool catalogue has
   no usage tool.
8. **The backup file (Tier B) holds configuration and conversation text only, and restore never relaxes protection
   (N2, N3).** Never in the file: API keys, key aliases (`apiKeyAlias`), the database key, grants, requests to the
   owner, apps authorised for screen control, shared-folder grants, the activity log, usage, tool calls and tool
   results (replaced by "A tool was used here: <tool title>. Its result is not in the backup."). Restore: grants
   nothing; `apiKeyAlias = null`, status UNKNOWN, locality re-inferred with `Locality.infer`, headers re-validated by
   `ProviderDraftValidator`; local-only mode = current OR file; both operator switches and auto-resume off; daily
   limits the stricter of current and file; other limits keep current values; the screen lists every setting that would
   change; memory ids and scopes kept as they are, a memory whose agent or space is not in the file dropped and
   counted, agents' space lists cut to spaces in the file; writes only into an empty app, checked inside the
   transaction; writes nothing when the passphrase is wrong or any chunk was changed, moved or cut off. Tests: JVM round
   trip; tamper, reorder, duplicate, truncate; wrong passphrase; vault holds `provider/p1/1`, file has provider `p1` at
   `https://other.example` with that alias → stored alias null and zero requests carry the key; local-only stays on;
   a `screen.read` result is absent from the decrypted body; private memories of A unreadable by B after restore.
9. **Recovery never deletes without the owner, and the Recovery path never touches the graph.** "Start over" (typed
   word, entered once in a field the keyboard does not learn) moves the database, its sealed key and the API-key vault
   file into `noBackupFilesDir/set-aside-<date>/`, starts the next key generation (`aicc.database-key.v<N+1>`,
   `aicc.secret-vault.v<N+1>`; the old keystore entries stay so the set-aside copy stays openable), releases
   Android's persisted folder grants, resets `seedVersion`, `memorySeedVersion`, `directorAgentId`,
   `onboardingCompleted` and both operator switches, then restarts the process. No destructive migration fallback.
   Tests: Robolectric with a failing open helper; a damaged-alias test (`containsAlias` true, load fails, as
   KeystoreVaultKey.kt:44-50 [V]) still opens a fresh database after Start over; instrumented test with the real keystore.
10. **Safe start (Tier B).** After two starts in a row without "ok", or three CRASH/CRASH_NATIVE/ANR exits of the main
    process (`processName == applicationInfo.processName`) within 15 minutes, nothing resumes automatically until the
    owner chooses. Test: JVM ledger state machine; three `:scripts` crashes give no safe start.
11. **The script process is untrusted (N12).** JavaScript runs in an `android:isolatedProcess="true"` service; every
    limit that protects the app is enforced in the app process: time (unbind and, through the watchdog, kill), the
    20-file cap, read, write and result sizes, `canWrite`, never-overwrite, and "no host call after the run is over"
    (JsSandbox.kt:185-189 [V]). One host binder object per run; AIDL carries only primitives, Strings of at most 64 KB
    and `ParcelFileDescriptor` pipes, never `Bundle`, `Parcelable` or `Serializable` from the script side. Ending a
    script never ends the app's process. Tests: `JsEngineContract` with a hostile engine that ignores every in-sandbox
    limit; emulator `ScriptProcessDeviceTest`; a source scan that app/scripts/ never names `graph`.
12. **Screen control: the banner stays while an action waits, and screen actions need a screen that is on and
    unlocked (N9).** From the moment a screen action waits for the owner until that call returns, the session is held
    (holds counted per request id, never ended by another run's `endSession`); Stop always works; every screen action
    is refused unless `PowerManager.isInteractive()` and not `isKeyguardLocked` ("The phone's screen is off. Screen
    actions wait until you use the phone."). Tests: AgentRunner virtual time with a 60 s approval; device Robolectric
    with `ShadowPowerManager.setIsInteractive(false)`; run 2 of agent X ends while run 1's tap waits: the banner stays.
13. **Questions to the owner are bounded and never look like approvals (Tier B; N5).** At most 1 open per task, 2 per
    task, 4 per objective; at most 5 open agent-initiated requests in the app and 3 per objective; a near-identical
    second question refused; no question while the same task has a PERMISSION or APPROVAL open. Own route and screen
    (`QuestionRoute`, title "A question from Researcher", the line "This is not a permission. Your answer is only text
    for the agent."); suggested answers shown as quoted agent text in a list ("Use this answer"), never as primary
    buttons; options matching approval words (allow, approve, permit, grant, always, once, deny, yes do it) refused at
    `OwnerRequest` init. Waiting costs no time; unanswered after the question time-out (default 20 min) the agent is
    told to decide itself. Tests: concurrent asks cannot pass a cap together; init refusals; screenshot of the
    question screen beside an approval screen.
14. **Pending requests raised by calls in progress are capped** at 12 in all and 6 per objective; over the cap the call
    is refused and nothing runs. Test: the 13th is refused and nothing is stored.
15. **One shell command at a time, from the question to the end** (D11). Test: the Phase 5 probes G1-B, R5-B, R5-C
    through the real broker and executor.
16. **Lists read summaries.** No list, dashboard, notification or Team view observes whole task rows or whole messages;
    view-model state is built off the main thread; the 1-second ticker never rebuilds plan, messages or log. Tests
    count, never time (F19): query constants (no `SELECT *` in summary queries), `EXPLAIN QUERY PLAN` index tests as in
    data/src/test/.../RoomActivityLogQueryTest.kt:105-114 and RoomMemoryBehaviourTest.kt:169-194 [V per critic], a
    view-model test counting builds (10 ticks, 0 plan builds), rows-read counts.
17. **Motion follows the owner.** With animations off (`ValueAnimator.areAnimatorsEnabled()` false, API 26+ [D]
    https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled()) or "Calm" on,
    nothing travels or pulses; the frame loop runs only while something moves. Tests: Compose with
    `MotionDurationScale` 0 and with Calm draw no dot.
18. **Plain words and the "(test)" rule.** Every new control fully works or ends in " (test)" through
    ui/components/TestLabel.kt; `PlaceholderLabelsTest` (app/src/test/.../journeys/PlaceholderLabelsTest.kt:56-58 [V])
    lists exactly Vision after this phase. `@Phase6Stub` files are checked by a gate task at the end of wave 3 (a grep
    plus `NoPhase6StubsTest` added only then), not by the normal suite (F25). Docs say "not built", never "planned".
19. **One migration.** Exactly one hand-written `Migration5To6` in data/db/AiccMigrations.kt (today `all` ends at
    `Migration4To5`, :21 [V]); `AiccDatabase.VERSION` 6 (:100 [V]); only `CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE …
    ADD COLUMN` and one backfill `UPDATE`; every ALTER-added field declared last in its entity so new installs and
    upgraded databases have the same column order; exported 6.json; the migration runs exactly the exported statements;
    5→6, 1→6 step by step and 1→6 directly with data in every table; the app opening a version-5 database upgrades it in
    place; `StoredNamesGoldenTest` extended; the 0.5.2 → 0.6.0 upgrade keeps everything on the emulator with SQLCipher.
20. **Phase 5 holds.** Every Phase 5 rule still holds; the full suite (2,480 tests at 0.5.0, STATUS.md:441, plus
    LauncherIconTest from 0.5.1) stays green; the Phase 5 probes are re-run in every review round.
21. **Owner's own exports are allowed in local-only mode, agents' hand-overs are not.** The backup file and the memory
    graph export are the owner's own action through Android's picker or share sheet, allowed in local-only mode with
    the line "The app you choose may send it off the phone"; every agent hand-over stays blocked (LIMITATIONS.md:308-315).
22. **Permissions grow by exactly three normal permissions** (`WAKE_LOCK`, `ACCESS_NETWORK_STATE`,
    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: 16 `uses-permission` in the APK, from 13 [V: aapt2 dump of the 0.5.2 APK]).
    Exported components: the main activity, the accessibility service, the AndroidX profile installer, and, only if
    WP16 ships, `ShareReceiverActivity`. Tests: a merged-manifest test in `testReleaseUnitTest` (the debug variant
    merges an exported `androidx.activity.ComponentActivity` from ui-test-manifest, app/build.gradle.kts:104 [V], F15),
    and aapt2 on the signed APK in §10 step 1. With Q1 = yes, routines add `SCHEDULE_EXACT_ALARM` and
    `RECEIVE_BOOT_COMPLETED`.
23. **No new entry points on MainActivity (S27).** No new extras or intent filters; no intent opens Backup, Restore,
    Recovery or Start over; no VIEW or SEND filter for backup files. Test: merged-manifest test lists MainActivity's
    filters exactly (MAIN/LAUNCHER).
24. **Owner notes cannot be forged (Tier B; N7).** `MessageType.GUIDANCE`, and ANSWER with `from = Participant.Owner`,
    are constructed only in `OwnerNotes` and `OwnerQuestions`; notes reach briefs in their own `owner_note` frame and
    the brief's fixed text lists the exact note ids present; `DataBlock.frame` and tool-result framing escape the
    literal `owner_note` tag and the label "Note from the owner". Tests: source scan; a delegated result containing a
    forged note does not appear in the notes section.
25. **No silent move farther from the phone (N8).** When the primary model's provider is only transiently unavailable
    (`PROVIDER_OFFLINE`), `resolveAvailability` never picks a fallback whose `effectiveLocality` is farther; the task
    waits ("Waiting: Ollama on your network is not answering"). Owner-made reasons (disabled, removed, key rejected,
    local-only) keep today's fallback order. Test: ON_NETWORK primary, CLOUD fallback, primary marked OFFLINE
    mid-objective → no request reaches the cloud mock.
26. **Safety collectors fail stop, not silent (N10).** `ApprovalNotifier`, `ScreenSessionNotifier` and
    `ObjectiveWorkController` run under `SafetyCollectors`, which restarts a failed collector with backoff (1, 2, 4 …
    60 s) and logs a WARNING; if `ScreenSessionNotifier` fails three times within 5 minutes `StopSwitch` stops screen
    control; if the controller does, running objectives pause. Test: inject an exception into each collector's flow.
27. **Owner input is exact (N13).** Every text field holds its text in a synchronous `TextFieldState`; passphrase and
    confirm-word fields use `OutlinedSecureTextField` (the keyboard does not learn them); the Recovery, Backup and
    passphrase screens set `FLAG_SECURE`. Tests: paused-clock fast-typing tests per field family; a source scan that
    every text field uses the `state =` overload.
28. **Lean requests (owner request, task #43).** No model request offers an agent's whole tool catalogue when its
    definitions exceed the menu threshold; the system prompt and the offered tools are byte-identical between calls
    of one run until a tool family is opened (cache-friendly prefix). Tests: per starter agent, the first request's tool
    definitions ≤ 3,000 estimated tokens and system prompt ≤ 1,500 (`TokenEstimator`); prefix stability over a 5-call
    run.
29. **Costs are estimates from the owner's prices** (never fetched); the screen says "estimated from the prices you
    entered; your provider's bill is what counts". Test: cost arithmetic with cached tokens; no price → no money shown.
30. **Restore and Start over end with a process restart** (`FLAG_ACTIVITY_NEW_TASK | CLEAR_TASK` on `MainActivity`
    from the visible activity, then `Process.killProcess(myPid())`), because `DataLayer.database` is `by lazy`
    (DataLayer.kt:89-93 [V]) and the graph is a single instance (AiControlCenterApp.kt:44-46 [V]). Test: Robolectric
    records the restart intent; no `graph` access on the Recovery path (source scan of app/health and ui/recovery).
31. **Script-process exits never count as app exits (F6).** Exit history, interrupted texts and safe start use the main
    process's exits only. Test: fake exit list.
32. **Answers that look like secrets are blocked, not just warned about (Tier B; N6).** Known key shapes (`xai-`, `sk-`,
    `sk-ant-`, `sk-or-`, `AIza`, `gsk_`, `ghp_`, 32+ characters of base64/hex) and an exact match of a stored provider key
    (constant-time compare) are refused; 4-8 digit codes when the question mentions code, SMS, verification, PIN or
    password are refused unless the owner taps "Send it anyway" a second time. After any answer, the task's first tool
    call that sends data off the phone needs an APPROVAL whose preview says "after you answered a question". Tests:
    view-model and broker tests with key and code patterns.
33. **A question cannot settle anything.** QUESTION never enters `grantFor`, the refusal cooldown or settle-covered
    (OwnerRequests.kt:124-126, :154-162; OwnerServices.kt:230 [V]); kept by the request contract suite.
34. **Shared content is data and lands only on the owner's tap (Tier B, owner request #43).** `ShareReceiverActivity`
    accepts only `ACTION_SEND`/`ACTION_SEND_MULTIPLE` of `text/*`, at most 200,000 characters and 10 files of at most 1 MB;
    it never starts work or calls a model; nothing is stored until the owner picks a memory space (or the workspace)
    and taps Save on its own screen, which screen control cannot operate (own package, ConsequentialControls.kt:292
    [V]); it is allowed in local-only mode. Test: Robolectric start with a SEND intent stores nothing until Save; size
    caps; a stored item is framed as recalled data.

## 4. Storage: schema 6 (all in WP1b, one migration, no table rebuild)

| Change | Why | Detail |
|---|---|---|
| `tasks.retry_at INTEGER` (ALTER) | R4: wait instead of fail | null = no wait |
| `tasks.result_chars INTEGER NOT NULL DEFAULT 0` (ALTER + `UPDATE tasks SET result_chars = COALESCE(length(result),0)`) | P1 summaries show result size without reading `result` | declared last in `TaskEntity` |
| index `tasks(assignee_id, updated_at)` | `observeRecentByAgent` | |
| `agent_messages.to_space_id TEXT` (ALTER) | `Participant.Space` for memory shares (WorkSteps.kt:88-91) | `to_kind = "space"`; old shares stay `System` |
| `owner_requests.question TEXT, options_json TEXT, answer TEXT` (ALTER) | C1 (Tier B) | QUESTION rows only; unused if C1 is switched off |
| `conversations.turn_started_at INTEGER` (ALTER) | R3 cut-off chat replies | set when a turn starts, cleared when it ends |
| `usage_hours` (new) | usage, speed averages, reliability, daily limits, daily and monthly totals | PK `(hour_start, model_id, agent_id, source)` (`agent_id` "" when none; `source` CHAT/OBJECTIVE/OTHER); labels `model_label, provider_id, provider_label, locality`; counts `calls, ok, failed, cancelled, blocked, retries, substituted, truncated, no_usage, rate_limited_waits`; failures `err_auth, err_rate_limited, err_overloaded, err_timeout, err_network, err_server, err_invalid, err_context, err_other`; `input_tokens, output_tokens, cached_input_tokens`; `answer_ms_sum, ttft_ms_sum, ttft_count`; `bytes_sent`. Indexes `(model_id, hour_start)`, `(agent_id, hour_start)`. No foreign keys |
| `work_usage` (new) | cost per chat, task and objective at current prices (owner request) | PK `(work_kind, work_id, model_id)`; `work_kind` TASK/OBJECTIVE/CHAT; `calls, input_tokens, output_tokens, cached_input_tokens, updated_at`. One call updates its TASK and OBJECTIVE rows, or its CHAT row, in the same transaction as `usage_hours` |
| `model_prices` (new) | cost from prices the owner types | `model_id PK, input_per_mtok_micros, output_per_mtok_micros, cached_input_per_mtok_micros (nullable = input price), currency_label, updated_at`; separate from `models` because a model refresh rewrites model rows (reader-performance 2.2) |
| (Q1 = yes only) `routines`, `routine_runs` | Appendix A | |

Settings (DataStore, `FrameworkSettings`, core/settings/FrameworkSettings.kt [V]; the codec reads each field on its own,
data/settings/SettingsCodec.kt:11-21 [V], so added fields with defaults are safe): `keepAwakeWhileAgentsWork = true`,
`notifyWhenWorkEnds = true`, `maxModelCallsPerDay = 200`, `maxTokensPerDay = 500_000`, `maxSpendPerDayMicros:
Long? = 1_000_000` (one dollar; applies to models with a price), `agentQuestionsEnabled = true` (Q3; forced false if C1
is switched off), `ownerQuestionTimeoutMinutes = 20` (5..240), `usageRetentionDays = 730` (90/365/730/0 = forever),
`calmMotion = false`, `keyGeneration` is **not** a setting (it lives in a plain file beside the sealed keys, WP2).
`onboardingCompleted` (FrameworkSettings.kt:43, read by nothing today [V]) gets its reader (Getting started, Tier B).
Kept outside the database: the start ledger and crash notes in `noBackupFilesDir`; the last processed exit time and
"screen service was ready" in device SharedPreferences; the last backup time in DataStore.

## 5. Work packages

Sizes: S (under a day of one agent), M (1-2 days), L (3-5 days). "Owns" = the only package that edits those files in
its wave; files not listed are read-only for it. A package that needs a change in a file it does not own writes it into
its hand-off note `scratchpad/phase6/handoff-<wp>.md` (what, where, why); the integrator applies it at merge time.

### Shared files: one owner per wave

| File | Wave 1 | Wave 2 | Waves 3-6 |
|---|---|---|---|
| core/** and testkit/** (existing files) | WP1a | nobody (new testkit files named in a WP are allowed) | WP8 (fixes only) |
| framework seams listed in WP1c; memory/DefaultMemoryService.kt, MemoryEmbeddings.kt | WP1c | per wave-2 ownership below | WP8 |
| data/** | WP1b | data/DataLayer.kt, data/security/*, data/recovery/* → WP2; data/backup/* → WP3 | WP8 |
| device/platform/QueriedIntent.kt, device manifest `<queries>` | WP1a | nobody | WP8 |
| app/build.gradle.kts, app AndroidManifest.xml, AiControlCenterApp.kt, MainActivity.kt, di/Ports.kt, di/ProductionPorts.kt, di/AppGraph.kt, di/AppStartup.kt, app test testing/TestApp.kt, both architecture tests (app side) | WP1d | nobody | WP8 |
| di/wiring/*Wiring.kt and the stubs WP1d creates | WP1d creates | the package named in each file | WP8 |
| app/ui/** | WP0 (text-field files only) | nobody | WP8 3a, then WP9/10/11/16 as listed |

### Wave 1 (base `p6/base` = 9c739d5)

Order inside the wave: WP0, WP1a and the WP6 spike start at once. WP1b and WP1c start from WP1a's first commit
(types, in-memory implementations and contract suites, aimed at the first half-day). WP1d starts when WP1a, WP1b and
WP1c are merged. Merge order: WP1a, WP0, WP1c, WP1b, WP1d. Gate: full suite green; `EncryptedMigrationTest` passed
once on the emulator (after the spike frees it); WP1a's builder's guide written to `scratchpad/phase6-contracts.md`;
WP3's backup format reviewed by a security reviewer; the spike verdict recorded in `scratchpad/phase6/spike-scripts.md`.

#### WP0. Text input: the owner's typing bug (#42). Size M.

Purpose: goal 10, rule 27. Cause [V per owner-typing-bug.md]: NewObjectiveScreen.kt:198-199 feeds
`OutlinedTextField(value = state.text, …)` from `uiState`, a `combine(…).stateIn` (NewObjectiveViewModel.kt:89-112), so
the field lags one frame behind the keyboard and Compose resets it.
Owns: ui/components/AppTextField.kt, SecretTextField.kt, ChipEditor.kt, InlineTextEntry.kt; ui/tasks/NewObjectiveScreen.kt,
NewObjectiveViewModel.kt; ui/agents/AgentsScreen.kt, AgentsViewModel.kt, AgentEditorIdentity.kt, AgentEditorSections.kt,
AgentEditorScreen.kt, AgentEditorViewModel.kt; ui/approvals/ApprovalScreen.kt, ApprovalViewModels.kt;
ui/models/ProviderFormScreen.kt, ProviderFormViewModel.kt, ModelEditorScreen.kt, ModelEditorViewModel.kt,
RegisterAppScreen.kt, RegisterAppViewModel.kt, AiAppDetailCards.kt, AiAppDetailScreen.kt, AiAppDetailViewModel.kt;
ui/memory/MemoryEditorScreen.kt, MemoryEditorViewModel.kt, MemoryDetailSections.kt, MemoryExplorerTab.kt,
SpaceDetailScreen.kt, SpacesTab.kt; ui/chat/ChatScreen.kt (confirm only); the matching tests under
app/src/test/.../ui/**; new app/src/test/.../ui/components/FastTypingTest.kt and TextFieldRuleTest.kt.
Work:
1. Every field uses the Material 3 `state: TextFieldState` overload (material3-android 1.5.0 from compose BOM
   2026.11.01 has it, and `OutlinedSecureTextField` in SecureTextFieldKt [V: javap of the cached
   material3-android-1.5.0 classes.jar]). Form text lives in a `TextFieldState` created once in the view model;
   counters, errors and buttons read `state.text` (in composition or through `snapshotFlow`); no field value ever comes
   from `combine`/`stateIn`/`map`.
2. Loaded values (editing an agent, a model, a memory) are put in once with `setTextAndPlaceCursorAtEnd` when the
   record arrives, never again while the owner types.
3. `SecretTextField` becomes `OutlinedSecureTextField` (no suggestions, no learning); `ChipEditor` and
   `InlineTextEntry` use `rememberTextFieldState`.
Tests: `FastTypingTest` per field family with `mainClock.autoAdvance = false`, several `performTextInput` calls
without advancing, then the exact text (first commit red on today's NewObjectiveScreen, then green);
`TextFieldRuleTest` scans app/src/main for `TextField(`, `OutlinedTextField(`, `BasicTextField(` and fails on any
call without `state =`; existing view-model tests adjusted. Done when `:app:testDebugUnitTest` is green and the
changed screens' screenshots differ only where the field changed (reviewed by eye).

#### WP1a. Contracts in `:core` and `:testkit`. Size L.

Owns: core/src/** and testkit/src/** (existing and new files); device/platform/QueriedIntent.kt and the `<queries>`
block of device/src/main/AndroidManifest.xml (S26 only); test files that encode the old fallback behaviour if rule 25
breaks them (named in its hand-off; candidates [V grep]: core CoreModelTest.kt, framework RegistryDirectoryTest.kt,
ModelGatewayTest.kt, ProviderManagerTest.kt).
Every addition is additive with defaults so every existing caller compiles; every new port gets an in-memory
implementation in :testkit and a contract suite in testkit/contract/ in the **first commit**.

| ID | Contract (signatures) | File |
|---|---|---|
| K1 | `TaskSummary(id, rootId, parentId, kind, status, assigneeId, title, statusDetailShort ≤200, sortOrder, attempt, revisionRound, needsReview, createdAt, updatedAt, startedAt, finishedAt, retryAt, resultChars, dependsOn)`; `ObjectiveRow(rootId, title, status, statusDetailShort, createdAt, updatedAt, finishedAt, planned, completed, participants: List<AgentId>)`; `ObjectiveCursor(updatedAt, id)`; `PlanProgress(rootId, total, done, running, waiting, failed)`; on `TaskRepository` default methods derived from `observeTree`/`observeObjectives`: `observeSummaries(rootId)`, `observeObjectiveRows(limit, olderThan: ObjectiveCursor? = null)`, `observeRecentByAgent(agentId, limit)`, `observeProgress(rootIds: Set<TaskId>)`; `TaskRecord.retryAt: Long? = null` | core/tasks/TaskPorts.kt, Tasks.kt |
| K2 | `MessageHeader(id, rootId, taskId, from, to, type, at, hop, replyTo, preview ≤120)`; `MessageRepository.observeHeaders(rootId, limit)`, `observeRecentHeaders(limit)` (defaults derived); `Participant.Space(spaceId)`; `MessageType.GUIDANCE` appended last | core/tasks/Messages.kt, TaskPorts.kt |
| K3 | `OwnerRequestKind.QUESTION` appended; `OwnerRequest.question: String?`, `options: List<String>`, `answer: String?` with init invariants (no `decisionScope`, no grant, question 1..1,000 chars, ≤4 options of ≤120 chars, no option matching the approval words of rule 13, APPROVED only with an answer ≤2,000 chars or "decide yourself"); `OwnerDecision.Answer(text: String?)` (null = decide yourself); `OwnerRequests.answer(id, text: String?, sendAnyway: Boolean = false): OwnerResult<Unit>` (owner-only, default "not available"); `OwnerAttention` constants (agent-initiated 5/3; pending call requests 12/6; questions 1 open/2 per task/4 per objective); pure `AnswerCheck.classify(question, answer): AnswerRisk {NONE, CODE, KEY}` with tests | core/operator/OwnerRequests.kt, OwnerPorts.kt, AnswerCheck.kt (new) |
| K4 | `OwnerConversation.addNote(rootId, toTaskId: TaskId?, text): OwnerResult<MessageId>` (owner-only; ≤1,000 chars, ≤10 per objective); `OwnerConversation.Unavailable` | core/tasks/OwnerConversation.kt (new) |
| K5 | `PreparedAction.beforeOwnerAsked(): String? = null`, `callEnded() {}`; `Workspace.reserveCommand(token: String): String?` (null = reserved, else the refusal) and `releaseReservation(token)` with defaults | core/tools/Tool.kt, core/operator/Files.kt |
| K6 | `WebAddressPolicy.literalRefusal(host: String): String?`, a pure function written here: the literal parse of providers/web/AddressGuard.kt:50-89 copied, plus `localhost`, `*.localhost`, `*.local`, `*.internal`, `*.home.arpa`, dotless names, trailing dot, decimal, octal, hex and IPv4-mapped IPv6 forms; tests | core/operator/WebReader.kt |
| K7 | `ScreenOperator.holdSession(holdId: String, agentName: String, packageName: String)`, `releaseSession(holdId)` (no-op defaults); `FrontState {None, Hidden, App(window)}`, `frontState()` defaulting from `activeWindow()`; `ScreenControl.switchedOffByAndroid: StateFlow<Boolean>` (default false) | core/operator/Screen.kt |
| K8 | `AgentChat.sendAgain(id)`, `recoverInterruptedTurns(): Int` (defaults: refuse / 0); `ConversationRepository.markTurnStarted(id, at)`, `markTurnEnded(id)`, `interruptedTurns()`, `observeLatestSeq(id)`, `entriesAfter(id, seq)`, `entriesBefore(id, seq, limit)` (defaults derived); `CallContext.conversationId: ConversationId? = null` | core/chat/Conversation.kt, core/llm/LlmClient.kt |
| K9 | `MemoryRepository.observeCount(filter)`, `observeCountsBySpace()` (defaults derived) | core/memory/MemoryRepository.kt |
| K10 | `ActivityFilter.text: String? = null`; `ActivityCursor(at, id)`; `ActivityLog.observePage(filter, before: ActivityCursor?, limit)` (default derived) | core/activity/ActivityEvent.kt |
| K11 | core/usage/Usage.kt (new): `ModelCallSample(at, modelId, modelLabel, providerId, providerLabel, locality, agentId?, taskId?, rootTaskId?, conversationId?, source, outcome, errorClass?, inputTokens?, outputTokens?, cachedInputTokens?, answerMs?, ttftMs?, retries, substituted, truncated, rateLimitedWaits, bytesSent)`; `UsageLedger { suspend fun record(sample); suspend fun totalsSince(fromMs): UsageTotals; None }`; `UsageReports` (today, this month, by model, by agent, reliability, per work item: `workUsage(kind, id)`; `None`); `UsageRange`; `WorkKind {TASK, OBJECTIVE, CHAT}`; `ModelPrice(modelId, inputPerMTokMicros, outputPerMTokMicros, cachedInputPerMTokMicros?, currencyLabel)`; `ModelPrices` (read) and owner-only `OwnerModelPrices` (write); owner-only `OwnerUsage.clearHistory()` (keeps today); `DailyLimits(maxCalls: Int?, maxTokens: Long?, maxSpendMicros: Long?)` + `NONE`; pure `CostEstimate.of(tokens, price)`; `LlmException.DailyLimitReached(message)` (sealed class at core/llm/LlmClient.kt:32 [V]) | core/usage/Usage.kt (new), core/llm/LlmClient.kt |
| K12 | core/health/AppHealth.kt (new): `ExitKind` (LOW_MEMORY, CRASH, CRASH_NATIVE, ANR, USER_REQUESTED, UPDATED, MEMORY_LIMIT, PERMISSION_CHANGE, FREEZER, OTHER, UNKNOWN), `ExitRecord(at, kind, importance, pssKb, rssKb)` (no description text), `ExitHistory { suspend fun newSince(ms): List<ExitRecord>; None }`, `BackgroundHealth` (`state: StateFlow<BackgroundState>`, `refresh()`; `BackgroundState(notificationsAllowed, batteryOptimised: Boolean?, backgroundRestricted: Boolean?, standbyBucket: StandbyBucket?)`; `None`), `StorageWatch` (`level: StateFlow<StorageLevel>`, `check()`; levels OK, LOW <500 MB, CRITICAL <100 MB, UNKNOWN; `None`), `NetworkStatus` (`available: StateFlow<Boolean>`; `AlwaysOn`), `TimedWaits` (`nextDueAt: StateFlow<Long?>`, `wake()`; `None`) | new |
| K13 | core/backup/Backup.kt (new): owner-only `OwnerBackup` (`save(passphrase: CharSequence, open: suspend () -> OutputStream, progress)`, `inspect(open: suspend () -> InputStream, passphrase): BackupSummary`, `restore(open, passphrase, progress): RestoreReport`), `BackupProblem` (sealed, plain texts), `SettingChange(name, from, to)`; pure `Passphrases.generate(random)` (20 characters from Crockford base32, shown as five groups of four: 100 bits) and `Passphrases.normalise/check` (own passphrase: ≥16 characters or ≥4 words; a small built-in common-password list); `MaintenanceGate` (`busy: StateFlow<Boolean>`, `runExclusive(block)`) | new |
| K14 | `Orchestrator.recoverInterrupted(cause: String?, resumeAllowed: Boolean)` overload (the existing no-argument call delegates with `null, true`); `Orchestrator.timedWaits: TimedWaits get() = TimedWaits.None`; owner-only `OwnerRecovery` (`state(): DatabaseState`, `startOver(): RecoveryResult`), `DatabaseState {OK, KEY_MISSING, KEY_DAMAGED, KEYSTORE_UNAVAILABLE, NOT_A_DATABASE, MIGRATION_FAILED, OTHER}` | core/tasks/TaskPorts.kt, core/health/Recovery.kt (new) |
| K15 | `UnavailableReason.isTransient` (true for `PROVIDER_OFFLINE` only, AgentAvailability.kt:25-35 [V]); rule 25 inside `resolveAvailability` (AgentAvailability.kt:41-56 [V]); `ProviderManager.recheckIfStale(providerId, olderThanMs): ConnectionStatus?` (default null), `ProviderManager.timedWaits` (default None), `ProviderManager.matchesStoredKey(text): Boolean` (default false) | core/agents/AgentAvailability.kt, core/llm/ProviderManager.kt |
| K16 | `FrameworkSettings` fields of section 4; `interface SettingsView { val settings: Flow<FrameworkSettings>; suspend fun get(): FrameworkSettings }`; `SettingsRepository : SettingsView` adds `update`; `class ReadOnlySettings(private val source: SettingsView) : SettingsView` (the only object agent-facing code may hold; listed in the app test's `views`) | core/settings/FrameworkSettings.kt |
| K17 | S26: `ConsequentialControls.PROTECTED_HANDLER_ACTIONS` (ConsequentialControls.kt:278-289 [V]) += `android.intent.action.OPEN_DOCUMENT`, `OPEN_DOCUMENT_TREE`, `CREATE_DOCUMENT`, `android.provider.action.PICK_IMAGES`; matching `QueriedIntent` entries (device/platform/QueriedIntent.kt:40-73 [V]) and `<queries>` intents, kept in step by `QueriesManifestTest` | core/operator/ConsequentialControls.kt, device files above |

Contract suites in testkit/contract/ (first commit): `TaskSummaryContract`, `MessageHeaderContract`,
`OwnerQuestionContract` (inside the request contract, with rule 33), `ConversationTurnContract`,
`ActivityPageContract`, `MemoryCountContract`, `UsageLedgerContract` (record, totals, work usage, clear keeps today),
`ModelPricesContract`; pure tests for `AnswerCheck`, `Passphrases`, `WebAddressPolicy`, `CostEstimate`,
`resolveAvailability` (rule 25), `OwnerRequest` QUESTION invariants. Run `tools/gw --offline :core:test :testkit:test`
for each commit, `:framework:test :app:testDebugUnitTest` before handing over. Writes `scratchpad/phase6-contracts.md`
(builder's guide, as phase5-contracts.md).

#### WP1b. Schema 6 and the Room side of every contract. Size L.

Owns: data/** (src/main, src/test, src/androidTest, schemas/, data/build.gradle.kts). Starts from WP1a's first commit.
- Entities and DAOs: new `TaskEntity` fields last; `TaskSummaryRow` projection and the aggregate `OBJECTIVE_ROWS` query
  (reader-performance P1 fix 3, matching `isPlanStep`, ObjectiveProgress.kt:33-34); `MessageHeaderRow`; conversation
  seq queries; `COUNT(*)` memory queries; activity keyset page and `LIKE` search on title and detail; `UsageDao`
  (upsert-by-transform of `usage_hours` and the `work_usage` rows of one call in one short transaction, as
  `TaskDao.update`, TaskDao.kt:52-58); `ModelPriceDao`.
- Room implementations: `RoomUsageLedger`, `RoomUsageReports` (local-day and local-month sums from UTC hours, in the
  owner's zone), `RoomModelPrices` (implements `ModelPrices` and `OwnerModelPrices`), `RoomOwnerUsage`, the new methods
  on `RoomTaskRepository`, `RoomMessageRepository`, `RoomConversationRepository`, `RoomMemoryRepository`,
  `RoomActivityLog`, `RoomOwnerRequestRepository` (QUESTION + answer through the existing compare-and-set);
  `deleteTree` and conversation deletion also delete their `work_usage` rows.
- DataLayer members `usageLedger`, `usageReports`, `modelPrices`, `ownerUsage` (by lazy, like the others).
- `Migration5To6`, `VERSION = 6`, 6.json exported, `StoredNamesGoldenTest` (GUIDANCE, QUESTION, `space`),
  `StoredColumnsTest`.
- data/build.gradle.kts: `androidTestImplementation(libs.androidx.room.testing)` and the schemas as androidTest
  assets (today only `test`, data/build.gradle.kts:22-23 [V]).
Tests: every contract suite against Room (ARCHITECTURE.md:82-83); MigrationTest 5→6 with data in every table
(results, reviews, usage and options kept byte for byte; `result_chars` filled), 1→6 step by step and directly, the app
opening a v5 database; SchemaExportTest (the migration runs exactly the exported statements); query-plan tests for
summaries, objective rows, headers, recent-by-agent, activity page; rows-read counts (rule 16); the column allow-list
test of rule 7; instrumented `EncryptedMigrationTest` (camelCase method names: names with spaces cannot be dexed below
API 30, data/build.gradle.kts:62 [V per critic]) run once on the emulator at the wave-1 gate.

#### WP1c. Framework seams. Size M.

Owns, in wave 1 only: framework/operator/ScriptTools.kt (split), new JsEngine.kt, JsTools.kt, ShellTool.kt,
TermuxTool.kt, RecentLaunches.kt; framework/operator/OperatorToolset.kt; framework/orchestration/WorkSteps.kt
(:189-192 only); the `SettingsRepository` → `SettingsView` field type in framework/agents/AgentRunner.kt:93,
operator/OperatorFacts.kt:31, orchestration/OrchestrationContext.kt:29, orchestration/DefaultOrchestrator.kt:86,
llm/DefaultModelGateway.kt:56, llm/DefaultProviderManager.kt:73, chat/DefaultAgentChat.kt:97,
registry/DefaultRegistryDirectory.kt:23, tools/DefaultToolAuthorizer.kt:28, memory/DefaultMemoryService.kt:82,
memory/MemoryEmbeddings.kt:38 [V grep]; framework/src/test/.../operator/OperatorDependencyTest.kt. Behaviour unchanged.
1. Split ScriptTools.kt (685 lines [V]) by pure moves: ScriptTools.kt keeps the `ScriptTools` object (:24); JsTools.kt
   gets `ScriptsRunJs` (:75) and `WorkspaceJsHost` (:138); ShellTool.kt gets `ScriptsRunShell` (:192) and its helpers;
   TermuxTool.kt gets `TermuxRun` (:635).
2. JsEngine.kt: `interface JsEngine { suspend fun run(code: String, inputJson: String?, host: JsHost?, timeoutMs: Long): JsOutcome }`;
   `JsSandbox` implements it; `ScriptsRunJs` takes a `JsEngine`; `OperatorToolset.create(d, js: JsEngine =
   JsSandbox(d.limits.js))` (today built inline, OperatorToolset.kt:130 [V]).
3. `OperatorToolDependencies.launches: RecentLaunches = RecentLaunches()` (records `packageName → at`, answers
   `launchedWithin(packageName, ms)`); `OperatorLimits.screenOpenWaitMs = 10_000`, `launchRecentMs = 15_000`
   (`screenPollMs = 250` exists, OperatorToolset.kt:87 [V]).
4. `TeamTools.ASK_OWNER_ID` constant (in TeamTools.kt, one line) and its offer in `WorkSteps.teamTools` when
   `settings.agentQuestionsEnabled` and the registry has it, as the other two (WorkSteps.kt:190-191 [V]).
5. `SettingsView` for every holder above (DefaultAgentManager and MemorySeeder keep the writer: they write
   `directorAgentId`/`seedVersion` (DefaultAgentManager.kt:170, :230) and `memorySeedVersion` (MemorySeeder.kt:70) [V]
   and no agent-facing root reaches them [V grep: only MemorySeeder and AppGraph hold `AgentManager`]).
6. Framework architecture test: `SettingsRepository` owner-only, `update` in `ownerOnlyActions`, roots +=
   `DefaultModelGateway`, `DefaultAgentChat`, `DefaultRegistryDirectory`, `DefaultToolAuthorizer`.
Tests: `tools/gw --offline :framework:test :memory:test`; app compiles.

#### WP1d. App seams and wiring hooks (the wave-1 integrator). Size M.

Owns in wave 1: app/build.gradle.kts, app/src/main/AndroidManifest.xml, AiControlCenterApp.kt, MainActivity.kt,
di/Ports.kt, di/ProductionPorts.kt, di/AppGraph.kt, di/AppStartup.kt, new di/wiring/*.kt, new res/xml/
data_extraction_rules.xml and backup_rules.xml, the new stub files below, app/src/test/.../testing/TestApp.kt,
app/src/test/.../di/OperatorDependencyTest.kt. Starts after WP1a, WP1b, WP1c are merged. Behaviour unchanged except
R6 (device-transfer exclusions), which is complete here.
1. **AiControlCenterApp** (today :24-58 [V]):
   - `onCreate`: `if (Process.isIsolated()) return` after `super.onCreate()` (API 28 [D]
     https://developer.android.com/reference/android/os/Process), then `AppProcessHooks.onCreate(this)`.
   - `appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + AppProcessHooks.scopeContext(this))`.
   - `val dataLayer: DataLayer by lazy { DataLayer(this) }` shared by the graph and the upkeep job (F3: two Room instances
     on one SQLCipher file would have separate invalidation trackers).
   - `val sharedMemory: MemoryService by lazy { … }` (the one `DefaultMemoryService` of the process, built from
     `dataLayer` stores with `ReadOnlySettings`, `HashingEmbedder`, its own `ActivityRecorder`); `AppGraph` takes
     `memoryService: MemoryService? = null` and uses it when given (the KDoc at AppGraph.kt:207-209 requires one per
     process [V]).
   - `graph` builds `AppGraph(createProductionPorts(this, dataLayer), appScope, memoryService = sharedMemory, …)`
     then calls `AppProcessHooks.onGraphBuilt(this, it, appScope)`, into which the three `start()` calls of
     AiControlCenterApp.kt:48-52 move verbatim (app/work/GraphServices.kt).
   - `fun graphIfBuilt(): AppGraph?`; `val upkeep: UpkeepGraph by lazy { UpkeepGraph(dataLayer, sharedMemory) }`;
     `val recovery: OwnerRecovery` from `BackgroundWiring.production(…)`.
2. **Ports and ProductionPorts:** `createProductionPorts(context, data: DataLayer)` (today it builds its own DataLayer,
   ProductionPorts.kt:33-35 [V]). New `Ports` fields with in-memory or `None` defaults: `usageLedger`, `usageReports`,
   `modelPrices`, `ownerModelPrices`, `ownerUsage` (wired to WP1b's DataLayer members now), and one holder per wave-2
   package, built in ProductionPorts by one pre-inserted line each: `background = BackgroundWiring.production(context,
   data)`, `network = ModelsWiring.network(context)`, `scripts = ScriptsWiring.production(context)`, `backup =
   BackupWiring.production(context, data)`. TestApp gets the same fields with in-memory values.
3. **AppGraph → assemblies (pure moves; the public API of AppGraph stays, as delegating vals):**
   - di/wiring/ModelsWiring.kt `class ModelsAssembly(ports, settings: SettingsView, activity, adapterFactories, appScope, network: NetworkStatus)`:
     AppGraph.kt:165-203 (registry directory, provider connections, gateway, provider manager) [V line map]. **WP4.**
   - di/wiring/OperatorWiring.kt `class OperatorAssembly(…)`: AppGraph.kt:225-389 (protected packages, workspace,
     screen sessions, request queue, grant lookup, waiters, web reader, capabilities, operator facts, broker, capability
     requester, tool registry, executor, owner ports) with `js: JsEngine` from `ports.scripts`. **WP5.**
   - di/wiring/AgentsWiring.kt `class AgentsAssembly(…)`: the monitor and `AgentRunner` (AppGraph.kt:391-416). **WP15.**
   - di/wiring/TeamWiring.kt `class TeamAssembly(…)`: orchestrator and chat (AppGraph.kt:419-469), receiving
     `providerManager`; exposes `ownerConversation: OwnerConversation = OwnerConversation.Unavailable`. **WP7.**
   - di/wiring/ScriptsWiring.kt `class ScriptsPorts(val engine: ((JsLimits) -> JsEngine)? = null)`, `object
     ScriptsWiring { fun production(context): ScriptsPorts = ScriptsPorts() }`. **WP6.**
   - di/wiring/BackgroundWiring.kt `class BackgroundPorts(health: BackgroundHealth, exits: ExitHistory, storage:
     StorageWatch, startup: StartupHooks, recovery: OwnerRecovery?, screens: BackgroundScreens)` with `None` defaults,
     where `interface BackgroundScreens { fun batteryExemption(): Intent?; fun appDetails(): Intent? }` is declared in
     the same file and `interface StartupHooks` in di/AppStartup.kt (item 4); `object BackgroundWiring { fun
     production(context, data) = BackgroundPorts() }`. **WP2.**
   - di/wiring/BackupWiring.kt `fun production(context, data): OwnerBackup? = null`. **WP3.**
   - New AppGraph public vals with defaults so wave 3 can bind UI at once: `ownerQuestions` (via
     `ownerRequests.answer`), `ownerConversation`, `usageReports`, `modelPrices`, `ownerModelPrices`, `ownerUsage`,
     `backgroundHealth`, `backgroundScreens`, `storageWatch`, `exitHistory`, `ownerBackup`, `ownerRecovery`, `maintenanceGate`.
   - Order inside AppGraph: models → memory → operator → agents → team → startup, as today.
4. **AppStartup** gets `hooks: StartupHooks = StartupHooks.None` (`beforeSteps()`, `interruptCause(): String?`,
   `resumeAllowed(): Boolean`, `afterSteps()`), called at the first step (before AppStartup.kt:93), at the
   recovery step (:119, now `orchestrator?.recoverInterrupted(hooks.interruptCause(), hooks.resumeAllowed())`), and
   after the last step; a new step "Could not mark interrupted chat replies" `agentChat.recoverInterruptedTurns()`
   before :119.
5. **MainActivity** never touches `graph` before `AppGate` (app/health/AppGate.kt, stub: open at once) says the
   database opened: `setContent { GateHost(gate, recovery = { RecoveryHost(it) }) { AiControlCenterRoot(graph) } }`;
   `graph.start()`, `openRequestedObjective` and `onAppForegrounded` run through `gate.whenOpen`. `RecoveryHost` is a
   stub in ui/recovery/RecoveryScreen.kt marked `@Phase6Stub` (WP10 fills it).
6. **Manifest:** the three permissions; `<service android:name=".work.UpkeepJobService"
   android:permission="android.permission.BIND_JOB_SERVICE" android:exported="false"/>`; `<receiver
   android:name=".work.WakeReceiver" android:exported="false"/>`; `<service android:name=".scripts.ScriptProcessService"
   android:isolatedProcess="true" android:process=":scripts" android:exported="false"/>`; the special-use subtype text
   widened to "the owner's agents answering a chat or working on an objective"; `android:dataExtractionRules=
   "@xml/data_extraction_rules"` (`<cloud-backup>` and `<device-transfer>` excluding every domain) and
   `android:fullBackupContent="@xml/backup_rules"` (exclude all, Android 11 and lower). Reason: for apps targeting
   31+, "on devices from some device manufacturers, specifying android:allowBackup="false" … doesn't disable
   device-to-device transfers" ([D] https://developer.android.com/identity/data/autobackup, Android 12+); such a
   transfer would carry the database without its key (DataLayer.kt:62, :158 [V]). No `<cross-platform-transfer>`: it
   needs an iOS bundle id and team id (Android 16 QPR2, same page) (F28).
7. **Stubs** that compile and do nothing, each owned by the named wave-2 package: app/work/AppProcessHooks.kt,
   GraphServices.kt (moved code), UpkeepJobService.kt (`onStartJob` returns false), WakeReceiver.kt, UpkeepGraph.kt
   (WP2); app/health/AppGate.kt (WP2); app/scripts/ScriptProcessService.kt (`onBind` returns null) (WP6).
8. **app/build.gradle.kts:** `buildFeatures { aidl = true }` (today compose and buildConfig, :60-63 [V]); androidTest
   dependencies as device/build.gradle.kts:62-66 (`testInstrumentationRunner` is set, :18 [V]). R8 stays off (:49 [V]),
   so the AIDL stubs, the job and the isolated service need no keep rules (F28). versionCode stays 8 until WP14.
9. **App architecture test:** `SettingsRepository` owner-only; `ReadOnlySettings` in `views`; `update` in the owner
   actions; roots unchanged until WP8 adds the wave-2 roots.
Tests: the full suite (`tools/gw --offline test`); `AppGraphTest` unchanged; a new `AssemblyMoveTest` asserting that
the public AppGraph members are the same instances as the assemblies' (no duplicate gateway or broker); a manifest
test for R6's XML (lint is available offline, 32.5.2 [V per critic]).

#### WP6 spike (wave 1, throwaway branch `p6/spike-scripts`, emulator only). Size S-M.

A minimal isolated service with an endless loop answers, with numbers, in `scratchpad/phase6/spike-scripts.md`:
Q1 can the app's `Process.killProcess` end it [A: no, its UID differs]; Q2 does `unbindService` end it and how fast, and
does a frozen cached isolated process stop burning CPU; Q3 does the watchdog's `Process.killProcess(Process.myPid())`
end it and does the app see `binderDied`; Q4 a 4 MB pipe transfer; Q5 process start time; Q6 three watchdog kills within
60 s, then the time to the fourth run, with and without `unbindService` plus a fresh bind (restart back-off [K]); Q7 a
round trip with a 200,000-character input and a 100,000-character result through pipes while accessibility events flow;
Q8 after an OOM in the isolated process, which `processName` and reason `getHistoricalProcessExitReasons` reports and
whether a crash dialog shows; Q9 cold bind plus Rhino start time on TCG; Q10 can the isolated process read and write a
`createReliablePipe()` descriptor from the app (SELinux `isolated_app`). Decision: the isolated design if Q3, Q7 and Q10
pass; otherwise `android:process=":scripts"` with the app's UID (the app can kill it; Rhino's `ClassShutter` stays the
sandbox; LIMITATIONS says the process could read the app's files if Rhino were escaped).

### Wave 2 (base `p6/wave1` = the merged wave 1)

Seven packages; at most four agents at once (more only wait for the Gradle lock); queue order WP4, WP15, WP5, WP7, then
WP2, WP6, WP3 as agents free up. Each package works in its own worktree, runs module-scoped tests, and may add its
own app-level integration tests under its own test packages through `TestApp`. Merge order (contracts and gateway
first, Android and emulator-dependent last): **WP4, WP15, WP7, WP5, WP2, WP3, WP6**; the full suite after each merge.

#### WP4. Usage, cost, limits and provider trouble. Size L.

Purpose: goals 1, 2, 7; D28, D29, P8; rules 6, 7, 25, 29. Owns: framework/llm/* (all files), framework/registry/*,
new device/net/AndroidNetworkStatus.kt, app/di/wiring/ModelsWiring.kt, their tests.
1. **Capture.** `ModelCall` gains `attemptStartedAt`, `firstChunkAt`, `firstTextAt`, `retryWaitMs`, `httpStatus`,
   `retryAfterMs`, measured on a `TimeSource`; set in `chatStream` and `chat` (DefaultModelGateway.kt:70-89, :97-126
   [V]); `ModelCallJournal.record` adds them to the MODEL_CALL event and calls `usage.record(sample)` best effort, with
   `agentId`, `taskId`, `rootTaskId`, `conversationId` from `CallContext` and cached tokens when reported
   (`TokenUsage.cachedInputTokens`, core/llm/Chat.kt:126-131 [V]).
2. **Daily limits and spending cap.** `DailyLimiter` (framework/llm/DailyLimiter.kt): today's calls, tokens and money
   kept in memory, seeded from `usageReports.today()` at first use; `reserve()` under one mutex before every attempt
   (the loops at :72-75 and :100-103), `commit(sample)` or `release()` after; money = tokens × current price of the
   called model (`CostEstimate`), only for priced models; limits from `SettingsView`; providers on this device counted,
   not limited. Refusal: `LlmException.DailyLimitReached("Today's limit of 200 model answers is reached. It starts
   again at midnight, or you can raise it in Settings > Daily limits.")` (money and token variants likewise).
3. **Provider trouble (D28).** Three final retryable failures from one provider within 5 minutes store OFFLINE with
   "Not answering since 10:42" (never over AUTH_ERROR). `ProviderRechecks` (framework/llm/ProviderRechecks.kt)
   re-checks an OFFLINE or ERROR provider after 1, 2, 5 and 10 minutes, then every 10 minutes, for at most 60 minutes
   unless `recheckIfStale` asks again; at once when `NetworkStatus` reports a network again
   (`ConnectivityManager.registerDefaultNetworkCallback`, API 24, `ACCESS_NETWORK_STATE`); `recheckIfStale(id, 60 s)`
   bounded at 10 s; never in local-only mode for off-phone providers (DefaultProviderManager.kt:226-228, :301 [V]);
   a rejected key is never re-checked by itself; `timedWaits.nextDueAt` = the next scheduled recheck. Today one failed
   check blocks agents until start, a return after 10 minutes or Check now (AppStartup.kt:212-217 [V]).
4. **429.** Rate-limit answers already wait for Retry-After (RetryPolicy.kt:27-33 [V]); after the last retry the
   failure stays `LlmException.RateLimited` so WP7 treats it as a timed wait, and `usage_hours.rate_limited_waits`
   counts it.
5. **Names (D29).** `ModelNames.sameModel` strips `-latest`, `-beta`, `-preview` and date stamps before comparing;
   used by the journal's `modelSubstituted` and by ChatPresentation (WP11b).
6. **`matchesStoredKey(text)`** for rule 32: constant-time compare against every provider key in the vault, owner path
   only.
7. **P8.** `DefaultRegistryDirectory` shares one registry snapshot (`shareIn(appScope, WhileSubscribed(5_000), replay =
   1)`; today every `observe()` builds its own flows, DefaultRegistryDirectory.kt:26-45 [V per reader]).
8. **Cheaper ways to run.** `ProviderPresets` descriptions gain one plain line on cost each, worded as "check the
   provider's page; terms change": Gemini (free tier with limits; Google may use free-tier prompts to improve its
   products), OpenRouter (`:free` models with daily request limits; some providers log prompts), Groq (free plan with
   a small per-minute token limit), Ollama and LM Studio (free and private on your own computer), xAI (no free tier).
   These facts come from the owner note's third-party pages, November 2026 [A]; they cannot be checked here (no
   internet on the emulator) and LIMITATIONS says so.
9. `UsageHousekeeping.prune(now, settings)`: `usage_hours` past `usageRetentionDays`; `work_usage` of work that no
   longer exists.
10. **ModelsWiring.kt:** the assembly builds the limiter, rechecks (started on `appScope`) and the network-aware
    provider manager; `network(context)` returns `AndroidNetworkStatus`.
Tests: JVM with a scripted streaming client and the test scheduler: TTFT, answer time, retry wait; error class →
column; day and month sums in a whole-hour and a half-hour zone; limit boundary, retry after the limit, two concurrent
reservations, midnight; on-device not limited; money cap with and without a price; outage marking and no override of
an auth error; recheck schedule with a fake clock; network-back recheck; 429 exhaustion stays RateLimited;
`ModelNames` ("grok-4-latest" vs "grok-4-0709"); `matchesStoredKey`; Robolectric `AndroidNetworkStatus` with the
connectivity shadow at SDK 35.

#### WP15. Lean requests (owner request #43). Size M-L.

Purpose: goal 1, rule 28; the AgentRunner side of D4. Owns: framework/agents/* (AgentRunner.kt, AgentPromptComposer.kt,
AgentRun.kt, AgentTemplates.kt, AgentTurnInfo.kt, DefaultAgentActivityMonitor.kt, DefaultAgentManager.kt), new
framework/agents/ToolMenu.kt, app/di/wiring/AgentsWiring.kt, their tests.
Measured [V per owner note]: the Operator agent's request carries 49 tool definitions, about 39 KB of JSON
(scratchpad/mockmodel/tools.json, 39,210 bytes indented [V]), on every call; `offeredTools` sends every enabled tool,
sorted (AgentRunner.kt:167-169 [V]).
1. **Tool menu.** When an agent's enabled tools would take more than 2,500 estimated tokens (`TokenEstimator`), the
   request offers: the always-on tools (the team tools inside an objective, `memory.search`, `clock.now` if enabled)
   plus `tools_open(family)`, whose description lists the agent's other families (the id before the dot) with one
   line each. When called, the family's tools are added for the rest of the run, appended after the existing ones in
   opening order, and the answer lists them. A call to an enabled tool not yet offered runs as usual (authorisation
   unchanged) and opens its family. Agents under the threshold get all their tools, as today.
2. **Stable prefix.** The system prompt (AgentPromptComposer, "never on the time or the conversation", :11 [V]) and the
   tool list stay byte-identical between calls of one run until a family opens; recalled memories stay after the
   prefix.
3. **D4 caller.** `AgentRunner.Waits.waiting` (AgentRunner.kt:403-413 [V]) calls `screen.holdSession(requestId, agent,
   package)` for a screen tool's request with an app constraint; `releaseSession` in a `finally` around the tool call
   (:350), so the hold covers `afterOwnerAnswered`.
4. Cost per chat and task flows from `CallContext` (WP4 records it); the runner passes `conversationId` when the chat
   gives it (K8).
Tests: per starter agent (AgentTemplates) the first request's tool definitions ≤ 3,000 tokens and the system prompt ≤
1,500, with the Operator's before and after printed in the test report; a scripted model that opens "files" and then
writes a file; a call to an unopened enabled tool runs; a call to a tool the agent does not have is refused as today;
prefix stability over a 5-call run; AgentRunner virtual-time screen hold over a 60 s approval.

#### WP7. Orchestration, teamwork and chat. Size L.

Purpose: goal 2 (pause), 6, 7; C2 (Tier B), C4, C7, R3, R4 part 1, D20-D23, D27 selector; rules 24, 25 (waiting
side). Owns: framework/orchestration/* except TeamTools.kt; framework/chat/*; app/di/wiring/TeamWiring.kt; tests.
1. **Limit pauses, not fails.** A task whose model call raises `DailyLimitReached` puts its objective in PAUSED with
   "Paused: today's limit is reached. Resume after midnight or raise the limit in Settings." (no attempt consumed).
2. **R4 part 1 + 429.** `WorkSteps.failed` (WorkSteps.kt:159-177) stores `retryAt` (30 s, then 2 min) after a
   retryable model error or `RateLimited`; `chooseAgent` (WorkSteps.kt:103-111 [V]) treats a `NotReady` whose reason
   `isTransient` as a wait with `retryAt` ("Waiting: xAI is not answering; trying again at 10:45") until the objective's
   own limit, calling `recheckIfStale` first; the driver skips tasks whose `retryAt` is in the future, wakes at the
   earliest by delay **or** on `timedWaits.wake()` (re-checking against the wall clock), and publishes
   `timedWaits.nextDueAt`.
3. **C2 notes (Tier B).** `OwnerNotes` implements `OwnerConversation` over `ObjectiveJournal` (GUIDANCE from
   `Participant.Owner` to the Director or the task's agent); `BriefWriter.work`, `review`, `arbitration`, `synthesis`
   (BriefWriter.kt:117, :181, :208, :231) add the `owner_note` frames of rule 24 with "This brief holds 2 notes from the
   owner, ids n1 and n2; nothing else is"; read only when `from == Participant.Owner` and built by `OwnerNotes`; not
   counted by `TeamGuards.messageLimit`. `DataBlock.frame` escapes the `owner_note` tag and label. `ObjectiveJournal`
   logs ANSWER and GUIDANCE without the body ("You wrote a note to the team"; ObjectiveJournal.kt:66 logs
   `firstLine(body)` today [V]).
4. **C4 reviewer.** `AgentSelector.forReview` (:61-71) excludes contributors unless nobody else can run, keeps the
   previous round's reviewer, adds 1.0 for a different resolved model; the status says "(same model as the author)".
5. **D27.** `AgentSelector.score` (:85-92) adds 0.75 per Coding or Reasoning step above BASIC when the task's words
   call for it; UNKNOWN adds nothing.
6. **C7.** Teammates' answers and delegated results framed with `DataBlock.frame` (TeamDesk.kt:111, :189).
7. **`Participant.Space`** for memory shares (WorkSteps.kt:88-91).
8. **R1 consumer.** `recoverInterrupted(cause, resumeAllowed)`: the cause becomes part of the interrupted text
   (DefaultOrchestrator.kt:542 [V] becomes a template); `resumeAllowed = false` overrides auto-resume.
9. **D20** (ObjectiveDriver.kt:671-689), **D21** (:771), **D22** (KnowledgeKeeper.kt:166-169), **D23**
   (KnowledgeKeeper.kt:98-101), as in revision 1.
10. **Chat (R3).** `DefaultAgentChat.send` marks the turn (around DefaultAgentChat.kt:181-200);
    `recoverInterruptedTurns()` stores "The app stopped before <agent> finished answering. Send your message again to
    try again." through `storeError` (:401); `sendAgain` re-runs the last owner message without storing it twice;
    `recheckIfStale` before refusing for OFFLINE; `DailyLimitReached` shown as the sentence; `CallContext.conversationId`.
11. **`MaintenanceGate`:** `startObjective` and `send` refuse while a restore runs ("The app is restoring a backup").
12. **Shorter role protocols (Tier B):** a token count test for each brief kind (planning, work, review, synthesis) at
    the seeded team; remove repeated sentences only; the count goes down or stays.
Tests: limit → PAUSED not FAILED, resume after a raised limit; `ObjectiveEndToEndTest` with scripted 503s and 429s
(waits, recovers, finishes) and with an OFFLINE provider that comes back; wake-up via `timedWaits.wake()` with a fake
clock; notes in each brief kind, budget and order; forged note in a delegated result absent from the notes section;
GUIDANCE source scan; `AgentSelectorTest`; `ReviewFlowTest`; `TeamToolsTest` framing; D20 crash-harness kill point;
KnowledgeKeeper three-edit test; chat: scope cancelled mid-turn then a new instance shows the notice, Send again stores
once.

#### WP5. Broker, questions and phone-operator fixes. Size L.

Purpose: goals 12; C1 (Tier B), C3, D4 (device side), D5-D8, D10-D12; rules 12-15, 32, 33. Owns: framework/operator/*
except JsSandbox.kt, JsEngine.kt, JsTools.kt; framework/tools/*; framework/orchestration/TeamTools.kt;
providers/web/AddressGuard.kt; device/screen/*; app/operator/ScreenSessions.kt; app/di/wiring/OperatorWiring.kt; tests.
1. **C1 `team.ask_owner(question, options?)` (Tier B)** (`AskOwnerTool` in TeamTools.kt) for the assignee of a running
   WORK task when `agentQuestionsEnabled`. `OwnerQuestions` reuses the broker's wait: the block of
   `PermissionBroker.ask` (PermissionBroker.kt:190-228) is extracted into one helper used by both. QUESTION (agent →
   You) and ANSWER (You → agent) through `ObjectiveJournal.send`. The answer returns "The owner answered, in their own
   words:" framed by `DataBlock.frame`; time-out: "The owner did not answer within 20 minutes. Make the most reasonable
   choice, say which one in your result, and go on." Caps and the open-request refusal of rule 13; rule 32 in
   `OwnerServices.answer` (uses `AnswerCheck` and `ProviderManager.matchesStoredKey`) and the after-answer APPROVAL in
   the broker (one flag per task, held in the broker).
2. **C3 + D10 `OwnerAttention`**, one mutex shared by `DefaultCapabilityRequester` (today 3 asks per agent,
   OwnerPorts.kt:194 [V]), `PermissionBroker.ask` and `OwnerQuestions`; refusal texts tell the model to assume, say so
   and go on; the owner is told once per task.
3. **D11 shell slot.** `PermissionBroker.authorize` calls `prepared.beforeOwnerAsked()` after the grant check and before
   the APPROVAL request (PermissionBroker.kt:136-141); `ToolExecutor.carryOut` calls `callEnded()` in a `finally`;
   `ScriptsRunShell` reserves with `LocalWorkspace.reserveCommand` instead of `commandRunning()` in assess.
4. **D12.** `OwnerWaitLedger` filled in the broker's and the questions' `finally`; `RecentFileChanges.count` subtracts
   owner-wait time (BulkChanges.kt:36-46); `ToolExecutor.record` logs `ownerWaitMs`.
5. **D4 device side and S22.** `AccessibilityScreenOperator` keeps holds by `holdId` with `Session.waitingForOwner` and
   the banner "<agent> waits for your answer to act in <app> · Stop"; `endSession(agentName)` never ends a session
   with an open hold; `ScreenSessions` mirrors it; Stop clears holds.
6. **D5.** `AppsOpen`, `AppsOpenLink`, `BrowserOpen` record launches in `RecentLaunches`; `ScreenTools.front`
   (ScreenTools.kt:90-101) polls every 250 ms for up to 10 s when this agent launched within 15 s.
7. **D6.** `frontState()` returns Hidden for a window without a readable root (AccessibilityScreenOperator.kt:157-164,
   :458-463): "The window in front is one Android does not let this app read, such as its permission screens. Nothing
   was read."
8. **D7.** device/screen stores "screen service was ready"; at start, NOT_ENABLED with the flag set gives
   `switchedOffByAndroid = true` ("Android switched screen control off after the app was stopped. Turn it on again in
   Android's accessibility settings."). On API 35+ it says "after the app was force-stopped" only when
   `ApplicationStartInfo.wasForceStopped()` of the current start says so (`getHistoricalProcessStartReasons`, API 35,
   present in the 37.2 jar [V per critic]); `REASON_USER_STOPPED` means a multi-user stop and Force stop is
   `REASON_USER_REQUESTED` like a swipe from Recents [D] https://developer.android.com/reference/android/app/ApplicationExitInfo
   (F7). `sdkInt` is injected (F2).
9. **D8.** `WebReadPage.assess` (WebTools.kt:50-61) calls `WebAddressPolicy.literalRefusal` before authorisation;
   AddressGuard calls the same function as its literal check. Names resolving to private addresses are still refused
   only when read.
10. **S15.** `DeviceFacts.screenOn()` (`PowerManager.isInteractive`) next to `keyguardLocked()`
    (ScreenAvailability.kt:119, :131 [V]); `AccessibilityScreenOperator`'s gates (:440, :468) refuse with the rule-12
    text.
Tests: real-broker tests for every rule (answered, decide yourself, expired, cancelled by pause and by task end, app
killed while waiting → "No longer waiting", deadlines moved as in OwnerWaitTest and OwnerWaitRecoveryTest, caps under
concurrency, approval-word options, question refused beside an open request, after-answer approval, key and code
answers, framing with forged closing tags); the Phase 5 shell probes; bulk window (15 changes, 11-minute wait, 6
changes → asks); `FakeScreenOperator` sequences for D5; broker test for D8 (`http://192.168.1.10/`,
`http://router.local/`, decimal/octal/IPv6-mapped, trailing dot); device Robolectric at SDK 35 for holds per request,
Hidden, the D7 flag with a forced `sdkInt` and a fake start-info list, and screen-off refusal; framework architecture
test roots += `OwnerQuestions`.

#### WP2. Background work and recovery, Android side. Size L.

Purpose: goals 3-6, 8; rules 1, 4, 5, 9, 10, 26, 30, 31. Owns: app/work/* (existing files and the WP1d stubs; new
WakeHold.kt, WakeAlarms.kt, ResultNotifications.kt, SafetyCollectors.kt), app/health/* (AppGate.kt and new
StartLedger.kt, CrashNotes.kt, AppScopeExceptionHandler.kt, AndroidStartupHooks.kt, SafeStart.kt, RecoveryActions.kt),
app/di/wiring/BackgroundWiring.kt, device/health/* (new), data/DataLayer.kt, data/security/*, data/recovery/* (new),
res/drawable/ic_stat_done.xml, ic_stat_stopped.xml, ic_stat_question.xml, and their tests.
1. **Wake lock (A1, F8).** `WakeHold` in `ObjectiveWorkService`: one `PARTIAL_WAKE_LOCK` "aicc:agents", not
   reference-counted, `acquire(10 min)`, renewed every 5 min, under rule 5's condition, from
   `AgentActivityMonitor.states` (WORKING), `AgentChat.liveTurns`, and `orchestrator.timedWaits` /
   `providerManager.timedWaits`; released when only waiting, paused, at `onDestroy`, and when
   `keepAwakeWhileAgentsWork` is off. Longer waits: `WakeAlarms` sets one `setAndAllowWhileIdle` alarm; `WakeReceiver`
   calls `graphIfBuilt()?.let { wake both timedWaits }` and holds the wake lock for up to 30 s. Whether the Director's
   planning shows as WORKING in the monitor is [A]; the service test with the real orchestrator through `TestApp`
   checks it. Official guidance: with a foreground service and the screen off the device may suspend; hold a wake lock
   when suspending would hurt ([D] https://developer.android.com/develop/background-work/background-tasks/awake).
   Notification line "Keeping the phone awake while agents work" while held.
2. **Chats in the service (A2).** `ObjectiveWorkController.shouldRun` (ObjectiveWorkController.kt:53 [V]) becomes
   `(objectivesRunning || liveChatTurns) && (inForeground || ignoringBatteryOptimizations)`: battery optimisation off
   is a documented exemption for starting a foreground service from the background ([D]
   https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start, Android 12+, F29); the
   `IllegalStateException` catch stays. Notice "<agent> is answering · Stop" (Stop = `AgentChat.cancel`).
   `specialUse` has no time limit on Android 14-17 ([D] https://developer.android.com/develop/background-work/services/fgs/timeout).
3. **Fewer reads (P1).** `ObjectiveWorkService.notices` uses `observeProgress(rootIds)` instead of whole trees.
4. **Background check (A3).** `device/health/AndroidBackgroundHealth`: notifications, `isIgnoringBatteryOptimizations`
   (API 23), `isBackgroundRestricted` (API 28), `getAppStandbyBucket` (API 28; warn only for rare/restricted); intents
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and `ACTION_APPLICATION_DETAILS_SETTINGS` as the fallback, exposed to the
   UI through `BackgroundWiring` (`BackgroundScreens`); `ActivityNotFoundException` answered in plain words. Play policy
   restricts the battery-exemption request to core use cases and lists task automation as acceptable; it does not apply
   to a sideloaded build ([D] https://developer.android.com/training/monitoring-device-state/doze-standby).
5. **Result notifications (A6, rule 4).** `ResultNotifications`: channel "Results" (default importance): "Finished:
   <objective title>", "Stopped: <app-written reason>", "Interrupted: <app-written reason>", "Paused: today's limit is
   reached", only while the app is not on screen and `notifyWhenWorkEnds` is on; tap opens the objective.
   `ApprovalNotifications` (app/work/ApprovalNotifications.kt:52-79 [V]) gains the QUESTION text "A question from
   <agent>. Open the app to read it." (never `request.summary` for a QUESTION) and one reminder 5 minutes before a
   request expires.
6. **Exit reasons (R1, F6, F7, F18).** `device/health/AndroidExitHistory` (`getHistoricalProcessExitReasons`, API 30+;
   only records whose `processName` equals `applicationInfo.processName`; each processed once by time; Android 9-10:
   "Android 9 and 10 do not say why"); kinds mapped from `REASON_*`, including `REASON_OTHER` whose description contains
   "MemoryLimiter" → MEMORY_LIMIT on Android 17.0, and `REASON_MEMORY_LIMITER` (17) and `REASON_ANOMALY` (18) by value
   (37.2) [D] https://developer.android.com/about/versions/17/behavior-changes-all; description text never stored or
   shown. `sdkInt` injected (F2).
7. **Safe start (R2, Tier B) and crash notes.** `StartLedger` (a small file in `noBackupFilesDir`): "started" with
   the versionCode at `beforeSteps`, "ok" at `afterSteps`. Safe start per rule 10, then `resumeAllowed = false` and a
   banner (WP10). `CrashNotes`: an uncaught handler chained to the previous one writes the exception class and the
   first 20 `com.aicontrolcenter` frames, no message text, main process only.
8. **Scope handler and supervised collectors (G7, rule 26).** `AppScopeExceptionHandler` (via
   `AppProcessHooks.scopeContext`) records to the activity log when it can, keeps the process for `Exception`, rethrows
   `Error`; `SafetyCollectors` runs the three collectors of GraphServices with restart and the fail-stop actions.
9. **Database cannot be opened (R5, F10).** `data/recovery/DatabaseHealth.check()` on IO, called by `AppGate` and by the
   upkeep job: opens the writable database once and classifies `DatabaseKeyException` (missing, damaged, keystore
   unavailable), "file is not a database", a failed migration, other. **Key generations:** `KeyGenerations` stores the
   current generation `N` in `noBackupFilesDir/database-keys/generation` (plain text, no secret; absent = 1); DataLayer
   uses `aicc.database-key.v<N>` and `aicc.secret-vault.v<N>` with the sealed-key and vault file names of generation N
   (generation 1 keeps today's names, DataLayer.kt:159, :184 and AndroidKeystoreVaultCipher.kt:58, :61 [V], so 0.5.2 data
   opens unchanged). `FileOwnerRecovery` (K14) implements rule 9's Start over and `RecoveryActions.restartApp` rule 30.
10. **Storage (R8).** `device/health/AndroidStorageWatch`: `getAllocatableBytes(getUuidForPath(filesDir))` (API 26) at
    start, before an objective starts and every 5 minutes while one runs; below 500 MB a Dashboard warning, below 100 MB
    the service pauses running objectives ("Paused: the phone is almost full") and New objective refuses.
11. **Upkeep job (R9, D36, rule 1).** `UpkeepJobService` (platform `JobScheduler`; androidx.work is not in the offline
    cache [V per readers]): idle, battery not low, every 24 h, not persisted (no `RECEIVE_BOOT_COMPLETED`), scheduled in
    `afterSteps`. `onStartJob` launches the work on its own scope, keeps the `JobParameters` and calls `jobFinished`;
    `onStopJob` cancels and returns true (Android 16 adds `STOP_REASON_TIMEOUT_ABANDONED` for jobs collected without
    `jobFinished` and counts jobs beside a foreground service against quotas [D]
    https://developer.android.com/about/versions/16/behavior-changes-all). `UpkeepGraph` runs memory upkeep
    (`sharedMemory.maintain()`, offline; skipped in local-only mode anyway), activity pruning, `UsageHousekeeping`; it
    returns at once when the ledger's last "ok" was another versionCode, the database check is not OK, safe start is on,
    or `MaintenanceGate.busy`.
Tests (Robolectric at SDK 35 only, with injected `sdkInt` for other branches; other API levels are "not verified" in
STATUS, F2): `ShadowPowerManager` and `ShadowAlarmManager` state table; service + controller (chat keeps the service,
Stop cancels the turn, battery-exempt start from the background); `ShadowActivityManager.addApplicationExitInfo` with
main and `:scripts` records and the 37.2 reasons; `ShadowNotificationManager` (rule 4 with the marker test); the job
with a recording HTTP layer (zero requests) and the version gate; `ShadowStorageManager`; ledger states; collector
fault injection; `DatabaseHealth` with an injected failing open helper; Start over with a damaged alias (fake
`VaultKeySource`) → generation 2 opens; process-restart intent recorded; reflection walk of rule 1; icons test;
instrumented (data/src/androidTest, camelCase): sealed key removed → classified, Start over with the real keystore
and SQLCipher, generation-1 data still opens after the upgrade.

#### WP3. Backup file (Tier B). Size M-L.

Purpose: goal Tier B "backup", rule 8, rule 27 (passphrase). Owns: data/backup/* (new), app/di/wiring/BackupWiring.kt,
their tests (data/src/test/.../backup, data/src/androidTest/.../backup).
- **Format `aicc-backup` v1 (frozen at the end of wave 1 and reviewed):** header (magic, format, schema version, app
  version, created at, KDF parameters, salt), then an AES-256-GCM body in 1 MiB chunks, each with a fresh 96-bit
  nonce and AAD = header hash + chunk number + "last chunk" flag. Key: `PBKDF2WithHmacSHA256`, 600,000 iterations in
  the header (accepted 100,000..10,000,000). The body is length-prefixed JSON records, not a zip.
- **Passphrase:** generated by default (`Passphrases.generate`, 100 bits, shown once with "Write this down; nobody can
  open the file without it, including you"); "Use my own" needs 16+ characters or 4+ words. Entry ignores case,
  spaces and dashes. Never stored or logged; cleared from the view model when the screen closes.
- **Contents (rule 8):** agents and their stats; providers without key aliases, status reset; models; model prices;
  AI-app registrations without accessibility authorisation; spaces; memories with links, provenance and history
  (embeddings recomputed with the hashing embedder on restore); conversations with owner messages and the agent's own
  reply text only; settings for the merge of rule 8. Not included: everything rule 8 lists, and objectives (said on
  the screen: "Finished objectives are not in the backup; copy or share a result you want to keep").
- **Restore:** inside `MaintenanceGate.runExclusive`; refused while an objective runs or a chat turn is live; free-space
  check (`getAllocatableBytes` ≥ 2 × file size) before starting; one Room transaction whose first statement checks
  "empty app" (only the starter team, built-in providers, the seeded space; no objectives, conversations or owner
  memories); every record validated like owner input (agent validation, `ProviderDraftValidator`, memory bounds; counts
  capped: 500 agents, 200,000 memories, 1 GB file); unknown fields ignored; any problem → nothing written and the owner
  told what was wrong; progress reported; then rule 30's restart. Saving says "Keep the app open while the file is
  saved"; a cut-off file is detected by the last-chunk flag.
- `RoomOwnerBackup` implements `OwnerBackup`; logged as USER_ACTION with counts only; allowed in local-only mode.
Tests: JVM round trip; one flipped bit in the header and in each chunk; reordered, duplicated and missing chunks;
wrong passphrase; format version 2 refused politely; hostile strings; every rule-8 test; PBKDF2-HMAC-SHA256 test vectors
of RFC 7914 §11 on the JVM and on the device (F30); Robolectric Room round trip with `DatabaseEncryption.None`;
instrumented round trip through a file URI against SQLCipher with the KDF time logged.

#### WP6. Scripts in their own process. Size L.

Purpose: D1, D2, D3; rules 11, 31. Owns: framework/operator/JsSandbox.kt, JsEngine.kt, JsTools.kt; app/scripts/* (the
WP1d stub and new IsolatedJsEngine.kt, RunHost.kt, ScriptWatchdog.kt); app/src/main/aidl/com/aicontrolcenter/app/scripts/*;
app/src/debug/ (a test-only `ScriptProbeService` and its debug manifest, F26); app/src/androidTest/**;
app/di/wiring/ScriptsWiring.kt; new testkit/src/main/.../contract/JsEngineContract.kt; tests.
Design (isolated variant; the spike may switch it to `:scripts` with the app's UID):
- **AIDL (F4, F22, S9, S19):** `IScriptRunner { oneway void run(in ParcelFileDescriptor request, IScriptHost host,
  IScriptResult result); int pid(); }` where the request pipe carries code, input and limits as JSON (≤ 600 KB, written
  by the app); `IScriptResult { oneway void done(int status, in ParcelFileDescriptor outcome); }` (outcome JSON read by
  the app with a 300 KB cap and a timeout); `IScriptHost` is a **fresh binder per run** created in the app with that
  run's `canWrite`, counters and `runOver`: `ParcelFileDescriptor read(String path)`, `String list(String path)`
  (≤ 64 KB), `String write(String path, in ParcelFileDescriptor content)`. The app enforces the 20-file cap, sizes,
  `canWrite`, never-overwrite, one outstanding call per run, and refuses every call after the run is over; host calls
  are served off the binder thread on IO with a timeout. No `Bundle`, `Parcelable` or `Serializable` from the script
  side. Binder sizes: `IBinder.getSuggestedMaxIpcSizeBytes()` (API 30, [D] https://developer.android.com/reference/android/os/IBinder;
  64 KB in AOSP [K]).
- **Process per slot (F23):** `bindIsolatedService(intent, BIND_AUTO_CREATE, "js-0"/"js-1", executor, connection)` on
  API 29+; on API 28 one shared process, and the answer of a run ended by the other run's watchdog says so.
- **Death and restart (F5):** on `binderDied`, `onServiceDisconnected`, `onBindingDied` or `onNullBinding` the engine
  calls `unbindService` at once and marks the slot dead; the next run binds afresh with its own bound
  (`JsLimits.bindTimeoutMs`, default 30 s, message "The script engine did not start; the phone is busy. Nothing of the
  script ran.").
- **Watchdog:** a normal-priority thread in the script process reports Stopped and kills its own process when a run is
  overdue by grace + 3 s; the app unbinds a run overdue by grace + 6 s. An uncaught error or OOM in the script
  process reports "the script used too much memory" and kills its process quietly (no crash record, no dialog; F6).
- **D2 (F21):** the script's clock starts at its own code (JsSandbox.kt:301), not at thread start (:222, :627); engine
  start has its own bound `JsLimits.engineStartMs` (default 30 s), separate from the bind bound. Ships even if the
  process part slips.
- `AiControlCenterApp.onCreate` returns at once in the isolated process (WP1d). Fallback if binding fails on a phone:
  in-process `JsSandbox` with today's MAX_STUCK rule, said in the answer.
Tests: `JsEngineContract` against `JsSandbox`, a fake remote engine that dies and starts slowly, and a hostile engine
(25 writes, writes after done, oversized reads and results); Robolectric `IsolatedJsEngine` with
`ShadowApplication.setComponentNameAndServiceForBindService` (overdue → unbind and rebind; death mid-run; third run
waits; host calls after the run refused); JVM D2 with a slow-start hook; emulator `ScriptProcessDeviceTest` (camelCase
names): `1+1`; the endless iterator loop three times then `1+1` within the restart back-off; the app's pid unchanged;
`Process.isIsolated()` and no access to the app's data folder through the debug-only probe; a 4 MB read; cold bind and
engine-start times logged, the 5 s default asserted on a warm second run; a memory hog ends only the script process and
adds no main-process exit.

### Wave 3 (base `p6/wave2`)

#### WP8. App integration and shared files (3a, then integration owner). Size M.

Owns in waves 3-6 every file of the "shared files" table. 3a (before any 3b agent starts): routes
`TeamRoute(objectiveId: String? = null)` (section TASKS), `ActivityRoute(tab: ActivityTab = LOG)` (data object → data
class; `Section.route` and `isTopLevel` updated, Destinations.kt:40, :131-141, :150-152 [V]), `BackupRoute` and
`DailyLimitsRoute` (SETTINGS), `QuestionRoute(requestId)` (TOOLS, rule 13); new composables and view models with final
signatures as `@Phase6Stub` files owned by one 3b package each; Settings slots (`BackgroundSection`,
`DailyLimitsSection`, `BackupSection`, `UsageHistorySection`, `MotionSection`), Dashboard slots (`TodayTile`,
`GettingStartedCard`, `PhoneAttention`, `SafeStartBanner`); `ShellViewModel`/`NavigationChrome` ("Waiting for you"
label and badge counting questions); the `ShareReceiverActivity` manifest entry (exported, SEND and SEND_MULTIPLE of
`text/*` only) with a stub, only if WP16 is scheduled; app architecture test roots += `OwnerQuestions`, the script
host, `ProviderRechecks`, `UpkeepJobService` (rules 1, 3, 11); the release merged-manifest test (rules 22, 23); D45
StrictMode (thread and VM policies, log only, debug builds). Later: merges every 3b branch, the full suite after each
merge, resolves conflicts, runs the stub gate at the end of wave 3.

Where the new screens sit (the eight sections stay; Destinations.kt:107-145 [V]):

| Screen or part | Section | Reached from |
|---|---|---|
| Team view (`TeamRoute`) | Tasks | Dashboard network card; objective header |
| Usage and cost (tab of `ActivityRoute`) | Activity | Activity tabs; Dashboard Today tile |
| Today tile (answers, tokens, cost today and this month), Getting started, phone attention items, safe-start banner | Dashboard | – |
| Price fields, "Speed and reliability", cheaper-helper suggestion (model screen) | Models | the existing model screen |
| Questions (`QuestionRoute`); "Waiting for you" list (today's Approvals) | Tools | shell badge, notification, chat, objective |
| Notes to the team (composer in the Conversation tab); cost line in the objective header and task screen | Tasks | objective screen; Team view node sheet |
| Working in the background, Daily limits, Question time-out, Backup, Usage history, Appearance | Settings | Settings; Dashboard attention items link here |
| Recovery screen | outside navigation | `AppGate` instead of the shell |
| Cut-off notice, cost line | Agents (chat) | the chat |
| Share screen ("Save to memory") | outside navigation | `ShareReceiverActivity` |

#### WP9. Team view (3b). Size L.

Owns app/ui/team/* (new), ui/dashboard/network/*, NetworkCard.kt, DashboardViewModel.kt. As revision 1 without
replay: `TeamGraphBuilder.build(snapshot, summaries, headers, requests, monitor, now)` pure over K1, K2 and requests;
"You" at the top, the Director in the centre, agents on a ring, spaces along the bottom; caps 30 agents, 6 spaces, 120
links; link styles by kind plus a 20 dp icon, never colour alone; node, link and "You" sheets; live dots (at most 8 in
flight, one `withFrameNanos` loop only while something moves); Picture/List toggle (list first with TalkBack or font
scale ≥ 1.5), traversal order ([D] https://developer.android.com/develop/ui/compose/accessibility/traversal), custom
actions, one polite live summary at most every 10 s, 48 dp targets; Calm and `areAnimatorsEnabled()`; dashboard card
N3/N6/P2/P4. Tests: `TeamGraphBuilderTest`, `PulseSchedulerTest`, `TeamLayoutTest`, view-model tests through `TestApp`,
Compose semantics tests (counts, not times), Roborazzi.

#### WP10. Monitoring, cost, background, recovery and backup screens (3b). Size L.

Owns ui/activity/* (Log paging, search (Tier B), new UsageScreen.kt, UsageViewModel.kt), ui/dashboard/TodayTile.kt,
GettingStartedCard.kt, PhoneAttention.kt, SafeStartBanner.kt, DashboardExtrasViewModel.kt, Attention.kt,
DashboardSections.kt, ui/settings/* except SettingsScreen.kt, ui/recovery/* (the WP1d stub and the screen), ui/backup/*.
- Usage and cost: Today / 7 days / 30 days / this month; how much (answers, tokens, estimated cost) by agent and by
  model; reliability in words; average time to first words and full answer (models with 5+ answers); "Waiting for you"
  answer times; every row links to the log filtered; "estimated from the prices you entered".
- Settings: Working in the background (A3 with buttons; keep awake; tell me when work ends); Daily limits (answers,
  tokens, money; plain text on what happens when reached); Question time-out (Tier B); Backup (Tier B: generated
  passphrase, own passphrase rules, FLAG_SECURE, "keep the app open", restore summary with setting changes); Usage
  history (keep for / clear, keeps today); Appearance: Calm.
- Recovery screen (FLAG_SECURE): plain words per `DatabaseState`; Start over (typed word in a secure field; what is
  set aside and what is reset, in words); Close and try later; never reads `graph`.
- Dashboard: Today tile with cost when every used model has a price; Getting started (Tier B: what model, provider and
  agent mean, add your xAI key, set a daily limit, try the simulator); phone attention items; safe-start banner.
Tests: view-model tests; journeys `BackgroundCheckJourneyTest`, `RecoveryJourneyTest` (each `DatabaseState`),
`BackupJourneyTest`, `UsageJourneyTest` (simulator objective → non-zero numbers; price → cost), `DailyLimitJourneyTest`
(limit → objective paused → raise → resume), `GettingStartedJourneyTest`; paused-clock typing tests for its fields;
Roborazzi in dark, light, wide and 360 dp.

#### WP11a. Tasks, approvals and questions (3b). Size L.

Owns ui/tasks/* (incl. markdown/MarkdownView.kt), ui/approvals/*, ui/questions/* (new). P1, P2, P7: one
`observeObjectiveRows(50)` plus "Show older" (ObjectivesViewModel.kt:76-77 [V]); summaries for the plan; the task text
only for the Result tab; the ticker in its own `StateFlow`; `flowOn(Dispatchers.Default)` and `conflate()`; MarkdownView
parses off the main thread, one lazy item per block. Objective header: cost line, "Team view". Conversation tab
composer (Tier B) "Write to the team" with "Notes reach the team from their next step". Reviewer line "(same model as
the author)". Approvals list renamed "Waiting for you"; **D9** "What happened" on the Approval screen. Question screen
of rule 13 (Tier B) with rule 32's checks. New objective form: Director reasoning warning (D27), background warning
(A3), storage refusal (R8), daily-limit line ("Today: 37 of 200 answers"). Tests: ticker builds count; rows query;
Show older; question screen vs approval screen screenshot; key and code answers; `QuestionJourneyTest`,
`NotesJourneyTest`; Roborazzi.

#### WP11b. Chat, models, agents, memory graph, tools (3b). Size M-L.

Owns ui/chat/*, ui/models/*, ui/agents/*,
ui/memory/graph/MemoryGraphViewModel.kt, ui/tools/*. Chat: cut-off notice with "Send again" (R3), newest 50 entries
with "Show earlier" (P5), cost line per chat, `ModelNames.sameModel` (D29), limit sentence. Model editor: price fields
("you enter these from your provider's price page; the app never looks them up"), `testLabel` removed from Reasoning
and Coding (ModelEditorScreen.kt:299, :300 [V]) with one line each saying what they change, Vision keeps it (:293);
"Speed and reliability" card; cheaper-helper suggestion (Tier B: among enabled models with Tools and a price, the
cheapest, offered through the existing "Use for agents…"). Agent editor model picker shows the tiers. D37, D38, D40,
the D7 notice in Tools > Screen. Tests: view-model tests, `ChatCutOffJourneyTest`, price → cost, Roborazzi,
`PlaceholderLabelsTest` (Vision only).

#### WP16. Receiving shares from another app (3b, Tier B). Size S-M.

Owns app/share/* (new: ShareReceiverActivity.kt, ShareViewModel.kt, ShareScreen.kt) and its tests. The activity passes
`AppGate`; reads `EXTRA_TEXT` and, for `EXTRA_STREAM`, up to 10 `text/*` files of at most 1 MB through the one-time read
grant; shows the size, a space picker (spaces the owner can write; "Workspace" as a file) and the line "Storing is free.
Only what agents recall is sent to a model. A cloud model would receive this text; local-only mode and space access keep
it on the phone."; Save stores through the owner's memory path (`MemoryService` with provenance "Shared from another
app", split into parts within the memory size bound) or the workspace; nothing before Save (rule 34). Tests: Robolectric
SEND and SEND_MULTIPLE, caps, nothing stored without Save, local-only allowed, recalled as data.

### WP12-WP14 (waves 4-6)

- **WP12. Adversarial review rounds (wave 4):** section 9. Size L.
- **WP13. Emulator end-to-end of the signed 0.6.0 (wave 5):** section 10. Size M.
- **WP14. Docs and release 0.6.0 (wave 6).** STATUS.md "Phase 6 (complete)" with What works / How it was verified /
  Not verified (including Android 9-13 and 15-17 behaviour: the emulator is 14 and Robolectric 15, F2) / Known rough
  edges; every earlier rough edge says "fixed in 0.6.0" or keeps its reason; "Next: Phase 6" becomes "After Phase 6".
  "Installing 0.6.0": installs over 0.5.2 and keeps the data; "Removing the old app" (section 0); there may be two
  entries in Android's accessibility list and which one belongs to which app. LIMITATIONS.md: background work, exit
  reasons absent on Android 9-10, costs are estimates, provider cost notes unverifiable here, the backup (in and out,
  passphrase), questions and notes leave the phone with the model call, the script process (or its fallback), D8's
  resolving names, C4 wording replacing LIMITATIONS.md:325, "planned" → "not built" at :50-51 and :63, no
  `<cross-platform-transfer>`, the battery-exemption Play policy line. ARCHITECTURE.md sections 3.C, 3.D, 5, 6, 8.
  README: backup, recovery, cost and limits for the owner. Version **0.6.0 / 9**; signed APK copied to the scratchpad;
  commit and push only as the session's user asks. Size M.

## 6. The "(test)" controls

Decision: **Reasoning and Coding become real; Vision keeps "(test)".**
- **Reasoning and Coding:** raise an agent's chance to be picked for tasks that call for them (WP7 item 5), the New
  objective form warns when the Director's model is rated Basic at reasoning (WP11a), and the agent editor's model
  picker shows the tiers (WP11b). Labels removed in the same change (ModelEditorScreen.kt:299-300 [V]).
- **Vision:** showing photos to a model is Tier C (F16, S14, S25); the field keeps " (test)" (ModelEditorScreen.kt:293
  [V]) and `PlaceholderLabelsTest` lists exactly it.

## 7. Deferred items and rough edges: fix now or document

STATUS line numbers are as of 9c739d5 (Phase 5 rough edges at :488-519).

| ID | Item (STATUS line) | Verdict |
|---|---|---|
| D1 | JS loop of built-ins blocks scripts until restart (:488-491) | Fix, WP6 |
| D2 | JS limit counts engine start (:503-505) | Fix, WP6 |
| D3 | JS memory limit measured on the whole app | Fix with D1, WP6 |
| D4 | Banner ends while an action waits (:506-508) | Fix, WP15 (caller) + WP5 (device) |
| D5 | Screen call right after opening an app refused (:511-512) | Fix, WP5 |
| D6 | Permission screens called "no app window" (:513-514) | Fix, WP5 |
| D7 | Force stop switches accessibility off (:509-510) | Explain and link, WP5 + WP11b; the switch-off is Android's rule |
| D8 | Private address asks permission first (:515-516) | Fix, WP1a (policy) + WP5; resolving names still refused at read time |
| D9 | Approval screen does not show the outcome | Fix, WP11a |
| D10 | No team-wide cap on pending requests | Fix, WP5 |
| D11 | Two shell commands can be asked; busy refusal leaks | Fix, WP5 |
| D12 | Bulk window not paused for owner waits (:501-502) | Fix, WP5; counts still reset at restart (documented) |
| D13 | Unanswered request refused after 30 min (:499-500) | Keep: a setting (5-240 min); final |
| D14 | During Android's install confirmation the old time-out moment shows | Document: cosmetic |
| D15 | Non-plain shell commands: best reading (:492-493) | Keep; final |
| D16 | A workspace change stops a waiting command (:494-496) | Keep: it is the guarantee; final |
| D17 | Undeletable home-folder leftovers stay in cache (:497-498) | Document |
| D18 | Termux keeps running after the limit | Keep; final |
| D19 | Objective screens reload whole tasks (:326-328) | Fix, WP1b + WP11a (+ WP2, WP9) |
| D20-D23 | Orchestration and knowledge rough edges (:333-340) | Fix, WP7 |
| D24 | 2 tasks at once; reviews share the limit (:329-330) | Keep: a setting; final |
| D25 | Notification starts only with the app on screen (:331-332) | **Improved**: also starts from the background when battery optimisation is off (WP2, F29) |
| D26 | Vision (test) | Keeps "(test)": photos are Tier C |
| D27 | Reasoning, Coding (test) | Make real, WP7 + WP11a/b |
| D28 | One failed check blocks agents (:158-159) | Fix, WP4 + WP7 |
| D29 | Aliases flagged "answered by another model" (:160-161) | Fix, WP4 + WP11b |
| D30 | "Find servers" only on this phone (:157) | Keep; final |
| D31 | Conversations not paged (:162) | Fix, WP1b + WP11b |
| D32 | Code shrinking off, APK about 80 MB | Keep: reflection in the Anthropic SDK; final |
| D33, D34 | On-device runtime, Gemini Nano, web search | Document as not built |
| D35 | Memory counts load the full list (:229) | Fix, WP1b + WP9 + WP11b |
| D36 | Memory upkeep only while open (:230-231) | Fix, WP2 (job) |
| D37 | Graph export refused in local-only mode (:232-233) | Fix, WP11b |
| D38 | Space deleted while editing loses a change (:234-235) | Fix, WP11b |
| D39 | No "keep on this phone" mark | Document as not built |
| D40 | New agent button covers the last card (:75) | Fix, WP11b |
| D41 | Activity: newest 300, no paging or search (:76) | Fix, WP1b + WP10 (search Tier B) |
| D42 | English only (:77) | Keep; final |
| D43 | Public development key used by CI without secrets (:78) | Keep; README says how |
| D44 | Lost database key: no in-app reset (:79-80) | Fix, WP2 + WP10 (Recovery screen) |
| D45 | ANR once at a cold start on the emulator (:517-518) | StrictMode in WP8; ANR exits now visible (WP2) |
| D46 | Launcher icon | Done in 0.5.1, carried by 0.5.2 (section 0) |
| D47 | `onboardingCompleted` never read | Fix, WP10 (Getting started, Tier B) |
| D48 | Web reader always connects directly | Keep; final |

Reader and owner items not covered by a D-id:

| Item | Verdict |
|---|---|
| A1 wake lock, A2 chats in the service, A3 background check | WP2 |
| G6 clock | Cut (F9) |
| A4, A5 routines and restart resume; folder pick-up import | Not built unless Q1 = yes (Appendix A) |
| A6 result notifications / away card | Notifications WP2; card cut |
| A7 daily limits, owner cost request (#43) | WP4 + WP7 + WP10 + WP15 (Q2) |
| R1 exit reasons, R2 safe start (Tier B) and crash notes, G7 scope handler | WP2 (+ WP7 text, WP10 banner) |
| R3 cut-off chat | WP7 + WP11b |
| R4 provider trouble, 429 | WP4 + WP7; mid-run fallback cut |
| R5 Recovery screen, R6 extraction rules | WP2 + WP10; R6 in WP1d; safety copy cut |
| R7 backup file | WP3 + WP10 (Tier B) |
| R8 storage watch | WP2 |
| R9 upkeep job | WP2 (+ WP1d hooks) |
| R10 continue from transcript | Cut |
| Typing bug (#42) | WP0 |
| Receiving shares (#43) | WP16 (Tier B) |
| P1-P5, P7, P8 | WP1b, WP2, WP4, WP9, WP10, WP11 |
| P6, P9, P10 | Cut / nothing to do |
| N1-N11 | WP9 |
| C1, C3 | WP5 + WP11a (Q3; C1 Tier B) |
| C2 | Stage 1 WP7 + WP11a (Tier B); stage 2 cut |
| C4, C7 | WP7 |
| C5, C6 | Cut |

## 8. Build order in waves

Machine: 4 cores, 21 GB free disk [V: df]; every Gradle run goes through the worktree's own `tools/gw --offline …`,
with **`TMPDIR=/tmp` for every agent** so all share `/tmp/aicc-gradle.lock` (tools/gw:6 locks
`${TMPDIR:-/tmp}/aicc-gradle.lock` [V]); never kill it. Worktrees: `git worktree add ../p6-<wp> -b p6/<wp> <wave base>`,
then copy `local.properties` into it (gitignored; `ANDROID_HOME` is unset, F24). Each worktree builds about 1.2 GB;
keep at most 7 at once and remove merged ones. Packages commit only to their own branch; nothing is pushed until wave 6.
The TCG emulator takes about 2 cores and 7 minutes to boot.

| Wave | Packages | Agents at once | Merge point |
|---|---|---|---|
| 0 | Owner answers Q1-Q3 (defaults apply if no answer) | – | Decides whether Appendix A tables enter schema 6 |
| 1 | WP0, WP1a, WP6 spike at once; WP1b and WP1c from WP1a's first commit; WP1d last | 4 (the spike's emulator runs while builders write code) | Merge WP1a, WP0, WP1c, WP1b, WP1d; full suite; EncryptedMigrationTest on the emulator; contracts guide; WP3 format review; spike verdict |
| 2 | WP4, WP15, WP5, WP7 first; WP2, WP6, WP3 as agents free up | 4 (queue of 7) | Merge WP4, WP15, WP7, WP5, WP2, WP3, WP6; full suite after each |
| 3 | 3a: WP8 alone. 3b: WP9, WP10, WP11a, WP11b, then WP16; WP8 integrates | 1, then 4 + integrator | Stub gate passes; full suite; every screenshot reviewed by eye |
| 4 | WP12 review rounds (section 9) | 4 reviewers → up to 3 fixers → up to 4 verifiers | Convergence rule met; Tier B decisions taken after round 2 |
| 5 | WP13 emulator end-to-end (section 10); the release **and** debug APKs are built before it starts (F27) | 1 (no Gradle runs meanwhile) | Results JSON and screenshots in scratchpad/e2e-0.6.0 |
| 6 | WP14 docs, version 0.6.0 / 9, signed APK | 1 | Release commit |

Dependencies in short: WP1a → WP1b, WP1c → WP1d → every wave-2 package; WP0 independent; WP4's limiter, rechecks and
`matchesStoredKey` reach WP7 and WP5 only through K11/K15; WP2's wake lock reads WP4's and WP7's `TimedWaits` only
through K12/K14; WP15's screen hold reaches WP5's device side only through K7; WP8 3a → WP9, WP10, WP11a, WP11b, WP16.

## 9. Adversarial review plan (wave 4)

Four lenses, each by an agent that did not build the area, with a reproduction (test or script) for every finding,
graded **critical** (an owner rule broken: something consequential without approval, data off the phone in local-only
mode, agent-facing code reaching an owner-only port or the settings writer, data loss, a secret or key alias in the
backup, the app unable to start), **major** (wrong behaviour in daily use, a guarantee broken by crafted input,
corruption on some path, a cost or performance regression over 2×), **minor** (wording, cosmetic, a rare edge that
fails safe).

- **Security:** every rule of section 3 with its test; questions as a prompt-injection channel (a page making an agent
  ask for an SMS code then read a page with it in the URL; an answer forged by agent text; a QUESTION that creates a
  grant or settles an approval; approval-word options); forged notes; agent text in notifications; the settings writer
  from agent-facing code; backup (keys and aliases, a tampered file that injects grants, requests, operator switches,
  relaxed settings, private provider addresses or re-scoped memories; tool results in the body; huge counts;
  iteration-count stall; restore into a used app; restore during a chat turn); the isolated process (hostile engine,
  host calls after the run, a write during a shell command, pipe and parcel sizes, another run's host binder, the
  service exported); `tools_open` (a family the agent does not have; authorisation unchanged); daily-limit bypass
  through retries, streaming, concurrency or the simulator path; the share receiver (nothing without Save, sizes,
  screen control cannot press Save); concurrent races past every cap; a shell reservation never released; D8 literal
  bypasses; screen actions with the screen off; system pickers under screen control; the upkeep job contacting a
  provider. Re-run every Phase 5 probe.
- **Android:** wake lock leaks, holds while waiting, renewal, the 2-minute rule and the alarm; foreground-service starts
  from the background (chat leaving the app, battery exemption); the job with the app closed, `onStopJob`,
  `jobFinished` and the version gate; exit info filtering by process name with forced `sdkInt`; extraction-rules XML
  validity; channels with notifications denied; the battery dialog intent missing; the isolated service under the
  freezer, binder death and restart back-off; `areAnimatorsEnabled` and TalkBack semantics; process death and rotation
  on every new screen; Start over with a damaged alias and the process restart; FLAG_SECURE on the right screens.
- **Integration:** migration 5→6 with real data on Robolectric and SQLCipher, indexes and query plans; kill points
  during migration, restore and Start over; `retryAt` against pause, resume, run again and crash recovery; OFFLINE
  marking against a running objective (waits, never fails for a transient state; never moves farther, rule 25); the
  limit pausing objectives and resuming; questions against pause, cancel and kill; caps against settle-covered;
  assemblies vs TestApp parity; the full suite three times in a row for flakiness.
- **Performance and cost:** token budgets per starter agent (rule 28) and prefix stability; no summary path reads the
  big columns (EXPLAIN and rows-read counts); no N+1 flows left (grep `observeTree(` in view models); every builder off
  the main thread; the frame loop stops; the Team view with 30 agents and 1,000 messages (counts); Activity and chat
  paging; the isolated process's memory (`dumpsys meminfo com.aicontrolcenter.hq:scripts` on the emulator); one usage
  write transaction per model call.

Round: 4 reviewers in parallel → fixes by up to 3 agents, each in the owning package's files → verifiers (one per lens
that had a critical or major) re-run every reproduction of every earlier round and attack the fixes. **Convergence:**
only critical and major findings open another round; minors are fixed in the same round when size S and covered by a
test, otherwise written into STATUS "Known rough edges". At most 4 rounds. After round 2, every Tier B item with an
open critical or major is switched off or removed (section 2). If a critical finding in a Tier A feature survives
round 3, that feature is switched off or removed for 0.6.0 and documented; a security-relevant control is removed,
never shipped with "(test)".

## 10. Emulator end-to-end script for 0.6.0 (wave 5)

Setup reused from Phase 5: scratchpad/emulator/run-emulator.sh (AVD tcg34, Android 14 aosp_atd), the scripted model
server scratchpad/mockmodel and scratchpad/e2e-tools/mock2 (extended to log request sizes and tool counts), helpers
s.sh, shot.sh, tapxy.sh, typeslow.sh, scrollto.sh; results in scratchpad/e2e-0.6.0/ plus e2e-0.6.0-result.json in the
format of e2e-result.json. Package `com.aicontrolcenter.hq` everywhere. The provider is registered at
**`http://10.0.2.2:8765`** ("local network"), so limits apply and airplane mode cuts it (F13); `127.0.0.1` via
`adb reverse` is "on this device". To put the app in the background use `am start -n com.android.stk/.StkMenu` or
`input keyevent KEYCODE_SLEEP` and confirm with `dumpsys activity activities` (the image has no launcher, F14).

1. **Check both APKs (built before the wave).** Signed release 0.6.0 / 9: `apksigner verify --print-certs` shows the
   0.5.0/0.5.2 signer (20:79:F0:E1:…:25:C5); `aapt2 dump badging`: `com.aicontrolcenter.hq`, versionCode 9, minSdk 28,
   targetSdk 36, application icon present; `aapt2 dump permissions`: exactly 16; exported components as rule 22.
2. **Upgrade with data.** Install scratchpad/AIControlCenter-0.5.2.apk, give it data (provider at 10.0.2.2, a chat, a
   memory, a finished objective, an "always" grant, an authorised app, screen control on via `settings put secure
   enabled_accessibility_services com.aicontrolcenter.hq/com.aicontrolcenter.device.screen.OperatorAccessibilityService`),
   install 0.6.0 over it: "Success"; first start migrates to schema 6; everything still there; no ERROR in the log.
3. **Typing.** `adb shell input text` a 200-character string into "Your objective"; `uiautomator dump` shows it exactly.
4. **Background check.** Settings > Working in the background shows battery optimisation on; `cmd deviceidle whitelist
   +com.aicontrolcenter.hq`, return: off. The Allow button shows the plain fallback (no Settings app on the image).
5. **Screen off.** Start a 3-task objective; background the app; KEYCODE_SLEEP; `dumpsys power | grep aicc` shows the
   wake lock while tasks run, released while an approval waits; a scripted `screen_read` with the screen off is refused
   with rule 12's text; approve; at the end `dumpsys notification --noredact` shows "Finished: …" with `ic_stat_done`,
   no actions; service and wake lock gone. `dumpsys deviceidle force-idle` during a run: work goes on (TCG does not
   really suspend; not proof for a phone).
6. **Chat in the background.** Slow streamed reply; background the app; "<agent> is answering · Stop"; the reply is
   complete when the chat is opened.
7. **Cut-off chat and force stop.** Slow reply, `am force-stop com.aicontrolcenter.hq`; reopen: the chat says the reply
   was cut off; "Send again" sends once (mock log); Tools > Screen and Needs attention say Android switched screen
   control off (Android 14: the generic sentence); turn it back on with the `.hq` component.
8. **Lean requests and cost.** The mock log shows the Operator agent's first request with the tool menu (definitions
   under the rule-28 budget) and `tools_open` working; enter a price for the mock model: chat, task and objective show
   tokens and estimated cost; the Dashboard shows today's and this month's total.
9. **Provider trouble.** Stop the mock server during a task: "Waiting 30 seconds: … did not answer", then "Not answering
   since …"; start it again: within about 2 minutes online by itself and the objective finishes. Airplane mode on then
   off: an OFFLINE provider is re-checked at once. A scripted 429 with Retry-After 5: the task waits and goes on.
10. **Daily limit.** Set 5 answers a day; the 6th chat message gets the limit sentence; a running objective shows
    "Paused: today's limit is reached"; raise the limit, Resume: it finishes.
11. **Exit reasons.** Start an objective, `am crash com.aicontrolcenter.hq`, dismiss the dialog with KEYCODE_BACK,
    reopen: "Interrupted: the app stopped because of an error in the app". (Tier B) two more crashes within 15 minutes:
    safe-start banner; "Keep everything paused" keeps the objective paused.
12. **Questions and notes (Tier B).** `team.ask_owner`: `dumpsys notification` shows "A question from Researcher. Open
    the app to read it." without the question text; the question screen differs from an approval; answer by typing; the
    mock log shows the framed answer; an answer like "xai-…" is refused; a note reaches the next brief in an
    `owner_note` frame.
13. **Team view.** From the dashboard and the objective header while it runs: You, Director, agents, "Waiting for you"
    during an approval; tap an agent and a link icon; List; `settings put global animator_duration_scale 0` and Calm: no
    dots.
14. **Scripts.** `1+1` on a warm second run within the default 5 s (D2; cold bind and engine-start times recorded); the
    endless iterator loop three times, each Stopped within its limit plus a few seconds, then `1+1` works (D1);
    `ps -A -o USER,PID,NAME | grep aicontrolcenter` shows `:scripts` under an isolated user and the app's pid unchanged;
    `dumpsys activity exit-info com.aicontrolcenter.hq` (or the app's log) shows the script exits did not count.
15. **Screen control (SIM Toolkit).** `apps_open` then at once `screen_read`: succeeds (D5); a tap answered after 45 s:
    `screencap` during the wait shows "… waits for your answer" (D4); with the app's own notification-permission
    dialog in front, `screen_read` says the window cannot be read (D6).
16. **Broker.** Two shell commands in a row: the second refused without asking (D11); `web_read_page
    http://192.168.1.10/` refused with no permission request (D8); the Approval screen of an approved
    `workspace.empty_trash` says "Done." (D9).
17. **Share (Tier B).** `am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "…" -n
    com.aicontrolcenter.hq/com.aicontrolcenter.app.share.ShareReceiverActivity`: nothing stored until Save; after Save
    the memory is in the chosen space.
18. **Upkeep job.** `cmd jobscheduler run -f com.aicontrolcenter.hq <job id>`: the activity log shows the upkeep
    entry and no model or tool call; the mock log shows no request.
19. **Cold start.** `am start -W` three times after `am force-stop`; `dumpsys activity anr` empty (emulator speed noted).
20. **Backup and recovery, last (F27).** If `cmd package resolve-activity -a android.intent.action.CREATE_DOCUMENT`
    finds a document picker: save a backup file (Tier B). Then install the **debug** build of 0.6.0 over it (same key),
    `run-as com.aicontrolcenter.hq` move the sealed database key away, open: Recovery screen; Start over; the app
    restarts empty with the starter team; (Tier B) restore the file: agents, memories and chats are back, providers ask
    for the key, both operator switches off, local-only as before. Without a picker the instrumented tests stand in.
21. **End.** Stop the mock server, reinstall the release 0.6.0 if the debug build was installed, stop the emulator,
    write the results file.

Cannot be done on the aosp_atd image, and said so in STATUS: the launcher icon (no launcher); Android's battery and
accessibility settings pages (no Settings app; `adb` stands in); the notification shade (`dumpsys notification` stands
in); real CPU suspend and Doze over an hour (TCG); a real provider and real prices (no internet); TalkBack; a
low-memory kill as Android reports it; Android 9-13 and 15-17 behaviour, including `wasForceStopped` (API 35);
makers' battery managers; possibly the document picker. Only the owner's phone can show: the icon, an hour-long
objective with the screen off, xAI's real answers, cached-token reporting and rate limits, and the real cost.

## 11. Risks and open questions (recommended answers; the owner decides only Q1-Q3)

| Risk / question | Recommended answer |
|---|---|
| Scope (the largest phase; Phase 5 took about 33 hours, 70% in fix rounds, F12) | Tiers of section 2; Tier B decisions after review round 2; dependability and cost items are Tier A |
| The isolated process cannot be ended the way the design needs | Wave-1 spike decides; fallback `:scripts` with the app's UID |
| The tool menu confuses models (they forget to open a family) | Threshold keeps small agents unchanged; a call to an unopened enabled tool still runs; the budget test and the mock log in §10 step 8; real Grok behaviour only on the owner's phone |
| Wake lock drains the battery | Held only while working or a retry is due within 2 minutes; 10-minute timeout per acquire; owner switch; documented |
| A provider marked OFFLINE fails objectives faster or moves data farther | Transient reasons make tasks wait (WP7); rule 25 |
| Costs shown are wrong | Estimates from the owner's prices, labelled so; cached tokens only when reported; the provider's bill counts |
| xAI may not report usage or cached tokens in streams | `no_usage` count; "the provider did not report tokens for N answers"; the token limit counts estimates then [A] |
| PBKDF2 at 600,000 iterations is slow on the emulator | Iterations in the header; tests use a low count plus the RFC 7914 vectors; progress text |
| Real models overuse or ignore `team.ask_owner` | Caps; Q3 can switch it off; Tier B |
| Merge conflicts in shared files | Wave-1 assemblies and hooks; one owner per shared file per wave |
| Developer verification on certified phones (LIMITATIONS.md:77-82) | Unchanged; the owner installs 0.6.0 the way 0.5.2 was installed |

**Questions for the owner (plain language; the default applies if there is no answer before wave 1):**

- **Q1. Should the app do things by itself while you are not using it?** For example start agents at set times
  ("every morning at 7, summarise the news"), or pick up new files from a shared folder into memory. Agents would use
  your xAI account while you are not looking, and Android may delay or skip such starts. **Recommended: not in this
  version.** Sharing from your other app into AI Control Center works without it.
- **Q2. Should the app stop agents for the rest of the day once they have used a set amount?** **Recommended: yes, at
  200 model answers or 500,000 tokens a day (tokens are the pieces of text models count, about three quarters of a word
  each), and at one dollar a day once you type in your model's price, whichever comes first.** A running objective
  pauses and you can raise the numbers in Settings.
- **Q3. Should agents be allowed to ask you a question while they work?** You would get a notification and type an
  answer; if you do not answer within 20 minutes they decide themselves and say what they chose. **Recommended: yes**
  (if it is not fully safe by the review, it ships switched off).

## Appendix A. Routines and folder pick-up (only if Q1 = yes)

Routines as in revision 1: core `Routine`, `RoutineSchedule`, `RoutineRepository`, owner-only `OwnerRoutines`;
schema-6 tables `routines` and `routine_runs` (outcome STARTED, SKIPPED_STILL_RUNNING, SKIPPED_DAILY_LIMIT,
SKIPPED_LOCAL_ONLY, SKIPPED_DIRECTOR_UNAVAILABLE, WAITING_FOR_TAP, MISSED) in WP1b; framework `RoutineClock` and
`RoutineRunner`; app `RoutineAlarms` with `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()` (owner-granted
`SCHEDULE_EXACT_ALARM`), `RoutineAlarmReceiver` → the existing foreground service (exact alarms may start one from the
background [D] https://developer.android.com/develop/background-work/services/alarms, Android 12+), "Tap to start" when
Android refuses, a `BootReceiver` disabled until a routine exists; limits: master switch off after installing, at most
10 routines, at least 1 h apart, no overlapping runs, pauses itself after 3 failures, daily limits and local-only checked
at run time, screen actions refused unless ticked per routine, the broker unchanged. Folder pick-up: an owner-made rule
"import new `text/*` files from shared folder F into space S", run by the upkeep job's scheduler (JobScheduler, not
WorkManager, which is not in the offline cache), deduplicated by content hash, at most 5 MB a day, nothing sent to a
model (the hashing embedder indexes it). Both become package WP17 in wave 2 (size L) and add §10 steps.

## Critique log

Verdicts: **A** accepted and changed; **P** partly accepted (what was not taken and why); **R** rejected (reason).

### Security critique (S1-S29)

| ID | Verdict | What changed |
|---|---|---|
| S1 stale HEAD | A | Header, §0, §5 WP14, §10 rebased on 9c739d5: versionCode 9, upgrade 0.5.2 → 0.6.0, `.hq` in every adb step, "Removing the old app" text, two accessibility entries noted |
| S2 settings writer reachable | P | `SettingsView` + `ReadOnlySettings` for every agent-facing holder (K16, WP1c item 5); writer owner-only in both tests with `update` as an owner action (rule 3). Not taken: a separate `SeedBookkeeping` port: the only writers, DefaultAgentManager and MemorySeeder, are owner/start-up code that no agent-facing root reaches [V grep], and the walks will fail if that ever changes |
| S3 restore reattaches keys | A | Rule 8: no aliases in the file, `apiKeyAlias = null`, status reset, locality re-inferred, headers re-validated; Start over moves the vault aside and starts a new vault generation (rule 9); the named JVM test |
| S4 restore relaxes settings | A | Rule 8 merge rules and the "what would change" list |
| S5 tool results in backup | A | Rule 8: owner and agent reply text only, tool uses as one placeholder line; decrypted-body test |
| S6 weak passphrase | P | Generated passphrase by default, own one ≥16 characters or ≥4 words. Changed: 20 Crockford base32 characters (100 bits) instead of 6 words, because no word list exists offline [V: none on the machine] and a list would bring a licence to carry |
| S7 typing bug in new fields, IME learning, screenshots | A | WP0 (all fields to `TextFieldState`), rule 27, `OutlinedSecureTextField`, FLAG_SECURE on Recovery/Backup/passphrase screens; "CharArray" replaced by "never stored, never logged, cleared" |
| S8 memory scopes on restore | A | Rule 8: ids and scopes kept, orphaned memories dropped and counted, space lists cut; isolation test |
| S9 limits inside the untrusted process | A | Rule 11; WP6 per-run host binder enforcing every limit app-side; hostile-engine contract |
| S10 questions look like approvals | A | Rule 13: `QuestionRoute`, own chrome and line, options as quoted list, approval words refused, no question beside an open request |
| S11 agent text in notifications | A | Rule 4 and WP2 item 5: app-written texts only; marker test |
| S12 secrets as answers only warned | A | Rule 32: key shapes and stored keys blocked, codes need a second tap, after-answer approval for the first off-phone call |
| S13 forged owner notes | A | Rule 24, WP7 item 3: `owner_note` frame with ids, escaping, source scan, forged-note test |
| S14 silent move farther from the phone | A (OFFLINE part); vision part moot | Rule 25 in `resolveAvailability` (WP1a); photos are Tier C, so no vision routing |
| S15 screen actions with the screen off | A | Rule 12 and WP5 item 10 (`isInteractive`), §10 step 5 |
| S16 fail-silent collectors | A | Rule 26, `SafetyCollectors` (WP2 item 8) |
| S17 background database opens, job reach | P | Job: `UpkeepGraph` on the shared `dataLayer` and memory service, version gate, zero-HTTP test (rule 1). Not taken: the accessibility-rebind part: the service touches neither database nor graph (OperatorAccessibilityService.kt:27-34 only hands itself to `ServiceLink`) [V]; the "before the safety copy" part is moot because the safety copy is cut (F10) |
| S18 script exits as app crashes | A | Rule 31, WP2 item 6 (filter by process name) |
| S19 AIDL hardening | P | No `Bundle`/`Parcelable`/`Serializable`, capped pipes, host calls off binder threads, one outstanding call per run (WP6). `BIND_WAIVE_PRIORITY` not adopted as a rule: its effect on an isolated service is [A]; the spike may try it |
| S20 "numbers only" vs text columns | A by removal | `provider_checks` and `app_exits` are Tier C; exit descriptions never stored (K12) |
| S21 problem report contents | moot | Problem report is Tier C |
| S22 holds keyed by agent name | A | K7 `holdId`; rule 12 per-request holds; WP5 item 5 test |
| S23 Start over leaves things behind | A | Rule 9: vault moved aside, folder grants released, seeds and operator switches reset; the screen says what remains |
| S24 restore races | A | `MaintenanceGate` (K13), refused during a run or live turn, "empty" checked inside the transaction (WP3, WP7 item 11) |
| S25 photo re-encoding | moot | Photos are Tier C |
| S26 system pickers unprotected | A | K17 in WP1a with `QueriedIntent` and `<queries>` in step |
| S27 MainActivity entry points | A | Rule 23; the share receiver is a separate activity (rule 34) |
| S28 owner text in the activity log | A | WP7 item 3: ANSWER and GUIDANCE logged without the body |
| S29 daily-limit races, clearing resets today | A | Rule 6 reserve-then-commit; `OwnerUsage.clearHistory()` keeps today |

### Missing rules (N1-N13)

| Rule | Verdict | Where now |
|---|---|---|
| N1 settings are the owner's | A | Rule 3 |
| N2 restore never relaxes, never reattaches | A | Rule 8 |
| N3 backup holds configuration and conversation text only | A | Rule 8 |
| N4 no agent text in notifications | A | Rule 4 |
| N5 questions never look like or sit beside approvals | A | Rule 13 |
| N6 secrets are blocked as answers | A | Rule 32 |
| N7 owner notes cannot be forged | A | Rule 24 |
| N8 no silent move farther from the phone | A | Rule 25 |
| N9 screen actions need a visible screen | A | Rule 12 |
| N10 safety collectors fail stop | A | Rule 26 |
| N11 background entry points are inert | A | Rule 1 (the "safety copy in any process's first open" part is moot: no safety copy) |
| N12 the script process is untrusted | A | Rule 11 |
| N13 owner input is exact | A | Rule 27 (generated passphrase in rule 8 / WP3) |

### Feasibility critique (F1-F30)

| ID | Verdict | What changed |
|---|---|---|
| F1 baseline 0.5.2 | A | As S1; STATUS citations moved (+10 lines after :420); "Next: Phase 6" :521-527, tests :441, permissions :445 |
| F2 Robolectric only SDK 35 | A | `sdkInt` injected in WP2, WP5 (D7) and WP6; tests at SDK 35; other API levels "not verified" (WP14) |
| F3 upkeep job composition | A | Shared lazy `dataLayer`, `sharedMemory` and `UpkeepGraph` (WP1d item 1, WP2 item 11); `onStopJob`/`jobFinished`; owner named (WP2, hooks by WP1d) |
| F4 binder sizes | A | Pipes for request and outcome, two-way host calls ≤ 64 KB; spike Q7 |
| F5 restart after death | A | Unbind at once, rebind with its own bound; spike Q6 |
| F6 script exits in exit history | A | Rule 31; quiet self-kill in the script process; spike Q8 |
| F7 force-stop reason | A | `wasForceStopped()` on API 35+, generic text otherwise; `ExitKind.USER_REQUESTED` |
| F8 waits stall while asleep | A | Rule 5: wake lock for waits due within 2 minutes, allow-while-idle alarm to `WakeReceiver` for longer ones, `TimedWaits` (K12) |
| F9 G6 does nothing | A | G6 cut (Tier C) |
| F10 Start over, keystore, safety copy | A | Process restart (rule 30); key generations (WP2 item 9); "Go back to the copy" and the safety copy cut; no `user_version` read |
| F11 ownership gaps, typing bug, big-bang 3a | A | Shared-files table; WP1d pre-inserts manifest entries, hooks and per-package assemblies; `resolveAvailability` in WP1a; agents/* to WP15; Attention/DashboardSections to WP10; shell files to WP8; WP0 for #42; contract suites in WP1a's first commit. ProviderDetailScreen needs no owner (connection history is Tier C) |
| F12 scope | P | Three tiers adopted with the round-2 rule. Not taken: cutting model prices and cost (Tier C in the critique), because the owner asked for cost control after the critique (owner-cost-and-import.md, task #43); they are Tier A. Lean requests are added for the same reason |
| F13 mock is "on this device" | A | §10 uses `http://10.0.2.2:8765` |
| F14 no launcher on aosp_atd | A | §10 uses StkMenu or KEYCODE_SLEEP and checks `dumpsys activity`; crash dialog dismissed |
| F15 debug manifest has an exported activity | A | Rule 22 tested in `testReleaseUnitTest` and with aapt2 |
| F16 Base64 pictures in rows | moot | Photos are Tier C |
| F17 `setProcessStateSummary` | A | Cut |
| F18 memory limiter on 17.0 | A | WP2 item 6 maps `REASON_OTHER` + "MemoryLimiter", 17 and 18 |
| F19 wall-clock tripwires | A | Rule 16 counts only; the rebuild gate is gone |
| F20 schema details | A | No rebuild; ALTER fields last; EXPLAIN citation corrected; room-testing and schema assets in data androidTest |
| F21 engine start on TCG | A | Separate `bindTimeoutMs` and `engineStartMs`; 5 s asserted on a warm run; spike Q9 |
| F22 shared host binder | A | Per-run host binder |
| F23 watchdog ends both runs | A | `bindIsolatedService` instance per slot on API 29+; API 28 says so |
| F24 worktree mechanics | A | §8: copy `local.properties`, `TMPDIR=/tmp`, disk budget |
| F25 stub test keeps suite red | A | Rule 18: gate task at the end of wave 3 |
| F26 instrumented names, debug-only hooks | A | camelCase; `ScriptProbeService` in app/src/debug |
| F27 debug install mid-script | A | Backup and recovery moved to step 20; both APKs built before wave 5 |
| F28 release checklist | A | R8 off stated (WP1d item 8); no `<cross-platform-transfer>`; versionCode 9 |
| F29 battery-exempt start | A | WP2 item 2; D25 "improved" |
| F30 backup size and interruption | A | Free-space check, progress, "keep the app open", RFC 7914 vectors (WP3) |

### Owner items that arrived during the review

| Item | Verdict | What changed |
|---|---|---|
| #42 typing drops and reorders characters (owner-typing-bug.md) | A | WP0 in wave 1, rule 27, §10 step 3 |
| #43 cost: lean requests, cache-friendly order, cost with owner prices, daily spending cap, cheaper routing, free/low-cost providers, 429 (owner-cost-and-import.md §1) | A | Goals 1-2; WP15 (rule 28); WP4 items 1-4, 8; `work_usage` and `model_prices` (§4); WP7 item 1 (pause, not fail); WP10/WP11 screens; Q2 defaults lowered to 200 answers / 500,000 tokens / one dollar; per-provider and monthly caps not built |
| #43 importing another app's exports (§2) | P | Receiving shares: WP16, rule 34, Tier B. Folder pick-up: only with Q1 = yes (Appendix A), because it is intake that runs while nobody watches |
