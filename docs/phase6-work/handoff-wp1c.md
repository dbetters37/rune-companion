# Hand-off from WP1c (framework seams)

Branch `p6/wp1c` (worktree `/home/user/p6-wp1c`), from `p6/wp1a` (0be7d62). Behaviour unchanged: every seam below
is either unused in this wave or used with today's default.

## 1. What WP1c built (plan ids)

| Item | What | Where |
|---|---|---|
| 1 | ScriptTools.kt split by pure moves (checked line by line: the four new files hold exactly the old text, apart from imports): `ScriptTools` object stays; `ScriptsRunJs` + `WorkspaceJsHost` → JsTools.kt; `ScriptsRunShell` (with its nested helpers) → ShellTool.kt; `TermuxRun` → TermuxTool.kt | framework/operator/ |
| 2 | `interface JsEngine { suspend fun run(code, inputJson, host, timeoutMs): JsOutcome }`; `JsSandbox : JsEngine`; `ScriptsRunJs(d, engine: JsEngine)`; `ScriptTools.create(d, js)`; `OperatorToolset.create(d, js: JsEngine = JsSandbox(d.limits.js))` | JsEngine.kt, JsSandbox.kt, JsTools.kt, ScriptTools.kt, OperatorToolset.kt |
| 3 | `RecentLaunches` (per agent, latest launch only, bounded to 200 agents, in memory): `record(agent, packageName?, at)`, `latest(agent, withinMs, now): Launch?`, `launchedWithin(agent, packageName?, withinMs, now)`; `OperatorToolDependencies.launches = RecentLaunches()`; `OperatorLimits.screenOpenWaitMs = 10_000`, `launchRecentMs = 15_000` | RecentLaunches.kt, OperatorToolset.kt |
| 4 | `TeamTools.ASK_OWNER_ID = ToolId("team.ask_owner")`; `WorkSteps.teamTools` offers it when it is registered and `agentQuestionsEnabled` | TeamTools.kt (one constant), WorkSteps.kt (teamTools only) |
| 5 | `SettingsRepository` → `SettingsView` in AgentRunner, OperatorFacts, OrchestrationContext, DefaultOrchestrator, DefaultModelGateway, DefaultProviderManager, DefaultAgentChat, DefaultRegistryDirectory, DefaultToolAuthorizer, memory DefaultMemoryService, MemoryEmbeddings. DefaultAgentManager and MemorySeeder keep the writer | as listed |
| 6 | Framework architecture test: `SettingsRepository` owner-only; settings `update` an owner-only action; roots += DefaultModelGateway, DefaultAgentChat, DefaultRegistryDirectory, DefaultToolAuthorizer; new test of the settings rule | framework/src/test/.../operator/OperatorDependencyTest.kt |

New tests: `JsEngineSeamTest`, `RecentLaunchesTest` (framework operator), `AskOwnerOfferTest` (framework orchestration).

Head commit 5ca66a7. Whole suite: 2,589 tests, 0 failed (core 128, testkit 163, framework 768, memory 107, providers
227, providers-anthropic 89, data 339, device 334, app 434); `:app:assembleDebug` builds.

## 2. Changes needed in files WP1c does not own

### For WP1d (app architecture test, app/src/test/.../di/OperatorDependencyTest.kt)

- **Do not add `update` to `ownerOnlyActions` as a bare method name.** The name alone also matches
  `TaskRepository.update` (the orchestrator's task store), `MemoryService.update`, `AgentManager.update`,
  `RoutineRepository.update` and `PickUpRuleRepository.update`; the orchestrator and the memory tools reach the first
  two, so the walk would fail at once. The framework test checks "an `update` whose signature names
  `FrameworkSettings`" (the transform `(FrameworkSettings) -> FrameworkSettings`, and the continuation that carries
  the result):

  ```kotlin
  private fun changesSettings(method: java.lang.reflect.Method): Boolean =
      method.name.substringBefore('-') == "update" &&
          method.genericParameterTypes.any { FrameworkSettings::class.java.name in it.typeName }
  ```

  In the app test, `ownerActionsOf(type)` should add `"update"` for a method where `changesSettings` holds. That
  catches `DataStoreSettingsRepository` and any wrapper that passes a settings update on, and nothing else.
