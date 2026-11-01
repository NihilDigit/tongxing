package com.nihildigit.safetycore.util

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * OpenCV CPU-only image operations for Stage 2 (Depth/Seg extraction).
 * Provides morphology and connected components labeling per implementation directives.
 *
 * All operations enforce CPU-only execution (OpenCL disabled globally in init).
 */
object ImageOpsOpenCv {
    /**
     * Set to true when OpenCV has been initialized successfully.
     * Stage 2 callers must check this to avoid invoking JNI when OpenCV is unavailable.
     */
    @Volatile
    var enabled: Boolean = true

    /**
     * Represents a connected component from CCL.
     */
    data class Component(
        val label: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val area: Int
    )

    data class CclResult(
        val components: List<Component>,
        val labels: IntArray,
        val width: Int,
        val height: Int
    )

    /**
     * Morphological opening: erosion followed by dilation.
     * @param srcBinary binary ByteArray (0 or 1) in row-major order
     * @param w image width
     * @param h image height
     * @param radius kernel radius (kernel size = 2*radius + 1)
     * @return binary ByteArray (0 or 1) after opening
     */
    fun open(srcBinary: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        val src = binaryToMat(srcBinary, w, h)
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size((2 * radius + 1).toDouble(), (2 * radius + 1).toDouble())
        )
        val dst = Mat()
        Imgproc.morphologyEx(src, dst, Imgproc.MORPH_OPEN, kernel)
        val result = matToBinary(dst, w, h)
        src.release()
        dst.release()
        kernel.release()
        return result
    }

    /**
     * Morphological closing: dilation followed by erosion.
     * @param srcBinary binary ByteArray (0 or 1) in row-major order
     * @param w image width
     * @param h image height
     * @param radius kernel radius
     * @return binary ByteArray (0 or 1) after closing
     */
    fun close(srcBinary: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        val src = binaryToMat(srcBinary, w, h)
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size((2 * radius + 1).toDouble(), (2 * radius + 1).toDouble())
        )
        val dst = Mat()
        Imgproc.morphologyEx(src, dst, Imgproc.MORPH_CLOSE, kernel)
        val result = matToBinary(dst, w, h)
        src.release()
        dst.release()
        kernel.release()
        return result
    }

    /**
     * Morphological dilation.
     * @param srcBinary binary ByteArray (0 or 1) in row-major order
     * @param w image width
     * @param h image height
     * @param radius kernel radius
     * @return binary ByteArray (0 or 1) after dilation
     */
    fun dilate(srcBinary: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        val src = binaryToMat(srcBinary, w, h)
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size((2 * radius + 1).toDouble(), (2 * radius + 1).toDouble())
        )
        val dst = Mat()
        Imgproc.dilate(src, dst, kernel)
        val result = matToBinary(dst, w, h)
        src.release()
        dst.release()
        kernel.release()
        return result
    }

    /**
     * 8-neighborhood connected component labeling.
     * @param srcBinary binary ByteArray (0 or 1) in row-major order
     * @param w image width
     * @param h image height
     * @return list of components (excludes background label 0)
     */
    fun connectedComponents8(srcBinary: ByteArray, w: Int, h: Int): List<Component> {
        return connectedComponents8WithLabels(srcBinary, w, h).components
    }

    /**
     * 8-neighborhood connected component labeling with label map output.
     * @param srcBinary binary ByteArray (0 or 1) in row-major order
     * @param w image width
     * @param h image height
     * @return CclResult with components + label map (size w*h)
     */
    fun connectedComponents8WithLabels(srcBinary: ByteArray, w: Int, h: Int): CclResult {
        val src = binaryToMat(srcBinary, w, h)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()

        // 8-neighborhood connectivity
        val numLabels = Imgproc.connectedComponentsWithStats(src, labels, stats, centroids, 8, CvType.CV_32S)

        val components = mutableListOf<Component>()

        // Iterate over labels 1..(numLabels-1); label 0 is background
        for (label in 1 until numLabels) {
            val left = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
            val top = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
            val width = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val height = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()

            components.add(Component(label, left, top, width, height, area))
        }

        val labelData = IntArray(w * h)
        labels.get(0, 0, labelData)

        src.release()
        labels.release()
        stats.release()
        centroids.release()

        return CclResult(components = components, labels = labelData, width = w, height = h)
    }

    /**
     * Convert binary ByteArray (0/1) to OpenCV Mat (CV_8UC1) with values 0/255.
     */
    private fun binaryToMat(binary: ByteArray, w: Int, h: Int): Mat {
        val mat = Mat(h, w, CvType.CV_8UC1)
        val data = ByteArray(w * h)
        for (i in binary.indices) {
            data[i] = if (binary[i] == 0.toByte()) 0 else 255.toByte()
        }
        mat.put(0, 0, data)
        return mat
    }

    /**
     * Convert OpenCV Mat (CV_8UC1) back to binary ByteArray (0/1).
     */
    private fun matToBinary(mat: Mat, w: Int, h: Int): ByteArray {
        val data = ByteArray(w * h)
        mat.get(0, 0, data)
        val binary = ByteArray(w * h)
        for (i in data.indices) {
            binary[i] = if (data[i].toInt() and 0xFF > 127) 1 else 0
        }
        return binary
    }
}
