package com.aicontrolcenter.app.screenshots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.sampleQuestion
import com.aicontrolcenter.app.testing.teamOnSimulator
import com.aicontrolcenter.app.ui.questions.ANSWER_FIELD
import com.aicontrolcenter.app.ui.questions.QuestionTexts
import com.aicontrolcenter.app.ui.tasks.WRITE_TO_THE_TEAM
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.UsageSource
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** WP11a's own sizes: wide landscape and the 360 dp phone (scratchpad/phase6/wave3-ownership.md, rule 7). */
private const val WIDE_LANDSCAPE = "w900dp-h600dp-xhdpi"
private const val SMALL_PHONE = "w360dp-h740dp-xhdpi"

/**
 * Pictures of the owner answering an agent's question (rule 13), beside the Approval screen it must never look like;
 * the "Waiting for you" list with both; the code warning (rule 32); an objective paused by today's limit with its cost
 * line and the notes composer; and the New objective form with its warnings. Dark, light, wide, 360 dp, and the
 * question at font scale 1.5 and 2.0. Review material, not golden files: recorded into `build/outputs/roborazzi`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class QuestionScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private val root = TaskId("obj_porto")

    /** The team on the simulator; Researcher asks where the weekend should be, and Operator waits for an approval. */
    private suspend fun TestApp.asking() {
        teamOnSimulator()
        val researcher = agentNamed("Researcher").profile.id
        objective(TaskStatus.RUNNING)
        raise(
            sampleQuestion(
                researcher, createdAt = clock.current, taskId = TaskId("tsk_hotels"), rootId = root,
                question = "Should the hotels be in the old town (Ribeira) or near the beach (Foz)? The prices differ by about a third.",
                options = listOf("Ribeira, the old town", "Foz, near the beach", "Whichever is cheaper"),
                reason = "The brief does not say where to stay.",
                // The owner's answer window (Q3: 20 minutes), so the pictures show what the owner sees (D3A-18b).
                minutes = 20,
            ),
        )
        raise(sampleApproval(agentNamed("Operator").profile.id, createdAt = clock.current + 1, minutes = 240))
    }

    private suspend fun TestApp.objective(status: TaskStatus, detail: String? = null) {
        val director = director().profile.id
        val researcher = agentNamed("Researcher").profile.id
        val now = clock.current
        tasks.upsert(
            TaskRecord(
                id = root, rootId = root, parentId = null, kind = TaskKind.OBJECTIVE, title = "Plan a weekend in Porto for two",
                instructions = "Plan a weekend in Porto for two in May: where to stay, what to see and how to get there, for under €600.",
                assignee = director, requestedBy = TaskRequester.Owner, status = status, statusDetail = detail, createdAt = now - 20 * 60_000L,
                updatedAt = now, startedAt = now - 20 * 60_000L,
            ),
        )
        tasks.upsert(
            TaskRecord(
                id = TaskId("tsk_hotels"), rootId = root, parentId = root, kind = TaskKind.WORK, title = "Find three hotels", instructions = "Find hotels.",
                assignee = researcher, requestedBy = TaskRequester.Agent(director), status = if (status == TaskStatus.RUNNING) TaskStatus.WAITING_FOR_OWNER else TaskStatus.PAUSED,
                statusDetail = if (status == TaskStatus.RUNNING) "Waiting for the owner's answer: Should the hotels be in the old town…" else DailyLimits.PAUSED_TEXT,
                createdAt = now - 18 * 60_000L, updatedAt = now,
            ),
        )
        usage.record(
            ModelCallSample(
                at = now, modelId = ModelId("mdl_grok"), modelLabel = "Grok", providerId = ProviderId("prv_xai"), providerLabel = "xAI",
                locality = Locality.CLOUD, rootTaskId = root, taskId = TaskId("tsk_hotels"), source = UsageSource.OBJECTIVE, outcome = CallOutcome.OK,
                inputTokens = 18_200, outputTokens = 3_100, answerMs = 2_400,
            ),
        )
        // The cloud call's own log entry, as the gateway writes it (D3A-5: the header's privacy line reads it).
        activityLog.record(
            com.aicontrolcenter.core.activity.ActivityEvent(
                id = com.aicontrolcenter.core.EventId("evt_porto_grok"), at = now, type = com.aicontrolcenter.core.activity.ActivityType.MODEL_CALL,
                agentId = researcher, taskId = TaskId("tsk_hotels"), rootTaskId = root, title = "Model call", detail = "Grok via xAI",
                egress = com.aicontrolcenter.core.activity.EgressInfo(
                    destinationHost = "api.x.ai", locality = Locality.CLOUD, providerId = ProviderId("prv_xai"), purpose = "prompt and conversation",
                ),
            ),
        )
    }

    private fun openQuestion(dark: Boolean = true): AppRobot {
        val started = AppRobot(compose).given { asking() }.launch(dark).also { robot = it }
        started.app.graph.requestApproval(RequestId("req_question"))
        return started.waitUntilShown(QuestionTexts.NOT_A_PERMISSION)
    }

    @Test
    fun question() {
        val robot = openQuestion()
        shoot("p6_question_1_screen_phone")
        robot.scrollToEnd()
        shoot("p6_question_2_answers_phone")
    }

    @Test
    fun questionLight() {
        openQuestion(dark = false)
        shoot("p6_question_1_screen_phone_light")
    }

    @Test
    fun questionBesideAnApproval() {
        val robot = openQuestion()
        shoot("p6_question_vs_approval_1_question")
        robot.back().waitUntilShown("1 request and 1 question wait for your answer")
            .tap("1 request and 1 question wait for your answer")
            .tap("Review")
            .waitUntilShown("Exactly what would happen".uppercase())
        shoot("p6_question_vs_approval_2_approval")
    }

    @Test
    fun codeWarning() {
        val robot = openQuestion()
        compose.onNode(hasContentDescription(ANSWER_FIELD) and hasSetTextAction()).performTextReplacement("482913")
        compose.waitForIdle()
        robot.scrollTo(QuestionTexts.SEND).tap(QuestionTexts.SEND).waitUntilShown("Is this a code?")
        // The button itself, not the warning that names it (D3A-18a): its exact text and a click action.
        compose.onNode(androidx.compose.ui.test.hasText(QuestionTexts.SEND_ANYWAY) and androidx.compose.ui.test.hasClickAction()).performScrollTo()
        compose.waitForIdle()
        shoot("p6_question_3_code_second_tap_phone")
    }

    @Test
    @Config(qualifiers = WIDE_LANDSCAPE)
    fun questionWide() {
        openQuestion()
        shoot("p6_question_1_screen_wide")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun questionNarrow() {
        val robot = openQuestion()
        shoot("p6_question_1_screen_360dp")
        robot.scrollToEnd()
        shoot("p6_question_2_answers_360dp")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = 1.5f)
    fun questionLargeFont() {
        openQuestion()
        shoot("p6_question_1_screen_360dp_font150")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = 2.0f)
    fun questionLargestFont() {
        val robot = openQuestion()
        shoot("p6_question_1_screen_360dp_font200")
        robot.scrollToEnd()
        shoot("p6_question_2_answers_360dp_font200")
    }

    private fun waitingList(dark: Boolean = true): AppRobot =
        AppRobot(compose).given { asking() }.launch(dark).also { robot = it }
            .waitUntilShown("1 request and 1 question wait for your answer")
            .tap("1 request and 1 question wait for your answer")

    @Test
    fun waitingForYou() {
        waitingList()
        shoot("p6_waiting_for_you_phone")
    }

    @Test
    fun waitingForYouLight() {
        waitingList(dark = false)
        shoot("p6_waiting_for_you_phone_light")
    }

    @Test
    @Config(qualifiers = WIDE_LANDSCAPE)
    fun waitingForYouWide() {
        AppRobot(compose).given { asking() }.launch().also { robot = it }.openSection("Waiting for you")
        shoot("p6_waiting_for_you_wide")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun waitingForYouNarrow() {
        waitingList()
        shoot("p6_waiting_for_you_360dp")
    }

    private fun pausedObjective(dark: Boolean = true): AppRobot =
        AppRobot(compose).given {
            teamOnSimulator()
            objective(TaskStatus.PAUSED, DailyLimits.PAUSED_TEXT)
        }.launch(dark).also { robot = it }.openSection("Tasks").tapItemContaining("Plan a weekend in Porto")

    @Test
    fun objectivePausedByTheLimit() {
        pausedObjective()
        shoot("p6_objective_paused_by_limit_phone")
    }

    @Test
    fun objectivePausedByTheLimitLight() {
        pausedObjective(dark = false)
        shoot("p6_objective_paused_by_limit_phone_light")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun objectivePausedByTheLimitNarrow() {
        pausedObjective()
        shoot("p6_objective_paused_by_limit_360dp")
    }

    @Test
    @Config(qualifiers = WIDE_LANDSCAPE)
    fun objectivePausedByTheLimitWide() {
        pausedObjective()
        shoot("p6_objective_paused_by_limit_wide")
    }

    /** Two more unfinished steps, so the composer has four recipients (D3A-17: every one wraps into view). */
    private suspend fun TestApp.moreSteps() {
        val director = director().profile.id
        val now = clock.current
        listOf("tsk_trains" to ("Writer" to "Compare the train and the flight"), "tsk_sights" to ("Analyst" to "Plan two days of sights")).forEach { (id, who) ->
            tasks.upsert(
                TaskRecord(
                    id = TaskId(id), rootId = root, parentId = root, kind = TaskKind.WORK, title = who.second, instructions = "${who.second}.",
                    assignee = agentNamed(who.first).profile.id, requestedBy = TaskRequester.Agent(director), status = TaskStatus.RUNNING,
                    createdAt = now - 10 * 60_000L, updatedAt = now,
                ),
            )
        }
    }

    private fun notesOpen(): AppRobot = AppRobot(compose).given {
        teamOnSimulator()
        objective(TaskStatus.RUNNING)
        moreSteps()
    }.launch().also { robot = it }.openSection("Tasks").tapItemContaining("Plan a weekend in Porto")
        // With large fonts the tabs sit below the tall header, in a lazy list: bring them in first.
        .scrollListTo("Conversation").tap("Conversation")

    @Test
    fun notesComposer() {
        notesOpen()
        shoot("p6_objective_notes_folded_phone")
        robot!!.tap(WRITE_TO_THE_TEAM)
        shoot("p6_objective_notes_composer_phone")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = 2.0f)
    fun notesComposerLargestFont() {
        notesOpen().scrollListTo(WRITE_TO_THE_TEAM).tap(WRITE_TO_THE_TEAM).scrollListTo("To")
        shoot("p6_objective_notes_composer_360dp_font200")
    }

    /** D3A-4: the task screen's own cost so far, with what a token is and that money is an estimate. */
    @Test
    fun taskCost() {
        AppRobot(compose).given {
            teamOnSimulator()
            objective(TaskStatus.PAUSED, DailyLimits.PAUSED_TEXT)
            prices.set(com.aicontrolcenter.core.usage.ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 3_000_000, outputPerMTokMicros = 15_000_000))
        }.launch().also { robot = it }.openSection("Tasks").tapItemContaining("Plan a weekend in Porto")
            .tapItemContaining("Find three hotels").waitUntilShown("So far: about US$")
        shoot("p6_task_cost_phone")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = 2.0f)
    fun waitingForYouLargestFont() {
        waitingList()
        shoot("p6_waiting_for_you_360dp_font200")
    }

    private fun newObjective(dark: Boolean = true): AppRobot =
        AppRobot(compose).given {
            teamOnSimulator()
            backgroundHealth.current.value = BackgroundState(notificationsAllowed = true, batteryOptimised = true, backgroundRestricted = false, standbyBucket = null)
            storage.freeBytes = 1L
            storage.check()
        }.launch(dark).also { robot = it }.openSection("Tasks").tap("New objective")

    @Test
    fun newObjectiveWarnings() {
        val robot = newObjective()
        shoot("p6_new_objective_1_top_phone")
        robot.scrollToEnd()
        shoot("p6_new_objective_2_limits_phone")
    }

    @Test
    fun newObjectiveWarningsLight() {
        newObjective(dark = false)
        shoot("p6_new_objective_1_top_phone_light")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun newObjectiveWarningsNarrow() {
        val robot = newObjective()
        shoot("p6_new_objective_1_top_360dp")
        robot.scrollToEnd()
        shoot("p6_new_objective_2_limits_360dp")
    }

    @Test
    @Config(qualifiers = WIDE_LANDSCAPE)
    fun newObjectiveWarningsWide() {
        newObjective()
        shoot("p6_new_objective_1_top_wide")
    }
}
