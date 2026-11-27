package com.aicontrolcenter.app.ui.team

import com.aicontrolcenter.app.ui.components.RESUME_AFTER_LIMIT
import com.aicontrolcenter.app.ui.tasks.WaitTexts
import com.aicontrolcenter.app.work.WorkResult
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.framework.agents.ToolMenu

/*
 * The Team view's words, in one place: plain, short, the owner's. Agents' own words are always quoted ("…") and never
 * mixed into the app's sentences unmarked.
 */
object TeamWords {
    const val TITLE = "Team view"
    const val ALL_CURRENT = "All current"
    const val PICTURE = "Picture"
    const val LIST = "List"
    const val YOU = "You"
    const val FORMER_MEMBER = "A former team member"
    const val SHARED_MEMORY = "Shared memory"
    const val SHARED_SPACE = "Shared space"
    const val NOTHING_WAITS = "Nothing waits for you"
    const val NO_OBJECTIVE = "No objective is running now. Choose a finished one above to see how its team worked, or start one in Tasks."
    const val LIST_IS_EASIER =
        "A list of the same information is easier to read with large text or with TalkBack."
    const val SHOW_LIST = "Show the list"

    /** Said over the picture when the names do not fit under the circles (D3B-2). */
    const val NAMES_DONT_FIT = "The names do not fit in the picture for a team this size. The list shows everyone by name."
    const val TOO_MANY = "The team is too big for the picture, so the list shows everyone."
    const val CALM_NOTE = "Motion is off (Calm), so messages do not travel as dots. A line drawn thicker carried a message in the last minute."
    const val DOTS_NOTE = "A dot travels once along a line when a message is sent. A line drawn thicker carried a message in the last minute."
    const val TAP_NOTE = "Tap anyone, or a line's sign, for details. Drag to move, pinch to zoom."
    const val PAN_NOTE = "Drag to move the picture, pinch or use the buttons to zoom. Tap anyone for details, or a line's sign for its messages."
    const val WAITING_FOR_TEAM = "Waiting for the team"
    const val WAITING_FOR_CHECK = "Waiting for its work to be checked"
    const val PLANNING = "Planning the work"

    /** The polite summary above the picture: "2 working, 1 waiting for you, 1 waiting for a teammate". */
    fun summary(working: Int, waitingForYou: Int, waitingForTeammate: Int, paused: Int, stopped: Int): String = buildList {
        add("$working working")
        add("$waitingForYou waiting for you")
        add("$waitingForTeammate waiting for a teammate")
        if (paused > 0) add("$paused paused")
        if (stopped > 0) add("$stopped stopped")
    }.joinToString(", ")

    fun times(n: Int, one: String, many: String): String = if (n == 1) "1 $one" else "$n $many"

    /** "2 requests and 1 question wait for you", "1 question waits for you". */
    fun waitingCount(requests: Int, questions: Int): String {
        val parts = listOfNotNull(
            times(requests, "request", "requests").takeIf { requests > 0 },
            times(questions, "question", "questions").takeIf { questions > 0 },
        )
        if (parts.isEmpty()) return NOTHING_WAITS
        val verb = if (requests + questions == 1) "waits" else "wait"
        return parts.joinToString(" and ") + " $verb for you"
    }

    fun notInObjective(n: Int): String = if (n == 1) "1 other agent is not in this objective" else "$n other agents are not in this objective"

    fun leftOut(agents: Int, spaces: Int): String? = listOfNotNull(
        (if (agents == 1) "1 more agent is only in the list" else "$agents more agents are only in the list").takeIf { agents > 0 },
        (if (spaces == 1) "1 more space is only in Memory" else "$spaces more spaces are only in Memory").takeIf { spaces > 0 },
    ).joinToString(". ").ifEmpty { null }?.let { "$it." }

    /** "Started by routine “Morning news”", as the objective's own page says it. */
    fun routine(title: String?): String = title?.let { "Started by routine “$it”" } ?: "Started by a routine"

    /** Why a paused task or objective does not move, in a few words; null when [detail] says nothing the app knows. */
    fun pauseReason(detail: String?): String = when (detail) {
        DailyLimits.PAUSED_TEXT -> "Paused: today's limit is reached"
        WorkResult.STORAGE_PAUSED_DETAIL -> "Paused: the phone is almost full"
        OWNER_PAUSED -> "Paused by you"
        APP_PAUSED -> "Paused by the app"
        null -> "Paused"
        else -> detail.trimEnd('.')
    }

    /** What a paused objective's owner can do, in one sentence. */
    fun pauseAdvice(detail: String?): String = when (detail) {
        DailyLimits.PAUSED_TEXT -> RESUME_AFTER_LIMIT
        WorkResult.STORAGE_PAUSED_DETAIL -> "Free some room on the phone, then resume it on the objective's page."
        else -> "Nothing moves until you resume it on the objective's page."
    }

