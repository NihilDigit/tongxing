package com.nihildigit.safetycore.sensors

import kotlin.math.max

enum class ImuSampleType { GYRO, ACCEL }

data class ImuSample(
    val tsNanos: Long,
    val type: ImuSampleType,
    val x: Float,
    val y: Float,
    val z: Float
)

data class ImuPoseDelta(
    val yawRad: Float,
    val pitchRad: Float,
    val rollRad: Float,
    val tsNanos: Long,
    val ageNs: Long
)

/**
 * Thread-safe ring buffer for IMU samples.
 * Supports concurrent add() from sensor callbacks and integrateGyro() from inference thread.
 */
class ImuBuffer(
    private val maxSamples: Int = 1024
) {
    private val lock = Any()
    private val samples = ArrayDeque<ImuSample>(maxSamples)

    fun add(sample: ImuSample) {
        synchronized(lock) {
            samples.addLast(sample)
            while (samples.size > maxSamples) {
                samples.removeFirst()
            }
        }
    }

    fun pruneOlderThan(cutoffNs: Long) {
        synchronized(lock) {
            while (samples.isNotEmpty() && samples.first().tsNanos < cutoffNs) {
                samples.removeFirst()
            }
        }
    }

    fun integrateGyro(
        startNs: Long,
        endNs: Long,
        alignmentToleranceNs: Long,
        maxAgeNs: Long,
        maxWindowNs: Long,
        accelAlpha: Float,
        accelMinG: Float,
        accelMaxG: Float
    ): ImuPoseDelta? {
        if (startNs <= 0L || endNs <= startNs) return null

        val windowStart = max(startNs, endNs - maxWindowNs)

        // Take snapshot under lock to avoid concurrent modification
        val gyroSamples: List<ImuSample>
        val accelSamples: List<ImuSample>
        synchronized(lock) {
            gyroSamples = samples.filter { it.type == ImuSampleType.GYRO }
            accelSamples = samples.filter { it.type == ImuSampleType.ACCEL }
        }
        if (gyroSamples.isEmpty()) return null

        // Find samples within window for alignment check
        val windowSamples = gyroSamples.filter { it.tsNanos in windowStart..endNs }
        if (windowSamples.isEmpty()) return null

        val lastSample = windowSamples.last()
        val ageNs = endNs - lastSample.tsNanos
        if (ageNs > maxAgeNs) return null
        if (kotlin.math.abs(lastSample.tsNanos - endNs) > alignmentToleranceNs) return null

        val firstIdx = gyroSamples.indexOfFirst { it.tsNanos >= windowStart }
        val startIdx = if (firstIdx >= 0) firstIdx else gyroSamples.size
        val prevSample = if (startIdx > 0) gyroSamples[startIdx - 1] else gyroSamples.first()

        var lastTs = max(windowStart, prevSample.tsNanos)
        var lastOmega = prevSample

        var yaw = 0f
        var pitch = 0f
        var roll = 0f

        for (i in startIdx until gyroSamples.size) {
            val sample = gyroSamples[i]
            if (sample.tsNanos > endNs) break
            val dt = (sample.tsNanos - lastTs) / 1_000_000_000f
            if (dt > 0f) {
                yaw += lastOmega.z * dt
                pitch += lastOmega.x * dt
                roll += lastOmega.y * dt
            }
            lastOmega = sample
            lastTs = sample.tsNanos
        }

        val dtEnd = (endNs - lastTs) / 1_000_000_000f
        if (dtEnd > 0f) {
            yaw += lastOmega.z * dtEnd
            pitch += lastOmega.x * dtEnd
            roll += lastOmega.y * dtEnd
        }

        val accelCorrection = computeAccelCorrection(
            accelSamples = accelSamples,
            windowStart = windowStart,
            endNs = endNs,
            accelAlpha = accelAlpha,
            accelMinG = accelMinG,
            accelMaxG = accelMaxG
        )

        val correctedPitch = if (accelCorrection != null) {
            accelAlpha * pitch + (1f - accelAlpha) * accelCorrection.first
        } else {
            pitch
        }

        val correctedRoll = if (accelCorrection != null) {
            accelAlpha * roll + (1f - accelAlpha) * accelCorrection.second
        } else {
            roll
        }

        return ImuPoseDelta(
            yawRad = yaw,
            pitchRad = correctedPitch,
            rollRad = correctedRoll,
            tsNanos = endNs,
            ageNs = ageNs
        )
    }

    private fun computeAccelCorrection(
        accelSamples: List<ImuSample>,
        windowStart: Long,
        endNs: Long,
        accelAlpha: Float,
        accelMinG: Float,
        accelMaxG: Float
    ): Pair<Float, Float>? {
        if (accelSamples.isEmpty()) return null
        if (accelAlpha >= 1f) return null

        val windowAccel = accelSamples.filter { it.tsNanos in windowStart..endNs }
        if (windowAccel.isEmpty()) return null

        var ax = 0f
        var ay = 0f
        var az = 0f
        for (s in windowAccel) {
            ax += s.x
            ay += s.y
            az += s.z
        }
        val n = windowAccel.size.toFloat()
        ax /= n
        ay /= n
        az /= n

        val mag = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        if (mag < accelMinG || mag > accelMaxG) return null

        // Gravity-aligned pitch/roll (device coordinates)
        val pitch = kotlin.math.atan2(-ax, kotlin.math.sqrt(ay * ay + az * az))
        val roll = kotlin.math.atan2(ay, az)
        return pitch to roll
    }
}
