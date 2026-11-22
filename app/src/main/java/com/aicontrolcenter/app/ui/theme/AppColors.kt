package com.aicontrolcenter.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.aicontrolcenter.core.registry.Locality

/**
 * The meaning something has for the owner, independent of what it is. Every status shown in
 * the app (an agent's availability, an app's install state, a severity) maps to one of these,
 * so the same colour and glyph always mean the same thing. The glyph for each tone lives next
 * to the status chip; a tone is never shown as a colour alone.
 */
enum class StatusTone {
    /** Can do its job right now. */
    READY,

    /** Doing something right now. */
    WORKING,

    /** Blocked on someone or something else. */
    WAITING,

    /** The owner should look at this. */
    ATTENTION,

    /** Cannot do its job; something is missing or broken. */
    UNAVAILABLE,

    /** Switched off on purpose. */
    DISABLED,

    /**
     * Not usable yet, for a reason that is expected and not a fault: a step of setting up that
     * has not happened. Deliberately calm, so an unfinished setup never reads as an error.
     */
    INFO,
}

/**
 * Semantic colours that Material's colour roles do not cover: status tones, where data goes,
 * hairline borders and the per-agent accent palette.
 */
@Immutable
data class AppColors(
    val isDark: Boolean,
    val ready: Color,
    val working: Color,
    val waiting: Color,
    val attention: Color,
    val unavailable: Color,
    val disabled: Color,
    val info: Color,
    val onDevice: Color,
    val localNetwork: Color,
    val cloud: Color,
    /** 1dp card and divider border. */
    val hairline: Color,
    /** Border of interactive or emphasised containers. */
    val hairlineStrong: Color,
    val agentAccents: List<Color>,
) {
    fun status(tone: StatusTone): Color = when (tone) {
        StatusTone.READY -> ready
        StatusTone.WORKING -> working
        StatusTone.WAITING -> waiting
        StatusTone.ATTENTION -> attention
        StatusTone.UNAVAILABLE -> unavailable
        StatusTone.DISABLED -> disabled
        StatusTone.INFO -> info
    }

    fun locality(locality: Locality): Color = when (locality) {
        Locality.ON_DEVICE -> onDevice
        Locality.LOCAL_NETWORK -> localNetwork
        Locality.CLOUD -> cloud
    }

    /** An agent's stable accent. Any integer is accepted so stored indices never go out of range. */
    fun agentAccent(colorIndex: Int): Color = agentAccents[Math.floorMod(colorIndex, agentAccents.size)]

    companion object {
        const val AGENT_ACCENT_COUNT = 8
    }
}

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }
