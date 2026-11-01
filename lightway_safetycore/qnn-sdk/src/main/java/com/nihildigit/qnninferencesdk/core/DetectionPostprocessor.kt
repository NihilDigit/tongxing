package com.nihildigit.qnninferencesdk.core

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

object DetectionPostprocessor {
    data class DetectRaw(
        val boxes: FloatArray,   // [N*4] in model input coordinate space
        val scores: FloatArray,  // [N]
        val classIdx: IntArray   // [N]
    )

    /**
     * Read YOLOX-like outputs from interpreter.
     * Expected indices:
     *  - boxes:  [1, N, 4] (FLOAT32 or UINT8 quantized)
     *  - scores: [1, N]    (FLOAT32 or UINT8 quantized)
     *  - class:  [1, N]    (INT32 or UINT8)
     * Uses per-tensor quantization parameters when dtype is quantized.
     */
    fun readRaw(
        interpreter: Interpreter,
        boxesIndex: Int,
        scoresIndex: Int,
        classIndex: Int
    ): DetectRaw {
        val tBoxes = interpreter.getOutputTensor(boxesIndex)
        val tScores = interpreter.getOutputTensor(scoresIndex)
        val tCls = interpreter.getOutputTensor(classIndex)

        val shapeB = tBoxes.shape() // [1, N, 4]
        require(shapeB.size == 3 && shapeB[0] == 1 && shapeB[2] == 4) { "Unexpected boxes shape: ${shapeB.contentToString()}" }
        val n = shapeB[1]

        val boxes = FloatArray(n * 4)
        val scores = FloatArray(n)
        val classIdx = IntArray(n)

        // Boxes
        when (tBoxes.dataType()) {
            DataType.FLOAT32 -> {
                val fb = tBoxes.asReadOnlyBuffer().asFloatBuffer()
                fb.get(boxes)
            }
            DataType.UINT8, DataType.INT8 -> {
                val q = tBoxes.quantizationParams()
                val scale = q.scale
                val zero = q.zeroPoint
                val bb = tBoxes.asReadOnlyBuffer()
                val bytes = ByteArray(n * 4)
                bb.get(bytes)
                var i = 0
                while (i < bytes.size) {
                    val qv0 = if (tBoxes.dataType() == DataType.UINT8) (bytes[i].toInt() and 0xFF) else bytes[i].toInt()
                    val qv1 = if (tBoxes.dataType() == DataType.UINT8) (bytes[i+1].toInt() and 0xFF) else bytes[i+1].toInt()
                    val qv2 = if (tBoxes.dataType() == DataType.UINT8) (bytes[i+2].toInt() and 0xFF) else bytes[i+2].toInt()
                    val qv3 = if (tBoxes.dataType() == DataType.UINT8) (bytes[i+3].toInt() and 0xFF) else bytes[i+3].toInt()
                    boxes[i] = scale * (qv0 - zero)
                    boxes[i+1] = scale * (qv1 - zero)
                    boxes[i+2] = scale * (qv2 - zero)
                    boxes[i+3] = scale * (qv3 - zero)
                    i += 4
                }
            }
            else -> error("Unsupported boxes dtype: ${tBoxes.dataType()}")
        }

        // Scores
        when (tScores.dataType()) {
            DataType.FLOAT32 -> {
                val fb = tScores.asReadOnlyBuffer().asFloatBuffer()
                fb.get(scores)
            }
            DataType.UINT8, DataType.INT8 -> {
                val q = tScores.quantizationParams()
                val scale = q.scale
                val zero = q.zeroPoint
                val bb = tScores.asReadOnlyBuffer()
                val bytes = ByteArray(n)
                bb.get(bytes)
                var i = 0
                while (i < n) {
                    val qv = if (tScores.dataType() == DataType.UINT8) (bytes[i].toInt() and 0xFF) else bytes[i].toInt()
                    scores[i] = scale * (qv - zero)
                    i++
                }
            }
            else -> error("Unsupported scores dtype: ${tScores.dataType()}")
        }

        // Class idx
        when (tCls.dataType()) {
            DataType.INT32 -> {
                val ib = tCls.asReadOnlyBuffer().asIntBuffer()
                ib.get(classIdx)
            }
            DataType.UINT8 -> {
                val bb = tCls.asReadOnlyBuffer()
                val bytes = ByteArray(n)
                bb.get(bytes)
                var i = 0
                while (i < n) { classIdx[i] = bytes[i].toInt() and 0xFF; i++ }
            }
            else -> error("Unsupported class_idx dtype: ${tCls.dataType()}")
        }

        return DetectRaw(boxes, scores, classIdx)
    }
}
