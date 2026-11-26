package com.aicontrolcenter.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.OpenInNew
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * Settings › Working in the background (A3; WP10b). A slot of `SettingsScreen` (WP8 places it). Android is asked again
 * every time the section comes back on screen, so returning from one of Android's screens shows what the owner changed
 * there. Each item says its state in words and with an icon (never colour alone), and is read as one phrase.
 */
@Composable
fun BackgroundSection(
    viewModel: BackgroundSectionViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    if (state.isLoading) return
    val open: (AndroidScreen) -> Unit = { screen -> openAndroidScreen(context, viewModel, screen) }
    Column(modifier = modifier) {
        SectionHeader("Working in the background")
        AppCard {
            InfoBanner(
                text = state.summary,
                tone = if (state.allClear) StatusTone.READY else StatusTone.ATTENTION,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            state.items.forEach { item ->
                RowDivider(Modifier.padding(vertical = Spacing.sm))
                BackgroundItemRow(item, onFix = open)
            }
            state.message?.let { message ->
                InfoBanner(
                    text = message,
                    tone = StatusTone.ATTENTION,
                    modifier = Modifier.padding(top = Spacing.md).semantics { liveRegion = LiveRegionMode.Polite },
                )
                state.offer?.let { other ->
                    OutlinedButton(
                        onClick = { open(other) },
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).heightIn(min = 48.dp),
                    ) { Text(APP_PAGE_BUTTON) }
                }
            }
            TextButton(
                onClick = viewModel::refresh,
                enabled = !state.checking,
                modifier = Modifier.padding(top = Spacing.sm).heightIn(min = 48.dp),
            ) {
                Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(if (state.checking) "Checking…" else "Check again", modifier = Modifier.padding(start = 8.dp))
            }
            RowDivider(Modifier.padding(vertical = Spacing.sm))
            SwitchRow(
                title = "Keep the phone awake while agents work",
                description = KEEP_AWAKE,
                checked = state.keepAwake,
                onCheckedChange = viewModel::setKeepAwake,
            )
            RowDivider()
            SwitchRow(
                title = "Tell me when work ends",
                description = NOTIFY_WHEN_WORK_ENDS,
                checked = state.notifyWhenWorkEnds,
                onCheckedChange = viewModel::setNotifyWhenWorkEnds,
            )
        }
    }
}

/** One item: what Android decides, in words and with an icon, read as one phrase; then its button, if one helps. */
@Composable
private fun BackgroundItemRow(item: BackgroundItemUi, onFix: (AndroidScreen) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = item.spoken },
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(item.tone.icon, contentDescription = null, tint = appColors.status(item.tone), modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(item.status, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val fix = item.fix
        if (fix != null && item.fixLabel != null) {
            OutlinedButton(
                onClick = { onFix(fix) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(item.fixLabel, modifier = Modifier.weight(1f, fill = false))
                Icon(AppIcons.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(ButtonDefaults.IconSize))
            }
        }
    }
}

/** Opens Android's [screen] for the owner; a phone without it gets the plain sentence and the other button. */
private fun openAndroidScreen(context: Context, viewModel: BackgroundSectionViewModel, screen: AndroidScreen) {
    val intent = viewModel.intentFor(screen)
    if (intent == null) {
        viewModel.screenMissing(screen)
        return
    }
    try {
        context.startActivity(intent)
        viewModel.screenOpened()
    } catch (_: ActivityNotFoundException) {
        viewModel.screenMissing(screen)
    } catch (_: SecurityException) {
        viewModel.screenMissing(screen)
    }
}

/** The fallback button after a missing screen. */
const val APP_PAGE_BUTTON: String = "Open the app's page in Android"

/** Under "Keep the phone awake while agents work". */
const val KEEP_AWAKE: String =
    "While an agent is working, or is about to try again within two minutes, the phone stays awake with the screen off. " +
        "It goes back to sleep as soon as agents only wait. Off: the phone may sleep, and work waits until you use it again."

/** Under "Tell me when work ends". */
const val NOTIFY_WHEN_WORK_ENDS: String =
    "A notification when an objective finishes, stops or pauses, while the app is not on screen."
