# Wave 2a review: minors left

Branch `p6/w2afix` (from 99dbb4a), merged into `claude/ai-control-center-architecture-wjdfww`.

Every finding was fixed, the four minors included (SEC-W2A-3, SEC-W2A-4, SEC-W2A-5, INT-2, INT-3, INT-4). The
items below are optional parts of a fix, or deliberate departures from the reviewer's suggested fix. None is a
finding left open.

| Finding | What is left or done differently | Why |
|---|---|---|
| SEC-W2A-1 (optional part) | `memory.save` / `memory.share` are not held back after an answer. An answer an agent saves to shared memory is not followed beyond its objective: an agent of another objective or a chat can find it and send it without the after-answer approval. Its usual permissions and approvals still apply. | The reviewer marked this optional. Asking before every memory save after an answer would add an approval the owner cannot judge well, since the save itself sends nothing. It is written in docs/LIMITATIONS.md section 6. WP12's attack list should try it. |
| SEC-W2A-1 (addition) | Beyond the reviewer's fix: after a restart, an objective whose stored messages hold an owner's answer counts as answered again (`AnsweredQuestions(messages)`, read once per objective). | Without it, a restart cleared every in-memory flag. A task that depends on the asking task would then send the answer unasked after Resume. |
| SEC-W2A-3 (different) | The new words "notification", "show", "character", "screen", "receiv", "display" and "appear" (`AnswerCheck.QUESTION_CODE_WORDS`) count only in the question, not in the answer. | In the owner's own sentence they are ordinary words ("Show the 2024 figures"), and a short number there would otherwise need the second tap. In the question the agent chooses them, so they count there. Codes standing on their own (6 to 8 digits, `G-482913`, `482 913`) are CODE whatever either side says. |
| SEC-W2A-4 | The last call let through can still take the day past the limit by its own cost, as with a single call. The estimate is the request's estimated input plus its answer room; it is 1,000 tokens when the request sets none. | This is inherent in checking before a call. It is written in docs/LIMITATIONS.md ("Limits stop work, not cost already spent"). WP10's Daily limits screen should say the same in one line. |
| INT-1 (choice) | Growing waits (30 s, 2, 5, 10 min…) were not added. Instead: at most 3 waits in a row after failed calls, a provider-wide hold, and a provider whose quota looks used up is not waited for. Its tasks fail within minutes. | Bounding the count is enough: 5 tasks on a used-up quota now end in 2 minutes with 36 requests and 14 calls, against 32 and 12 before wave 2a. The existing waits (30 s, then 2 min) and their tests are unchanged. |
| INT-1 (note) | Waits for an agent whose provider is marked OFFLINE send no model request. They stay bounded by the objective's time limit only, as WP4/WP7 designed (D28). | They cost nothing but the provider re-checks. |

Nothing else from the review is open.
