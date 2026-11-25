package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.ReviewVerdict
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.agents.RunLimits

/**
 * Runs REVIEW tasks: a reviewer's check of a result, and the Director's arbitration when a
 * review does not converge. A review's verdict is applied to the reviewed task in the same
 * transaction as the review's completion, with the next step ([ReviewPolicy]) already decided:
 * accepted, back to its author for a revision or a new attempt, or on to the Director.
 *
 * When a review cannot be done at all (no reviewer can run, its runs keep failing, the task
 * limit is reached), the result is used as it is and the task's status line says that it was
 * not reviewed and why, so the final document can say so. Holding back a finished result
 * because nobody could check it would lose work the owner asked for.
 *
 * Phase 6: today's limit reached pauses the objective (rule 6); a reviewer whose provider is not
 * answering, or asks the app to slow down, waits and is tried again without using an attempt (R4);
 * the reviewer is chosen as [AgentSelector.forReview] says (C4), and a review by an agent on the
 * same model as the author says so.
 */
internal class ReviewSteps(private val ctx: OrchestrationContext, private val work: WorkSteps) {

    suspend fun run(rt: ObjectiveRuntime, reviewId: TaskId) {
        val review = ctx.tasks.get(reviewId) ?: return
        val reviewed = review.parentId?.let { ctx.tasks.get(it) }
        if (reviewed == null || reviewed.status != TaskStatus.IN_REVIEW) {
            ctx.change(rt, reviewId, setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
                it.copy(status = TaskStatus.CANCELLED, error = "There was nothing left to review.", finishedAt = ctx.clock.now())
            }
            return
        }
        val objective = ctx.tasks.get(review.rootId) ?: return
        val settings = ctx.settings.get()
        val limits = ctx.limits(objective, settings)
        val kind = ReviewKind.of(review)

        val ready = when (val choice = chooseReviewer(review, reviewed, kind)) {
            is Readiness.Ready -> choice
            is Readiness.NotReady -> {
                if (choice.transient) {
                    ctx.waitForProvider(rt, review, ProviderWaits.named(choice.provider, "The provider"), null, setOf(TaskStatus.QUEUED, TaskStatus.RUNNING))
                    return
                }
                return couldNotReview(rt, review, reviewed, kind, choice.reason)
            }
        }
        // C4: said when the reviewer thinks with the same model as the author did.
        val same = if (kind == ReviewKind.REVIEW && sameModel(reviewed, ready)) WorkSteps.SAME_MODEL else ""
        val conversationId = review.conversationId ?: ctx.transcripts.open(ready.agent.id, "${kind.prefix.trim()} ${reviewed.title}")
        val now = ctx.clock.now()
        val started = ctx.change(rt, reviewId, setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
            it.copy(
                status = TaskStatus.RUNNING, assignee = ready.agent.id, conversationId = conversationId, startedAt = it.startedAt ?: now,
                deadlineAt = now + limits.taskTimeoutMs, retryAt = null, statusDetail = "${ready.agent.name} is reviewing$same, ${ready.destination}.",
            )
        } ?: return
        ctx.journal.taskChanged(started, if (kind == ReviewKind.REVIEW) "Review started" else "Arbitration started")
        val author = authorName(reviewed.assignee)
        val earlier = reviewed.reviews.map { it to authorName(it.reviewer) }
        val budget = ctx.steps.briefBudget(ready)
        val notes = work.notesFor(started, also = reviewed)
        val brief = when (kind) {
            ReviewKind.REVIEW -> {
                val plan = ctx.tasks.getTree(reviewed.rootId).filter { it.kind == TaskKind.WORK && it.delegationChain.isEmpty() }.sortedBy { it.order }
                ctx.briefs.review(ReviewBrief(objective, reviewed, author, plan.indexOfFirst { it.id == reviewed.id } + 1, plan.size, earlier, notes), budget)
            }
            // Only the framework's own reason goes into the brief's text; what agents wrote is framed there.
            ReviewKind.ARBITRATION -> ctx.briefs.arbitration(ArbitrationBrief(objective, reviewed, author, earlier, arbitrationWhy(review), notes), budget)
        }
        ctx.runtimes.started(ready.agent.id, started.id)
        val result = try {
            ctx.steps.run(
                ready, started, conversationId, brief, json = true, limits = RunLimits(timeoutMs = limits.taskTimeoutMs),
                purpose = "a result of another agent for review", screens = objective.options?.screenActionsAllowed ?: true,
            )
        } finally {
            ctx.runtimes.ended(ready.agent.id, started.id)
        }
        ctx.account(rt, started.id) { it.copy(usage = it.usage + result.usage) }
        when (val trouble = Trouble.of(result)) {
            is Trouble.DailyLimit -> return run { ctx.pauseForDailyLimit(rt, trouble.sentence) }
            is Trouble.Passing -> return run { ctx.waitForProvider(rt, started, ProviderWaits.named(ready.provider, ready.providerName), trouble.error, setOf(TaskStatus.RUNNING)) }
            null -> Unit
        }
        rt.waitsSoFar.remove(started.id)
        if (!result.ok) return runFailed(rt, started, reviewed, kind, result, limits.maxTaskAttempts)
        when (kind) {
            ReviewKind.REVIEW -> applyVerdict(rt, started, ready.agent, VerdictParser.review(result.text), limits.maxRevisionRounds, limits.maxTaskAttempts, limits.maxTasks, same)
            ReviewKind.ARBITRATION -> applyArbitration(rt, started, ready.agent, VerdictParser.arbitration(result.text))
        }
    }

