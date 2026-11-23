package com.aicontrolcenter.framework.orchestration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One task of the Director's plan, as validated. */
internal data class PlannedTask(
    val key: String,
    val title: String,
    val instructions: String,
    val completionCriteria: List<String>,
    /** Lower-case skill tags the selector matches against agents' skills. */
    val skills: List<String>,
    /** An agent's name, when the Director named one. */
    val assignee: String?,
    val dependsOn: List<String>,
    val needsReview: Boolean,
)

/** The Director's plan for an objective. */
internal data class ObjectivePlan(
    val interpretation: String,
    val tasks: List<PlannedTask>,
    val finalInstructions: String,
)

internal sealed interface PlanReading {
    data class Valid(val plan: ObjectivePlan) : PlanReading

    /** Every problem found, each a sentence the Director can act on when it is asked again. */
    data class Invalid(val problems: List<String>) : PlanReading
}

/**
 * Reads and validates the Director's plan. Tolerant in form ([JsonReply], alternative field
 * spellings, a string where a list was asked for) and strict in substance: 1..[maxTasks]
 * tasks, unique keys, dependencies only on keys of the plan, no cycles, and instructions for
 * every task. All problems are reported at once, so a single retry can fix them all.
 *
 * Lengths are capped so that one plan cannot fill the task store: an over-long title or
 * instruction text is cut, which is not an error.
 */
internal object PlanParser {
    const val MAX_TITLE = 100
    const val MAX_INSTRUCTIONS = 4_000
    const val MAX_CRITERIA = 8
    const val MAX_CRITERION = 300
    const val MAX_SKILLS = 8
    const val MAX_KEY = 40
    const val MAX_PROBLEMS = 10
    const val DEFAULT_FINAL_INSTRUCTIONS = "Combine the results into one clear Markdown document that answers the objective directly."

    /**
     * [reviewsOn]: the owner wants reviews, so each task marked for review needs a second task
     * place for its review, and the plan must leave room for them all.
     */
    fun read(text: String, maxTasks: Int, reviewsOn: Boolean = false): PlanReading {
        val root = JsonReply.firstObject(text) ?: return PlanReading.Invalid(listOf("The answer contained no JSON object."))
        val body = (root.field("plan") as? JsonObject)?.takeIf { root.field("tasks") == null } ?: root
        val rawTasks = body.field("tasks") as? JsonArray
            ?: return PlanReading.Invalid(listOf("The JSON object has no \"tasks\" list."))
        // Too many tasks: the count is the problem; examining each would only make the answer longer.
        if (rawTasks.size > maxTasks) return PlanReading.Invalid(listOf("The plan has ${rawTasks.size} tasks; at most $maxTasks are allowed. Combine or drop tasks."))
        val problems = ArrayList<String>()
        if (rawTasks.isEmpty()) problems += "The plan has no tasks; it needs at least one."

        val tasks = ArrayList<PlannedTask>()
        val seen = HashSet<String>()
        rawTasks.forEachIndexed { index, element ->
            val number = index + 1
            val item = element as? JsonObject
            if (item == null) {
                problems += "Task $number is not a JSON object."
                return@forEachIndexed
            }
            val key = item.field("key", "id").text()?.let(::normaliseKey)?.ifEmpty { null }
            if (key == null) {
                problems += "Task $number has no \"key\"."
                return@forEachIndexed
            }
            if (!seen.add(key)) problems += "The key \"$key\" is used by more than one task; every key must be unique."
            val instructions = item.field("instructions", "description").text()?.take(MAX_INSTRUCTIONS)
            if (instructions == null) problems += "Task \"$key\" has no instructions."
            val title = item.field("title", "name").text()?.lineSequence()?.first()?.take(MAX_TITLE)
                ?: instructions?.lineSequence()?.first()?.take(MAX_TITLE)
                ?: "Task $key"
            tasks += PlannedTask(
                key = key,
                title = title,
                instructions = instructions.orEmpty(),
                completionCriteria = item.field("completion_criteria", "completionCriteria", "criteria").texts()
                    .map { it.take(MAX_CRITERION) }.take(MAX_CRITERIA),
                skills = item.field("skills").texts().map { it.lowercase() }.distinct().take(MAX_SKILLS),
                assignee = item.field("assignee", "agent").text(),
                dependsOn = item.field("depends_on", "dependsOn", "dependencies").texts().map(::normaliseKey).filter { it.isNotEmpty() }.distinct(),
                needsReview = item.field("needs_review", "needsReview", "review").flag() ?: false,
            )
        }
        problems += dependencyProblems(tasks)
        val reviewed = if (reviewsOn) tasks.count { it.needsReview } else 0
        if (problems.isEmpty() && tasks.size + reviewed > maxTasks) {
            problems += "The plan has ${tasks.size} tasks and marks $reviewed for review; each review takes a task place of its own, so it needs " +
                "${tasks.size + reviewed} of the $maxTasks places. Combine tasks or mark fewer for review."
        }
        if (problems.isNotEmpty()) return PlanReading.Invalid(capped(problems))
        return PlanReading.Valid(
            ObjectivePlan(
                interpretation = body.field("interpretation").text().orEmpty().take(MAX_INSTRUCTIONS),
                tasks = tasks,
                finalInstructions = body.field("final_instructions", "finalInstructions").text()?.take(MAX_INSTRUCTIONS)
                    ?: DEFAULT_FINAL_INSTRUCTIONS,
            ),
        )
    }

    /**
     * A key as the plan uses it: on one line, without surrounding space, at most [MAX_KEY]
     * characters. Task keys and the keys in depends_on are read the same way, so a long key
     * still matches the dependencies that name it.
     */
    fun normaliseKey(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MAX_KEY).trimEnd()

    /** At most [MAX_PROBLEMS] problems, then how many more there were. */
    private fun capped(problems: List<String>): List<String> =
        if (problems.size <= MAX_PROBLEMS) problems
        else problems.take(MAX_PROBLEMS) + "And ${problems.size - MAX_PROBLEMS} more problems of the same kinds."

    /** Unknown and self dependencies, and the first cycle found (named by its keys). */
    private fun dependencyProblems(tasks: List<PlannedTask>): List<String> {
        val keys = tasks.map { it.key }.toSet()
        val problems = ArrayList<String>()
        for (task in tasks) {
            for (dependency in task.dependsOn) {
                when {
                    dependency == task.key -> problems += "Task \"${task.key}\" depends on itself."
                    dependency !in keys -> problems += "Task \"${task.key}\" depends on \"$dependency\", which is not a key of this plan."
                }
            }
        }
        if (problems.isNotEmpty()) return problems
        findCycle(tasks)?.let { problems += "The dependencies form a cycle: ${it.joinToString(" → ")}. A task can only depend on tasks that can finish before it." }
        return problems
    }

    private fun findCycle(tasks: List<PlannedTask>): List<String>? {
        val edges = tasks.associate { it.key to it.dependsOn }
        val state = HashMap<String, Int>() // 1 = on the path, 2 = done
        val path = ArrayList<String>()
        fun visit(key: String): List<String>? {
            when (state[key]) {
                1 -> return path.subList(path.indexOf(key), path.size) + key
                2 -> return null
            }
            state[key] = 1
            path += key
            for (next in edges[key].orEmpty()) visit(next)?.let { return it }
            path.removeAt(path.size - 1)
            state[key] = 2
            return null
        }
        for (task in tasks) visit(task.key)?.let { return it }
        return null
    }
}
