package com.aicontrolcenter.core.device

import kotlinx.serialization.Serializable

/**
 * What this phone can do, measured at runtime. Used to explain which local models are realistic
 * and which platform integrations are present. Everything here is read from public Android
 * APIs about this device; nothing is sent anywhere.
 */
@Serializable
data class DeviceReport(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String? = null,
    val socManufacturer: String? = null,
    val socModel: String? = null,
    val supportedAbis: List<String> = emptyList(),
    val cpuCores: Int = 0,
    val totalRamBytes: Long = 0,
    val availableRamBytes: Long = 0,
    val isLowRamDevice: Boolean = false,
    val storageTotalBytes: Long = 0,
    val storageFreeBytes: Long = 0,
    /** OpenGL ES version string such as "3.2", when the device reports one. */
    val openGlEsVersion: String? = null,
    /** Named platform capabilities and whether each is present on this device. */
    val features: List<DeviceFeature> = emptyList(),
    val generatedAt: Long = 0,
) {
    val totalRamGb: Double get() = totalRamBytes / GIB
    val storageFreeGb: Double get() = storageFreeBytes / GIB

    /** A rule-of-thumb statement of which on-device models are realistic for this much memory. */
    val localModelGuidance: LocalModelGuidance get() = LocalModelGuidance.forRam(totalRamBytes)

    private companion object {
        const val GIB = 1024.0 * 1024.0 * 1024.0
    }
}

@Serializable
data class DeviceFeature(
    val id: String,
    val title: String,
    val available: Boolean,
    /** One sentence saying what the feature enables, or what the user must do to enable it. */
    val detail: String = "",
)

/** Coarse guidance from installed RAM. Quantised (roughly 4-bit) model sizes are assumed. */
enum class LocalModelGuidance(val headline: String, val detail: String) {
    UNKNOWN(
        "Unknown",
        "The device did not report its memory size.",
    ),
    MINIMAL(
        "Very small models only",
        "Under 4 GB of RAM: models up to about 1 billion parameters. Expect short context and slow replies.",
    ),
    SMALL(
        "Small models",
        "4 to 6 GB of RAM: models of 1 to 2 billion parameters run comfortably.",
    ),
    MEDIUM(
        "Small and mid-size models",
        "6 to 10 GB of RAM: models up to about 4 billion parameters are practical.",
    ),
    LARGE(
        "Mid-size models",
        "10 to 14 GB of RAM: models of 4 to 8 billion parameters are practical.",
    ),
    VERY_LARGE(
        "Larger models",
        "14 GB of RAM or more: models of 8 billion parameters and somewhat beyond can fit; speed depends on the chip.",
    ),
    ;

    companion object {
        private const val GIB = 1024L * 1024L * 1024L

        /** Thresholds sit a little below the marketed size because devices report slightly less than nominal RAM. */
        fun forRam(totalRamBytes: Long): LocalModelGuidance = when {
            totalRamBytes <= 0 -> UNKNOWN
            totalRamBytes < (3.6 * GIB).toLong() -> MINIMAL
            totalRamBytes < (5.6 * GIB).toLong() -> SMALL
            totalRamBytes < (9.5 * GIB).toLong() -> MEDIUM
            totalRamBytes < (13.5 * GIB).toLong() -> LARGE
            else -> VERY_LARGE
        }
    }
}

interface DeviceInspector {
    suspend fun inspect(): DeviceReport
}
