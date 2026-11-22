package com.aicontrolcenter.core.registry

import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ProviderId
import kotlinx.serialization.Serializable

/**
 * An installed Android *application* that offers AI functionality (for example a chatbot app).
 *
 * An AI application is NOT a model and NOT an agent:
 *  - an [AiApp] is a package on the phone with its own UI, owned by its vendor;
 *  - a [ModelDescriptor] is an LLM that this framework can call directly through a [ModelProvider];
 *  - an agent is a configured actor inside this framework that uses a model.
 *
 * Registering an app never implies it can be controlled. What the framework may do with it is
 * exactly the set returned by [controlChannels], and each channel is an officially supported
 * Android mechanism (or accessibility automation the user explicitly authorised for this app).
 */
@Serializable
data class AiApp(
    val id: AiAppId,
    val packageName: String,
    val label: String,
    val vendor: String? = null,
    val versionName: String? = null,
    val source: DiscoverySource,
    /** Integration surfaces the app actually exposes on this device, as detected or declared. */
    val capabilities: Set<AiAppCapability> = emptySet(),
    val status: AiAppStatus = AiAppStatus.INSTALLED,
    /** User switch inside this registry. A disabled app is listed but never used. */
    val enabled: Boolean = true,
    /** True only after the user explicitly allowed accessibility-driven operation of this app. */
    val accessibilityAuthorized: Boolean = false,
    /** A separately registered API provider for the same vendor's models, if the user linked one. */
    val linkedProviderId: ProviderId? = null,
    /** Web URL template that opens the service with a pre-filled prompt; `{prompt}` is replaced. */
    val promptDeepLink: String? = null,
    val notes: String = "",
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

/** How an app entered the registry. */
enum class DiscoverySource {
    /** Matched the built-in catalog of known AI application packages. */
    CATALOG,

    /** Found by querying Android for apps that handle an AI-relevant intent (for example the assist intent). */
    INTENT_QUERY,

    /** Not in the catalog; its name suggests an AI app. Unverified until the user confirms it. */
    HEURISTIC,

    /** Added by the user from the list of installed apps. */
    MANUAL,
}

enum class AiAppStatus {
    INSTALLED,

    /** Installed but disabled in Android settings. */
    DISABLED_BY_SYSTEM,

    /** Was registered earlier, but the package is no longer on the device. */
    NOT_INSTALLED,
}

/** An integration surface an app exposes. Each one maps to a supported Android mechanism. */
enum class AiAppCapability {
    /** Has a launcher activity; can be opened. */
    LAUNCH,

    /** Accepts shared text via ACTION_SEND. */
    SHARE_TEXT,

    /** Accepts shared images via ACTION_SEND. */
    SHARE_IMAGE,

    /** Handles ACTION_PROCESS_TEXT (may return processed text to the caller). */
    PROCESS_TEXT,

    /** Registers as a device assistant (ACTION_ASSIST / voice command). */
    ASSISTANT,

    /** Has a documented web/deep link that pre-fills a prompt. */
    PROMPT_DEEP_LINK,

    /** Hosts a local model server or terminal that other apps may call (e.g. Termux, a local runner). */
    LOCAL_MODEL_HOST,
}

/** A way this framework is permitted to interact with a registered app. */
enum class ControlChannel {
    /** Open the app. Nothing is sent to it. */
    LAUNCH,

    /** Hand text or a file to the app's own UI. The app's answer does not come back. */
    INTENT_HANDOFF,

    /** Send text and receive processed text back (ACTION_PROCESS_TEXT). */
    ROUND_TRIP_TEXT,

    /** Operate the app's UI through the accessibility service; only after explicit authorisation. */
    ACCESSIBILITY,

    /** Talk to the vendor's models through a linked API provider instead of the app. */
    LINKED_API,
}

/** The complete set of interactions permitted with this app right now. Empty when unusable. */
fun AiApp.controlChannels(): Set<ControlChannel> {
    if (!enabled) return emptySet()
    val channels = mutableSetOf<ControlChannel>()
    if (linkedProviderId != null) channels += ControlChannel.LINKED_API
    if (status != AiAppStatus.INSTALLED) return channels
    if (AiAppCapability.LAUNCH in capabilities) channels += ControlChannel.LAUNCH
    if (AiAppCapability.SHARE_TEXT in capabilities ||
        AiAppCapability.SHARE_IMAGE in capabilities ||
        AiAppCapability.PROMPT_DEEP_LINK in capabilities
    ) {
        channels += ControlChannel.INTENT_HANDOFF
    }
    if (AiAppCapability.PROCESS_TEXT in capabilities) channels += ControlChannel.ROUND_TRIP_TEXT
    if (accessibilityAuthorized) channels += ControlChannel.ACCESSIBILITY
    return channels
}

/** A package reported by a device scan, before it is merged into the registry. */
@Serializable
data class DiscoveredApp(
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val vendor: String? = null,
    val capabilities: Set<AiAppCapability> = emptySet(),
    val source: DiscoverySource,
    val enabledInSystem: Boolean = true,
    val promptDeepLink: String? = null,
)

/** Any installed, launchable app; shown when the user registers an app manually. */
@Serializable
data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val isSystemApp: Boolean = false,
)

/** Outcome of asking Android to open or hand something to another app. */
sealed interface LaunchResult {
    /** Android accepted the request and the other app's UI is opening. */
    data object Started : LaunchResult

    /** Nothing was started. [reason] is a sentence suitable for showing to the user. */
    data class NotPossible(val reason: String) : LaunchResult
}

/**
 * Starts supported hand-offs to a registered app. Every method checks [controlChannels] first
 * and refuses anything the app does not support or the user has not allowed. A hand-off opens
 * the other app's own UI; whatever that app then answers is not returned to this framework.
 */
interface AiAppLauncher {
    /** Opens the app. Requires [ControlChannel.LAUNCH]. */
    suspend fun launch(app: AiApp): LaunchResult

    /** Hands [text] to the app's UI by share intent or prompt link. Requires [ControlChannel.INTENT_HANDOFF]. */
    suspend fun handOffText(app: AiApp, text: String): LaunchResult
}
