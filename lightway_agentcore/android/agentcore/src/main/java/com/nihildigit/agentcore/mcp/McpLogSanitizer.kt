package com.nihildigit.agentcore.mcp

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import java.net.URI

private val URL_IN_TEXT_REGEX = Regex("""(?:https?|wss?)://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ')', ']', '}')

private fun defaultPortForScheme(scheme: String): Int = when (scheme.lowercase()) {
    "https", "wss" -> 443
    "http", "ws" -> 80
    else -> -1
}

private fun effectivePort(uri: URI): Int {
    val scheme = uri.scheme?.lowercase() ?: return -1
    return if (uri.port >= 0) uri.port else defaultPortForScheme(scheme)
}

/** Keep only the server origin; paths and credentials may contain access tokens. */
internal fun redactMcpUrl(rawUrl: String): String = runCatching {
    val uri = URI(rawUrl)
    val scheme = uri.scheme?.lowercase() ?: return@runCatching "<redacted-url>"
    val host = uri.host ?: return@runCatching "<redacted-url>"
    val port = uri.port
    if (port > 65535) return@runCatching "<redacted-url>"
    val defaultPort = port == defaultPortForScheme(scheme)
    val portSuffix = if (port >= 0 && !defaultPort) ":$port" else ""
    "$scheme://$host$portSuffix"
}.getOrDefault("<redacted-url>")

internal fun redactSessionId(sessionId: String?): String =
    if (sessionId.isNullOrEmpty()) "(none)" else "<present>"

/** Redact any embedded HTTP(S)/WS(S) URL inside free-form error messages before logging or propagating. */
internal fun redactUrlsInMessage(message: String?): String {
    if (message.isNullOrBlank()) return "unknown"
    return URL_IN_TEXT_REGEX.replace(message) { match ->
        val raw = match.value
        val trimmed = raw.trimEnd(*TRAILING_PUNCTUATION)
        val suffix = raw.substring(trimmed.length)
        redactMcpUrl(trimmed) + suffix
    }
}

/** Resolve an SSE endpoint against baseUrl and reject cross-origin or credential-bearing redirects. */
internal fun resolveSameOriginUrl(baseUrl: String, endpoint: String): String {
    val trimmed = endpoint.trim()
    if (trimmed.isEmpty()) throw StreamableHttpError(null, "Missing endpoint from SSE")
    val base = runCatching { URI(baseUrl) }.getOrElse {
        throw StreamableHttpError(null, "Invalid base SSE URL")
    }
    val baseScheme = base.scheme?.lowercase() ?: throw StreamableHttpError(null, "Invalid base SSE URL")
    val baseHost = base.host ?: throw StreamableHttpError(null, "Invalid base SSE URL")
    val resolved = runCatching { base.resolve(trimmed) }.getOrElse {
        throw StreamableHttpError(null, "Invalid endpoint from SSE")
    }
    val resolvedScheme = resolved.scheme?.lowercase()
    val resolvedHost = resolved.host
    if (
        resolved.userInfo != null ||
        resolvedScheme != baseScheme ||
        resolvedHost == null ||
        !resolvedHost.equals(baseHost, ignoreCase = true) ||
        effectivePort(resolved) != effectivePort(base)
    ) {
        throw StreamableHttpError(null, "SSE endpoint origin mismatch")
    }
    return resolved.toString()
}
