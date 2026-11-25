package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.registry.Locality
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WP4 item 8: every preset says plainly what using it costs; what can change says "check the page; terms change". */
class ProviderPresetsCostTest {
    private fun costLine(id: String) = ProviderPresets.byId(id)!!.description.lines().last()

    @Test
    fun `every preset ends with one plain line on cost`() {
        for (preset in ProviderPresets.all) {
            val lines = preset.description.lines()
            assertEquals(2, lines.size, "${preset.id}: what it is, then what it costs")
            assertTrue(lines.last().endsWith("."), preset.id)
        }
    }

    @Test
    fun `services on the internet are hedged, because their terms change`() {
        for (preset in ProviderPresets.all.filter { Locality.infer(it.kind, it.baseUrl) == Locality.CLOUD && it.needsApiKey }) {
            val line = preset.description.lines().last()
            assertTrue("terms change" in line && "page" in line, "${preset.id}: $line")
        }
    }

    @Test
    fun `the free tiers and their catches are named where the owner's note found them`() {
        assertTrue("free tier" in costLine(ProviderPresets.GEMINI) && "improve its products" in costLine(ProviderPresets.GEMINI))
        assertTrue(":free" in costLine(ProviderPresets.OPENROUTER) && "keep what you send" in costLine(ProviderPresets.OPENROUTER))
        assertTrue("free plan" in costLine(ProviderPresets.GROQ) && "per minute" in costLine(ProviderPresets.GROQ))
        assertTrue("No free tier" in costLine(ProviderPresets.XAI))
        for (local in listOf(ProviderPresets.OLLAMA_ON_PHONE, ProviderPresets.OLLAMA_ON_NETWORK, ProviderPresets.LM_STUDIO, ProviderPresets.LLAMA_CPP)) {
            assertTrue(costLine(local).startsWith("Free and private"), local)
        }
    }
}
