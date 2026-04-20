package com.measurearr

import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableNativeMap
import org.opencv.core.Mat

private const val TAG = "MeasurementBridge"

class MeasurementBridgeModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "MeasurementBridge"

    @ReactMethod
    fun measure(params: ReadableMap, promise: Promise) {
        try {
            val u1 = params.getDouble("u1")
            val v1 = params.getDouble("v1")
            val u2 = params.getDouble("u2")
            val v2 = params.getDouble("v2")
            // scaleGeometric / depthScale still accepted for optional calibrated path
            val scaleGeometric = if (params.hasKey("scaleGeometric")) params.getDouble("scaleGeometric") else null
            val depthScale      = if (params.hasKey("depthScale"))      params.getDouble("depthScale")      else null

            val K = intrinsicsFromShared()

            // ── Primary path: metric depth model ─────────────────────────────────
            val depthResult = runDepthInference(u1, v1, u2, v2)
            Log.d(TAG, "taps u1=$u1 v1=$v1 u2=$u2 v2=$v2 | frame ${MeasureFrameProcessorPlugin.sharedLastFrameOrigW}x${MeasureFrameProcessorPlugin.sharedLastFrameOrigH} | K fx=${K.fx} cx=${K.cx} cy=${K.cy} | depth=${if (depthResult is DepthResult.Success) "p1=${depthResult.depthAtP1} p2=${depthResult.depthAtP2}" else depthResult::class.simpleName}")

            val result = when (depthResult) {
                is DepthResult.Success -> {
                    val raw = MeasureFrameProcessorPlugin.sharedMeasurementEngine
                        .measureMetricDepth(u1, v1, u2, v2, K, depthResult.depthAtP1, depthResult.depthAtP2)
                    // Apply depth scale from calibration when present
                    if (depthScale != null && raw is MeasurementResult.Success) {
                        raw.copy(distanceMm = raw.distanceMm * depthScale, isApproximate = false)
                    } else {
                        raw
                    }
                }
                is DepthResult.NotReady -> MeasurementResult.Error.RAY_PARALLEL_TO_PLANE
                is DepthResult.OOM      -> MeasurementResult.Error.DEGENERATE_INPUT
            }

            when (result) {
                is MeasurementResult.Success -> {
                    val map = WritableNativeMap()
                    map.putDouble("distanceMm", result.distanceMm)
                    map.putString("method", result.method.name.lowercase())
                    map.putBoolean("isApproximate", result.isApproximate)
                    promise.resolve(map)
                }
                is MeasurementResult.Error -> promise.reject("MEASURE_ERROR", result.name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "measure failed", e)
            promise.reject("MEASURE_EXCEPTION", e.message ?: "unknown")
        }
    }

    @ReactMethod
    fun calibrate(params: ReadableMap, promise: Promise) {
        try {
            val u1      = params.getDouble("u1")
            val v1      = params.getDouble("v1")
            val u2      = params.getDouble("u2")
            val v2      = params.getDouble("v2")
            val knownMm = params.getDouble("knownMm")

            val K = intrinsicsFromShared()

            // Derive depth scale purely from inference — no plane detection required.
            val depthResult = runDepthInference(u1, v1, u2, v2)
            val (d1, d2) = when (depthResult) {
                is DepthResult.Success  -> Pair(depthResult.depthAtP1, depthResult.depthAtP2)
                is DepthResult.NotReady -> return promise.reject("DEPTH_NOT_READY", "Depth model not ready — wait a moment and retry")
                is DepthResult.OOM      -> return promise.reject("DEPTH_OOM", "Out of memory — close other apps and retry")
            }

            val uncalibrated = MeasureFrameProcessorPlugin.sharedMeasurementEngine
                .measureMetricDepth(u1, v1, u2, v2, K, d1, d2)

            val rawMm = when (uncalibrated) {
                is MeasurementResult.Success -> uncalibrated.distanceMm
                is MeasurementResult.Error   -> return promise.reject("CALIBRATE_FAILED", "Depth measurement failed: ${uncalibrated.name}")
            }

            if (rawMm < 1.0) return promise.reject("CALIBRATE_FAILED", "Points too close — tap further apart")

            val depthScale = knownMm / rawMm
            Log.d(TAG, "calibrate: knownMm=$knownMm rawMm=$rawMm depthScale=$depthScale")

            val map = WritableNativeMap()
            map.putDouble("scaleGeometric", 1.0)
            map.putDouble("depthScale", depthScale)
            promise.resolve(map)
        } catch (e: Exception) {
            Log.e(TAG, "calibrate failed", e)
            promise.reject("CALIBRATE_EXCEPTION", e.message ?: "unknown")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun runDepthInference(u1: Double, v1: Double, u2: Double, v2: Double): DepthResult {
        val origW = MeasureFrameProcessorPlugin.sharedLastFrameOrigW.takeIf { it > 0 } ?: return DepthResult.NotReady
        val origH = MeasureFrameProcessorPlugin.sharedLastFrameOrigH.takeIf { it > 0 } ?: return DepthResult.NotReady
        val depthEngine = MeasureFrameProcessorPlugin.sharedDepthEngine ?: return DepthResult.NotReady

        val frames = synchronized(MeasureFrameProcessorPlugin.sharedFrameBuffer) {
            MeasureFrameProcessorPlugin.sharedFrameBuffer.toList()
        }
        if (frames.isEmpty()) return DepthResult.NotReady

        val cacheSize = MeasureFrameProcessorPlugin.DEPTH_CACHE_SIZE
        val scaleX = cacheSize.toFloat() / origW
        val scaleY = cacheSize.toFloat() / origH
        val p1x = (u1 * scaleX).toInt()
        val p1y = (v1 * scaleY).toInt()
        val p2x = (u2 * scaleX).toInt()
        val p2y = (v2 * scaleY).toInt()

        // Run inference on every buffered frame; median the depth at each tap point.
        val depths1 = ArrayList<Float>(frames.size)
        val depths2 = ArrayList<Float>(frames.size)
        for (frameBytes in frames) {
            val mat = Mat(cacheSize, cacheSize, org.opencv.core.CvType.CV_8UC4)
            mat.put(0, 0, frameBytes)
            val result = try {
                depthEngine.infer(mat, p1x, p1y, p2x, p2y)
            } finally {
                mat.release()
            }
            when (result) {
                is DepthResult.OOM      -> return DepthResult.OOM
                is DepthResult.NotReady -> continue
                is DepthResult.Success  -> {
                    depths1.add(result.depthAtP1)
                    depths2.add(result.depthAtP2)
                }
            }
        }

        if (depths1.isEmpty()) return DepthResult.NotReady
        depths1.sort(); depths2.sort()
        return DepthResult.Success(
            depthAtP1 = depths1[depths1.size / 2],
            depthAtP2 = depths2[depths2.size / 2],
        )
    }

    private fun intrinsicsFromShared(): CameraIntrinsics {
        val K = MeasureFrameProcessorPlugin.sharedCameraMatrix
        if (K.empty()) return CameraIntrinsics(1200.0, 1200.0, 540.0, 720.0)
        return CameraIntrinsics(
            fx = K.get(0, 0)[0],
            fy = K.get(1, 1)[0],
            cx = K.get(0, 2)[0],
            cy = K.get(1, 2)[0],
        )
    }
}
