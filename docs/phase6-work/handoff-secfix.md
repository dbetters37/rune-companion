# Hand-off from the wave-1 security fixes (F1-F14)

Branch `p6/secfix` (worktree `/home/user/p6-secfix`), from 8ac755d. Commits: af6f471 (core and testkit: F1, F2, F5-F9,
F14), 99e7843 (wiring and tests: F3, F4, F10-F13), 1a3ff9f (F11 `UpkeepHost`), then the **second review's round** (two
skeptics re-checked every finding; what they found still open is closed in dbbe634: see "Second review" at the end,
which overrides anything above it). Merged into `claude/ai-control-center-architecture-wjdfww` as 90e1d70. Findings: `review-wave1-security.md`. Contracts guide:
`phase6-contracts.md` (every changed contract says "(F<n>)" or "second review"; section 4 is **FROZEN v1**).

Whole suite after the fixes: 2,746 tests, 0 failed (2,701 before; 45 new). After the second review: 2,775 tests, 0 failed
(app 473, core 164, data 430, device 335, framework 779, memory 107, providers 227, providers-anthropic 89, testkit 171),
on p6/secfix and again, re-run, on the merged branch; `:app:assembleDebug` and `:data:assembleDebugAndroidTest` build.

Read this before you start your package. "Must" and "must not" below are as binding as the plan's rules; each names the
test that holds you to it. Tests marked **(add)** are yours to write.

## Changes every package inherits

