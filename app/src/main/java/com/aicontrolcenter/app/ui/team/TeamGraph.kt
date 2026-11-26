package com.aicontrolcenter.app.ui.team

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId

/*
 * The Team view's model (goal 9; WP9): who is in the picture, what each one is doing, and who talked to whom. Plain
 * immutable data with no Compose or Android types (the Immutable marks are hints for the compiler only), so the
 * mapping from what is stored ([TeamGraphBuilder]), the layout ([TeamLayout]) and the dots ([PulseScheduler]) are
 * unit-tested on the JVM. The picture and the list both draw this one model, so they always say the same thing.
 */

/** What a node stands for. */
enum class TeamNodeKind {
    /** The owner: "You", at the top. */
    OWNER,

    /** The designated Director, in the centre. */
    DIRECTOR,

    /** Any other agent, on the ring. */
    AGENT,

    /** An agent that took part but has been deleted since: "A former team member". */
    FORMER_AGENT,

    /** A shared memory space, along the bottom. */
    SPACE,
}

/**
 * What a team member is doing, most important first: when several apply (an agent with two tasks), the builder takes the
 * first. Every state has a word and a glyph ([tone]'s), never a colour alone.
 */
enum class NodeState(val word: String, val tone: StatusTone) {
    WAITING_FOR_YOU("Waiting for you", StatusTone.ATTENTION),
    WORKING("Working", StatusTone.WORKING),
    WAITING_FOR_TEAMMATE("Waiting for a teammate", StatusTone.WAITING),

    /** Its provider is not answering or asked the app to slow down; the work goes on by itself (R4). */
    WAITING_TO_TRY_AGAIN("Waiting to try again", StatusTone.WAITING),

    /** Switched off, or something it needs is missing or broken; its availability says what. */
    CANNOT_RUN("Cannot run", StatusTone.UNAVAILABLE),
    PAUSED("Paused", StatusTone.DISABLED),

    /** Interrupted, or waiting work whose objective was paused (handoff-w2afix.md INT-3): it does not move by itself. */
    STOPPED("Stopped", StatusTone.DISABLED),

    /** Its step waits for earlier steps of the plan. */
    BLOCKED("Waiting for earlier steps", StatusTone.WAITING),
    UP_NEXT("Up next", StatusTone.WAITING),
    FAILED("Could not finish", StatusTone.UNAVAILABLE),
    DONE("Done", StatusTone.READY),

    /** Nothing to do in what is shown. */
    IDLE("Free", StatusTone.READY),
}

/** One member of the picture: an agent, "You", or a shared space. */
@Immutable
data class TeamNode(
    /** "you", an agent's id, or "space:<id>". */
    val id: String,
    val kind: TeamNodeKind,
    val name: String,
    /** "Specialist", "Director", "Shared space"; null for "You". */
    val roleLabel: String?,
    val colorIndex: Int,
    val state: NodeState,
    /** The state in words: [NodeState.word], or the agent's availability ("No model yet") when it cannot run. */
    val stateText: String,
    val tone: StatusTone,
    /**
     * What the state is about, in the owner's words: "Find offline note apps · Writing a reply", "Waiting for your
     * answer: “Which city?”" (an agent's own words are always quoted), "Paused: today's limit is reached".
     */
    val detail: String? = null,
    val agentId: AgentId? = null,
    val spaceId: SpaceId? = null,
    /** The task the state is about. */
    val taskId: TaskId? = null,
    val taskTitle: String? = null,
    /** The objective of [taskId]. */
    val rootId: TaskId? = null,
    /** Requests of this agent that wait for the owner ("You": every one in the picture), oldest first. */
    val requestIds: List<RequestId> = emptyList(),
    /** Those of [requestIds] that are questions (opened on the question screen, rule 13). */
    val questionIds: Set<RequestId> = emptySet(),
    /** Which model it uses and where its text goes: "Grok 4 Fast (xAI) · leaves this device (cloud)". */
    val modelLine: String? = null,
    /** A space: the agents that may read it and write it, as the owner set them up (never offered wider here). */
    val readers: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    /** Switched off: drawn faint. */
    val dimmed: Boolean = false,
) {
    val isAgent: Boolean get() = kind == TeamNodeKind.DIRECTOR || kind == TeamNodeKind.AGENT || kind == TeamNodeKind.FORMER_AGENT
}

/**
 * What a link carries, by the kind of messages on it. Each kind is drawn its own way (solid, dashed, dotted, double…)
 * with its own 20 dp glyph in the middle, so no kind is told apart by colour alone.
 */
