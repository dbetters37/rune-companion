package com.aicontrolcenter.providers

import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ModelCapabilities
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCapabilityEstimatorTest {
    private fun estimate(name: String) = ModelCapabilityEstimator.estimate(name)

    @Test
    fun `an unknown name gets a chat model about which nothing is claimed`() {
        for (name in listOf("my-custom-finetune", "acme/frobnicator-v2", "x", "")) {
            assertEquals(ModelCapabilities(), estimate(name), name)
        }
        val unknown = estimate("my-custom-finetune")
        assertTrue(unknown.chat && unknown.streaming)
        assertFalse(unknown.toolCalling || unknown.vision || unknown.jsonOutput || unknown.embeddings)
        assertEquals(CapabilityTier.UNKNOWN, unknown.reasoning)
        assertEquals(CapabilityTier.UNKNOWN, unknown.coding)
        assertNull(unknown.contextWindow)
    }

    @Test
    fun `hosted families get their tools vision tier and smallest context window`() {
        val gpt4o = estimate("gpt-4o")
        assertTrue(gpt4o.toolCalling && gpt4o.vision && gpt4o.jsonOutput)
        assertEquals(CapabilityTier.GOOD, gpt4o.reasoning)
        assertEquals(128_000, gpt4o.contextWindow)

        assertEquals(CapabilityTier.STRONG, estimate("gpt-5").reasoning)
        assertEquals(CapabilityTier.STRONG, estimate("claude-sonnet-4-5").coding)
        assertEquals(200_000, estimate("claude-haiku-4-5").contextWindow)
        assertEquals(CapabilityTier.STRONG, estimate("gemini-2.5-pro").reasoning)
        assertEquals(1_000_000, estimate("gemini-2.5-flash").contextWindow)
        assertEquals(CapabilityTier.STRONG, estimate("grok-4").reasoning)
        assertTrue(estimate("mistral-large-latest").toolCalling)
        assertEquals(8_192, estimate("gpt-4").contextWindow, "the original gpt-4, not the larger later members")
    }

    @Test
    fun `a vendor prefix and letter case do not matter`() {
        assertEquals(estimate("gpt-4o"), estimate("openai/gpt-4o"))
        assertEquals(estimate("gpt-4o"), estimate(" GPT-4o "))
        assertEquals(estimate("claude-sonnet-4-5"), estimate("anthropic/claude-sonnet-4-5"))
    }

    @Test
    fun `small variants of a hosted family rank one tier lower`() {
        assertEquals(CapabilityTier.BASIC, estimate("gpt-4o-mini").reasoning)
        assertEquals(CapabilityTier.GOOD, estimate("gpt-5-nano").reasoning)
        assertEquals(CapabilityTier.BASIC, estimate("gemini-2.5-flash-lite").reasoning)
        assertTrue(estimate("gpt-4o-mini").toolCalling, "a smaller tier, not fewer features")
    }

    @Test
    fun `reasoning and coding models rank higher in their speciality only`() {
        val o4mini = estimate("o4-mini")
        assertEquals(CapabilityTier.STRONG, o4mini.reasoning)
        assertEquals(CapabilityTier.GOOD, o4mini.coding)

        val reasoner = estimate("deepseek-reasoner")
        assertEquals(CapabilityTier.STRONG, reasoner.reasoning)
        assertEquals(CapabilityTier.GOOD, reasoner.coding)

        val coder = estimate("qwen2.5-coder:32b")
        assertEquals(CapabilityTier.STRONG, coder.coding)
        assertEquals(CapabilityTier.GOOD, coder.reasoning)

        val r1 = estimate("deepseek-r1:8b")
        assertEquals(CapabilityTier.GOOD, r1.reasoning)
        assertEquals(CapabilityTier.BASIC, r1.coding)
    }

    @Test
    fun `open-weight models are tiered by size and given the local context floor`() {
        val small = estimate("llama3.2:3b")
        assertEquals(CapabilityTier.BASIC, small.reasoning)
        assertEquals(ModelCapabilityEstimator.LOCAL_CONTEXT_FLOOR, small.contextWindow)
        assertTrue(small.toolCalling)

        val large = estimate("llama-3.3-70b-versatile")
        assertEquals(CapabilityTier.GOOD, large.reasoning)
        assertEquals(8_192, large.contextWindow)

        assertEquals(CapabilityTier.BASIC, estimate("gemma3:270m").reasoning)
        assertEquals(CapabilityTier.GOOD, estimate("gpt-oss:20b").reasoning)

        val unknownFamily = estimate("llava:13b")
        assertEquals(CapabilityTier.GOOD, unknownFamily.reasoning, "a size alone is enough for a tier")
        assertEquals(8_192, unknownFamily.contextWindow)
    }

    @Test
    fun `a size in the name marks a self-hosted build of a hosted family`() {
        val hosted = estimate("mistral-small-latest")
        assertEquals(32_000, hosted.contextWindow)
        val local = estimate("mistral-small:24b")
        assertEquals(8_192, local.contextWindow)
        assertEquals(CapabilityTier.GOOD, local.reasoning)
        assertTrue(local.toolCalling)
    }

    @Test
    fun `tool calling is claimed only for families known to have it`() {
        for (name in listOf("llama3.2:3b", "qwen3:8b", "hermes3:8b", "phi4-mini:3.8b", "gpt-oss:20b", "mistral-nemo:12b", "deepseek-chat")) {
            assertTrue(estimate(name).toolCalling, name)
        }
        for (name in listOf("gemma3:4b", "llama2:7b", "phi3:mini", "deepseek-r1:8b", "llava:13b", "llama3.2-vision:11b", "tinyllama", "gemini-2.5-flash-image")) {
            assertFalse(estimate(name).toolCalling, name)
        }
    }

    @Test
    fun `vision is recognised from the family or the name`() {
        for (name in listOf("gpt-4o", "gemma3:4b", "llava:13b", "llama3.2-vision:11b", "qwen2.5vl:7b", "pixtral-12b", "moondream")) {
            assertTrue(estimate(name).vision, name)
        }
        for (name in listOf("gpt-3.5-turbo", "gemma3:1b", "gemma3:270m", "llama3.2:3b", "deepseek-chat")) {
            assertFalse(estimate(name).vision, name)
        }
    }

    @Test
    fun `models that are not chat models are marked so`() {
        for (name in listOf("text-embedding-3-small", "nomic-embed-text:latest", "bge-m3", "all-minilm:l6-v2")) {
            val caps = estimate(name)
            assertTrue(caps.embeddings, name)
            assertFalse(caps.chat || caps.toolCalling || caps.streaming, name)
        }
        for (name in listOf("whisper-large-v3", "dall-e-3", "tts-1", "gpt-4o-mini-tts", "gpt-4o-transcribe", "omni-moderation-latest", "imagen-4.0-generate-001")) {
            val caps = estimate(name)
            assertFalse(caps.chat || caps.embeddings || caps.toolCalling, name)
        }
    }

    @Test
    fun `what the provider reports overrides what the name suggests`() {
        val sized = ModelCapabilityEstimator.estimate("mystery", parameterSize = "70.6B")
        assertEquals(CapabilityTier.GOOD, sized.reasoning)
        assertEquals(8_192, sized.contextWindow)
        assertEquals(CapabilityTier.BASIC, ModelCapabilityEstimator.estimate("mystery:70b", parameterSize = "7.6B").reasoning)

        assertEquals(CapabilityTier.GOOD, ModelCapabilityEstimator.estimate("mystery:7b", thinks = true).reasoning)
        assertEquals(CapabilityTier.BASIC, ModelCapabilityEstimator.estimate("deepseek-r1:8b", thinks = false).reasoning)
    }
}
