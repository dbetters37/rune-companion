package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.framework.orchestration.BriefWriter
import com.aicontrolcenter.framework.orchestration.DataBlock
import com.aicontrolcenter.framework.orchestration.OrchestrationProtocol
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The simulator's side of the Director protocol, so a whole objective runs offline: it
 * recognises the orchestrator's briefs by their [OrchestrationProtocol] marker and answers
 * each in the form asked for.
 *
 *  - A plan: one task per part of the objective (split at commas, "and" and sentence ends, at
 *    most the number of tasks allowed and five), each given skills of the starter team from its
 *    verb (research, compare, plan, write, code). Each task depends on the one before; all but
 *    the first are marked for review.
 *  - A work result restating the assignment, what it built on, and the review issues a revision
 *    addresses.
 *  - A review verdict: approved, except that the second task of the plan is sent back once in
 *    its first round, so the revision path can be seen. Deterministic: the same brief always
 *    gets the same verdict.
 *  - An arbitration that accepts, an answer to a teammate, and the final Markdown document
 *    assembled from the results in the brief.
 *
 * Everything is labelled as simulated, inside the JSON as well as in the text.
 */
internal object SimulatedTeamwork {
    private const val MAX_TASKS = 5
    private val CLAUSE_BREAK = Regex("[.;!?\\n]+|,\\s*(?:and\\s+|then\\s+)?|\\s+and\\s+then\\s+|\\s+then\\s+")
    private val AT_MOST = Regex("at most (\\d+) tasks")
    private val AGENT_NAME = Regex("^You are (.+?), an? ")

    private class Kind(val verbs: Regex, val skills: List<String>, val title: String)

    private val KINDS = listOf(
        Kind(Regex("\\b(compare|comparison|evaluate|assess|analy[sz]e|weigh|rank|contrast)\\b", RegexOption.IGNORE_CASE), listOf("analysis", "comparison"), "Compare"),
        Kind(Regex("\\b(plan|strategy|roadmap|design|schedule)\\b", RegexOption.IGNORE_CASE), listOf("planning", "analysis"), "Plan"),
        Kind(Regex("\\b(write|document|organi[sz]e|report|summari[sz]e|draft|present|compile)\\b", RegexOption.IGNORE_CASE), listOf("writing", "documentation"), "Write"),
        Kind(Regex("\\b(code|script|program|debug|implement)\\b", RegexOption.IGNORE_CASE), listOf("coding", "debugging"), "Build"),
        Kind(Regex("\\b(research|investigate|find|gather|look|learn|explore|study)\\b", RegexOption.IGNORE_CASE), listOf("research", "fact-finding"), "Research"),
    )

    /** The reply to [request] when its newest user message is a protocol brief, or null. */
    fun reply(request: ChatRequest): String? {
        val users = request.messages.filter { it.role == ChatRole.USER }
        val latest = users.lastOrNull()?.text ?: return null
        val marker = OrchestrationProtocol.markerIn(latest) ?: return null
        val agent = request.messages.firstOrNull { it.role == ChatRole.SYSTEM }?.text?.let { AGENT_NAME.find(it)?.groupValues?.get(1) } ?: "The agent"
        // A corrected plan is asked for in a short follow-up; the objective is in the first brief.
        val brief = if (marker == OrchestrationProtocol.PLAN && BriefWriter.H_OBJECTIVE !in latest) {
            users.lastOrNull { BriefWriter.H_OBJECTIVE in it.text }?.text ?: latest
        } else latest
        return when (marker) {
            OrchestrationProtocol.PLAN -> plan(brief)
            OrchestrationProtocol.TASK -> work(brief, agent)
            OrchestrationProtocol.REVIEW -> verdict(brief)
            OrchestrationProtocol.ARBITRATE -> buildJsonObject {
                put("simulated", true)
                put("decision", "accept")
                put("reason", "Simulated decision: the result is used as it is, with the reviewer's remaining issues noted.")
            }.toString()
            OrchestrationProtocol.SYNTHESIS -> document(brief)
            OrchestrationProtocol.ASK -> answer(brief, agent)
            else -> null
        }
    }

    private fun plan(brief: String): String {
        val objective = objective(brief)
        val allowed = AT_MOST.find(brief)?.groupValues?.get(1)?.toIntOrNull() ?: MAX_TASKS
        val clauses = CLAUSE_BREAK.split(objective).map { it.trim().trimEnd(',') }.filter { it.length >= 3 }
            .take(minOf(allowed, MAX_TASKS).coerceAtLeast(1))
            .ifEmpty { listOf(objective.ifBlank { "the objective" }) }
        return buildJsonObject {
            put("simulated", true)
            put("interpretation", "Simulated plan: the owner wants this done: ${shorten(objective, 200)}")
            put("tasks", buildJsonArray {
                clauses.forEachIndexed { index, clause ->
                    val kind = KINDS.firstOrNull { it.verbs.containsMatchIn(clause) } ?: KINDS.last()
                    add(buildJsonObject {
                        put("key", "t${index + 1}")
                        put("title", shorten(clause.replaceFirstChar { it.uppercase() }, 80))
                        put("instructions", "(Simulated) ${clause.replaceFirstChar { it.uppercase() }}. Report what you did and what you found.")
                        put("completion_criteria", buildJsonArray {
                            add("Addresses: ${shorten(clause, 120)}")
                            add("States what remains uncertain")
                        })
                        put("skills", buildJsonArray { kind.skills.forEach { add(it) } })
                        put("depends_on", buildJsonArray { if (index > 0) add("t$index") })
                        put("needs_review", index > 0)
                    })
                }
            })
            put("final_instructions", "Combine the results into one Markdown document, in plan order, and say what is simulated.")
        }.toString()
    }

