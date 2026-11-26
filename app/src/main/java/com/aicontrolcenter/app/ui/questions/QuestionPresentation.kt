package com.aicontrolcenter.app.ui.questions

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.models.isSimulator
import com.aicontrolcenter.app.ui.approvals.hiddenCharacterWarning
import com.aicontrolcenter.app.ui.approvals.inWords
import com.aicontrolcenter.app.ui.approvals.isOpenAt
import com.aicontrolcenter.app.ui.approvals.timingOf
import com.aicontrolcenter.app.ui.approvals.visible
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.tasks.AgentFaceUi
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.operator.AnswerCheck
import com.aicontrolcenter.core.operator.AnswerRisk
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.PreviewText
import com.aicontrolcenter.core.operator.QuestionRules
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.framework.operator.DefaultOwnerRequests
import com.aicontrolcenter.core.registry.effectiveLocality
import java.time.ZoneId

/*
 * What the question screen shows of an agent's question to the owner (rule 13, Tier B): pure functions over the request
 * and one registry snapshot, so every way a question can look is unit-tested without a screen.
 *
 * Everything the agent wrote (the question, its suggested answers, its reason) is untrusted text: it is shown quoted, as
 * the agent wrote it, with hidden or direction-changing characters made visible the way the approval screens do it
 * ([visible], [inWords]), and never as a button that could pass for an answer of the app's own.
 */

/** One question, as the question screen and the "Waiting for you" list show it. */
@Immutable
data class QuestionUi(
    val id: RequestId,
    val agent: AgentFaceUi,
    /** "A question from Researcher". */
    val title: String,
    /** The agent's question, exactly, with hidden characters shown as marks. */
    val question: String,
    /** The answers the agent suggests, exactly as written; the owner may copy one into the answer. */
    val options: List<String>,
    /** The agent's own reason, when it gave one. */
    val reason: String?,
    /** Still waiting for the owner, and not past its time. */
    val isOpen: Boolean,
    val status: StatusLabel,
    /** "18 min left" while it waits; when it closed afterwards. */
    val timing: String,
    val askedAt: String,
    /** What became of it, in a sentence, once it is closed; null while it waits. */
    val outcome: String?,
    /** The owner's own answer, once answered with words. */
    val answer: String?,
    /** Where the owner's answer goes, in plain words. */
    val whereAnswerGoes: String,
    /** True when the answer leaves this phone (a model on the internet or on the owner's network). */
    val answerLeavesPhone: Boolean,
    /** Set when the question holds a hidden character: what it can do, in words. */
    val hiddenWarning: String?,
    /** How long the agent waits, for the line under the buttons. */
    val waitLine: String?,
    val taskId: TaskId?,
    val rootId: TaskId?,
)

/** What the answer typed so far looks like (rule 32), said before it is sent; the app refuses it again when it is sent. */
enum class AnswerHint(val text: String) {
    /** A key or password: never sent, whatever the owner taps. */
    KEY(
        "This looks like a key or a password. Agents never need your keys or passwords, so this cannot be sent. " +
            "Write your answer without it.",
    ),

    /** A code: sent only after a second tap. */
    CODE("This looks like a code, such as one from a text message or your bank. You are asked once more before it is sent."),
    ;

    companion object {
        /** The hint for [answer] to [question], or null when it looks like ordinary words. */
        fun of(question: String, answer: String): AnswerHint? {
            if (answer.isBlank()) return null
            return when (AnswerCheck.classify(question, answer.take(QuestionRules.MAX_ANSWER_CHARS))) {
                AnswerRisk.KEY -> KEY
                AnswerRisk.CODE -> CODE
                AnswerRisk.NONE -> null
            }
        }
    }
}

/** The screen's fixed words (rule 13). */
object QuestionTexts {
    /** Always shown on the question screen, above the question. */
    const val NOT_A_PERMISSION = "This is not a permission. Your answer is only text for the agent."

    /** What answering does and does not do. */
    const val ALLOWS_NOTHING = "An answer gives no permission and approves nothing. Requests that need your approval still ask you."

    const val SEND = "Send"
    const val SEND_ANYWAY = "Send it anyway"

    /** The button that sends no words: "Let Researcher decide" (D3A-12f: "Decide yourself" read as an order to the owner). */
    fun letDecide(agentName: String): String = "Let $agentName decide"

    /** The code warning (rule 32), with the screen's curly quotes around the button's name (D3A-12e). */
    val CODE_WARNING: String = DefaultOwnerRequests.CODE_ASK_AGAIN.replace("\"$SEND_ANYWAY\"", "“$SEND_ANYWAY”")
    const val USE_THIS_ANSWER = "Use this answer"

    /** Rule 32: after any answer, the objective's next tool call that sends something off the phone asks first. */
    const val AFTER_ANSWER =
        "After you answer, the team asks you first before it next uses a tool that sends something off the phone."
}

/** The agent's name for [request]: as registered now, or as it was when it asked. */
fun questionAgent(request: OwnerRequest, snapshot: RegistrySnapshot?): AgentFaceUi {
    val profile = snapshot?.agentsById?.get(request.agentId)?.profile
    return AgentFaceUi(request.agentId, shown(profile?.name ?: request.agentName ?: "An agent"), profile?.colorIndex ?: 0)
}

/** "A question from Researcher". */
fun questionTitle(agentName: String): String = "A question from $agentName"

