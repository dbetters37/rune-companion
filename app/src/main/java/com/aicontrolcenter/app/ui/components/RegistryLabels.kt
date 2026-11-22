package com.aicontrolcenter.app.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Cloud
import com.aicontrolcenter.app.ui.icons.Lan
import com.aicontrolcenter.app.ui.icons.OnDevice
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ControlChannel
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.ProviderKind

/*
 * The single place where registry values become words and tones. Screens and view models call
 * these instead of writing their own wording, so an agent that is "No model assigned" on the
 * dashboard is "No model assigned" everywhere else too.
 */

/** A status ready to render: what to say, how to tint it, and the longer explanation if any. */
data class StatusLabel(val text: String, val tone: StatusTone, val detail: String? = null)

/** The agent's availability with the exact reason reported by the framework. */
fun AgentAvailability.toStatusLabel(): StatusLabel = when (this) {
    is AgentAvailability.Ready ->
        if (usingFallback) {
            StatusLabel("Ready (fallback model)", StatusTone.READY, "The primary model is unavailable; a fallback would be used.")
        } else {
            StatusLabel("Ready", StatusTone.READY)
        }
    AgentAvailability.Disabled -> StatusLabel("Disabled", StatusTone.DISABLED, "Switched off by you.")
    is AgentAvailability.Unavailable -> StatusLabel(reason.label, StatusTone.UNAVAILABLE, detail)
}

val UnavailableReason.label: String
    get() = when (this) {
        UnavailableReason.NO_MODEL_ASSIGNED -> "No model assigned"
        UnavailableReason.MODEL_NOT_REGISTERED -> "Model not registered"
        UnavailableReason.MODEL_DISABLED -> "Model disabled"
        UnavailableReason.MODEL_UNAVAILABLE -> "Model unavailable"
        UnavailableReason.PROVIDER_NOT_REGISTERED -> "Provider not registered"
        UnavailableReason.PROVIDER_DISABLED -> "Provider disabled"
        UnavailableReason.PROVIDER_OFFLINE -> "Provider offline"
        UnavailableReason.PROVIDER_AUTH_ERROR -> "Credentials rejected"
        UnavailableReason.BLOCKED_BY_LOCAL_ONLY_MODE -> "Blocked by local-only mode"
    }

val Locality.label: String
    get() = when (this) {
        Locality.ON_DEVICE -> "On device"
        Locality.LOCAL_NETWORK -> "Local network"
        Locality.CLOUD -> "Cloud"
    }

/** What using something at this locality means for the owner's data. */
val Locality.explanation: String
    get() = when (this) {
        Locality.ON_DEVICE -> "Stays on this phone."
        Locality.LOCAL_NETWORK -> "Leaves this phone, to a machine on your own network."
        Locality.CLOUD -> "Leaves this device, to a third-party service on the internet."
    }

val Locality.icon: ImageVector
    get() = when (this) {
        Locality.ON_DEVICE -> AppIcons.OnDevice
        Locality.LOCAL_NETWORK -> AppIcons.Lan
        Locality.CLOUD -> AppIcons.Cloud
    }

fun AiAppStatus.toStatusLabel(): StatusLabel = when (this) {
    AiAppStatus.INSTALLED -> StatusLabel("Installed", StatusTone.READY)
    AiAppStatus.DISABLED_BY_SYSTEM ->
        StatusLabel("Disabled in Android", StatusTone.ATTENTION, "The app is installed but disabled in Android settings.")
    AiAppStatus.NOT_INSTALLED ->
        StatusLabel("Not installed", StatusTone.UNAVAILABLE, "The app was registered earlier but is no longer on this device.")
}

val DiscoverySource.label: String
    get() = when (this) {
        DiscoverySource.CATALOG -> "Known AI app"
        DiscoverySource.INTENT_QUERY -> "Found by Android intent"
        DiscoverySource.HEURISTIC -> "Unverified"
        DiscoverySource.MANUAL -> "Added by you"
    }

val DiscoverySource.explanation: String
    get() = when (this) {
        DiscoverySource.CATALOG -> "Matched the built-in catalog of known AI application packages."
        DiscoverySource.INTENT_QUERY -> "Android reports that it handles an AI-related action, such as the assistant action."
        DiscoverySource.HEURISTIC -> "Not in the catalog: only its name suggests an AI app. Treat it as unverified."
        DiscoverySource.MANUAL -> "You added it from the list of installed apps."
    }

val AiAppCapability.label: String
    get() = when (this) {
        AiAppCapability.LAUNCH -> "Can be opened"
        AiAppCapability.SHARE_TEXT -> "Accepts shared text"
        AiAppCapability.SHARE_IMAGE -> "Accepts shared images"
        AiAppCapability.PROCESS_TEXT -> "Processes selected text"
        AiAppCapability.ASSISTANT -> "Device assistant"
        AiAppCapability.PROMPT_DEEP_LINK -> "Prompt link"
        AiAppCapability.LOCAL_MODEL_HOST -> "Local model host"
    }

val ControlChannel.label: String
    get() = when (this) {
        ControlChannel.LAUNCH -> "Open it"
        ControlChannel.INTENT_HANDOFF -> "Hand text to it"
        ControlChannel.ROUND_TRIP_TEXT -> "Process text and return it"
        ControlChannel.ACCESSIBILITY -> "Operate its screen"
        ControlChannel.LINKED_API -> "Use the vendor's API instead"
    }

/** One plain sentence saying exactly what the channel permits and what it does not. */
val ControlChannel.explanation: String
    get() = when (this) {
        ControlChannel.LAUNCH -> "The app can be opened. Nothing is sent to it."
        ControlChannel.INTENT_HANDOFF ->
            "Text can be handed to the app's own screen. Whatever the app answers stays in that app."
        ControlChannel.ROUND_TRIP_TEXT ->
            "Text can be sent through Android's process-text action and the processed text comes back."
        ControlChannel.ACCESSIBILITY ->
            "You authorised operating this app's screen through the accessibility service. " +
                "Nothing can use this until the operator service exists in a later version."
        ControlChannel.LINKED_API ->
            "The vendor's models can be called through a linked API provider. That does not go through the app."
    }

val ProviderKind.label: String
    get() = when (this) {
        ProviderKind.OPENAI_COMPATIBLE -> "OpenAI-compatible"
        ProviderKind.ANTHROPIC -> "Anthropic"
        ProviderKind.GEMINI -> "Gemini"
        ProviderKind.OLLAMA -> "Ollama"
        ProviderKind.ON_DEVICE -> "On-device runtime"
        ProviderKind.SIMULATOR -> "Simulator"
    }

fun ConnectionStatus.toStatusLabel(): StatusLabel = when (this) {
    ConnectionStatus.UNKNOWN -> StatusLabel("Not checked", StatusTone.DISABLED)
    ConnectionStatus.ONLINE -> StatusLabel("Online", StatusTone.READY)
    ConnectionStatus.OFFLINE -> StatusLabel("Offline", StatusTone.UNAVAILABLE)
    ConnectionStatus.AUTH_ERROR -> StatusLabel("Credentials rejected", StatusTone.UNAVAILABLE)
    ConnectionStatus.ERROR -> StatusLabel("Error", StatusTone.ATTENTION)
}

fun ModelStatus.toStatusLabel(): StatusLabel = when (this) {
    ModelStatus.UNKNOWN -> StatusLabel("Not checked", StatusTone.DISABLED)
    ModelStatus.AVAILABLE -> StatusLabel("Available", StatusTone.READY)
    ModelStatus.UNAVAILABLE -> StatusLabel("Unavailable", StatusTone.UNAVAILABLE)
}
