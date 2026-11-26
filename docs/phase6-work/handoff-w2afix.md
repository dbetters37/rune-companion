# Hand-off from the wave-2a review fixes (branch p6/w2afix)

Merged into `claude/ai-control-center-architecture-wjdfww` after 99dbb4a. Each finding has its own commit and test;
see the merge commit. This page lists what later waves need to know.

## Behaviour that changed

- **Rule 32 (SEC-W2A-1).** After an owner's answer, every task of that objective asks before its first call that may
  send data off the phone. That covers the task that asked, the task that delegated it, the tasks that depend on it,
  the synthesis and planning. Each new answer asks each task once more. After a restart, an objective whose messages
  hold an owner's answer counts as answered again. `AnsweredQuestions(messages)` is wired in `OperatorWiring`;
  `TestWorld.answeredQuestions` is shared by its broker and any question tool a test adds. The approval's first
  detail line is still `PermissionBroker.AFTER_ANSWER`.
- **Rule 24 (SEC-W2A-2).** `AgentRunner` runs `DataBlock.neutralise` on every tool result, and the neutraliser also
  matches "notes from the owner" with any spacing. Read back, the words are the same. In the model's transcript a
  page or file that says `owner_note` or "Notes from the owner" shows them as `owner&#95;note` and
  `N&#111;tes from the owner`.
- **Notes sentence (SEC-W2A-5).** `OwnerNoteFrame.ONE_CANNOT` / `SEVERAL_CANNOT`.
- **Code check (SEC-W2A-3).** These answers are CODE whatever the question says, so the owner gets the second tap:
  6 to 8 digits standing alone, a short letter prefix with a dash and 5 to 8 digits, or two groups of 3 to 4 digits.
  `AnswerCheck.QUESTION_CODE_WORDS` count in the question only. WP11a's question screen shows "Send it anyway" for
  these as for any CODE.
- **Daily limits (SEC-W2A-4, INT-1).** A call's first attempt reserves its estimated tokens, and they count for the
  other calls in flight. A call that ended in `RateLimited` is not one of today's answers. `DailyUse.counted(totals)`
  is the shared rule (the limiter, `DailyUse.of`, the usage rows after a restart). WP10's Today tile and Usage tab
  must use `DailyUse.of`, which already applies it.
- **Provider waits (INT-1).** A step waits at most 3 times in a row after failed calls; the next failure is an
  ordinary failed attempt. `ProviderSlowDowns` (one per orchestrator) holds a provider's other work while it asks
  the app to slow down, and treats a repeated "slow down" after the wait as a used-up quota: no more waiting, so its
  tasks fail within minutes. Planning that cannot wait any more fails the objective with "The Director could not
  plan: <provider> asked the app to slow down, and waiting for it did not help. Try again later."
- **Questions (INT-2).** Only questions that were answered, ran out of time, or still wait count against the caps
  and the near-identical check. The owner's ANSWER message has `replyTo` = its QUESTION message. A question that
  ran out of time gets a STATUS reply from the system: "The owner did not answer this question within N minutes."
  (`OwnerQuestions.NO_ANSWER_PREFIX`). WP9 and WP11a may show that line in the conversation.
- **Waiting work while stopped (INT-3).** A paused or interrupted objective's waiting work (QUEUED with `retryAt`)
  says "Was waiting: <what>. Tries again when the objective resumes." It no longer starts with
  `ProviderWaits.WAITING`, so WP9/WP11a show it as stopped, not as waiting with a time. Resume still tries it again
  at once. An objective whose work waited when the app stopped says "Interrupted while its work waited: <what>.
  Resume it to continue." and does not count towards the three-in-a-row rule.
- **Message reads (INT-4).** New `MessageRepository.listOfTypes(rootId, types, taskId?)` (core; the default filters
  `list`, Room overrides it with `AgentMessageDao.BY_ROOT_AND_TYPES` / `BY_TASK_AND_TYPES`; contract test in
  testkit). Any new repository double passes the contract as it is. WP9's team view can use it for notes
  (`OwnerNotes.TYPES`).

## Docs (WP14)

docs/LIMITATIONS.md gained:
- the after-answer approval and its limit (memory);
- tool output imitating notes;
- the daily limits and calls in flight;
- providers that keep asking to slow down.

