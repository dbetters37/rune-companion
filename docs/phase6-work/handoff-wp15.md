# Hand-off from WP15 (lean requests, owner request #43)

Branch `p6/wp15` (worktree `/home/user/p6-wp15`), from 90e1d70. WP15 changed only files it owns:
framework/agents/AgentRunner.kt, AgentPromptComposer.kt, new ToolMenu.kt, app/di/wiring/AgentsWiring.kt (KDoc only), and new
tests (framework/src/test/.../agents/: ToolMenuTest, ToolMenuRunTest, LeanRequestBudgetTest, RequestPrefixTest,
AgentRunnerScreenHoldTest, LeanRequestSupport; app/src/test/.../di/wiring/AgentsWiringTest). Nothing needs applying by the
merger for WP15 to work; the items in section 3 are for other packages' files.

## 1. What WP15 built (plan items)

| Item | What | Where |
|---|---|---|
| 1 Tool menu | An agent whose enabled tools (its own plus the run's extra tools) would take more than `ToolMenu.THRESHOLD_TOKENS` = 2,500 estimated tokens (`TokenEstimator`) is offered its always-on tools (the run's extra tools = the team tools inside an objective; `memory.search` and `clock.now` when switched on) plus `tools_open`, whose description lists the agent's other families (tool id before the dot) with one line each (the titles of the agent's tools in that family). `tools_open {"family": …}` (enum of the agent's families; a family name in any case, or a tool's id or function name, also works) appends that family's tools for the rest of the run, in opening order, and answers with their names. A call to an enabled tool not offered yet goes to the executor as usual and opens its family. Agents at or under the threshold get every tool, as before, with today's system prompt. | ToolMenu.kt; AgentRunner.runTools/openFamily; AgentPromptComposer.compose(agent, tools, menu) |
| 2 Stable prefix | The system prompt names only the first call's tools and says `tools_open` adds the rest, so it never changes in a run; definitions only grow by appending; `tools_open`'s own definition never changes. Recalled memories stay in the newest user message (after the prefix), as before. Families the conversation already used (`tools_open` calls and tool calls in the history) are open from the first call, in order of first use, stopping at the first family that would take the first call over the threshold (cache-friendly for a chat's next turn, and the first request never exceeds the threshold because of history). | ToolMenu.openUsedIn |
| 3 D4 caller | `Waits.waiting` (the `OwnerWaits` of every tool call) calls `screen.holdSession(request.id.value, agent.name, packageName)` for a request raised by a `screen.*` tool whose constraint is `CapabilityConstraint.App`; every hold the call took is released (`releaseSession`) in a `finally` around `executor.execute`, so the hold covers the owner's answer, `changedSinceOwnerAnswered` (the app's return) and the action, and is released however the call ends (cancellation included). A PERMISSION then an APPROVAL in one call are two holds. | AgentRunner.Waits |
| 4 Cost per chat | Every model call's `CallContext` carries `conversationId = request.conversationId` (K8). | AgentRunner.Run.callContext |

`tools_open` is SAFE and runner-internal: it is **not a registered tool** (not in the catalogue, cannot be switched on, no
grant, no request), reaches no store, setting or port, and only changes which definitions the next call carries. It can
only list families made at run start from the agent's own enabled tools and the run's extra tools; anything else is
refused and adds nothing. Every real tool call still goes through `ToolExecutor` and `PermissionBroker` exactly as before
(rule 2). If a registered tool ever uses the id `tools.open` or the function name `tools_open`, no menu is made.
Listeners get `ToolMenu.SPEC` (title "Open more tools") in `onToolStarted`/`onToolFinished`; a `tools_open` call does not
count against the tool-call limit or the agent's tool-call stats (it is bounded by the model-call limit).

`AgentRunner` and `ToolMenu` hold no `ProviderManager`, `OwnerKeyCheck`, `UsageHousekeeping` or vault (secfix WP15 "must");
both app walks (`OperatorDependencyTest`, `ProductionGraphDependencyTest`) pass.

## 2. Measurements (estimated tokens, `TokenEstimator`; printed by `LeanRequestBudgetTest` and `AgentsWiringTest`)

