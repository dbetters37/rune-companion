package com.aicontrolcenter.app.operator

import com.aicontrolcenter.app.ui.tools.SWITCHED_OFF
import com.aicontrolcenter.device.screen.ScreenSwitchedOffSentences
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Wave 3 hand-off (WP8; handoff-WP11b.md section 2, D7): when the device layer gives no sentence, Tools › Screen says the
 * device's own words for screen control switched off after the app was stopped, never a second wording of it.
 */
class SwitchedOffWordsTest {
    @Test
    fun `Tools › Screen's fallback sentence is the device layer's own`() {
        assertEquals(ScreenSwitchedOffSentences.STOPPED, SWITCHED_OFF)
    }
}