/** Builds what the screens show of the QUESTION [request] at [now]. */
fun questionUi(request: OwnerRequest, snapshot: RegistrySnapshot?, now: Long, zone: ZoneId): QuestionUi {
    require(request.kind == OwnerRequestKind.QUESTION) { "Only a question is shown on the question screen." }
    val agent = questionAgent(request, snapshot)
    val open = request.isOpenAt(now)
    val question = request.question.orEmpty()
    val marked = listOf(visible(question)) + request.options.map(::visible) + listOfNotNull(request.reason?.let { PreviewText.exact(it) })
    val (where, leaves) = whereAnswerGoes(request, snapshot, agent.name)
    val minutesLeft = ((request.expiresAt - now + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(1)
    return QuestionUi(
        id = request.id,
        agent = agent,
        title = questionTitle(agent.name),
        question = shown(question),
        options = request.options.map(::shown),
        reason = request.reason?.let { inWords(PreviewText.exact(it)) },
        isOpen = open,
        status = questionStatus(request, now),
        timing = timingOf(request, now, zone),
        askedAt = formatCompactTimestamp(request.createdAt, now, zone),
        outcome = if (open) null else questionOutcome(request, agent.name),
        answer = request.answer?.takeIf { request.status == OwnerRequestStatus.APPROVED },
        whereAnswerGoes = where,
        answerLeavesPhone = leaves,
        hiddenWarning = hiddenCharacterWarning(marked),
        waitLine = if (open) "If you do not answer within ${minutesText(minutesLeft)}, ${agent.name} decides itself." else null,
        taskId = request.taskId,
        rootId = request.rootId,
    )
}

/** Where a question stands, for the owner; never in an approval's words ("Allowed", "Approved"). */
fun questionStatus(request: OwnerRequest, now: Long): StatusLabel = when (request.status) {
    OwnerRequestStatus.PENDING ->
        if (request.isExpiredAt(now)) StatusLabel("No answer in time", StatusTone.DISABLED) else StatusLabel("Waiting for your answer", StatusTone.ATTENTION)
    OwnerRequestStatus.APPROVED ->
        if (request.answer == null) StatusLabel("You let it decide", StatusTone.READY) else StatusLabel("Answered", StatusTone.READY)
    OwnerRequestStatus.EXPIRED -> StatusLabel("No answer in time", StatusTone.DISABLED)
    OwnerRequestStatus.CANCELLED -> StatusLabel("Withdrawn", StatusTone.DISABLED)
    // A question is never refused (OwnerRequest's own rule); shown plainly should one ever be stored so.
    OwnerRequestStatus.DENIED -> StatusLabel("Closed", StatusTone.DISABLED)
}

/** What became of a closed question, in one sentence. */
fun questionOutcome(request: OwnerRequest, agentName: String): String = when (request.status) {
    OwnerRequestStatus.APPROVED ->
        if (request.answer == null) "You let $agentName decide itself." else "You answered. $agentName read your answer as text."
    OwnerRequestStatus.PENDING, OwnerRequestStatus.EXPIRED -> "Nobody answered in time, so $agentName decided itself."
    OwnerRequestStatus.CANCELLED ->
        "This question was withdrawn before you answered" + (request.decisionNote?.let { ": " + inWords(PreviewText.exact(it)) } ?: ".")
    OwnerRequestStatus.DENIED -> "This question can no longer be answered."
}

/**
 * Where the owner's answer goes, in plain words, and whether that is off the phone: to the model the agent uses, with
 * its next request. A model on the internet or on the owner's network receives it; a model on the phone keeps it here.
 */
fun whereAnswerGoes(request: OwnerRequest, snapshot: RegistrySnapshot?, agentName: String): Pair<String, Boolean> {
    val entry = snapshot?.agentsById?.get(request.agentId)
    val modelId = (entry?.availability as? AgentAvailability.Ready)?.modelId
    val model = modelId?.let { snapshot.modelsById[it] }
    val provider = model?.let { snapshot.provider(it) }
    if (model == null || provider == null) {
        return "Your answer goes to the model $agentName uses, with its next request. If that model is on the internet, your " +
            "answer leaves this phone. Nothing else is sent by answering." to true
    }
    // "Grok 4 from xAI", but not "Simulator (not a real model) from Simulator" (D3A-12b).
    val name = if (model.displayName.contains(provider.name, ignoreCase = true)) model.displayName else "${model.displayName} from ${provider.name}"
    if (model.isSimulator) return "Your answer stays on this phone: $agentName uses the built-in simulator, which runs here." to false
    return when (provider.effectiveLocality) {
        Locality.ON_DEVICE -> "Your answer stays on this phone: $agentName's model, $name, runs here." to false
        Locality.LOCAL_NETWORK ->
            "Your answer leaves this phone: it goes to $agentName's model, $name, on a computer on your network, with its next request. " +
                "Nothing else is sent by answering." to true
        Locality.CLOUD ->
            "Your answer leaves this phone: it goes to $agentName's model, $name, on the internet, with its next request. " +
                "Nothing else is sent by answering." to true
    }
}

/** "18 min", "3 h 56 min". */
private fun minutesText(minutes: Long): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

/** Text from the request as the screens show it: hidden characters made visible, then named. */
private fun shown(text: String): String = inWords(visible(text))

private const val MINUTE_MS = 60_000L
