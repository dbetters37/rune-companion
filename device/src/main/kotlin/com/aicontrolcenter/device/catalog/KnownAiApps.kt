package com.aicontrolcenter.device.catalog

/**
 * The built-in catalog of known AI application packages.
 *
 * To add an app: append a [KnownAiApp] to [cloudAiApps] or [localAiApps] and add the matching
 * `<package>` line to this module's `AndroidManifest.xml`. Without that line Android hides the
 * package from this app unless it has a launcher icon; a unit test fails when the two lists
 * drift apart.
 */
object KnownAiApps {
    /** Every catalog entry, cloud apps first. Package names are unique. */
    val all: List<KnownAiApp> = cloudAiApps + localAiApps

    private val byPackage: Map<String, KnownAiApp> = all.associateBy { it.packageName }

    val packageNames: Set<String> get() = byPackage.keys

    /** The catalog entry for [packageName], or null. Package names are case-sensitive. */
    fun find(packageName: String): KnownAiApp? = byPackage[packageName]
}

/**
 * Packages that are not AI applications but whose presence this product reports as a device
 * feature. They are declared in the manifest `<queries>` block alongside the catalog.
 */
object CompanionPackages {
    /**
     * Android AICore, the system service that hosts Gemini Nano. The id is unconfirmed (recalled,
     * not read from a vendor source) and the package has no launcher icon.
     */
    const val AICORE = "com.google.android.aicore"

    /** Termux:API, the add-on that lets Termux scripts call Android APIs. */
    const val TERMUX_API = "com.termux.api"

    /** Termux itself is a catalog entry; repeated here because the device report looks for it. */
    const val TERMUX = "com.termux"

    /** Companion packages that are not also catalog entries. */
    val notInCatalog: Set<String> = setOf(AICORE, TERMUX_API)
}
