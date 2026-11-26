package com.aicontrolcenter.app.ui.settings

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.theme.Spacing

/**
 * Settings › Appearance: Calm (rule 17; WP10b). A slot of `SettingsScreen` (WP8 places it). When Android's own
 * animations are off the app is calm already, and the section says so (read again whenever the screen comes back, since
 * the owner may have changed it in Android's settings meanwhile).
 *
 * @param animatorsEnabled Android's animation switch; tests replace it.
 */
@Composable
fun MotionSection(
    viewModel: MotionSettingsViewModel,
    modifier: Modifier = Modifier,
    animatorsEnabled: () -> Boolean = ValueAnimator::areAnimatorsEnabled,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var androidAnimationsOn by remember { mutableStateOf(animatorsEnabled()) }
    LifecycleResumeEffect(animatorsEnabled) {
        androidAnimationsOn = animatorsEnabled()
        onPauseOrDispose { }
    }
    if (state.isLoading) return
    Column(modifier = modifier) {
        SectionHeader("Appearance")
        AppCard {
            SwitchRow(
                title = "Calm",
                description = CALM_SWITCH,
                checked = state.calm,
                onCheckedChange = viewModel::setCalm,
            )
            if (!androidAnimationsOn) {
                InfoBanner(text = ANDROID_ANIMATIONS_OFF, modifier = Modifier.padding(top = Spacing.sm))
            }
            state.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.sm).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Under the Calm switch. */
const val CALM_SWITCH: String =
    "Nothing on screen moves by itself: the Team view's travelling dots and the network card's pulses stand still, and " +
        "panels open at once instead of sliding. Everything is still shown, in words as well."

/** When Android's own animations are off (Settings › Accessibility › Remove animations). */
const val ANDROID_ANIMATIONS_OFF: String =
    "Android's animations are off on this phone, so the app is calm already, whatever this switch says."