enum class LinkKind(
    /** "Work handed out": the kind as a heading. */
    val title: String,
    /** "work handed out": the kind inside a sentence. */
    val phrase: String,
) {
    ASSIGNS("Work handed out", "work handed out"),
    ASKS("Questions between teammates", "questions"),
    DELEGATES("Work handed on", "work handed on"),
    REVIEWS("Checks of work", "checks of work"),
    REPORTS("Results reported", "results reported"),
    SHARES("Memories shared", "memories shared"),
    ASKS_YOU("Questions for you", "questions for you"),
    TELLS_YOU("News for you", "news for you"),
    NOTES("Your notes", "your notes"),
}

/** How many messages of one kind went each way along a link: [aToB] from [TeamLink.aId] to [TeamLink.bId]. */
@Immutable
data class KindCount(val kind: LinkKind, val aToB: Int, val bToA: Int) {
    val total: Int get() = aToB + bToA
}

/**
 * Every message between two members of the picture, in either direction, as one link. [kind] is the kind of the newest
 * message (what happens between them now), [kinds] every kind with its counts, most used first.
 */
@Immutable
data class TeamLink(
    /** "a|b", with the two ids in a fixed order. */
    val key: String,
    val aId: String,
    val bId: String,
    val kind: LinkKind,
    val kinds: List<KindCount>,
    /** A message went from [aId] to [bId]; arrowheads show which ways were used. */
    val aToB: Boolean,
    val bToA: Boolean,
    val count: Int,
    val lastAt: Long,
    /** It carried a message in the last minute: drawn thicker (the static "afterglow", also under Calm). */
    val recent: Boolean,
    /** The newest [TeamGraphBuilder.LINK_MESSAGES] messages on it, in sending order (headers only; bodies on demand). */
    val messageIds: List<MessageId>,
) {
    fun other(id: String): String = if (id == aId) bId else aId

    fun touches(id: String): Boolean = id == aId || id == bId
}

/** A message that can travel along its link as a dot, once, when it is new to the screen ([PulseScheduler]). */
@Immutable
data class TeamPulse(
    /** The message id: a dot travels at most once per message. */
    val key: String,
    val fromId: String,
    val toId: String,
    val linkKey: String,
    val kind: LinkKind,
)

/** The whole picture. */
@Immutable
data class TeamGraph(
    val nodes: List<TeamNode> = emptyList(),
    val links: List<TeamLink> = emptyList(),
    /** The newest messages of the picture, newest last; the screen sends a dot only for those it has not seen. */
    val pulses: List<TeamPulse> = emptyList(),
    /** One objective shown: agents of the team that take no part in it ("4 other agents are not in this objective"). */
    val agentsNotInObjective: Int = 0,
    /** Agents left out above [TeamGraphBuilder.MAX_AGENTS]; the list shows everyone. */
    val agentsLeftOut: Int = 0,
    val spacesLeftOut: Int = 0,
    val linksLeftOut: Int = 0,
) {
    val owner: TeamNode? get() = nodes.firstOrNull { it.kind == TeamNodeKind.OWNER }
    val director: TeamNode? get() = nodes.firstOrNull { it.kind == TeamNodeKind.DIRECTOR }

    /** The agents on the ring, in their order around it (clockwise from the top). */
    val ring: List<TeamNode> get() = nodes.filter { it.kind == TeamNodeKind.AGENT || it.kind == TeamNodeKind.FORMER_AGENT }
    val spaces: List<TeamNode> get() = nodes.filter { it.kind == TeamNodeKind.SPACE }

    fun node(id: String?): TeamNode? = if (id == null) null else nodes.firstOrNull { it.id == id }

    fun link(key: String?): TeamLink? = if (key == null) null else links.firstOrNull { it.key == key }

    fun linksOf(id: String): List<TeamLink> = links.filter { it.touches(id) }

    /** Agents (and "You") in each state. */
    fun count(state: NodeState): Int = nodes.count { it.isAgent && it.state == state }
}

/** The node id of the owner. */
const val YOU_ID = "you"

/** The node id of memories shared before shared spaces had their own place in a message (stored as "System"). */
const val SHARED_MEMORY_ID = "space:shared"

fun spaceNodeId(id: SpaceId): String = "space:${id.value}"

fun linkKeyOf(a: String, b: String): String = if (a <= b) "$a|$b" else "$b|$a"
