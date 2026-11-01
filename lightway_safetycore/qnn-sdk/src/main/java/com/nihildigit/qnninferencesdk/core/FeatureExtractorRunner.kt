package com.nihildigit.qnninferencesdk.core

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * MobileNetV2 feature extractor model runner.
 * Input: 224x224 RGB UINT8
 * Output: 1280-dimensional L2-normalized feature vector
 */
class FeatureExtractorRunner(
    private val context: Context,
    private val assetPath: String
) : AutoCloseable {
    private val result: TFLiteHelper.InterpreterResult
    private val interp: Interpreter
    private var lastInferNs: Long = 0L
    private var lastPostNs: Long = 0L

    // Output quantization parameters
    private val outputScale: Float
    private val outputZeroPoint: Int

    init {
        val pair = TFLiteHelper.loadModelFromAssets(context, assetPath)
        val mapped = pair.first
        val token = pair.second
        result = TFLiteHelper.createInterpreterWithDelegates(context, mapped, token)
        interp = result.interpreter
        require(interp.inputTensorCount == 1 && interp.outputTensorCount >= 1)

        // Get output quantization parameters
        val outputTensor = interp.getOutputTensor(0)
        val params = outputTensor.quantizationParams()
        outputScale = params.scale
        outputZeroPoint = params.zeroPoint
    }

    /**
     * Run inference and return L2-normalized feature vector.
     */
    fun infer(input: ByteBuffer): FloatArray {
        input.order(ByteOrder.nativeOrder())
        val outputs = mutableMapOf<Int, Any>()
        val t0 = System.nanoTime()
        interp.runForMultipleInputsOutputs(arrayOf(input), outputs)
        val t1 = System.nanoTime()
        val rawFeatures = readFeatures(interp, 0, outputScale, outputZeroPoint)
        val normalized = l2Normalize(rawFeatures)
        val t2 = System.nanoTime()
        lastInferNs = interp.lastNativeInferenceDurationNanoseconds ?: (t1 - t0)
        lastPostNs = (t2 - t1)
        return normalized
    }

    /**
     * Batched API entrypoint.
     *
     * NOTE: QNN delegate pays a very large cost on `resizeInput + allocateTensors`
     * when toggling batch dimension at runtime. Keep batch=1 graph hot and run
     * sequentially for stable latency.
     */
    fun inferBatch(inputs: List<ByteBuffer>): List<FloatArray> {
        if (inputs.isEmpty()) return emptyList()
        if (inputs.size == 1) return listOf(infer(inputs.first()))

        val out = ArrayList<FloatArray>(inputs.size)
        var inferNs = 0L
        var postNs = 0L
        inputs.forEach { input ->
            val f = infer(input)
            inferNs += lastInferNs
            postNs += lastPostNs
            out.add(f)
        }
        lastInferNs = inferNs
        lastPostNs = postNs
        return out
    }

    fun inputShape(): IntArray = interp.getInputTensor(0).shape()
    fun inputDataType() = interp.getInputTensor(0).dataType()
    fun outputShape(): IntArray = interp.getOutputTensor(0).shape()

    fun backendLabel(): String {
        val keys = result.delegates.keys
        val hasQnn = keys.contains("QNN_NPU")
        val hasGpu = keys.contains("GPUv2")
        return when {
            hasQnn && hasGpu -> "QNN+GPU"
            hasQnn -> "QNN"
            hasGpu -> "GPU"
            else -> "CPU"
        }
    }

    override fun close() {
        try { interp.close() } finally {
            result.delegates.values.forEach { it.close() }
        }
    }

    fun lastInferenceMs(): Double = lastInferNs / 1_000_000.0
    fun lastPostprocessMs(): Double = lastPostNs / 1_000_000.0

    companion object {
        /**
         * Read raw feature vector from interpreter output with proper dequantization.
         */
        private fun readFeatures(
            interpreter: Interpreter,
            outputIndex: Int,
            scale: Float,
            zeroPoint: Int
        ): FloatArray {
            val t = interpreter.getOutputTensor(outputIndex)
            val shape = t.shape()
            val dtype = t.dataType()

            // Expected shape: [1, N] or [1, 1, 1, N] (flattened feature vector)
            val featureSize = shape.reduce { acc, i -> acc * i }

            return when (dtype) {
                DataType.FLOAT32 -> {
                    val fb = t.asReadOnlyBuffer().asFloatBuffer()
                    val arr = FloatArray(featureSize)
                    fb.get(arr)
                    arr
                }
                DataType.INT8 -> {
                    val buf = t.asReadOnlyBuffer()
                    val arr = FloatArray(featureSize)
                    for (i in 0 until featureSize) {
                        val quantized = buf.get().toInt()  // signed int8
                        arr[i] = (quantized - zeroPoint) * scale
                    }
                    arr
                }
                DataType.UINT8 -> {
                    val buf = t.asReadOnlyBuffer()
                    val arr = FloatArray(featureSize)
                    for (i in 0 until featureSize) {
                        val quantized = buf.get().toInt() and 0xFF  // unsigned uint8
                        arr[i] = (quantized - zeroPoint) * scale
                    }
                    arr
                }
                else -> error("Unsupported dtype: $dtype")
            }
        }

        /**
         * L2 normalize a feature vector.
         */
        private fun l2Normalize(features: FloatArray): FloatArray {
            var sumSq = 0f
            for (v in features) {
                sumSq += v * v
            }
            val norm = sqrt(sumSq).coerceAtLeast(1e-12f)
            val result = FloatArray(features.size)
            for (i in features.indices) {
                result[i] = features[i] / norm
            }
            return result
        }
    }
}
