package com.nihildigit.agentcore.mcp

import com.nihildigit.agentcore.logging.AgentLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * ModelScope-specific Streamable HTTP transport (DEPRECATED).
 *
 * ## DEPRECATION NOTICE
 * This custom transport is **only needed for legacy ModelScope `/sse` endpoints**.
 * ModelScope's new `/mcp` endpoints fully support standard Streamable HTTP protocol
 * and work with the SDK's built-in `StreamableHttpClientTransport`.
 *
 * **When to use this:**
 * - Legacy ModelScope endpoints like: `https://mcp.api-inference.modelscope.net/{id}/sse`
 *
 * **When NOT to use this:**
 * - New ModelScope endpoints like: `https://mcp.api-inference.modelscope.net/{id}/mcp`
 * - Use standard `StreamableHttpClientTransport` instead
 *
 * ## Legacy Implementation Details
 * ModelScope's old SSE endpoints use a non-standard architecture:
 * - SSE GET on /{service-id}/sse (read-only)
 * - JSON-RPC POST on /messages/?session_id=... (write-only)
 * - session_id is obtained from the SSE `event: endpoint` payload
 *
 * ## Key Compatibility Fixes
 * 1. **Content-Type**: Uses `application/json` without charset (阿里云兼容性要求)
 * 2. **Protocol Version**: Fixed to `2024-11-05` (older version for compatibility)
 * 3. **Split Channels**: Separate SSE (read) and POST (write) URLs
 * 4. **Dynamic Endpoint**: Waits for SSE endpoint event before sending requests
 *
 * @see <a href="https://blog.csdn.net/qq_42145865/article/details/151954884">ModelScope MCP 调试参考</a>
 */
