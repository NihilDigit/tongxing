package com.nihildigit.tongxing.benchmark

import android.os.Build
import android.util.Log
import com.nihildigit.agentcore.Router
import com.nihildigit.lightwayllama.llm.LlmEngine
import com.nihildigit.tongxing.overlay.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentCoreBenchmark {

    companion object {
        private const val TAG = "LW_BENCH"
        private const val OUTPUT_DIR = "/sdcard/lightway_benchmark"
    }

    data class TestCase(
        val label: String,
        val query: String,
        val followUp: String? = null,
        val expectedT1Tool: String,
        val expectedT2Tool: String? = null
    )

    enum class FailReason { TIMEOUT, WRONG_TOOL, NO_TOOL_CALL }

    private val json = Json { ignoreUnknownKeys = true }

    private val testCases = listOf(
        // 导航多轮 — 用通用 POI 确保多结果
        TestCase("导航→应询问", "导航去图书馆", null,
            "find_nearby", "text"),
        TestCase("导航→第一个", "导航去图书馆", "第一个",
            "find_nearby", "navigate_to"),
        TestCase("导航→第二个", "导航去饭店", "去第二个",
            "find_nearby", "navigate_to"),
        TestCase("导航→最近的", "导航去饭店", "就最近的吧",
            "find_nearby", "navigate_to"),
        // 打车多轮
        TestCase("打车→应询问", "打车去市政府", null,
            "find_nearby", "text"),
        TestCase("打车→第一个", "打车去市政府", "打车去第一个",
            "find_nearby", "call_taxi"),
        TestCase("打车→第二个", "打车去饭店", "打车去第二个",
            "find_nearby", "call_taxi"),
        TestCase("打车→最近的", "打车去图书馆", "打车去最近的",
            "find_nearby", "call_taxi"),
        // 单轮工具
        TestCase("天气", "今天天气怎么样", null, "weather_here"),
        TestCase("火车", "查一下北京到上海的火车", null, "search_train"),
        TestCase("航班", "北京飞广州的航班", null, "search_flight"),
        TestCase("看前面", "前面是什么", null, "describe_camera"),
        TestCase("读屏", "帮我读一下屏幕", null, "describe_screen"),
        TestCase("停止导航", "停止导航", null, "stop_navigation"),
        TestCase("打车状态", "我的车到哪了", null, "taxi_status"),
        TestCase("取消打车", "取消打车", null, "cancel_taxi"),
    )

    suspend fun run(): JSONObject = withContext(Dispatchers.Default) {
        Log.i(TAG, "BENCHMARK_START mode=agentcore cases=${testCases.size}")

        val router = OverlayService.getRouterForBenchmark()
            ?: throw IllegalStateException("Router not available — is OverlayService running?")

        OverlayService.setBenchmarkSuppressExternalActions(true)

        val casesArray = JSONArray()
        val failures = JSONArray()
        var passed = 0
        val e2eTimes = mutableListOf<Long>()
        val prefillSpeeds = mutableListOf<Double>()
        val decodeSpeeds = mutableListOf<Double>()

        try {
            for (tc in testCases) {
                Log.i(TAG, "Case: ${tc.label}")
                router.clearHistory()
                val caseObj = JSONObject().apply { put("label", tc.label) }

                // T1
                val t1Start = System.currentTimeMillis()
                val t1Result = router.process(tc.query)
                val t1Ms = System.currentTimeMillis() - t1Start
                val t1Gen = LlmEngine.getLastGenerateResult()

                val t1Tool = t1Result.toolsUsed.firstOrNull()
                val t1Pass = when {
                    tc.expectedT1Tool == "text" -> t1Result.toolsUsed.isEmpty()
                    else -> t1Tool == tc.expectedT1Tool
                }

                val t1Obj = JSONObject().apply {
                    put("query", tc.query)
                    put("expectedTool", tc.expectedT1Tool)
                    put("actualTool", t1Tool ?: "text")
                    put("pass", t1Pass)
                    put("e2eMs", t1Ms)
                    put("response", t1Result.response?.take(200))
                    t1Gen?.let {
                        put("prefillMs", it.prefillTimeUs / 1000)
                        put("decodeMs", it.decodeTimeUs / 1000)
                        put("promptTokens", it.promptTokens)
                        put("decodeTokens", it.generatedTokens)
                        put("prefillSpeed", round2(it.prefillSpeed))
                        put("decodeSpeed", round2(it.decodeSpeed))
                    }
                }
                caseObj.put("t1", t1Obj)
                e2eTimes.add(t1Ms)
                t1Gen?.let {
                    prefillSpeeds.add(it.prefillSpeed)
                    decodeSpeeds.add(it.decodeSpeed)
                }

                // T2 (if multi-turn)
                var casePass = t1Pass
                if (tc.followUp != null) {
                    val t2Start = System.currentTimeMillis()
                    val t2Result = router.process(tc.followUp)
                    val t2Ms = System.currentTimeMillis() - t2Start
                    val t2Gen = LlmEngine.getLastGenerateResult()

                    val t2Tool = t2Result.toolsUsed.lastOrNull()
                    val t2Pass: Boolean
                    var failReason: FailReason? = null

                    if (tc.expectedT2Tool == "text") {
                        t2Pass = t2Result.toolsUsed.isEmpty()
                    } else {
                        // 只验证工具名，LID 参数只记录不判断
                        t2Pass = t2Tool == tc.expectedT2Tool
                        if (!t2Pass) failReason = if (t2Tool == null) FailReason.NO_TOOL_CALL else FailReason.WRONG_TOOL
                    }

                    val lastDetail = t2Result.toolCallDetails.lastOrNull()
                    val actualArg = lastDetail?.let { extractLocationId(it.arguments) }
                    val t2Obj = JSONObject().apply {
                        put("query", tc.followUp)
                        put("expectedTool", tc.expectedT2Tool)
                        put("actualTool", t2Tool ?: "text")
                        put("actualArg", actualArg)
                        put("actualArgsFull", lastDetail?.arguments)
                        put("pass", t2Pass)
                        put("e2eMs", t2Ms)
                        put("response", t2Result.response?.take(200))
                        t2Gen?.let {
                            put("prefillMs", it.prefillTimeUs / 1000)
                            put("decodeMs", it.decodeTimeUs / 1000)
                            put("promptTokens", it.promptTokens)
                            put("decodeTokens", it.generatedTokens)
                            put("prefillSpeed", round2(it.prefillSpeed))
                            put("decodeSpeed", round2(it.decodeSpeed))
                        }
                    }
                    caseObj.put("t2", t2Obj)
                    e2eTimes.add(t2Ms)
                    t2Gen?.let {
                        prefillSpeeds.add(it.prefillSpeed)
                        decodeSpeeds.add(it.decodeSpeed)
                    }
                    casePass = t1Pass && t2Pass

                    if (!t2Pass && failReason != null) {
                        failures.put(JSONObject().apply {
                            put("label", tc.label)
                            put("reason", failReason.name)
                            put("expected", tc.expectedT2Tool)
                            put("actual", t2Tool ?: "text")
                        })
                    }
                } else if (!t1Pass) {
                    val reason = if (t1Tool == null) FailReason.NO_TOOL_CALL else FailReason.WRONG_TOOL
                    failures.put(JSONObject().apply {
                        put("label", tc.label)
                        put("reason", reason.name)
                        put("expected", tc.expectedT1Tool)
                        put("actual", t1Tool ?: "text")
                    })
                }

                if (casePass) passed++
                casesArray.put(caseObj)
                Log.i(TAG, "Case done: ${tc.label} pass=$casePass")
            }
        } finally {
            OverlayService.setBenchmarkSuppressExternalActions(false)
        }

        // Stem data
        val stemObj = JSONObject()
        OverlayService.getAgentCoreStatsForBenchmark()?.let { agentStats ->
            stemObj.put("warmupTimeMs", agentStats.stemWarmupTimeMs)
            stemObj.put("tokenCount", agentStats.stemTokenCount)
        }

        val result = JSONObject().apply {
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            put("device", getDeviceInfo())
            put("stem", stemObj)
            put("cases", casesArray)
            put("summary", JSONObject().apply {
                put("total", testCases.size)
                put("passed", passed)
                put("failed", testCases.size - passed)
                put("successRate", round2(passed.toDouble() / testCases.size * 100))
                put("avgE2eMs", if (e2eTimes.isNotEmpty()) e2eTimes.average().toLong() else 0)
                put("avgPrefillSpeed", if (prefillSpeeds.isNotEmpty()) round2(prefillSpeeds.average()) else 0.0)
                put("avgDecodeSpeed", if (decodeSpeeds.isNotEmpty()) round2(decodeSpeeds.average()) else 0.0)
                put("failures", failures)
            })
        }

        val outFile = File(OUTPUT_DIR, "agent_benchmark.json")
        outFile.parentFile?.mkdirs()
        outFile.writeText(result.toString(2))
        Log.i(TAG, "BENCHMARK_DONE mode=agentcore output=${outFile.absolutePath}")
        result
    }

    private fun extractLocationId(arguments: String?): String? {
        if (arguments == null) return null
        return try {
            val obj = json.parseToJsonElement(arguments).jsonObject
            obj["location_id"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }
    }

    private fun round2(v: Double) = String.format("%.2f", v).toDouble()

    private fun getDeviceInfo() = JSONObject().apply {
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("sdk", Build.VERSION.SDK_INT)
        put("brand", Build.BRAND)
    }
}
