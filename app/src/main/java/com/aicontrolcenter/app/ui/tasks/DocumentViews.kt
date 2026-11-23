package com.aicontrolcenter.app.ui.tasks

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Copy
import com.aicontrolcenter.app.ui.icons.Description
import com.aicontrolcenter.app.ui.tasks.markdown.MarkdownText
import com.aicontrolcenter.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The final document, rendered as readable Markdown, with Copy and Share. Sharing hands the
 * text to the app the owner picks in Android's share sheet, and only then does it leave the app.
 */
@Composable
internal fun DocumentCard(title: String, document: String?, note: String, onCopied: () -> Unit) {
    if (document == null) {
        EmptyState(icon = AppIcons.Description, title = "No final document yet", message = note)
        return
    }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilledTonalButton(onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(title, document)))
                    onCopied()
                }
            }) {
                Icon(AppIcons.Copy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Copy", modifier = Modifier.padding(start = Spacing.sm))
            }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, document)
                runCatching { context.startActivity(Intent.createChooser(send, "Share the document").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) {
                Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Share", modifier = Modifier.padding(start = Spacing.sm))
            }
        }
        AppCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.lg, vertical = Spacing.lg)) {
            MarkdownText(document)
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
