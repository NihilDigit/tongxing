package com.nihildigit.tongxing.export

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Headless Activity for adb-triggered annotated video export.
 *
 * Usage:
 *   adb shell am start -n com.nihildigit.tongxing/.export.VideoExportActivity \
 *     --es input "/sdcard/Movies/ped02.mp4" \
 *     --es output "/sdcard/Movies/ped02_annotated.mp4" \
 *     --ei inference_fps 15
 */
class VideoExportActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val inputPath = intent.getStringExtra("input")
        val outputPath = intent.getStringExtra("output")
        val inferenceFps = intent.getIntExtra("inference_fps", 15)

        if (inputPath.isNullOrBlank() || outputPath.isNullOrBlank()) {
            Log.e(TAG, "EXPORT_ERROR: missing input or output path")
            finish()
            return
        }

        Log.i(TAG, "VideoExportActivity started input=$inputPath output=$outputPath fps=$inferenceFps")

        scope.launch {
            val exporter = VideoExporter(this@VideoExportActivity)
            try {
                val initOk = withContext(Dispatchers.Default) { exporter.init() }
                if (!initOk) {
                    Log.e(TAG, "EXPORT_ERROR: failed to init V2 processor")
                    return@launch
                }

                withContext(Dispatchers.Default) {
                    exporter.export(inputPath, outputPath, inferenceFps) { current, total ->
                        Log.i(TAG, "EXPORT_PROGRESS frame=$current/$total")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "EXPORT_ERROR: ${e.message}", e)
            } finally {
                exporter.close()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "LW_EXPORT"
    }
}
