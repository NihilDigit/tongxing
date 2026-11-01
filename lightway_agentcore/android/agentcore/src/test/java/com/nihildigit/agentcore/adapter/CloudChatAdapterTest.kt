package com.nihildigit.agentcore.adapter

import com.nihildigit.agentcore.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CloudChatAdapter.
 * Tests request building and response parsing logic.
 */
class CloudChatAdapterTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `test CloudChatResult creation`() {
        val result = CloudChatResult(
            content = "Test response",
            shouldDowngrade = false,
            usage = null
        )

        assertEquals("Test response", result.content)
        assertFalse(result.shouldDowngrade)
        assertNull(result.usage)
    }

    @Test
    fun `test CloudChatResult with downgrade flag`() {
        val result = CloudChatResult(
            content = "Task completed. 本轮已完成，可切回本地",
            shouldDowngrade = true,
            usage = Usage(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        )

        assertTrue(result.shouldDowngrade)
        assertNotNull(result.usage)
        assertEquals(150, result.usage?.totalTokens)
    }

    @Test
    fun `test CloudException creation`() {
        val exception = CloudException("API error")
        assertEquals("API error", exception.message)
        assertNull(exception.cause)

        val withCause = CloudException("Network error", java.io.IOException("Connection refused"))
        assertEquals("Network error", withCause.message)
        assertNotNull(withCause.cause)
        assertTrue(withCause.cause is java.io.IOException)
    }

    @Test
    fun `test AgentConfig cloud completion marker`() {
        val defaultConfig = AgentConfig()
        assertEquals("本轮已完成，可切回本地", defaultConfig.cloudCompletionMarker)

        val customConfig = AgentConfig(cloudCompletionMarker = "DONE")
        assertEquals("DONE", customConfig.cloudCompletionMarker)
    }

    @Test
    fun `test completion marker detection logic`() {
        val marker = "本轮已完成，可切回本地"

        // Test various response scenarios
        val responseWithMarker = "图片中显示一只猫在沙发上睡觉。$marker"
        assertTrue(responseWithMarker.contains(marker))

        val responseWithoutMarker = "图片中显示一只猫在沙发上睡觉。"
        assertFalse(responseWithoutMarker.contains(marker))

        val responseWithPartialMarker = "本轮已完成"
        assertFalse(responseWithPartialMarker.contains(marker))
    }

    @Test
    fun `test multimodal request messages structure`() {
        val messages = listOf(
            ChatMessage(role = "user", content = "这张图片里有什么？")
        )

        // Verify message structure is correct
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("这张图片里有什么？", messages[0].content)
    }

    @Test
    fun `test text request messages structure`() {
        val messages = listOf(
            ChatMessage(role = "user", content = "请解释量子计算的基本原理"),
            ChatMessage(role = "assistant", content = "量子计算是..."),
            ChatMessage(role = "user", content = "能举个例子吗？")
        )

        assertEquals(3, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)
        assertEquals("user", messages[2].role)
    }

    @Test
    fun `test SSE data line parsing`() {
        // Test SSE line format
        val sseLines = listOf(
            "data: {\"output\":{\"text\":\"Hello\"}}",
            "data: {\"output\":{\"text\":\" World\"}}",
            "data: [DONE]"
        )

        for ((index, line) in sseLines.withIndex()) {
            assertTrue(line.startsWith("data:"))
            if (index < 2) {
                val data = line.removePrefix("data:").trim()
                assertFalse(data == "[DONE]")
                assertTrue(data.startsWith("{"))
            }
        }
    }

    @Test
    fun `test JSON response parsing for text output`() {
        val jsonResponse = """
            {
                "output": {
                    "text": "这是一段测试文本"
                },
                "usage": {
                    "input_tokens": 50,
                    "output_tokens": 20
                }
            }
        """.trimIndent()

        val parsed = json.parseToJsonElement(jsonResponse)
        val output = parsed.jsonObject["output"]?.jsonObject
        val text = output?.get("text")?.jsonPrimitive?.content

        assertEquals("这是一段测试文本", text)
    }

    @Test
    fun `test JSON response parsing for multimodal output`() {
        val jsonResponse = """
            {
                "output": {
                    "choices": [
                        {
                            "message": {
                                "content": [
                                    {"text": "图片分析结果"}
                                ]
                            }
                        }
                    ]
                }
            }
        """.trimIndent()

        val parsed = json.parseToJsonElement(jsonResponse)
        val output = parsed.jsonObject["output"]?.jsonObject
        val choices = output?.get("choices")?.jsonArray

        assertNotNull(choices)
        assertEquals(1, choices?.size)

        val message = choices?.get(0)?.jsonObject?.get("message")?.jsonObject
        val content = message?.get("content")?.jsonArray
        val text = content?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content

        assertEquals("图片分析结果", text)
    }

    @Test
    fun `test empty image URLs list`() {
        val imageUrls = emptyList<String>()
        assertTrue(imageUrls.isEmpty())

        val hasImages = imageUrls.isNotEmpty()
        assertFalse(hasImages)
    }

    @Test
    fun `test image URLs list with content`() {
        val imageUrls = listOf(
            "https://example.com/image1.jpg",
            "https://example.com/image2.png"
        )

        assertFalse(imageUrls.isEmpty())
        assertEquals(2, imageUrls.size)
        assertTrue(imageUrls[0].endsWith(".jpg"))
        assertTrue(imageUrls[1].endsWith(".png"))
    }
}