STATUS and ARCHITECTURE are WP14's to update from this list.

## Device tests to run

None added.

## Residual fixes (branch p6/w2ares, merge 6fb816d "Wave 2a: residual fixes before wave 2b")

The fixer's four commits (look-alikes, stored originals, per-minute 429, docs) and the last round's three
(rate limits, owner notes, a clean-up). What later waves must know:

- **Tool results (WP11b's chat display, WP9).** The stored result, `observeEntries` and everything the owner sees
  are the tool's own text. Only the copy a model reads changes (`DataBlock.forModel`, made by `AgentRunner` for
  every request): one character of each owner-notes word as `&#NNN;`, and, only then, the `&` of every number
  the text already held as `&#38;`, followed by `DataBlock.ESCAPES_EXPLAINED` (new wording: "…write each such
  character itself, once."). Reading the copy back once gives the tool's text exactly. Unicode tag characters
  (U+E0000 to U+E007F) are left out of every neutralised text (model copies, briefs, recalled memories, the
  prompted quote) and are not explained; the stored text keeps them. Never show the model's copy to the owner.
  Note: `SimulatorLlmClient` echoes the model's copy into its reply (unreachable today; keep it so).
- **What counts as the notes' words (rule 24, `NoteWords`).** Now also: letters from Unicode's confusables data
  (`NoteLookAlikes.kt`, generated with ICU's `SpoofChecker`; regenerate rather than edit), any other non-ASCII
  letter for at most half of a match, markdown `*`/`~`/backticks and short inline HTML tags, hyphens, dots, low
  lines and the blank braille pattern between the label's words, "the" left out, "rn" for m and "vv" for w;
  the tag with up to three non-alphanumeric characters or none between its words (`owner-note`, `ownernote`,
  `OwnerNote`; `owner note` only when no letter follows). The label is not matched inside a longer word
  ("Footnotes"). Not covered, written in LIMITATIONS section 6 (WP12's attack list): words written as HTML
  character codes, punctuation inside a word, more than half of the letters replaced.
- **Recalled memories.** `AgentRunner.recallMemories` neutralises the recalled block on the native path too, so
  a forged notes section saved to memory (by `memory.save` or "Save what was learned to memory") is defused when
  it is recalled into a brief.
- **Echo check.** `EchoGuard.of` reads each tool result without `ESCAPES_EXPLAINED`, so results read in order
  join as the tool wrote them.
- **Daily quota wording (core `LlmException.RateLimited.namesDailyQuota`).** "per day", "daily quota", "daily …
  limit", RPD, TPD. "Today" alone no longer counts (Groq's per-minute 429 ends "Upgrade to Dev Tier today").
  The redacted copy of a 429 (`withoutSecret`) keeps `dailyQuota`.
- **Daily-quota 429 and other models.** `ProviderSlowDowns.slowedDown` returns false at once for a daily quota and
  leaves the provider's strikes and hold alone, so another model of the same provider keeps R4's waits.
- **No in-call repeat for an objective's bare 429.** `RetryPolicy.delayBeforeRetry(…, callerWaits)`: a call whose
  `CallContext.taskId` is set (every objective step, and team_ask_agent runs, which carry the asking task's id)
  does not repeat a 429 that names no wait; the step's own waits (30 s, then 2 min) cover it. Chats and calls
  without a task still repeat it after 1 s and 2 s. A 429 with Retry-After of at most 20 s is still waited for
  inside the call. Five tasks on a 429 without daily words all day: 14 work requests, 16 calls, about 4 minutes
  (INT-1's test bound is now 20 requests, 16 calls, 5 minutes).
- **Stopped 429-only calls (WP10).** A call whose every attempt was answered 429 and that is cancelled while it
  waits (objective paused, stopped, timed out) is not one of today's answers. Its event is still "Model call
  cancelled", but its usage record is FAILED with `ErrorClass.RATE_LIMITED`, so `DailyUse.counted` leaves it out
  after a restart too. The Usage tab will list such a call among calls refused for rate limits.
- **Docs (WP14).** LIMITATIONS section 5 (waits, daily wording, per-model quotas, stopped calls) and section 6
  (what the notes matching covers and does not; "the shared space that option names").
