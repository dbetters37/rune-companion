# Status

What is built, how it was checked, and what is not there yet. Updated at the end of each phase.

## Phase 1: core application, registry, agent creation (complete)

### What works

- **App shell.** Eight sections (Dashboard, Agents, Models, Tasks, Memory, Tools, Activity,
  Settings); bottom bar plus "More" on phones, navigation rail on wide screens; dark and light
  themes following the system.
- **Agents.** A starter team (Director, Researcher, Analyst, Writer, Reviewer, Operator) is
  created on first start. Create from templates or blank, edit every field with validation
  next to the field, duplicate, enable or disable, designate the Director, delete (the
  designated Director is protected). Each agent shows whether it can run and exactly why not.
- **AI applications.** Scan the phone for installed AI apps (a catalog of 57 known packages,
  apps that answer the assistant intent, and a cautious name match shown as "Unverified" until
  you confirm it); register any installed app by hand; see what each app exposes and what the
  framework is permitted to do with it; open an app or hand text to it; enable, annotate,
  remove. A removed app stays removed across scans. An app that is uninstalled, reinstalled
  or re-signed loses its accessibility authorisation.
- **Registry directory.** One live view of agents, apps, providers and models feeds the
  dashboard: counts, the Director's state, a "Needs attention" list that links to the fix, and
  an interactive agent network (zoom, pan, tap a node).
- **Activity log.** Every registry change and hand-off is recorded; filter by type, severity
  and "information left the device"; open an event to see all fields.
- **Settings.** Local-only mode, activity retention, orchestration limits (stored now, used
  from Phase 4), a device report (chip, memory, storage, platform features, guidance on which
  local models are realistic).
- **Storage and security.** Room database encrypted with SQLCipher; its key is random and
  sealed by a hardware-backed Android keystore key. A keystore-backed vault for API keys is in
  place for Phase 2. Cloud backup is disabled. The APK is not debuggable.

### What is deliberately not there yet

- No model providers or models can be connected, so no agent can run. The app says so once on
  the dashboard. This is Phase 2.
- Tasks, Memory and Tools each show one screen stating that the section is not built yet.
- Accessibility operation can be authorised per app, but nothing uses it until the operator
  service exists (Phase 5); the app says so in the warning dialog.

### How it was verified

| Check | Result |
|---|---|
| Unit and UI tests, all modules (`./gradlew test`) | 481 pass: core, test kit and framework 82; data 202; device 85; app 112 |
| Contract suites | The in-memory and the Room implementations of every storage port pass the same suites, including re-emission, concurrent updates and hostile content |
| Screenshot tests | 59 screens and states rendered; reviewed by eye for layout, truncation and contrast |
| Independent reviews | The foundation and the data layer were each reviewed by a separate reviewer; findings were fixed (see below) |
| Android 14 emulator (software emulation) | Release APK installs and starts; the dashboard renders; the starter team is stored; the database file on disk is not readable as SQLite (encrypted) |
| Instrumented tests on the emulator | 16 pass against the real Android keystore and SQLCipher: key creation and reuse, non-exportable AES-256-GCM key, tamper detection, a lost key is reported and never silently replaced, the database reopens with its data, a wrong key fails |

Defects the reviews found and that are now fixed and covered by tests:

- A crafted provider address could be classified as "on device" although an HTTP client would
  send it to a remote host. Addresses are now parsed strictly and anything unusual counts as
  cloud.
- An app replaced under the same package name kept its accessibility authorisation.
- A removed app reappeared at the next scan.
- The vault could delete its key after a transient keystore error, and could treat an
  unreadable vault file as empty and overwrite it. Both would have lost every stored secret.
- One unreadable settings field reset all settings, including turning local-only mode off.

### Not verified

- Nothing has run on a physical phone. Start-up was checked only on the emulator's Android 14
  image, which has no Google apps and no AI apps installed, so the device scan found nothing
  there; scan logic is covered by Robolectric tests with simulated packages.
