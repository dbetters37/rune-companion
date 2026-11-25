package com.aicontrolcenter.framework.llm

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D29: aliases and dated versions of a model are the same model; another model is not. */
class ModelNamesTest {
    @Test
    fun `an alias and the dated version a provider resolves it to are the same model`() {
        val same = listOf(
            "grok-4-latest" to "grok-4-0709",
            "gpt-4o" to "gpt-4o-2024-08-06",
            "claude-3-5-sonnet-latest" to "claude-3-5-sonnet-20241022",
            "gemini-2.5-flash-preview" to "gemini-2.5-flash-preview-05-20",
            "models/gemini-2.5-pro" to "gemini-2.5-pro",
            "mistral-large-latest" to "mistral-large-2411",
            "gemma3" to "gemma3:latest",
            "x-ai/grok-4" to "grok-4-0709",
            "Grok-4-Beta" to "grok-4",
            "claude-3-5-sonnet@20241022" to "claude-3-5-sonnet",
        )
        for ((a, b) in same) assertTrue(ModelNames.sameModel(a, b), "$a and $b")
    }

    @Test
    fun `another model, a variant or a number that is not a date is not the same`() {
        val different = listOf(
            "gpt-4o" to "gpt-4o-mini",
            "grok-4" to "grok-3",
            "grok-4" to "grok-4-fast",
            "llama3-70b-8192" to "llama3-70b-4096",
            "claude-3-5-sonnet" to "claude-3-5-haiku",
        )
        for ((a, b) in different) assertFalse(ModelNames.sameModel(a, b), "$a and $b")
    }

    @Test
    fun `blank names are never the same, and a name that is only a stamp keeps it`() {
        assertFalse(ModelNames.sameModel("", ""))
        assertFalse(ModelNames.sameModel("grok-4", " "))
        assertEquals("grok-4", ModelNames.base("grok-4-latest"))
        assertEquals("llama3-70b-8192", ModelNames.base("llama3-70b-8192"))
        assertEquals("-latest", ModelNames.base("-latest"))
    }
}
