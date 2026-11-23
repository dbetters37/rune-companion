package com.aicontrolcenter.device.operator

import android.content.Context
import com.aicontrolcenter.core.operator.WorkspaceRoot
import java.io.File

/**
 * The agents' workspace: `files/workspace` in this app's private storage. No other app can read
 * it, it needs no permission, and Android removes it with the app. Only `Workspace` and
 * `WorkspaceFile.resolve` use it; the share provider ([WorkspaceFileProvider]) serves files from
 * this directory and nowhere else.
 */
class AndroidWorkspaceRoot(context: Context) : WorkspaceRoot {
    private val filesDir: File = (context.applicationContext ?: context).filesDir

    override fun directory(): File {
        val dir = File(filesDir, DIRECTORY)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("The workspace folder could not be created.")
        }
        return dir
    }

    /** Constants. */
    companion object {
        /** The folder name under `filesDir`; `res/xml/workspace_share_paths.xml` names the same one. */
        const val DIRECTORY: String = "workspace"
    }
}
