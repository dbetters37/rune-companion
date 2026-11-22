package com.aicontrolcenter.providers.http

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.failsWith
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EndpointTest {
    private fun resolve(baseUrl: String?, default: String = "https://api.example.com/v1", kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE) =
        Endpoint.resolve(kind, baseUrl, default)

    @Test
    fun `paths are appended to the base path with or without a trailing slash`() {
        assertEquals("https://api.example.com/v1/chat/completions", resolve("https://api.example.com/v1").url("chat/completions").toString())
        assertEquals("https://api.example.com/v1/chat/completions", resolve("https://api.example.com/v1/").url("/chat/completions").toString())
        assertEquals("https://host.example/models?pageSize=1", resolve(" https://host.example ").url("models", mapOf("pageSize" to "1")).toString())
    }

    @Test
    fun `a query on the base address is kept`() {
        assertEquals(
            "https://x.example/openai?api-version=2025-01-01&alt=sse",
            resolve("https://x.example/openai?api-version=2025-01-01").url("", mapOf("alt" to "sse")).toString().replace("/?", "?"),
        )
    }

    @Test
    fun `a blank address means the default`() {
        assertEquals("api.example.com", resolve(null).host)
        assertEquals("api.example.com", resolve("   ").host)
    }

    @Test
    fun `plain http is accepted for this device and the local network`() {
        for (local in listOf(
            "http://localhost:1234/v1", "http://127.0.0.1:8080", "http://192.168.1.20:11434", "http://10.0.0.5:8000/v1",
            "http://172.16.4.4:8000", "http://nas.local:11434", "http://[::1]:11434", "http://box.lan/v1",
        )) {
            resolve(local)
        }
    }

    @Test
    fun `plain http to anywhere else is refused before anything is sent`() {
        for (remote in listOf(
            "http://api.openai.com/v1", "http://8.8.8.8:8080", "http://example.com", "http://172.32.0.1:8000",
            // Not classifiable as local by the strict parser, so treated as cloud.
            "http://localhost\\@evil.example/v1", "http://2130706433/v1", "http://0x7f.1/v1",
        )) {
            val error = failsWith<LlmException.BlockedByPolicy> { resolve(remote) }
            assertFalse(error.retryable, remote)
        }
    }

    @Test
    fun `plain http is refused for a remote default too`() {
        failsWith<LlmException.BlockedByPolicy> { resolve(null, default = "http://api.example.com/v1") }
    }

    @Test
    fun `https is accepted anywhere`() {
        resolve("https://api.openai.com/v1")
        resolve("https://8.8.8.8/v1")
    }

    @Test
    fun `an address that is not an http url is an invalid request`() {
        for (bad in listOf("api.openai.com/v1", "ftp://host/v1", "ws://host", "not a url", "http://")) {
            failsWith<LlmException.InvalidRequest> { resolve(bad) }
        }
    }

    @Test
    fun `credentials inside the address are refused and not repeated`() {
        val error = failsWith<LlmException.InvalidRequest> { resolve("https://user:hunter2secret@host.example/v1") }
        assertFalse("hunter2secret" in error.message.orEmpty())
    }

    @Test
    fun `toString shows no path or query`() {
        assertEquals("Endpoint(https://host.example:443)", resolve("https://host.example/v1?key=abcdef123456").toString())
    }
}
