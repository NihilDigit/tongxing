package com.nihildigit.tongxing.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * 帧副本缓冲器
 * 提供给云端视觉调用的帧副本，不阻塞 30fps 安全管线
 *
 * 符合 F-001 规范：
 * - 降采样/压缩
 * - 非阻塞
 */
class CameraTap {

    companion object {
        private const val TAG = "CameraTap"

        // 默认配置
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 480
        const val DEFAULT_QUALITY = 75 // JPEG 质量
    }

    /**
     * 帧副本数据
     */
    data class FrameSnapshot(
        val bitmap: Bitmap?,
        val jpeg: ByteArray?,
        val timestamp: Long,
        val width: Int,
        val height: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FrameSnapshot) return false
            return timestamp == other.timestamp &&
                   width == other.width &&
                   height == other.height
        }

        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            return result
        }
    }

    // 最新帧缓冲（原子引用，无锁）
    private val latestFrame = AtomicReference<FrameSnapshot?>(null)

    // 锁定帧（用户开始说话时的帧）
    private val lockedFrame = AtomicReference<FrameSnapshot?>(null)

    @Volatile private var lastUpdateAtMs: Long = 0L
    @Volatile private var totalFrames: Long = 0L

    // 配置
    private var targetWidth = DEFAULT_WIDTH
    private var targetHeight = DEFAULT_HEIGHT
    private var jpegQuality = DEFAULT_QUALITY

    /**
     * 配置帧副本参数
     */
    fun configure(width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT, quality: Int = DEFAULT_QUALITY) {
        targetWidth = width
        targetHeight = height
        jpegQuality = quality.coerceIn(1, 100)
        Log.d(TAG, "Configured: ${width}x${height}, quality=$quality")
    }

    /**
     * 更新最新帧
     * 由 SafetyCore 的 FrameProcessor 调用
     */
    fun updateFrame(bitmap: Bitmap, timestamp: Long) {
        try {
            // 降采样
            val scaled = if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }

            // 压缩为 JPEG
            val jpeg = compressToJpeg(scaled)

            val snapshot = FrameSnapshot(
                bitmap = scaled,
                jpeg = jpeg,
                timestamp = timestamp,
                width = targetWidth,
                height = targetHeight
            )

            latestFrame.set(snapshot)
            totalFrames++
            lastUpdateAtMs = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update frame", e)
        }
    }

    /**
     * 获取最新帧副本
     * 供云端视觉调用
     */
    fun getLatestFrame(): FrameSnapshot? {
        return latestFrame.get()
    }

    /**
     * 获取最新帧的 JPEG 数据
     */
    fun getLatestJpeg(): ByteArray? {
        return latestFrame.get()?.jpeg
    }

    /**
     * 获取最新帧的 Bitmap
     */
    fun getLatestBitmap(): Bitmap? {
        return latestFrame.get()?.bitmap
    }

    /**
     * 获取帧时间戳
     */
    fun getLatestTimestamp(): Long {
        return latestFrame.get()?.timestamp ?: 0L
    }

    fun getLastUpdateAtMs(): Long = lastUpdateAtMs

    fun getTotalFrames(): Long = totalFrames

    /**
     * 压缩为 JPEG
     */
    private fun compressToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
        return stream.toByteArray()
    }

    /**
     * 从 JPEG 解码回 Bitmap
     */
    fun decodeJpeg(jpeg: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode JPEG", e)
            null
        }
    }

    /**
     * 获取 Base64 编码的 JPEG（用于 API 上传）
     * 优先返回锁定帧，否则返回最新帧
     */
    fun getLatestBase64(): String? {
        val frame = lockedFrame.get() ?: latestFrame.get()
        return frame?.jpeg?.let {
            android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
        }
    }

    /**
     * 锁定当前帧（用户开始说话时调用）
     */
    fun lockFrame() {
        val current = latestFrame.get()
        lockedFrame.set(current)
        Log.d(TAG, "Frame locked at timestamp: ${current?.timestamp}")
    }

    /**
     * 释放锁定帧（处理完成后调用）
     */
    fun unlockFrame() {
        lockedFrame.set(null)
        Log.d(TAG, "Frame unlocked")
    }

    /**
     * 获取锁定帧的 Base64
     */
    fun getLockedBase64(): String? {
        return lockedFrame.get()?.jpeg?.let {
            android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
        }
    }

    /**
     * 清除缓冲
     */
    fun clear() {
        latestFrame.set(null)
        lockedFrame.set(null)
    }
}
