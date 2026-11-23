package com.aicontrolcenter.device.operator.apps

import android.content.Context
import androidx.core.content.FileProvider
import com.aicontrolcenter.device.R

/**
 * Serves workspace files to the one app the owner shares them with (`workspace.export`),
 * read-only and only for that hand-off. Its paths (`res/xml/workspace_share_paths.xml`, also
 * named in the manifest entry so `getUriForFile` finds them before the provider is attached)
 * cover `files/workspace` and nothing else. A subclass, so that it sits next to the app's own
 * `FileProvider` with a different authority.
 */
class WorkspaceFileProvider : FileProvider(R.xml.workspace_share_paths) {
    /** The authority. */
    companion object {
        /** The authority declared in this module's manifest (`${applicationId}.workspace`). */
        fun authority(context: Context): String = "${context.packageName}.workspace"
    }
}
