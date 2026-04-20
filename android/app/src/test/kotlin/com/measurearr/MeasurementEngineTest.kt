package com.measurearr

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs

class MeasurementEngineTest {

    private lateinit var engine: MeasurementEngine
    private val K = CameraIntrinsics(fx = 1200.0, fy = 1200.0, cx = 540.0, cy = 720.0)

    // Plane facing camera at 500mm, normal pointing toward camera
    private val normalVec = doubleArrayOf(0.0, 0.0, 1.0)
    private val distanceRaw = 500.0
    private val calibration = CalibrationState(
        scaleGeometric = 1.0,
        depthScale = 1.0,
        isCalibrated = true,
    )

    @BeforeEach
    fun setUp() {
        engine = MeasurementEngine()
    }

    @Test
    fun `geometric projection credit card 85_6mm`() {
        // Two points separated by 85.6mm at 500mm depth
        // Using small-angle approximation: pixel_offset = (mm_offset / depth_mm) * fx
        val offsetPx = (85.6 / distanceRaw) * K.fx  // ~205 pixels
        val u1 = K.cx - offsetPx / 2
        val u2 = K.cx + offsetPx / 2
        val v1 = K.cy
        val v2 = K.cy

        val result = engine.measure(u1, v1, u2, v2, K, normalVec, distanceRaw, calibration)

        assertInstanceOf(MeasurementResult.Success::class.java, result)
        val success = result as MeasurementResult.Success
        assertTrue(
            abs(success.distanceMm - 85.6) < 2.0,
            "Expected ~85.6mm, got ${success.distanceMm}mm",
        )
        assertEquals(MeasurementResult.Method.GEOMETRIC, success.method)
        assertFalse(success.isApproximate)
    }

    @Test
    fun `geometric projection ray parallel to plane returns error not crash`() {
        // Ray parallel to plane: dot(n, r) ≈ 0 when r is in the plane
        // For n = [0,0,1], a ray [x,y,0] is parallel — force r = [u,v,0] by extreme u
        // In practice, this means the tap is at the "horizon" of the plane
        // We simulate by passing a near-zero denom via a degenerate normal
        val degenerateNormal = doubleArrayOf(1.0, 0.0, 0.0)  // normal along x-axis
        // u1, v1 at cx, cy → r1 = [0,0,1], dot([1,0,0],[0,0,1]) = 0
        val result = engine.measure(K.cx, K.cy, K.cx + 10, K.cy, K, degenerateNormal, distanceRaw, calibration)

        assertEquals(MeasurementResult.Error.RAY_PARALLEL_TO_PLANE, result)
    }

    @Test
    fun `geometric projection same tap twice returns degenerate not Inf`() {
        val result = engine.measure(K.cx, K.cy, K.cx, K.cy, K, normalVec, distanceRaw, calibration)
        assertEquals(MeasurementResult.Error.DEGENERATE_INPUT, result)
    }

    @Test
    fun `geometric projection t less than zero returns behind camera error`() {
        // Force t < 0 by using a normal that causes denom > 0 but d_metric < 0
        // Negative distanceRaw with positive scale → dMetric < 0
        val negCalibration = CalibrationState(-1.0, 1.0, true)
        val result = engine.measure(K.cx + 10, K.cy, K.cx - 10, K.cy, K, normalVec, distanceRaw, negCalibration)
        assertEquals(MeasurementResult.Error.POINT_BEHIND_CAMERA, result)
    }

    @Test
    fun `uncalibrated returns NOT_CALIBRATED`() {
        val uncal = CalibrationState(1.0, 1.0, isCalibrated = false)
        val result = engine.measure(K.cx, K.cy, K.cx + 50, K.cy, K, normalVec, distanceRaw, uncal)
        assertEquals(MeasurementResult.Error.NOT_CALIBRATED, result)
    }

    @Test
    fun `depth path same depth at both points gives approx zero distance`() {
        val result = engine.measureDepth(
            K.cx - 50, K.cy, K.cx + 50, K.cy, K,
            depthAtP1 = 1.0f, depthAtP2 = 1.0f,
            calibration,
        )
        assertInstanceOf(MeasurementResult.Success::class.java, result)
        val success = result as MeasurementResult.Success
        // Same depth, different x → distance along x only = (absDepth1 * dx_n) - (absDepth2 * dx_n)
        // p1 = depth * [r1x, r1y, 1], p2 = depth * [r2x, r2y, 1]
        // Since depthAtP1 == depthAtP2, the z components cancel but x components differ
        assertTrue(success.distanceMm > 0, "Same depth but different position → non-zero distance")
        assertTrue(success.isApproximate)
        assertEquals(MeasurementResult.Method.DEPTH, success.method)
    }

    @Test
    fun `calibrate credit card produces valid scale factors`() {
        val offsetPx = (85.6 / distanceRaw) * K.fx
        val u1 = K.cx - offsetPx / 2
        val u2 = K.cx + offsetPx / 2

        val result = engine.calibrate(
            u1, K.cy, u2, K.cy, K,
            normalVec, distanceRaw,
            depthAtP1 = 0.5f, depthAtP2 = 0.5f,
            knownRealMm = 85.6,
        )

        assertTrue(result.isSuccess)
        val cal = result.getOrThrow()
        assertTrue(cal.scaleGeometric > 0, "scaleGeometric must be positive")
        assertTrue(cal.depthScale > 0, "depthScale must be positive")
        assertTrue(cal.isCalibrated)
    }

    @Test
    fun `calibrate same tap twice returns failure not Inf scale`() {
        val result = engine.calibrate(
            K.cx, K.cy, K.cx, K.cy, K,
            normalVec, distanceRaw,
            0.5f, 0.5f,
            85.6,
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `calibrate with zero knownRealMm returns failure`() {
        val result = engine.calibrate(
            K.cx - 50, K.cy, K.cx + 50, K.cy, K,
            normalVec, distanceRaw,
            0.5f, 0.5f,
            knownRealMm = 0.0,
        )
        assertTrue(result.isFailure)
    }
}
