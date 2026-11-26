package com.aicontrolcenter.app.ui.recovery

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.health.RecoveryActions
import com.aicontrolcenter.app.ui.backup.NoteLines
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SecretTextField
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.OwnerRecovery

/**
 * What the owner sees when the app's data cannot be opened (Phase 6, R5, rules 9, 27 and 30; WP10b). It is shown by the
 * app's gate instead of the shell, outside navigation; no intent opens it (rule 23), and it holds nothing of the app's
 * agents: only the owner's recovery port, from the application.
 *
 * Plain words for each [DatabaseState], what to try first, Start over (a typed word in a secure field; what is set
 * aside and what is reset, in words; nothing is deleted), "Try again" ([onTryAgain], the gate's `recheck`) and "Close and
 * try later". The window is secure while it shows (`FLAG_SECURE`).
 *
 * @param state why the data could not be opened.
 * @param recovery the owner's way out (Start over); null when this build has none.
 * @param onTryAgain looks again whether the data opens (the gate's `recheck`; the gate itself never reaches this screen).
 */
@Composable
fun RecoveryHost(
    state: DatabaseState,
    recovery: OwnerRecovery?,
    onTryAgain: () -> Unit = {},
) {
    AppTheme(isSystemInDarkTheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // One per reason: a "Try again" that finds another reason starts afresh.
            val viewModel = viewModel(key = "recovery:${state.name}") { RecoveryViewModel(state, recovery) }
            RecoveryScreen(viewModel, onTryAgain)
        }
    }
}

/** The screen itself. [restart] starts the app again after Start over (rule 30); [close] closes it; tests replace both. */
@Composable
fun RecoveryScreen(
    viewModel: RecoveryViewModel,
    onTryAgain: () -> Unit,
    restart: (Activity) -> Unit = { RecoveryActions.restartApp(it) },
    close: (Activity) -> Unit = { it.finish() },
) {
    SecureWindow()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.restartNow) {
        if (state.restartNow) context.findActivity()?.let(restart)
    }
    ScreenScaffold(title = RecoveryViewModel.TITLE) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .readableWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            InfoBanner(title = state.reason, text = state.advice, tone = StatusTone.UNAVAILABLE)
            if (state.stillNotOpening) {
                Text(
                    STILL_NOT_OPENING,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(
                onClick = {
                    viewModel.triedAgain()
                    onTryAgain()
                },
                enabled = !state.startingOver,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Try again") }
            OutlinedButton(
                onClick = { context.findActivity()?.let(close) },
                enabled = !state.startingOver,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Close and try later") }
            Text(
                "Closing changes nothing: your data stays as it is. When you open the app again, it looks again by itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.canStartOver) StartOverCard(viewModel, state)
        }
    }
}

@Composable
private fun StartOverCard(viewModel: RecoveryViewModel, state: RecoveryUiState) {
    Column {
        SectionHeader("Start over")
        AppCard {
            Text(
                "Only if trying again does not help.",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            NoteLines(RecoveryViewModel.WHAT_START_OVER_DOES, Modifier.padding(top = Spacing.sm))
            SecretTextField(
                state = viewModel.confirmWord,
                label = "Type ${OwnerRecovery.CONFIRM_WORD} to confirm",
                supportingText = confirmHint(viewModel.confirmWord.text.length),
                enabled = !state.startingOver,
                modifier = Modifier.padding(top = Spacing.md),
            )
            state.refusal?.let {
                InfoBanner(
                    it,
                    tone = StatusTone.ATTENTION,
                    modifier = Modifier.padding(top = Spacing.sm).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            OutlinedButton(
                onClick = viewModel::startOver,
                enabled = viewModel.wordTyped() && !state.startingOver && !state.restartNow,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).heightIn(min = 48.dp),
            ) { Text(if (state.startingOver || state.restartNow) "Starting over…" else "Start over") }
        }
    }
}

/** Under the hidden field: what is typed so far, in a count only, and that case does not matter. */
private fun confirmHint(length: Int): String {
    val typed = when (length) {
        0 -> "Nothing typed yet."
        1 -> "1 character typed."
        else -> "$length characters typed."
    }
    return "$typed Capital letters do not matter, and the keyboard does not learn it."
}

const val STILL_NOT_OPENING: String = "Looked again: the data still does not open."
