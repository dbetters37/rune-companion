package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.registry.CapabilityTier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ModelTraitsTest {

    @Test
    fun `an id is matched to its own generation and not to a shorter alias`() {
        assertEquals(RefusalFallback.SERVER_DEFAULT, ModelTraits.of("claude-fable-5-2").fallback)
        assertEquals(RefusalFallback.OPUS_4_8, ModelTraits.of("claude-fable-5-1").fallback)
        assertEquals(RefusalFallback.NONE, ModelTraits.of("claude-fable-5").fallback)
        assertTrue(ModelTraits.of("claude-sonnet-5-5").midConversationSystem)
        assertFalse(ModelTraits.of("claude-sonnet-5").midConversationSystem)
        assertEquals(ModelTraits.of("claude-opus-5-5"), ModelTraits.of("  Claude-Opus-5-5 "))
    }

    @Test
    fun `a dated snapshot behaves like its alias`() {
        assertEquals(ModelTraits.of("claude-opus-5"), ModelTraits.of("claude-opus-5-20260601"))
        assertEquals(ModelTraits.of("claude-fable-5"), ModelTraits.of("claude-fable-5-20260115"))
        assertEquals(ModelTraits.of("claude-opus-4-5"), ModelTraits.of("claude-opus-4-5-20251101"))
        assertEquals(ThinkingRequest.OMIT, ModelTraits.of("claude-haiku-4-5-20251001").thinking)
    }

    @Test
    fun `an unknown id gets the request no model rejects`() {
        for (id in listOf("", "gpt-like-model", "claude-opus-50", "anthropic.claude-opus-5-5", "claude-opus-5-5x")) {
            val traits = ModelTraits.of(id)
            assertEquals(ThinkingRequest.OMIT, traits.thinking, id)
            assertFalse(traits.effort, id)
            assertEquals(RefusalFallback.NONE, traits.fallback, id)
            assertFalse(traits.midConversationSystem, id)
        }
    }

    @Test
    fun `families are ranked coarsely and unknown names not at all`() {
        assertEquals(CapabilityTier.STRONG, ModelTraits.tierOf("claude-fable-5-2"))
        assertEquals(CapabilityTier.STRONG, ModelTraits.tierOf("claude-sonnet-5-5"))
        assertEquals(CapabilityTier.GOOD, ModelTraits.tierOf("claude-haiku-5-5"))
        assertEquals(CapabilityTier.UNKNOWN, ModelTraits.tierOf("something-else"))
    }
}
