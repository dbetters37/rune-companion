package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ProviderPreset
import com.aicontrolcenter.core.registry.ProviderKind

/**
 * Starting points for connecting well-known services and local servers. A preset only
 * pre-fills the form: the provider created from it is ordinary data.
 *
 * Vendor-specific kinds (Anthropic, Gemini) carry no address: their adapters know the vendor's
 * endpoint, and an address is only entered to go through a proxy.
 *
 * Each description ends with one plain line on what using it costs (Phase 6, WP4 item 8). The facts
 * about free tiers and limits come from the providers' pages as of November 2026 and could not be
 * checked from the app, so every such line tells the owner to check the provider's page: terms change.
 * The app never fetches prices; the owner types them in (rule 29).
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

    // What each one costs, one plain line each (WP4 item 8). Hedged on purpose: free tiers and limits change.
    private const val COST_OPENAI = "Costs depend on the model and on how much text goes back and forth. Check OpenAI's price page; terms change."
    private const val COST_ANTHROPIC = "Costs depend on the model and on how much text goes back and forth. Check Anthropic's price page; terms change."
    private const val COST_GEMINI = "Has a free tier with limits; Google may use what you send on the free tier to improve its products. " +
        "Check Google's page; terms change."
    private const val COST_OPENROUTER = "Models whose name ends in \":free\" cost nothing but allow only a limited number of requests a day, " +
        "and some of the services behind them keep what you send. Check OpenRouter's page; terms change."
    private const val COST_GROQ = "Has a free plan with a small limit of words per minute; long requests may have to wait. Check Groq's page; terms change."
    private const val COST_MISTRAL = "Costs depend on the model and on how much text goes back and forth. Check Mistral's price page; terms change."
    private const val COST_DEEPSEEK = "Costs depend on the model and on how much text goes back and forth. Check DeepSeek's price page; terms change."
    private const val COST_XAI = "No free tier: every answer is paid for by how much text goes back and forth. Check xAI's price page; terms change."
    private const val COST_ON_PHONE = "Free and private: it runs on this phone."
    private const val COST_OWN_COMPUTER = "Free and private: it runs on your own computer."
    private const val COST_PHONE_OR_COMPUTER = "Free and private: it runs on this phone or your own computer."
    private const val COST_CUSTOM = "What it costs depends on who runs the server. Check its page."
    private const val COST_SIMULATOR = "Free: nothing is sent anywhere."

    val all: List<ProviderPreset> = listOf(
        cloud(OPENAI, "OpenAI", "GPT models through the OpenAI API.\n" + COST_OPENAI, "https://api.openai.com/v1", "Create a key at platform.openai.com under API keys."),
        ProviderPreset(
            ANTHROPIC, "Anthropic", "Claude models through the Anthropic API.\n" + COST_ANTHROPIC, ProviderKind.ANTHROPIC, baseUrl = null,
            needsApiKey = true, setupHint = "Create a key in the Anthropic Console under API keys.",
        ),
        ProviderPreset(
            GEMINI, "Google Gemini", "Gemini models through the Gemini API.\n" + COST_GEMINI, ProviderKind.GEMINI, baseUrl = null,
            needsApiKey = true, setupHint = "Create a key in Google AI Studio under Get API key.",
        ),
        cloud(OPENROUTER, "OpenRouter", "Many vendors' models behind one key.\n" + COST_OPENROUTER, "https://openrouter.ai/api/v1", "Create a key at openrouter.ai under Keys."),
        cloud(GROQ, "Groq", "Open models served at high speed.\n" + COST_GROQ, "https://api.groq.com/openai/v1", "Create a key at console.groq.com under API Keys."),
        cloud(MISTRAL, "Mistral", "Mistral's models through La Plateforme.\n" + COST_MISTRAL, "https://api.mistral.ai/v1", "Create a key at console.mistral.ai under API keys."),
        cloud(DEEPSEEK, "DeepSeek", "DeepSeek's chat and reasoning models.\n" + COST_DEEPSEEK, "https://api.deepseek.com/v1", "Create a key at platform.deepseek.com under API keys."),
        cloud(XAI, "xAI", "Grok models through the xAI API.\n" + COST_XAI, "https://api.x.ai/v1", "Create a key at console.x.ai under API keys."),
        ProviderPreset(
            OLLAMA_ON_PHONE, "Ollama on this phone", "Models served by Ollama running on this phone, for example in Termux. Nothing leaves the device.\n" + COST_ON_PHONE,
            ProviderKind.OLLAMA, "http://127.0.0.1:11434", needsApiKey = false,
            setupHint = "Start it with \"ollama serve\" in Termux and pull a model with \"ollama pull\".",
        ),
        ProviderPreset(
            OLLAMA_ON_NETWORK, "Ollama on the local network", "Models served by Ollama on a computer in your home network.\n" + COST_OWN_COMPUTER,
            ProviderKind.OLLAMA, "http://192.168.1.100:11434", needsApiKey = false,
            setupHint = "Start Ollama on the computer with OLLAMA_HOST=0.0.0.0 and replace the address with that computer's.",
        ),
        ProviderPreset(
            LM_STUDIO, "LM Studio", "Models served by LM Studio's local server on a computer in your network.\n" + COST_OWN_COMPUTER,
            ProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.100:1234/v1", needsApiKey = false,
            setupHint = "Start the server in LM Studio's Developer tab with \"Serve on local network\" and replace the address with that computer's.",
        ),
        ProviderPreset(
            LLAMA_CPP, "llama.cpp server", "A llama.cpp server on this phone or in your network.\n" + COST_PHONE_OR_COMPUTER,
            ProviderKind.OPENAI_COMPATIBLE, "http://127.0.0.1:8080/v1", needsApiKey = false,
            setupHint = "Start it with \"llama-server -m model.gguf --port 8080\"; change the address if it runs on another machine.",
        ),
        ProviderPreset(
            CUSTOM_OPENAI_COMPATIBLE, "Custom OpenAI-compatible server", "Any server that speaks the OpenAI Chat Completions protocol.\n" + COST_CUSTOM,
            ProviderKind.OPENAI_COMPATIBLE, baseUrl = null, needsApiKey = false,
            setupHint = "Enter the server's base address, usually ending in /v1, and a key if the server asks for one.",
        ),
        ProviderPreset(
            SIMULATOR, "Simulator", "A built-in stand-in that answers offline with clearly labelled simulated replies. Not a real model.\n" + COST_SIMULATOR,
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
