package com.aicontrolcenter.device.report

import android.content.pm.PackageManager
import android.os.Build
import com.aicontrolcenter.core.device.DeviceFeature
import com.aicontrolcenter.device.catalog.CompanionPackages
import com.aicontrolcenter.device.platform.packageInfoOrNull

/** Stable ids of the [DeviceFeature] entries in a device report, for callers that key on them. */
object DeviceFeatureIds {
    const val ABI_64_BIT = "abi_64_bit"
    const val OPENGL_ES_3_1 = "opengl_es_3_1"
    const val VULKAN = "vulkan"
    const val AICORE = "aicore"
    const val TERMUX = "termux"
    const val TERMUX_API = "termux_api"

    /** Available when Android does NOT class the phone as a low-RAM device. */
    const val STANDARD_MEMORY_CLASS = "standard_memory_class"

    /**
     * Available when Advanced Protection mode is OFF, so that "available" stays the good case
     * for this product. Only listed when Android can say (Android 16 and newer).
     */
    const val ADVANCED_PROTECTION = "advanced_protection"
}

/**
 * Builds the feature list of a device report. Each entry states only what was observed: a
 * declared hardware feature or an installed package is reported as present, never as working.
 * What happens inside another app (a model downloaded, a server set up) is not looked at.
 */
internal class DeviceFeatureProbe(
    private val pm: PackageManager,
    private val advancedProtection: AdvancedProtectionReader,
) {

    /**
     * @param glEsVersion the packed `reqGlEsVersion` (major in the high 16 bits), 0 if unknown.
     * @param glEsVersionName the same version as text, or null if unknown.
     */
    fun probe(glEsVersion: Int, glEsVersionName: String?, isLowRamDevice: Boolean): List<DeviceFeature> = listOfNotNull(
        abi64Bit(),
        openGlEs31(glEsVersion, glEsVersionName),
        vulkan(),
        aiCore(),
        termux(),
        termuxApi(),
        standardMemoryClass(isLowRamDevice),
        advancedProtection(),
    )

    private fun abi64Bit(): DeviceFeature {
        val abis64 = Build.SUPPORTED_64_BIT_ABIS.orEmpty()
        val available = abis64.isNotEmpty()
        return DeviceFeature(
            id = DeviceFeatureIds.ABI_64_BIT,
            title = "64-bit processor",
            available = available,
            detail = if (available) {
                "Supports ${abis64.joinToString()}, which on-device model runtimes are generally built for."
            } else {
                "Only 32-bit code is supported; on-device model runtimes are generally built for 64-bit only."
            },
        )
    }

    private fun openGlEs31(version: Int, versionName: String?): DeviceFeature {
        val available = version >= GL_ES_3_1
        return DeviceFeature(
            id = DeviceFeatureIds.OPENGL_ES_3_1,
            title = "OpenGL ES 3.1 or newer",
            available = available,
            detail = when {
                available -> "Reports OpenGL ES $versionName, which has the compute shaders GPU inference backends use."
                versionName != null -> "Reports OpenGL ES $versionName; GPU inference backends need 3.1 or newer."
                else -> "The device did not report an OpenGL ES version, so GPU inference cannot be assumed."
            },
        )
    }

    private fun vulkan(): DeviceFeature {
        val declared = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        val version = pm.systemAvailableFeatures.orEmpty()
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?.version
            ?.takeIf { it > 0 }
        val versionText = version?.let { " ${it ushr 22}.${(it ushr 12) and 0x3ff}" }.orEmpty()
        return DeviceFeature(
            id = DeviceFeatureIds.VULKAN,
            title = "Vulkan",
            available = declared,
            detail = if (declared) {
                "Declares Vulkan$versionText hardware support, which some local model runtimes can use for GPU acceleration."
            } else {
                "Declares no Vulkan hardware support, so runtimes that accelerate through Vulkan will use the CPU."
            },
        )
    }

    private fun aiCore(): DeviceFeature {
        val present = isInstalled(CompanionPackages.AICORE)
        return DeviceFeature(
            id = DeviceFeatureIds.AICORE,
            title = "Android AICore (Gemini Nano)",
            available = present,
            detail = if (present) {
                "The AICore system package is present; that alone does not show that Gemini Nano is downloaded or usable by this app."
            } else {
                "The AICore system package was not found under its expected name, so on-device Gemini Nano is not expected here."
            },
        )
    }

    private fun termux(): DeviceFeature {
        val present = isInstalled(CompanionPackages.TERMUX)
        return DeviceFeature(
            id = DeviceFeatureIds.TERMUX,
            title = "Termux",
            available = present,
            detail = if (present) {
                "Installed; whether a local model server such as Ollama or llama.cpp is set up inside it cannot be seen from here."
            } else {
                "Not installed; Termux is one way to run a local model server such as Ollama or llama.cpp on a phone."
            },
        )
    }

    private fun termuxApi(): DeviceFeature {
        val present = isInstalled(CompanionPackages.TERMUX_API)
        return DeviceFeature(
            id = DeviceFeatureIds.TERMUX_API,
            title = "Termux:API",
            available = present,
            detail = if (present) {
                "Installed; it is the add-on through which scripts running in Termux call Android features."
            } else {
                "Not installed; it is only needed if scripts running in Termux should call Android features."
            },
        )
    }

    private fun standardMemoryClass(isLowRamDevice: Boolean) = DeviceFeature(
        id = DeviceFeatureIds.STANDARD_MEMORY_CLASS,
        title = "Standard memory class",
        available = !isLowRamDevice,
        detail = if (isLowRamDevice) {
            "Android classes this phone as a low-RAM device and restricts background work, so local models are unlikely to fit."
        } else {
            "Android does not class this phone as a low-RAM device."
        },
    )

    /** Absent, rather than guessed, when Android cannot say whether the mode is on. */
    private fun advancedProtection(): DeviceFeature? {
        val on = advancedProtection.isEnabled() ?: return null
        val effect = "Android disables accessibility services that are not assistive tools and blocks sideloading"
        return DeviceFeature(
            id = DeviceFeatureIds.ADVANCED_PROTECTION,
            title = "Advanced Protection is off",
            available = !on,
            detail = if (on) {
                "Advanced Protection is on, and while it is on $effect."
            } else {
                "Advanced Protection is off; while it is on, $effect."
            },
        )
    }

    private fun isInstalled(packageName: String): Boolean = pm.packageInfoOrNull(packageName) != null

    private companion object {
        /** `reqGlEsVersion` packs major.minor as two 16-bit halves. */
        const val GL_ES_3_1 = 0x0003_0001
    }
}
