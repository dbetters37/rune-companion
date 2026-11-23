package com.aicontrolcenter.device.operator.apps

import android.content.Intent
import android.net.Uri

/**
 * The implicit intents [AndroidAppActions] resolves device-wide, to know whether any app takes a
 * hand-over and that none of the takers is protected. Since Android 11 a query sees only the
 * apps a manifest `<queries>` entry makes visible, so the manifest lists each of these;
 * `QueriesManifestTest` compares the two.
 */
internal enum class HandoverQuery(val action: String, val scheme: String? = null, val category: String? = null) {
    /** Browsers and apps that open web links (open_link, browser.open). */
    WEB_LINK_HTTPS(Intent.ACTION_VIEW, scheme = "https", category = Intent.CATEGORY_BROWSABLE),
    WEB_LINK_HTTP(Intent.ACTION_VIEW, scheme = "http", category = Intent.CATEGORY_BROWSABLE),

    /** Browsers that offer Custom Tabs (`CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION`). */
    CUSTOM_TABS("android.support.customtabs.action.CustomTabsService"),

    /** Mail apps (compose_email). */
    EMAIL(Intent.ACTION_SENDTO, scheme = "mailto"),

    /** Messaging apps (compose_sms). */
    SMS(Intent.ACTION_SENDTO, scheme = "smsto"),

    /** The dialer (dial). */
    DIAL(Intent.ACTION_DIAL, scheme = "tel"),
    ;

    /** An intent with this signature; [data] defaults to an empty URI of [scheme]. */
    fun intent(data: Uri? = scheme?.let { Uri.parse("$it:") }): Intent {
        val intent = Intent(action)
        if (data != null) intent.data = data
        category?.let(intent::addCategory)
        return intent
    }
}
