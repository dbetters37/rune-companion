# Phase 6 reader: live network view and collaboration refinements

Repository read at /home/user/rune-companion, branch claude/ai-control-center-architecture-wjdfww,
HEAD d7315fd (0.5.1). Nothing in the repository was changed and no build was run. File paths below
are relative to the repository root unless absolute. "Verified" means read in the code (file:line
given) or in an official page (URL given). "Assumption" means not checked and has to be confirmed
while building.

Short names used below: `dash/` = `app/src/main/java/com/aicontrolcenter/app/ui/dashboard/`,
`orch/` = `framework/src/main/kotlin/com/aicontrolcenter/framework/orchestration/`,
`op/` = `framework/src/main/kotlin/com/aicontrolcenter/framework/operator/`,
`coretasks/` = `core/src/main/kotlin/com/aicontrolcenter/core/tasks/`.

---------------------------------------------------------------------------------------------------

## 1. The agent network as it is today (Phase 1, pulses since Phase 4)

### 1.1 Files

| File | What it holds |
|---|---|
| `dash/network/AgentNetwork.kt` | Pure data, no Compose types: `NetworkNode` (19-29), `EdgeKind` REPORTS / EXCHANGE (31-37), `NetworkEdge` (40-46), `NetworkPulse` (54-60), `AgentTraffic` (63), `AgentNetwork` (65-73), the mapping `RegistrySnapshot.toAgentNetwork(working, traffic)` (85-136), `MAX_MESSAGE_PULSES = 4` (139) |
| `dash/network/NetworkLayout.kt` | `NetworkLayout.arrange()` (17-55): Director at (0,0), the rest on one ring from the top clockwise in registry order; more than `MAX_SINGLE_RING = 10` (19) spill into an inner ring at radius 0.56. `Viewport` (62-91): scale 1..2.5 (`MAX_SCALE` 88), pan clamped so nothing is lost |
| `dash/network/NetworkGestures.kt` | `detectNetworkTransform` (21-51): pinch always; one-finger drag claimed only when zoomed (43), so the dashboard page keeps scrolling |
| `dash/network/AgentNetworkView.kt` | The composable `AgentNetworkView` (79-187): a `Canvas` for backdrop, lines, curves and dots (136-172), real composables for the nodes (`NetworkNodeView`, 189-253) laid over it, zoom and pan through one `graphicsLayer` (129-134) |
| `dash/NetworkCard.kt` | `NetworkCard` (67-124): title row with Zoom out / Zoom in / Reset buttons (89-101, the accessible alternative to pinching), the view at a fixed `height(320.dp)` (117), a legend (127-152), a "selected agent" panel with an Open button (166-204). `Viewport` survives rotation through `rememberSaveable` (76) |
| `dash/DashboardViewModel.kt` | Builds `DashboardUiState.network` (101-132, the network at 108) from five flows; the orchestration part is 81-97; `selectNode()` 170-172; `RECENT_MESSAGE_LIMIT = 60` (232) |
| `dash/DashboardScreen.kt` | Places `NetworkCard` (128-136) in a `verticalScroll` `Column` on phones (166-177) and in the right column at 700 dp and wider (144-164) |

There is no other network view: no route, no full-screen version, nothing in the objective screens.
The Memory section's graph (`app/.../ui/memory/graph/MemoryGraphCanvas.kt`) is a separate canvas for
memories and is the in-repo model for an accessible canvas (see 1.5).

### 1.2 Where the data comes from (verified)

- **Nodes**: every agent of the live `RegistrySnapshot` (`RegistryDirectory.observe()`,
  DashboardViewModel.kt:102). Status is the agent's availability, or "Working" with the monitor's
  step text when the agent is in `AgentActivityMonitor.states` (AgentNetwork.kt:90-103).
- **Edges**: REPORTS from every agent to the designated Director, solid when both can run, dashed
  otherwise (AgentNetwork.kt:108-114); EXCHANGE, a curve, for every pair of agents that exchanged a
  message in a running objective, except pairs that include the Director (115-121).
- **Traffic**: `MessageRepository.observeRecent(60)` across all objectives, kept only for
  objectives in `Orchestrator.running`, and only messages whose sender and recipient are both agents
  (DashboardViewModel.kt:87-97). Messages from or to the owner (`Participant.Owner`) or the system
  (`Participant.System`) are dropped (91-92).
- **Pulses**: the newest 4 agent-to-agent messages, spaced by their index, not by time:
  `progress = (0.15 + index * 0.22) % 1` (AgentNetwork.kt:122-130); plus one pulse towards the
  Director for every working agent that is not already sending one (131-134), which covers chats.
- **Tasks**: `TaskRepository.observeTree()` for every running objective (DashboardViewModel.kt:84-86)
  feed the "Working now" list, not the network.

### 1.3 How it is drawn and how it moves (verified)

- Node positions are remembered per list of node ids and hub id (AgentNetworkView.kt:88), so an
  agent keeps its place when unrelated data changes.
- The dots move by one `rememberInfiniteTransition` with a 1.6 s linear loop (96-107), created only
  while `network.pulses` is non-empty. Each dot is drawn at `(pulse.progress + phase) % 1` along a
  straight line or along the quadratic curve of an exchange (158-171).
- Lines stop short of the avatars (`drawEdge`, 284-300); exchanges are drawn as a 24-step `Path`
  built in the draw lambda (`drawExchange`, 306-320).
- Interaction: tap a node to select it (179) and tap empty space to clear (113); pinch to zoom,
  drag when zoomed (114-123); buttons zoom in, out and reset (NetworkCard.kt:89-101); the selected
  agent's card has Open (201). Tapping the selected node again clears it (DashboardViewModel.kt:170-172).
  Edges and dots cannot be tapped.

### 1.4 Tests today (verified)

| Test | What it checks |
|---|---|
| `app/src/test/.../ui/dashboard/NetworkLayoutTest.kt` (4 tests, 26/36/49/56) | Hub centred, first satellite straight above; unique places with two rings; no hub; viewport limits |
| `app/src/test/.../ui/dashboard/NetworkTrafficTest.kt` (3 tests, 34/53/63) | Exchange edges, newest-4 pulses with the sender's colour, chat pulses to the Director, edges without a Director |
| `app/src/test/.../ui/dashboard/DashboardViewModelTest.kt` 220, 349 | Director in the centre with reporting lines; working agents pulse |
| `app/src/test/.../ui/dashboard/DashboardOrchestrationTest.kt` 26-59 | Real orchestrator with a scripted team: the Director's assignment pulse travels to the working agent; no pulses after the objective ends |
| `app/src/test/.../screenshots/ScreenshotTest.kt` 50-86 | Roborazzi pictures: dashboard dark, light, wide, with models, empty; a node selected by its spoken description (`tapIcon("Researcher, Specialist, No model yet")`, 55) |
| `app/src/test/.../screenshots/TasksScreenshotTest.kt` 176-183 | Dashboard while the team works (`p4_dashboard_2_network`) |

Screenshots are review material, "not golden files that fail a build" (ScreenshotTest.kt:21-23), and
show the dots at rest because Compose tests stop infinite animations (AgentNetworkView.kt:95). No
test renders a dot at a chosen moment, checks TalkBack order, or checks behaviour with animations
switched off.

### 1.5 Accessibility today (verified)

- Each node is a real composable with `Role.Button`, an "Select" click label, a merged
  `contentDescription` "Name, Role[, designated Director], Status[: step]" and `selected`
  (AgentNetworkView.kt:198-217). Touch target: 88 dp wide (64), avatar 46-60 dp.
