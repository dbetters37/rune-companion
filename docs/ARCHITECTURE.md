# AI Control Center: architecture

AI Control Center is a native Android app that acts as a personal headquarters for AI agents:
it keeps a registry of AI applications, model providers, models and agents; runs agents on
whichever model each is assigned; gives them private and shared memory; lets a Director agent
split an objective across specialists; and lets an authorised agent operate the phone through a
permission system. Everything is stored on the device.

This document describes the design. [STATUS.md](STATUS.md) says which parts are built and how
each was verified. [LIMITATIONS.md](LIMITATIONS.md) lists what Android does not allow and what
each feature needs from you (keys, models, permissions).

## 1. Principles

1. **Three different things.** An *AI application* is an installed Android app with its own
   UI (a chatbot app). A *model* is an LLM the framework can call through a *provider*. An
   *agent* is a configured actor inside the framework that uses a model. They are separate
   types with separate registries, and nothing treats one as another.
2. **Registering is not controlling.** An installed AI app can be opened or handed text
   through Android intents. It can be operated through its UI only by the accessibility
   service, and only after you authorise that app specifically. The framework never reads
   another app's private data.
3. **No provider is special.** Agents reference a model id. Models belong to providers.
   Providers are adapters behind one interface. Replacing an agent's model is a one-field
   change.
4. **Local first.** Data lives in an encrypted database in app-private storage. API keys
   live in a keystore-backed vault. Every transfer off the device is labelled before it
   happens and logged after.
5. **The owner decides.** Tools need a grant per agent. Consequential actions (delete,
   install, send, purchase) need a confirmation every time, whatever was granted.
6. **Bounded autonomy.** Every task has a time-out and budgets for model calls, tool calls,
   attempts and revisions. Delegation has a depth limit and cycle detection. An objective can
   be paused or cancelled at any point.
7. **Modular.** Adding an agent is a data operation. Adding a provider, a tool or a memory
   backend means implementing one interface and registering it.

## 2. Modules

```
                       ┌──────────────────────── :app ────────────────────────┐
                       │ Compose UI (8 sections), view models, composition    │
                       │ root (AppGraph), foreground execution service        │
                       └───────┬──────────────┬──────────────┬────────────────┘
                               │              │              │
            ┌──────────────────▼───┐   ┌──────▼──────┐   ┌───▼─────────────────────┐
            │ :framework  (JVM)    │   │ :data       │   │ :device                 │
            │ agent manager,       │   │ (Android)   │   │ (Android)               │
            │ registry services,   │   │ Room, Data- │   │ PackageManager scan,    │
            │ model gateway, agent │   │ Store, key- │   │ hand-off intents,       │
            │ runner, tools +      │   │ store vault │   │ device report, phone    │
            │ permissions, message │   └──────┬──────┘   │ operator tools,         │
            │ bus, tasks, Director │          │          │ accessibility service   │
            └───┬─────────┬────────┘          │          └───┬─────────────────────┘
                │         │                   │              │
        ┌───────▼────────────┐ ┌───▼───────┐  │              │
        │ :providers (JVM)   │ │ :memory   │  │              │
        │ OpenAI-compatible, │ │ (JVM)     │  │              │
        │ Ollama, Gemini     │ │ memory    │  │              │
        │ :providers-        │ │ engine    │  │              │
        │ anthropic (SDK)    │ │ (Phase 3) │  │              │
        └───────┬────────────┘ └───┬───────┘  │              │
                │         │                   │              │
            ┌───▼─────────▼───────────────────▼──────────────▼───┐
            │ :core  (JVM)  domain models and ports (interfaces)  │
            └─────────────────────────────────────────────────────┘
              :testkit (JVM): in-memory implementations of every port,
              fakes and contract test suites; used by all modules' tests
```

Rules:

- `:core` depends on nothing but coroutines and kotlinx.serialization. It holds every domain
  type and every interface that crosses a module boundary.
