package com.nihildigit.tongxing.benchmark

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Headless Activity for adb-triggered benchmarks.
 *
 * Usage:
 *   adb shell am start -n com.nihildigit.tongxing/.benchmark.BenchmarkActivity \
 *     --es mode safetycore \
 *     --es files "/sdcard/.../video1.mp4,/sdcard/.../video2.mp4"
 *
 *   adb shell am start -n com.nihildigit.tongxing/.benchmark.BenchmarkActivity \
 *     --es mode agentcore
 */
class BenchmarkActivity : Activity() {

    companion object {
        private const val TAG = "LW_BENCH"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra("mode") ?: "safetycore"
        val files = intent.getStringExtra("files")?.split(",")?.map { it.trim() } ?: emptyList()

        Log.i(TAG, "BenchmarkActivity started mode=$mode files=${files.size}")

        scope.launch {
            try {
                when (mode) {
                    "safetycore" -> {
                        if (files.isEmpty()) {
                            Log.e(TAG, "BENCHMARK_ERROR: no files specified for safetycore mode")
                            return@launch
                        }
                        SafetyCoreBenchmark(this@BenchmarkActivity).run(files)
                    }
                    "agentcore" -> {
                        AgentCoreBenchmark().run()
                    }
                    else -> {
                        Log.e(TAG, "BENCHMARK_ERROR: unknown mode=$mode")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "BENCHMARK_ERROR: ${e.message}", e)
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
