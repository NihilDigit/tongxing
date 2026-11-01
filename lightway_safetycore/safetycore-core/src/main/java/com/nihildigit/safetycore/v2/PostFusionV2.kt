package com.nihildigit.safetycore.v2

import com.nihildigit.safetycore.v2.types.*

/**
 * Post-fusion module that wraps native V2 operations.
 */
class PostFusionV2(private val config: ConfigV2) {

    private var initialized = false
    private val dangerHistory = mutableMapOf<Int, ArrayDeque<DangerLevelV2>>()

    fun init(): Boolean {
        if (!NativeV2Ops.isAvailable()) return false

        initialized = NativeV2Ops.init(
            distanceCritical = config.distanceCritical,
            distanceHigh = config.distanceHigh,
            distanceMedium = config.distanceMedium,
            ttcCritical = config.ttcCritical,
            ttcMin = config.ttcMinSeconds,
            ttcMax = config.ttcMaxSeconds,
            lambdaMotion = config.lambdaMotion,
            lambdaAppearance = config.lambdaAppearance,
            mahalanobisThreshold = config.mahalanobisThreshold,
            cosineThreshold = config.cosineThreshold,
            maxAge = config.maxAge,
            nInit = config.nInit,
            maxSizeChange = config.maxSizeChange,
            arLogThreshold = config.arLogThreshold,
            fps = config.fps,
            maxApproachSpeed = config.maxApproachSpeed,
            minBoxAreaPx = config.minBoxAreaPx,
            maxBoxAreaRatio = config.maxBoxAreaRatio,
            centerRegionRatio = config.centerRegionRatio,
            cameraHeight = config.cameraHeightDefault,
            pitchDeg = config.fixedPitchDeg
        )
        return initialized
    }

    fun reset() {
        if (initialized) {
            NativeV2Ops.reset()
        }
        dangerHistory.clear()
    }

    /**
     * Update scale from segmentation and depth.
     */
    fun updateScale(
        segClasses: ByteArray,
        depthRel: FloatArray,
        width: Int,
        height: Int
    ): ScaleStateV2? {
        if (!initialized) return null

        val result = NativeV2Ops.updateScale(segClasses, depthRel, width, height)
            ?: return null

        return ScaleStateV2(
            scaleFactor = result[0],
            stable = result[1] > 0.5f,
            validFrames = result[2].toInt()
        )
    }

    /**
     * Extract obstacles from detections.
     */
    fun extractFromDetections(
        boxes: FloatArray,
        scores: FloatArray,
        classIds: IntArray,
        depthRel: FloatArray,
        width: Int,
        height: Int
    ): List<ObstacleV2> {
        if (!initialized) return emptyList()

        val data = NativeV2Ops.extractObstaclesFromDetections(
            boxes, scores, classIds, depthRel, width, height
        ) ?: return emptyList()

        return parseObstacles(data)
    }

    /**
     * Extract obstacles from segmentation.
     */
    fun extractFromSegmentation(
        segClasses: ByteArray,
        depthRel: FloatArray,
        width: Int,
        height: Int
    ): List<ObstacleV2> {
        if (!initialized) return emptyList()

        // Minimum connected-component area scales from the 1280x720 baseline (config.minSegmentationArea).
        val scaledMinArea = ((config.minSegmentationArea.toFloat() * width.toFloat() * height.toFloat()) / (1280f * 720f))
            .toInt()
            .coerceAtLeast(1)
        val data = NativeV2Ops.extractObstaclesFromSegmentation(
            segClasses, depthRel, width, height, scaledMinArea
        ) ?: return emptyList()

        return parseObstacles(data)
    }

    /**
     * Update tracker with obstacles and features.
     */
    fun updateTracker(
        obstacles: List<ObstacleV2>,
        features: List<FloatArray>
    ): List<TrackedObstacleV2> {
        if (!initialized || obstacles.isEmpty()) return emptyList()

        val obstacleData = serializeObstacles(obstacles)
        val featureData = FloatArray(features.size * 1280)
        for (i in features.indices) {
            System.arraycopy(features[i], 0, featureData, i * 1280, 1280)
        }

        val trackData = NativeV2Ops.updateTracker(obstacleData, featureData)
            ?: return emptyList()

        return parseTracks(trackData)
    }

