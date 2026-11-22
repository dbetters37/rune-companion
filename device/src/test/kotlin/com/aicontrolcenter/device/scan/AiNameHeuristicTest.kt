package com.aicontrolcenter.device.scan

import org.junit.Test
import kotlin.test.assertEquals

class AiNameHeuristicTest {

    private data class Case(val label: String, val packageName: String, val expected: Boolean)

    private val cases = listOf(
        // Names that say what the app is.
        Case("Pocket AI", "com.example.pocket", true),
        Case("AI Chat", "com.example.talk", true),
        Case("OpenAI Playground", "com.example.playground", true),
        Case("AIChat", "com.example.aichat", true),
        Case("xAI Console", "com.example.console", true),
        Case("Otter.ai", "com.example.otter", true),
        Case("Character.AI", "com.example.character", true),
        Case("LocalGPT", "com.example.local", true),
        Case("My GPTs", "com.example.mine", true),
        Case("Chatbot Pro", "com.example.pro", true),
        Case("LLM Hub", "com.example.hub", true),
        Case("AnythingLLM", "com.example.anything", true),
        Case("Ollama Remote", "com.example.remote", true),
        // Neutral label, telling package id.
        Case("Nova", "ai.nova.app", true),
        Case("Smart Keyboard", "com.example.gptkeyboard", true),
        Case("Notes", "com.example.ai.notes", true),
        Case("Runner", "com.example.mnnllm.android", true),
        Case("Helper", "com.openai.helper", true),
        Case("Server", "org.example.llamacpp_server", true),

        // Messaging and mail: "chat" alone must never match.
        Case("WhatsApp", "com.whatsapp", false),
        Case("Messages", "com.google.android.apps.messaging", false),
        Case("Telegram", "org.telegram.messenger", false),
        Case("Signal", "org.thoughtcrime.securesms", false),
        Case("Chat", "com.google.android.apps.dynamite", false),
        Case("WeChat", "com.tencent.mm", false),
        Case("Snapchat", "com.snapchat.android", false),
        Case("Gmail", "com.google.android.gm", false),
        Case("Mail", "com.example.mail", false),
        Case("Live Chat Support", "com.example.support", false),
        // Words that merely contain the letters.
        Case("AIMP", "com.aimp.player", false),
        Case("Air India", "com.example.airindia", false),
        Case("Samurai Siege", "com.example.samurai", false),
        Case("Hallmark TV", "com.example.hallmark", false),
        Case("Thai Dictionary", "com.example.thai", false),
        Case("Paint", "com.example.paint", false),
        Case("iA Writer", "net.example.writer", false),
        Case("Guida ai musei", "it.example.musei", false),
        Case("MAIL.RU", "ru.example.client", false),
        Case("Kai", "com.example.kai", false),
        // Words left out on purpose because they are too broad or are brand names.
        Case("Shopping Assistant", "com.example.shop", false),
        Case("Bot Arena", "com.example.arena", false),
        Case("Gemini", "com.gemini.android.app", false),
        Case("CoPilot GPS", "com.example.navigation", false),
        Case("Llama Life", "com.example.focus", false),
        Case("Maid Service", "com.example.cleaning", false),
        Case("", "com.example.blank", false),
    )

    @Test
    fun `matches technical AI names and nothing else`() {
        val wrong = cases.filter { AiNameHeuristic.looksLikeAiApp(it.label, it.packageName) != it.expected }
        assertEquals(emptyList(), wrong, "misjudged names")
    }

    @Test
    fun `label and package are judged independently`() {
        assertEquals(true, AiNameHeuristic.labelSuggestsAi("Pocket AI"))
        assertEquals(false, AiNameHeuristic.packageSuggestsAi("com.example.pocket"))
        assertEquals(false, AiNameHeuristic.labelSuggestsAi("Nova"))
        assertEquals(true, AiNameHeuristic.packageSuggestsAi("ai.nova.app"))
        // Segments are compared whole: "ai" inside a longer segment is not the "ai" segment.
        assertEquals(false, AiNameHeuristic.packageSuggestsAi("com.mail.air.thai"))
        assertEquals(false, AiNameHeuristic.packageSuggestsAi("com.example.email_client"))
    }
}