- The JVM modules (`:core`, `:framework`, `:providers`, `:providers-anthropic`, `:memory`,
  `:testkit`) contain no Android code and are tested with plain JUnit. Most of the product's
  logic lives there. The only vendor SDK (Anthropic's) is confined to `:providers-anthropic`;
  every other adapter speaks HTTP directly.
- `:data` and `:device` implement `:core` ports with Android APIs. They do not depend on
  each other or on `:framework`.
- `:app` is the only module that knows all the others. `AppGraph` builds the object graph
  from a `Ports` value, so tests run the real framework over in-memory ports.
- Every persistence port has a contract suite in `:testkit`. The in-memory and the Room
  implementations both pass it, which keeps tests on fakes honest.

## 3. The five systems

### A. AI registry and discovery

| Concept | Type (`core.registry`) | Notes |
|---|---|---|
| AI application | `AiApp` | Package, label, how it was found, detected capabilities, status, per-app accessibility authorisation, optional link to an API provider |
| Provider | `ModelProvider` | Kind (protocol), base URL, key alias, `Locality`, connection status |
| Model | `ModelDescriptor` | Provider, wire name, capabilities (tools, vision, tiers, context), source, status |
| Agent | `AgentProfile` | See system B |

- **Discovery** (`AiAppScanner`, implemented in `:device`) reads the device through
  `PackageManager` only: a catalog of known AI packages, apps that answer assistant intents,
  and a conservative name heuristic marked "unverified". Manual registration picks from the
  installed launchable apps.
- **What may be done with an app** is `AiApp.controlChannels()`: `LAUNCH`,
  `INTENT_HANDOFF` (share intent or prompt link; the reply stays in that app),
  `ROUND_TRIP_TEXT`, `ACCESSIBILITY` (explicit per-app authorisation) and `LINKED_API`
  (use the vendor's API through a separately registered provider). `AiAppLauncher` enforces
  it.
- **Status tracking.** `AiAppRegistry.discover()` merges a scan into the registry, keeps
  your decisions, marks removed packages `NOT_INSTALLED` and withdraws their accessibility
  authorisation. A package-change receiver triggers a re-check while the app runs.
  Providers are health-checked; models are listed from the provider.
- **Availability** of an agent is derived, never stored: `resolveAvailability()` returns
  `Ready(model)`, `Disabled`, or `Unavailable(reason)` with the exact cause (no model, model
  or provider missing/disabled/offline, credentials rejected, blocked by local-only mode),
  trying fallback models in order.
- **`RegistryDirectory`** exposes one live `RegistrySnapshot` of everything; the dashboard
  and every picker read from it.

### B. Agent framework

`AgentProfile` is pure configuration: identity, role (`DIRECTOR`, `SPECIALIST`, `REVIEWER`,
`OPERATOR`), instructions, personality, responsibilities, skills, model and fallbacks,
generation settings, enabled tools, memory policy, delegation policy. Runtime state
(`AgentRuntimeState`), track record (`AgentStats`, including a smoothed reliability score)
and permission grants are stored separately, so duplicating an agent copies configuration
and nothing else.

`AgentManager` creates (from templates or blank), validates, updates, duplicates, enables,
assigns models, designates the Director and deletes. It seeds a starter team once.

Execution (`:framework`):

```
task ─► AgentRunner
          1. build context: persona + role protocol + tool list, recalled memories, task brief
          2. ModelGateway.chat(agent's model)          ← provider adapter
          3. tool calls? ─► ToolExecutor ─► PermissionBroker ─► Tool.execute ─► results appended
          4. repeat until a final answer, or a budget/time-out ends the run
          5. persist transcript step by step; update stats; report
```

Models without native function calling use a prompted tool protocol with tolerant parsing,
so small local models can still use tools.

### C. Multi-agent communication

- **Messages.** `AgentMessage` is a typed envelope (assignment, assistance request and
  response, question, answer, delegation, findings, memory share, review request and result,
  completion, failure, status, cancel) with a typed sender and recipient (owner, agent or
  system), objective, task, reply-to and hop count. Messages are append-only and stored per
  objective.
- **Tasks.** A `TaskRecord` is an objective, a work task, a review or the synthesis. It
  has instructions, completion criteria, an assignee, dependencies, a delegation chain,
  attempts and revision rounds, usage, a result or error, and review verdicts. States:

```
QUEUED ─► RUNNING ─► COMPLETED
            │  ▲
            │  └── WAITING_FOR_AGENT (asked or delegated to a teammate)
            ├─► IN_REVIEW ─► (approved) COMPLETED │ (revise) RUNNING, next round
            ├─► FAILED │ TIMED_OUT │ CANCELLED
            └─► PAUSED (by the owner) │ INTERRUPTED (process died; recovered on next start)
```

- **Director.** `DefaultOrchestrator` runs each objective in its own supervised job. Given an
  objective the Director (1) interprets it, (2) returns a JSON plan of tasks with criteria
  and dependencies, which is validated and retried once with the problems quoted, (3) has
  `AgentSelector` pick an agent per task by skills, word overlap, reliability and current
  load, (4) distributes assignments, (5) collects results, (6) has results it marked for
  review checked by a different agent, (7) sends revisions back, and when reviewer and author
  keep disagreeing decides itself in an arbitration task, (8) writes the final Markdown
  document, falling back to assembling the results mechanically, and (9) saves a summary to
  a shared space when the objective allows it.
- **Single source of truth.** The task store drives everything. Each step re-reads the
  stored state, checks under a per-objective lock that the task still has the status it
  expects, and writes before acting. Pause, resume and crash recovery therefore run the same
  `ObjectiveDriver` code. Work done by agents is always passed to other agents' prompts as
  quoted, framed data with a size budget, never as instructions.
- **Teamwork inside a task.** `team.ask_agent` runs a bounded question to a teammate;
  `team.delegate` creates a child task and waits for it. Both are offered according to each
  agent's delegation policy.
- **Loop protection.** Delegation depth; no delegating to an agent already in the chain;
  refusal of the third near-identical request to the same agent; per-objective caps on tasks
  and messages; per-task caps on model calls (questions to teammates included), tool calls,
  attempts and revision rounds; wall-clock limits per task and per objective; a bound on
  restarts that make no progress. A refusal reaches the model as a tool result and the owner
  as a "Limit reached" message. When an objective-wide limit stops work, finished results are
  kept and the final document says what was not completed and why.

### D. Phone operator

Tools are plug-ins: a `ToolSpec` (id, JSON-schema parameters, required capability, declared
risk, whether it sends data off the device) plus an `execute` function. A tool that needs to
show you the exact action first returns a `PreparedAction`: a summary, the details, the risk
assessed for this call, and a check that says whether the thing it described has changed.

| Family | Tools | Mechanism | Needs |
|---|---|---|---|
| Workspace | list, read, write, append, replace text, make folder, move, trash, restore, empty trash, search, export | Files in app-private storage, path-sandboxed, with a trash | Nothing |
| Shared folders | list, read, write, make folder, rename, copy in and out, delete | Storage Access Framework tree grants, one per folder | You pick each folder |
| Photos and videos | list, copy to workspace | MediaStore | Media permission, or selected photos on Android 14+ |
| Apps and links | list, open, open link, share text, compose email and SMS, dial, install, uninstall | Intents; `PackageInstaller` sessions | "Install unknown apps" for installs |
| Web | read page, open in browser | OkHttp GET with an address policy; Custom Tabs | Internet |
| Screen | read, tap, type, scroll, back, home, wait for, screenshot | Accessibility service on apps you authorise | You enable the service in Android settings; per-app authorisation |
| Scripts | run JavaScript, run shell, run in Termux | Rhino interpreter with everything Java shut out; `/system/bin/sh` in the workspace; Termux `RUN_COMMAND` | Termux installed and configured for the last one |
| Permissions | request | Raises a request to you | Nothing |

`PermissionBroker` implements the `ToolAuthorizer` port and decides every call, in this order:

1. the agent is switched on and the tool is switched on for it;
2. the master switches (phone operator; screen control for screen tools) and local-only mode
   allow it;
3. the agent holds a grant for the tool's capability. A grant names the agent, the capability,
   a constraint (one folder or one app, where the capability has one) and a scope: once (bound
   to the request that asked), this task, or always. Without one a `PERMISSION` request is
   raised and the call waits for you;
4. if the risk of this call (the higher of declared and assessed) is consequential, an
   `APPROVAL` request with the preview is raised every time, whatever was granted. It can only
   be approved or denied, never "always". After you answer, the tool checks that what it showed
   you is still what it would do; if not, nothing runs;
5. request, decision and result are logged with the request id.

Rules that hold around this:

- **Only you decide.** Requests are answered through owner-only ports (`OwnerRequests`,
  `OwnerGrants`, `OwnerFolders`, `OwnerOperatorApps`, `ScreenControl`) that only the UI holds. An
  architecture test walks everything reachable from agent-facing code and fails if it can reach
  one of them or any store that could change access. A request is decided at most once
  (compare-and-set), and the grant is written in the same transaction.
- **A request lives as long as the call that waits for it.** If the app, the chat or the task
  stopped, the request is closed and cannot be answered. Withdrawing an app, removing a folder,
  revoking a permission or pressing Stop closes the related requests.
- **Waiting for you costs the agent no time.** Limits on the tool, the task and the objective
  are paused while a request waits, and the pause survives a crash.
- **Screen control.** Each authorised app has a policy: ask before every action (the default),
  or ask only before buying, sending, deleting and similar, recognised from the words on the
  control, its row and its window. A consequential action shows the window's text; a digest of
  the window and the control is checked again before the action runs. A banner "Agent is
  operating <app> · Stop" is shown the whole time. The service never operates this app, Android
  settings, the permission controller, the package installer, system dialogs, the lock screen
  or password fields, and is not declared an accessibility tool.
- **Files.** Shared storage is reached only through folders you pick; the app does not ask for
  all-files access. A replaced workspace file goes to the trash; replacing or deleting in a
  shared folder, emptying the trash, and changing many files in a short time are consequential.
  The trash writes its record of an item before the item moves, so a kill leaves a file in its
  place or in the trash, and what a kill left behind is put right at the first change after a
  start (`TrashItem.recovered`, `Workspace.trashLeftovers`). Trash records count against the
  workspace's size limit, and a little room is kept free for them.
- **Scripts.** The JavaScript sandbox has a budget of instructions, time and memory, and its
  host functions reach the workspace only. A shell command is put to you every time. It runs
  only if nothing changed the workspace through the app since you were asked (a change counter
  that every change raises), and while it runs it holds the workspace's change lock, so every
  other change waits and one command runs at a time (`Workspace.runCommand`). Everything it
  starts is stopped when it ends: its process group is killed, then every process left under
  the app's user id that is not the app; the same sweep runs at start-up. Its `HOME` is an empty
  folder outside the workspace, so a shell it starts reads no start-up file from the workspace
  unless the command itself points `HOME` or `ENV` there, which its text shows. Only for the
  plain form `sh file [arguments]` (`ShellPlainForm`, an allow-list) does the approval name the
  script that runs. For every other command, and inside a script, finding, showing and pinning
  scripts is help on a best-effort basis: what the reader finds is shown as its reading
  ("Seems to run …"), and it often, not always, refuses a command it cannot read well before
  you are asked. A command asked for while another one runs is refused without asking you.
- **Data leaving the phone.** Tools that can send data out are marked in the catalogue, refused
  in local-only mode, and write an egress entry naming the agent, task and objective.

### E. Memory

- `MemoryRecord`: content, kind, tier (short-term or long-term), tags, importance,
  timestamps, access counters, expiry, revision, **provenance** (who or what first wrote it,
  from which task, conversation, model and sources) and an append-only **history** of edits,
  shares and promotions. Copies keep the original's provenance; the copy is recorded in the
  history.
- **Scopes**: `Agent(id)` private memory, `Shared(space)` knowledge spaces, `Task(id)`
  working memory. An agent reads and writes only the scopes its `MemoryPolicy` allows;
  sharing a memory is an explicit, logged operation that copies it into a space with a link
  back to the original.
- **Links**: typed, weighted edges between memories (relates to, supports, contradicts,
  derived from, part of, references, supersedes, shared copy of). Together with the records
  they form the memory graph.
- **Retrieval**: hybrid ranking of semantic similarity (pluggable `Embedder`; an offline
  feature-hashing embedder ships by default, provider and on-device embedding models can
  replace it), keyword match, recency and importance. Reads are bounded by count and by total
  text, and scoring runs off the caller's thread so a time-out can stop it.
- **Use by agents**: tools to search, save and share memory, and automatic recall before a
  reply. Recalled memories go into the request as a quoted data block with a per-turn marker,
  never into the system prompt, so stored text cannot pose as instructions. Conversations
  store references to memory search results, not their text, and render them from current
  memory on every read: a forgotten memory, or one whose space was revoked, is not sent again.
- **Lifecycle**: task memory expires; on completion the valuable parts are promoted to
  long-term memory when the task allows saving knowledge.
- **Graph bridge**: the engine exports a neutral `MemoryGraphSnapshot` (nodes, edges,
  weights, clusters, timestamps, provenance) and accepts external consumers through a
  `MemoryGraphBridge` interface, so an existing visual memory graph can be attached without
  touching the engine. The app also ships its own graph view.

## 4. Model compatibility

`:core` defines provider-neutral chat types (messages, tool definitions and calls, usage,
finish reasons, an error taxonomy) and `LlmClient`. `:providers` supplies adapters:

| Adapter | Covers |
|---|---|
| OpenAI-compatible | OpenAI and the many services and local servers that speak the same protocol (OpenRouter, Groq, Mistral, DeepSeek, xAI, LM Studio, llama.cpp server, vLLM, Ollama's `/v1`) |
| Anthropic | Claude models |
| Gemini | Google's native Gemini API |
| Ollama | Ollama's native API, with model capability listing |
| On-device | Model files run in-process on the phone |
| Simulator | A built-in deterministic stand-in so the whole pipeline can be exercised with no key and no network; clearly labelled as not a real model |

`Locality` (on device / local network / cloud) is inferred from the provider's address and
errs towards "cloud". Provider-specific data that must be echoed back unchanged (reasoning
blocks, signatures, tool-call ids) travels in an opaque field on messages.

## 5. Interface

Eight sections: Dashboard, Agents, Models, Tasks, Memory, Tools, Activity, Settings. On
phones a bottom bar holds Dashboard, Agents, Tasks, Activity and More (all eight); wide
screens use a navigation rail. Each screen is a stateless composable driven by a view model
that depends only on `:core` interfaces. The dashboard shows a live agent network: nodes are
agents, edges are their connections, and messages travel along them while tasks run.

## 6. Security and reliability

| Concern | Mechanism |
|---|---|
| Storage | Room database in app-private storage, encrypted with SQLCipher; the database key is random and wrapped by a hardware-backed keystore key. Cloud backup is disabled |
| API keys | `SecretVault`: AES-256-GCM, non-exportable AndroidKeyStore key, per-secret IV, alias bound as associated data. Other code holds aliases only |
| Agent permissions | Per-agent tool enablement plus grants per capability, with a scope (once, this task, always) and, for folders and apps, a single target; revocable; every request, decision and result logged. Only owner-held ports can decide a request or change access, and an architecture test fails if agent-facing code can reach one |
| Consequential actions | Confirmed by you every time, with a preview of the exact action; what was shown is checked again before it runs, and it does not run if it changed |
| Data leaving the device | `Locality` on every provider and model; egress recorded as `EgressInfo` on activity events; a local-only mode that blocks every off-device provider and every tool that can send data out, including handing text or files to another app and acting on another app's screen |
| Execution logs | Append-only `ActivityLog` with filters by type, agent, task, objective, severity and egress |
| Crash and interruption | Task state and transcripts are persisted step by step; tasks found running at start-up are marked interrupted and resumed or held, as configured; an interrupted run counts as an attempt, so a step that keeps crashing is not retried forever; objectives run in a foreground service with a Pause control |
| Untrusted content | Tool results, web pages, app screens and other agents' messages are passed to models as data, and cannot raise an agent's permissions |

## 7. Testing

- JVM unit tests for all logic in `:core`, `:framework`, `:providers`, `:memory`.
- Contract suites run against both in-memory and Room implementations.
- Provider adapters are tested against a local mock HTTP server with recorded protocol
  shapes, including streaming, tool calls and error mapping.
- Android unit tests under Robolectric for `:data`, `:device` and `:app` view models and UI
  journeys; screenshot tests for every screen.
- A software-emulated Android device is used for smoke tests of what Robolectric cannot
  cover (keystore, database encryption, app start-up). It is slow, so this set is small.
- Each phase ends with an adversarial review by independent reviewers, fixes, and a separate
  verification that re-runs every reproduction; architecture tests keep owner-only ports out of
  reach of agent-facing code.
- Not testable in the build environment, and stated as such in STATUS.md: behaviour on your
  specific phone, real provider accounts, the accessibility service against real third-party
  apps, on-device model performance.

## 8. Phases

| Phase | Scope |
|---|---|
| 1 | Project, core contracts, registry of AI apps and agents, agent creation, dashboard, activity log, settings |
| 2 | Model providers and models, secure keys, agent execution with tools, direct chat with an agent |
| 3 | Private and shared memory, semantic search, links, memory tools, memory explorer and graph |
| 4 | Tasks, message bus, Director, review and revision, loop protection, live monitoring and interruption |
| 5 | Permission broker UI, phone operator tools, file management, accessibility integration |
| 6 | Recovery, background execution, performance monitoring, collaboration refinements, live network view |