    /**
     * Assess danger for tracks.
     */
    fun assessDanger(
        tracks: List<TrackedObstacleV2>,
        scaleFactor: Float,
        frameWidth: Int,
        frameHeight: Int
    ): List<ThreatResultV2> {
        if (!initialized || tracks.isEmpty()) return emptyList()

        val trackData = serializeTracks(tracks)
        val threatData = NativeV2Ops.assessDanger(trackData, scaleFactor, config.fps, frameWidth, frameHeight)
            ?: return emptyList()

        return stabilizeThreatLevels(parseThreats(threatData))
    }

    /**
     * Fused native path: tracker update + danger assessment in one JNI call.
     */
    fun updateTrackerAndAssessDanger(
        obstacles: List<ObstacleV2>,
        features: List<FloatArray>,
        scaleFactor: Float,
        frameWidth: Int,
        frameHeight: Int
    ): Pair<List<TrackedObstacleV2>, List<ThreatResultV2>> {
        if (!initialized || obstacles.isEmpty()) return emptyList<TrackedObstacleV2>() to emptyList()

        val obstacleData = serializeObstacles(obstacles)
        val featureData = FloatArray(features.size * 1280)
        for (i in features.indices) {
            System.arraycopy(features[i], 0, featureData, i * 1280, 1280)
        }

        val fused = NativeV2Ops.updateTrackerAndAssessDanger(
            obstacleData,
            featureData,
            scaleFactor,
            config.fps,
            frameWidth,
            frameHeight
        ) ?: return emptyList<TrackedObstacleV2>() to emptyList()

        if (fused.size < 2) return emptyList<TrackedObstacleV2>() to emptyList()
        val trackCount = fused[0].toInt().coerceAtLeast(0)
        val threatCount = fused[1].toInt().coerceAtLeast(0)
        val trackStride = 16
        val threatStride = 5
        val tracksStart = 2
        val tracksEnd = tracksStart + trackCount * trackStride
        val threatsEnd = tracksEnd + threatCount * threatStride
        if (tracksEnd > fused.size || threatsEnd > fused.size) {
            return emptyList<TrackedObstacleV2>() to emptyList()
        }

        val trackData = fused.copyOfRange(tracksStart, tracksEnd)
        val threatData = fused.copyOfRange(tracksEnd, threatsEnd)
        val tracks = parseTracks(trackData)
        val threats = stabilizeThreatLevels(parseThreats(threatData))
        return tracks to threats
    }

    private fun stabilizeThreatLevels(threats: List<ThreatResultV2>): List<ThreatResultV2> {
        if (threats.isEmpty()) return threats
        val confirmFrames = 3
        return threats.map { threat ->
            val history = dangerHistory.getOrPut(threat.trackId) { ArrayDeque() }
            history.addLast(threat.level)
            while (history.size > confirmFrames) history.removeFirst()

            if (history.size < confirmFrames) {
                // Require one extra frame before surfacing HIGH/CRITICAL.
                if (history.size >= 2) {
                    val prev = history.elementAt(history.size - 2)
                    if (threat.level == DangerLevelV2.CRITICAL && prev != DangerLevelV2.CRITICAL) {
                        return@map threat.copy(level = DangerLevelV2.HIGH)
                    }
                    if (threat.level == DangerLevelV2.HIGH &&
                        prev != DangerLevelV2.HIGH &&
                        prev != DangerLevelV2.CRITICAL) {
                        return@map threat.copy(level = DangerLevelV2.MEDIUM)
                    }
                }
                threat
            } else {
                val counts = mutableMapOf<DangerLevelV2, Int>()
                history.forEach { level -> counts[level] = (counts[level] ?: 0) + 1 }
                val currentCount = counts[threat.level] ?: 0
                val stabilized = if (currentCount >= 2) {
                    threat
                } else {
                    val stable = counts.maxByOrNull { it.value }?.key ?: threat.level
                    threat.copy(level = stable)
                }
                val prev = history.elementAt(history.size - 2)
                if (stabilized.level == DangerLevelV2.CRITICAL && prev != DangerLevelV2.CRITICAL) {
                    return@map stabilized.copy(level = DangerLevelV2.HIGH)
                }
                if (stabilized.level == DangerLevelV2.HIGH &&
                    prev != DangerLevelV2.HIGH &&
                    prev != DangerLevelV2.CRITICAL) {
                    return@map stabilized.copy(level = DangerLevelV2.MEDIUM)
                }
                stabilized
            }
        }
    }

