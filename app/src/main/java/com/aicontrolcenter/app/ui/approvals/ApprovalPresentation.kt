package com.aicontrolcenter.app.ui.approvals

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.tasks.AgentFaceUi
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.PreviewText
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.RiskLevel
import java.time.ZoneId

/*
 * Turns questions to the owner into what the approval screens, the chat and the task screens
 * show. Pure functions, so every way a request can look is unit-tested without a screen.
 *
 * Text written by an agent or a model is shown exactly: tools pass every value they put into a
 * summary or detail through PreviewText.exact, which writes line breaks, tabs, long runs of
 * spaces and invisible or direction-changing characters as visible markers (⏎, ⇥, ␣×5,
 * ⟨U+202E⟩). Here, any such character that still got through is made visible the same way
 * ([visible]), the agent's own reason is passed through PreviewText.exact, the common hidden
 * characters are then named in words ([inWords]: ⟨hidden: reverses text⟩), and the screens draw
 * every marker in a highlighted style ([previewMarks]), so none can pass for ordinary text. A
 * hidden character also raises a plain warning ([hiddenCharacterWarning]).
 */

/** An answer the owner can give, in the order the buttons are shown. */
enum class RequestChoice(val label: String) {
    ALLOW_ONCE("Allow just this once"),
    ALLOW_FOR_TASK("Allow for the rest of this task"),
    ALLOW_ALWAYS("Always allow…"),
    APPROVE("Approve"),
    DENY("Deny"),
}

/** One question to the owner, as the approval screens show it. */
@Immutable
data class RequestUi(
    val id: RequestId,
    val kind: OwnerRequestKind,
    /** "Approval needed" or "Permission needed" while it waits; "Approval request" or "Permission request" once closed. */
    val title: String,
    /** "Waiting for you", or how it ended. */
    val status: StatusLabel,
    /** Still waiting for an answer, and not past its expiry. */
    val isOpen: Boolean,
    /** Its status in the store; PENDING past the expiry reads as expired ([status]). */
    val storedStatus: OwnerRequestStatus,
    val agent: AgentFaceUi,
    /** Agents that delegated down to the asking agent, outermost first. */
    val chain: List<String>,
    /** The tool whose call asked; null when the agent asked for the permission itself. */
    val toolTitle: String?,
    /** The permission's name for the owner, e.g. "Change a shared folder". */
    val permission: String,
    val permissionDescription: String?,
    /** What exactly would happen, as the tool assessed it. */
    val summary: String,
    val details: List<String>,
    /** The folder or app it is about, when it is about one. */
    val target: String?,
    /** What [target] is: "Shared folder" or "App"; null without one. */
    val targetKind: String?,
    /** "Stays on this phone", or where information goes. */
    val dataGoesTo: String,
    val dataLeavesPhone: Boolean,
    /** The host or app information goes to, when it leaves the phone. */
    val dataGoesToHost: String?,
    val riskLevel: RiskLevel,
    val risk: StatusLabel,
    /** The agent's own words, shown quoted. */
    val reason: String?,
    /** "24 min left", "Expired", or when it was answered. */
    val timing: String,
    val raisedAt: String,
    val choices: List<RequestChoice>,
    /** What each answer in [choices] leads to, in one line, for the line under its button. */
    val consequences: Map<RequestChoice, String>,
    /** The approve button's words, naming the action: "Approve: delete 3 files". */
    val approveLabel: String,
    /** The action alone, for the confirmation: "Delete 3 files"; null when the summary does not start with one. */
    val action: String?,
    /** The "Always allow…" confirmation: title, text and confirming button. */
    val always: AlwaysUi,
    /** For a hard-to-undo action: the sentence shown right above the answers. */
    val hardToUndo: String?,
    /** Set when a hidden character is in what the owner is asked: what it can do, in words. */
    val hiddenWarning: String?,
    /** The owner's note, or why it expired or was cancelled. */
    val note: String?,
    /**
     * For a call on another app's screen: that the app comes back once the owner allows it, and
     * that the call acts only if the app still shows what is described. Null for other requests.
     */
    val returnsToApp: String? = null,
    val taskId: TaskId?,
    val rootId: TaskId?,
    val conversationId: ConversationId?,
) {
    val isConsequential: Boolean get() = riskLevel == RiskLevel.CONSEQUENTIAL
}