    /**
     * The assigned reviewer when it can run, or waits for its provider (rule 25: never another agent then); otherwise the
     * best other reviewer (C4). An arbitration is the Director's.
     */
    private suspend fun chooseReviewer(review: TaskRecord, reviewed: TaskRecord, kind: ReviewKind): Readiness {
        val assigned = review.assignee?.let { ctx.prepareChecked(it) }
        if (assigned is Readiness.Ready || kind == ReviewKind.ARBITRATION) return assigned ?: Readiness.NotReady("the Director is not known")
        if (assigned is Readiness.NotReady && assigned.transient) return assigned
        val snapshot = ctx.directory.snapshot()
        val team = snapshot.agents.filter { it.profile.id != review.assignee }
        val tree = ctx.tasks.getTree(reviewed.rootId)
        val helpers = ctx.messages.list(reviewed.rootId).filter { it.taskId == reviewed.id && it.type == MessageType.ASSISTANCE_RESPONSE }
            .mapNotNull { (it.from as? Participant.Agent)?.agentId } + tree.filter { it.parentId == reviewed.id && it.kind == TaskKind.WORK }.mapNotNull { it.assignee }
        val authorModel = reviewed.assignee?.let { (ctx.steps.prepare(it) as? Readiness.Ready)?.modelId }
        val selection = ctx.selector.forReview(
            TaskNeeds(reviewed.title, reviewed.instructions), reviewed.assignee, team, ctx.runtimes.loads(),
            contributors = helpers.toSet(), previousReviewer = reviewed.reviews.lastOrNull()?.reviewer, authorModel = authorModel, models = snapshot.modelsById,
        )
        return when (selection) {
            is Selection.Chosen -> ctx.steps.prepare(selection.agent.id)
            is Selection.NoneSuitable -> Readiness.NotReady(selection.reason)
        }
    }

    /** The reviewer works on the model the author works on now (C4). */
    private suspend fun sameModel(reviewed: TaskRecord, reviewer: Readiness.Ready): Boolean =
        reviewed.assignee?.let { (ctx.steps.prepare(it) as? Readiness.Ready)?.modelId } == reviewer.modelId

