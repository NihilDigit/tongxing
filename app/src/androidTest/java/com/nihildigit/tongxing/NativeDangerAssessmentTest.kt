package com.nihildigit.tongxing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nihildigit.safetycore.v2.NativeV2Ops
import com.nihildigit.safetycore.util.NativeImageOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDangerAssessmentTest {
    @Test
    fun nativeMinMaxExcludesNonfiniteValues() {
        val output = FloatArray(3)
        assertTrue(NativeImageOps.minMaxFinite(
            floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 3f, 7f),
            output
        ))
        assertEquals(3f, output[0], 0f)
        assertEquals(7f, output[1], 0f)
        assertEquals(2f, output[2], 0f)
    }

    @Before
    fun initializeNativePipeline() {
        assertTrue("Packaged SafetyCore JNI library must load", NativeV2Ops.isAvailable())
        assertTrue(NativeV2Ops.init(
            distanceCritical = 2f, distanceHigh = 4f, distanceMedium = 6f,
            ttcCritical = 1f, ttcMin = 0.1f, ttcMax = 30f,
            lambdaMotion = 0.5f, lambdaAppearance = 0.5f,
            mahalanobisThreshold = 9.4877f, cosineThreshold = 0.3f,
            maxAge = 30, nInit = 5, maxSizeChange = 0.5f,
            arLogThreshold = 0.5f, fps = 15f, maxApproachSpeed = 15f,
            minBoxAreaPx = 100, maxBoxAreaRatio = 0.4f, centerRegionRatio = 0.2f,
            cameraHeight = 1.2f, pitchDeg = 35f
        ))
    }

    private fun assess(scale: Float, depth: Float, speed: Float): FloatArray {
        val track = floatArrayOf(
            1f, 300f, 200f, 40f, 80f, 0f, 0f, 0.9f,
            depth, 0f, 0f, speed, 0f, 5f, 5f, 1f
        )
        return requireNotNull(NativeV2Ops.assessDanger(track, scale, 15f, 640, 480))
            .also { assertEquals("JNI threat stride", 5, it.size) }
    }

    @Test
    fun invalidDepthAndScaleDoNotProduceWarningsThroughJni() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            for (result in listOf(assess(invalid, 1f, 1f), assess(1f, invalid, 1f))) {
                assertEquals(0f, result[1], 0f)
                assertTrue(result[2].isNaN())
                assertTrue(result[3].isInfinite())
                assertEquals(0f, result[4], 0f)
            }
        }
    }

    @Test
    fun invalidSpeedPreservesDistanceWarningAndValidApproachRemainsCritical() {
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val result = assess(1f, 1f, speed)
            assertEquals(3f, result[1], 0f)
            assertTrue(result[3].isInfinite())
        }
        assertEquals(4f, assess(1f, 1f, 2f)[1], 0f)
    }
}
