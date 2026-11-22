package com.aicontrolcenter.device.platform

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.security.MessageDigest

/** The flags [PackageManager.getPackageInfo] needs for [installIdentity] to see the signers. */
internal const val INSTALL_IDENTITY_FLAGS: Long = PackageManager.GET_SIGNING_CERTIFICATES.toLong()

/**
 * What tells one installation of a package name from another: when Android first installed it
 * and who signed it. Either part is null when the platform did not report it, and null never
 * counts as a difference.
 */
internal data class InstallIdentity(val firstInstallTime: Long?, val signingCertSha256: String?) {

    /** True when both sides know a fact and the facts disagree. */
    fun differsFrom(firstInstallTime: Long?, signingCertSha256: String?): Boolean {
        val reinstalled = this.firstInstallTime != null && firstInstallTime != null &&
            this.firstInstallTime != firstInstallTime
        val resigned = this.signingCertSha256 != null && signingCertSha256 != null &&
            !this.signingCertSha256.equals(signingCertSha256, ignoreCase = true)
        return reinstalled || resigned
    }
}

/**
 * Reads the identity from a [PackageInfo] fetched with [INSTALL_IDENTITY_FLAGS].
 *
 * The certificate value is the lower-case hex SHA-256 of the certificate that signs the
 * installed APK now, the same fingerprint `apksigner verify --print-certs` prints. After a key
 * rotation it therefore changes, which errs on the side of asking the owner again. The rare
 * package with several signers gets one value for the whole set: the SHA-256 over its sorted
 * certificate fingerprints, so the order Android happens to return them in does not matter.
 */
internal fun PackageInfo.installIdentity(): InstallIdentity {
    val fingerprints = signingInfo?.apkContentsSigners.orEmpty()
        .mapNotNull { it?.toByteArray() }
        .map { sha256(it).toHexString() }
        .distinct()
        .sorted()
    val certificate = when (fingerprints.size) {
        0 -> null
        1 -> fingerprints.single()
        else -> sha256(fingerprints.joinToString(separator = "").toByteArray(Charsets.US_ASCII)).toHexString()
    }
    // Android stores 0 when it has no record of the first install.
    return InstallIdentity(firstInstallTime = firstInstallTime.takeIf { it > 0 }, signingCertSha256 = certificate)
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
