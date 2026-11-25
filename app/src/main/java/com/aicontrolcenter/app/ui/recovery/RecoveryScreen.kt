package com.aicontrolcenter.app.ui.recovery

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.OwnerRecovery

/**
 * What the owner sees when the app's data cannot be opened (Phase 6, R5, rules 9, 27 and 30; WP10 owns this file in
 * wave 3). It is shown by the app's gate instead of the shell, outside navigation; no intent opens it (rule 23), and
 * it never reads the app's graph.
 *
 * Wave 1 stub: the gate never shows it (it is always open), and it only says what went wrong. WP10 adds Start over
 * (a typed word in a secure field), "Close and try later" and `FLAG_SECURE`.
 *
 * Wave 3 (WP8): the final signature. WP10b fills it with [RecoveryViewModel] (`viewModel { RecoveryViewModel(state,
 * recovery) }`), `RecoveryActions.startOverAndRestart`, and [onTryAgain] for "Try again".
 *
 * @param state why the data could not be opened.
 * @param recovery the owner's way out (Start over); null when this build has none.
 * @param onTryAgain looks again whether the data opens (the gate's `recheck`; the gate itself never reaches this screen).
 */
@Phase6Stub("WP10b")
@Composable
fun RecoveryHost(
    state: DatabaseState,
    @Suppress("UNUSED_PARAMETER") recovery: OwnerRecovery?,
    @Suppress("UNUSED_PARAMETER") onTryAgain: () -> Unit = {},
) {
    AppTheme(isSystemInDarkTheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(state.ownerText, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