    private suspend fun applyVerdict(
        rt: ObjectiveRuntime,
        review: TaskRecord,
        reviewer: AgentProfile,
        reading: VerdictReading,
        maxRounds: Int,
        maxAttempts: Int,
        maxTasks: Int,
        /** [WorkSteps.SAME_MODEL] when the reviewer works on the author's model, else empty. */
        same: String = "",
    ) {
        val now = ctx.clock.now()
        val unreadable = reading is VerdictReading.Unreadable
        val verdict = when (reading) {
            is VerdictReading.Read -> ReviewVerdict(reviewer.id, reading.decision, reading.summary, reading.issues, now, review.id)
            is VerdictReading.Unreadable -> ReviewVerdict(
                reviewer.id, ReviewDecision.REVISE, "${ReviewPolicy.UNREADABLE_PREFIX}: ${reading.why}.",
                listOf("Make the result meet every completion criterion explicitly, so that the next review can check each one."), now, review.id,
            )
        }
        var arbitrationLimit: String? = null
        val outcome = rt.lock.withLockNonCancellable {
            val current = ctx.tasks.get(review.id)?.takeIf { it.status == TaskStatus.RUNNING } ?: return@withLockNonCancellable null
            val reviewed = current.parentId?.let { ctx.tasks.get(it) }?.takeIf { it.status == TaskStatus.IN_REVIEW } ?: return@withLockNonCancellable null
            val step = ReviewPolicy.next(reviewed, verdict, unreadable, maxRounds, maxAttempts)
            val done = current.copy(
                status = TaskStatus.COMPLETED, result = verdictText(verdict), finishedAt = now, updatedAt = now,
                statusDetail = "Verdict: ${verdict.decision.name.lowercase()}.",
            )
            val judged = reviewed.copy(reviews = reviewed.reviews + verdict, updatedAt = now)
            var arbitration: TaskRecord? = null
            val next = when (step) {
                ReviewStep.Accept -> judged.copy(status = TaskStatus.COMPLETED, finishedAt = now, statusDetail = "Approved by ${reviewer.name}$same.")
                is ReviewStep.Revise -> judged.copy(status = TaskStatus.QUEUED, revisionRound = step.round, error = null, statusDetail = "${reviewer.name} asked for revision ${step.round}.")
                is ReviewStep.NewAttempt -> judged.copy(status = TaskStatus.QUEUED, attempt = step.attempt, revisionRound = 0, error = null, statusDetail = "${reviewer.name} rejected the result; attempt ${step.attempt + 1} follows.")
                is ReviewStep.Arbitrate -> {
                    val objective = ctx.tasks.get(reviewed.rootId)
                    val director = objective?.assignee?.let { ctx.agents.get(it) }
                    val count = ctx.committedTasks(ctx.tasks.getTree(reviewed.rootId))
                    if (director != null && count < maxTasks) {
                        arbitration = work.reviewTask(judged, director, ReviewKind.ARBITRATION, ARBITRATION_PREFIX + step.why, now)
                        judged.copy(statusDetail = "Waiting for the Director's decision: ${step.why}")
                    } else {
                        arbitrationLimit = if (director == null) "the Director is not available to settle the review" else "the objective has reached its limit of $maxTasks tasks"
                        judged.copy(status = TaskStatus.COMPLETED, finishedAt = now, statusDetail = "Used with the review's open issues: $arbitrationLimit.")
                    }
                }
            }
            ctx.tasks.upsertAll(listOfNotNull(done, next, arbitration))
            Triple(done, next, step)
        } ?: return
        val (done, reviewed, step) = outcome
        ctx.journal.taskChanged(done, "Review finished")
        ctx.journal.send(done.rootId, reviewed, Participant.Agent(reviewer.id), author(reviewed), MessageType.REVIEW_RESULT, verdictText(verdict))
        reviewed.assignee?.let { authorId ->
            ctx.journal.countStats(authorId) {
                when (verdict.decision) {
                    ReviewDecision.APPROVED -> it.copy(reviewsPassed = it.reviewsPassed + 1)
                    ReviewDecision.REVISE -> it.copy(revisionsRequested = it.revisionsRequested + 1)
                    ReviewDecision.REJECTED -> it.copy(reviewsRejected = it.reviewsRejected + 1)
                }
            }
        }
        arbitrationLimit?.let { ctx.journal.limitReached(done.rootId, reviewed, "\"${reviewed.title}\" was used with open review issues: $it.") }
        when {
            reviewed.status == TaskStatus.COMPLETED -> completedAfterReview(reviewed)
            step is ReviewStep.Arbitrate -> ctx.journal.taskChanged(reviewed, "Sent to the Director")
            else -> ctx.journal.taskChanged(reviewed, if (step is ReviewStep.Revise) "Revision requested" else "New attempt requested")
        }
    }

    private suspend fun applyArbitration(rt: ObjectiveRuntime, arbitration: TaskRecord, director: AgentProfile, reading: ArbitrationReading) {
        val now = ctx.clock.now()
        val (decision, reason) = when (reading) {
            is ArbitrationReading.Read -> reading.decision to reading.reason
            is ArbitrationReading.Unreadable -> Arbitration.ACCEPT to "The Director's decision could not be read (${reading.why}); the result is used with the review's open issues."
        }
        val outcome = rt.lock.withLockNonCancellable {
            val current = ctx.tasks.get(arbitration.id)?.takeIf { it.status == TaskStatus.RUNNING } ?: return@withLockNonCancellable null
            val reviewed = current.parentId?.let { ctx.tasks.get(it) }?.takeIf { it.status == TaskStatus.IN_REVIEW } ?: return@withLockNonCancellable null
            val text = "Decision: ${if (decision == Arbitration.ACCEPT) "accept" else "fail"}. $reason"
            val done = current.copy(status = TaskStatus.COMPLETED, result = text, finishedAt = now, updatedAt = now, statusDetail = text.take(200))
            val decided = if (decision == Arbitration.ACCEPT) {
                reviewed.copy(status = TaskStatus.COMPLETED, finishedAt = now, updatedAt = now, statusDetail = "Accepted by the Director: $reason")
            } else {
                reviewed.copy(status = TaskStatus.FAILED, finishedAt = now, updatedAt = now, error = "The Director decided not to use it: $reason", statusDetail = "Failed by the Director's decision.")
            }
            ctx.tasks.upsertAll(listOf(done, decided))
            done to decided
        } ?: return
        val (done, decided) = outcome
        ctx.journal.taskChanged(done, "Director decided")
        ctx.journal.send(done.rootId, decided, Participant.Agent(director.id), author(decided), MessageType.REVIEW_RESULT, done.result.orEmpty())
        if (decided.status == TaskStatus.COMPLETED) completedAfterReview(decided) else {
            ctx.journal.taskChanged(decided, "Task failed", Severity.WARNING)
            ctx.journal.send(decided.rootId, decided, Participant.Agent(director.id), Participant.Owner, MessageType.FAILURE, "${decided.title}: ${decided.error}")
            ctx.journal.countFinished(decided)
        }
    }

