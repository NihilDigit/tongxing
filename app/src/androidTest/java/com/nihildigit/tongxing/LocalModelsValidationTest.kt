package com.nihildigit.tongxing

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import com.nihildigit.lightwayllama.llm.LlmEngine
import com.nihildigit.qnninferencesdk.sdk.BackendMode
import com.nihildigit.qnninferencesdk.sdk.QnnLiteRtSdk
import com.nihildigit.qnninferencesdk.sdk.SdkConfig
import com.nihildigit.safetycore.v2.ConfigV2
import com.nihildigit.safetycore.v2.SafetyCoreV2Engine
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
class LocalModelsValidationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireExplicitModelValidation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("validateModels") == "true")
    }

    @Test
    fun qnnVisionAndOpenClLanguageRunConcurrently() {
        val model = File(context.getExternalFilesDir("models"), "Qwen3-1.7B-Q4_0-pure.gguf")
        assertTrue("Prepare the pinned GGUF model before running validation", model.isFile)
        val config = ConfigV2()
        val sdk = QnnLiteRtSdk(context, SdkConfig(BackendMode.QNN_ONLY))
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        val executor = Executors.newSingleThreadExecutor()
        try {
            sdk.loadDepth(config.depthModelPath).loadDetect(config.detectModelPath)
                .loadFFNet(config.ffnetModelPath).loadFeatureExtractor(config.featureModelPath)
            val features = sdk.inferFeatures(bitmap)
            assertEquals(1280, features.features.size)
            assertTrue(features.features.all { it.isFinite() })
            assertEquals(1f, features.features.sumOf { (it * it).toDouble() }.toFloat(), 0.001f)
            val backends = listOf(sdk.inferDepth(bitmap).backend, sdk.inferDetect(bitmap).backend,
                sdk.inferFFNet(bitmap).backend, features.backend)
            assertTrue("Expected four QNN backends: $backends", backends.all { it.contains("QNN") })
            LlmEngine.load(context, LlmEngine.Config(model.absolutePath, nGpuLayers = 99))
            assertTrue(LlmEngine.isModelLoaded)
            assertTrue(LlmEngine.getCurrentGpuLayers() > 0)
            val generation = executor.submit<LlmEngine.GenerateResult> {
                LlmEngine.generate("""[{"role":"user","content":"请用一句话介绍自己。/no_think"}]""")
            }
            SafetyCoreV2Engine.createFrameProcessor(sdk, config).use { processor ->
                repeat(20) {
                    val frame = processor.processFrame(bitmap)
                    assertTrue(frame.timings.totalMs.isFinite())
                    assertTrue(frame.timings.totalMs > 0)
                }
            }
            val result = generation.get(120, TimeUnit.SECONDS)
            assertTrue("No generated tokens", result.generatedTokens > 0)
            assertFalse("Missing parsed response", result.parsedResponseJson.isNullOrBlank())
            Log.i("TongXingValidation", "Concurrent QNN/OpenCL passed: tokens=${result.generatedTokens}")
        } finally {
            LlmEngine.cancelGeneration()
            executor.shutdown()
            if (executor.awaitTermination(60, TimeUnit.SECONDS)) LlmEngine.unload()
            sdk.close()
            bitmap.recycle()
        }
    }

    @Test
    fun paraformerDecodesBundledSpeech() {
        val directory = "sherpa-onnx-paraformer-zh-2024-03-09"
        val recognizer = OfflineRecognizer(context.assets, OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = "$directory/model.int8.onnx"),
                tokens = "$directory/tokens.txt", numThreads = 2, provider = "cpu"
            )
        ))
        try {
            val wave = WaveReader.readWave(context.assets, "$directory/test_wavs/0.wav")
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(wave.samples, wave.sampleRate)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                assertTrue("Speech decoded to empty text", result.text.isNotBlank())
                Log.i("TongXingValidation", "Paraformer speech decoded: characters=${result.text.length}")
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }

    @Test
    fun closingProcessorDrainsConcurrentFrameWork() {
        val processor = SafetyCoreV2Engine.createFrameProcessor(context)
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        val framesReady = CountDownLatch(2)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val frames = executor.submit<Int> {
                var processed = 0
                while (processed < 100) {
                    try { processor.processFrame(bitmap) }
                    catch (closed: IllegalStateException) {
                        if (closed.message == "Frame processor is closed") break
                        throw closed
                    }
                    processed++
                    framesReady.countDown()
                }
                processed
            }
            assertTrue(framesReady.await(30, TimeUnit.SECONDS))
            processor.close()
            assertTrue(frames.get(30, TimeUnit.SECONDS) >= 2)
            processor.close()
            try { processor.processFrame(bitmap); fail("Closed processor accepted another frame") }
            catch (closed: IllegalStateException) { assertEquals("Frame processor is closed", closed.message) }
        } finally {
            processor.close()
            executor.shutdown()
            executor.awaitTermination(30, TimeUnit.SECONDS)
            bitmap.recycle()
        }
    }
}
