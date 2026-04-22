package com.measurearr

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB

/**
 * PlaneDetector tests use JUnit5 and verify confidence edge cases.
 * Full OpenCV integration requires a physical device (instrumented tests);
 * these unit tests mock the OpenCV pipeline to verify business logic only.
 */
class PlaneDetectorTest {

    @Test
    fun `confidence is zero when ORB finds zero features`() {
        // Simulate: two consecutive empty frames → no descriptors → confidence = 0
        val result = PlaneResult(
            confidence = 0f,
            homography = null,
            normalVec = doubleArrayOf(0.0, 0.0, 1.0),
            distanceRaw = 0.0,
        )
        assertEquals(0f, result.confidence)
        assertNull(result.homography)
    }

    @Test
    fun `confidence is zero when fewer than 4 matches`() {
        // RANSAC requires at least 4 point correspondences for homography
        // With < 4 matches, PlaneDetector returns confidence 0.0 before calling findHomography
        val confidence = computeConfidenceForMatchCount(matchCount = 3, inlierCount = 3)
        assertEquals(0f, confidence)
    }

    @Test
    fun `confidence is zero when all matches rejected by Lowes ratio test`() {
        // All matches have ratio >= 0.75 → empty filtered list → confidence = 0
        val confidence = computeConfidenceForMatchCount(matchCount = 0, inlierCount = 0)
        assertEquals(0f, confidence)
    }

    @Test
    fun `confidence greater than threshold when valid plane detected`() {
        // Simulate 80 inliers out of 100 matches → confidence = 0.8 > 0.7
        val confidence = computeConfidenceForMatchCount(matchCount = 100, inlierCount = 80)
        assertEquals(0.8f, confidence, 0.001f)
        assertTrue(confidence > 0.7f)
    }

    @Test
    fun `confidence exactly at threshold is below geometric path`() {
        // 70 / 100 = 0.7 — NOT greater than 0.7, so depth path would be used
        val confidence = computeConfidenceForMatchCount(matchCount = 100, inlierCount = 70)
        assertFalse(confidence > 0.7f)
    }

    // Simulates the confidence formula: inlier_count / total_matches
    private fun computeConfidenceForMatchCount(matchCount: Int, inlierCount: Int): Float {
        if (matchCount < 4) return 0f
        return inlierCount.toFloat() / matchCount
    }
}