- Package visibility on a real device (whether every catalog app is actually seen).
- Most catalog package ids come from store listings and were not confirmed on a device.
- Android versions other than 14 (emulator) and 15 (Robolectric runtime).

### Known rough edges

- On the Agents list the "New agent" button covers the last visible card until you scroll.
- The activity screen shows the newest 300 matching events, without paging or text search.
- All text is English and hard-coded.
- The signing key in `keystore/` is public (see README).
- If the database key is ever lost, the only recovery is clearing the app's data; there is no
  in-app reset flow yet.

## Phase 2: model providers and agent execution (complete)

### What works

- **Providers.** Add a provider from presets: OpenAI, Anthropic, Google Gemini, OpenRouter,
  Groq, Mistral, DeepSeek, xAI, Ollama on this phone or on your network, LM Studio, llama.cpp
  server, any OpenAI-compatible server, and the built-in simulator. Each shows where text sent
  to it goes (on this device, local network, or cloud), its connection status, and whether a
  key is stored. Check the connection, refresh the model list, edit, disable, remove. "Find
  servers on this phone" looks for local model servers.
- **Keys.** Entered once in a masked field and stored in the keystore-backed vault. They are
  never shown again, never logged, and only ever sent to the address they were entered for:
  changing the scheme, host, port or kind of a provider requires entering the key again.
- **Models.** Listed from each provider, grouped by provider, with capabilities (tool calling,
  vision, reasoning and coding tiers, context window) marked as reported or estimated. Edit,
  disable, add by name, remove.
- **Agents run.** Assign a model (and fallbacks) to each agent, switch its tools on or off,
  and chat with it. Replies stream; tool calls appear as their own rows with their results;
  a turn keeps running if you leave the screen and can be stopped. Every reply says which
  model answered and how many tokens it used. Above the input, the chat always says where your
  text goes.
- **Tools.** Two read-only tools so far: the current date and time, and the list of agents.
  Models without native tool calling use a prompted protocol.
- **Simulator.** "Try with the simulator" lets you exercise chat, tools and the activity log
  with no key and no network. Every simulated reply says it is simulated.
- **Privacy controls with real effect.** Local-only mode blocks every provider and tool that
  is not on this device, immediately, including retries already in progress. Every model
  call, connection check and model listing that leaves the device is logged with where it went.
- **Dashboard.** Providers online and with problems, agents working right now and on what,
  working agents highlighted in the network, provider problems in "Needs attention".

### How it was verified

| Check | Result |
|---|---|
| Unit and UI tests, all modules | 1,023 pass: core 11, test kit 44, framework 191, providers 202, Claude adapter 89, data 228, device 85, app 173 |
| Provider adapters | Request and response formats, streaming, tool calls, errors, model listing and health tested against a local mock server for each adapter |
| Database upgrade | 0.1.0 installed and used on an Android 14 emulator, then 0.2.0 installed over it: the agents, the designated Director and the settings were kept; the encrypted database migrated from version 1 to 2 |
| On the emulator | Simulator assigned to all agents, a chat turn completed end to end through the encrypted database; the starter agents received their tools during the upgrade |
| Independent review | An adversarial review of the engine and adapters found 11 problems, 2 of them critical. Three rounds of fixes followed, each checked by a separate verifier who re-ran the reviewer's reproductions and tried to get around the fix |
| Screenshot tests | Every new screen in dark and light, a wide layout, and a 360 dp narrow phone |

What the review found and what was fixed:

- The Claude adapter followed HTTP redirects, so a local proxy or another app on the phone
  could forward conversations to an internet server without any check or log entry. Redirects
  are now refused for every provider.
- Retries re-sent a prompt without re-checking local-only mode, the provider's state or its
  key. Every attempt is now checked again, and the network library's own silent re-sends are
  switched off, so one model call is never sent more times than the log says.
- Changing a provider's address kept its key, which would then go to the new address. The
  key must now be entered again; keys are stored under a new name on every change, so a
  failed save can never pair the new key with the old address.
