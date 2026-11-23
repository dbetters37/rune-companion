package com.aicontrolcenter.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * Asks before something consequential happens. [destructive] colours the confirming button as
 * an error so that deleting never looks like the safe default; [widens] colours it as a
 * warning, so giving agents more never looks like going back.
 *
 * [guarded] is for a decision about what agents may do: the dialog refuses touches while another
 * app's window covers it, and on Android 14 and later its buttons are marked sensitive, so only
 * accessibility tools that help the owner use the phone can read or press them.
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
    /** Confirming gives agents more than they had: the confirming button is drawn as a warning. */
    widens: Boolean = false,
    guarded: Boolean = false,
) {
    val iconTint = when {
        destructive -> MaterialTheme.colorScheme.error
        cautionary -> appColors.attention
        else -> MaterialTheme.colorScheme.secondary
    }
    val buttonModifier = if (guarded) Modifier.semantics { isSensitiveData = true } else Modifier
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null, tint = iconTint) } },
        title = { Text(title) },
        text = {
            if (guarded) ObscuredTouchGuard()
            Text(message, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = when {
                    destructive -> ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    widens -> ButtonDefaults.textButtonColors(contentColor = appColors.attention)
                    else -> ButtonDefaults.textButtonColors()
                },
                modifier = buttonModifier,
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = buttonModifier) { Text(dismissLabel) } },
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
