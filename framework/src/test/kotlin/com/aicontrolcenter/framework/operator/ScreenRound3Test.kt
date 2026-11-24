package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.testkit.FakeControl
import com.aicontrolcenter.testkit.FakeScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 3, C1 (rest of S2), through the tool: in an app the owner set to ask only before
 * consequential actions, a yes-button beside a tappable row that shows a price or an amount asks.
 * The verifier's reproduction (V6Last.yesButtonBesideAClickablePrice).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenRound3Test {
    private val pkg = "com.example.bank"

    private suspend fun riskOfTapping(label: String, row: FakeControl): RiskLevel {
        val f = OperatorFixture(initialScreen = FakeScreen(pkg, "Step 2", listOf(row, FakeControl(text = label, nodeKey = "n"))))
        f.authorise(pkg, "Bank", ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY)
        return f.assess("screen.tap", "exact_text" to label).risk
    }

    @Test
    fun `a yes-button beside a tappable row with a price or an amount asks before it is tapped`() = runTest {
        for (label in listOf("OK", "Continue", "Yes", "Next", "Save")) {
            val rows = mapOf(
                "a text that is not clickable" to FakeControl(role = "TextView", text = "Total: 249.00 EUR", clickable = false, nodeKey = "t"),
                "the same text on a clickable row" to FakeControl(role = "View", text = "Total: 249.00 EUR", clickable = true, nodeKey = "t"),
                "a clickable row whose label holds it" to FakeControl(role = "View", label = "To Mallory · 5,000.00 EUR", clickable = true, nodeKey = "t"),
                "a clickable row with an amount and no currency" to FakeControl(role = "View", text = "Summe 1.299,00", clickable = true, nodeKey = "t"),
            )
            for ((name, row) in rows) assertEquals(RiskLevel.CONSEQUENTIAL, riskOfTapping(label, row), "\"$label\" beside $name")
        }
    }

    @Test
    fun `the approval says what on the screen made the button ask`() = runTest {
        val f = OperatorFixture(
            initialScreen = FakeScreen(pkg, "Step 2", listOf(FakeControl(role = "View", text = "Total: 249.00 EUR", clickable = true, nodeKey = "t"), FakeControl(text = "Next", nodeKey = "n"))),
        )
        f.authorise(pkg, "Bank", ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY)
        val assessment = f.assess("screen.tap", "exact_text" to "Next")
        assertTrue("249.00 EUR" in assessment.preview, assessment.preview)
    }

    @Test
    fun `a yes-button beside controls without a price is still an ordinary tap there`() = runTest {
        // A word on another control does not decide (unchanged), nor does a count.
        assertEquals(RiskLevel.MODIFYING, riskOfTapping("OK", FakeControl(role = "View", text = "Delete all 248 photos for good", clickable = true, nodeKey = "t")))
        assertEquals(RiskLevel.MODIFYING, riskOfTapping("Next", FakeControl(role = "View", text = "Step 2 of 4", clickable = true, nodeKey = "t")))
        assertEquals(RiskLevel.MODIFYING, riskOfTapping("Next", FakeControl(role = "Button", text = "Back", clickable = true, nodeKey = "t")))
    }
}
