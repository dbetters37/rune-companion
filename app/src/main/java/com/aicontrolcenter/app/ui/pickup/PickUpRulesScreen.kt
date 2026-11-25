@file:Phase6Stub("WP10c")

package com.aicontrolcenter.app.ui.pickup

import androidx.compose.runtime.Composable
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.ui.shell.StubScreen
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.SpaceId

/** Where the Folder pick-up screens lead (supplied by the navigation shell). */
class PickUpNavigation(
    val onBack: () -> Unit,
    val onNewRule: (FolderId?) -> Unit = {},
    val onOpenRule: (PickUpRuleId) -> Unit = {},
    /** Tools › Folders, to share a folder or make one read-only. */
    val onOpenFolders: () -> Unit = {},
    val onOpenSpace: (SpaceId) -> Unit = {},
    /** Settings, where the master switch is. */
    val onOpenSettings: () -> Unit = {},
)

/**
 * The owner's folder pick-up rules (Q1, Appendix A, Tier B; WP10c): each rule's folder and space, last run and its result
 * (sentences from `PickUpRunner`/`PickUpSafety`), the files it picked up; the master switch's state; "files already in the
 * folder are left out, only text files arrive, at most 5 MB a day; nothing is sent to a model by picking up". Hidden when
 * the build has no pick-up.
 */
@Composable
fun PickUpRulesRoute(navigation: PickUpNavigation) {
    StubScreen("Folder pick-up", navigation.onBack)
}