- Text a model reads (for example a tool result) could make a small model issue a tool call
  by repeating it. Such echoed calls are refused, including when the model alters the text
  slightly while copying it.
- Health checks and model listings to cloud providers were not logged. They are now.
- Smaller fixes: a malformed tool call is answered with an error the model can act on
  instead of ending the turn; tool permissions reflect the agent's current settings mid-turn;
  local server names that resolve to public addresses are refused; system proxies are never
  used for local servers; live reply text stays fluid for very long replies.

### Not verified

- **No real provider was contacted.** All adapters were tested against recorded request and
  response formats on a mock server. The official documentation for OpenAI, OpenRouter and
  Gemini could not be reached from the build environment, so those formats rest on
  documentation knowledge rather than a fresh reading. The first real use of each provider is
  its first live test.
- Nothing has run on a physical phone.
- Code shrinking is off, so the APK is about 80 MB, mostly the Anthropic SDK's libraries.
  Shrinking needs to be proven on a device first.

### Known rough edges

- "Find servers on this phone" only looks on this phone, not on your network.
- After a provider fails a check, its agents stay blocked until it is checked again (on start,
  when the app returns after 10 minutes, or with "Check now").
- The flag "answered by another model" compares model names, so a provider that answers under
  an unrelated alias of the same model is flagged.
- Conversations are not paged; very long conversations with many images load slowly.

## Phase 3: individual and shared memory (complete)

### What works

- **Private memory per agent.** Each agent has its own memory, which no other agent can read.
  Switch it off per agent in the agent editor.
- **Shared knowledge spaces.** Create spaces and allow each agent to read and, separately, to
  write each one. The starter space "Team knowledge" is readable by the starter agents and
  writable only by the Director. Agents are never given access to a space you did not allow.
- **Short-term task memory** that expires (3 days by default), with the important parts kept
  long-term when a task ends (used by the Director in Phase 4).
- **Agents use memory.** Agents can search, save and share memories with tools, and recall
  relevant memories automatically before replying (switchable per agent, with a limit).
- **Semantic search, offline.** Search ranks by meaning, keywords, recency and importance. It
  runs on the phone with no download and no network.
- **Provenance.** Every memory says who wrote it, in which chat or task, with which model and
  when, what it was derived from, and every later edit, share and promotion.
- **Links and graph.** Link memories (relates to, supports, contradicts, derived from, part
  of, references, replaces). The Graph tab draws them interactively, coloured by agent and
  space, and exports the graph as `aicc.memory-graph` JSON through the share sheet, for an
  external graph viewer.
- **Memory section**: an explorer with search and filters, memory details, a new-memory form,
  spaces with per-agent read and write switches, and the graph.

### How it was verified

| Check | Result |
|---|---|
| Unit and UI tests, all modules | 1,303 pass: core 11, test kit 57, framework 240, memory 107, providers 202, Claude adapter 89, data 272, device 85, app 240. The app suite passed three times in a row |
| Storage | The Room implementation and the reference implementation pass the same memory contract suite; migrations 1 → 2 → 3 and 2 → 3 tested with data in every table |
| Upgrade on the emulator | 0.3.0 installed over 0.2.0: agents, models and conversations kept; the "Team knowledge" space created with the intended access |
| On the emulator | A memory created, stored in the encrypted database, found by search, and drawn in the graph |
| Independent review | An adversarial review of the engine, storage and agent integration found 11 problems (1 critical). Two rounds of fixes followed, each checked by a separate verifier who re-ran the reviewer's reproductions |

What the review found and what was fixed:

- Recalled memories were placed in the system prompt, where a stored note could pose as an
  instruction. They now travel as a quoted data block with a per-turn marker; nothing inside
  a memory can start a line of the frame or close it.
- Memory text that had appeared in a chat kept being sent to the model after the memory was
  deleted or access to it was revoked. Chats now store references and re-check access on
  every read.
- Sharing or promoting a memory overwrote who originally wrote it, and edits were not
  recorded. The original provenance is now kept, with an append-only history.
