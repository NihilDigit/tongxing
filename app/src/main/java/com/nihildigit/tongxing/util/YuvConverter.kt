package com.nihildigit.tongxing.util

import java.nio.ByteBuffer

object YuvConverter {
    init {
        System.loadLibrary("yuvbridge")
    }

    external fun convertToArgb(
        yuvData: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        sliceHeight: Int,
        colorFormat: Int,
        outArgb: ByteBuffer
    ): Boolean

    external fun convertToRgb24Scaled(
        yuvData: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        sliceHeight: Int,
        colorFormat: Int,
        dstWidth: Int,
        dstHeight: Int,
        outRgb: ByteBuffer
    ): Boolean

    external fun convertYuv420ToArgb(
        yPlane: ByteBuffer,
        yRowStride: Int,
        uPlane: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        vPlane: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        width: Int,
        height: Int,
        outArgb: ByteBuffer
    ): Boolean

    external fun convertYuv420ToRgb24Scaled(
        yPlane: ByteBuffer,
        yRowStride: Int,
        uPlane: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        vPlane: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        width: Int,
        height: Int,
        dstWidth: Int,
        dstHeight: Int,
        outRgb: ByteBuffer
    ): Boolean
}
