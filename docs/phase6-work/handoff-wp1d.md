# Hand-off from WP1d (app seams and wiring hooks; the wave-1 integrator)

Branch `p6/wp1d` (worktree `/home/user/p6-wp1d`), from `claude/ai-control-center-architecture-wjdfww` at 936d8a8
(the merged WP1a, WP0, WP1c, WP1b and the hand-off fix). Behaviour unchanged except R6 (device-transfer exclusions).

WP1d changed no file it does not own, except `app/src/test/.../di/ProductionPortsTest.kt` (one line: the call of
`createProductionPorts`, whose signature WP1d owns, now passes a `DataLayer`). Nothing needs applying by another
package. The rest of this note is what each wave-2 package finds in place, so that none of them edits a shared file.

## 1. Where each wave-2 package plugs in (its own file only)

| Package | Its wiring file (di/wiring) | Called from (shared, do not edit) | What is there now |
|---|---|---|---|
| WP4 | `ModelsWiring.kt`: `ModelsAssembly(ports, settings: SettingsView, activity, adapterFactories, appScope, network)`; `ModelsWiring.network(context): NetworkStatus` | AppGraph (first assembly); `createProductionPorts` (`network = ModelsWiring.network(appContext)`) | registry directory, provider connections, gateway, provider manager (moved verbatim); `NetworkStatus.AlwaysOn`. `appScope` and `network` are unused parameters waiting for the limiter and the re-checks. `ports.usageLedger`, `ports.usageReports`, `ports.modelPrices` (read-only view) are wired to Room. |
| WP5 | `OperatorWiring.kt`: `OperatorAssembly(ports, settings, activity, appScope, registryDirectory, memory, providerManager)` | AppGraph (after memory) | everything of old AppGraph.kt:222-389 plus `operatorHooks` (old :412-416) and the Stop collector (old `init`, :562-580), moved verbatim. `providerManager` is unused, for `matchesStoredKey` (rule 32). New hook `teamReady(orchestrator)`: AppGraph calls it once right after the team assembly registered the team tools and before any agent runs; empty now (for `team.ask_owner`, which needs both the broker's wait and the orchestrator). |
| WP15 | `AgentsWiring.kt`: `AgentsAssembly(ports, settings, gateway, tools, executor, memory, activity, backgroundContext, screen)` | AppGraph (after operator) | monitor and `AgentRunner` (old :391-408). |
| WP7 | `TeamWiring.kt`: `TeamAssembly(ports, settings, registryDirectory, runner, toolRegistry, capabilities, monitor, activity, appScope, memory, operatorHooks, providerManager, maintenanceGate)` | AppGraph (after agents) | orchestrator + team tools + chat (old :419-469); `chatDeletionListener` for the agent manager; `ownerConversation = OwnerConversation.Unavailable`. `providerManager` (D28 `recheckIfStale`) and `maintenanceGate` (item 11) are unused parameters. AppStartup already calls `agentChat.recoverInterruptedTurns()` (new step "Could not mark interrupted chat replies", before recovery) and `orchestrator.recoverInterrupted(cause, resumeAllowed)`. |
| WP2 | `BackgroundWiring.kt`: `BackgroundPorts(health, exits, storage, startup: StartupHooks, recovery: OwnerRecovery?, screens: BackgroundScreens)`; `BackgroundWiring.production(context, data)` | `AiControlCenterApp.background` (built once, without the graph) and passed into `createProductionPorts`, so the app and the graph share the one instance | all `None`; `recovery = null`. `interface StartupHooks` is in di/AppStartup.kt (all members `suspend`, defaults do nothing). |
| WP2 | stubs `work/AppProcessHooks.kt` (`onCreate(app)`, `scopeContext(app)`, `onGraphBuilt(app, graph, scope)` → `GraphServices.start`), `work/GraphServices.kt` (the three `start()` calls moved verbatim), `work/UpkeepJobService.kt`, `work/WakeReceiver.kt`, `work/UpkeepGraph.kt`, `health/AppGate.kt` (`AppGate`, `GateHost`) | `AiControlCenterApp`, `MainActivity` | do nothing / open at once; each marked `@Phase6Stub("WP2")` except GraphServices (moved code). |
| WP3 | `BackupWiring.kt`: `BackupWiring.production(context, data, gate: MaintenanceGate): OwnerBackup?` | `createProductionPorts` (`backup = …`) → `Ports.backup` → `AppGraph.ownerBackup` | returns null. |
| WP6 | `ScriptsWiring.kt`: `ScriptsPorts(engine: ((JsLimits) -> JsEngine)? = null)`, `ScriptsWiring.production(context)`; stub `scripts/ScriptProcessService.kt` (`onBind` returns null) | `createProductionPorts` (`scripts = …`); `OperatorAssembly` builds the toolset with `ports.scripts.engine?.invoke(limits.js) ?: JsSandbox(limits.js)` | in-process sandbox as before. `buildFeatures { aidl = true }` and app androidTest dependencies (junit, kotlin-test, test core, ext-junit, runner 1.7.0, no testkit) are in app/build.gradle.kts. |
| WP17 | `RoutinesWiring.kt`: `RoutinesPorts(routines, pickUpRules)`, `RoutinesAssembly(ports, settings, orchestrator, memory, activity, appScope)` (exposes `ownerRoutines`, `ownerPickUpRules`, both null), `RoutinesWiring.production(context, data)` (wires `data.routines`, `data.pickUpRules`), `RoutinesWiring.upkeepSteps(context, data, memory): List<UpkeepStep>` (empty); stubs `routines/RoutineAlarmReceiver.kt`, `routines/BootReceiver.kt` | AppGraph (after the team, so the orchestrator exists); `createProductionPorts` (`routines = …`); `AiControlCenterApp.upkeep` (the upkeep steps) | nothing runs; master switches off. |

## 2. Notes per package

### WP2
- `AiControlCenterApp`: `onCreate` returns at once when `Process.isIsolated()` (first statement after `super`, before
  `ScreenSessionNotifications.cancel` and `AppProcessHooks.onCreate`, as the spike asked). `appScope` is `by lazy`, so
  `AppProcessHooks.scopeContext` never runs in the script process; every other member is lazy too.
- Process-wide singletons on the application: `dataLayer`, `sharedMemory` (the one `DefaultMemoryService`, with
  `ReadOnlySettings`, the hashing embedder and its own `ActivityRecorder`; the graph gets it through
  `AppGraph(memoryService = …, embedder = …)`), `maintenanceGate` (`LocalMaintenanceGate`; also `Ports.maintenanceGate`
  and `AppGraph.maintenanceGate`), `background`, `recovery` (= `background.recovery`, nullable: deviation, see §3),
  `upkeep`, `gate`, `graphIfBuilt()`.
- `installGraph(graph)` (tests) also installs `AppGate.open()`, so a test with an installed graph never opens the real
  database. Keep that when the gate gets its real check.
- `AppGate.whenOpen(action)` runs now when open; the stub never queues. When you add the check: run queued actions on
  the main thread, in order, and only while the activity still exists. `MainActivity` calls it for `graph.start()` and
  the notification extras (onCreate), for `onNewIntent`, and for `onAppForegrounded` (onStart); `onStop` uses
  `graphIfBuilt()`.
- `UpkeepGraph(context, data, memory, gate, steps)`: deviation from `UpkeepGraph(dataLayer, sharedMemory)` (§3). For the
  rule-1 reflection walk from `UpkeepJobService`: `DataLayer` keeps the application context in a private field, and
  the application holds the graph, so the walk must treat `android.content.Context`-typed fields as leaves (or it
  reaches `AppGraph` through any context).
- `AppStartup`: a hook that throws is logged under its own sentence; `interruptCause` falls back to null and
  `resumeAllowed` to **false** (unsure → nothing resumes by itself; the owner can resume). `beforeSteps`/`afterSteps`
  run as steps ("Could not note that the app started", "… started fully").

### WP4
- `TestApp` passes narrow views over the in-memory stores (`UsageLedgerView`, `UsageReportsView`, `ModelPricesView`,
  in app/src/test/.../testing/TestApp.kt), because `InMemoryUsageStore` also implements `OwnerUsage` and
  `InMemoryModelPrices` also `OwnerModelPrices` (both owner-only in the app test): give the gateway's journal and
  limiter `ports.usageLedger`, `ports.usageReports`, `ports.modelPrices` and the walk stays green. In production these
  are Room's separate classes and `DataLayer.modelPrices` (a private read-only wrapper).

### WP8 (wave 3)
- App architecture test: roots unchanged (executor, broker, every tool); add the wave-2 roots there. `SettingsRepository`
  is owner-only, `ReadOnlySettings` is in `views`, and a settings `update` (by signature) is an owner action.
- The `@Phase6Stub(owner)` annotation is `com.aicontrolcenter.app.Phase6Stub` (source retention); the stub gate greps for
  `@Phase6Stub`. Marked: AppProcessHooks, UpkeepJobService, WakeReceiver, UpkeepGraph, AppGate, BackgroundWiring (WP2);
  ScriptsWiring, ScriptProcessService (WP6); BackupWiring (WP3); ModelsWiring (WP4, `network` only); RoutinesWiring,
  RoutinesAssembly, RoutineAlarmReceiver, BootReceiver (WP17); RecoveryHost (WP10).
- `ManifestRulesTest` (app/src/test/.../ManifestRulesTest.kt) reads the source manifest: it pins the app manifest's own
  9 permissions and that only MainActivity is exported. When WP16's `ShareReceiverActivity` is added, update its last
  assertion; the release merged-manifest test of rules 22 and 23 is still yours.

### WP10 (wave 3)
- `RecoveryHost(state: DatabaseState, recovery: OwnerRecovery?)` in ui/recovery/RecoveryScreen.kt is the stub
  `MainActivity` shows through `GateHost`; it gets `recovery` from the application, never from the graph.
- AppGraph members ready to bind: `usageReports`, `modelPrices`, `ownerModelPrices`, `ownerUsage`, `backgroundHealth`,
  `backgroundScreens`, `storageWatch`, `exitHistory`, `ownerBackup` (null = hide Backup), `ownerRecovery`,
  `maintenanceGate`, `ownerRoutines`/`ownerPickUpRules` (null = hide), `ownerQuestions` (= `ownerRequests`, which has
  `answer`), `ownerConversation`, `settingsView`.

### WP17
- Manifest (already in place, nothing to add in wave 2): `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`;
  `.routines.RoutineAlarmReceiver` (no intent filter, `enabled=false`, `exported=false`: target your alarm
  PendingIntents and the "Tap to start" notification at it explicitly) and `.routines.BootReceiver` (`enabled=false`,
  `exported=false`, actions `BOOT_COMPLETED` and `android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`).
  Enable both with `PackageManager.setComponentEnabledSetting(…, COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP)` when
  the first routine is made, disable when the last goes. Anything else in the manifest (e.g. `MY_PACKAGE_REPLACED`) has
  to go through WP8 in wave 3.
- `BootReceiver.onReceive` returns at once for any action but its two (lint's UnsafeProtectedBroadcastReceiver); keep
  that check when you fill it.
- `RoutinesAssembly` is built in AppGraph after the orchestrator; a receiver reaches it through
  `(context.applicationContext as AiControlCenterApp).graph` (building the graph: routines are the one place work may
  start while nobody watches, Tier B).

## 3. Checks run

- Whole suite in the worktree: 2,701 tests, 0 failed (core 128, testkit 163, framework 768, memory 107, providers 227,
  providers-anthropic 89, data 422, device 334, app 463). `:app:assembleDebug` builds.
- `aapt2 dump permissions` of the debug APK: 18 `uses-permission` (13 of 0.5.2 + the 3 of rule 22 + the 2 routine ones),
  as rule 22 with Q1 = yes says. Merged manifest: the new components are `exported=false`, the script service
  `isolatedProcess=true` on `:scripts`, the routine receivers `enabled=false`.
- `:app:lintDebug` (offline, once): nothing on data_extraction_rules.xml, backup_rules.xml or the manifest entries.
  In WP1d's files only `UseTomlInstead` on the literal `androidx.test:runner:1.7.0` (the same literal as
  device/build.gradle.kts; a catalogue entry is WP8's to add) and the old `OldTargetApi` (targetSdk 36 by decision).

## 4. Deviations from the plan (and why)

1. **`createProductionPorts(context, data, background = BackgroundWiring.production(context, data), maintenanceGate =
   LocalMaintenanceGate())`** instead of `(context, data)`: the application passes its own `background` and gate, so
   the Recovery path, the upkeep job and the graph share one instance of each (two `BackgroundPorts` would mean two
   start ledgers and two recoveries).
2. **`Ports` also gained `routines: RoutinesPorts` and `maintenanceGate`** (WP17's stores; the one gate).
3. **`BackupWiring.production(context, data, gate)`** gained the gate: a restore runs inside `MaintenanceGate.runExclusive`,
   and the call site (ProductionPorts) cannot change in wave 2.
4. **`UpkeepGraph(context, data, memory, gate, steps)`** instead of `UpkeepGraph(dataLayer, sharedMemory)`: the job must
   check the gate and read the start ledger (no-backup folder, versionCode), and WP17's folder pick-up runs as an
   upkeep step (Appendix A: "run by the upkeep job's scheduler") without WP17 editing WP2's file.
5. **`AiControlCenterApp.recovery` is `OwnerRecovery?`** (null until WP2 builds `FileOwnerRecovery`; the gate then opens at
   once), as `BackgroundPorts.recovery` is.
6. **`OperatorAssembly` takes `providerManager` and has `teamReady(orchestrator)`; `TeamAssembly` takes `providerManager` and
   `maintenanceGate`; the Stop collector and `operatorHooks` moved into `OperatorAssembly`.** All so that WP5 and WP7
   need no AppGraph edit for rule 32, `team.ask_owner`, D28 and item 11.
7. **`AppStartup.settings` is a `SettingsView`** (it only reads).
8. **`Ports.ownerModelPrices` and `ownerUsage` default to private stand-ins** that refuse or do nothing (core has no `None` for
   these owner ports); production and `TestApp` pass the real stores.
9. **BootReceiver also listens for `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`** (re-arm the alarms exactly once the
   owner allows exact alarms), so that WP17 needs no manifest edit for it.
10. **`AppGraph.settingsView`** (public, `ReadOnlySettings`) is the one read-only view every assembly gets.