    private suspend fun runFailed(rt: ObjectiveRuntime, review: TaskRecord, reviewed: TaskRecord, kind: ReviewKind, result: StepResult, maxAttempts: Int) {
        val reason = result.failure ?: "The review did not produce an answer."
        val retried = ctx.change(rt, review.id, setOf(TaskStatus.RUNNING)) {
            if (it.attempt + 1 < maxAttempts) it.copy(status = TaskStatus.QUEUED, attempt = it.attempt + 1, error = reason, statusDetail = "Trying the review again. $reason")
            else it
        }
        if (retried != null && retried.status == TaskStatus.QUEUED) {
            ctx.journal.taskChanged(retried, "Review will be retried")
            return
        }
        couldNotReview(rt, review, reviewed, kind, reason)
    }

    /** The review cannot be done: it fails, and the result is used without it (or, for an arbitration, with the open issues). */
    private suspend fun couldNotReview(rt: ObjectiveRuntime, review: TaskRecord, reviewed: TaskRecord, kind: ReviewKind, reason: String) {
        val now = ctx.clock.now()
        val note = if (kind == ReviewKind.REVIEW) "Used without review: the review could not be done ($reason)." else "Used with the review's open issues: the Director could not decide ($reason)."
        val outcome = rt.lock.withLockNonCancellable {
            val current = ctx.tasks.get(review.id)?.takeIf { !it.status.isFinished } ?: return@withLockNonCancellable null
            val parent = ctx.tasks.get(reviewed.id)?.takeIf { it.status == TaskStatus.IN_REVIEW }
            val failed = current.copy(status = TaskStatus.FAILED, error = reason, statusDetail = reason, finishedAt = now, updatedAt = now)
            val used = parent?.copy(status = TaskStatus.COMPLETED, finishedAt = now, updatedAt = now, statusDetail = note)
            ctx.tasks.upsertAll(listOfNotNull(failed, used))
            failed to used
        } ?: return
        ctx.journal.taskChanged(outcome.first, if (kind == ReviewKind.REVIEW) "Review failed" else "Arbitration failed", Severity.WARNING)
        outcome.second?.let { completedAfterReview(it) }
    }

    private suspend fun completedAfterReview(task: TaskRecord) {
        ctx.journal.taskChanged(task, "Task completed")
        task.assignee?.let { ctx.journal.send(task.rootId, task, Participant.Agent(it), ctx.assigner(task), MessageType.COMPLETION, WorkSteps.summaryBody("Completed", task)) }
        ctx.journal.countFinished(task)
    }

    private fun author(task: TaskRecord): Participant = task.assignee?.let { Participant.Agent(it) } ?: Participant.Owner

    private suspend fun authorName(id: AgentId?): String = id?.let { ctx.agents.get(it)?.name } ?: "a former team member"

    /** The framework's sentence on why the Director arbitrates; an arbitration stored before it was kept apart says it in general words. */
    private fun arbitrationWhy(review: TaskRecord): String =
        review.instructions.takeIf { it.startsWith(ARBITRATION_PREFIX) }?.removePrefix(ARBITRATION_PREFIX) ?: "the reviewer and the author did not agree."

    private fun verdictText(verdict: ReviewVerdict): String = buildString {
        append("Decision: ").append(verdict.decision.name.lowercase()).append(". ").append(verdict.summary)
        if (verdict.issues.isNotEmpty()) append('\n').append(verdict.issues.joinToString("\n") { "- $it" })
    }

    private companion object {
        /** Opens the instructions of an arbitration; the framework's reason follows. */
        const val ARBITRATION_PREFIX = "Settle the review that did not converge: "
    }
}
