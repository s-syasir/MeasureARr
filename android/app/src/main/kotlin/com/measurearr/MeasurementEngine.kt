package com.measurearr

import kotlin.math.abs
import kotlin.math.sqrt

sealed class MeasurementResult {
    data class Success(
        val distanceMm: Double,
        val method: Method,
        val isApproximate: Boolean,
    ) : MeasurementResult()

    enum class Error : MeasurementResult() {
        RAY_PARALLEL_TO_PLANE,  // tap near horizon of plane — show "Tap closer to center"
        POINT_BEHIND_CAMERA,    // t < 0 — shouldn't occur if n[2] > 0, guard anyway
        DEGENERATE_INPUT,       // same tap twice, or NaN/Inf in inputs
        NOT_CALIBRATED,         // no scale factor available — caller decides whether to proceed
    }

    enum class Method { GEOMETRIC, DEPTH }
}

data class CameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
)

data class CalibrationState(
    val scaleGeometric: Double,  // known_mm / distance_raw (geometric path)
    val depthScale: Double,      // d_metric / mean(depth[p1], depth[p2]) (depth path)
    val isCalibrated: Boolean,
)

class MeasurementEngine {

    // Geometric path
    fun measure(
        u1: Double, v1: Double,
        u2: Double, v2: Double,
        K: CameraIntrinsics,
        normalVec: DoubleArray,
        distanceRaw: Double,
        calibration: CalibrationState,
    ): MeasurementResult {
        if (!calibration.isCalibrated) return MeasurementResult.Error.NOT_CALIBRATED
        if (isDegenerate(u1, v1, u2, v2)) return MeasurementResult.Error.DEGENERATE_INPUT

        val dMetric = distanceRaw * calibration.scaleGeometric

        val r1 = doubleArrayOf((u1 - K.cx) / K.fx, (v1 - K.cy) / K.fy, 1.0)
        val r2 = doubleArrayOf((u2 - K.cx) / K.fx, (v2 - K.cy) / K.fy, 1.0)

        val denom1 = dot(normalVec, r1)
        val denom2 = dot(normalVec, r2)

        if (abs(denom1) < 1e-6 || abs(denom2) < 1e-6) {
            return MeasurementResult.Error.RAY_PARALLEL_TO_PLANE
        }

        val t1 = dMetric / denom1
        val t2 = dMetric / denom2

        if (t1 <= 0 || t2 <= 0) return MeasurementResult.Error.POINT_BEHIND_CAMERA

        val p1 = r1.map { it * t1 }.toDoubleArray()
        val p2 = r2.map { it * t2 }.toDoubleArray()

        return MeasurementResult.Success(
            distanceMm = norm3(p2[0] - p1[0], p2[1] - p1[1], p2[2] - p1[2]),
            method = MeasurementResult.Method.GEOMETRIC,
            isApproximate = false,
        )
    }

    // Depth (TFLite fallback) path
    fun measureDepth(
        u1: Double, v1: Double,
        u2: Double, v2: Double,
        K: CameraIntrinsics,
        depthAtP1: Float,
        depthAtP2: Float,
        calibration: CalibrationState,
    ): MeasurementResult {
        if (!calibration.isCalibrated) return MeasurementResult.Error.NOT_CALIBRATED
        if (isDegenerate(u1, v1, u2, v2)) return MeasurementResult.Error.DEGENERATE_INPUT

        val absDepth1 = calibration.depthScale * depthAtP1
        val absDepth2 = calibration.depthScale * depthAtP2

        val p1 = doubleArrayOf(
            absDepth1 * (u1 - K.cx) / K.fx,
            absDepth1 * (v1 - K.cy) / K.fy,
            absDepth1,
        )
        val p2 = doubleArrayOf(
            absDepth2 * (u2 - K.cx) / K.fx,
            absDepth2 * (v2 - K.cy) / K.fy,
            absDepth2,
        )

        return MeasurementResult.Success(
            distanceMm = norm3(p2[0] - p1[0], p2[1] - p1[1], p2[2] - p1[2]),
            method = MeasurementResult.Method.DEPTH,
            isApproximate = true,
        )
    }

    // Called during calibration with a known-size reference object.
    // Produces both scale factors simultaneously.
    fun calibrate(
        u1: Double, v1: Double,
        u2: Double, v2: Double,
        K: CameraIntrinsics,
        normalVec: DoubleArray,
        distanceRaw: Double,   // norm(t) from homography decomp, scale-ambiguous
        depthAtP1: Float,
        depthAtP2: Float,
        knownRealMm: Double,
    ): Result<CalibrationState> {
        if (knownRealMm <= 0) return Result.failure(IllegalArgumentException("knownRealMm must be > 0"))
        if (isDegenerate(u1, v1, u2, v2)) return Result.failure(IllegalArgumentException("Same tap twice — degenerate calibration"))
        if (distanceRaw <= 0) return Result.failure(IllegalArgumentException("distanceRaw must be > 0"))

        val dPlane = distanceRaw  // scene units (scale-ambiguous)

        val r1 = doubleArrayOf((u1 - K.cx) / K.fx, (v1 - K.cy) / K.fy, 1.0)
        val r2 = doubleArrayOf((u2 - K.cx) / K.fx, (v2 - K.cy) / K.fy, 1.0)

        val denom1 = dot(normalVec, r1)
        val denom2 = dot(normalVec, r2)
        if (abs(denom1) < 1e-6 || abs(denom2) < 1e-6) {
            return Result.failure(IllegalStateException("Ray parallel to plane during calibration — tap closer to center"))
        }

        val t1 = dPlane / denom1
        val t2 = dPlane / denom2
        val p1Raw = r1.map { it * t1 }.toDoubleArray()
        val p2Raw = r2.map { it * t2 }.toDoubleArray()
        val distanceRawMm = norm3(p2Raw[0] - p1Raw[0], p2Raw[1] - p1Raw[1], p2Raw[2] - p1Raw[2])

        if (distanceRawMm < 1e-9) {
            return Result.failure(IllegalStateException("Distance too small — tap two distinct points"))
        }

        val scaleGeometric = knownRealMm / distanceRawMm

        // Derive depth scale: convert homography plane distance to metric, divide by mean model depth
        val dMetric = dPlane * scaleGeometric
        val meanDepth = (depthAtP1 + depthAtP2) / 2.0
        val depthScale = if (meanDepth > 1e-9) dMetric / meanDepth else 1.0

        return Result.success(CalibrationState(
            scaleGeometric = scaleGeometric,
            depthScale = depthScale,
            isCalibrated = true,
        ))
    }

    private fun isDegenerate(u1: Double, v1: Double, u2: Double, v2: Double): Boolean {
        if (u1.isNaN() || v1.isNaN() || u2.isNaN() || v2.isNaN()) return true
        if (u1.isInfinite() || v1.isInfinite() || u2.isInfinite() || v2.isInfinite()) return true
        return u1 == u2 && v1 == v2
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun norm3(dx: Double, dy: Double, dz: Double): Double = sqrt(dx * dx + dy * dy + dz * dz)
}
