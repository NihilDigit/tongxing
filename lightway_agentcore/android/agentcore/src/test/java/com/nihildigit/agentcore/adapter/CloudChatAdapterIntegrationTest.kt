package com.nihildigit.agentcore.adapter

import com.nihildigit.agentcore.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Integration tests for CloudChatAdapter with real DashScope SDK.
 *
 * These tests require a valid DASHSCOPE_API_KEY environment variable.
 * They are skipped if the API key is not available.
 */
class CloudChatAdapterIntegrationTest {

    private var apiKey: String? = null
    private lateinit var adapter: CloudChatAdapter

    @Before
    fun setUp() {
        apiKey = System.getenv("DASHSCOPE_API_KEY")
        if (apiKey != null) {
            adapter = CloudChatAdapter(
                apiKey = apiKey!!,
                config = AgentConfig(timeoutMs = 30000),
                model = "qwen-vl-plus"
            )
        }
    }

    @Test
    fun `test real DashScope text chat streaming`() = runBlocking {
        assumeTrue("DASHSCOPE_API_KEY not set, skipping integration test", apiKey != null)

        val messages = listOf(
            ChatMessage(role = "user", content = "你好，请用一句话回答：1+1等于多少？")
        )

        val responseBuilder = StringBuilder()
        val result = adapter.streamChat(
            messages = messages,
            imageUrls = emptyList(),
            onDelta = { delta ->
                responseBuilder.append(delta)
                println("Delta: $delta")
            }
        )

        assertNotNull(result)
        assertTrue(result.content.isNotEmpty())
        assertTrue(result.content.contains("2") || result.content.contains("二"))
        println("Full response: ${result.content}")
    }

    @Test
    fun `test real DashScope text chat non-streaming`() = runBlocking {
        assumeTrue("DASHSCOPE_API_KEY not set, skipping integration test", apiKey != null)

        val messages = listOf(
            ChatMessage(role = "user", content = "请用一个词回答：天空的颜色是？")
        )

        val result = adapter.call(
            messages = messages,
            imageUrls = emptyList()
        )

        assertNotNull(result)
        assertTrue(result.content.isNotEmpty())
        println("Response: ${result.content}")
        println("Usage: ${result.usage}")
    }

    @Test
    fun `test real DashScope multimodal chat with image`() = runBlocking {
        assumeTrue("DASHSCOPE_API_KEY not set, skipping integration test", apiKey != null)

        val messages = listOf(
            ChatMessage(role = "user", content = "这张图片里有什么？请简短描述。")
        )

        // Use a public test image URL
        val imageUrls = listOf(
            "https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg"
        )

        val responseBuilder = StringBuilder()
        val result = adapter.streamChat(
            messages = messages,
            imageUrls = imageUrls,
            onDelta = { delta ->
                responseBuilder.append(delta)
                println("Delta: $delta")
            }
        )

        assertNotNull(result)
        assertTrue(result.content.isNotEmpty())
        println("Full response: ${result.content}")
    }

    @Test
    fun `test completion marker detection`() = runBlocking {
        assumeTrue("DASHSCOPE_API_KEY not set, skipping integration test", apiKey != null)

        val messages = listOf(
            ChatMessage(
                role = "user",
                content = "请回复'测试完成'，然后添加这句话：本轮已完成，可切回本地"
            )
        )

        val result = adapter.streamChat(
            messages = messages,
            imageUrls = emptyList(),
            onDelta = { }
        )

        // The response should contain the marker and trigger downgrade
        if (result.content.contains("本轮已完成，可切回本地")) {
            assertTrue(result.shouldDowngrade)
        }
        println("Response: ${result.content}, shouldDowngrade: ${result.shouldDowngrade}")
    }

    @Test
    fun `test cancel streaming request`() = runBlocking {
        assumeTrue("DASHSCOPE_API_KEY not set, skipping integration test", apiKey != null)

        val messages = listOf(
            ChatMessage(role = "user", content = "请详细描述中国的历史，从远古时代开始。")
        )

        var chunkCount = 0

        try {
            adapter.streamChat(
                messages = messages,
                imageUrls = emptyList(),
                onDelta = { delta ->
                    chunkCount++
                    if (chunkCount >= 3) {
                        adapter.cancel()
                    }
                }
            )
        } catch (e: Exception) {
            // Cancellation may throw
        }

        // We should have received some chunks before cancellation
        assertTrue(chunkCount >= 1)
        println("Received $chunkCount chunks before cancellation")
    }

    @Test
    fun `test error handling for invalid API key`() = runBlocking {
        val invalidAdapter = CloudChatAdapter(
            apiKey = "invalid",
            config = AgentConfig(timeoutMs = 10000)
        )

        val messages = listOf(
            ChatMessage(role = "user", content = "Hello")
        )

        try {
            invalidAdapter.streamChat(
                messages = messages,
                imageUrls = emptyList(),
                onDelta = { }
            )
            fail("Should have thrown CloudException")
        } catch (e: CloudException) {
            assertTrue(e.message?.contains("API") == true || e.message?.contains("error") == true)
            println("Expected error: ${e.message}")
        }
    }

    @Test
    fun `test multi-turn conversation`() = runBlocking {
        assumeTrue("DASHSCOPE_API_KEY not set, skipping integration test", apiKey != null)

        val messages = listOf(
            ChatMessage(role = "user", content = "我叫小明"),
            ChatMessage(role = "assistant", content = "你好小明！"),
            ChatMessage(role = "user", content = "我叫什么名字？")
        )

        val result = adapter.call(
            messages = messages,
            imageUrls = emptyList()
        )

        assertNotNull(result)
        assertTrue(result.content.contains("小明") || result.content.contains("名字"))
        println("Response: ${result.content}")
    }
}