- `SettingsRepository::class.java` in `ownerOnly`; `ReadOnlySettings::class.java` in `views` (its private `source`
  is the repository).
- In AppGraph (WP1d owns it): hand `ReadOnlySettings(ports.settings)` to every agent-facing holder listed in item 5,
  because the app test walks runtime values: the orchestrator reached through the team tools would otherwise hold
  the DataStore repository. `DefaultAgentManager`, `MemorySeeder` and the owner's view models keep the repository.
  This includes `OperatorToolDependencies(settings = ports.settings::get)` (AppGraph.kt:316 today): a bound
  reference keeps the repository as its receiver, which the walk follows; use the `ReadOnlySettings` instance's `get`.

### For WP1d (OperatorWiring)

- `OperatorToolset.create(d, js = ports.scripts.engine?.invoke(d.limits.js) ?: JsSandbox(d.limits.js))` once
  `ScriptsPorts` exists; until then the one-argument call keeps today's in-process sandbox.

## 3. Notes for the wave-2 packages

### WP6 (JsEngine.kt, JsTools.kt, JsSandbox.kt)

- The seam fits the spike's isolated design: what crosses it is plain data (code and input texts, a time limit,
  a `JsOutcome` of texts and numbers), and the `JsHost` stays in the app's process. `JsOutcome`, `JsHost` and
  `JsHostError` moved from JsSandbox.kt to JsEngine.kt (pure move, same package), so the app-side engine needs
  nothing of Rhino. `JsLimits` stays in JsSandbox.kt, where `engineStartMs` and `bindTimeoutMs` (D2) go.
- The KDoc of `JsEngine` states the contract for `JsEngineContract`: failures, budgets and an engine that cannot
  start end as a `JsOutcome`; cancelling the caller ends the wait; the limits that protect the app (no host call
  after the run, `canWrite`, never overwrite, the file cap, sizes of what comes back) are kept in the app's process,
  never left to an engine in another process (rule 11). Today the file cap (20) and "no host call after the run"
  live inside `JsSandbox.ScriptRun`; for the isolated engine they must be in the run's host binder (RunHost.kt).
- A Kotlin override cannot carry a default argument, so `JsSandbox` has a separate three-argument
  `run(code, inputJson, host)` that uses its own `JsLimits.timeoutMs`; the existing JS tests call that one.
- Spike items that land in WP6's own files (from handoff-spike.md): Rhino initialised on the script service's main
  thread (a helper such as `JsSandbox.prepareOnThisThread()`, F-B), instance names `js0`/`js1`, plain length-prefixed
  pipes, unbind and bind afresh after a death, a per-run watchdog.

### WP5 (D5, team.ask_owner)

- `RecentLaunches`: `AppsOpen` records `record(context.agent.id, packageName, d.clock.now())` on success;
  `AppsOpenLink` and `BrowserOpen` record a null package. `ScreenTools.front` reads
  `d.launches.latest(agent, d.limits.launchRecentMs, now)` and polls every `screenPollMs` for up to
  `screenOpenWaitMs`. Only each agent's latest launch is kept.
- `AskOwnerTool` must use `TeamTools.ASK_OWNER_ID` ("team.ask_owner"), function name `team_ask_owner`
  (`AskOwnerOfferTest` uses that name for its stand-in). Registering it is enough for work tasks to offer it while
  `agentQuestionsEnabled`; nothing in this wave registers it.

### WP7 (WorkSteps.kt)

- `teamTools` became `suspend` and reads `ctx.settings.get()` itself (a second read in `run`, because WP1c owned only
  :189-192). WP7 may pass `run`'s settings snapshot instead.
