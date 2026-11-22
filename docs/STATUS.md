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

## Next: Phase 2

Model providers (OpenAI-compatible, Anthropic, Gemini, Ollama, a built-in simulator), secure
key entry, model listing and capabilities, the agent execution loop with tools, and direct
chat with an agent.