/** What the "Always allow…" confirmation says. */
@Immutable
data class AlwaysUi(val title: String, val message: String, val confirm: String)

/** Raised and still open at [now]: pending in the store and not past its expiry. */
fun OwnerRequest.isOpenAt(now: Long): Boolean = status == OwnerRequestStatus.PENDING && !isExpiredAt(now)

/** "Approval needed" or "Permission needed": a request still waiting. */
val OwnerRequestKind.title: String
    get() = when (this) {
        OwnerRequestKind.APPROVAL -> "Approval needed"
        OwnerRequestKind.PERMISSION -> "Permission needed"
    }

/** "Approval request" or "Permission request": a request that no longer waits for anyone. */
val OwnerRequestKind.closedTitle: String
    get() = when (this) {
        OwnerRequestKind.APPROVAL -> "Approval request"
        OwnerRequestKind.PERMISSION -> "Permission request"
    }

/**
 * How long a permission lasts, in the one set of words the app uses for it: on the answer
 * buttons ("Allow just this once", "Allow for the rest of this task", "Always allow…"), on a
 * closed request ([statusAt]) and in Tools › Allowed.
 */
val GrantScope.scopeLabel: String
    get() = when (this) {
        GrantScope.ONCE -> "Just once"
        GrantScope.TASK -> "For the task"
        GrantScope.ALWAYS -> "Always"
    }

/** Where a request is, for the owner. */
fun OwnerRequest.statusAt(now: Long): StatusLabel = when (status) {
    OwnerRequestStatus.PENDING ->
        if (isExpiredAt(now)) StatusLabel("Expired", StatusTone.DISABLED) else StatusLabel("Waiting for you", StatusTone.ATTENTION)
    OwnerRequestStatus.APPROVED -> when (decisionScope) {
        GrantScope.ONCE -> StatusLabel("Allowed just once", StatusTone.READY)
        GrantScope.TASK -> StatusLabel("Allowed for the task", StatusTone.READY)
        GrantScope.ALWAYS -> StatusLabel("Always allowed", StatusTone.READY)
        null -> StatusLabel("Approved", StatusTone.READY)
    }
    OwnerRequestStatus.DENIED -> StatusLabel("Denied", StatusTone.UNAVAILABLE)
    OwnerRequestStatus.EXPIRED -> StatusLabel("Expired", StatusTone.DISABLED)
    OwnerRequestStatus.CANCELLED -> StatusLabel("Cancelled", StatusTone.DISABLED)
}

/**
 * A call's or a tool's risk in words, with what it means for the owner. The one set of risk
 * words: approval screens, request cards, the Tools catalogue and the agent editor all use it.
 */
fun RiskLevel.toRiskLabel(): StatusLabel = when (this) {
    RiskLevel.SAFE -> StatusLabel("Reads only", StatusTone.READY, "Reads or looks something up; changes nothing.")
    RiskLevel.MODIFYING -> StatusLabel("Makes changes", StatusTone.INFO, "Changes something on this phone that you can see and undo.")
    RiskLevel.CONSEQUENTIAL -> StatusLabel(
        "Hard to undo", StatusTone.UNAVAILABLE,
        "It may be permanent, or reach other people, money or installed apps. You are asked every time.",
    )
}

/** The answers offered for [request]: a scope for a permission (the task one only inside a task), yes or no for an approval. */
fun choicesFor(request: OwnerRequest): List<RequestChoice> = when (request.kind) {
    // No first: the safe answer leads.
    OwnerRequestKind.APPROVAL -> listOf(RequestChoice.DENY, RequestChoice.APPROVE)
    OwnerRequestKind.PERMISSION -> request.offeredScopes.map {
        when (it) {
            GrantScope.ONCE -> RequestChoice.ALLOW_ONCE
            GrantScope.TASK -> RequestChoice.ALLOW_FOR_TASK
            GrantScope.ALWAYS -> RequestChoice.ALLOW_ALWAYS
        }
    } + RequestChoice.DENY
}

