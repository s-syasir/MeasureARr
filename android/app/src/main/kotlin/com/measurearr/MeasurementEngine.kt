package com.measurearr

import kotlin.math.abs
import kotlin.math.sqrt

sealed class MeasurementResult {
    data class Success(
        val distanceMm: Double,
        val method: Method,
        val isApproximate: Boolean,
    ) : MeasurementResult()

    sealed class Error : MeasurementResult() {
        val name: String get() = this::class.simpleName ?: "UNKNOWN"
        object RAY_PARALLEL_TO_PLANE : Error()
        object POINT_BEHIND_CAMERA : Error()
        object DEGENERATE_INPUT : Error()
    }

    enum class Method { DEPTH }
}

data class CameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
)

class MeasurementEngine {

    // Metric depth path — depth values from ONNX model are already in metres.
    fun measureMetricDepth(
        u1: Double, v1: Double,
        u2: Double, v2: Double,
        K: CameraIntrinsics,
        depthAtP1: Float,   // metres
        depthAtP2: Float,   // metres
    ): MeasurementResult {
        if (isDegenerate(u1, v1, u2, v2)) return MeasurementResult.Error.DEGENERATE_INPUT
        val z1 = depthAtP1.toDouble().coerceAtLeast(0.05)   // floor at 5 cm
        val z2 = depthAtP2.toDouble().coerceAtLeast(0.05)

        val p1 = doubleArrayOf(z1 * (u1 - K.cx) / K.fx, z1 * (v1 - K.cy) / K.fy, z1)
        val p2 = doubleArrayOf(z2 * (u2 - K.cx) / K.fx, z2 * (v2 - K.cy) / K.fy, z2)

        return MeasurementResult.Success(
            distanceMm = norm3(p2[0] - p1[0], p2[1] - p1[1], p2[2] - p1[2]) * 1000.0,
            method = MeasurementResult.Method.DEPTH,
            isApproximate = false,
        )
    }

    private fun isDegenerate(u1: Double, v1: Double, u2: Double, v2: Double): Boolean {
        if (u1.isNaN() || v1.isNaN() || u2.isNaN() || v2.isNaN()) return true
        if (u1.isInfinite() || v1.isInfinite() || u2.isInfinite() || v2.isInfinite()) return true
        return u1 == u2 && v1 == v2
    }

    private fun norm3(dx: Double, dy: Double, dz: Double): Double = sqrt(dx * dx + dy * dy + dz * dz)
}