- The canvas has no semantics: lines, curves and dots do not exist for TalkBack. The legend is text
  (NetworkCard.kt:140-150); legend chips are spoken as "Legend: Working" (157).
- No `isTraversalGroup` / `traversalIndex`: TalkBack reads nodes in the default top-to-bottom,
  left-to-right order, which on a ring puts the Director somewhere in the middle. Compose's own
  guidance for circular layouts is to set `isTraversalGroup` on the container and `traversalIndex`
  on each item ([Compose traversal order](https://developer.android.com/develop/ui/compose/accessibility/traversal),
  current Compose, all Android versions the app supports).
- No reduced-motion handling anywhere in the repository (grep for `ANIMATOR_DURATION_SCALE`,
  `areAnimatorsEnabled`, `MotionDurationScale`: no hits).
- The memory graph already shows a better pattern for a canvas: one summary `contentDescription` and
  `CustomAccessibilityAction`s "Select <memory>" for the 40 heaviest nodes, plus a pointer to the
  list (`MemoryGraphCanvas.kt:96-108`, `MAX_ACCESSIBLE_NODES = 40` at 166).

### 1.6 Performance characteristics (verified unless marked)

- **The animated phase is read during composition.** `val pulsePhase = ... phase` is evaluated in
  the body of `AgentNetworkView` (AgentNetworkView.kt:96-107) and captured by the `BoxWithConstraints`
  content and the Canvas lambda. So `AgentNetworkView` recomposes on every animation frame while any
  dot exists. Google's guidance is to read fast-changing state in the draw phase (a lambda or
  `drawBehind`), which skips composition and layout
  ([Compose performance best practices, "Defer reads as long as possible"](https://developer.android.com/develop/ui/compose/performance/bestpractices)).
  Whether the 6-30 `NetworkNodeView` children also recompose each frame depends on skipping (strong
  skipping is the Compose compiler's default in Kotlin 2.x; the repository does not configure it,
  `grep composeCompiler` finds nothing). Assumption: they mostly skip; not measured.
- **It runs as long as any objective runs**, not only while a message travels: the newest four
  messages of a running objective loop for ever (AgentNetwork.kt:122-130; the objective filter is
  DashboardViewModel.kt:90). The phone `Column` keeps the card composed when it is scrolled out of
  sight (DashboardScreen.kt:166-177), so the animation also runs then, as long as the dashboard is
  the screen in front (Compose's frame clock stops when the activity is not resumed).
- **A new Path per curve per frame** (`drawExchange`, AgentNetworkView.kt:312-319).
- **Heavy inputs for a light picture.** `observeRecent(60)` is `SELECT * FROM agent_messages ORDER
  BY at DESC, id DESC LIMIT :limit` (`data/.../db/dao/AgentMessageDao.kt:42`): every insert into
  `agent_messages` re-reads 60 whole messages, bodies included (a body may hold up to 20,000
  characters, `orch/ObjectiveJournal.kt:181`). `observeTree()` re-reads whole tasks, results
  included, for each running objective (DashboardViewModel.kt:84-86). The network uses only the ids,
  sender, recipient and type.
- **Layout limits** (computed from the constants, not measured): on a 360 dp phone the card is about
  328 dp wide, so the ring's horizontal radius is (328 - 88) / 2 = 120 dp and its circumference
  about 760 dp. Ten satellites leave about 76 dp between centres, less than the 88 dp label width
  (AgentNetworkView.kt:64): from about 9 satellites the names can touch. The inner ring (radius
  0.56) is tighter.
- **The objective screens rebuild everything every second** while an objective runs: `ticks`
  (ObjectiveViewModel.kt:184, `everySecond()` 350) is combined with the whole task tree and all
  messages (181, 186-192). Not part of the network, but any network embedded there would inherit it.

### 1.7 Gaps and defects in the existing network

| # | Finding | Where |
|---|---|---|
| N1 | Per-frame recomposition (phase read in composition) | AgentNetworkView.kt:96-107 |
| N2 | Dots do not mean "a message on its way": the newest 4 loop for as long as the objective runs, spaced by index; the legend says "dots are messages on their way" | AgentNetwork.kt:122-130; NetworkCard.kt:142-144 |
| N3 | An agent waiting for a teammate or for the owner is shown as "Working" | AgentNetwork.kt:96-103 vs `AgentActivity.WAITING_FOR_APPROVAL/WAITING_FOR_AGENT` (core/.../agents/AgentProfile.kt:160-172), which the Working-now list does use (DashboardViewModel.kt:150) |
| N4 | The owner is not in the network; owner and system messages are dropped; memory shares are sent to `Participant.System` without the space (`orch/WorkSteps.kt:88-91`), although the share tool's outcome carries `spaceId` (`framework/.../memory/MemoryShareTool.kt:87-93`) | DashboardViewModel.kt:91-92 |
| N5 | Message kinds are not told apart: an assignment, a question, a delegation and a review all look like the same line or curve | AgentNetwork.kt:31-46 |
| N6 | Whole messages and whole tasks re-read on every change | AgentMessageDao.kt:42; DashboardViewModel.kt:84-87 |
| N7 | A Path allocated per curve per frame | AgentNetworkView.kt:312-319 |
| N8 | TalkBack: default order on a ring; nothing about connections; no list alternative | AgentNetworkView.kt:109-124, 204-217 |
| N9 | No reduced-motion handling; with animations off the dots freeze in place but are still drawn as if travelling (see 2.9 for the Compose behaviour) | AgentNetworkView.kt:96-107 |
| N10 | Fixed 320 dp height; labels collide from about 9 satellites on a 360 dp phone (computed) | NetworkCard.kt:117; NetworkLayout.kt:19 |
| N11 | Edges and dots cannot be tapped; the only detail is the agent's role and status | AgentNetworkView.kt:136-172; NetworkCard.kt:166-204 |

---------------------------------------------------------------------------------------------------

## 2. Proposal: the live team view

Owner-facing name: **"Team view"** (the owner does not need the word "network"). Plain words
throughout: "Waiting for you", "Asked a teammate", "Handed part of the work to", "Checking the
work of", "Shared a memory into", "You". The existing "You" label for the owner is reused
(`app/.../ui/tasks/TaskPresentation.kt:38-39`).

### 2.1 What it is for, for this owner

One full screen that answers, at a glance and without reading the conversation: who is working on
what right now, who is waiting for whom, who is waiting for **me**, what just happened, and how this
objective went (replay). It must stay calm (no constant motion), work with TalkBack and with
animations switched off, and stay smooth on a mid-range phone.

### 2.2 Entry points and navigation

- New route `@Serializable data class TeamRoute(val objectiveId: String? = null)` in
  `app/.../ui/shell/Destinations.kt` (next to `ObjectiveRoute`, 86), composed in `AppNavHost.kt`.
  `objectiveId = null` means "all running objectives".
- Dashboard: the network card keeps its compact picture and gets a "Team view" button in its title
  row (NetworkCard.kt:79-102). The card itself is fixed as in 2.11 (N1-N3, N7).
- Objective screen: a "Team view" button in the header (`app/.../ui/tasks/ObjectiveHeader.kt`) opens
  `TeamRoute(objectiveId)`. Not a fifth tab: the screen's tab row already scrolls
  (`ObjectiveScreen.kt:183`), and the view needs the whole screen.
- A node's sheet links on to the agent (`AgentDetailRoute`), its task (`TaskRoute`), and a waiting
  request (`ApprovalRoute`).

### 2.3 Model (pure Kotlin in `:app`, no Compose types, like AgentNetwork.kt)

New package `app/.../ui/team/`:

```kotlin
enum class TeamNodeKind { OWNER, DIRECTOR, AGENT, FORMER_AGENT, SPACE, TOOL_FAMILY }
enum class NodeState { IDLE, WORKING, WAITING_FOR_TEAMMATE, WAITING_FOR_YOU, BLOCKED, PAUSED, DONE, FAILED, CANNOT_RUN }
data class TeamNode(id, kind, name, colorIndex, state: NodeState, stateText: String,   // "Waiting for you: permission to read Documents"
                    taskId: TaskId?, taskTitle: String?, waitingRequestIds: List<RequestId>)
enum class LinkKind { ASSIGNS, ASKS, DELEGATES, REVIEWS, REPORTS, SHARES_MEMORY, ASKS_YOU, TELLS_YOU, USES_TOOL }
data class TeamLink(fromId, toId, kind, count: Int, lastAt: Long, messageIds: List<MessageId> /* newest 50 */)
data class TeamPulse(linkKey, fromId, toId, kind, bornAt: Long)                          // one per message, travels once
data class TeamGraph(nodes, links, pulses, objectives: List<ObjectiveChip>, overflow: Int /* agents not shown */)
```

Built by `TeamGraphBuilder.build(snapshot, tasks: List<TaskSummary>, headers: List<MessageHeader>,
requests: List<OwnerRequest>, monitor: Map<AgentId, AgentRuntimeState>, toolUse: List<ToolUse>, now)`:
a pure function, unit-tested on the JVM.

Mapping from what is stored (all verified as existing data):

| Stored | Becomes |
|---|---|
| `AgentMessage` ASSIGNMENT, CANCEL from the Director or the owner (`orch/WorkSteps.kt:65-67`, `orch/DefaultOrchestrator.kt:152, 243`) | ASSIGNS |
| ASSISTANCE_REQUEST / ASSISTANCE_RESPONSE, linked by `replyTo` (`orch/TeamDesk.kt:92, 110-114`) | ASKS (both directions on one link) |
| DELEGATION (`orch/TeamDesk.kt:157`), the child's COMPLETION / FAILURE back | DELEGATES |
| REVIEW_REQUEST (Director to reviewer, `orch/WorkSteps.kt:147-150`), REVIEW_RESULT (reviewer or Director to author, `orch/ReviewSteps.kt:145, 184`) | REVIEWS |
| FINDINGS, COMPLETION, FAILURE to the assigner (`orch/WorkSteps.kt:145, 155, 175`) | REPORTS |
| MEMORY_SHARE (`orch/WorkSteps.kt:90`) | SHARES_MEMORY, to the space node (needs D2) |
| Pending `OwnerRequest` with `rootId`/`taskId` (`core/.../operator/OwnerRequests.kt:51-100`) | ASKS_YOU from the agent to "You"; the agent's state WAITING_FOR_YOU |
| STATUS / FAILURE / COMPLETION to `Participant.Owner` (e.g. "Limit reached", `orch/ObjectiveJournal.kt:100-101`; final result `orch/ObjectiveDriver.kt:678`) | TELLS_YOU |
| Owner's STATUS (pause/resume, `DefaultOrchestrator.kt:175, 212`) | ASSIGNS from "You" to the Director, shown in the sheet as "You paused it" |
| `ActivityType.TOOL_CALL` events with `rootTaskId` and `data.toolId` (`framework/.../tools/ToolExecutor.kt:293-306`) | USES_TOOL to a tool-family node (Workspace, Folders, Photos, Apps, Web, Screen, Scripts), only when "Show tools" is on |

Node state per agent, in this order (first that applies): `CANNOT_RUN` (availability not Ready,
the reason in words); `WAITING_FOR_YOU` (a pending request of this agent in this objective, or its
task is `TaskStatus.WAITING_FOR_OWNER`, `coretasks/Tasks.kt:50`); `WAITING_FOR_TEAMMATE`
(`WAITING_FOR_AGENT`); `WORKING` (`RUNNING` or `IN_REVIEW` as reviewer, or in the monitor map);
`PAUSED`; `BLOCKED` (a QUEUED task whose `dependsOn` are not all completed: "Waiting for earlier
tasks"); `FAILED` / `DONE` for finished tasks; else `IDLE`. Every state has a glyph and a word, not
only a colour.

### 2.4 Data sources and the two storage changes

The view model (`TeamViewModel`, `app/.../ui/team/TeamViewModel.kt`) observes:

1. `Orchestrator.running` and, per shown objective, a **slim task projection** (D1) instead of whole
   tasks.
2. A **message header projection** (D1) instead of whole messages.
3. `OwnerRequests.pending` (the UI may hold it; `ObjectiveViewModel` already takes it as a flow,
   ObjectiveViewModel.kt:150) and, for replay, `OwnerRequests.observeRecent` filtered by `rootId`.
4. `RegistryDirectory.observe()` and `AgentActivityMonitor.states` (live step text).
5. Only with "Show tools": `ActivityLog.observe(ActivityFilter(types = {TOOL_CALL}, rootTaskId), limit = 500)`,
   aggregated per tool family. The activity log is pruned after `activityRetentionDays` (default 30,
   `core/.../settings/FrameworkSettings.kt:14`), so tools of old objectives may be missing; the view
   says so instead of showing nothing.

Storage changes (both additive, together in schema 6 with the collaboration changes of section 4):

- **D1 Projections, no schema change.** `MessageRepository.observeHeaders(rootId)` and
  `observeRecentHeaders(limit)` returning `MessageHeader(id, rootId, taskId, from, to, type, replyTo,
  hop, at)` (Room: a `SELECT id, root_id, task_id, from_kind, from_agent_id, to_kind, to_agent_id, type,
  reply_to, hop, at` query, served by the existing indexes `index_agent_messages_root_id_at_id` and
  `index_agent_messages_at_id`, `data/.../db/AiccMigrations.kt:124-125`).
  `TaskRepository.observeSummaries(rootId)` returning `TaskSummary` (id, rootId, parentId, kind,
  title, status, statusDetail, assignee, dependsOn, delegationChain, order, attempt, revisionRound,
  createdAt, startedAt, finishedAt, updatedAt) without instructions, result, reviews and usage.
  Contract suites in `:testkit` for both, run against the in-memory and the Room implementations as
  every port is. The dashboard switches to the same projections. This overlaps with the "faster
  objective screens" item of STATUS (STATUS.md:326-328); one implementation should serve both.
- **D2 The space of a memory share.** Either a new `Participant.Space(spaceId)` (stored as
  `to_kind = "space"` with a new nullable column `to_space_id`; `StoredNamesGoldenTest` in
  `data/src/test/.../StoredNamesGoldenTest.kt` extended), or, smaller, keep `to = System` and write
  the space id into the message body's first line in a fixed form. Recommendation: the participant,
  because the conversation tab, the log line ("Memory shared: Researcher → Team knowledge",
  `ObjectiveJournal.kt:65`) and the network all read better from it. `WorkSteps.kt:88-91` passes
  `execution.outcome.data["spaceId"]`. Old messages (to System) are drawn to one "Shared memory" node.

### 2.5 Layout

- Full-screen canvas, portrait first. In the layout's unit space (as `UnitPoint`, NetworkLayout.kt):
  "You" at the top centre outside the ring, the Director at the centre, agents on the ring
  (`NetworkLayout.arrange` reused, ring radius 0.8), spaces and tool families as small rounded squares
  on a band along the bottom. Positions stay fixed for an agent while the screen is open (keyed by
  id, as AgentNetworkView.kt:88), also while replaying.
- **Objective filter** chips above the canvas: "All running", then each running objective, then the
  five most recent finished ones. With one objective chosen, only its participants are placed
  (assignees of its tasks, senders and recipients of its messages, the owner, the Director); others are
  counted in a chip "4 other agents not in this objective". The full registry is never forced on the
  ring.
- Caps: at most 30 agent nodes placed (two rings; above that the view opens in the list mode of 2.8
  and says why), 6 space nodes, 7 tool-family nodes. At 360 dp and more than 8 agents, labels show
  initials plus name on selection (the overlap computed in 1.6, N10). A layout test asserts that no
  two avatar circles overlap for 1..30 agents at 360x640 dp and 600x960 dp.
- One-finger drag always pans (the full screen does not scroll), pinch and the three zoom buttons as
  today (`Viewport`, NetworkLayout.kt:62-91, scale limit raised to 3).

### 2.6 Live message flow

- **A dot travels once, when the message is sent**: `TeamPulse.bornAt = message.at`; position
  `t = (now - bornAt) / 1200 ms`, drawn while `0 <= t <= 1`, then gone. At most 8 dots in flight;
  later ones wait in a queue (start time pushed back by at most 2 s, then dropped and only counted).
  This replaces the looping newest-four (N2).
- **Afterglow instead of motion**: a link that carried a message in the last 60 s is drawn thicker,
  fading back over 60 s; its count badge ("3") shows how many messages it carried in this objective.
- **The clock runs only while something moves**: one `LaunchedEffect` loops on `withFrameNanos`
  while a dot is in flight or an afterglow fades, then stops. No `rememberInfiniteTransition`. The
  frame time is kept in a `State` read only inside the Canvas draw lambda (fixes N1).
- **Link styles by kind** (never colour alone): assignments solid, questions dashed, delegations
  double line, reviews dotted, reports thin; a 20 dp icon at the middle of each link uses the same
  icons as the Conversation tab (`app/.../ui/tasks/TaskLabels.kt:82-96`); arrowheads show direction,
  both ends when both directions were used.
- "Waiting for you" is the one emphasised state: the agent's ring pulses once when the request
  appears (static ring with motion off), a red-free "Needs you" badge, and the "You" node shows the
  number of open requests. Tapping either opens the request.

### 2.7 Details on tap

- **Node** (bottom sheet): name, role, state in words ("Waiting for you: approve deleting
  Reports/2024.csv"), the task and its step, which model it uses and where its text goes (the same
  wording as the chat's "where your text goes" line), its last 10 messages in this objective (type,
  other party, time, first line), and buttons: Open agent, Open task, Open the request, and, from
  section 4, "Write to <agent>".
- **Link**: tapping the middle icon (a real composable, 48 dp target; drawn for at most the 12 most
  recent links, or every link of the selected node) opens a sheet listing every message on that link
  in order, each expandable, rendered as plain text exactly as stored (as `MessageRow`,
  `app/.../ui/tasks/MessageViews.kt:58-129`, which is reused).
- **"You"**: the open requests of this objective, oldest first, and what the system told you
  (limits reached, final result ready).
- **Space**: its name, who shared into it in this objective, and, faintly, which agents may read it
  (from each agent's `MemoryPolicy.readableSpaces`, `core/.../agents/AgentProfile.kt:89-97`), with a
  link to the space's screen. The view shows the owner's sharing settings; it never offers to widen
  them.

### 2.8 Replay

- Available for any objective, running or finished: a "Replay" button opens a timeline bar under
  the canvas: Play/Pause, "Previous step" / "Next step", a slider, and the time and the step in
  words ("10:42 · Researcher asked Analyst a question"). Playback compresses the whole objective into
  about 40 s (steps at least 0.4 s apart), or steps one message at a time.
- **Reconstructed from what is stored, no new event table** (verified that every input exists):
  messages give the steps; `TaskSummary.startedAt/finishedAt` and the ASSIGNMENT message of each
  attempt or revision give working intervals; ASSISTANCE_REQUEST → its reply (`replyTo`) and
  DELEGATION → the child's end give "waiting for a teammate"; `OwnerRequest.createdAt → decidedAt`
  give "waiting for you" with the answer; STATUS pause/resume give paused intervals. The view says
  "Replayed from the record: the moments between messages are not shown." Precision is the message
  times; the steps inside a model call are not recorded and not invented.
- `TeamTimeline` (pure): events sorted once; state at time t by binary search over checkpoints taken
  every 50 events; 1,000 messages (the settings' maximum per objective,
  `app/.../ui/settings/OrchestrationLimit.kt:33-36`) plus 200 requests gives at most about 3,000
  events.

### 2.9 Accessibility

- **List alternative, same model**: a toggle "Picture / List" in the top bar. The list is a
  `LazyColumn` of the same `TeamGraph`: "You" first (open requests), then the Director, then agents
  by state (waiting for you, working, waiting for a teammate, blocked, done), each row with state in
  words, the task, and "Connections: asked Analyst 2 times, checked by Reviewer"; rows expand to the
  link messages. With TalkBack on (`AccessibilityManager.isTouchExplorationEnabled`) or font scale
  1.5 and above, the list is offered first (one line at the top of the picture: "A list of the same
  information is easier with TalkBack. Show list"); the choice is remembered.
- **Picture with TalkBack**: the canvas container gets `isTraversalGroup = true`; nodes get
  `traversalIndex` in reading order: You 0, Director 1, ring clockwise from the top, then spaces and
  tools (per the Compose clock-face example,
  [traversal](https://developer.android.com/develop/ui/compose/accessibility/traversal)). Node
  descriptions say the state and the links in words: "Researcher, specialist. Waiting for you:
  permission to read Documents. Asked Analyst 2 questions. Working on: Find note apps." Custom
  actions per node: "Show messages with <other agent>" for its links, "Open the request". Link icons
  are buttons with descriptions "Questions between Researcher and Analyst, 3 messages".
- **Announcements, not noise**: one polite live region on a summary line above the canvas ("3
  working, 1 waiting for you, 0 blocked"), updated at most every 10 s and only when the numbers
  change. Individual messages are never announced.
- **Timeline**: the slider exposes `progressBarRangeInfo` and `setProgress`, the step buttons have
  labels, and the current step text is the slider's state description.
- 48 dp minimum touch targets for nodes, link icons and controls; contrast checked in dark and light
  as earlier phases did.

### 2.10 Reduced motion

Verified platform facts:

- `ValueAnimator.areAnimatorsEnabled()` (API 26+, so every supported version) "Returns whether
  animators are currently enabled, system-wide ... This can change if either the user sets a
  Developer Option to set the animator duration scale to 0 or by Battery Savery mode being enabled"
  ([ValueAnimator](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled())).
  `ValueAnimator.getDurationScale()` and `registerDurationScaleChangeListener()` exist from API 33.
- Compose: "When the duration scaleFactor is 0, the motion will end in the next frame callback"
  ([MotionDurationScale](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale),
  Compose UI 1.2+). Its test hook is the `effectContext` parameter of the test rule (same page).
- For infinite transitions specifically: the bytecode of `InfiniteTransition$run$1$1` in
  animation-core-android 1.13.0 (the version in this machine's Gradle cache, read with `javap`)
  compares the context's duration scale with 0 and then suspends on
  `snapshotFlow { durationScale }.first { > 0 }`: the animation stops and waits. So today, with
  "Remove animations" or battery saver, the dashboard's dots freeze where they are but are still drawn
  (N9). Assumption to check: the "Remove animations" accessibility switch sets the animator duration
  scale to 0 on the owner's phone (community sources say it does; an official page saying so was not
  found).

Design:

- `MotionPreference` (app): `FOLLOW_PHONE` (default) or `CALM` from Settings > Appearance, combined
  with `areAnimatorsEnabled()` read on start and on resume (and the API 33 listener where available).
  The decision is passed into the composables as a parameter, so tests set it directly.
- With motion off: no travelling dots, no pulsing rings, no animated zoom. A message is shown by its
  link turning thick at once (afterglow drawn as a static "recent" style for 60 s) and the count
  badge incrementing; the replay steps from state to state without in-between frames.
- The dashboard card follows the same preference.

### 2.11 Performance limits and frame budget

Budget: a frame has 16 ms at 60 Hz, 11 ms at 90 Hz, 8 ms at 120 Hz; slow frames are 16-700 ms,
frozen frames over 700 ms ([Slow rendering](https://developer.android.com/topic/performance/vitals/render)).
Target for this view (assumption, to be measured on the owner's phone, not here): record and draw
under 3 ms per frame while dots move; no frozen frame when opening the screen or switching objective.

Rules that make this hold by construction:

- Only the draw phase runs per frame: the frame time is read inside the Canvas lambda; nodes are
  composables whose inputs change only when the graph changes; their position uses the lambda form
  of `offset` (as AgentNetworkView.kt:180-182 already does).
- Static geometry (link lines, curves, arrowheads) is built in `drawWithCache` keyed by the layout
  and the link set, not per frame (fixes N7); dots are drawn analytically along it.
- View-model work off the main thread (`flowOn(Dispatchers.Default)`), inputs `conflate()`d and
  sampled to at most 4 graph emissions per second while live; replay state computed on demand.
- Caps: 30 agents + 1 owner + 6 spaces + 7 tool families; 120 links; 8 dots in flight; message
  headers of shown objectives only (at most 1,000 each); tool events at most 500 per objective.
- The screen does not read message bodies until a link sheet opens (`MessageRepository.list` /
  `get` for those ids).
- Animation stops when nothing moves (2.6), and Compose's frame clock stops when the app is not in
  front, so a long objective in the background costs nothing here.
- Fix the dashboard card the same way (N1, N2, N3, N6, N7), keep it compact.

### 2.12 How it is tested

JVM unit tests (`app/src/test/.../ui/team/`):

- `TeamGraphBuilderTest`: every message type to its link kind; owner node and its requests; space
  node from `Participant.Space`, old shares to "Shared memory"; deleted agents shown as "A former team
  member" (as elsewhere, `orch/ReviewSteps.kt:229`); objective filter keeps only participants and
  counts the rest; caps (31 agents → overflow 1); state precedence of 2.3; plain-language state texts.
- `TeamTimelineTest`: intervals from the `ObjectiveWorld` sample (`app/src/test/.../testing/ObjectiveWorld.kt`)
  and from a scripted run; state at the exact boundaries; a request answered, refused and expired;
  pause/resume; 1,000 messages build in under 250 ms on the build machine (a tripwire against an
  accidental quadratic step, not a phone number).
- `PulseSchedulerTest` with a fake clock: one dot per message, born at its time, gone after 1.2 s; at
  most 8; queueing and dropping; none with motion off.
- `TeamLayoutTest`: no overlapping avatars for 1..30 agents at 360 and 600 dp; stable positions when
  an unrelated agent changes.

View-model tests (Robolectric, real framework over in-memory ports, as `DashboardOrchestrationTest`
with `TestApp` and `TeamModel`, `app/src/test/.../testing/TeamTesting.kt`): an objective whose task
raises a permission request shows the agent WAITING_FOR_YOU and an ASKS_YOU link that disappears
when answered; `team.ask_agent` gives an ASKS link both ways; a delegation; a review sends back a
revision; a memory share reaches the space; pause shows PAUSED; after the end, replay is offered and
no dot moves.

Compose UI tests (Robolectric): node descriptions contain the state in words; traversal indices in
the stated order; custom actions present and working; the list mode has the same counts as the
picture; the slider has range info and responds to `setProgress`; touch targets at least 48 dp; a
test with `MotionDurationScale` 0 in the rule's `effectContext` and one with `MotionPreference.CALM`
both draw no dot (exposed for tests through a semantics property "dots in flight").

Roborazzi pictures (review material, as before): dark, light, wide, 360 dp, font scale 1.5 and 2.0,
list mode, node sheet, link sheet, "Waiting for you", 30 agents, motion off, and replay at its start,
middle and end with `mainClock.autoAdvance = false` and `advanceTimeBy` so that dots are captured in
flight (the current pictures cannot, AgentNetworkView.kt:95).

Emulator: one smoke run only (open the view while the simulator runs an objective; open replay after
it ends). Frame timing on the software-emulated AVD says nothing about a phone and is not claimed.
Not testable here: smoothness on the owner's phone, TalkBack with a real voice.

---------------------------------------------------------------------------------------------------

## 3. Multi-agent collaboration as it is today

### 3.1 Messages (the "message bus")

There is no separate bus type: `ObjectiveJournal.send()` (`orch/ObjectiveJournal.kt:45-77`) stores
an `AgentMessage` (`coretasks/Messages.kt:55-71`) through `MessageRepository.append`
(`coretasks/TaskPorts.kt:33-41`) and writes one activity line with at most a first line of the body
(61-75). Bodies are capped at 20,000 characters (181). Messages are append-only and never rewritten
(`AgentMessageDao.insertIfAbsent`, `data/.../db/dao/AgentMessageDao.kt:16-21`).

Who sends what (verified):

| Type | From → to | Where |
|---|---|---|
| ASSIGNMENT | You → Director (the objective); Director → agent (each planned task, each attempt and revision) | DefaultOrchestrator.kt:152; WorkSteps.kt:65-67 |
| ASSISTANCE_REQUEST / _RESPONSE | agent → teammate, and back | TeamDesk.kt:92, 110-114 |
| DELEGATION | agent → teammate | TeamDesk.kt:157 |
| FINDINGS | author → assigner ("Result ready for review") | WorkSteps.kt:145 |
| REVIEW_REQUEST / REVIEW_RESULT | Director → reviewer; reviewer → author; Director → author after arbitration | WorkSteps.kt:147-150; ReviewSteps.kt:145, 184 |
| MEMORY_SHARE | agent → System | WorkSteps.kt:88-91 |
| COMPLETION / FAILURE | agent → assigner; Director → You at the end | WorkSteps.kt:155, 175; ObjectiveDriver.kt:678-681 |
| STATUS | Director → You (the plan); System → You ("Limit reached", knowledge); You → Director (pause, resume, run again) | PlanningStep.kt:132; ObjectiveJournal.kt:100-101; DefaultOrchestrator.kt:175, 212, 328 |
| CANCEL | You → Director or the task's agent | DefaultOrchestrator.kt:243, 273 |
| QUESTION / ANSWER | **defined but never sent** | Messages.kt:46-47; only labels use them (TaskLabels.kt:76-77, ObjectiveJournal.kt:173-174) |

### 3.2 Teamwork inside a task

- `team.ask_agent` and `team.delegate` (`orch/TeamTools.kt:58-140`), capability `team.communicate`
  granted by default (40-44) because the delegation policy, the limits and the loop guards decide
  (comment 31-32). Offered per agent by `DelegationPolicy` (`canRequestAssistance` default true,
  `canDelegate` default false, `maxDepth` 1; `core/.../agents/AgentProfile.kt:100-107`;
  `WorkSteps.teamTools`, WorkSteps.kt:189-192).
- Only the assignee of a RUNNING WORK task of a RUNNING objective may use them (`TeamDesk.caller`,
  TeamDesk.kt:226-233).
- Asking: the teammate answers in a bounded run of at most 4 model calls and 4 tool calls and 5
  minutes, within what the asking task has left, charged to it (TeamDesk.kt:80-108, 276-278); at most
  5 questions per task (281). The asking task is WAITING_FOR_AGENT meanwhile (251-262).
- Delegating: a stored child WORK task under the caller, run there with the caller's remaining time,
  settled when the caller stops waiting (TeamDesk.kt:119-223).
- What comes back is a tool result. The teammate's text is appended unframed:
  `"${target.name} answered:\n${answer.text}"` (TeamDesk.kt:111) and `"Its result:\n${final.result}"`
  (189). Tool results go to the model only as tool messages (`framework/.../agents/AgentRunner.kt:49-50`),
  but unlike every brief they are not in a `DataBlock` frame (`orch/DataBlock.kt:41-46`).

### 3.3 Reviews and arbitration

- A finished planned task marked `needsReview` (and the objective's `reviewResults` on) gets a
  REVIEW task created in the same transaction (`orch/WorkSteps.kt:114-157`).
- The reviewer is chosen by `AgentSelector.forReview` (`orch/AgentSelector.kt:61-71`): never the
  author; reviewers first, then anyone else, then the Director; score = skills (3 each) + task words
  (max 5) + reliability - 0.75 per running task (85-92); ties by name. It does not look at the model,
  at who helped the author (teammates who answered or took delegated parts), or at who reviewed the
  earlier round. LIMITATIONS.md:325 says "Reviews are done by another model": not what the code does.
- `ReviewPolicy` decides accept / revise / new attempt / arbitrate; the Director arbitrates when the
  review does not converge (`orch/ReviewSteps.kt:94-161`, 163-190). A review that cannot be done uses
  the result "without review" and says so (205-219).

### 3.4 Loop protection

`TeamGuards` (`orch/TeamGuards.kt`): depth (31), per-agent policy depth (39), cycles (50), task limit
(56), questions per task (59), message limit (62), the third near-identical request from the same
sender to the same recipient (`MAX_REPEATS = 2`, 27; similarity by word sets at 0.8, 77). Every
refusal reaches the model as a tool result and the owner as a "Limit reached" STATUS
(TeamDesk.kt:236-248). Objective-wide limits: settings `maxTasksPerObjective` 24 (4..100),
`maxMessagesPerObjective` 200 (50..1000), `maxDelegationDepth` 3 (0..10) and the rest
(`OrchestrationLimit.kt:21-63`). Owner waits do not count against time limits
(`op/PermissionBroker.kt:77-80, 212`).

### 3.5 Shared spaces and memory shares

Private memory per agent; spaces readable and writable per agent by the owner's `MemoryPolicy`
(`AgentProfile.kt:89-97`); `memory.share` copies one memory into a space the agent may write, never
switched on by default (`framework/.../memory/MemoryShareTool.kt:19-29`). The Director's summary goes
into the first space it may write when the owner allowed saving for that objective
(`orch/KnowledgeKeeper.kt:27-52`). Nothing makes all agents share all memories, and nothing below
changes that.

### 3.6 What the owner can say to a running objective today

Start it (text and two options), pause, resume, cancel, cancel one task, run a task or the objective
again, delete (`coretasks/TaskPorts.kt:61-96`); answer permission and approval requests, with an
optional note that reaches the agent quoted ("The owner's note, quoted: ...", up to 500 characters,
`op/PermissionBroker.kt:294-296, 419`). The owner cannot answer a question, add information or
correct course mid-run. An agent cannot ask the owner anything except permissions; the
`permissions.request` tool tells it "this does not wait and you are not told the answer"
(`op/PermissionRequestTool.kt:39-40`).

### 3.7 Known rough edges that concern collaboration

From STATUS: reviews take task places from the same limit as the work, and 2 tasks at once by default
(STATUS.md:329-330); knowledge records numbered by run (335-336); the summary status after an
owner edit is rewritten (337-338); a refused knowledge save is not named in the objective's status
(339-340); real models' planning, reviewing and arbitrating not verified (317-319). From LIMITATIONS:
agents can mislead each other; reviews "catch mistakes, not attacks" (LIMITATIONS.md:322-325).
Found in the code: QUESTION/ANSWER unused (3.1); unframed teammate text in tool results (3.2); reviewer
choice blind to model, helpers and earlier rounds (3.3); memory shares without their space (N4); no
limit on agent-initiated owner requests across the team, only 3 own asks per agent
(`CapabilityRequester.MAX_PENDING_ASKS_PER_AGENT = 3`, `core/.../operator/OwnerPorts.kt:194`).

---------------------------------------------------------------------------------------------------

## 4. Collaboration refinements for the last phase

Order is the recommended priority. C1, C3 and C4 are the ones with the clearest value for this owner;
C2 is valuable but has a provider-compatibility risk that must be tested; C5 and C6 are polish with
low risk; C7 is hardening.

### C1. Agents ask you a question instead of guessing (`team.ask_owner`)

**Value.** Real models given a vague objective ("plan a weekend") guess. One typed question ("Which
city?") saves a whole run. For an owner who is not a developer this is the most natural way to steer.

**Mechanism.**
- Core: `OwnerRequestKind.QUESTION` (appended; stored by name, `OwnerRequests.kt:16-22`). New fields
  on `OwnerRequest`: `question: String?` (the agent's text, up to 1,000 characters), `options:
  List<String>` (up to 4 suggested answers, each up to 120 characters), `answer: String?` (the owner's
  text, up to 2,000 characters). Invariants added to `init` (102-116): a QUESTION never has a
  `decisionScope`, never creates a grant (`grantFor` already requires PERMISSION, 154-162), is
  APPROVED only with an `answer`. New decision `OwnerDecision.Answer(text)`; `problemWith` accepts
  only Answer or Deny for QUESTION. Capability on the record: `team.communicate`, risk SAFE.
- Owner-only port: `OwnerRequests.answer(id, text): DecideResult` (`core/.../operator/OwnerPorts.kt:42-88`),
  compare-and-set like the others; Deny is labelled "Decide yourself".
- Framework: tool `team.ask_owner(question, options?)` in `orch/TeamTools.kt`, offered with the
  team tools to the assignee of a running WORK task (and the Director while planning, where a
  question is most useful), through a new `OwnerQuestions` class that reuses the broker's waiting
  machinery: `RequestWaiters.register` before storing (`op/RequestWaiters.kt:37-50`),
  `WaitClock.pausedWhile` (`op/PermissionBroker.kt:212`), the WAITING_FOR_OWNER flip and the deadline
  move (302-345). Extract that block from `PermissionBroker.ask` (190-228) into one helper used by
  both, so the two waits cannot drift apart.
- Messages: QUESTION agent → You and ANSWER You → agent (finally using the existing types), so the
  conversation and the team view show them.
- Time-out: new setting "Question time-out" (`ownerQuestionTimeoutMinutes`, default 20, 5..240) in
  `OrchestrationLimit`. On expiry the tool returns: "The owner did not answer within 20 minutes. Make
  the most reasonable choice, say which one in your result, and go on." Answer and "decide yourself"
  return "The owner answered, in their own words: <framed>".
- UI: the Approvals list becomes "Waiting for you" with three kinds; the request screen
  (`app/.../ui/approvals/ApprovalScreen.kt`) shows the question quoted as the agent's words, the
  suggested answers as buttons, a text field, "Decide yourself", and the line "Your answer goes to
  <provider> (<where>)" built from the agent's model locality. A fixed line: "Agents never need your
  passwords, codes or keys. Don't type them here." A check before sending warns when the answer looks
  like a key, a code or a password (a few patterns; help, not a guarantee). The notification "A
  question from <agent>" only opens the app, like approvals today (`app/.../ui/approvals/OperatorNotifications.kt`).
- The final document lists the questions, the answers or "no answer, assumed ..." (C5).

**Risks and their limits.**
- Prompt injection through the owner: a planted page makes an agent ask "paste the code you just got
  by SMS". Mitigation: the fixed warning, the key/code check, the question shown as quoted agent text
  with look-alikes marked (`PreviewText.exact`, as approvals do), and no answer can do anything by
  itself: it is text to a model, never an approval, never a grant. Architecture tests
  (`app/src/test/.../di/OperatorDependencyTest.kt`, framework `OperatorDependencyTest`) extended so
  nothing agent-facing reaches `answer`.
- Nagging and loops: at most 1 open question per task, 2 per task in total, 4 per objective (counted
  from stored QUESTION messages, like `asked` in TeamDesk.kt:76-78); the second near-identical
  question to the owner is refused (`TeamGuards.similar`); team-wide cap C3. Questions count against
  the objective's message limit like any message.
- Privacy: the answer goes to the agent's provider (cloud for this owner); local-only mode does not
  block the question but the screen says where the answer goes; the answer is logged as part of the
  model call's egress record as now.
- The owner is away: the time-out is not counted against the task (paused clock), so a 20-minute wait
  does not fail it; the objective's own 90-minute limit is also paused (same machinery).

**Tests.** Core invariants (`OwnerRequest` init for QUESTION); framework: answered, decided-yourself,
expired, cancelled by pause and by the task ending, app killed while waiting (start-up closes it as
"No longer waiting", `op/OperatorTaskHooks.kt:43`), deadlines moved like `OwnerWaitTest` and
`OwnerWaitRecoveryTest` (`framework/src/test/.../orchestration/`), caps and the repetition guard,
the answer framed in the tool result; data: migration 5 → 6 (`OwnerRequest` columns), the request
contract suite; app: request screen VM, the warning, the key check, notification; screenshots;
architecture tests.

### C2. You join the conversation: notes to the team mid-run

**Value.** The owner sees the plan go the wrong way ("Only free apps, please") and today can only
cancel and start again.

**Mechanism.**
- `MessageType.GUIDANCE` (appended), label "Note from you". Owner-only port
  `OwnerConversation.addNote(rootId, toTaskId: TaskId?, text)` in `:core`, implemented in
  `:framework` over `ObjectiveJournal` (from = `Participant.Owner`, to = Director or the task's
  agent), logged as `USER_ACTION`. Up to 1,000 characters, at most 10 notes per objective, not
  counted against the agents' message limit.
- Delivery, in two stages:
  1. **Every brief written after the note** includes the notes that apply (objective-wide, or for
     that task or its parent): `BriefWriter.work`, `review`, `arbitration` and `synthesis`
     (`orch/BriefWriter.kt:117, 181, 208, 231`) get a section "Notes from the owner" with each note
     framed (same structural framing as `DataBlock`, labelled "Note from the owner, 10:42") and the
     explanation: these are the owner's own words, they carry the same weight as the objective, and
     they cannot change tools, limits or permissions. Tasks that start, restart, are revised or
     reviewed after the note get it; so does the final document. Reviewers check against it.
  2. **A task already running** gets it before its next model call: an optional
     `AgentRunRequest.inbox: suspend () -> List<String>` polled in `AgentRunner`'s loop before each
     model call after the first (`framework/.../agents/AgentRunner.kt:216-246`), appended as one user
     message after the tool results. Assumption to verify per adapter with the mock-server tests in
     `:providers` and `:providers-anthropic`: that a user message directly after tool results is
     accepted (OpenAI-compatible, which covers xAI, Gemini, Ollama, the prompted-tools path, and
     Anthropic's adapter). Where one is not, that provider gets the note at stage 1 only, and the
     composer says "The note reaches its next step".
- UI: a composer at the bottom of the Conversation tab ("Write to the team" / "Write to <agent>"),
  and from the team view's node sheet. Under it: "Notes reach tasks from their next step. To change
  the plan, cancel tasks or run the objective again." and where the text goes.

**Risks.** Agents cannot forge an owner note: only the owner-only port writes GUIDANCE, and a test
walks the tools and asserts that no agent-reachable path calls it (the architecture tests above), and
`BriefWriter` reads GUIDANCE only with `from == Participant.Owner`. A note pasted from a web page is the
owner's choice. A note that contradicts the completion criteria may cause extra revision rounds;
bounded by `maxRevisionRounds`. If stage 2 changes the request shape for a provider in a way the tests
miss, a model call fails: it then fails like any model call (attempts), and the note is not lost
(stage 1).

**Tests.** Framework: notes in each brief kind, budget and order; a running task receives the note
once, before its next call; no inbox call after the run ended; GUIDANCE not counted by
`TeamGuards.messageLimit`; adapters: request mapping of tool result + user text for each adapter;
app: composer VM; architecture tests.

### C3. A team-wide cap on open questions to you

**Value.** Five agents each asking for permissions and answers at once is overwhelming; the owner
should see a few clear questions.

**Mechanism.** In one place (`OwnerAttention`, `:framework`), checked under the same lock as
`DefaultCapabilityRequester` (`op/DefaultCapabilityRequester.kt:56-120`): at most 5 open
agent-initiated requests (permission asks and C1 questions) across all objectives and chats, at most 3
per objective, besides the existing 3 per agent (`OwnerPorts.kt:194`). Over the cap the tool is
refused at once: "The owner already has 5 questions waiting. Make a reasonable assumption, say which,
and go on." and the owner is told once per task through the existing `guardOnce` pattern
(TeamDesk.kt:242-248). Requests the broker raises for an action in progress (a permission the call
needs, an approval) are not refused by count: each is one held call, bounded by "Tasks at once"
(`ObjectiveDriver.kt:237`); the screen groups them by objective. Optional, small: an "I'm away"
switch on the Waiting-for-you screen that refuses new questions (not approvals) with "The owner is
away".

**Risks.** A refused question makes the agent guess, which is today's behaviour; the guess is named
in the result (C1 wording). No security risk: the cap only refuses.

**Tests.** Concurrent asks cannot pass the cap together; per-objective and team-wide counts; approvals
not counted; the owner told once per task.

### C4. Better choice of reviewer

**Value.** A review by the agent that wrote half of the result, or by the same model with the same
blind spots, adds little. With one xAI key all agents may use the same model; the owner should then
at least be told.

**Mechanism.** `AgentSelector.forReview(needs, author, contributors, authorModel, previousReviewer,
team, load)` (`orch/AgentSelector.kt:61-71`):
1. Exclude contributors (agents that answered the author's questions or took delegated parts under
   the task: senders of ASSISTANCE_RESPONSE and assignees of child tasks), unless nobody else can run.
2. For a later revision round, prefer the earlier round's reviewer (`reviews.last().reviewer`) when it
   can run, so feedback stays consistent.
3. Add 1.0 to the score of a reviewer whose resolved model (`AgentAvailability.Ready.modelId`,
   `core/.../agents/AgentAvailability.kt:15`) differs from the author's.
4. Status line and task screen say "Reviewed by Reviewer (same model as the author)" when it is.
Correct LIMITATIONS.md:325 to "by another agent, on another model when one is available".
Arbitration stays with the Director; when the Director was the author (possible when only it could
run, AgentSelector.kt:46-50) the arbitration says so.

**Risks.** Fewer eligible reviewers → more "used without review" (already handled,
ReviewSteps.kt:205-219). Determinism is kept (ties as today).

**Tests.** `AgentSelectorTest` (`framework/src/test/.../orchestration/AgentSelectorTest.kt`): helpers
excluded, fallback when only helpers can run, same reviewer on round 2, model bonus, ties;
`ReviewFlowTest`: the status line.

### C5. Clearer final documents

**Value.** The owner needs to know what the answer rests on, without reading the log.

**Mechanism.** The synthesis brief (`orch/BriefWriter.kt:231-265`) asks for a two- or three-sentence
"In short" at the top. The app adds an "About this result" part that the **framework writes from
stored facts**, never the model: who did which part, which models (and "simulated" where the
simulator was used), which results were reviewed and by whom, used without review, or used with open
issues (`statusDetail`, `ReviewSteps.kt:136, 208`), the questions to you and your answers or time-outs
(C1), your notes (C2), limits reached ("Limit reached" STATUS messages), and how many times data left
the phone (as `ObjectiveViewModel.egressOf`). Rendered under the document on the Result tab and
included in Copy and Share (a switch "Include how it was made", on). Not stored in `result`, so
knowledge saving and "run again" are unchanged. `FinalDocument.assemble` (`orch/SynthesisStep.kt:86-120`)
stays the fallback.

**Risks.** None to safety; the part is built from records, so it cannot be persuaded by agent text
(agent text appears in it only as titles, shown as plain text).

**Tests.** Pure builder tests over the `ObjectiveWorld` sample (reviewed, unreviewed, open issues,
limits, egress, simulator); Result tab screenshot; Share text contains the part when switched on.

### C6. Building on an earlier result

**Value.** "Now book the cheapest of those three" should not redo the research.

**Mechanism.** `ObjectiveOptions.buildsOn: List<TaskId>` (stored in the existing `options_json`,
no schema change; `coretasks/Tasks.kt:101-109`), at most 3, only finished objectives with a
document. The New objective form (`app/.../ui/tasks/NewObjectiveScreen.kt`) gets "Build on an
earlier result" with a picker. The planning brief (`BriefWriter.plan`, BriefWriter.kt:75) and the
synthesis brief include each earlier document framed and labelled "Final result of an earlier
objective: <title>", within the brief budget (`TextBudget`); the planner may mark tasks that need it
(`"uses_earlier": true` in the plan JSON, parsed in `orch/PlanParser.kt`), whose work briefs then get
it as a source result. Only the document is passed: no memories, no transcripts. The form says which
models will receive it and whether that is off the phone, as it already does for the team's models.

**Risks.** An old document can carry text planted by a web page in that earlier run: framed as data
like every result. Large documents: budgeted and cut with a note, as briefs already are.

**Tests.** Plan and work briefs contain the framed document within budget; nothing else of the earlier
objective is read (no memory reads, asserted with the in-memory memory port); options round-trip; form
VM.

### C7. Frame what teammates write, also in tool results (hardening)

**Value.** Defence in depth against agents misleading each other (LIMITATIONS.md:322-325).

**Mechanism.** In `TeamDesk.ask` and `delegate`, wrap the teammate's answer and the delegated result
in `DataBlock.frame` with labels "Answer from Analyst" / "Result of the delegated task ... by
Writer", plus one sentence of `DataBlock.EXPLANATION` (TeamDesk.kt:111, 189; DataBlock.kt:24, 41).
Same for C1 answers and C6 documents. Small, no data change.

**Risks.** Slightly longer tool results; the simulator's `DataBlock.parseAll` (DataBlock.kt:52) keeps
working.

**Tests.** `TeamToolsTest`: forged closing tags and headings in an answer stay quoted.

### Not proposed, and why

- A free agent-to-agent chat outside tasks: loops and cost without a task to bound them.
- A "team memory" every agent reads by default: against the owner's rule "Do not force all agents to
  share all memories". The team view only shows the spaces the owner configured.
- Answering approvals by typing ("yes, do it") or from the notification: an answer must never become
  an approval; approvals stay tap-only on their own screen.
- Re-planning by the Director after a note: a new orchestration path at the end of the project; cancel
  task and run again already exist.

---------------------------------------------------------------------------------------------------

## 5. Summary of changes by module (for the plan)

| Module | Change |
|---|---|
| `:core` | `MessageHeader`, `TaskSummary` and their repository methods (D1); `Participant.Space` (D2); `MessageType.GUIDANCE`; `OwnerRequestKind.QUESTION`, `OwnerRequest.question/options/answer`, `OwnerDecision.Answer`, `OwnerRequests.answer`; `OwnerConversation` port; `ObjectiveOptions.buildsOn`; `FrameworkSettings.ownerQuestionTimeoutMinutes` |
| `:testkit` | In-memory implementations and contract suites for D1, D2 and the request fields |
| `:data` | Schema 6: `agent_messages.to_space_id`, `owner_requests.question/options_json/answer`; migration 5 → 6 hand-written and tested with data in every table, as before; projection queries; `StoredNamesGoldenTest` |
| `:framework` | `TeamGraph` needs nothing here; `team.ask_owner` + `OwnerQuestions` (shared wait helper extracted from `PermissionBroker.ask`); `OwnerAttention` cap; `OwnerConversation` implementation and brief sections; `AgentRunRequest.inbox`; `AgentSelector.forReview` changes; DataBlock in team tool results; memory share participant |
| `:providers*` | Request-mapping tests for "tool result then user text" (C2 stage 2) |
| `:app` | `TeamRoute`, `team/` package (model, builder, timeline, pulse scheduler, layout, view, list, sheets, VM); dashboard card fixes N1-N3, N6, N7; Waiting-for-you screen with questions; Conversation composer; Result tab "About this result"; New objective "Build on"; Settings: "Question time-out", motion preference |
| Docs | STATUS, LIMITATIONS (reviews wording; questions and notes leave the phone with the model call; replay is reconstructed; tool nodes depend on log retention) |

"(test)" labels: none needed if each control ships working. If C2 stage 2 is not proven for a
provider, its option is not offered for that provider rather than labelled.

## 6. Open points and assumptions

1. Strong skipping makes the node composables skip per frame today (assumed, unmeasured).
2. "Remove animations" sets the animator scale to 0 on the owner's phone (community sources; not
   found in official documentation). `areAnimatorsEnabled()` covers the developer option and battery
   saver (official).
3. A user message after tool results is accepted by every adapter (C2 stage 2): to be tested.
4. The 3 ms draw target and the 30-node cap are design numbers, not measurements; only the owner's
   phone can confirm them.
5. Real models' use of `team.ask_owner` (too often, too rarely) is unknown; the caps bound the first,
   the tool description and the Director's protocol text must encourage the second.
6. slack-chats.kotlinlang.org and android.googlesource.com could not be reached from this session
   (not in its allowed network); the Compose infinite-transition behaviour was read from the local
   animation-core-android 1.13.0 bytecode instead.

## Sources

- [Compose accessibility: traversal order](https://developer.android.com/develop/ui/compose/accessibility/traversal)
- [Compose performance best practices: defer reads](https://developer.android.com/develop/ui/compose/performance/bestpractices)
- [MotionDurationScale](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale)
- [ValueAnimator.areAnimatorsEnabled, getDurationScale](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled())
- [Slow rendering and frozen frames](https://developer.android.com/topic/performance/vitals/render)
- Community, for the "Remove animations" proxy only: [Inclusive Android Apps #8](https://buttondown.com/inclusive-android-apps/archive/inclusive-android-apps-8-the-problem-of-animations/), [Android animations and reduced motion](https://dev.to/eevajonnapanula/android-animations-and-reduced-motion-2em2)
