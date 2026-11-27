package com.aicontrolcenter.app.ui.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.core.operator.ScreenServiceState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tools › Screen control's "Set up phone control" on screen: five rows, each one target of at least 48 dp that TalkBack
 * reads as "Step N, title, Done/Not done" and that calls the action leading to where the step is done (step 5 scrolls
 * to the authorised apps); the statuses and the summary follow the state; Check again stays.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ScreenControlChecklistTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = ScreenControlActions(
        onOpenAccessibilitySettings = { calls += "accessibility settings" },
        onOpenAppInfo = { calls += "app info" },
        onCheckAgain = { calls += "check again" },
        onOpenOperatorSettings = { calls += "settings at phone operator" },
        onOpenScreenControlSettings = { calls += "settings at screen control" },
        onStop = { calls += "stop" },
        onResume = { calls += "resume" },
        onOpenPicker = { calls += "picker" },
        onClosePicker = {},
        onAuthorise = { _, _ -> },
        onWithdraw = {},
        onSetAccess = { _, _, _ -> },
    )

    private val notes = OperatorAppUi(
        packageName = "org.example.notes", label = "Notes", authorised = "Authorised today",
        certificate = "Checked: this exact app. A look-alike app with the same name is not trusted.",
        fingerprint = null, inEffect = true, agents = emptyList(),
    )

    private fun stateOf(
        operator: Boolean = false,
        screenControl: Boolean = false,
        service: ScreenServiceState = ScreenServiceState.NOT_ENABLED,
        apps: List<OperatorAppUi> = emptyList(),
        sdkInt: Int = 35,
    ): ScreenControlUiState {
        val status = serviceStatusOf(service)
        return ScreenControlUiState(
            isLoading = false, service = status, operatorEnabled = operator, screenControlEnabled = screenControl, apps = apps,
            checklist = setupChecklistOf(operator, screenControl, status, apps, sdkInt),
        )
    }

    private var state by mutableStateOf(stateOf())

    private fun show() {
        compose.setContent { AppTheme(darkTheme = true) { ScreenControlTab(state, actions) } }
        compose.waitForIdle()
    }

    private fun row(number: Int) = compose.onNode(hasContentDescription("Step $number, ", substring = true) and hasClickAction())

    private fun spoken(number: Int): String =
        row(number).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun leadsTo(label: String) = SemanticsMatcher("click label \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    /** The authorised apps' header, on screen (a lazy list composes it only once it is near). */
    private fun appsHeaderShown(): Boolean = try {
        compose.onNode(hasContentDescription("Authorised apps") and isHeading()).assertIsDisplayed()
        true
    } catch (_: AssertionError) {
        false
    }

    @Test
    fun `each row is one 48 dp target, read as step, title and status, that opens where the step is done`() {
        show()
        compose.onNode(hasText(SETUP_TITLE) and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("0 of 5 done").assertIsDisplayed()

        val expected = listOf(
            Triple(
                "Step 1, Turn on Phone operator, Not done. In Settings, switch on Phone operator.",
                "Open Settings at Phone operator", "settings at phone operator",
            ),
            Triple(
                "Step 2, Turn on Screen control, Not done. Do step 1 first, then switch on Screen control.",
                "Open Settings at Screen control", "settings at screen control",
            ),
            Triple(
                "Step 3, Allow restricted settings (Android blocks this for apps installed from a file), Not done. " +
                    "Tap the three dots (⋮) at the top right, then Allow restricted settings.",
                "Open App info", "app info",
            ),
            Triple(
                "Step 4, Turn on AI Control Center in Accessibility, Not done. " +
                    "Find “AI Control Center operator” (often under Downloaded apps) and switch it on.",
                "Open accessibility settings", "accessibility settings",
            ),
        )
        expected.forEachIndexed { index, (spoken, label, action) ->
            val number = index + 1
            row(number).performScrollTo().assert(isButton).assert(leadsTo(label)).assertHeightIsAtLeast(48.dp)
            assertEquals(spoken, spoken(number))
            calls.clear()
            row(number).performClick()
            compose.waitForIdle()
            assertEquals(listOf(action), calls, "step $number")
        }

        // Step 5 leads to the authorised apps on this same screen, below Stop.
        assertFalse(appsHeaderShown(), "below the fold before")
        row(5).performScrollTo().assert(isButton).assert(leadsTo("Show the authorised apps")).assertHeightIsAtLeast(48.dp)
        assertEquals("Step 5, Choose the apps it may use, Not done. Pick at least one app agents may see and use.", spoken(5))
        calls.clear()
        row(5).performClick()
        compose.waitForIdle()
        assertTrue(appsHeaderShown(), "scrolled to the authorised apps")
        assertEquals(emptyList(), calls, "it only scrolls; the owner picks an app with Authorise an app")
        compose.onNode(hasText("Authorise an app") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `the statuses and the summary follow the state, from none done to all set`() {
        show()
        (1..5).forEach { assertTrue(", Not done" in spoken(it), "step $it") }

        state = stateOf(operator = true, screenControl = true, service = ScreenServiceState.NOT_ENABLED)
        compose.waitForIdle()
        compose.onNodeWithText("2 of 5 done").assertIsDisplayed()
        assertEquals("Step 1, Turn on Phone operator, Done", spoken(1))
        assertEquals("Step 2, Turn on Screen control, Done", spoken(2))
        assertTrue(spoken(3).startsWith("Step 3, Allow restricted settings (Android blocks this for apps installed from a file), Not done"))
        assertTrue(spoken(4).startsWith("Step 4, Turn on AI Control Center in Accessibility, Not done"))
        assertTrue(spoken(5).startsWith("Step 5, Choose the apps it may use, Not done"))

        state = stateOf(operator = true, screenControl = true, service = ScreenServiceState.READY, apps = listOf(notes))
        compose.waitForIdle()
        compose.onNodeWithText(SETUP_ALL_SET).assertIsDisplayed()
        assertEquals(
            listOf(
                "Step 1, Turn on Phone operator, Done",
                "Step 2, Turn on Screen control, Done",
                "Step 3, Allow restricted settings (Android blocks this for apps installed from a file), Done",
                "Step 4, Turn on AI Control Center in Accessibility, Done",
                "Step 5, Choose the apps it may use, Done",
            ),
            (1..5).map(::spoken),
        )
        // The rows still lead where the steps are done, to check or undo them.
        row(1).performClick()
        compose.waitForIdle()
        assertEquals(listOf("settings at phone operator"), calls)

        state = stateOf(operator = false, screenControl = true, service = ScreenServiceState.READY, apps = listOf(notes))
        compose.waitForIdle()
        compose.onNodeWithText("4 of 5 done").assertIsDisplayed()
        assertEquals("Step 1, Turn on Phone operator, Not done. In Settings, switch on Phone operator.", spoken(1))
    }

    @Test
    fun `below Android 13 step 3 is not needed on this phone`() {
        state = stateOf(sdkInt = 32)
        show()
        assertEquals("Step 3, Allow restricted settings (Android blocks this for apps installed from a file), Not needed on this phone", spoken(3))
        compose.onNodeWithText("0 of 4 done").assertIsDisplayed()
    }

    @Test
    fun `Check again reads the service again while it is not on, and is gone once it is`() {
        show()
        compose.onNode(hasText("Check again") and hasClickAction()).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        assertEquals(listOf("check again"), calls)

        state = stateOf(service = ScreenServiceState.READY)
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("Check again")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the old steps and switch rows are gone, so nothing is said twice`() {
        state = stateOf(service = ScreenServiceState.RESTRICTED)
        show()
        for (gone in listOf("To switch it on", "The switches in Settings", "Open Settings", "Open App info", "Open accessibility settings", "Android's screen-control permission")) {
            assertTrue(compose.onAllNodes(hasText(gone, substring = true, ignoreCase = true)).fetchSemanticsNodes().isEmpty(), gone)
        }
        // Stop and the authorised apps stay.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Stop screen control"))
        compose.onNode(hasText("Stop screen control") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("No apps authorised"))
        compose.onNode(hasText("Authorise an app") and hasClickAction()).assertIsDisplayed()
    }
}
