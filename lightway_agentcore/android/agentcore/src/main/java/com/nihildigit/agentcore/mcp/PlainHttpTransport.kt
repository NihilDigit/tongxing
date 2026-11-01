package com.nihildigit.agentcore.mcp

import com.nihildigit.agentcore.logging.AgentLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Plain HTTP JSON-RPC Transport for MCP servers that don't use SSE streaming.
 *
 * Some MCP servers (like ModelScope and DiDi) implement "Streamable HTTP" protocol but return
 * synchronous JSON responses instead of Server-Sent Events (SSE) streams.
 * The standard SDK's `StreamableHttpClientTransport` expects SSE (`text/event-stream`)
 * and fails with "Expected Content-Type text/event-stream but was text/plain" or
 * "...but was application/json".
 *
 * This transport handles the simpler HTTP POST -> JSON response pattern:
 * - POST JSON-RPC request to endpoint
 * - Receive JSON-RPC response directly (not via SSE)
 * - Parse and deliver response to SDK
 * 
 * ## Session Management
 * ModelScope-style servers require session management:
 * - Initialize request sent without session ID
 * - Server returns `Mcp-Session-Id` header in response
 * - All subsequent requests must include the session ID header
 *
 * ## Usage
 * ```kotlin
 * val transport = PlainHttpTransport(
 *     client = httpClient,
 *     url = "https://mcp.api-inference.modelscope.net/{id}/mcp",
 *     customHeaders = mapOf("User-Agent" to "TongXing/1.0")
 * )
 * mcpClient.connect(transport)
 * ```
 *
 * ## Protocol Compatibility
 * - ModelScope MCP Server (mcp.api-inference.modelscope.net)
 * - DiDi MCP Server (mcp.didichuxing.com)
 * - Any MCP server returning `application/json` instead of `text/event-stream`
 */
class PlainHttpTransport(
    private val client: HttpClient,
    private val url: String,
    private val customHeaders: Map<String, String> = emptyMap()
) : AbstractTransport() {

    companion object {
        private const val TAG = "PlainHttpTransport"
        private const val SESSION_ID_HEADER = "Mcp-Session-Id"
    }
    
    // Session ID received from server after initialize
    @Volatile
    private var sessionId: String? = null

    override suspend fun start() {
        logI("PlainHttpTransport started for ${redactMcpUrl(url)}")
        // No persistent connection needed - each request is independent
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        withContext(Dispatchers.IO) {
            val normalized = normalizeMessage(message)
            val body = McpJson.encodeToString(JsonObject.serializer(), normalized)
            val method = normalized["method"]?.jsonPrimitive?.content ?: "<unknown>"
            val rpcId = normalized["id"]?.jsonPrimitive?.content ?: "<none>"
            logD("POST ${redactMcpUrl(url)} method=$method id=$rpcId sessionId=${redactSessionId(sessionId)}")

            try {
                val response = client.post(url) {
                    // DiDi requires charset in Content-Type
                    contentType(ContentType.Application.Json.withParameter("charset", "utf-8"))
                    headers {
                        customHeaders.forEach { (k, v) -> append(k, v) }
                        // Accept both JSON (primary) and event-stream (fallback)
                        append("Accept", "application/json, text/event-stream")
                        // Include session ID if available (required for non-initialize requests)
                        sessionId?.let { sid ->
                            append(SESSION_ID_HEADER, sid)
                            logD("Including session ID: ${redactSessionId(sid)}")
                        }
                    }
                    setBody(body)
                }

                logD("Response status: ${response.status.value}")
                logD("Response Content-Type: ${response.contentType()?.withoutParameters()}")
                
                // Capture session ID from response headers (usually from initialize response)
                response.headers[SESSION_ID_HEADER]?.let { sid ->
                    if (sessionId == null) {
                        sessionId = sid
                        logI("Captured session ID: ${redactSessionId(sid)}")
                    }
                }

                if (!response.status.isSuccess()) {
                    val error = StreamableHttpError(response.status.value, "HTTP ${response.status.value}")
                    logW("HTTP error ${response.status.value}")
                    _onError(error)
                    throw error
                }

                val responseBody = response.bodyAsText()
                logD("Response body length: ${responseBody.length}")

                if (responseBody.isBlank()) {
                    logD("Empty response body, no message to deliver")
                    return@withContext
                }

                // Parse JSON-RPC response
                runCatching { McpJson.decodeFromString<JSONRPCMessage>(responseBody) }
                    .onSuccess { msg ->
                        logD("Decoded response: ${describeMessage(msg)}")
                        _onMessage(msg)
                    }
                    .onFailure { err ->
                        logW("Failed to decode response: ${err.javaClass.simpleName}")
                        logD("Undecodable response length: ${responseBody.length}")
                        _onError(err)
                    }

            } catch (e: StreamableHttpError) {
                throw e
            } catch (e: Exception) {
                logE("Request failed: ${e.javaClass.simpleName}")
                _onError(e)
                throw e
            }
        }
    }

    override suspend fun close() {
        logI("PlainHttpTransport closed")
        // No persistent connection to close
    }

    /**
     * Normalize JSON-RPC message for compatibility.
     * - Ensures protocol version is set for initialize requests
     * - Ensures capabilities are present for initialize requests (required by some servers)
     * - Ensures request ID is present
     */
    private fun normalizeMessage(message: JSONRPCMessage): JsonObject {
        val element = McpJson.encodeToJsonElement(message)
        val obj = element as? JsonObject ?: return buildJsonObject { }

        val method = obj["method"]?.jsonPrimitive?.content
        if (method != "initialize") return obj

        // Patch initialize request for compatibility
        val params = obj["params"] as? JsonObject ?: buildJsonObject {}
        val patchedParams = buildJsonObject {
            params.forEach { (k, v) ->
                if (k == "protocolVersion") {
                    // Use older protocol version for compatibility
                    put(k, JsonPrimitive("2024-11-05"))
                } else {
                    put(k, v)
                }
            }
            if (!params.containsKey("protocolVersion")) {
                put("protocolVersion", JsonPrimitive("2024-11-05"))
            }
            // Ensure capabilities is present (ModelScope requires this)
            if (!params.containsKey("capabilities")) {
                putJsonObject("capabilities") { }
            }
        }

        return buildJsonObject {
            obj.forEach { (k, v) ->
                when (k) {
                    "id" -> put(k, (v as? JsonPrimitive) ?: JsonPrimitive("init-${System.currentTimeMillis()}"))
                    "params" -> put(k, patchedParams)
                    else -> put(k, v)
                }
            }
            if (!obj.containsKey("id")) {
                put("id", JsonPrimitive("init-${System.currentTimeMillis()}"))
            }
        }
    }

    private fun describeMessage(msg: JSONRPCMessage): String = when (msg) {
        is JSONRPCRequest -> "Request(method=${msg.method}, id=${msg.id})"
        is JSONRPCResponse -> "Response(id=${msg.id})"
        is JSONRPCNotification -> "Notification(method=${msg.method})"
        else -> "Unknown"
    }

    private fun logD(message: String) = AgentLogger.verbose(TAG, "Transport", message)
    private fun logI(message: String) = AgentLogger.normal(TAG, "Transport", message)
    private fun logW(message: String) = AgentLogger.normal(TAG, "Transport", "[WARN] $message")
    private fun logE(message: String, throwable: Throwable? = null) = 
        AgentLogger.error(TAG, "Transport", message, throwable)
}
