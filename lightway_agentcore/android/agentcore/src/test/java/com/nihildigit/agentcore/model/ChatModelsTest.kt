package com.nihildigit.agentcore.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ChatModels serialization and data classes.
 */
class ChatModelsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `ChatMessage serialization with content`() {
        val message = ChatMessage(role = "user", content = "Hello")
        val serialized = json.encodeToString(ChatMessage.serializer(), message)

        assertTrue(serialized.contains("\"role\":\"user\""))
        assertTrue(serialized.contains("\"content\":\"Hello\""))

        val deserialized = json.decodeFromString(ChatMessage.serializer(), serialized)
        assertEquals(message.role, deserialized.role)
        assertEquals(message.content, deserialized.content)
    }

    @Test
    fun `ChatMessage serialization with tool calls`() {
        val toolCall = ToolCall(
            id = "call_123",
            type = "function",
            function = FunctionCall(name = "get_weather", arguments = "{\"city\":\"Beijing\"}")
        )
        val message = ChatMessage(role = "assistant", toolCalls = listOf(toolCall))

        val serialized = json.encodeToString(ChatMessage.serializer(), message)
        assertTrue(serialized.contains("tool_calls"))
        assertTrue(serialized.contains("get_weather"))

        val deserialized = json.decodeFromString(ChatMessage.serializer(), serialized)
        assertEquals(1, deserialized.toolCalls?.size)
        assertEquals("get_weather", deserialized.toolCalls?.first()?.function?.name)
    }

    @Test
    fun `ChatRequest serialization`() {
        val request = ChatRequest(
            messages = listOf(ChatMessage(role = "user", content = "test")),
            tools = listOf(
                Tool(
                    type = "function",
                    function = ToolFunction(
                        name = "test_tool",
                        description = "A test tool",
                        parameters = buildJsonObject {
                            put("type", JsonPrimitive("object"))
                        }
                    )
                )
            ),
            stream = true,
            maxTokens = 1024
        )

        val serialized = json.encodeToString(ChatRequest.serializer(), request)
        assertTrue(serialized.contains("\"stream\":true"))
        assertTrue(serialized.contains("\"max_tokens\":1024"))
        assertTrue(serialized.contains("test_tool"))
    }

    @Test
    fun `ChatResponse deserialization`() {
        val responseJson = """
            {
                "id": "chatcmpl-123",
                "created": 1700000000,
                "model": "qwen3-1.7b-local",
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Hello!"
                        },
                        "finish_reason": "stop"
                    }
                ],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 5,
                    "total_tokens": 15
                }
            }
        """.trimIndent()

        val response = json.decodeFromString(ChatResponse.serializer(), responseJson)
        assertEquals("chatcmpl-123", response.id)
        assertEquals("qwen3-1.7b-local", response.model)
        assertEquals(1, response.choices.size)
        assertEquals("Hello!", response.choices[0].message?.content)
        assertEquals("stop", response.choices[0].finishReason)
        assertEquals(15, response.usage?.totalTokens)
    }

    @Test
    fun `StreamDelta deserialization`() {
        val deltaJson = """
            {
                "id": "chatcmpl-123",
                "created": 1700000000,
                "model": "qwen3-1.7b-local",
                "choices": [
                    {
                        "index": 0,
                        "delta": {
                            "content": "Hello"
                        },
                        "finish_reason": null
                    }
                ]
            }
        """.trimIndent()

        val delta = json.decodeFromString(StreamDelta.serializer(), deltaJson)
        assertEquals("Hello", delta.choices[0].delta.content)
        assertNull(delta.choices[0].finishReason)
    }

    @Test
    fun `ToolResult creation`() {
        val result = ToolResult(
            toolCallId = "call_123",
            name = "get_weather",
            content = "Sunny, 25°C",
            isError = false
        )

        assertEquals("call_123", result.toolCallId)
        assertEquals("get_weather", result.name)
        assertEquals("Sunny, 25°C", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun `ToolResult with error`() {
        val result = ToolResult(
            toolCallId = "call_456",
            name = "invalid_tool",
            content = "Tool not found",
            isError = true
        )

        assertTrue(result.isError)
    }

    @Test
    fun `AgentConfig defaults`() {
        val config = AgentConfig()

        assertEquals(28000, config.maxInputTokens)
        assertEquals(2048, config.maxNewTokens)
        assertEquals(60000, config.timeoutMs)
        assertEquals("本轮已完成，可切回本地", config.cloudCompletionMarker)
        assertEquals(2, config.maxToolRetries)
        assertTrue(config.enableCloudEscalation)
    }

    @Test
    fun `AgentConfig custom values`() {
        val config = AgentConfig(
            maxInputTokens = 16000,
            maxNewTokens = 1024,
            timeoutMs = 30000,
            maxToolRetries = 3,
            enableCloudEscalation = false
        )

        assertEquals(16000, config.maxInputTokens)
        assertEquals(1024, config.maxNewTokens)
        assertEquals(30000, config.timeoutMs)
        assertEquals(3, config.maxToolRetries)
        assertFalse(config.enableCloudEscalation)
    }

    @Test
    fun `EscalationTrigger enum values`() {
        assertEquals(5, EscalationTrigger.values().size)
        assertTrue(EscalationTrigger.values().contains(EscalationTrigger.IMAGE_INPUT))
        assertTrue(EscalationTrigger.values().contains(EscalationTrigger.COMPLEX_REASONING))
        assertTrue(EscalationTrigger.values().contains(EscalationTrigger.TOOL_FAILURE))
        assertTrue(EscalationTrigger.values().contains(EscalationTrigger.LOW_CONFIDENCE))
        assertTrue(EscalationTrigger.values().contains(EscalationTrigger.USER_REQUEST))
    }

    @Test
    fun `ExecutionMode enum values`() {
        assertEquals(2, ExecutionMode.values().size)
        assertTrue(ExecutionMode.values().contains(ExecutionMode.LOCAL))
        assertTrue(ExecutionMode.values().contains(ExecutionMode.CLOUD))
    }

    @Test
    fun `ParsedToolCall creation`() {
        val parsed = ParsedToolCall(
            name = "get_weather",
            arguments = mapOf("city" to "Shanghai", "unit" to "celsius"),
            id = "call_custom_123"
        )

        assertEquals("get_weather", parsed.name)
        assertEquals(2, parsed.arguments.size)
        assertEquals("Shanghai", parsed.arguments["city"])
        assertEquals("call_custom_123", parsed.id)
    }

    @Test
    fun `ParsedToolCall default id generation`() {
        val parsed = ParsedToolCall(
            name = "test",
            arguments = emptyMap()
        )

        assertTrue(parsed.id.startsWith("call_"))
    }
}