| Agent (first request) | Tool definitions before → after | System prompt before → after |
|---|---|---|
| Operator, chat | 49 tools, 7,045 → 3 tools (clock_now, memory_search, tools_open), 710 | 881 → 746 |
| Operator, task (with team tools) | → 5 tools, 1,009 | → 754 |
| Director / Researcher / Analyst / Writer / Reviewer, chat | 5 tools, 1,078 (unchanged, under the threshold) | 649-745 (unchanged) |
| same, task (with team.ask_agent, team.delegate) | 7 tools, 1,377 (unchanged) | 656-753 |

The Operator's first request: 7,926 → 1,456 estimated tokens (system prompt and tools), 5.4 times smaller. Opening a family
adds its definitions (workspace 1,468; folders 1,216; screen 1,102; apps 902; scripts 647; permissions 401; media 264;
termux 138; web 156; browser 74; team 64; memory (memory.save only, as memory.search is always on): part of the two memory tools' 518).

## 3. Changes needed in files WP15 does not own

1. **Chat and task transcripts show `tools_open` as a raw name (plain language).** `ChatPresentation.toolCallUi` and
   `LiveTurn.toUi` (app/ui/chat, via `ChatViewModel.toolInfo` → `tools.byFunctionName`) and the task transcript/Team view look
   tools up in the registry, where `tools_open` is not registered, so the owner sees "tools_open". Show
   `ToolMenu.TITLE` ("Open more tools") when `name == ToolMenu.FUNCTION_NAME` (or use `ToolMenu.SPEC`), e.g.
   "Opened more tools: workspace". **WP11b** (chat), **WP11a** (task transcript), **WP9** (Team view). Wave 3 (app/ui is
   nobody's in wave 2).
2. **Backup (WP3, wave 2b):** `BackupRecords.entryForSave(entry) { name -> title }`: map `ToolMenu.FUNCTION_NAME` to
   `ToolMenu.TITLE`, so the file says "A tool was used here: Open more tools." rather than "tools_open".
3. **Usage source (WP4, at merge):** the runner now passes `conversationId` in every `CallContext` that has one. Task runs
   have one too (the task transcript's conversation, `StepRunner.run`), alongside `taskId`/`rootTaskId`. `workItems`
   already prefers the task (core), but `usage_hours.source` must also be decided by `rootTaskId` first (OBJECTIVE), then
   `conversationId` (CHAT), else OTHER; never CHAT just because a conversation id is present.
4. **Screen tools are assessed before the routine refusal (WP5, framework/tools/ToolExecutor.kt or the broker):** for a
   routine objective with `screenActionsAllowed = false`, `ToolExecutor.carryOut` runs `tool.assess` before
   `authorizer.authorize`, and the screen tools' `assess` calls `ScreenOperator.inspect` (reads the window in front, side
   effect free, nothing leaves the phone, the result is discarded when the broker refuses). The refusal itself works
   (`AgentRunnerScreenHoldTest` checks that the operator's `calls` stay empty and nothing is asked). Optional for WP5:
   refuse `screen.*` in such an objective before `assess`, so the window is not even read.
5. **Optional, WP7:** not offering the `screen` family at all in an objective whose `screenActionsAllowed` is false would
   save its tokens; the runner has no objective options. If wanted, WP7 can pass the agent's tools minus `screen.*` in
   `AgentRunRequest.agent` for such runs (the broker refuses them anyway; authorisation reads the stored agent, so this
   only narrows the offer). Not needed for any rule.
6. **Optional, owner request #43 "compact descriptions":** `permissions.request` alone is 401 estimated tokens and the two
   memory tools 518; every non-Operator agent carries them on every call. Shorter descriptions in
   framework/operator/PermissionRequestTool.kt (WP5) and framework/memory/MemoryTools.kt (WP8 in wave 3) would cut each
   starter agent's first request by a few hundred tokens. Check `LeanRequestBudgetTest` afterwards.
7. **Architecture tests:** nothing to add. `ToolMenu` holds only `ToolSpec`s and definitions and lives inside a run (not a
   field of the runner).

## 4. Device tests to run

None: WP15 has no instrumented tests. For section 10 step 8 (wave 5): the mock server's log of the Operator's first
request should show exactly three tools (`clock_now`, `memory_search`, `tools_open`) and about 710 estimated tokens of
definitions; after "open the workspace" the next request lists the workspace tools after those three.

## 5. Notes for wave 2b and wave 3

- **WP2 (wake lock):** a `tools_open` call is instant and reports no activity; the agent stays WORKING
  ("Waiting for the model" before and after). Screen holds do not affect `AgentActivityMonitor`.
- **WP5 (device side of D4):** the hold id is the `OwnerRequest.id.value` of the waiting request; the agent name is the
  run's `AgentRunRequest.agent.name` (the same name `endSession` gets); `releaseSession` is called once per hold, from a
  `finally`, not suspending. `ScreenSessions` (app/operator, WP5) is what `AgentsAssembly` gets as `screen`, so it must pass
  `holdSession`/`releaseSession` on to the device operator (today they are the no-op defaults).
- **WP7:** `AgentRunner.estimatePromptTokens` now counts the first request as sent (with a menu: the always-on tools,
  `tools_open` and families already used), so `StepRunner.briefBudget` and the chat's "too long" check see a smaller
  overhead for the Operator. A family opened later adds its definitions to later calls; the brief budget's 4/5 margin
  covers a family or two.
- **WP10 (cost screens):** the before/after numbers above are the owner-visible story of goal 1. `ToolMenu.estimatedTokens`
  is public if a screen wants to show what an agent's tools add to every request.
- **WP11b (agent editor):** with `ToolMenu.estimatedTokens(specs.map(ToolMenu::definition))` the editor can say, in plain
  words, "These tools add about N to every request" and, above `ToolMenu.THRESHOLD_TOKENS`, "Many tools: they are offered
  in groups, so each request stays small." Map `tools_open` as in section 3.1.
- **WP12 (review):** attack `tools_open` with a family the agent does not have, a tool name of another agent, unreadable
  arguments, a forged `tools_open` in the history (only the agent's own families can open), and repeated opening (bounded
  by the agent's own catalogue, i.e. the pre-WP15 cost); check rule 2 with `ToolMenuRunTest`'s same-request comparison.
- **WP14 (docs):** ARCHITECTURE (agents: the tool menu, the stable prefix, D4 hold), STATUS (owner request #43, lean
  requests and the numbers above), LIMITATIONS (an agent over the threshold may need one extra model call to open a family;
  `tools_open` calls are not in the Activity log, they act on nothing; a chat's next turn starts with the families it used,
  up to the threshold).

## 6. Deviations from the plan (and why)

1. **`tools_open` is answered inside the runner, not registered as a tool.** Its description is per agent and per run
   (the agent's own families), it must never be switchable in the agent editor or grantable, and it acts on nothing, so
   it needs neither the executor nor the broker; the opened tools themselves always go through both. Consequence: no
   Activity-log entry for it (section 5, WP14).
2. **Family lines are the titles of the agent's own tools in that family**, not a fixed table, so they stay right for any
   tools the owner switched on (an Operator with only two workspace tools sees only those two).
3. **`tools_open` also accepts a family name in any case, or a tool's id or function name**, and its parameter is an enum
   of the agent's families (fewer failed calls with small models).
4. **Families used earlier in the conversation open from the first call** (not in the plan), within the threshold: a chat's
   next turn then starts with the beginning of the tool list the last turn ended with (provider caches), never over 2,500.
5. **A `tools_open` call is not a tool call** for the per-task tool limit and the agent's stats.
6. **`estimatePromptTokens` estimates the first request as sent** (the menu), as its contract says; before WP15 the
   first request was the whole catalogue.
7. **No screen-family hiding for routine objectives** (secfix "optionally"): the runner does not know objective options;
   the broker's refusal is tested through the runner, menu or no menu (section 3.5 for WP7).
8. **`AgentRunner` gained `toolMenuThresholdTokens: Int = ToolMenu.THRESHOLD_TOKENS`** (tests), and `ToolMenu.definition`
   and `ToolMenu.estimatedTokens` are public (the app-level test and future screens; `TokenEstimator` is internal).