@Deprecated(
    message = "Only needed for legacy ModelScope /sse endpoints. Use standard StreamableHttpClientTransport for new /mcp endpoints.",
    replaceWith = ReplaceWith("StreamableHttpClientTransport"),
    level = DeprecationLevel.WARNING
)
class ModelScopeStreamableHttpTransport(
    private val client: HttpClient,
    private val sseUrl: String,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val reconnectionTime: Duration? = null
) : AbstractTransport() {

    companion object {
        private const val TAG = "ModelScopeTransport"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sseJob: Job? = null
    private val endpointDeferred = CompletableDeferred<String>()

    @Volatile
    private var messagesUrl: String? = null

    private fun normalizeMessage(message: JSONRPCMessage): JsonObject {
        val element = McpJson.encodeToJsonElement(message)
        val obj = element as? JsonObject ?: return buildJsonObject { }

        val method = obj["method"]?.jsonPrimitive?.content
        if (method != "initialize") return obj

        val params = obj["params"] as? JsonObject ?: buildJsonObject {}
        val patchedParams = buildJsonObject {
            params.forEach { (k, v) ->
                if (k == "protocolVersion") {
                    // Try older protocol version for ModelScope compatibility
                    put(k, JsonPrimitive("2024-11-05"))
                } else {
                    put(k, v)
                }
            }
            if (!params.containsKey("protocolVersion")) {
                put("protocolVersion", JsonPrimitive("2024-11-05"))
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
        is JSONRPCRequest -> "method=${msg.method} id=${msg.id}"
        is JSONRPCResponse -> "id=${msg.id}"
        is JSONRPCNotification -> "method=${msg.method}"
        else -> ""
    }

    override suspend fun start() {
        startSse()
        // Wait for endpoint event to derive messages URL (fail fast if missing)
        val endpoint = withTimeout(5_000) { endpointDeferred.await() }
        messagesUrl = resolveMessagesUrl(endpoint)
        logI("resolved messages URL = ${redactMcpUrl(messagesUrl.orEmpty())}")
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        val targetUrl = messagesUrl ?: throw StreamableHttpError(null, "Missing messagesUrl (endpoint not received)")
        val normalized = normalizeMessage(message)
        val body = McpJson.encodeToString(JsonObject.serializer(), normalized)
        val method = normalized["method"]?.jsonPrimitive?.content ?: "<unknown>"
        val rpcId = normalized["id"]?.jsonPrimitive?.content ?: "<none>"
        logD("POST ${redactMcpUrl(targetUrl)} method=$method id=$rpcId payloadLength=${body.length}")

        try {
            val response = client.post(targetUrl) {
                // IMPORTANT: ModelScope doesn't support charset in Content-Type
                // Use application/json without charset for compatibility
                contentType(ContentType.Application.Json)
                headers {
                    customHeaders.forEach { (k, v) -> append(k, v) }
                    // Accept both JSON (for inline responses) and event-stream (some servers echo via SSE)
                    append(HttpHeaders.Accept, "${ContentType.Application.Json}, ${ContentType.Text.EventStream}")
                }
                setBody(body)
            }

            logD("POST response status=${response.status.value}")
            logD("POST response contentType=${response.contentType()?.withoutParameters()}")

            if (response.status.isSuccess()) {
                // 202 is typical; if server responds with inline JSON, try to parse
                val isJson = response.contentType()?.match(ContentType.Application.Json) == true
                val responseBody = response.bodyAsText()
                logD("POST success, isJson=$isJson, bodyLength=${responseBody.length}")

                if (isJson && responseBody.isNotEmpty()) {
                    runCatching { McpJson.decodeFromString<JSONRPCMessage>(responseBody) }
                        .onSuccess { msg ->
                            logD("inline JSON response decoded -> type=${msg.javaClass.simpleName} ${describeMessage(msg)}")
                            _onMessage(msg)
                        }
                        .onFailure { err ->
                            logW("inline JSON decode error: ${err.javaClass.simpleName}")
                            _onError(err)
                        }
                } else {
                    logD("POST accepted (status=${response.status.value}), waiting for SSE response...")
                }
                return
            }

            val error = StreamableHttpError(response.status.value, "HTTP ${response.status.value}")
            logW("POST ${redactMcpUrl(targetUrl)} failed status=${response.status.value}")
            _onError(error)
            throw error
        } catch (e: Exception) {
            logE("POST exception: ${e.javaClass.simpleName}")
            throw e
        }
    }

    override suspend fun close() {
        sseJob?.cancelAndJoin()
        scope.cancel()
    }

    private fun startSse() {
        sseJob?.cancel()
        sseJob = scope.launch {
            try {
                logI("Starting SSE connection to ${redactMcpUrl(sseUrl)}")
                client.sse(
                    urlString = sseUrl,
                    reconnectionTime = reconnectionTime,
                    request = {
                        headers {
                            customHeaders.forEach { (k, v) -> append(k, v) }
                        }
                    }
                ) {
                    logD("SSE connection established, waiting for events...")
                    incoming.collect { event ->
                        val data = event.data ?: ""
                        val eventType = event.event ?: "message"
                        logD("SSE received event data.length=${data.length}")

                        if (eventType == "endpoint" && !endpointDeferred.isCompleted) {
                            logI("received endpoint event: <redacted>")
                            endpointDeferred.complete(data)
                            return@collect
                        }

                        val trimmed = data.trim()
                        if (trimmed.isEmpty()) {
                            logD("SSE event with empty data after trim, skipping")
                            return@collect
                        }

                        // Only attempt JSON decode for payloads that look like JSON objects
                        if (!trimmed.startsWith("{")) {
                            logD("SSE non-JSON event data.length=${trimmed.length}")
                            return@collect
                        }

                        logD("SSE JSON payload data.length=${trimmed.length}")
                        runCatching { McpJson.decodeFromString<JSONRPCMessage>(trimmed) }
                            .onSuccess { msg ->
                                logD("SSE JSON-RPC decoded -> type=${msg.javaClass.simpleName} ${describeMessage(msg)}")
                                _onMessage(msg)
                            }
                            .onFailure { err ->
                                logW("SSE decode error: ${err.javaClass.simpleName}")
                            }
                    }
                }
                logD("SSE connection closed normally")
            } catch (ce: CancellationException) {
                logD("SSE cancelled")
            } catch (t: Throwable) {
                logE("SSE error: ${t.javaClass.simpleName}")
                if (!endpointDeferred.isCompleted) {
                    endpointDeferred.completeExceptionally(t)
                }
                _onError(t)
            } finally {
                if (!endpointDeferred.isCompleted) {
                    val error = StreamableHttpError(null, "SSE closed before endpoint event")
                    logW("SSE closed but endpoint not received")
                    endpointDeferred.completeExceptionally(error)
                }
            }
        }
    }

    private suspend fun resolveMessagesUrl(endpoint: String): String = withContext(Dispatchers.Default) {
        resolveSameOriginUrl(sseUrl, endpoint)
    }

    // Logging helpers using AgentLogger
    private fun logD(message: String) = AgentLogger.verbose("MCP", "HttpTransport", message)
    private fun logI(message: String) = AgentLogger.normal("MCP", "HttpTransport", message)
    private fun logW(message: String) = AgentLogger.normal("MCP", "HttpTransport", message)
    private fun logE(message: String, throwable: Throwable? = null) = AgentLogger.error("MCP", "HttpTransport", message, throwable)

    private inline fun safeLog(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Fallback for JVM unit tests where android.util.Log is not available
        }
    }
}