/** What the time means for [request] at [now]: time left while it waits, when it was answered afterwards. */
fun timingOf(request: OwnerRequest, now: Long, zone: ZoneId): String = when {
    request.isOpenAt(now) -> {
        val minutes = ((request.expiresAt - now + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(1)
        if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min left" else "$minutes min left"
    }
    request.status == OwnerRequestStatus.PENDING || request.status == OwnerRequestStatus.EXPIRED ->
        "Expired ${formatCompactTimestamp(request.expiresAt, now, zone)}"
    else -> "Closed ${formatCompactTimestamp(request.decidedAt ?: request.expiresAt, now, zone)}"
}

/** Builds what the screens show for [request]. */
fun requestUi(
    request: OwnerRequest,
    snapshot: RegistrySnapshot?,
    capabilityInfo: (Capability) -> CapabilityInfo?,
    now: Long,
    zone: ZoneId,
): RequestUi {
    val profile = snapshot?.agentsById?.get(request.agentId)?.profile
    val agent = AgentFaceUi(request.agentId, shown(profile?.name ?: request.agentName ?: "Removed agent"), profile?.colorIndex ?: 0)
    val chain = request.delegationChain.map { id -> shown(snapshot?.agentsById?.get(id)?.profile?.name ?: "Removed agent") }
    val info = capabilityInfo(request.capability)
    val open = request.isOpenAt(now)
    val permission = info?.title ?: request.capability.id
    val target = request.targetLabel?.let(::shown)
    val host = request.dataGoesTo?.let(::shown)
    // Read the hidden characters before they are named in words.
    val marked = listOf(visible(request.summary)) + request.details.map(::visible) + listOfNotNull(request.reason?.let { PreviewText.exact(it) })
    val summary = shown(request.summary)
    val details = request.details.map(::shown)
    val action = actionOf(summary)
    // Acting on another app's screen: that app may send what it is made to do off the phone.
    val actsOnScreen = request.capability == OperatorCapabilities.SCREEN_OPERATE
    val app = target ?: (request.constraint as? CapabilityConstraint.App)?.packageName
    val breadth = breadthOf(request.capability, permission, target)
    val notOnly = if (target == null && host != null) ", not only $host," else ""
    return RequestUi(
        id = request.id,
        kind = request.kind,
        title = if (open) request.kind.title else request.kind.closedTitle,
        status = request.statusAt(now),
        isOpen = open,
        storedStatus = request.status,
        agent = agent,
        chain = chain,
        toolTitle = request.toolTitle?.let(::shown),
        permission = permission,
        permissionDescription = info?.description,
        summary = summary,
        details = details,
        target = target,
        targetKind = when (request.constraint) {
            is CapabilityConstraint.Folder -> "Shared folder"
            is CapabilityConstraint.App -> "App"
            CapabilityConstraint.None -> null
        },
        dataGoesTo = host?.let { "Sent to $it" } ?: if (actsOnScreen) "May leave this phone through ${target ?: "the app"}" else "Stays on this phone",
        dataLeavesPhone = host != null || actsOnScreen,
        dataGoesToHost = host,
        riskLevel = request.risk,
        risk = request.risk.toRiskLabel(),
        reason = request.reason?.let { inWords(PreviewText.exact(it)) },
        timing = timingOf(request, now, zone),
        raisedAt = formatCompactTimestamp(request.createdAt, now, zone),
        choices = if (open) choicesFor(request) else emptyList(),
        consequences = if (!open) emptyMap() else when (request.kind) {
            OwnerRequestKind.APPROVAL -> mapOf(
                RequestChoice.DENY to "Nothing happens. ${agent.name} is told, with your note if you write one.",
                RequestChoice.APPROVE to "Runs exactly what is shown above, this one time. You are asked again next time.",
            )
            OwnerRequestKind.PERMISSION -> mapOf(
                RequestChoice.ALLOW_ONCE to "Only what is shown above, this one time. ${agent.name} asks you again next time.",
                RequestChoice.ALLOW_FOR_TASK to "${agent.name} may $breadth$notOnly until this task ends.",
                RequestChoice.ALLOW_ALWAYS to "${agent.name} may $breadth$notOnly in any task or chat, until you remove it in Tools › Allowed.",
                RequestChoice.DENY to "${agent.name} is refused and told, with your note if you write one.",
            ).filterKeys { it in choicesFor(request) }
        },
        approveLabel = action?.let { "Approve: " + it.replaceFirstChar(Char::lowercaseChar) } ?: RequestChoice.APPROVE.label,
        action = action,
        always = AlwaysUi(
            title = "Always let ${agent.name} $breadth?",
            message = "${agent.name} may then $breadth$notOnly in any task or chat without asking you, until you remove it in " +
                "Tools › Allowed. " + if (actsOnScreen) {
                    "Each action on ${app ?: "the app"}'s screen still asks you, unless you set the app to ask only before buying, " +
                        "sending, deleting and similar."
                } else {
                    "Buying, sending, deleting and similar still ask you every time."
                },
            confirm = "Always allow ${agent.name}",
        ),
        hardToUndo = if (request.risk != RiskLevel.CONSEQUENTIAL) null else {
            // A screen action's details quote the other app's own text, which must not be lifted into this app's warning.
            if (request.constraint is CapabilityConstraint.App) request.risk.toRiskLabel().detail
            else details.filter { IRREVERSIBLE.containsMatchIn(it) }.joinToString(" ").ifEmpty { request.risk.toRiskLabel().detail }
        },
        hiddenWarning = hiddenCharacterWarning(marked),
        note = request.decisionNote?.let { inWords(PreviewText.exact(it)) },
        taskId = request.taskId,
        rootId = request.rootId,
        conversationId = request.conversationId,
        returnsToApp = if (open && request.constraint is CapabilityConstraint.App) {
            "You answer here, in AI Control Center. Once you allow it, this app steps aside so $app comes back, and the agent " +
                "goes on once $app is in front again; an action runs only if $app still shows what is described above. If " +
                "another app comes back instead, switch to $app within 30 seconds, or nothing is done."
        } else {
            null
        },
    )
}

/** Text from the request as the screens show it: hidden characters made visible ([visible]), then named ([inWords]). */
private fun shown(text: String): String = inWords(visible(text))

/**
 * The action a summary starts with, as the tool wrote it: its verb and the plain words that
 * follow, up to the first name, value or "in", "to", "from" and the like. "Delete 3 files in
 * Documents: …" gives "Delete 3 files"; "Run the shell command "ls"…" gives "Run the shell
 * command". Model-chosen values are always quoted, so none ends up here. Null when the summary
 * does not start with a capitalised verb.
 */
fun actionOf(summary: String): String? {
    val words = summary.trim().split(' ')
    val verb = words.firstOrNull()?.takeIf { it.length > 1 && it[0].isUpperCase() && it.drop(1).all(Char::isLowerCase) } ?: return null
    val action = mutableListOf(verb)
    for (word in words.drop(1)) {
        if (action.size == MAX_ACTION_WORDS || word in ACTION_STOP_WORDS) break
        if (word.isEmpty() || !(word.all(Char::isLowerCase) || word.all(Char::isDigit))) break
        action += word
    }
    return action.joinToString(" ")
}

/**
 * What a permission covers once it lasts longer than one time, in the owner's words: "read any
 * web page", "change files in “Documents” only". Folder and app permissions name their folder or app.
 */
fun breadthOf(capability: Capability, title: String, target: String?): String {
    val folder = target?.let { "“$it” only" } ?: "this shared folder only"
    val app = target ?: "this app"
    return when (capability) {
        OperatorCapabilities.WORKSPACE_WRITE -> "change any file in the workspace"
        OperatorCapabilities.FOLDERS_READ -> "read files in $folder"
        OperatorCapabilities.FOLDERS_WRITE -> "change files in $folder"
        OperatorCapabilities.MEDIA_READ -> "see any of your photos and videos"
        OperatorCapabilities.APPS_OPEN -> "open any app or link"
        OperatorCapabilities.APPS_COMPOSE -> "prepare any message for you to send"
        OperatorCapabilities.APPS_MANAGE -> "ask to install or remove any app"
        OperatorCapabilities.WEB_READ -> "read any web page"
        OperatorCapabilities.SCREEN_READ -> "see the screen of $app, and no other app"
        OperatorCapabilities.SCREEN_OPERATE -> "tap and type in $app, and no other app"
        OperatorCapabilities.SCRIPTS_JS -> "run any JavaScript in the sandbox"
        OperatorCapabilities.SCRIPTS_SHELL -> "ask to run shell commands"
        OperatorCapabilities.SCRIPTS_TERMUX -> "ask to run Termux commands"
        else -> "use “${title.replaceFirstChar(Char::lowercaseChar)}”" + (target?.let { " for $it only" } ?: "")
    }
}

/**
 * [text] with any invisible, direction-changing, control or unusual space character that is
 * still in it written as "⟨U+XXXX⟩". Line breaks stay (a preview from [PreviewText.exact] shows
 * each one as ⏎ followed by the real break). Text already made exact is returned unchanged.
 */
fun visible(text: String): String {
    if (text.none { it != '\n' && (PreviewText.isHidden(it) || Character.isISOControl(it) || it.isSurrogate() || PreviewText.isUnusualSpace(it.code)) }) {
        return text
    }
    val out = StringBuilder(text.length + 16)
    text.codePoints().forEach { cp ->
        val shown = cp != '\n'.code && (PreviewText.isHidden(cp) || Character.isISOControl(cp) || PreviewText.isUnusualSpace(cp))
        if (shown) out.append("⟨U+").append(String.format("%04X", cp)).append('⟩') else out.appendCodePoint(cp)
    }
    return out.toString()
}

/**
 * [text] with the "⟨U+XXXX⟩" markers of common hidden characters named in words, so the owner
 * can tell what they do: ⟨U+202E⟩ becomes "⟨hidden: reverses text⟩", ⟨U+200B⟩ "⟨hidden: invisible
 * space⟩". Rarer characters keep their code.
 */
fun inWords(text: String): String =
    if ("⟨U+" !in text) text else CODE_MARK.replace(text) { mark -> HIDDEN_NAMES[mark.groupValues[1].toInt(16)]?.let { "⟨hidden: $it⟩" } ?: mark.value }

/**
 * A plain warning when [texts] (made visible with [visible] or [PreviewText.exact], not yet
 * [inWords]) hold a hidden character, or null. A name with a direction change is explained: how
 * it can look elsewhere and how it really ends ("Invoice⟨U+202E⟩fdp.exe" can look like
 * "Invoiceexe.pdf" but ends in ".exe", a program).
 */
fun hiddenCharacterWarning(texts: List<String>): String? {
    val marked = texts.filter { CODE_MARK.containsMatchIn(it) }
    if (marked.isEmpty()) return null
    for (text in marked) {
        for (word in NAME.findAll(text).map { it.value }) {
            val codes = CODE_MARK.findAll(word).map { it.groupValues[1].toInt(16) }.toList()
            if (codes.none { it in DIRECTION_CODES }) continue
            val real = CODE_MARK.replace(word, "")
            val ending = real.substringAfterLast('.', "").takeIf { '.' in real && it.length in 1..5 && it.all(Char::isLetterOrDigit) }?.lowercase()
            val reversal = word.indexOf("⟨U+202E⟩")
            val looks = if (reversal < 0) null else (CODE_MARK.replace(word.substring(0, reversal), "") + CODE_MARK.replace(word.substring(reversal), "").reversed())
            val endsIn = ending?.let { "“.$it”" + if (it in PROGRAM_ENDINGS) ", which runs as a program" else "" }
            return buildString {
                append("A name here contains a hidden character that ")
                append(if (looks != null) "reverses text" else "changes the direction of text")
                append(", a known trick to disguise a file or an address.")
                when {
                    looks != null && looks != real && endsIn != null -> append(" Elsewhere it can look like “$looks”, but it really ends in $endsIn.")
                    looks != null && looks != real -> append(" Elsewhere it can look like “$looks”.")
                    endsIn != null -> append(" It really ends in $endsIn.")
                }
                append(" Deny unless you expected this.")
            }
        }
    }
    return "Something here contains hidden characters, shown as highlighted ⟨hidden: …⟩ marks. They can make a name or an address " +
        "look like something it is not. Deny unless you expected this."
}

/**
 * Where [text] holds one of [PreviewText]'s markers (⏎ and ⏎×N, ⇥, ␣ and ␣×N, ⟨U+XXXX⟩ and
 * ⟨U+XXXX⟩×N, a quote inside a value ⟨"⟩, a repeated character's ⟨×N⟩, a combining-mark count)
 * or a named one (⟨hidden: …⟩), as ranges in order, so a screen can draw them apart from the
 * text around them.
 */
fun previewMarks(text: String): List<IntRange> = MARKER.findAll(text).map { it.range }.toList()

/** True when [text] shows a hidden character (⟨U+XXXX⟩ or ⟨hidden: …⟩), as opposed to only line breaks or spaces. */
fun hasHiddenMark(text: String): Boolean = HIDDEN_MARK.containsMatchIn(text)

private val MARKER = Regex("""⏎(?:×\d+)?|⇥|␣(?:×\d+)?|(?:⟨U\+[0-9A-F]{4,6}⟩|⟨hidden: [a-z -]+⟩)(?:×\d+)?|⟨"⟩|⟨×\d+⟩|⟨\d+ more combining marks⟩""")
private val HIDDEN_MARK = Regex("""⟨U\+[0-9A-F]{4,6}⟩|⟨hidden: [a-z -]+⟩""")
private val CODE_MARK = Regex("""⟨U\+([0-9A-F]{4,6})⟩""")

/** A run that can be a name: no spaces, quotes or brackets. */
private val NAME = Regex("""[^\s“”"'‘’()\[\]<>,;]+""")

private val DIRECTION_CODES = setOf(0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069)

private val HIDDEN_NAMES: Map<Int, String> = buildMap {
    put(0x202E, "reverses text")
    put(0x202D, "forces left to right")
    listOf(0x202A, 0x202B, 0x2066, 0x2067, 0x2068).forEach { put(it, "changes text direction") }
    listOf(0x202C, 0x2069).forEach { put(it, "ends a direction change") }
    listOf(0x200E, 0x200F, 0x061C).forEach { put(it, "direction mark") }
    listOf(0x200B, 0x2060, 0xFEFF).forEach { put(it, "invisible space") }
    listOf(0x200C, 0x200D).forEach { put(it, "invisible joiner") }
    put(0x00AD, "invisible hyphen")
    listOf(0x00A0, 0x202F, 0x2007).forEach { put(it, "unusual space") }
}

/** Endings of files that run as programs, named in the hidden-character warning. */
private val PROGRAM_ENDINGS = setOf("apk", "apks", "xapk", "exe", "msi", "bat", "cmd", "com", "scr", "jar", "js", "vbs", "ps1", "sh", "dex")

/** Lines of an assessment's details that say an action cannot be undone. */
private val IRREVERSIBLE = Regex("cannot be (undone|restored)|for good|no trash", RegexOption.IGNORE_CASE)

private val ACTION_STOP_WORDS = setOf("in", "into", "to", "from", "with", "on", "at", "for", "of", "as", "and", "by")
private const val MAX_ACTION_WORDS = 4

private const val MINUTE_MS = 60_000L