    private fun parseObstacles(data: FloatArray): List<ObstacleV2> {
        val stride = 9
        val count = data.size / stride
        return (0 until count).map { i ->
            ObstacleV2(
                bbox = floatArrayOf(
                    data[i * stride + 0],
                    data[i * stride + 1],
                    data[i * stride + 2],
                    data[i * stride + 3]
                ),
                classId = data[i * stride + 4].toInt(),
                confidence = data[i * stride + 5],
                depthMedian = data[i * stride + 6],
                depthStd = data[i * stride + 7],
                source = if (data[i * stride + 8] < 0.5f) ObstacleSource.DETECTION else ObstacleSource.SEGMENTATION
            )
        }
    }

    private fun serializeObstacles(obstacles: List<ObstacleV2>): FloatArray {
        val stride = 9
        val data = FloatArray(obstacles.size * stride)
        for (i in obstacles.indices) {
            val obs = obstacles[i]
            data[i * stride + 0] = obs.bbox[0]
            data[i * stride + 1] = obs.bbox[1]
            data[i * stride + 2] = obs.bbox[2]
            data[i * stride + 3] = obs.bbox[3]
            data[i * stride + 4] = obs.classId.toFloat()
            data[i * stride + 5] = obs.confidence
            data[i * stride + 6] = obs.depthMedian
            data[i * stride + 7] = obs.depthStd
            data[i * stride + 8] = if (obs.source == ObstacleSource.DETECTION) 0f else 1f
        }
        return data
    }

    private fun parseTracks(data: FloatArray): List<TrackedObstacleV2> {
        val stride = 16
        val count = data.size / stride
        return (0 until count).map { i ->
            TrackedObstacleV2(
                trackId = data[i * stride + 0].toInt(),
                bbox = floatArrayOf(
                    data[i * stride + 1],
                    data[i * stride + 2],
                    data[i * stride + 3],
                    data[i * stride + 4]
                ),
                classId = data[i * stride + 5].toInt(),
                classSource = data[i * stride + 6].toInt(),
                confidence = data[i * stride + 7],
                depthMeters = data[i * stride + 8],
                velocityX = data[i * stride + 9],
                velocityY = data[i * stride + 10],
                approachSpeed = data[i * stride + 11],
                timeSinceUpdate = data[i * stride + 12].toInt(),
                hits = data[i * stride + 13].toInt(),
                age = data[i * stride + 14].toInt(),
                confirmed = data[i * stride + 15] > 0.5f
            )
        }
    }

    private fun serializeTracks(tracks: List<TrackedObstacleV2>): FloatArray {
        val stride = 16
        val data = FloatArray(tracks.size * stride)
        for (i in tracks.indices) {
            val t = tracks[i]
            data[i * stride + 0] = t.trackId.toFloat()
            data[i * stride + 1] = t.bbox[0]
            data[i * stride + 2] = t.bbox[1]
            data[i * stride + 3] = t.bbox[2]
            data[i * stride + 4] = t.bbox[3]
            data[i * stride + 5] = t.classId.toFloat()
            data[i * stride + 6] = t.classSource.toFloat()
            data[i * stride + 7] = t.confidence
            data[i * stride + 8] = t.depthMeters
            data[i * stride + 9] = t.velocityX
            data[i * stride + 10] = t.velocityY
            data[i * stride + 11] = t.approachSpeed
            data[i * stride + 12] = t.timeSinceUpdate.toFloat()
            data[i * stride + 13] = t.hits.toFloat()
            data[i * stride + 14] = t.age.toFloat()
            data[i * stride + 15] = if (t.confirmed) 1f else 0f
        }
        return data
    }

    private fun parseThreats(data: FloatArray): List<ThreatResultV2> {
        val stride = 5
        val count = data.size / stride
        return (0 until count).map { i ->
            ThreatResultV2(
                trackId = data[i * stride + 0].toInt(),
                level = DangerLevelV2.entries[
                    data[i * stride + 1].toInt().coerceIn(0, DangerLevelV2.entries.lastIndex)
                ],
                distanceMeters = data[i * stride + 2],
                ttcSeconds = data[i * stride + 3],
                score = data[i * stride + 4]
            )
        }
    }
}
