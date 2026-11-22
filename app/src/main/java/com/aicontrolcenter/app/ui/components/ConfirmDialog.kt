package com.aicontrolcenter.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * Asks before something consequential happens. [destructive] colours the confirming button as
 * an error so that deleting never looks like the safe default.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    icon: ImageVector? = null,
    /** Marks a decision with consequences that are easy to underestimate; tints the icon as a warning. */
    cautionary: Boolean = false,
) {
    val iconTint = when {
        destructive -> MaterialTheme.colorScheme.error
        cautionary -> appColors.attention
        else -> MaterialTheme.colorScheme.secondary
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null, tint = iconTint) } },
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** Explains something the owner cannot do right now, with a single acknowledging button. */
@Composable
fun NoticeDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    dismissLabel: String = "OK",
    icon: ImageVector? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