    private fun work(brief: String, agent: String): String {
        val blocks = DataBlock.parseAll(brief)
        val assignment = blocks.firstOrNull { it.label.startsWith("Assignment") }?.content.orEmpty()
        val title = assignment.lineSequence().firstOrNull { it.startsWith(BriefWriter.FACT_TITLE) }?.removePrefix(BriefWriter.FACT_TITLE)?.trim() ?: "the task"
        val criteria = assignment.substringAfter("Completion criteria:", "").lines().map { it.trim().removePrefix("- ").trim() }.filter { it.isNotEmpty() }
        val sources = blocks.filter { it.label.startsWith("Result of") }.map { it.label }
        val review = blocks.firstOrNull { it.label.startsWith("Review by") }?.content.orEmpty()
        val issues = review.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ") }
        return buildString {
            appendLine(Simulator.DISCLOSURE)
            appendLine()
            appendLine("## $title")
            appendLine("$agent worked on this task (simulated).")
            if (sources.isNotEmpty()) appendLine("- Built on: ${sources.joinToString("; ")}.")
            criteria.forEach { appendLine("- Criterion met (simulated): $it") }
            if (issues.isNotEmpty()) {
                appendLine()
                appendLine("Revised to address the review:")
                issues.forEach { appendLine("- $it") }
            }
            appendLine()
            append("A real model would do the actual work here.")
        }
    }

    private fun verdict(brief: String): String {
        val round = factInt(brief, BriefWriter.FACT_ROUND) ?: 1
        val position = brief.lineSequence().firstOrNull { it.startsWith(BriefWriter.FACT_POSITION) }?.removePrefix(BriefWriter.FACT_POSITION)
        val index = position?.substringBefore(" of ")?.trim()?.toIntOrNull() ?: 1
        val size = position?.substringAfter(" of ", "")?.trim()?.toIntOrNull() ?: 1
        val revise = round == 1 && index == minOf(2, size)
        return buildJsonObject {
            put("simulated", true)
            put("decision", if (revise) "revise" else "approved")
            put("summary", if (revise) "Simulated review: the result needs one revision before it can be used." else "Simulated review: the result meets its completion criteria.")
            put("issues", buildJsonArray { if (revise) add("State explicitly how each completion criterion is met (simulated issue).") })
        }.toString()
    }

    private fun document(brief: String): String {
        val objective = objective(brief)
        val blocks = DataBlock.parseAll(brief)
        val results = blocks.filter { it.label.startsWith(BriefWriter.RESULT_LABEL) }
        val missing = blocks.firstOrNull { it.label == BriefWriter.NOT_COMPLETED_LABEL }?.content?.lines()?.filter { it.isNotBlank() }.orEmpty()
        return buildString {
            appendLine("# ${shorten(objective.lineSequence().firstOrNull().orEmpty().ifBlank { "Final result" }, 80)}")
            appendLine()
            appendLine("> Simulated document. No real AI model wrote this; it was assembled from the team's simulated results.")
            appendLine()
            appendLine("## Summary")
            appendLine("The team completed ${results.size} of ${results.size + missing.size} tasks.")
            for (result in results) {
                appendLine()
                appendLine("## ${result.label.substringAfter(": ", result.label)}")
                appendLine(result.content.lines().filter { it.trim() != Simulator.DISCLOSURE }.joinToString("\n").trim())
            }
            if (missing.isNotEmpty()) {
                appendLine()
                appendLine("## Not completed")
                missing.forEach { appendLine("- $it") }
            }
        }.trimEnd()
    }

    private fun answer(brief: String, agent: String): String {
        val question = DataBlock.parseAll(brief).firstOrNull { it.label.startsWith(BriefWriter.QUESTION_LABEL) }?.content.orEmpty()
        return "${Simulator.DISCLOSURE}\n\n$agent was asked: \"${shorten(question.lineSequence().firstOrNull().orEmpty(), 200)}\". A real model would answer from its knowledge here."
    }

    /** The owner's objective, from its quoted lines under the objective heading. */
    private fun objective(brief: String): String =
        brief.substringAfter(BriefWriter.H_OBJECTIVE, "").lines().drop(1).takeWhile { it.startsWith(">") }
            .joinToString("\n") { it.removePrefix(">").removePrefix(" ") }.trim()

    private fun factInt(brief: String, prefix: String): Int? =
        brief.lineSequence().firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()?.toIntOrNull()

    private fun shorten(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
}
