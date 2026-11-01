package com.nihildigit.tongxing.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.nihildigit.safetycore.v2.types.FusionResultV2
import com.nihildigit.safetycore.v2.types.TimingsV2
import com.nihildigit.tongxing.SafetyDemoVideoProcessorV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class SafetyCoreBenchmark(private val context: Context) {

    companion object {
        private const val TAG = "LW_BENCH"
        private const val WARMUP_FRAMES = 60
        private const val OUTPUT_DIR = "/sdcard/lightway_benchmark"
    }

    data class StageStats(
        val avg: Double,
        val p50: Double,
        val p95: Double,
        val p99: Double,
        val std: Double
    )

    suspend fun run(filePaths: List<String>): JSONObject = withContext(Dispatchers.Default) {
        Log.i(TAG, "BENCHMARK_START mode=safetycore files=${filePaths.size}")

        val casesArray = JSONArray()
        val allTimings = mutableListOf<TimingsV2>()

        for (path in filePaths) {
            Log.i(TAG, "Processing video: $path")
            val frameTimings = mutableListOf<TimingsV2>()
            var frameCount = 0

            val processor = SafetyDemoVideoProcessorV2(context)
            if (!processor.init()) {
                Log.e(TAG, "Failed to init SafetyCore processor, skipping: $path")
                continue
            }
            processor.use {
                it.runVideo(path) { _: Bitmap, result: FusionResultV2?, _: Float? ->
                    frameCount++
                    if (frameCount > WARMUP_FRAMES && result != null) {
                        frameTimings.add(result.timings)
                    }
                }
            }

            allTimings.addAll(frameTimings)

            val stats = computeStats(frameTimings)
            val totalMs = frameTimings.map { it.totalMs.toDouble() }
            val fps = if (totalMs.isNotEmpty()) 1000.0 / totalMs.average() else 0.0

            val caseObj = JSONObject().apply {
                put("file", path.substringAfterLast("/Documents/"))
                put("frameCount", frameCount)
                put("warmupFrames", WARMUP_FRAMES)
                put("measuredFrames", frameTimings.size)
                put("stats", stats)
                put("fps", String.format("%.1f", fps).toDouble())
            }
            casesArray.put(caseObj)
            Log.i(TAG, "Video done: $path frames=$frameCount measured=${frameTimings.size}")
        }

        val aggregate = computeStats(allTimings)
        val aggTotalMs = allTimings.map { it.totalMs.toDouble() }
        val aggFps = if (aggTotalMs.isNotEmpty()) 1000.0 / aggTotalMs.average() else 0.0
        aggregate.put("fps", String.format("%.1f", aggFps).toDouble())

        val result = JSONObject().apply {
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            put("gitCommit", getGitCommit())
            put("device", getDeviceInfo())
            put("cases", casesArray)
            put("aggregate", aggregate)
        }

        val outFile = File(OUTPUT_DIR, "safety_benchmark.json")
        outFile.parentFile?.mkdirs()
        outFile.writeText(result.toString(2))
        Log.i(TAG, "BENCHMARK_DONE mode=safetycore output=${outFile.absolutePath}")
        result
    }

    private fun computeStats(timings: List<TimingsV2>): JSONObject {
        val stages = mapOf(
            "detection" to timings.map { it.detectInferMs.toDouble() },
            "depth" to timings.map { it.depthInferMs.toDouble() },
            "seg" to timings.map { it.segInferMs.toDouble() },
            "feature" to timings.map { it.featureInferMs.toDouble() },
            "fusion" to timings.map { it.fusionMs.toDouble() },
            "total" to timings.map { it.totalMs.toDouble() }
        )
        val obj = JSONObject()
        for ((name, values) in stages) {
            obj.put(name, computeStageStats(values))
        }
        return obj
    }

    private fun computeStageStats(values: List<Double>): JSONObject {
        if (values.isEmpty()) return JSONObject()
        val sorted = values.sorted()
        val avg = values.average()
        val p50 = percentile(sorted, 50.0)
        val p95 = percentile(sorted, 95.0)
        val p99 = percentile(sorted, 99.0)
        val variance = values.map { (it - avg) * (it - avg) }.average()
        val std = sqrt(variance)
        return JSONObject().apply {
            put("avg", round2(avg))
            put("p50", round2(p50))
            put("p95", round2(p95))
            put("p99", round2(p99))
            put("std", round2(std))
        }
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = (p / 100.0) * (sorted.size - 1)
        val lower = index.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.size - 1)
        val frac = index - lower
        return sorted[lower] + frac * (sorted[upper] - sorted[lower])
    }

    private fun round2(v: Double) = String.format("%.2f", v).toDouble()

    private fun getGitCommit(): String = try {
        context.assets.open("git_commit.txt").bufferedReader().readLine() ?: "unknown"
    } catch (_: Exception) { "unknown" }

    private fun getDeviceInfo() = JSONObject().apply {
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("sdk", Build.VERSION.SDK_INT)
        put("brand", Build.BRAND)
    }
}