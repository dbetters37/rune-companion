package com.aicontrolcenter.device.operator

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.DeviceAccessState
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.testkit.InMemorySharedFolderRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PhoneOperatorLayerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val layer = PhoneOperatorLayer(app, InMemorySharedFolderRepository(), { ProtectedPackages(app.packageName) })

    @Test
    fun `the workspace is files-workspace in private storage, created on demand`() {
        val dir = layer.workspaceRoot.directory()

        assertEquals(File(app.filesDir, "workspace"), dir)
        assertTrue(dir.isDirectory)
        assertIs<DocResult.Ok<WorkspaceFile>>(WorkspaceFile.resolve(dir, RelativePath.of("notes/a.txt")))
    }

    @Test
    fun `a file where the workspace should be is reported, not replaced`() {
        File(app.filesDir, "workspace").apply {
            deleteRecursively()
            writeText("not a folder")
        }

        val error = runCatching { AndroidWorkspaceRoot(app).directory() }.exceptionOrNull()

        assertIs<IllegalStateException>(error)
        assertEquals("not a folder", File(app.filesDir, "workspace").readText())
    }

    @Test
    fun `settings screens for what the owner must turn on`() {
        val own = Uri.fromParts("package", app.packageName, null)

        val install = layer.settingsIntent(DeviceAccess.INSTALL_UNKNOWN_APPS)!!
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, install.action)
        assertEquals(own, install.data)
        assertTrue(install.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, layer.settingsIntent(DeviceAccess.TERMUX_RUN_COMMAND)!!.action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, layer.settingsIntent(DeviceAccess.MEDIA)!!.action)
        assertNull(layer.settingsIntent(DeviceAccess.ACCESSIBILITY_SERVICE), "the screen operator has its own enable flow")
    }

    @Test
    fun `the state of each Android access, for the owner's Tools screen`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        val shadowPm = org.robolectric.Shadows.shadowOf(app.packageManager)
        assertEquals(DeviceAccessState.NOT_ALLOWED, layer.accessState(DeviceAccess.MEDIA))
        shadowApp.grantPermissions(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(DeviceAccessState.SELECTED_ONLY, layer.accessState(DeviceAccess.MEDIA))
        shadowApp.grantPermissions(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        assertEquals(DeviceAccessState.ALLOWED, layer.accessState(DeviceAccess.MEDIA))
        assertEquals(layer.mediaPermissions, layer.runtimePermissions(DeviceAccess.MEDIA))

        shadowPm.setCanRequestPackageInstalls(false)
        assertEquals(DeviceAccessState.NOT_ALLOWED, layer.accessState(DeviceAccess.INSTALL_UNKNOWN_APPS))
        shadowPm.setCanRequestPackageInstalls(true)
        assertEquals(DeviceAccessState.ALLOWED, layer.accessState(DeviceAccess.INSTALL_UNKNOWN_APPS))
        assertTrue(layer.runtimePermissions(DeviceAccess.INSTALL_UNKNOWN_APPS).isEmpty(), "a settings page, not a dialog")

        assertEquals(DeviceAccessState.NOT_INSTALLED, layer.accessState(DeviceAccess.TERMUX_RUN_COMMAND))
        shadowPm.installPackage(android.content.pm.PackageInfo().apply { packageName = "com.termux" })
        assertEquals(DeviceAccessState.NOT_ALLOWED, layer.accessState(DeviceAccess.TERMUX_RUN_COMMAND))
        assertEquals(listOf("com.termux.permission.RUN_COMMAND"), layer.runtimePermissions(DeviceAccess.TERMUX_RUN_COMMAND))
        shadowApp.grantPermissions("com.termux.permission.RUN_COMMAND")
        assertEquals(DeviceAccessState.ALLOWED, layer.accessState(DeviceAccess.TERMUX_RUN_COMMAND))
    }
}
