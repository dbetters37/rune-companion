@file:Phase6Stub("WP16")

package com.aicontrolcenter.app.share

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.aicontrolcenter.app.Phase6Stub

/**
 * Receives text another app shares (owner request #43, rule 34, Tier B; WP16): `ACTION_SEND` and `ACTION_SEND_MULTIPLE`
 * of `text/` types only (the manifest's filter), at most 200,000 characters and 10 files of at most 1 MB. It passes the
 * app's gate (`AiControlCenterApp.gate`: the Recovery screen when the data cannot be opened), never starts work or calls a
 * model, and stores nothing until the owner picks a memory space (or the workspace) and taps Save on [ShareScreen]. It is
 * allowed in local-only mode. Its own activity, never [com.aicontrolcenter.app.MainActivity] (rule 23).
 *
 * Wave 3 stub (WP8): it closes at once, storing nothing.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
