package com.aicontrolcenter.device.catalog

/**
 * How a catalog package id was established. Mirrors the confidence tags of the research the
 * catalog was seeded from, so the app never presents a guessed id as a confirmed one.
 */
enum class IdEvidence {
    /** Read from the vendor's own repository, build file or `assetlinks.json`. */
    VENDOR_SOURCE,

    /** A Google Play listing URL for this id surfaced in web search; the listing was not fetched. */
    STORE_LISTING,

    /** Recalled only. Needs confirmation on a device that has the app. */
    UNCONFIRMED,
}

/** What sort of AI application a catalog entry is. Drives [KnownAiApp.hostsLocalModels]. */
enum class KnownAiAppKind(val hostsLocalModels: Boolean) {
    /** The vendor's app for a model served from the cloud. */
    CLOUD_ASSISTANT(false),

    /** A browser whose AI assistant is a built-in feature rather than a separate package. */
    AI_BROWSER(false),

    /** Downloads and runs models on the phone itself. */
    LOCAL_RUNNER(true),

    /** A chat client for servers or API keys the user supplies; runs no model itself. */
    API_CLIENT(false),

    /** A terminal that can run commands and host local model servers (Termux). */
    TERMINAL(true),
}

/**
 * A URL that opens an app with a prompt already typed in.
 *
 * @property template https URL in which [PromptLinkTemplate.PLACEHOLDER] is replaced by the
 *   URL-encoded prompt.
 * @property verified true only when the vendor documents the link for its Android app. Unverified
 *   links are kept as leads for on-device confirmation and are never offered as a capability.
 * @property opens what the user sees after following the link.
 */
data class PromptDeepLink(
    val template: String,
    val verified: Boolean,
    val opens: String,
)

/**
 * One known AI application package.
 *
 * The catalog only says that a package id belongs to a known product. Everything the registry
 * claims an installed app can do is detected on the device; the two exceptions that cannot be
 * detected are carried here: [promptLinks] and [hostsLocalModels].
 *
 * @property name the product name used in the catalog. The label shown to the user always comes
 *   from the installed package.
 * @property vendor the company or maintainer shown next to the app.
 */
data class KnownAiApp(
    val packageName: String,
    val name: String,
    val vendor: String,
    val kind: KnownAiAppKind,
    val idEvidence: IdEvidence,
    val promptLinks: List<PromptDeepLink> = emptyList(),
) {
    /** True when the id was read from the vendor's own published sources. */
    val idVerified: Boolean get() = idEvidence == IdEvidence.VENDOR_SOURCE

    /**
     * True when the app runs models or commands on this phone. This does not mean the app exposes
     * an interface this framework can call: no inter-app API is documented for the local runners.
     */
    val hostsLocalModels: Boolean get() = kind.hostsLocalModels

    /** The vendor-documented prompt link, if there is one. */
    val verifiedPromptLink: PromptDeepLink? get() = promptLinks.firstOrNull { it.verified }
}
