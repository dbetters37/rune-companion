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
  response, findings, delegation, memory share, review request and result, completion,
  failure, question, answer, cancel) with sender, recipient, task, thread, reply-to and hop
  count. Every message is persisted and shown in Activity.
- **Tasks.** A `Task` has instructions, completion criteria, an assignee, dependencies,
  limits, usage, a delegation chain, a result or error, and review verdicts. States:

```
QUEUED ─► RUNNING ─► COMPLETED
            │  ▲         ▲
            │  └─ WAITING_APPROVAL / WAITING_FOR_AGENT ─┘
            ├─► IN_REVIEW ─► (approved) COMPLETED │ (revise) RUNNING, next round
            ├─► FAILED │ TIMED_OUT │ CANCELLED
            └─► PAUSED (by the owner) │ INTERRUPTED (process died; recovered on next start)
```

- **Director.** Given an objective it (1) interprets it, (2) produces a plan of tasks with
  criteria and dependencies, (3) selects an agent per task by skills, availability and
  reliability, (4) distributes assignments, (5) collects results, (6) has important results
  reviewed by a different agent, (7) sends revisions back or arbitrates disagreements,
  (8) synthesises the final result and saves it as a document, (9) saves knowledge to memory
  when the objective authorises it.
- **Loop protection.** Delegation depth limit; no delegating to an agent already in the
  delegation chain; per-objective caps on tasks and messages; per-task caps on model calls,
  tool calls, attempts and revision rounds; repeated-exchange detection between two agents;
  wall-clock time-outs per task and per objective. Hitting a limit fails the task with a
  clear reason instead of continuing.

### D. Phone operator

Tools are plug-ins: a `ToolSpec` (name, JSON-schema parameters, required capability, risk,
whether it sends data off the device) plus an `execute` function. Families:

| Family | Mechanism | Needs |
|---|---|---|
| Project workspace | Files in app-private storage, path-sandboxed | Nothing |
| Files and folders | Storage Access Framework folder grants; optional all-files access | You pick a folder, or toggle all-files access in Settings |
| Documents and text | Read/create/edit text formats; export documents | As files |
| Photos and media | MediaStore | Media permission (or selected photos) |
| Installed apps | Launch, share, deep links | Nothing beyond package visibility |
| Browser | Open URLs; an in-app WebView the agent can script | Internet |
| App navigation | Accessibility service: read screen, tap, type, scroll, global actions | You enable the service in Android settings; per-app authorisation |
| Scripts | Embedded JavaScript sandbox; shell in the app sandbox; Termux `RUN_COMMAND` | Termux installed and configured for the last one |

`PermissionBroker` decides every call:

1. the tool must be enabled for the agent;
2. master switches and local-only mode must allow it;
3. the agent needs a grant for the tool's capability; without one a permission request is
   raised and the task waits for you (approve once / for this task / always, or deny);
4. if the action is consequential, an approval request with a preview of the exact action
   is raised every time, regardless of grants;
5. request, decision and result are logged.

Grants can be revoked at any time. Agents can ask for a capability and explain why; they
cannot grant themselves anything, and the accessibility service refuses to operate system
settings, the permission controller and the package installer.

### E. Memory

- `MemoryRecord`: content, kind, tier (short-term or long-term), tags, importance,
  timestamps, access counters, expiry, revision, and **provenance** (who or what created it,
  from which task, model and sources, with what confidence).
- **Scopes**: `Agent(id)` private memory, `Shared(space)` knowledge spaces, `Task(id)`
  working memory. An agent reads and writes only the scopes its `MemoryPolicy` allows;
  sharing a memory is an explicit, logged operation that copies it into a space with a link
  back to the original.
- **Links**: typed, weighted edges between memories (relates to, supports, contradicts,
  derived from, part of, references, supersedes, shared copy of). Together with the records
  they form the memory graph.
- **Retrieval**: hybrid ranking of semantic similarity (pluggable `Embedder`; an offline
  feature-hashing embedder ships by default, provider and on-device embedding models can
  replace it), keyword match, recency and importance.
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
| Agent permissions | Per-agent tool enablement plus per-capability grants with scope; revocable; all decisions logged |
| Consequential actions | Always confirmed by you, with a preview |
| Data leaving the device | `Locality` on every provider and model; egress recorded as `EgressInfo` on activity events; a local-only mode that blocks every off-device provider and tool |
| Execution logs | Append-only `ActivityLog` with filters by type, agent, task, objective, severity and egress |
| Crash and interruption | Task state and transcripts are persisted step by step; tasks found running at start-up are marked interrupted and resumed or held, as configured; long runs execute in a foreground service with a stop control |
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
