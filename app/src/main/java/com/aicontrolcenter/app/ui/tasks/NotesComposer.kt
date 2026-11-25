package com.aicontrolcenter.app.ui.tasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.atMostChars
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.OwnerConversation

/** What the note field is called for screen readers (and tests). */
const val NOTE_FIELD = "Note to the team"

/** The composer's name and the button that opens it. */
const val WRITE_TO_THE_TEAM = "Write to the team"

/**
 * "Write to the team" (C2, Tier B): a note for the Director or for the agent on one unfinished task. Notes reach the
 * team from their next step (stage 2, reaching a step that runs, is not built); they never answer a request and
 * never change a setting. A refusal is shown in the app's own words.
 */
@Composable
internal fun NotesComposer(notes: NotesUi, note: TextFieldState, onRecipient: (TaskId?) -> Unit, onSend: () -> Unit, onOpen: (Boolean) -> Unit = {}) {
    if (!notes.isOpen) {
        // Folded: one button, so the conversation below stays in view.
        OutlinedButton(onClick = { onOpen(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) {
            Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(WRITE_TO_THE_TEAM, modifier = Modifier.padding(start = Spacing.sm))
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(WRITE_TO_THE_TEAM, actionLabel = "Close", onAction = { onOpen(false) })
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("To", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    notes.recipients.forEach { recipient ->
                        FilterChip(
                            selected = notes.to == recipient.taskId,
                            onClick = { onRecipient(recipient.taskId) },
                            label = { Text(recipient.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.heightIn(min = Spacing.touchTarget),
                        )
                    }
                }
                AppTextField(
                    state = note,
                    label = NOTE_FIELD,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = NOTE_FIELD },
                    placeholder = "For example: Keep the summary to one page.",
                    supportingText = "Notes reach the team from their next step. " +
                        "${notes.notesWritten} of ${OwnerConversation.MAX_NOTES_PER_OBJECTIVE} notes used.",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    inputTransformation = remember { atMostChars(OwnerConversation.MAX_NOTE_CHARS) },
                    enabled = !notes.isSending,
                )
                notes.refusal?.let { InfoBanner(title = "Not added", text = it, tone = StatusTone.ATTENTION) }
                Button(onClick = onSend, enabled = !notes.isSending, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                    if (notes.isSending) {
                        CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(AppIcons.Send, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    }
                    Text("Add note", modifier = Modifier.padding(start = Spacing.sm))
                }
                Text(
                    "A note is your own words for the team. It allows nothing and answers no request. It leaves the phone with the " +
                        "next request to an agent's model when that model is on the internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
