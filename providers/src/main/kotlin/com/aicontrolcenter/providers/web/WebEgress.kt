package com.aicontrolcenter.providers.web

import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.operator.WebReadResult
import com.aicontrolcenter.core.registry.Locality

/**
 * Egress records for page reads, in the form the activity log uses for model calls. What
 * leaves the device is the page address (path and query included) and standard request
 * headers: no cookies, no credentials, no body. Every host a read may reach is public
 * ([com.aicontrolcenter.core.operator.WebAddressPolicy]), so the locality is always CLOUD.
 */
object WebEgress {
    const val PURPOSE = "web page request (the page address and standard headers)"

    /** One request sent to [host], [approxBytes] long when known. */
    fun of(host: String, approxBytes: Long? = null): EgressInfo =
        EgressInfo(destinationHost = host, locality = Locality.CLOUD, purpose = PURPOSE, approxBytes = approxBytes)

    /** One record per host a successful read contacted ([WebReadResult.Page.hostsContacted]), in order. */
    fun forPage(page: WebReadResult.Page): List<EgressInfo> = page.hostsContacted.map { of(it) }
}
