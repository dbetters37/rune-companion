@file:Phase6Stub("WP10c")

package com.aicontrolcenter.app.ui.pickup

import androidx.compose.runtime.Composable
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.ui.shell.StubScreen
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.PickUpRuleId

/**
 * A new pick-up rule ([ruleId] null; [folderId] when the owner came from a folder) or one to change (WP10c): the folder
 * and the space; the refusals (`FOLDER_NOT_READ_ONLY` with "Make it read-only", `FOLDER_OVERLAPS_WRITABLE`,
 * `FOLDER_UNREADABLE`, `FOLDER_GONE`, the Downloads-entry and other-app sentences) shown as they are.
 */
@Composable
fun PickUpRuleEditorRoute(
    ruleId: PickUpRuleId?,
    @Suppress("UNUSED_PARAMETER") folderId: FolderId?,
    onClose: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onSaved: (PickUpRuleId) -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenFolders: () -> Unit,
) {
    StubScreen(if (ruleId == null) "New pick-up rule" else "Pick-up rule", onClose)
}
