package com.aicontrolcenter.app.ui.team

/**
 * The Team view in words: what each member's row, sheet and TalkBack description say, and what each link carries. The
 * picture and the list both use these, so a member reads the same in both.
 */
object TeamDescriptions {
    /** One member's links, newest first, with what it did along each, from its own side. */
    fun linkRows(id: String, graph: TeamGraph): List<LinkRowUi> = graph.linksOf(id).sortedByDescending { it.lastAt }.map { link ->
        val other = graph.node(link.other(id))
        val otherIsYou = other?.kind == TeamNodeKind.OWNER
        val otherName = other?.name ?: TeamWords.FORMER_MEMBER
        val forward = link.aId == id
        val words = link.kinds.flatMap { count ->
            TeamWords.connection(
                count.kind,
                out = if (forward) count.aToB else count.bToA,
                incoming = if (forward) count.bToA else count.aToB,
                other = if (otherIsYou) "you" else otherName,
                otherIsYou = otherIsYou,
            )
        }.ifEmpty { listOf(TeamWords.times(link.count, "message", "messages") + " with " + if (otherIsYou) "you" else otherName) }
        LinkRowUi(link.key, otherName, words.joinToString(", ").replaceFirstChar(Char::uppercase), link.kind, link.count)
    }

    /** "Questions between Analyst and Researcher, 3 messages"; "Questions for you with Writer, 2 messages". */
    fun linkTitle(link: TeamLink, graph: TeamGraph): String {
        val a = graph.node(link.aId)
        val b = graph.node(link.bId)
        val withYou = when {
            a?.kind == TeamNodeKind.OWNER -> b?.name
            b?.kind == TeamNodeKind.OWNER -> a?.name
            else -> null
        }
        val kinds = link.kinds.map { it.kind }.ifEmpty { listOf(link.kind) }
        return TeamWords.linkDescription(kinds, a?.name.orEmpty(), b?.name.orEmpty(), link.count, withYou)
    }

    /** "Connections: asked Analyst 2 times; got work from Director". */
    fun connectionsLine(id: String, graph: TeamGraph): String? =
        linkRows(id, graph).joinToString("; ") { it.words.replaceFirstChar(Char::lowercase) }.ifEmpty { null }?.let { "Connections: $it." }

    /**
     * What TalkBack says for a member, in the order the owner needs it: who, what they do now and about what, then whom
     * they dealt with. "Researcher, Specialist. Waiting for you. Asks you: “Which city?”. Task: Find note apps.
     * Connections: asked Analyst 2 times."
     */
    fun node(node: TeamNode, graph: TeamGraph): String = buildList {
        // "Researcher, Specialist"; the Director is not "Director, Director".
        add(listOfNotNull(node.name, node.roleLabel?.takeIf { it != node.name }).joinToString(", "))
        if (node.kind == TeamNodeKind.DIRECTOR) add("In the centre: it plans and hands out the work")
        add(node.stateText)
        node.detail?.let(::add)
        node.taskTitle?.let { add("Task: $it") }
        if (node.kind == TeamNodeKind.SPACE) {
            if (node.readers.isNotEmpty()) add("Can be read by " + names(node.readers))
            if (node.writers.isNotEmpty()) add("Can be written by " + names(node.writers))
        }
        connectionsLine(node.id, graph)?.let { add(it.removeSuffix(".")) }
    }.joinToString(". ") { it.trimEnd('.') } + "."

    /** "Researcher, Analyst and Writer". */
    fun names(all: List<String>): String = when (all.size) {
        0 -> "nobody"
        1 -> all.single()
        else -> all.dropLast(1).joinToString(", ") + " and " + all.last()
    }
}