    /**
     * Waiting work that stopped with its objective ("Was waiting: …") or work that was interrupted: shown as stopped, in the
     * framework's own sentence, never as waiting with a time (handoff-w2afix.md INT-3).
     */
    fun stoppedReason(detail: String?): String = detail?.takeIf { it.isNotBlank() } ?: "Stopped. Resume the objective to go on."

    /** "Xai is not answering; trying again at 10:45." from a provider wait's status line (R4). */
    fun tryAgainReason(detail: String?): String? = WaitTexts.waitingFor(detail)

    /**
     * The step alone from the activity monitor's "Task: <title> · <step>" (or "Task: <title> (helping Analyst) · <step>"
     * while answering a teammate), as "Writing a reply" or "Helping Analyst · Writing a reply"; null when [detail] is not
     * about [title].
     */
    fun stepOf(detail: String, title: String): String? {
        var rest = detail.removePrefix("Task: $title").trim()
        if (rest == detail.trim()) return null
        if (rest.startsWith("(") && ")" in rest) {
            val aside = rest.substringBefore(")").removePrefix("(").trim().replaceFirstChar(Char::uppercase)
            val after = rest.substringAfter(")").trim().removePrefix("·").trim()
            rest = if (after.isEmpty()) aside else "$aside · $after"
        }
        return rest.removePrefix("·").trim().ifBlank { null }?.let(::toolNames)
    }

    /** A step as the owner reads it: the menu tool's function name is shown by its title (WP15 §3.1). */
    fun toolNames(step: String): String = step.replace(ToolMenu.FUNCTION_NAME, ToolMenu.TITLE)

    /**
     * What one member did along one link, from its own side: "asked Analyst 2 times", "got work from Director", "shared 3
     * memories into Team notes". [out] counts the exchanges it started towards [other], [incoming] those [other] started.
     */
    fun connection(kind: LinkKind, out: Int, incoming: Int, other: String, otherIsYou: Boolean): List<String> {
        fun n(count: Int) = if (count > 1) " $count times" else ""
        return buildList {
            when (kind) {
                LinkKind.ASSIGNS -> {
                    if (out > 0) add("gave ${other} work" + n(out))
                    if (incoming > 0) add("got work from $other" + n(incoming))
                }
                LinkKind.ASKS -> {
                    if (out > 0) add("asked $other" + n(out))
                    if (incoming > 0) add("was asked by $other" + n(incoming))
                }
                LinkKind.DELEGATES -> {
                    if (out > 0) add("handed work on to $other" + n(out))
                    if (incoming > 0) add("took on work from $other" + n(incoming))
                }
                LinkKind.REVIEWS -> {
                    if (out > 0) add("checked the work of $other" + n(out))
                    if (incoming > 0) add("had its work checked by $other" + n(incoming))
                }
                LinkKind.REPORTS -> {
                    if (out > 0) add("reported to $other" + n(out))
                    if (incoming > 0) add("got results from $other" + n(incoming))
                }
                LinkKind.SHARES -> if (out > 0) add("shared " + times(out, "memory", "memories") + " into $other")
                LinkKind.ASKS_YOU -> {
                    if (out > 0) add(if (otherIsYou) "asked you " + times(out, "question", "questions") else "asked $other")
                    if (incoming > 0) add(times(incoming, "question", "questions") + " from $other")
                }
                LinkKind.TELLS_YOU -> {
                    if (out > 0) add(if (otherIsYou) "told you how its work went" else "told $other")
                    if (incoming > 0) add("news from $other")
                }
                LinkKind.NOTES -> {
                    if (out > 0) add("wrote " + times(out, "note", "notes") + " to $other")
                    if (incoming > 0) add("got " + times(incoming, "note", "notes") + " from ${if (otherIsYou) "you" else other}")
                }
            }
        }
    }

    /**
     * What a link carries, for its sign's spoken description and its sheet's title: "Questions between Analyst and
     * Researcher, 3 messages", "Questions for you with Writer, 2 messages".
     */
    fun linkDescription(kinds: List<LinkKind>, a: String, b: String, count: Int, withYou: String?): String {
        val phrases = kinds.distinct().map { it.phrase }
        val joined = when (phrases.size) {
            0 -> "messages"
            1 -> phrases.single()
            else -> phrases.dropLast(1).joinToString(", ") + " and " + phrases.last()
        }.replaceFirstChar(Char::uppercase)
        val between = if (withYou != null) "with $withYou" else "between $a and $b"
        return "$joined $between, " + times(count, "message", "messages")
    }

    /** The framework's pause lines (DefaultOrchestrator's, private there), as the screens recognise them. */
    const val OWNER_PAUSED = "Paused by the owner."
    const val APP_PAUSED = "Paused by the app."
}
