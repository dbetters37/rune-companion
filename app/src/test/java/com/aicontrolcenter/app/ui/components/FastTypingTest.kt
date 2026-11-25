package com.aicontrolcenter.app.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.operatorOn
import com.aicontrolcenter.app.testing.populateWithProviders
import com.aicontrolcenter.app.ui.shell.AiControlCenterRoot
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.framework.llm.SimulatorProviderFactory
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's report (#42) and rule 27: what is typed is exactly what the field holds and what
 * is saved, however fast it is typed and however long the screen's data takes to catch up.
 *
 * Every test types one key at a time with the frame clock paused and lets a frame through only
 * every few keys, as a fast typist on a phone does: keys arrive faster than the screen redraws.
 * One test per family of fields (the objective, the agent editor, the provider and model forms,
 * memory, searches, chat, the approval note, short entries in place, AI app notes). Each checks
 * the field's text exactly and then what was stored.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class FastTypingTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val turns = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stopApp() {
        turns.cancel()
        robot?.close()
        scope.cancel()
    }

    private fun start(populate: suspend TestApp.() -> Unit = { graph.start().join() }): AppRobot =
        AppRobot(compose).given(populate).launch().also { robot = it }

    // --- the objective (the field the owner reported) ---

    @Test
    fun `the objective is exactly what was typed while the form waits for storage`() {
        // The form's other data comes from storage, which answers later than the keyboard (Room reads
        // finish on another thread). Here storage answers only once the owner has finished typing.
        val app = TestApp(scope)
        val reads = HeldAgentReads(app.agents)
        val graph = AppGraph(
            app.ports.copy(agents = reads), scope, app.factories.values.toList() + SimulatorProviderFactory(chunkDelayMs = 0),
            backgroundContext = EmptyCoroutineContext,
            notifications = app.notifications,
        )
        runBlocking {
            graph.start().join()
            for (agent in app.agents.getAll()) graph.agentManager.assignModel(agent.id, Simulator.MODEL_ID)
        }
        compose.setContent { AiControlCenterRoot(graph, true) }
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Tasks", substring = true) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText("New objective") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()

        val field = compose.onNode(hasContentDescription("Objective") and hasSetTextAction())
        reads.hold = CompletableDeferred()
        field.typeFast(OBJECTIVE)
        reads.hold?.complete(Unit)
        reads.hold = null
        compose.waitForIdle()
        assertEquals(OBJECTIVE, field.typed())
        compose.onAllNodes(hasText("${OBJECTIVE.length} / 4,000", substring = true)).onFirst().assertExists()

        compose.onAllNodes(hasText("Start objective") and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            runBlocking { app.tasks.observeObjectives().first() }.isNotEmpty()
        }
        assertEquals(OBJECTIVE, runBlocking { app.tasks.observeObjectives().first() }.single().instructions)
    }

    // --- the agent editor: four text fields, a number field and the chip entry ---

    @Test
    fun `every field of the agent editor holds exactly what was typed`() {
        val robot = start().openSection("Agents").tapIcon("New agent").scrollListTo("Blank agent").tapItemContaining("Blank agent")
        val app = robot.app

        labelled("Name").also { it.performTextClearance() }.typeFast("Garden Planner 2")
        labelled("Description").typeFast("Plans the vegetable beds, season by season.")
        labelled("Instructions").also { it.performTextClearance() }.typeFast("Ask which beds get sun, then plan.\nKeep it short.")
        labelled("Personality").also { it.performTextClearance() }.typeFast("Patient, practical; never preachy.")
        labelled("Maximum output tokens").typeFast("12a34")
        assertEquals("Garden Planner 2", labelled("Name").typed())
        assertEquals("Plans the vegetable beds, season by season.", labelled("Description").typed())
        assertEquals("Ask which beds get sun, then plan.\nKeep it short.", labelled("Instructions").typed())
        assertEquals("Patient, practical; never preachy.", labelled("Personality").typed())
        assertEquals("1234", labelled("Maximum output tokens").typed(), "only digits are taken")

        val skill = hinted("Add skill")
        skill.typeFast("gardening")
        assertEquals("gardening", skill.typed())
        skill.performImeAction()
        compose.waitForIdle()

        robot.scrollTo("Create agent").tap("Create agent")
        val saved = runBlocking { app.agentNamed("Garden Planner 2") }.profile
        assertEquals("Plans the vegetable beds, season by season.", saved.description)
        assertEquals("Ask which beds get sun, then plan.\nKeep it short.", saved.systemInstructions)
        assertEquals("Patient, practical; never preachy.", saved.personality)
        assertEquals(1234, saved.generation.maxOutputTokens)
        assertTrue("gardening" in saved.skills, saved.skills.toString())
    }

    // --- the provider form, the API key included ---

    @Test
    fun `the provider form saves exactly what was typed, the key included`() {
        val robot = start().openSectionViaMore("Models").tap("Add provider")
            .scrollListTo("Custom OpenAI-compatible server").tapItemContaining("Any server that speaks the OpenAI")
        val app = robot.app

        labelled("Name").also { it.performTextClearance() }.typeFast("Garage box")
        labelled("Base URL").typeFast("http://192.168.1.77:8080/v1")
        val key = compose.onNode(hasSetTextAction() and hasText("API key"))
        key.typeFast(KEY)
        labelled("Extra headers").typeFast("X-Title: Garden\nX-Mode: fast")
        assertEquals("Garage box", labelled("Name").typed())
        assertEquals("http://192.168.1.77:8080/v1", labelled("Base URL").typed())
        assertEquals("X-Title: Garden\nX-Mode: fast", labelled("Extra headers").typed())

        robot.scrollTo("Add and connect").tap("Add and connect")
        robot.waitUntilShown("Garage box is registered.")
        val provider = runBlocking { app.providers.getAll() }.single { it.name == "Garage box" }
        assertEquals("http://192.168.1.77:8080/v1", provider.baseUrl)
        assertEquals(mapOf("X-Title" to "Garden", "X-Mode" to "fast"), provider.extraHeaders)
        assertEquals(KEY, runBlocking { app.vault.get(assertNotNull(provider.apiKeyAlias)) })
    }

    // --- the model editor: names, notes and number fields ---

    @Test
    fun `the model editor saves exactly what was typed`() {
        val robot = start { populateWithProviders() }.openSectionViaMore("Models").tap("Models · 5").tapItemContaining("Llama 3.2 3B")
        val app = robot.app

        labelled("Display name").also { it.performTextClearance() }.typeFast("Llama, the small one")
        labelled("Notes").typeFast("Quick; fine for summaries.")
        labelled("Context window (tokens)").also { it.performTextClearance() }.typeFast("131x072")
        labelled("Longest reply (tokens)").also { it.performTextClearance() }.typeFast("8192")
        assertEquals("Llama, the small one", labelled("Display name").typed())
        assertEquals("Quick; fine for summaries.", labelled("Notes").typed())
        assertEquals("131072", labelled("Context window (tokens)").typed(), "only digits are taken")

        robot.scrollTo("Save changes").tap("Save changes").waitUntilShown("Saved.")
        val model = runBlocking { app.models.getAll() }.single { it.modelName == "llama3.2:3b" }
        assertEquals("Llama, the small one", model.displayName)
        assertEquals("Quick; fine for summaries.", model.notes)
        assertEquals(131_072, model.capabilities.contextWindow)
        assertEquals(8_192, model.capabilities.maxOutputTokens)
    }

    // --- memory: a new space, its renaming, a memory, and the explorer's search ---

    @Test
    fun `memory spaces and memories are saved exactly as typed, and the search keeps every key`() {
        val robot = start().openSectionViaMore("Memory").tap("Spaces · 1").tapIcon("New space")
        val app = robot.app
        labelled("Space name").typeFast("Allotment, plot 7")
        labelled("What belongs in it (optional)").typeFast("Seeds, dates, what grew.")
        assertEquals("Allotment, plot 7", labelled("Space name").typed())
        robot.tap("Create space").assertShown("Seeds, dates, what grew.")

        robot.tap("Rename")
        labelled("Space name").also { it.performTextClearance() }.typeFast("Allotment plot 7b")
        assertEquals("Allotment plot 7b", labelled("Space name").typed())
        robot.tap("Save").waitUntilShown("Allotment plot 7b")
        assertTrue(runBlocking { app.graph.memoryService.observeSpaces().first() }.any { it.name == "Allotment plot 7b" })

        robot.tap("Its memories").tapIcon("New memory")
        labelled("Title").typeFast("Beans went in on 3 May")
        labelled("What to remember").typeFast("Runner beans, two rows; water every second day.")
        assertEquals("Beans went in on 3 May", labelled("Title").typed())
        assertEquals("Runner beans, two rows; water every second day.", labelled("What to remember").typed())
        robot.tap("Save memory").assertShown("Written by you")
        val saved = runBlocking { app.memories.list() }.single()
        assertEquals("Beans went in on 3 May", saved.title)
        assertEquals("Runner beans, two rows; water every second day.", saved.content)

        robot.back().tap("All memory")
        val search = hinted("Search memories")
        search.typeFast("runner beans water")
        assertEquals("runner beans water", search.typed())
        robot.waitUntilShown("% match").assertShown("Beans went in on 3 May")
        assertEquals("runner beans water", search.typed(), "the search result did not change the field")
    }

    // --- searches over lists ---

    @Test
    fun `the agent search and the app search keep every key`() {
        val robot = start { graph.start().join(); installSampleApps() }.openSection("Agents")
        val agents = hinted("Search agents")
        agents.typeFast("investigates")
        assertEquals("investigates", agents.typed())
        robot.assertShown("Researcher").assertNotShown("Writer")

        robot.openSectionViaMore("Models").tap("AI apps · 0").tap("Register manually")
        val apps = hinted("Search installed apps")
        apps.typeFast("terminal")
        assertEquals("terminal", apps.typed())
        robot.assertShown("Terminal").assertNotShown("Notes")
    }

    // --- chat: the message being written, and renaming the conversation in place ---

    @Test
    fun `a chat message and a conversation's new title are exactly what was typed`() {
        val robot = start { teamOnSimulatorHere() }.openSection("Agents").tapItemContaining("Investigates subjects")
            .tap("Chat").tap("New conversation")
        val app = robot.app
        val message = compose.onNode(hasSetTextAction() and hasContentDescription("Message to Researcher"))
        message.typeFast(MESSAGE)
        assertEquals(MESSAGE, message.typed())
        robot.tapIcon("Send").waitUntilShown("Simulated reply")
        assertEquals("", message.typed(), "the field is empty once the message is sent")
        val conversation = runBlocking { app.conversations.observeConversations(null).first() }.single()
        val sent = runBlocking { app.conversations.getEntries(conversation.id) }.first { it.message.role.name == "USER" }
        assertEquals(MESSAGE, sent.message.text)

        robot.tapIcon("Conversation options").tap("Rename")
        val title = compose.onNode(hasSetTextAction() and hasText("Title"))
        title.performTextClearance()
        title.typeFast("Tomatoes, again")
        assertEquals("Tomatoes, again", title.typed())
        compose.onAllNodes(hasText("Rename") and hasClickAction()).onFirst().performClick()
        robot.waitUntil { runBlocking { app.conversations.observeConversations(null).first() }.single().title == "Tomatoes, again" }
    }

    // --- the note sent with an answer to an agent's request ---

    @Test
    fun `the note to the agent is exactly what was typed`() {
        val model = ScriptedLlmClient.script(
            ScriptStep.Calls(listOf(ScriptedLlmClient.call("call_1", "workspace_write", buildJsonObject { put("path", "notes/todo.md"); put("content", "Buy milk") }))),
            ScriptStep.Reply("Done."),
        )
        val robot = AppRobot(compose).given {
            operatorOn(model)
            settings.update { it.copy(approvalTimeoutMinutes = 240, operatorEnabled = true) }
            val operator = agentNamed("Operator").profile
            val conversation = graph.agentChat.startConversation(operator.id, "My list")
            turns.launch { graph.agentChat.send(conversation.id, "Save my list: buy milk") }
        }.launch().also { robot = it }
        robot.waitUntilShown("1 request waits for your answer").tap("1 request waits for your answer").tap("Review")
        val note = labelled("Note for the agent (optional)")
        note.typeFast(NOTE)
        assertEquals(NOTE, note.typed())
        robot.scrollTo("Deny").tap("Deny").waitUntilShown("Denied. The agent is told")
        val request = runBlocking { robot.app.ownerRequestStore.observeRecent(10).first() }.single()
        assertEquals(OwnerRequestStatus.DENIED, request.status)
        assertEquals(NOTE, request.decisionNote)
    }

    // --- an AI app: text handed to it, and the owner's notes ---

    @Test
    fun `text handed to an AI app and notes about it are exactly what was typed`() {
        val robot = start { graph.start().join(); installSampleApps() }.openSectionViaMore("Models").tap("AI apps · 0").tap("Scan device")
            .scrollListTo("Example Chatbot").tapItemContaining("com.example.chatbot")
        val app = robot.app

        // The notes first: the hand-off's confirmation would cover their Save button for a few seconds.
        val notes = labelled("Your notes about this app")
        notes.typeFast("Good at lists; slow on long text.")
        assertEquals("Good at lists; slow on long text.", notes.typed())
        robot.tap("Save notes").waitUntilShown("Notes saved.")
        assertEquals("Good at lists; slow on long text.", runBlocking { app.appNamed("Example Chatbot") }.notes)

        robot.tap("Send text to app…")
        val handOff = labelled("Text to hand over")
        handOff.typeFast("Summarise: beans, peas, two rows.")
        assertEquals("Summarise: beans, peas, two rows.", handOff.typed())
        robot.tap("Send").waitUntilShown("Handed to Example Chatbot.")
        assertEquals(listOf("com.example.chatbot" to "Summarise: beans, peas, two rows."), app.launcher.handedOff)
    }

    // --- helpers ---

    /** The text field whose label reads [label] (a label stays while text is typed). */
    private fun labelled(label: String): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasText(label))

    /** The text field whose hint reads [hint]. The hint goes once something is typed, so the field is followed by its node. */
    private fun hinted(hint: String): SemanticsNodeInteraction {
        val id = compose.onNode(hasSetTextAction() and hasText(hint)).fetchSemanticsNode().id
        return compose.onNode(SemanticsMatcher("the field hinted \"$hint\"") { it.id == id })
    }

    /**
     * Types [text] one key at a time with the frame clock paused, letting a frame through every
     * [keysPerFrame] keys: the keyboard is faster than the screen, as it is for a fast typist.
     */
    private fun SemanticsNodeInteraction.typeFast(text: String, keysPerFrame: Int = 3): SemanticsNodeInteraction {
        try {
            performScrollTo()
        } catch (_: AssertionError) {
            // Not inside a scrolling column.
        }
        performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            text.forEachIndexed { index, key ->
                performTextInput(key.toString())
                if (index % keysPerFrame == keysPerFrame - 1) compose.mainClock.advanceTimeByFrame()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        return this
    }

    /** What the field holds now, exactly. */
    private fun SemanticsNodeInteraction.typed(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private suspend fun TestApp.teamOnSimulatorHere() {
        graph.start().join()
        for (agent in agents.getAll()) graph.agentManager.assignModel(agent.id, Simulator.MODEL_ID)
    }

    /** Stands in for Room, whose reads finish on another thread: while [hold] is set, reading an agent waits for it. */
    private class HeldAgentReads(private val store: AgentRepository) : AgentRepository by store {
        @Volatile var hold: CompletableDeferred<Unit>? = null

        override suspend fun get(id: AgentId): AgentProfile? {
            hold?.await()
            return store.get(id)
        }
    }

    private companion object {
        const val OBJECTIVE = "Research offline note apps, compare the best three and write a one-page plan."
        const val MESSAGE = "When should I sow tomatoes indoors?"
        const val NOTE = "Not now, thanks. Ask me tomorrow."
        const val KEY = "sk-test-0123456789abcdefXYZ"
    }
}
