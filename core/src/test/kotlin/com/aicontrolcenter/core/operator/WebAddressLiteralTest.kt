package com.aicontrolcenter.core.operator

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Phase 6, K6 (D8): hosts refused before anything is looked up or asked. */
class WebAddressLiteralTest {
    @Test
    fun `public names and public literal addresses are left to the read`() {
        for (host in listOf("example.com", "www.example.org.", "EXAMPLE.COM", "a.b.c.example.net", "93.184.216.34", "8.8.8.8", "[2606:4700:4700::1111]", "2606:4700:4700::1111")) {
            assertNull(WebAddressPolicy.literalRefusal(host), host)
        }
    }

    @Test
    fun `this device, the local network and local names are refused`() {
        for (host in listOf(
            "localhost", "LOCALHOST", "localhost.", "app.localhost", "router.local", "router.local.", "nas.internal", "printer.home.arpa",
            "box.lan", "router", "nas", "127.0.0.1", "127.0.0.1.", "192.168.1.10", "10.0.0.1", "172.16.5.4", "169.254.1.1", "100.64.0.1",
            "0.0.0.0", "[::1]", "::1", "::", "fe80::1", "fd00::1", "[::ffff:127.0.0.1]", "::ffff:7f00:1", "::ffff:192.168.1.10", "64:ff9b::c0a8:10a",
            "224.0.0.1", "255.255.255.255",
        )) {
            assertNotNull(WebAddressPolicy.literalRefusal(host), host)
        }
    }

    @Test
    fun `numbers in unusual forms are refused without a lookup, whatever they point to`() {
        for (host in listOf("2130706433", "0177.0.0.1", "0x7f.0.0.1", "0x7f000001", "127.1", "010.0.0.1", "1.2.3", "1.2.3.4.5", "256.1.1.1", "0x", "08.8.8.8")) {
            assertEquals(WebAddressPolicy.NOT_A_USUAL_ADDRESS, WebAddressPolicy.literalRefusal(host), host)
        }
        assertEquals(WebAddressPolicy.NOT_A_USUAL_ADDRESS, WebAddressPolicy.literalRefusal(""))
        assertEquals(WebAddressPolicy.NOT_A_USUAL_ADDRESS, WebAddressPolicy.literalRefusal("."))
        assertEquals(WebAddressPolicy.NOT_A_USUAL_ADDRESS, WebAddressPolicy.literalRefusal("g:1"), "a colon that is no IPv6 literal is never looked up")
        assertEquals(WebAddressPolicy.NOT_A_USUAL_ADDRESS, WebAddressPolicy.literalRefusal("1::2::3"))
    }

    @Test
    fun `refusals read as plain sentences`() {
        assertEquals("Pages on a local network name are not read: only public web addresses are.", WebAddressPolicy.literalRefusal("router.local"))
        assertEquals("Pages on this device (loopback) are not read: only public web addresses are.", WebAddressPolicy.literalRefusal("localhost"))
        assertEquals("Pages on a private network address are not read: only public web addresses are.", WebAddressPolicy.literalRefusal("192.168.1.10"))
    }
}
