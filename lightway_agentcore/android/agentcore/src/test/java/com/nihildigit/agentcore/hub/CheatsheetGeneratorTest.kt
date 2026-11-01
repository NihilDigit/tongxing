package com.nihildigit.agentcore.hub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatsheetGeneratorTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun generateWebCheatsheet_withoutCloudKey_usesFallbackAndIncludesSources() = runBlockingTest {
        val generator = CheatsheetGenerator(
            config = HubConfig(cloudCheatsheetApiKey = null),
            clockMs = { 1_000L }
        )

        val rawJson = """
            [
              {"title":"A","snippet":"aaa","url":"https://www.example.com/a"},
              {"title":"B","snippet":"bbb","url":"https://news.ycombinator.com/item?id=1"},
              {"title":"C","snippet":"ccc","url":"https://example.org/c"},
              {"title":"D","snippet":"ddd","url":"https://example.net/d"}
            ]
        """.trimIndent()

        val out = generator.generateWebCheatsheet(query = "测试问题", rawJson = rawJson)
        val obj = json.parseToJsonElement(out).jsonObject

        assertEquals("cheatsheet", obj["type"]?.toString()?.trim('"'))
        assertEquals("web", obj["domain"]?.toString()?.trim('"'))
        assertEquals(true, obj["fallback"]?.toString()?.toBoolean())
        assertTrue(obj["cheatsheet"]!!.toString().contains("云端小抄未启用"))

        val sources = obj["sources"]!!.jsonArray
        assertTrue(sources.size in 1..3)
        sources.forEach { src ->
            val srcObj = src.jsonObject
            assertNotNull(srcObj["id"])
            assertNotNull(srcObj["title"])
            assertNotNull(srcObj["domain"])
        }
    }

    private fun runBlockingTest(block: suspend () -> Unit) {
        kotlinx.coroutines.test.runTest { block() }
    }
}