- Deletes could race with updates and shares and bring a memory back. Writes are serialised,
  and a copy and its link are stored together or not at all.
- With large stores, recall and agent deletion read far too much (including other agents'
  short-term memory), and recall could not be interrupted. Reads are now bounded and
  interruptible; in the reviewer's test a slow recall went from 1.6 s to 0.2-0.3 s.
- Smaller fixes: switching on local-only mode stops a maintenance pass at once; deleted
  agents' memory is cleaned up even after an interrupted deletion; search folds accents; a
  memory and its shared copy no longer take two recall slots; re-sharing never overwrites
  edits someone made to the shared copy.

### Not verified

- Search quality with real-world memories at scale. The offline embedder recognises
  inflections and shared word parts, but it is not a language model: it finds related wording,
  not related meaning in different words, and a single misspelt word may not be found.
- Performance with tens of thousands of memories on a phone. Bounds and timings were measured
  on a desktop JVM.
- Nothing has run on a physical phone.

### Known rough edges

- Counts on the Memory screens load the full memory list; very large stores will load slowly.
- Memory upkeep (deleting expired short-term memory) runs at start and every 30 minutes while
  the app is open, not in the background.
- Exporting the graph is refused while local-only mode is on, even when you would only save
  the file on the phone.
- Deleting a space while editing an agent's memory settings at the same moment can lose one of
  the two changes.

## Phase 4: the Director and multi-agent work (complete)

### What works

- **Objectives.** In Tasks, give the Director an objective. Choose whether results are
  reviewed and whether what was learned is saved to a shared space (only offered when the
  Director may write one, which the form names). Before you start, the form says which models
  the team will use, whether any of them is off the phone, and which agents cannot run.
- **The Director's nine steps.** It interprets the objective, writes a plan of tasks with
  completion criteria and dependencies, picks an agent for each task by skills, current load
  and reliability (never an agent that cannot run), assigns the work, collects results, has
  the results it marked important reviewed by a different agent, sends revisions back or
  decides itself when reviewer and author keep disagreeing, writes the final document, and
  saves a summary to memory when you allowed it.
- **Agents work together.** Inside a task an agent can ask a teammate a question or delegate
  a sub-task, when its delegation settings allow it. Every exchange is a stored message:
  assignment, question, answer, delegation, review request and result, memory share,
  completion, failure.
- **Loop protection.** Limits on delegation depth, on delegating back to an agent already in
  the chain, on the third near-identical request to the same agent, on tasks and messages per
  objective, on model calls, tool calls, attempts and revision rounds per task, on time per
  task and per objective, and on restarts that make no progress. A reached limit is shown as
  such. Finished work is kept and used; a result its reviewer rejected is not.
- **Watching and stepping in.** Pause, resume (with the time the objective had left), cancel
  (with confirmation), run a single task again, run an objective again, or delete it. The
  objective screen has four tabs: the plan as a tree with attempts, revisions and verdicts; the
  conversation between agents, filterable by message type; the execution log, including every
  point where data left the phone; and the result, rendered as Markdown with Copy and Share.
  Each task has its own screen with its instructions, result, reviews, messages and the
  agent's full transcript.
- **Running with the screen off.** While an objective runs, a notification shows its progress
  and a Pause button. The app asks for the notification permission once, the first time.
- **Crash recovery.** Work is saved step by step. Work found running after a crash is marked
  interrupted and waits for you to resume it, or continues by itself when "Resume interrupted
  tasks" is on in Settings. A step that keeps crashing is retried a bounded number of times.
  An objective with only bookkeeping left (no agent work) is finished at the next start either
  way.
- **Knowledge.** The Director's summary of an objective is saved once and updated in place
  when a task is run again and the result changes. A summary whose text you edited is left
  as you wrote it. The objective's status says exactly what was and was not saved.
- **Dashboard.** "Working now" shows each agent's task and current step, and messages between
  agents pulse along the edges of the network.
