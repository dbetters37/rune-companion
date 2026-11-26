package com.aicontrolcenter.app.ui.shell

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.activity.LocalUsageWork
import com.aicontrolcenter.app.ui.tasks.LocalScreenWork
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.app.ui.theme.calmMotion
import com.aicontrolcenter.core.settings.SettingsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

/**
 * Where the app's screens build what they show (P1, P2, P7, rule 16): the background pool `MainActivity` hands to
 * [AiControlCenterRoot]. The Tasks, Waiting for you, question, Activity, Usage and Dashboard view models run their
 * building `flowOn` it, so the main thread only draws.
 */
val AppScreenWork: CoroutineContext = Dispatchers.Default

/**
 * What every screen reads besides the graph (wave 3, WP8): where view models build their state, and Calm.
 *
 * @param settings read only, for the owner's Calm setting (`calmMotion`).
 * @param screenWork the context `LocalScreenWork` (WP11a's screens) and `LocalUsageWork` (WP10a's) carry. Null keeps
 *   what an outer caller provided: the app's tests compose the app without a thread in between, so their stores answer
 *   at once and Compose's idle wait sees every state.
 * @param animatorsEnabled Android's own animation switch (`ValueAnimator.areAnimatorsEnabled()`), read again whenever
 *   the app comes back to the screen, since developer options and battery saver change it meanwhile.
 */
@Composable
internal fun AppEnvironment(
    settings: SettingsView,
    screenWork: CoroutineContext?,
    animatorsEnabled: () -> Boolean = ValueAnimator::areAnimatorsEnabled,
    content: @Composable () -> Unit,
) {
    val calmFlow = remember(settings) { settings.settings.map { it.calmMotion }.distinctUntilChanged() }
    val calmSetting by calmFlow.collectAsStateWithLifecycle(initialValue = false)
    var animators by remember { mutableStateOf(animatorsEnabled()) }
    LifecycleResumeEffect(animatorsEnabled) {
        animators = animatorsEnabled()
        onPauseOrDispose { }
    }
    val calm = calmMotion(calmSetting, animators)
    if (screenWork == null) {
        CompositionLocalProvider(LocalCalmMotion provides calm, content = content)
    } else {
        CompositionLocalProvider(
            LocalCalmMotion provides calm,
            LocalScreenWork provides screenWork,
            LocalUsageWork provides screenWork,
            content = content,
        )
    }
}
