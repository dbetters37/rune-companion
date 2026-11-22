package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ProviderPreset
import com.aicontrolcenter.core.registry.ProviderKind

/**
 * Starting points for connecting well-known services and local servers. A preset only
 * pre-fills the form: the provider created from it is ordinary data.
 *
 * Vendor-specific kinds (Anthropic, Gemini) carry no address: their adapters know the vendor's
 * endpoint, and an address is only entered to go through a proxy.
 */
object ProviderPresets {
    const val OPENAI = "openai"
    const val ANTHROPIC = "anthropic"
    const val GEMINI = "gemini"
    const val OPENROUTER = "openrouter"
    const val GROQ = "groq"
    const val MISTRAL = "mistral"
    const val DEEPSEEK = "deepseek"
    const val XAI = "xai"
    const val OLLAMA_ON_PHONE = "ollama-phone"
    const val OLLAMA_ON_NETWORK = "ollama-lan"
    const val LM_STUDIO = "lmstudio"
    const val LLAMA_CPP = "llamacpp"
    const val CUSTOM_OPENAI_COMPATIBLE = "custom-openai"
    const val SIMULATOR = "simulator"

    val all: List<ProviderPreset> = listOf(
        cloud(OPENAI, "OpenAI", "GPT models through the OpenAI API.", "https://api.openai.com/v1", "Create a key at platform.openai.com under API keys."),
        ProviderPreset(
            ANTHROPIC, "Anthropic", "Claude models through the Anthropic API.", ProviderKind.ANTHROPIC, baseUrl = null,
            needsApiKey = true, setupHint = "Create a key in the Anthropic Console under API keys.",
        ),
        ProviderPreset(
            GEMINI, "Google Gemini", "Gemini models through the Gemini API.", ProviderKind.GEMINI, baseUrl = null,
            needsApiKey = true, setupHint = "Create a key in Google AI Studio under Get API key.",
        ),
        cloud(OPENROUTER, "OpenRouter", "Many vendors' models behind one key.", "https://openrouter.ai/api/v1", "Create a key at openrouter.ai under Keys."),
        cloud(GROQ, "Groq", "Open models served at high speed.", "https://api.groq.com/openai/v1", "Create a key at console.groq.com under API Keys."),
        cloud(MISTRAL, "Mistral", "Mistral's models through La Plateforme.", "https://api.mistral.ai/v1", "Create a key at console.mistral.ai under API keys."),
        cloud(DEEPSEEK, "DeepSeek", "DeepSeek's chat and reasoning models.", "https://api.deepseek.com/v1", "Create a key at platform.deepseek.com under API keys."),
        cloud(XAI, "xAI", "Grok models through the xAI API.", "https://api.x.ai/v1", "Create a key at console.x.ai under API keys."),
        ProviderPreset(
            OLLAMA_ON_PHONE, "Ollama on this phone", "Models served by Ollama running on this phone, for example in Termux. Nothing leaves the device.",
            ProviderKind.OLLAMA, "http://127.0.0.1:11434", needsApiKey = false,
            setupHint = "Start it with \"ollama serve\" in Termux and pull a model with \"ollama pull\".",
        ),
        ProviderPreset(
            OLLAMA_ON_NETWORK, "Ollama on the local network", "Models served by Ollama on a computer in your home network.",
            ProviderKind.OLLAMA, "http://192.168.1.100:11434", needsApiKey = false,
            setupHint = "Start Ollama on the computer with OLLAMA_HOST=0.0.0.0 and replace the address with that computer's.",
        ),
        ProviderPreset(
            LM_STUDIO, "LM Studio", "Models served by LM Studio's local server on a computer in your network.",
            ProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.100:1234/v1", needsApiKey = false,
            setupHint = "Start the server in LM Studio's Developer tab with \"Serve on local network\" and replace the address with that computer's.",
        ),
        ProviderPreset(
            LLAMA_CPP, "llama.cpp server", "A llama.cpp server on this phone or in your network.",
            ProviderKind.OPENAI_COMPATIBLE, "http://127.0.0.1:8080/v1", needsApiKey = false,
            setupHint = "Start it with \"llama-server -m model.gguf --port 8080\"; change the address if it runs on another machine.",
        ),
        ProviderPreset(
            CUSTOM_OPENAI_COMPATIBLE, "Custom OpenAI-compatible server", "Any server that speaks the OpenAI Chat Completions protocol.",
            ProviderKind.OPENAI_COMPATIBLE, baseUrl = null, needsApiKey = false,
            setupHint = "Enter the server's base address, usually ending in /v1, and a key if the server asks for one.",
        ),
        ProviderPreset(
            SIMULATOR, "Simulator", "A built-in stand-in that answers offline with clearly labelled simulated replies. Not a real model.",
            ProviderKind.SIMULATOR, baseUrl = null, needsApiKey = false,
            setupHint = "Nothing to set up. Use it to try agents and tools before connecting a real model.",
        ),
    )

    /** Loopback addresses where a model server may already be running on this phone. */
    internal val localProbes: List<ProviderPreset> = listOf(
        byId(OLLAMA_ON_PHONE)!!,
        byId(LLAMA_CPP)!!,
        byId(LM_STUDIO)!!.copy(baseUrl = "http://127.0.0.1:1234/v1"),
    )

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }

    private fun cloud(id: String, title: String, description: String, baseUrl: String, setupHint: String) =
        ProviderPreset(id, title, description, ProviderKind.OPENAI_COMPATIBLE, baseUrl, needsApiKey = true, setupHint = setupHint)
}