| What changed | Where | Who is affected |
|---|---|---|
| `ProviderManager` lost `recheckIfStale`, `timedWaits`, `matchesStoredKey`; it is **owner-only** in both architecture tests | core/llm/ProviderManager.kt | WP4, WP5, WP7, WP2 |
| New `ProviderRechecks` (agent side: `recheckIfStale`, `timedWaits`, `None`) and `OwnerKeyCheck` (owner-only: `matchesStoredKey`, `None`) | core/llm/ProviderRechecks.kt | WP4 builds, WP7/WP2 use the first, WP5 the second |
| `ModelsAssembly.providerRechecks` and `.ownerKeyCheck` (both `None` now); `TeamAssembly(providerRechecks = …)`; `OperatorAssembly(…, ownerKeyCheck)`; `AppGraph.providerRechecks` | app/di/wiring/ModelsWiring.kt, TeamWiring.kt, OperatorWiring.kt, app/di/AppGraph.kt | WP4, WP5, WP7, WP2 |
| `UsageLedger` lost `pruneHours`/`pruneOrphanedWork`; new owner-only `UsageHousekeeping` (clamped: never today's rows); `RoomUsageHousekeeping`; `DataLayer.usageHousekeeping`; `UsageStores.housekeeping` | core/usage/Usage.kt, data/repository/RoomUsage.kt, data/DataLayer.kt, testkit | WP4, WP2 |
| Both architecture tests also forbid `ProviderManager`, `SecretVault`, `OwnerKeyCheck`, `UsageHousekeeping` and the owner action `answer(RequestId…)`; `ProviderConnections` is the one sealed holder of the vault | framework/src/test/.../operator/OperatorDependencyTest.kt; app/src/test/.../di/AgentFacingWalk.kt (shared walk), OperatorDependencyTest.kt | all |
| The app walk's roots: executor, broker, every tool, **`agentChat`, `modelGateway`, `registryDirectory`** | app/src/test/.../di/OperatorDependencyTest.kt | WP7, WP4, WP8 |
| **`ProductionGraphDependencyTest`** walks the graph `createProductionPorts` builds (unencrypted `DataLayer`, real device layer); it found and the fix removed a real leak (the app actions' start gate captured the whole `ScreenOperatorLayer`, so tools reached Stop and Resume) | app/src/test/.../di/ProductionGraphDependencyTest.kt, app/di/ProductionPorts.kt | anyone touching `ProductionPorts` or a production class reachable from a tool |
| `ObjectiveOptions.screenActionsAllowed` (default `routineId == null`: fails closed for a routine) and `.approvalTimeoutMinutes` (null); `Routine.objectiveOptions()`; the broker refuses `screen.read`/`screen.operate` when false, or when the objective's record cannot be read, and uses the objective's time-out | core/tasks/Tasks.kt, core/routines/Routines.kt, framework/operator/PermissionBroker.kt | WP5, WP7, WP15, WP17 |
| `UpkeepGraph(ports: UpkeepPorts, steps)`; `UpkeepHost`; `RoutinesWiring.upkeepSteps(ports: PickUpStepPorts)`; `PickUpFolders`/`PickUpFoldersView`; `RoutinesWiring.ACTION_RUN_DUE_ROUTINES`; `RoutinesAssembly.runDue()` | app/work/UpkeepGraph.kt, app/di/wiring/RoutinesWiring.kt, app/operator/NarrowViews.kt, core/pickup/PickUp.kt, AiControlCenterApp.kt | WP2, WP17 |
| `BackupWiring.production(stores: BackupStores, gate, freeBytes)`; `DataLayer.backupStores`; `OwnerBackup.save(passphrase, generatedShown, open, progress)` | app/di/wiring/BackupWiring.kt, data/DataLayer.kt, core/backup/Backup.kt | WP3 |
| `OwnerPickUpRules` refuses a folder agents may change; `PickUpSafety` | core/pickup/PickUp.kt, testkit `ReferenceOwnerPickUpRules`, `OwnerPickUpRulesContract` | WP17, WP10 |
| `AnswerCheck` (separated, spelled and bare codes; case-insensitive key prefixes; more shapes) and `QuestionRules.APPROVAL_WORDS` (go-ahead words) | core/operator/AnswerCheck.kt | WP5, WP11a |
| `WebAddressPolicy` refuses IPv4-compatible and 6to4 forms | core/operator/WebReader.kt | WP5 |

## WP4 (usage, cost, limits, provider trouble)

Must:
- Build `DefaultProviderRechecks : ProviderRechecks` in framework/llm/ProviderRechecks.kt (the plan's `ProviderRechecks`
  class is now this name; the core interface took the plain name). It **must not hold `ProviderManager` or `SecretVault`**:
  share the check with `DefaultProviderManager` through a small internal checker over `ProviderConnections`, the provider
  repository and the settings view (the connections are the one allowed holder of the vault). Wire it as
  `ModelsAssembly.providerRechecks` (replace `ProviderRechecks.None`); start it on `appScope` there.
- Build the owner's key check (e.g. `VaultKeyCheck : OwnerKeyCheck` in framework/llm) over the vault: true when the text is
  or contains a stored provider key, compared in constant time (e.g. every window of each key's length with
  `MessageDigest.isEqual`); never returns, logs or caches a key. Wire it as `ModelsAssembly.ownerKeyCheck`. Only
  `OperatorAssembly` receives it (for WP5's owner side).
- Give the gateway's journal and the daily limiter `UsageLedger`, `UsageReports` and the read-only `ModelPrices` only
  (`ports.usageLedger`, `ports.usageReports`, `ports.modelPrices`; production `DataLayer.modelPrices` is the private
  `ReadOnlyModelPrices`, which `ProductionGraphDependencyTest` already accepts as a view). Never `OwnerModelPrices`,
  `OwnerUsage`, `UsageHousekeeping`.
- Plan item 9 ("`UsageHousekeeping.prune(now, settings)`") becomes a framework step with another name, e.g.
  `UsageRetention(housekeeping: UsageHousekeeping, settings: SettingsView, clock, zone)` in framework/llm, that WP2's
  `UpkeepGraph` runs with `UpkeepPorts.usageHousekeeping`. It computes the cut from `usageRetentionDays` (0 = keep all);
  the store clamps anything inside today anyway.

Must not: add `recheckIfStale`, `timedWaits` or `matchesStoredKey` back to `ProviderManager`; hand `providerManager` to
anything but the owner's screens and `AppStartup`.

Tests (add): framework `OperatorDependencyTest.agentFacingRoots()` += `DefaultProviderRechecks::class.java` (one line in a
shared test file, allowed); `VaultKeyCheckTest` (exact key, key inside a longer answer, a key of another provider, no
key stored, a 3-character "key" never matches everything); `UsageRetentionTest` (90/365/730/0 days; a cut inside today
deletes nothing of today, on Room through `RoomUsageHousekeeping`); the app walks stay green with the real rechecks
wired (`OperatorDependencyTest`, `ProductionGraphDependencyTest`).

## WP15 (lean requests)

Must:
- Keep `AgentRunner` and `ToolMenu` free of `ProviderManager`, `OwnerKeyCheck`, `UsageHousekeeping` and the vault (the runner
  is reached from the chat, a root of both app walks).
- Leave authorisation to the broker: a screen tool of a routine objective whose `screenActionsAllowed` is false is refused by
  `PermissionBroker` with `ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED`; `tools_open("screen")` must not route around it.
  Optionally, do not offer the `screen` family at all in such a run (fewer tokens); the broker check stays either way.

Tests (add): a run of a routine objective with `ObjectiveOptions(screenActionsAllowed = false)` whose scripted model calls
`screen.read` gets the refusal sentence and the screen operator sees no call; the D4 hold test keeps passing.

## WP7 (orchestration, teamwork, chat)

Must:
- Use `TeamAssembly`'s `providerRechecks: ProviderRechecks` (not a provider manager) for D28 `recheckIfStale` in
  `WorkSteps.chooseAgent` and `DefaultAgentChat`, and for publishing waits; the orchestrator's and chat's constructors take
  `ProviderRechecks`. Both app walks start at `agentChat`; the team tools reach the orchestrator.
- Store `ObjectiveOptions` unchanged on the objective, all fields (`routineId`, `screenActionsAllowed`,
  `approvalTimeoutMinutes`); any code that rebuilds options (resume, recovery, D20) must copy, never construct anew.
  Objectives the owner starts by hand keep the defaults (`screenActionsAllowed = true`).

Must not: give the orchestrator, chat, `OwnerNotes`' agent side or `DataBlock` framing any owner-only type (the walks fail).

Tests (add): `startObjective` with `ObjectiveOptions(screenActionsAllowed = false, approvalTimeoutMinutes = 90)` keeps both
on the root task through Room (`StoredColumnsTest`-style round trip) and through recovery after a restart; a chat that
waits for an OFFLINE provider calls `ProviderRechecks.recheckIfStale` (fake rechecks) and never a `ProviderManager`.

## WP5 (broker, questions, phone-operator fixes)

Must:
- Implement rule 32's stored-key check in `OwnerServices.answer` with `OwnerKeyCheck.matchesStoredKey`, passed from
  `OperatorAssembly(…, ownerKeyCheck)` to `DefaultOwnerRequests` (the owner's port) only; never to the broker,
  `OwnerQuestions`' agent side or `AskOwnerTool`.
- Name no public method of an agent-facing class `answer` with a `RequestId` first parameter: that is now an owner-only action
  in both walks (use e.g. `awaitAnswer` for the agent side's wait).
- Use the new `AnswerCheck` (separated and spelled-out codes, bare 4-8 digit answers are CODE whatever the question, key
  prefixes in any case, AWS ids, Slack, GitLab, Stripe, Hugging Face) and `QuestionRules.approvalWordIn` (confirm, proceed,
  ok, accept, agree, authorise, consent, pay, buy, purchase, install, uninstall, send, delete, erase, "do it", "go ahead").
- When the broker's wait is extracted into the helper shared with `OwnerQuestions`, keep `PermissionBroker.authorize`'s
  first check (screen tools refused when `screenActionsAllowed` is false, before `gate`) and `newRequest`'s time-out source
  (the objective's `approvalTimeoutMinutes`, brought into 1..240, else the setting). Questions keep
  `ownerQuestionTimeoutMinutes`.
- Apply the objective's `approvalTimeoutMinutes` in `DefaultCapabilityRequester` too (an agent's own `permissions.request`
  in a routine objective).
- `AddressGuard`: call `WebAddressPolicy.literalRefusal` and `refusal(InetAddress)`, which now refuse `::a.b.c.d` and
  `2002:…` forms of refused addresses.

Tests (add): `RoutineObjectiveBrokerTest` (framework, already there) stays green after the refactor; view-model and
`OwnerServices` tests with `4.8.2.9.1.3`, "four eight two nine one three" (CODE, sent only on the second tap) and
`XAI-…`, `AKIA…` (KEY, never sent); an answer equal to a key in a fake `OwnerKeyCheck` is refused; `DefaultCapabilityRequester`
expiry follows a routine's time-out; D8 broker test with `http://[::127.0.0.1]/` and `http://[2002:7f00:1::]/`;
framework walk roots += `OwnerQuestions` (and its agent-side tool).

## WP2 (background work and recovery)

Must:
- Build on `UpkeepGraph(ports: UpkeepPorts, steps)`. `UpkeepPorts` holds the memory service, the activity log, usage
  housekeeping, the settings view, the gate, clock, zone, `noBackupFilesDir` and `versionCode`. Add what the database
  check and the start ledger need as **narrow ports of their own** (e.g. `databaseCheck: suspend () -> DatabaseState`
  built by the application from `data/recovery/DatabaseHealth`, a `StartLedger` over a file); never `DataLayer`, a
  `Context`, the application, `AppGraph` or `Ports` (`UpkeepIsolationTest.wide`).
- `UpkeepJobService` reads its graph as `(application as UpkeepHost).upkeep`, never naming `AiControlCenterApp`, `graph`,
  `graphIfBuilt`, `DataLayer`, `dataLayer` or `applicationContext` (`UpkeepIsolationTest` scans app/work/Upkeep*).
- Run usage retention with `UpkeepPorts.usageHousekeeping` (WP4's `UsageRetention`) and activity pruning with
  `activityLog.prune`.
- `WakeHold` reads `AppGraph.providerRechecks.timedWaits` (and the orchestrator's), never a provider manager.
- `ObjectiveWorkService.onStartCommand` handles `RoutinesWiring.ACTION_RUN_DUE_ROUTINES`: `startForeground` first, then
  `graph.routinesAssembly.runDue()` in the service's scope (WP17 fills `runDue`; the service may build the graph, a
  receiver may not).
- Keep `DataLayer.usageHousekeeping` and `DataLayer.backupStores` when you change data/DataLayer.kt.

Tests (add): the rule-1 reflection walk from `UpkeepJobService` also fails on `SecretVault`, `PermissionRepository`,
`OwnerRequestRepository`, `ProviderManager`, `OwnerKeyCheck` (and treats `Context` fields as leaves); extend
`UpkeepIsolationTest`'s narrowness check to every port you add (do not loosen `wide`); a Robolectric test that
`ACTION_RUN_DUE_ROUTINES` starts the service in the foreground and calls `runDue` once.

## WP17 (routines and folder pick-up)

Must:
- `OwnerPickUpRules`: refuse `create` and `setEnabled(true)` with `PickUpSafety.folderProblem(folder)` (the folder must
  exist and be read-only for agents); pass `OwnerPickUpRulesContract` (now 6 tests, incl. "a rule is refused for a folder
  agents may change" and "a folder made writable later cannot be switched on").
- The pick-up runner, at every run of every rule, reads the rule and the folder afresh and calls
  `PickUpSafety.runProblem(rule, folder, settings.folderPickUpEnabled)`; on a sentence it imports nothing and stores it as
  `lastResult`; on `RUN_FOLDER_NOT_READ_ONLY` it also switches the rule off.
- Every imported file becomes memories made by `PickUpSafety.memoryDraft(rule, folder, title, text, at)` (origin IMPORT,
  source "Picked up from <folder>", tag `picked-up`) written through `MemoryActor.Owner`; never origin OWNER. A file longer
  than one memory (20,000 characters) is split into parts titled "<name> (part 2 of 3)" (or skipped with a sentence).
- Build the pick-up step only from `RoutinesWiring.PickUpStepPorts` (`upkeepSteps(ports)`); read folders only through
  `PickUpFolders`. Wire `PickUpFoldersView(dataLayer.sharedFolders, <reader>)` in `AiControlCenterApp.upkeep` in place of
  `PickUpFolders.None`; the reader is the device's SAF gateway (`SafSharedFolders` is internal to `:device`: add one public
  factory, e.g. `PhoneOperatorLayer.folderFiles(context, folders): SharedFolderFiles`).
- Start every routine run with `routine.objectiveOptions()` (never `ObjectiveOptions(routineId = …)` alone).
- `RoutineAlarmReceiver` and `BootReceiver` only signal: the alarm starts `ObjectiveWorkService` with
  `RoutinesWiring.ACTION_RUN_DUE_ROUTINES` (explicit component); re-arming after boot is a one-off job or the same
  action. They never name the application, the graph or the data layer (`UpkeepIsolationTest` scans app/routines/*).
  Implement `RoutinesAssembly.runDue()`.
- "Tap to start" (F12): when Android refuses to start the work, the run is stored WAITING_FOR_TAP and a notification with
  **no actions** opens `MainActivity` with **no new extra** (rule 23); the app shows "Routine '<title>' is waiting to start"
  with a Start button the owner taps inside the app (WP10's screen or a Dashboard banner). Never start work from a
  notification or a receiver for a waiting run; do not set `showWhenLocked`.

Tests (add): runner tests (rule made, folder then made writable, a run imports nothing, `lastResult` is the sentence, the
rule is off; master switch off; folder removed); a picked-up memory has origin IMPORT and is recalled framed as data;
`routine.objectiveOptions()` reaches the broker (a routine without the tick: a screen tool refused); Robolectric
`ShadowNotificationManager`: the waiting-routine notification has no actions and its content intent targets
`MainActivity` without extras; `RoutineAlarmReceiver.onReceive` starts the service with the action and nothing else.

## WP3 (backup file)

Must:
- Implement only the records: write with `BackupPreamble.write`, `BackupChunks.seal` (fresh `SecureRandom` nonces),
  read with `BackupPreamble.read`, `.unlock`, `BackupChunks.open`, in section 4's order; no crypto of your own, no
  `PBEKeySpec`.
- `RoomOwnerBackup` is built in `BackupWiring.production(stores: BackupStores, gate, freeBytes)` from `BackupStores` alone
  (`DataLayer.backupStores`: agents, providers, models, prices, AI apps, memories, conversations, tasks read-only, settings,
  activity log, one transaction). Do not add the data layer, a context, the vault, grants, requests, folders or apps
  (`BackupWiringTest`). If you need more, add a narrow member to `BackupStores` and keep the test green.
- Save: refuse with `WeakPassphrase(problemForSave(passphrase, generatedShown))`; manifest settings =
  `BackupSettings.of(settings)`; drop what section 4 lists (headers but `HTTP-Referer`/`X-Title` without a key-like value;
  AI apps' certificate, install time, verified and accessibility flags; Task-scope memories). Routines, runs, pick-up rules
  and imports are not in v1.
- Restore and inspect: `RestoreSettings.merge(current, file, agentsAfterRestore)`; show `changes`, `keptBack` and
  `RestoreSettings.ALWAYS_OFF_AFTER_RESTORE`; providers with `apiKeyAlias = null` always, status UNKNOWN, the farther
  locality; AI apps unverified; Task memories dropped and counted; all inside `stores.transactions.run` within
  `gate.runExclusive`.

Tests (add): everything in section 4's "Tests WP3 owes", including the instrumented `BackupVectorsDeviceTest` (the section's
vectors on the phone's provider) and the F13 drops.

## WP6 (scripts in their own process)

Must: keep the engine and `RunHost` reachable from the `scripts.run_js` tool free of owner-only types (the tool is a root
of both app walks, including `ProductionGraphDependencyTest`, which walks the production engine; `Context` and binder
fields are leaves, everything of `com.aicontrolcenter.*` is walked). Nothing else changed for WP6.

Tests (add): none beyond the plan; `ProductionGraphDependencyTest` must stay green with the isolated engine wired.

## Wave 3 notes

- WP8: add the wave-2 roots (`OwnerQuestions`' agent side, the script host, `DefaultProviderRechecks`) to
  `OperatorDependencyTest.roots()` and `ProductionGraphDependencyTest`; production-only narrow views go in the latter's
  `productionViews` with a sentence each (today: `DataLayer.ReadOnlyModelPrices`, device `StopSignal`, `AuthorisedAppView`).
- WP10: the folder pick-up screen shows `FOLDER_NOT_READ_ONLY` as the reason a folder cannot be chosen (offer "Make it
  read-only"); the restore inspect screen lists `changes`, `keptBack` and the always-off line (the "Director" entries carry
  an agent id: show that agent's name, or "an agent not in this backup"); the passphrase field uses `problemForSave`.
- WP11a: question options are refused with the new approval words; the answer field follows the new CODE rules.

## Deviations from the reviewer's proposals (and why)

1. **F5:** besides the keyed check, the file carries an unkeyed SHA-256 digest of everything before the chunks. Without it a
   flipped bit in the salt, the iteration count or the key check would read as "wrong passphrase"; with it, accidental
   damage is `Damaged` before the KDF runs, as WP3's planned test expects. It authenticates nothing (the key check does).
2. **F6:** `MAX_ITERATIONS` lowered to 2,000,000 **and** the KDF is cancellable (both proposals).
3. **F7:** four real words or 60 estimated bits, where a real word has 4+ letters and 3+ different letters and is not a
   common password, a keyboard walk or an alphabet run ("aaaa bbbb cccc dddd eeee" has 5 distinct 4-letter words and is
   refused). `problemForSave` is the single gate instead of a contract sentence only.
4. **F8:** AWS key ids are matched by their exact shape (case-sensitive), not as a case-insensitive prefix, so
   "Asia-Pacific-Region" is not a key; a run of more than 8 digits is not a code (phone numbers).
5. **F9:** suggested options are kept but refused with the go-ahead words (the proposal's list plus agree, authorise,
   consent, uninstall and "do it"); "Yes" and "No" alone stay allowed.
6. **F11:** besides narrow ports, `UpkeepHost` lets the job find its graph without naming the application, and
   `RoutinesWiring.ACTION_RUN_DUE_ROUTINES` / `RoutinesAssembly.runDue()` give the receivers something to signal.
7. **F12:** implemented now (small): the broker refuses screen tools when off and uses the objective's time-out.
8. **F13:** the save drops and restore rules are frozen in section 4 for WP3 to implement; the header rule is an
   allow-list (`HTTP-Referer`, `X-Title`) plus `looksLikeKey`, so no key check of the vault is needed on save.
9. **F10:** the production walk stops at Room's database and DAOs (`com.aicontrolcenter.data.db.`), which every Room store
   shares; the ports above them are what the rule is about.


## Second review (round 2 of the security fixes): what changed again, and what each package must do

Two skeptics re-checked F1-F14, the frozen format and this hand-off. Everything they found open is closed in dbbe634,
each with a test that fails without it (18 mutations of the fixed code, each caught: logs in
`scratchpad/secfix2/mutation-summary-all.txt`). **This section overrides anything above it.**

### Changes every package inherits

| What changed | Where | Who is affected |
|---|---|---|
| Registry stores are owner-only in both walks: `AgentRepository`, `ProviderRepository`, `ModelRepository`, `AiAppRepository`. Agent-facing classes declare `AgentDirectory` / `AgentStatsLog` (reads + `updateStats`), `ProviderDirectory`, `ModelDirectory`, `AiAppDirectory`, wired with `ReadOnlyRegistry.agents/providers/models/apps(store)`; the memory service keeps a view and `SpaceAccessRevoker` | core/registry/Repositories.kt; framework (gateway, chat, registry directory, runner, orchestrator, facts, memory tools); memory (DefaultMemoryService, MemoryContext); app wiring | WP4, WP5, WP7, WP15, WP17, WP2 |
| `ProviderConnections` uses a key only under an alias of the record's own id (`KeyAliases.belongsTo`); `ProviderLease.exposes`/`redact` are `internal`; a lease held outside the connections and the gateway fails both walks | framework/llm/ProviderConnections.kt | WP4 |
| Framework walk: reads generic type arguments of fields, fails on fields typed `Any` (locks aside), confines `ProviderLease`. App walk: reads through `AtomicReference`, `Reference`, `StateFlow.value`, completed `Deferred`, `Optional`; Room's database, open helper and DAOs only inside Room stores or `RoomTransactions` (no more leaf packages); `DataLayer.database` is `internal` | framework/src/test/.../OperatorDependencyTest.kt; app/src/test/.../di/AgentFacingWalk.kt, ProductionGraphDependencyTest.kt, BackupWiringTest.kt | all |
| Schema 6 gains three columns: `shared_folders.read_only_since` (ALTER in `Migration5To6.ADD_COLUMNS`), `pickup_rules.folder_read_only_since`, `pickup_imports.seen_only` (in the CREATE TABLE) | data/db/entity, AiccMigrations.kt, schemas/6.json | WP17, WP2 |
| Pick-up: `SharedFolder.readOnlySince`; `PickUpRule.folderReadOnlySince`; `PickedUpFile.seenOnly`; `PickUpFolders.all()`; `PickUpSafety.folderProblem(folder, allFolders)`, `runProblem(rule, folder, allFolders, masterSwitchOn)`, `overlapsWritable`, `contentHash`, `baseline`; `FOLDER_OVERLAPS_WRITABLE`, `RUN_FOLDER_OVERLAPS_WRITABLE`, `FOLDER_UNREADABLE`; `FolderTrees.overlap`; `DefaultOwnerFolders(..., pickUpRules)` switches rules off when a folder becomes writable or a writable folder is shared around/inside one; `ReferenceOwnerPickUpRules(repository, folders: PickUpFolders, memory, clock, ids)`; testkit `InMemoryPickUpFolders` | core/pickup/PickUp.kt, core/operator/FolderTrees.kt, framework/operator/OwnerServices.kt, device SafSharedFolders.kt, testkit | WP17, WP10 |
| Backup: `BackupRecords` (record classes, `JSON`, `encode`/`decode`, `providerForSave`/`ForRestore`, `aiAppForSave`/`ForRestore`, `memoryForSave`/`memoryInFile`, `entryForSave`, `toolLine`); `BackupCounts` is `@Serializable` (`KEYS`); salt must be the padded base64 exactly; `BackupFormat.MAX_FILE_BYTES` = 1,073,779,790 (one bound; `MAX_BODY_BYTES`, `CHUNK_OVERHEAD_BYTES`, `PREAMBLE_FIXED_BYTES`); `Passphrases.normalise` folds case both ways and drops Cf and Pd; `check` needs 55 bits with four words, counts words apart only when they differ in two letters without accents; `problemForSave` trusts only an exactly generated `generatedShown` | core/backup/* | WP3, WP10 |
| `RestoreSettings.merge(current, file, agentsAfterRestore: Map<AgentId, String>)`; the Director is shown by name (`UNKNOWN_AGENT` otherwise) with the ids in `SettingChange.agentFrom/agentTo`, `SettingKeptBack.agentInFile/agentAfterRestore` | core/backup/RestoreSettings.kt, Backup.kt | WP3, WP10 |
| `AnswerCheck`: code words in the answer too, digits of any script, looser separators under a code word, letter-and-digit codes, keys typed in groups; `QuestionRules`: look-alike letters read as Latin (`skeleton`), mixed alphabets refused (`mixesAlphabets`), many more go-ahead words and phrases | core/operator/AnswerCheck.kt | WP5, WP11a |
| `WebAddressPolicy` also refuses IPv4-translated, ISATAP, Teredo forms and the local-use NAT64 prefix | core/operator/WebReader.kt | WP5 |

### WP3 (backup file), in addition

Must:
- Write and read every record with `BackupRecords.encode`/`decode` and its classes; no record class or JSON of your own.
  Use `providerForSave` (write nothing when `record` is null and count it; show `PROVIDER_LEFT_OUT` and, when
  `headersLeftOut > 0`, `HEADERS_LEFT_OUT`), `aiAppForSave`, `memoryForSave`, `entryForSave(entry) { name -> <the tool's
  title from the registry, or the name> }`; on restore `providerForRestore`, `aiAppForRestore`, `Price.toPrice()`.
- After the end record, any plaintext byte is `Damaged` (test it).
- Call `BackupPreamble.unlock` and `BackupKeys.derive` on `Dispatchers.Default`; the KDF progress is indeterminate with
  Cancel (`derive` reports no progress).
- `BackupVectorsDeviceTest` also asserts `BackupKeys.derive(…, 600_000)` takes at most 15 s on the emulator and logs it.
- Pass `agentsAfterRestore` as a map of every agent after the restore to its name; never show an agent id.

Tests (add): the golden keys are pinned in core (`BackupRecordsTest`); if a record class changes, update that test and
section 4 together, never one alone.

### WP5 (broker, questions), in addition

Must:
- `DefaultCapabilityRequester` refuses a `screen.read`/`screen.operate` permission request in an objective whose
  `screenActionsAllowed` is false, or whose record cannot be read, with `ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED`,
  before anything is raised (the broker already does this for tool calls). Test (add): a routine objective without the
  tick asks for "Use an app's screen" and nothing reaches the owner.
- Use `QuestionRules.questionProblem` as it is (it now refuses mixed alphabets and the new go-ahead words) and
  `AnswerCheck.classify` (code words in the answer count).

### WP7 (orchestration, chat), in addition

Must: construct the orchestrator, chat and runner with `ReadOnlyRegistry` views (as `TeamWiring`/`AgentsWiring` now do),
never `ports.agents`/`ports.providers`/`ports.models` directly; copy `ObjectiveOptions` (the default now fails closed for
a routine, so a rebuilt `ObjectiveOptions(routineId = …)` silently loses screens instead of gaining them).

### WP4 (usage, providers), in addition

Must: `DefaultProviderRechecks` reads providers through a `ProviderDirectory` and stores a status only through
`ProviderConnections.storeStatus`; it never holds `ProviderRepository` (both walks now fail on it). Keep key aliases in
`KeyAliases` (`base`, `next`, `belongsTo`).

### WP17 (routines and folder pick-up), in addition

Must:
- The runner calls `PickUpSafety.runProblem(rule, folder, folders.all(), masterSwitchOn)` at the start of every run **and
  again after reading the files, before storing any memory**; on a sentence it stores nothing, sets `lastResult`, and
  switches the rule off for `RUN_FOLDER_NOT_READ_ONLY` and `RUN_FOLDER_OVERLAPS_WRITABLE`.
- Skip every file whose `PickUpSafety.contentHash` the rule has recorded (`wasImported`: imported or seen); record imports
  with `seenOnly = false`.
- The real `OwnerPickUpRules` behaves as `ReferenceOwnerPickUpRules` (baseline before switching on; `FOLDER_UNREADABLE`)
  and passes `OwnerPickUpRulesContract` (9 tests).
- Optional, labelled "(test)" until it fully works: a list of the files seen when the rule was switched on, where the
  owner ticks those to import.
- **The in-app "Routine '<title>' is waiting to start" Start button is WP17's** (a Dashboard banner in
  `app/routines/`), with its test (the banner appears for a WAITING_FOR_TAP run, Start asks once more and starts exactly
  that run, nothing starts without the tap). WP10 only links to it from the Routines screen.

### WP10 (owner screens, wave 3), in addition

The folder pick-up screen shows `FOLDER_OVERLAPS_WRITABLE` and `FOLDER_UNREADABLE` like `FOLDER_NOT_READ_ONLY`; the
Folders screen says, when the owner makes a folder writable, that its pick-up rule was switched off. The restore inspect
screen shows the Director's name from `SettingChange`/`SettingKeptBack` (never the id fields).

### Deviations from the skeptics' proposals (and why)

1. **F1:** besides `readOnlySince` and the seen-files baseline, a new writable folder shared around or inside a pick-up
   folder switches its rule off at once (not only at the next run); the SAF gateway refuses every change in a folder that
   overlaps a stored read-only one (coarser than "a change whose target lies inside it": the gateway resolves names, not
   ids, so the whole folder is refused; the sentence says why).
2. **F3:** the agent store split keeps stats writable for the runner and the orchestrator (`AgentStatsLog`): a track
   record is an observation, like a provider's status. The framework walk cannot see a bound method reference's
   receiver (no type names it); the app walk does (it walks the reference's `receiver` field).
3. **F7:** the year and "digit with its shifted symbol" rules were added to `strengthBits` so "Summer2024!Summer2024!"
   and "Aa1!Bb2@Cc3#Dd4$" are refused; "Seventeen chars!!" stays accepted: without a word list a character-class
   estimate cannot see English words (the generated passphrase is the default).
4. **F8:** the long-run key check is not repeated on the answer with its spaces removed (any sentence with a digit would
   become one run and be blocked for ever); keys typed in groups are found by their equal group lengths instead.
5. **F9:** look-alikes are mapped (Cyrillic, Greek, a few Latin letters) **and** a word mixing alphabets is refused, rather
   than refusing every non-Latin option (an agent may suggest "Москва").
6. **FORMAT:** the record schemas are frozen as the core classes and a golden key test, not as a hand-written field list.
