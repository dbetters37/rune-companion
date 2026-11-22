package com.aicontrolcenter.device.catalog

import com.aicontrolcenter.device.catalog.IdEvidence.STORE_LISTING
import com.aicontrolcenter.device.catalog.IdEvidence.UNCONFIRMED
import com.aicontrolcenter.device.catalog.IdEvidence.VENDOR_SOURCE
import com.aicontrolcenter.device.catalog.KnownAiAppKind.AI_BROWSER
import com.aicontrolcenter.device.catalog.KnownAiAppKind.API_CLIENT
import com.aicontrolcenter.device.catalog.KnownAiAppKind.CLOUD_ASSISTANT

private fun cloud(packageName: String, name: String, vendor: String, evidence: IdEvidence = STORE_LISTING) =
    KnownAiApp(packageName, name, vendor, CLOUD_ASSISTANT, evidence)

private fun browser(packageName: String, name: String, vendor: String, evidence: IdEvidence = STORE_LISTING) =
    KnownAiApp(packageName, name, vendor, AI_BROWSER, evidence)

/**
 * Apps for cloud-hosted AI services, and browsers with a built-in assistant.
 *
 * Most ids come from Play listing URLs ([IdEvidence.STORE_LISTING]). Look-alike ids exist for
 * several of these products (for example `com.deepseek.chat.nov`); only add an id here when it
 * is the vendor's own.
 */
internal val cloudAiApps: List<KnownAiApp> = listOf(
    KnownAiApp(
        packageName = "com.openai.chatgpt", name = "ChatGPT", vendor = "OpenAI",
        kind = CLOUD_ASSISTANT, idEvidence = STORE_LISTING,
        promptLinks = listOf(
            PromptDeepLink(
                template = "https://chatgpt.com/?q={prompt}",
                verified = false,
                opens = "A new chat with the prompt typed in, as observed on the web; unconfirmed for the Android app.",
            ),
        ),
    ),
    cloud("com.openai.sora", "Sora", "OpenAI"),
    cloud("com.google.android.apps.bard", "Gemini", "Google"),
    cloud("com.google.android.googlequicksearchbox", "Google", "Google"),
    cloud("com.google.android.apps.labs.language.tailwind", "NotebookLM", "Google"),
    cloud("com.google.android.apps.aistudio", "Google AI Studio", "Google"),
    KnownAiApp(
        packageName = "com.anthropic.claude", name = "Claude", vendor = "Anthropic",
        kind = CLOUD_ASSISTANT, idEvidence = VENDOR_SOURCE,
        promptLinks = listOf(
            PromptDeepLink(
                template = "https://claude.ai/code/new?q={prompt}",
                verified = true,
                opens = "A new Claude Code session with the prompt typed in; it is not sent automatically.",
            ),
            PromptDeepLink(
                template = "https://claude.ai/new?q={prompt}",
                verified = false,
                opens = "A new chat on the web; documented for the desktop app only.",
            ),
        ),
    ),
    cloud("com.anthropic.aibo", "Claude Duet", "Anthropic", VENDOR_SOURCE),
    cloud("com.microsoft.copilot", "Microsoft Copilot", "Microsoft"),
    cloud("com.microsoft.office.officehubrow", "Microsoft 365 Copilot", "Microsoft"),
    browser("com.microsoft.emmx", "Microsoft Edge", "Microsoft"),
    cloud("ai.perplexity.app.android", "Perplexity", "Perplexity AI"),
    browser("ai.perplexity.comet", "Comet", "Perplexity AI"),
    cloud("ai.x.grok", "Grok", "xAI"),
    cloud("com.deepseek.chat", "DeepSeek", "DeepSeek"),
    cloud("com.facebook.stella", "Meta AI", "Meta"),
    cloud("ai.mistral.chat", "Le Chat", "Mistral AI"),
    cloud("com.poe.android", "Poe", "Quora"),
    cloud("ai.inflection.pi", "Pi", "Inflection AI"),
    cloud("ai.character.app", "Character.AI", "Character.AI"),
    cloud("com.moonshot.kimichat", "Kimi", "Moonshot AI"),
    cloud("com.moonshot.kimiclaw", "Kimi Claw", "Moonshot AI"),
    cloud("ai.qwenlm.chat.android", "Qwen Chat", "Alibaba Cloud"),
    cloud("com.tongyi.intl", "Qwen", "Alibaba"),
    cloud("com.aliyun.tongyi", "Tongyi", "Alibaba", UNCONFIRMED),
    cloud("com.larus.nova", "Doubao", "ByteDance"),
    cloud("com.larus.wolf", "Cici", "ByteDance"),
    cloud("com.tencent.hunyuan.app.chat", "Tencent Yuanbao", "Tencent"),
    cloud("com.you.browser", "You.com", "You.com"),
    browser("com.brave.browser", "Brave", "Brave Software"),
    browser("com.duckduckgo.mobile.android", "DuckDuckGo", "DuckDuckGo", VENDOR_SOURCE),
    cloud("me.proton.android.lumo", "Lumo", "Proton"),
    cloud("tech.butterfly.app", "Manus", "Manus"),
    cloud("ai.manus.agents", "Cue by Manus", "Manus"),
    cloud("ai.mainfunc.genspark", "Genspark", "Genspark"),
    cloud("ai.replika.app", "Replika", "Luka"),
    cloud("com.laylacloud", "Layla Cloud", "Layla"),
    KnownAiApp("xyz.chatboxapp.chatbox", "Chatbox AI", "Chatbox", API_CLIENT, STORE_LISTING),
)
