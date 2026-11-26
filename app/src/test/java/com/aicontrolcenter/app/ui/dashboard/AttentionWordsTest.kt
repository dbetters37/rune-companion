package com.aicontrolcenter.app.ui.dashboard

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A provider check's raw status never reaches the Dashboard (D3A-14): only a sentence for the owner, else the app's own words. */
class AttentionWordsTest {
    @Test
    fun `only an owner sentence is shown, never a raw status`() {
        assertEquals("Connection refused.", ownerSentence("Connection refused."))
        assertEquals("The server did not answer in time.", ownerSentence("  The server did not answer in time.  "))
        for (raw in listOf(null, "", "ok", "OK.", "503", "ECONNREFUSED", "timeout", "HTTP 502", "Ok ok.", "{\"error\":\"x\"}")) {
            assertNull(ownerSentence(raw), raw.toString())
        }
    }
}
