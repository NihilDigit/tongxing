package com.nihildigit.qnninferencesdk.core

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType

class SegmentationRunner(
    private val context: Context,
    private val assetPath: String
) : AutoCloseable {
    private val result: TFLiteHelper.InterpreterResult
    private val interp: Interpreter
    private var lastInferNs: Long = 0L
    private var lastPostNs: Long = 0L

    init {
        val pair = TFLiteHelper.loadModelFromAssets(context, assetPath)
        val mapped = pair.first
        val token = pair.second
        result = TFLiteHelper.createInterpreterWithDelegates(context, mapped, token)
        interp = result.interpreter
        // Basic validation
        require(interp.inputTensorCount == 1 && interp.outputTensorCount >= 1)
    }

    fun infer(input: ByteBuffer): SegmentationPostprocessor.SegResult {
        input.order(ByteOrder.nativeOrder())
        val outputs = mutableMapOf<Int, Any>()
        val t0 = System.nanoTime()
        interp.runForMultipleInputsOutputs(arrayOf(input), outputs)
        val t1 = System.nanoTime()
        val classes = SegmentationPostprocessor.argmax(interp, 0)
        val t2 = System.nanoTime()
        // Record timings
        lastInferNs = interp.lastNativeInferenceDurationNanoseconds
            ?: (t1 - t0) // fallback if API returns null on some versions
        lastPostNs = (t2 - t1)
        return classes
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
}

class DepthRunner(
    private val context: Context,
    private val assetPath: String
) : AutoCloseable {
    private val result: TFLiteHelper.InterpreterResult
    private val interp: Interpreter
    private var lastInferNs: Long = 0L
    private var lastPostNs: Long = 0L

    init {
        val pair = TFLiteHelper.loadModelFromAssets(context, assetPath)
        val mapped = pair.first
        val token = pair.second
        result = TFLiteHelper.createInterpreterWithDelegates(context, mapped, token)
        interp = result.interpreter
        require(interp.inputTensorCount == 1 && interp.outputTensorCount >= 1)
    }

    fun infer(input: ByteBuffer): FloatArray {
        input.order(ByteOrder.nativeOrder())
        val outputs = mutableMapOf<Int, Any>()
        val t0 = System.nanoTime()
        interp.runForMultipleInputsOutputs(arrayOf(input), outputs)
        val t1 = System.nanoTime()
        val out = DepthPostprocessor.readDepthAsFloat(interp, 0)
        val t2 = System.nanoTime()
        lastInferNs = interp.lastNativeInferenceDurationNanoseconds
            ?: (t1 - t0)
        lastPostNs = (t2 - t1)
        return out
    }

    fun inputShape(): IntArray = interp.getInputTensor(0).shape()
    fun inputDataType() = interp.getInputTensor(0).dataType()

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
}

class DetectionRunner(
    private val context: Context,
    private val assetPath: String
) : AutoCloseable {
    private val result: TFLiteHelper.InterpreterResult
    private val interp: Interpreter
    private var lastInferNs: Long = 0L
    private var lastPostNs: Long = 0L

    init {
        val pair = TFLiteHelper.loadModelFromAssets(context, assetPath)
        val mapped = pair.first
        val token = pair.second
        result = TFLiteHelper.createInterpreterWithDelegates(context, mapped, token)
        interp = result.interpreter
        // Expect 1 input and >=3 outputs (boxes, scores, class_idx)
        require(interp.inputTensorCount == 1)
        require(interp.outputTensorCount >= 3)
    }

    fun infer(input: ByteBuffer): DetectionPostprocessor.DetectRaw {
        input.order(ByteOrder.nativeOrder())
        val outputs = mutableMapOf<Int, Any>()
        val t0 = System.nanoTime()
        interp.runForMultipleInputsOutputs(arrayOf(input), outputs)
        val t1 = System.nanoTime()
        val out = DetectionPostprocessor.readRaw(interp, boxesIndex = 0, scoresIndex = 1, classIndex = 2)
        val t2 = System.nanoTime()
        lastInferNs = interp.lastNativeInferenceDurationNanoseconds ?: (t1 - t0)
        lastPostNs = (t2 - t1)
        return out
    }

    fun inputShape(): IntArray = interp.getInputTensor(0).shape()
    fun inputDataType() = interp.getInputTensor(0).dataType()

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
}
