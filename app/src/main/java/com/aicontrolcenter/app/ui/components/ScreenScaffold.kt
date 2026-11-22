package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.aicontrolcenter.app.ui.icons.AppIcons
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

/**
 * A one-off sentence for the owner (the result of an action, or why it failed). [id] makes two
 * identical texts in a row distinct, so each is shown.
 */
data class UserMessage(val text: String, val id: Long)

/**
 * The frame every screen shares: a top bar with an optional back arrow and actions, a snackbar
 * for [message], and the content. Keeping it in one place gives all screens identical bar
 * heights, colours and inset handling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    message: UserMessage? = null,
    onMessageShown: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val latestMessage by rememberUpdatedState(message)
    val latestOnMessageShown by rememberUpdatedState(onMessageShown)
    // One long-lived collector instead of an effect keyed on the message: acknowledging sets the
    // message back to null, and an effect keyed on it would cancel the snackbar it just showed.
    LaunchedEffect(snackbarHostState) {
        snapshotFlow { latestMessage }.filterNotNull().collectLatest { shown ->
            // Acknowledge first, so a screen that is recreated does not show the text again.
            latestOnMessageShown()
            snackbarHostState.showSnackbar(shown.text)
        }
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Back") }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                // A raised panel of the app's own palette instead of Material's inverted default,
                // which is a white slab on the dark scheme.
                Snackbar(
                    snackbarData = data,
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.primary,
                )
            }
        },
        content = content,
    )
}
