package com.aicontrolcenter.device.testing

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPackageManager
import org.robolectric.shadows.ShadowSigningInfo

/**
 * Scripts what is "installed" on the Robolectric device. Apps are described the way their
 * manifests would describe them (activities with intent filters), so production code is tested
 * against real PackageManager matching rather than canned answers.
 */
internal class FakeDevice {
    val application: Application = ApplicationProvider.getApplicationContext()
    val packageManager: PackageManager = application.packageManager
    val shadowPackageManager: ShadowPackageManager = shadowOf(packageManager)
    val ownPackage: String = application.packageName

    /**
     * @param firstInstalledAt what Android would report as the first install time; 0 = no record.
     * @param signedBy the certificates of the current signers, each given as its raw bytes in
     *   text form; empty = the platform reports no signing information.
     */
    fun install(
        packageName: String,
        label: String = packageName,
        version: String? = "1.0",
        system: Boolean = false,
        firstInstalledAt: Long = 0,
        signedBy: List<String> = emptyList(),
        manifest: AppManifest.() -> Unit = {},
    ): AppManifest {
        val info = PackageInfo().apply {
            this.packageName = packageName
            versionName = version
            firstInstallTime = firstInstalledAt
            signingInfo = signingInfoOf(signedBy)
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = label
                if (system) flags = flags or ApplicationInfo.FLAG_SYSTEM
            }
        }
        shadowPackageManager.installPackage(info)
        return AppManifest(shadowPackageManager, packageName).apply(manifest)
    }

    /** Describes more components of a package that already exists (including this app itself). */
    fun manifestOf(packageName: String): AppManifest = AppManifest(shadowPackageManager, packageName)

    fun setLabel(packageName: String, label: String) {
        shadowPackageManager.getInternalMutablePackageInfo(packageName).applicationInfo!!.nonLocalizedLabel = label
    }

    fun setVersion(packageName: String, version: String) {
        shadowPackageManager.getInternalMutablePackageInfo(packageName).versionName = version
    }

    /** Replaces the signers of an installed package, as an update signed with other keys would. */
    fun setSigners(packageName: String, signedBy: List<String>) {
        shadowPackageManager.getInternalMutablePackageInfo(packageName).signingInfo = signingInfoOf(signedBy)
    }

    fun setFirstInstallTime(packageName: String, firstInstalledAt: Long) {
        shadowPackageManager.getInternalMutablePackageInfo(packageName).firstInstallTime = firstInstalledAt
    }

    fun uninstall(packageName: String) = shadowPackageManager.deletePackage(packageName)

    private fun signingInfoOf(certificates: List<String>): SigningInfo? {
        if (certificates.isEmpty()) return null
        val signingInfo = Shadow.newInstanceOf(SigningInfo::class.java)
        Shadow.extract<ShadowSigningInfo>(signingInfo)
            .setSignatures(certificates.map { Signature(it.toByteArray()) }.toTypedArray())
        return signingInfo
    }

    /** Makes this app hold [permission], as if it were declared in the manifest and granted. */
    fun grantToSelf(permission: String) {
        val own = shadowPackageManager.getInternalMutablePackageInfo(ownPackage)
        own.requestedPermissions = (own.requestedPermissions ?: emptyArray()) + permission
        own.requestedPermissionsFlags =
            (own.requestedPermissionsFlags ?: IntArray(0)) + PackageInfo.REQUESTED_PERMISSION_GRANTED
    }

    fun disableInSettings(packageName: String) =
        packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)

    fun enableInSettings(packageName: String) =
        packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0)
}

/**
 * The intent filters of one fake app. Each call adds a filter to the named activity or service.
 * Activities are exported and unguarded unless [exported] or [permission] is changed first.
 */
internal class AppManifest(private val pm: ShadowPackageManager, val packageName: String) {

    /** Applies to the activities declared after it is set. */
    var exported: Boolean = true

    /** Permission a caller needs to start the activities declared after it is set. */
    var permission: String? = null

    fun component(simpleName: String) = ComponentName(packageName, "$packageName.$simpleName")

    fun launcher(activity: String = "MainActivity") = activity(activity) {
        addAction(Intent.ACTION_MAIN)
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    fun sharesText(activity: String = "ShareActivity", default: Boolean = true) = send(activity, "text/plain", default)

    fun sharesImages(activity: String = "ShareActivity", mimeType: String = "image/*") = send(activity, mimeType, true)

    fun processesText(activity: String = "ProcessTextActivity") = activity(activity) {
        addAction(Intent.ACTION_PROCESS_TEXT)
        addCategory(Intent.CATEGORY_DEFAULT)
        addDataType("text/plain")
    }

    fun assist(activity: String = "AssistActivity") = activity(activity) {
        addAction(Intent.ACTION_ASSIST)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    fun voiceCommand(activity: String = "VoiceActivity") = activity(activity) {
        addAction(Intent.ACTION_VOICE_COMMAND)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    fun voiceInteractionService(service: String = "AssistantService") {
        val component = component(service)
        pm.addServiceIfNotPresent(component)
        pm.addIntentFilterForService(component, IntentFilter("android.service.voice.VoiceInteractionService"))
    }

    fun opensHttpsLinks(host: String, activity: String = "LinkActivity") = activity(activity) {
        addAction(Intent.ACTION_VIEW)
        addCategory(Intent.CATEGORY_DEFAULT)
        addCategory(Intent.CATEGORY_BROWSABLE)
        addDataScheme("https")
        addDataAuthority(host, null)
    }

    private fun send(activity: String, mimeType: String, default: Boolean) = activity(activity) {
        addAction(Intent.ACTION_SEND)
        if (default) addCategory(Intent.CATEGORY_DEFAULT)
        addDataType(mimeType)
    }

    private fun activity(simpleName: String, filter: IntentFilter.() -> Unit) {
        val component = component(simpleName)
        pm.addActivityIfNotPresent(component).also {
            it.exported = exported
            it.permission = permission
        }
        pm.addIntentFilterForActivity(component, IntentFilter().apply(filter))
    }
}
