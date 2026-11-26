package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.recordSampleUsage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Wave 3 hand-off (WP8; handoff-WP10a.md item 2.1): the Dashboard route hands the Today tile the usage reports, the
 * owner's prices and the owner's zone, so the tile names a model used today without a price instead of showing a
 * partial cost, and adds this month. (Item 2.5, the attention card's empty line, is pinned by `KeyJourneysTest`.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DashboardWiringJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    @Test
    fun `the Today tile names the model used today without a price, says no cost, and shows this month`() {
        val robot = AppRobot(compose).given {
            graph.start().join()
            recordSampleUsage()
        }.launch().also { robot = it }

        robot.waitUntilShown("No cost shown: Llama 3.3 70B has no price yet.")
            .assertShown("This month:")
            .assertShown("Add its price")
            .assertNotShown("The cost counts only the models you entered a price for.")
    }
}
