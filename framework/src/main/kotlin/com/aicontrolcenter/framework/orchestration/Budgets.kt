package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.framework.llm.TokenEstimator
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import com.aicontrolcenter.framework.operator.OperatorTaskHooks

/**
 * How much of an agent's context window a brief may take, and how a brief is made to fit.
 *
 * Costs follow [TokenEstimator], the estimate the chat uses against a model's context window,
 * plus what quoting adds: every line of a [DataBlock] starts with a quote mark, and `&`, `<`
 * and `>` are escaped. A brief that does not fit gives each variable piece (the owner's
 * objective, every framed result, review and question) a share of what is left after its
 * fixed text: a piece smaller than its share keeps all of it, and what it leaves is shared
 * among the larger ones. A piece over its share is cut, with a note saying how much is missing.
 */
internal object TextBudget {
    /** The context window assumed for a model that does not state one: small, so a brief also fits most on-device models. */
    const val DEFAULT_CONTEXT_TOKENS = 8_192

    /** Room kept for the reply when the agent sets no output limit, as the chat keeps it. */
    const val DEFAULT_REPLY_ROOM = 512

    /** A brief never gets less than this: with a window that small the run fails as too long, which is reported. */
    const val MIN_BRIEF_TOKENS = 1_000

    /**
     * The most a brief takes however large the window: about 480 KB of Latin text, so the brief
     * stored in the task's transcript stays well within what one stored row may hold.
     */
    const val MAX_BRIEF_TOKENS = 120_000

    /** The least a piece keeps, its cut note included, however many pieces share a brief. */
    private const val MIN_SHARE = 40.0

    /** What a line break costs inside a DataBlock: the break and the quote mark and space that open the next line. */
    const val FRAMED_LINE = 1.5

    /** What a line break costs in the quoted objective ("> " opens every line). */
    const val QUOTED_LINE = 0.75

    /** One variable part of a brief; [perLine] is what each line break costs where the brief puts it. */
    data class Piece(val text: String, val perLine: Double = FRAMED_LINE)

    fun cutNote(omitted: Int): String = "[cut: $omitted characters omitted]"

    /**
     * Renders a brief from its [pieces] with [render], which gets the pieces' texts in order.
     * When the whole brief is over [budget] tokens, the pieces are cut to their shares.
     */
    fun fit(budget: Int, pieces: List<Piece>, render: (List<String>) -> String): String {
        val whole = render(pieces.map { it.text })
        if (TokenEstimator.tokens(whole) <= budget || pieces.isEmpty()) return whole
        val fixed = TokenEstimator.tokens(render(pieces.map { "" }))
        val available = (budget - fixed).toDouble().coerceAtLeast(MIN_SHARE * pieces.size)
        val costs = pieces.map { cost(it.text, it.perLine) }
        val shares = allocate(costs, available)
        return render(pieces.mapIndexed { i, piece -> if (costs[i] <= shares[i]) piece.text else cut(piece.text, shares[i], piece.perLine) })
    }

    /** Estimated tokens of [text] where every line break costs [perLine]. */
    fun cost(text: String, perLine: Double = FRAMED_LINE): Double {
        var total = 0.0
        for (c in text) total += charCost(c, perLine)
        return total
    }

    /** [text] cut to at most [maxTokens], with a note on what was cut when anything was. */
    fun cut(text: String, maxTokens: Double, perLine: Double = FRAMED_LINE): String {
        if (cost(text, perLine) <= maxTokens) return text
        val room = maxTokens - cost(cutNote(text.length), perLine) - perLine
        var spent = 0.0
        var end = 0
        while (end < text.length) {
            val next = spent + charCost(text[end], perLine)
            if (next > room) break
            spent = next
            end++
        }
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate()) end--
        val kept = text.substring(0, end).trimEnd()
        val note = cutNote(text.length - kept.length)
        return if (kept.isEmpty()) note else "$kept\n$note"
    }

    /** Shares of [total] for pieces costing [costs]: none gets more than it needs, the rest is split evenly. */
    fun allocate(costs: List<Double>, total: Double): List<Double> {
        if (costs.sum() <= total) return costs
        val shares = DoubleArray(costs.size)
        var left = total
        val order = costs.indices.sortedBy { costs[it] }
        order.forEachIndexed { position, index ->
            val even = left / (order.size - position)
            val share = minOf(costs[index], even).coerceAtLeast(MIN_SHARE)
            shares[index] = share
            left = (left - share).coerceAtLeast(0.0)
        }
        return shares.toList()
    }

    private fun charCost(c: Char, perLine: Double): Double = when {
        c == '\n' -> perLine
        c == '&' -> 1.25
        c == '<' || c == '>' -> 1.0
        c.code < 0x250 -> 0.25
        else -> 1.0
    }
}

