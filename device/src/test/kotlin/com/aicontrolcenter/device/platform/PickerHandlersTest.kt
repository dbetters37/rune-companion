package com.aicontrolcenter.device.platform

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.device.screen.ProtectedPackagesResolver
import com.aicontrolcenter.device.testing.FakeDevice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6, K17 (S26): Android's own file and photo pickers are protected like its settings and
 * installers, matched through the filters the pickers really declare (DocumentsUI and the photo
 * picker of the media module), so a picker in front can never be operated by an agent.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PickerHandlersTest {
    private val device = FakeDevice()

    private fun handles(packageName: String, activity: String, filter: IntentFilter) {
        if (runCatching { device.packageManager.getPackageInfo(packageName, 0) }.isFailure) device.install(packageName, system = true)
        val component = ComponentName(packageName, "$packageName.$activity")
        device.shadowPackageManager.addActivityIfNotPresent(component)
        device.shadowPackageManager.addIntentFilterForActivity(component, filter)
    }

    @Test
    fun `the document pickers and the photo picker are protected`() {
        handles("com.google.android.documentsui", "OpenDocument", IntentFilter("android.intent.action.OPEN_DOCUMENT").apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        })
        handles("com.google.android.documentsui", "CreateDocument", IntentFilter("android.intent.action.CREATE_DOCUMENT").apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        })
        handles("com.oem.files", "PickFolder", IntentFilter("android.intent.action.OPEN_DOCUMENT_TREE").apply { addCategory(Intent.CATEGORY_DEFAULT) })
        handles("com.google.android.providers.media.module", "PhotoPicker", IntentFilter("android.provider.action.PICK_IMAGES").apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataType("image/*")
            addDataType("video/*")
        })
        // An app that only views documents is not a picker.
        handles("com.example.viewer", "View", IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataType("*/*")
        })

        val protected = ProtectedPackagesResolver.resolve(device.application)

        assertEquals(setOf("com.google.android.documentsui", "com.oem.files", "com.google.android.providers.media.module"), protected.resolved)
        assertTrue(protected.isProtected("com.google.android.providers.media.module"))
        assertEquals(
            listOf(QueriedIntent.OPEN_DOCUMENT, QueriedIntent.OPEN_DOCUMENT_TREE, QueriedIntent.CREATE_DOCUMENT, QueriedIntent.PICK_IMAGES),
            QueriedIntent.protectedHandlers.takeLast(4),
        )
    }
}
