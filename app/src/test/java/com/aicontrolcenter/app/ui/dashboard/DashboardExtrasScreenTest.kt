package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.health.SafeStartReason
import com.aicontrolcenter.app.health.SafeStartState
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.recordSampleUsage
import com.aicontrolcenter.app.ui.activity.UnpricedModelUi
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.usage.DailyLimitKind
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.health.BackgroundState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Dashboard's Phase 6 parts on screen (WP10a): what TalkBack hears (tiles and steps as phrases, done said in words,
 * not colour), where each part sits, 48 dp targets, and the tiles stacked for large fonts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class DashboardExtrasScreenTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun described(text: String) = compose.onAllNodes(hasContentDescription(text, substring = true)).onFirst()

    @Test
    fun `the Today tile reads each number with its limit, and Getting started says which step is done`() {
        val robot = AppRobot(compose).given {
            graph.start().join()
            registerSampleModel()
            recordSampleUsage()
        }.launch().also { robot = it }
        described("Answers, of 200").assertExists()
        described("Tokens, of 500,000").assertExists()
        described("Estimated cost").assertExists()
        described("Step 1").assertExists()
        described("Step 2, done").assertExists()
        robot.assertShown("Add your key · done")
        compose.onAllNodes(hasText("Done, hide this") and hasClickAction()).onFirst().assertHeightIsAtLeast(48.dp)
        compose.onAllNodes(hasText("Daily limits") and hasClickAction()).fetchSemanticsNodes().indices.forEach { index ->
            compose.onAllNodes(hasText("Daily limits") and hasClickAction())[index].performScrollTo().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `a model used today without a price is named, with no partial cost and a way to add the price`() {
        var opened = 0
        var openedModel: ModelId? = null
        compose.setContent {
            AppTheme(darkTheme = true) {
                TodayTileContent(
                    TodayTileUi(
                        used = true, answers = 18, tokens = 51_300, limits = DailyLimits.DEFAULT, cost = null,
                        costNote = "No cost shown: Llama 3.3 70B has no price yet.",
                        unpriced = listOf(UnpricedModelUi(ModelId("mdl_llama"), "Llama 3.3 70B")),
                        month = "This month: 43 answers · 128,800 tokens", reached = null, currency = "US$",
                    ),
                    navigation = navigation(onOpenModel = { opened++; openedModel = it }),
                )
            }
        }
        compose.onAllNodes(hasText("No cost shown: Llama 3.3 70B has no price yet.")).onFirst().assertExists()
        described("No cost Estimated cost, a price is missing").assertExists()
        described("18 Answers, of 200").assertExists()
        compose.onAllNodes(hasText("Add its price") and hasClickAction()).onFirst().assertHeightIsAtLeast(48.dp).performClick()
        // One model without a price: straight to its page (handoff-WP10a.md item 2.3).
        assertEquals(1, opened)
        assertEquals(ModelId("mdl_llama"), openedModel)
    }

    @Test
    fun `a reached limit is said in words in the tile, not by colour alone`() {
        compose.setContent {
            AppTheme(darkTheme = false) {
                TodayTileContent(
                    TodayTileUi(
                        used = true, answers = 200, tokens = 90_000, limits = DailyLimits.DEFAULT, cost = "US$0.40", costNote = null,
                        unpriced = emptyList(), month = null, reached = DailyLimits.DEFAULT.sentence(DailyLimitKind.CALLS), currency = "US$",
                    ),
                    navigation = navigation(),
                )
            }
        }
        described("200 Answers, of 200, reached").assertExists()
        compose.onAllNodes(hasText("Today's limit is reached")).onFirst().assertExists()
        compose.onAllNodes(hasText("Today's limit of 200 model answers is reached.", substring = true)).onFirst().assertExists()
    }

    @Test
    fun `safe start sits at the very top and Back to normal leaves it`() {
        val robot = AppRobot(compose).given {
            graph.start().join()
            safeStart.state.value = SafeStartState(on = true, reason = SafeStartReason.STARTS_WITHOUT_OK)
        }.launch().also { robot = it }
        robot.assertShown("Safe start is on")
            .assertShown(SafeStartReason.STARTS_WITHOUT_OK.ownerText)
            .assertShown("Interrupted work waits for you")
        val banner = compose.onAllNodes(hasText("Safe start is on")).onFirst().getUnclippedBoundsInRoot()
        val gettingStarted = compose.onAllNodes(hasText("Getting started")).onFirst().getUnclippedBoundsInRoot()
        assertTrue(banner.top < gettingStarted.top, "safe start comes first")
        compose.onAllNodes(hasText("Back to normal") and hasClickAction()).onFirst().assertHeightIsAtLeast(48.dp)
        robot.tap("Back to normal").assertNotShown("Safe start is on")
        assertEquals(1, robot.app.safeStart.left.get())
    }

    @Test
    fun `the phone's items follow the registry's list, each with an icon named for screen readers`() {
        val robot = AppRobot(compose).given {
            graph.start().join()
            backgroundHealth.current.value = BackgroundState(notificationsAllowed = false, batteryOptimised = true, backgroundRestricted = false, standbyBucket = null)
        }.launch().also { robot = it }
        described("On this phone · 2").assertExists()
        robot.assertShown("Notifications are off")
            .assertShown("Android may stop agents working in the background")
        assertTrue(compose.onAllNodes(hasClickLabel("Fix") and hasText("Notifications are off", substring = true)).fetchSemanticsNodes().isNotEmpty())
        described("Attention").assertExists()
        val registry = described("Needs attention").getUnclippedBoundsInRoot()
        val phone = described("On this phone").getUnclippedBoundsInRoot()
        assertTrue(registry.top < phone.top, "right after the registry's attention list")
        robot.tapItemContaining("Notifications are off").assertShown("Settings")
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `with large fonts the Today tile's numbers stack instead of squeezing into one row`() {
        AppRobot(compose).given {
            graph.start().join()
            recordSampleUsage()
        }.launch().also { robot = it }
        val answers = described("Answers, of 200").getUnclippedBoundsInRoot()
        val tokens = described("Tokens, of 500,000").getUnclippedBoundsInRoot()
        assertTrue(tokens.top >= answers.bottom, "one under the other at font scale 2.0")
    }

    private fun navigation(onOpenModels: () -> Unit = {}, onOpenModel: (ModelId) -> Unit = {}) = DashboardNavigation(
        onOpenAgent = {}, onOpenAttention = {}, onNewAgent = {}, onOpenAgents = {}, onOpenModels = onOpenModels, onOpenProviders = {},
        onOpenApps = {}, onSeeAllActivity = {}, onAddProvider = {}, onOpenProvider = {}, onOpenModel = onOpenModel,
    )

    private fun hasClickLabel(label: String) = SemanticsMatcher("has click label \"$label\"") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }
}