- **Simulator.** With no model configured, the built-in simulator plays every role, so an
  objective can be tried end to end offline. Its plans and documents are labelled as simulated.

### How it was verified

| Check | Result |
|---|---|
| Unit and UI tests, all modules | 1,497 pass on a fresh run: core 11, test kit 64, framework 356, memory 107, providers 202, Claude adapter 89, data 297, device 85, app 286. The app and framework suites were re-run several times with no flaky results |
| Storage | The Room task and message stores pass the same contract suites as the reference implementation. Migrations 3 → 4, 1 → 4 step by step, and 1 → 4 directly were tested with data in every table |
| Orchestrator | The whole Director flow runs in tests on the simulator and on scripted model replies: planning, retries, reviews, revisions, arbitration, delegation, limits, pause, cancel, run again and recovery |
| Crash simulation | An independent harness killed the app at each of 15 points while an objective finished, once or every time, under different limits and settings, plus store errors at every write: several hundred scenarios. Across all of them no summary or announcement was duplicated, every objective that could finish finished, and recovery never started agent work you had not allowed |
| Independent review | An adversarial review of the orchestrator found 11 problems (1 critical, 6 major). Six rounds of fixes followed, each checked by separate verifiers who re-ran the earlier reproductions and wrote new ones |

What the review found and what was fixed:

- A crash or a pause and resume restarted the objective's clock and did not count the
  interrupted run, so a step that kept crashing could run forever. Interrupted runs now count
  as attempts, the time left is kept, and restarts without progress are capped.
- An objective limit (time, messages) threw away results that were already finished and
  failed the objective. Finished results are now kept and used; a result its reviewer rejected
  is not, and one still waiting for changes is used with the reviewer's issues attached.
- Briefs had no size budget, so a large set of results could fail an objective whose work all
  completed. Every brief now has a budget, and long results are shortened with a note.
- Running a task again while the objective was finishing could be lost; a delegated task could
  be left running when the task that delegated it stopped; a teammate's error text reached
  the next brief unquoted; questions to teammates were not counted against the asking task's
  limits. All fixed.
- Plan problems were not bounded and a plan with more reviews than the task limit allows
  failed. Reviews are now kept in plan order while they fit, and the rest are named as not
  reviewed.
- The fix rounds then hardened the end of an objective: the summary is never saved twice even
  if the app dies mid-save, the end is announced exactly once, and a storage error while
  finishing no longer turns a finished objective into a failed one.

### Not verified

- How well real models plan, review and arbitrate. The pipeline was exercised with the
  simulator and with scripted model replies, not with live providers. Small local models are
  expected to struggle with the Director role.
- Long objectives on a phone with aggressive battery management. The foreground service was
  started on the emulator, not on a physical device.
- Nothing has run on a physical phone.

### Known rough edges

- The task lists on the objective screens reload whole tasks, results included, whenever any
  task changes. Objectives with many large results will make those screens slow. Scheduled for
  Phase 6.
- An objective runs at most 2 tasks at the same time by default ("Tasks at once" in
  Settings > Orchestration limits). Reviews take places from the same task limit as the work.
- The notification is started only while the app is on screen, as Android requires. An
  objective resumed automatically after a crash gets its notification once you open the app.
- If the app is killed at the exact moment an objective ends, again and again, the activity
  log can show "Objective completed" more than once. The message to you is sent once.
- Records of saved knowledge number results by run ("final result 3"), so numbers can skip
  when a run ended without a result.
- If you edit the Director's summary and another agent then rewrites it, the summary is left
  as the agent wrote it, and the status still says it holds your version.
- If saving knowledge is refused because the Director lost access to the space, the refusal is
  logged, but the objective's status does not mention it.

## Next: Phase 5

The phone operator: a permission broker with per-agent, per-capability grants and an approval
screen, file and document tools in the app's workspace and in folders you choose, opening apps
and links, scripts in a sandbox, and the accessibility operator for apps you authorise, with
confirmation before anything consequential.
