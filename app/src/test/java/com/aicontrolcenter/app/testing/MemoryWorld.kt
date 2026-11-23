package com.aicontrolcenter.app.testing

import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.toDraft
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.framework.memory.MemorySeeder
import kotlinx.coroutines.flow.first

private const val DAY = 24L * 60 * 60 * 1000
private const val HOUR = 60L * 60 * 1000

/** Handles on the sample memories, so tests can open and check particular ones. */
class SampleMemories(
    val teamSpace: SpaceId,
    val researchSpace: SpaceId,
    val bulletPoints: MemoryId,
    val bulletPointsShared: MemoryId,
    val lisbonTrip: MemoryId,
    val ferryTimetable: MemoryId,
    val vegetarian: MemoryId,
    val earlyFlights: MemoryId,
    val groceriesUp: MemoryId,
    val groceriesFlat: MemoryId,
    val conversation: ConversationId,
)

/**
 * The owner a few weeks into Phase 3: the Phase 2 world, plus memories written by agents during
 * their chats (with the model that answered), memories the owner wrote, a second shared space
 * for research, a shared copy, a contradiction, links and some task memory. Everything goes
 * through the real memory service, as agents and the owner would.
 */
suspend fun TestApp.populateWithMemories(): SampleMemories {
    populateWithProviders()
    val memory = graph.memoryService
    val teamSpace = memory.observeSpaces().first().first { it.name == MemorySeeder.TEAM_SPACE_NAME }.id
    val researchSpace = memory.createSpace(MemoryActor.Owner, "Research notes", "Sources, findings and open questions from research work.").id
    val researcher = agentNamed("Researcher").profile
    val analyst = agentNamed("Analyst").profile
    grant(researcher, read = setOf(researchSpace), write = setOf(researchSpace))
    grant(analyst, read = setOf(researchSpace), write = emptySet())

    val end = clock.current
    clock.current = end - 9 * DAY
    val conversation = graph.agentChat.startConversation(researcher.id, "Planning the Lisbon trip")

    suspend fun agent(name: String) = MemoryActor.Agent(agentNamed(name).profile)
    suspend fun saved(
        by: String,
        scope: MemoryScope,
        kind: MemoryKind,
        title: String,
        content: String,
        importance: Double,
        tags: Set<String> = emptySet(),
        source: String? = null,
        inChat: Boolean = false,
        tier: MemoryTier = MemoryTier.LONG_TERM,
    ): MemoryId {
        val profile = agentNamed(by).profile
        val model = profile.modelId?.let { id -> models.getAll().firstOrNull { it.id == id } }
        val record = memory.remember(
            agent(by),
            MemoryDraft(
                scope = scope, kind = kind, title = title, content = content, tier = tier, tags = tags, importance = importance,
                provenance = Provenance(
                    origin = ProvenanceOrigin.AGENT, agentId = profile.id, conversationId = conversation.id.takeIf { inChat },
                    modelId = model?.id, modelName = model?.modelName, source = source, recordedAt = clock.now(),
                ),
            ),
        )
        clock.advance(7 * HOUR)
        return record.id
    }
    suspend fun written(scope: MemoryScope, kind: MemoryKind, title: String, content: String, importance: Double, tags: Set<String> = emptySet()): MemoryId {
        val record = memory.remember(
            MemoryActor.Owner,
            MemoryDraft(scope, kind, title, content, tags = tags, importance = importance, provenance = Provenance(ProvenanceOrigin.OWNER, recordedAt = clock.now())),
        )
        clock.advance(5 * HOUR)
        return record.id
    }

    val researcherPrivate = MemoryScope.Agent(researcher.id)
    val bulletPoints = saved(
        "Researcher", researcherPrivate, MemoryKind.PREFERENCE, "Prefers summaries as short bullet points",
        "When summarising sources, the owner wants five bullet points at most, each one sentence, with the source named at the end.",
        importance = 0.8, tags = setOf("writing", "summaries"), inChat = true,
    )
    val lisbonTrip = saved(
        "Researcher", researcherPrivate, MemoryKind.EVENT, "Lisbon trip, 12 to 16 March",
        "The owner travels to Lisbon from 12 to 16 March and wants day trips to Sintra and Cascais researched.",
        importance = 0.65, tags = setOf("travel"), inChat = true,
    )
    val ferryTimetable = saved(
        "Researcher", researcherPrivate, MemoryKind.FINDING, "Cascais trains run every 20 minutes",
        "Trains from Cais do Sodré to Cascais run every 20 minutes on weekdays and every 30 minutes at weekends; the trip takes 40 minutes.",
        importance = 0.5, tags = setOf("travel", "transport"), source = "cp.pt timetable",
    )
    saved(
        "Researcher", MemoryScope.Shared(researchSpace), MemoryKind.NOTE, "Lisbon transport sources",
        "Official timetables: CP for trains, Carris for buses and trams. Third-party sites were often out of date.",
        importance = 0.45, tags = setOf("sources"),
    )
    val writerPrivate = MemoryScope.Agent(agentNamed("Writer").profile.id)
    written(writerPrivate, MemoryKind.PREFERENCE, "House style: British spelling", "Use British spelling and the Oxford comma in everything written for the owner.", 0.9, setOf("style"))
    val analystPrivate = MemoryScope.Agent(analyst.id)
    val groceriesUp = saved(
        "Analyst", analystPrivate, MemoryKind.FINDING, "Grocery spending rose 12% in Q3",
        "Compared with Q2, grocery spending in July to September rose by 12%, mostly in August.",
        importance = 0.7, tags = setOf("budget"),
    )
    val groceriesFlat = written(
        analystPrivate, MemoryKind.FINDING, "Grocery spending was flat in Q3",
        "The bank's own summary shows grocery spending unchanged between Q2 and Q3.", 0.4, setOf("budget"),
    )
    val vegetarian = written(
        MemoryScope.Shared(teamSpace), MemoryKind.FACT, "The owner is vegetarian",
        "No meat or fish. Eggs and dairy are fine. Applies to restaurant suggestions, recipes and shopping lists.", 0.9, setOf("food"),
    )
    val earlyFlights = written(
        MemoryScope.Shared(teamSpace), MemoryKind.PREFERENCE, "No flights before 8:00",
        "Never suggest or book a flight that departs before 8:00 in the morning.", 0.75, setOf("travel"),
    )
    saved(
        "Director", MemoryScope.Shared(teamSpace), MemoryKind.DECISION, "Weekly review every Monday",
        "The team reviews open objectives every Monday morning and the Director sends the owner a short summary.",
        importance = 0.6, tags = setOf("routine"),
    )

    clock.current = end - 6 * HOUR
    val shared = memory.share(MemoryActor.Owner, bulletPoints, teamSpace)
    memory.link(MemoryActor.Owner, lisbonTrip, ferryTimetable, LinkRelation.RELATES_TO)
    memory.link(MemoryActor.Owner, earlyFlights, lisbonTrip, LinkRelation.REFERENCES)
    memory.link(MemoryActor.Owner, groceriesFlat, groceriesUp, LinkRelation.CONTRADICTS)
    memory.remember(
        agent("Researcher"),
        MemoryDraft(
            MemoryScope.Task(TaskId("tsk_lisbon_daytrips"), researcher.id), MemoryKind.NOTE, "Compare Sintra and Cascais by train",
            "Working note: Sintra trains leave from Rossio, Cascais trains from Cais do Sodré. Check both for Saturday.",
            tier = MemoryTier.SHORT_TERM, importance = 0.3,
            provenance = Provenance(ProvenanceOrigin.AGENT, agentId = researcher.id, taskId = TaskId("tsk_lisbon_daytrips"), recordedAt = clock.now()),
        ),
    )
    // The Researcher has used its memories in real work, so they show recall counts.
    memory.recall(agent("Researcher"), RecallQuery("how should summaries be written"))
    memory.recall(agent("Researcher"), RecallQuery("Lisbon trip dates"))
    clock.current = end + HOUR

    return SampleMemories(
        teamSpace = teamSpace, researchSpace = researchSpace, bulletPoints = bulletPoints, bulletPointsShared = shared.id,
        lisbonTrip = lisbonTrip, ferryTimetable = ferryTimetable, vegetarian = vegetarian, earlyFlights = earlyFlights,
        groceriesUp = groceriesUp, groceriesFlat = groceriesFlat, conversation = conversation.id,
    )
}

/** Gives [agent] access to spaces the way the owner does: an ordinary update through the agent manager. */
private suspend fun TestApp.grant(agent: AgentProfile, read: Set<SpaceId>, write: Set<SpaceId>) {
    val current = agents.get(agent.id) ?: return
    graph.agentManager.update(
        agent.id,
        current.toDraft().copy(memory = current.memory.copy(readableSpaces = current.memory.readableSpaces + read, writableSpaces = current.memory.writableSpaces + write)),
    )
}