/**
 * Caps on what the orchestrator stores. The task store refuses a row holding more than 1 MiB
 * of UTF-8 text (and the message store a message), which would fail the objective; what an
 * agent or a provider wrote is therefore cut before it is stored, with a note where it was cut.
 */
internal object StoredText {
    /** A task's result: well below the store's row limit, which also holds the instructions and reviews. */
    const val MAX_RESULT_BYTES = 512 * 1024

    /** The final document assembled when the Director could not write it. */
    const val MAX_DOCUMENT_BYTES = 256 * 1024

    const val MAX_ERROR_CHARS = 4_000
    const val MAX_DETAIL_CHARS = 1_000

    /** Problems quoted back to the Director, and the reasons and details written to the objective and the log. */
    const val MAX_REASON_CHARS = 2_000

    /** [text] in at most [maxBytes] of UTF-8, cut with a note (which [note] words from the number of characters omitted). */
    fun capBytes(text: String, maxBytes: Int, note: (Int) -> String = TextBudget::cutNote): String {
        if (utf8Bytes(text) <= maxBytes) return text
        val room = maxBytes - utf8Bytes(note(text.length)) - 2
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val c = text[end]
            val size = when {
                c.isHighSurrogate() && end + 1 < text.length && text[end + 1].isLowSurrogate() -> 4
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            if (bytes + size > room) break
            bytes += size
            end += if (size == 4) 2 else 1
        }
        val kept = text.substring(0, end).trimEnd()
        return "$kept\n\n${note(text.length - kept.length)}"
    }

    /** [text] in at most [maxChars] characters, cut with a note. */
    fun capChars(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val note = TextBudget.cutNote(0)
        var end = (maxChars - note.length - 12).coerceAtLeast(0)
        if (end > 0 && text[end - 1].isHighSurrogate()) end--
        val kept = text.substring(0, end).trimEnd()
        return "$kept ${TextBudget.cutNote(text.length - kept.length)}"
    }

    fun utf8Bytes(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> { bytes += 4; i++ }
                c.code < 0x80 -> bytes += 1
                c.code < 0x800 -> bytes += 2
                else -> bytes += 3
            }
            i++
        }
        return bytes
    }

    /** [task] with its result, error and status line cut to what may be stored. */
    fun capped(task: TaskRecord): TaskRecord {
        val result = task.result?.let { capBytes(it, MAX_RESULT_BYTES) { n -> "[cut: $n characters omitted; the text was too large to store]" } }
        val error = task.error?.let { capChars(it, MAX_ERROR_CHARS) }
        val detail = task.statusDetail?.let { capChars(it, MAX_DETAIL_CHARS) }
        return if (result === task.result && error === task.error && detail === task.statusDetail) task
        else task.copy(result = result, error = error, statusDetail = detail)
    }
}

/** The task store as the orchestrator writes it: every task is capped by [StoredText.capped] on its way in. */
internal class CappedTaskStore(private val inner: TaskRepository) : TaskRepository by inner {
    override suspend fun upsert(task: TaskRecord) = inner.upsert(StoredText.capped(task))

    override suspend fun upsertAll(tasks: List<TaskRecord>) = inner.upsertAll(tasks.map(StoredText::capped))

    override suspend fun update(id: TaskId, transform: (TaskRecord) -> TaskRecord): TaskRecord? =
        inner.update(id) { StoredText.capped(transform(it)) }
}

/**
 * The task store as the orchestrator writes it, telling [hooks] about every task written with a
 * finished status, whoever finished it (a step, a limit, the owner, the recovery): its pending
 * asks are withdrawn and its TASK and ONCE grants revoked. A failure there never fails the write.
 */
internal class TaskEndingStore(private val inner: TaskRepository, private val hooks: OperatorTaskHooks) : TaskRepository by inner {
    override suspend fun upsert(task: TaskRecord) {
        inner.upsert(task)
        ended(listOf(task))
    }

    override suspend fun upsertAll(tasks: List<TaskRecord>) {
        inner.upsertAll(tasks)
        ended(tasks)
    }

    override suspend fun update(id: TaskId, transform: (TaskRecord) -> TaskRecord): TaskRecord? =
        inner.update(id, transform)?.also { ended(listOf(it)) }

    private suspend fun ended(tasks: List<TaskRecord>) {
        for (task in tasks) {
            if (!task.status.isFinished) continue
            try {
                hooks.taskEnded(task)
            } catch (e: Exception) {
                rethrowIfCancelled(e)
            }
        }
    }
}
