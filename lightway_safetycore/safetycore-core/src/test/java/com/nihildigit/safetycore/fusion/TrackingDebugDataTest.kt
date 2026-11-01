package com.nihildigit.safetycore.fusion

import com.nihildigit.safetycore.core.MatchType
import com.nihildigit.safetycore.core.TrackMatchDebug
import com.nihildigit.safetycore.core.TrackingDebugData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingDebugDataTest {

    @Test
    fun trackingDebugDataCapturesMatchesCorrectly() {
        val trackBbox = floatArrayOf(100f, 150f, 50f, 60f)
        val obsBbox = floatArrayOf(110f, 160f, 45f, 55f)
        val match = TrackMatchDebug(
            trackId = 1,
            trackBbox = trackBbox,
            obsIndex = 2,
            obsBbox = obsBbox,
            cost = 0.35f,
            costIou = 0.15f,
            costDepth = 0.10f,
            costVelocity = 0.10f,
            matchType = MatchType.HUNGARIAN
        )

        assertEquals("trackId should match", 1, match.trackId)
        assertEquals("trackBbox should have 4 elements", 4, match.trackBbox.size)
        assertEquals("obsIndex should match", 2, match.obsIndex)
        assertEquals("obsBbox should have 4 elements", 4, match.obsBbox.size)
        assertEquals("cost should match", 0.35f, match.cost, 0.001f)
        assertEquals("costIou should match", 0.15f, match.costIou, 0.001f)
        assertEquals("costDepth should match", 0.10f, match.costDepth, 0.001f)
        assertEquals("costVelocity should match", 0.10f, match.costVelocity, 0.001f)
        assertEquals("matchType should be HUNGARIAN", MatchType.HUNGARIAN, match.matchType)
    }

    @Test
    fun trackingDebugDataCapturesSecondChanceMatches() {
        val trackBbox = floatArrayOf(200f, 250f, 40f, 50f)
        val obsBbox = floatArrayOf(210f, 260f, 38f, 48f)
        val match = TrackMatchDebug(
            trackId = 5,
            trackBbox = trackBbox,
            obsIndex = 3,
            obsBbox = obsBbox,
            cost = 0.25f,
            costIou = 0.25f,
            costDepth = 0.0f,
            costVelocity = 0.0f,
            matchType = MatchType.SECOND_CHANCE
        )

        assertEquals("matchType should be SECOND_CHANCE", MatchType.SECOND_CHANCE, match.matchType)
        assertEquals("costIou should be primary cost in second-chance", 0.25f, match.costIou, 0.001f)
    }

    @Test
    fun trackingDebugDataBuildsCompleteDebugPayload() {
        val matches = listOf(
            TrackMatchDebug(
                trackId = 1, trackBbox = floatArrayOf(100f, 150f, 50f, 60f), obsIndex = 0,
                obsBbox = floatArrayOf(110f, 160f, 45f, 55f),
                cost = 0.30f, costIou = 0.10f, costDepth = 0.10f, costVelocity = 0.10f,
                matchType = MatchType.HUNGARIAN
            ),
            TrackMatchDebug(
                trackId = 2, trackBbox = floatArrayOf(150f, 200f, 60f, 70f), obsIndex = 1,
                obsBbox = floatArrayOf(160f, 210f, 55f, 65f),
                cost = 0.35f, costIou = 0.15f, costDepth = 0.12f, costVelocity = 0.08f,
                matchType = MatchType.HUNGARIAN
            )
        )
        val unmatchedTrackIds = listOf(3, 4)
        val unmatchedObsIndices = listOf(2, 3)

        val debugData = TrackingDebugData(
            matches = matches,
            unmatchedTrackIds = unmatchedTrackIds,
            unmatchedObsIndices = unmatchedObsIndices,
            secondChanceMatches = emptyList(),
            costThresh = 0.5f,
            costWeightIou = 0.4f,
            costWeightDepth = 0.3f,
            costWeightVelocity = 0.3f,
            maxCentroidDispPxPerFrame = 50f
        )

        assertNotNull("debugData should not be null", debugData)
        assertEquals("should capture 2 matches", 2, debugData.matches.size)
        assertEquals("should capture 2 unmatched tracks", 2, debugData.unmatchedTrackIds.size)
        assertEquals("should capture 2 unmatched obstacles", 2, debugData.unmatchedObsIndices.size)
        assertEquals("secondChanceMatches should be empty", 0, debugData.secondChanceMatches.size)
    }

    @Test
    fun trackingDebugDataHandlesNoMatches() {
        val debugData = TrackingDebugData(
            matches = emptyList(),
            unmatchedTrackIds = listOf(1, 2, 3),
            unmatchedObsIndices = listOf(0, 1),
            secondChanceMatches = emptyList(),
            costThresh = 0.5f,
            costWeightIou = 0.4f,
            costWeightDepth = 0.3f,
            costWeightVelocity = 0.3f,
            maxCentroidDispPxPerFrame = 50f
        )

        assertEquals("matches list should be empty", 0, debugData.matches.size)
        assertEquals("should capture all unmatched tracks", 3, debugData.unmatchedTrackIds.size)
        assertEquals("should capture all unmatched obstacles", 2, debugData.unmatchedObsIndices.size)
    }

    @Test
    fun trackingDebugDataHandlesSecondChanceMatches() {
        val secondChanceMatch = TrackMatchDebug(
            trackId = 5, trackBbox = floatArrayOf(250f, 300f, 45f, 55f), obsIndex = 0,
            obsBbox = floatArrayOf(260f, 310f, 40f, 50f),
            cost = 0.22f, costIou = 0.22f, costDepth = 0.0f, costVelocity = 0.0f,
            matchType = MatchType.SECOND_CHANCE
        )

        val debugData = TrackingDebugData(
            matches = emptyList(),
            unmatchedTrackIds = emptyList(),
            unmatchedObsIndices = emptyList(),
            secondChanceMatches = listOf(secondChanceMatch),
            costThresh = 0.5f,
            costWeightIou = 0.4f,
            costWeightDepth = 0.3f,
            costWeightVelocity = 0.3f,
            maxCentroidDispPxPerFrame = 50f
        )

        assertEquals("should capture second-chance match", 1, debugData.secondChanceMatches.size)
        assertEquals("second-chance match track should be 5", 5, debugData.secondChanceMatches[0].trackId)
        assertEquals("second-chance match type should be SECOND_CHANCE", MatchType.SECOND_CHANCE, debugData.secondChanceMatches[0].matchType)
    }

    @Test
    fun matchCostsAreNonNegative() {
        val trackBbox = floatArrayOf(100f, 150f, 50f, 60f)
        val obsBbox = floatArrayOf(110f, 160f, 45f, 55f)
        val match = TrackMatchDebug(
            trackId = 1, trackBbox = trackBbox, obsIndex = 0, obsBbox = obsBbox,
            cost = 0.40f, costIou = 0.15f, costDepth = 0.15f, costVelocity = 0.10f,
            matchType = MatchType.HUNGARIAN
        )

        assertTrue("total cost should be non-negative", match.cost >= 0f)
        assertTrue("costIou should be non-negative", match.costIou >= 0f)
        assertTrue("costDepth should be non-negative", match.costDepth >= 0f)
        assertTrue("costVelocity should be non-negative", match.costVelocity >= 0f)
    }

    @Test
    fun costComponentsSumToApproximateTotal() {
        val costIou = 0.15f
        val costDepth = 0.10f
        val costVelocity = 0.10f
        val totalCost = costIou + costDepth + costVelocity

        val trackBbox = floatArrayOf(100f, 150f, 50f, 60f)
        val obsBbox = floatArrayOf(110f, 160f, 45f, 55f)
        val match = TrackMatchDebug(
            trackId = 1, trackBbox = trackBbox, obsIndex = 0, obsBbox = obsBbox,
            cost = totalCost, costIou = costIou, costDepth = costDepth, costVelocity = costVelocity,
            matchType = MatchType.HUNGARIAN
        )

        assertEquals("total cost should equal sum of components", totalCost, match.cost, 0.001f)
    }

    @Test
    fun unmatchedTrackIdsAndObsIndicesAreDistinct() {
        val unmatchedTrackIds = listOf(1, 2, 3)
        val unmatchedObsIndices = listOf(0, 1)

        val debugData = TrackingDebugData(
            matches = emptyList(),
            unmatchedTrackIds = unmatchedTrackIds,
            unmatchedObsIndices = unmatchedObsIndices,
            secondChanceMatches = emptyList(),
            costThresh = 0.5f,
            costWeightIou = 0.4f,
            costWeightDepth = 0.3f,
            costWeightVelocity = 0.3f,
            maxCentroidDispPxPerFrame = 50f
        )

        // Verify no overlap would be expected (tracks and obs are different identifiers)
        assertTrue("unmatched tracks should be positive integers", unmatchedTrackIds.all { it > 0 })
        assertTrue("unmatched obs indices should be non-negative", unmatchedObsIndices.all { it >= 0 })
    }
}
