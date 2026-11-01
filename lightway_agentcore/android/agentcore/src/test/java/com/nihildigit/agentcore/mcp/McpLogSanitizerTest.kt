package com.nihildigit.agentcore.mcp

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpLogSanitizerTest {
    @Test
    fun stripsModelScopeInstancePath() {
        assertEquals("https://mcp.example.invalid", redactMcpUrl(
            "https://mcp.example.invalid/example-instance/mcp"
        ))
    }

    @Test
    fun stripsQueryFragmentAndUserInfo() {
        assertEquals("https://mcp.example.com", redactMcpUrl(
            "https://user:password@mcp.example.com/didi?key=secret#session"
        ))
    }

    @Test
    fun keepsOnlyNonDefaultPorts() {
        assertEquals("https://example.com", redactMcpUrl("https://example.com:443/path"))
        assertEquals("http://example.com", redactMcpUrl("http://example.com:80/path"))
        assertEquals("https://example.com:8443", redactMcpUrl("https://example.com:8443/path"))
        assertEquals("https://[::1]:8443", redactMcpUrl("https://[::1]:8443/path"))
    }

    @Test
    fun rejectsInvalidOrHostlessInput() {
        listOf("not a URI", "/messages/?session_id=secret", "", "https:///path", "https://example.com:99999")
            .forEach { assertEquals("<redacted-url>", redactMcpUrl(it)) }
    }

    @Test
    fun hidesSessionTokens() {
        assertEquals("(none)", redactSessionId(null))
        assertEquals("(none)", redactSessionId(""))
        assertEquals("<present>", redactSessionId("secret-session"))
    }

    @Test
    fun redactsEmbeddedUrlsInMessages() {
        val raw = "Failed POST https://mcp.example.invalid/mcp-servers-sandbox?key=secret123 (timeout)."
        assertEquals(
            "Failed POST https://mcp.example.invalid (timeout).",
            redactUrlsInMessage(raw)
        )
    }

    @Test
    fun enforcesSameOriginForSseEndpoint() {
        val base = "https://mcp.example.invalid/example-instance/sse"
        assertEquals(
            "https://mcp.example.invalid/messages/?session_id=abc",
            resolveSameOriginUrl(base, "/messages/?session_id=abc")
        )
        assertThrows(StreamableHttpError::class.java) {
            resolveSameOriginUrl(base, "https://attacker.example/messages")
        }
        assertThrows(StreamableHttpError::class.java) {
            resolveSameOriginUrl(base, "http://mcp.example.invalid/messages")
        }
        assertThrows(StreamableHttpError::class.java) {
            resolveSameOriginUrl(base, "//attacker.example/messages")
        }
        assertThrows(StreamableHttpError::class.java) {
            resolveSameOriginUrl(base, "https://user:pass@mcp.example.invalid/messages")
        }
    }
}
