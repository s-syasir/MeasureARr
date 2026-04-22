package com.measurearr

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.mrousavy.camera.frameprocessors.Frame
import com.mrousavy.camera.frameprocessors.FrameProcessorPlugin
import com.mrousavy.camera.frameprocessors.VisionCameraProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat

private const val TAG = "MeasurePlugin"

// VisionCamera v4 Kotlin FrameProcessorPlugin (not Nitro Modules — overkill at 10fps)
class MeasureFrameProcessorPlugin(
    proxy: VisionCameraProxy,
    options: Map<String, Any>?,
) : FrameProcessorPlugin() {

    private val context: Context = proxy.context
    private val planeDetector = PlaneDetector()
    private val depthEngine = DepthEngine(context)
    private val measurementEngine = MeasurementEngine()
    private val modelDownloader = ModelDownloader(context)

    private var cameraMatrix: Mat = Mat()
    private var frameCount = 0
    private var lastPlaneResult: PlaneResult? = null

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Loaded once on SCANNING entry from JS side via plugin options
    private var tfliteLoadStarted = false

    init {
        val loaded = OpenCVLoader.initLocal()
        if (!loaded) Log.e(TAG, "OpenCV failed to load")
        Log.d(TAG, "MeasureFrameProcessorPlugin initialized, OpenCV=$loaded")
    }

    override fun callback(frame: Frame, arguments: Map<String, Any>?): Any? {
        val args = arguments ?: return buildError("no_args")
        val action = args["action"] as? String ?: return buildError("no_action")

        return when (action) {
            "processFrame" -> processFrame(frame, args)
            "measure"      -> performMeasurement(args)
            "calibrate"    -> performCalibration(args)
            "loadDepth"    -> startDepthLoad(args)
            else           -> buildError("unknown_action:$action")
        }
    }

    private fun processFrame(frame: Frame, args: Map<String, Any>): Map<String, Any> {
        frameCount++
        // Run plane detection every 3rd frame to avoid stutter
        if (frameCount % 3 != 0) {
            return mapOf(
                "confidence" to (lastPlaneResult?.confidence ?: 0f),
                "skipped" to true,
            )
        }

        val image = frame.image ?: return buildError("no_image")
        val cameraId = args["cameraId"] as? String

        if (cameraMatrix.empty() && cameraId != null) {
            cameraMatrix = buildCameraMatrix(cameraId, frame.width, frame.height)
        }

        val frameMat = Mat(frame.height, frame.width, CvType.CV_8UC4)
        // NOTE: frame.image is a android.media.Image — plane data is YUV or RGBA depending on VisionCamera config.
        // VisionCamera v4 with pixelFormat="yuv" delivers YUV_420_888. We request pixelFormat="rgb" in Camera.tsx
        // so the frame arrives as RGBA — this mat assignment is valid.
        // If pixelFormat changes, update this conversion.

        val result = planeDetector.detect(frameMat, cameraMatrix)
        frameMat.release()

        lastPlaneResult = result

        return mapOf(
            "confidence" to result.confidence,
            "hasPlane"   to (result.homography != null),
            "skipped"    to false,
        )
    }

    private fun performMeasurement(args: Map<String, Any>): Map<String, Any> {
        val u1 = (args["u1"] as? Number)?.toDouble() ?: return buildError("missing_u1")
        val v1 = (args["v1"] as? Number)?.toDouble() ?: return buildError("missing_v1")
        val u2 = (args["u2"] as? Number)?.toDouble() ?: return buildError("missing_u2")
        val v2 = (args["v2"] as? Number)?.toDouble() ?: return buildError("missing_v2")
        val scaleGeometric = (args["scaleGeometric"] as? Number)?.toDouble() ?: return buildError("missing_scaleGeometric")
        val depthScale = (args["depthScale"] as? Number)?.toDouble() ?: return buildError("missing_depthScale")

        val plane = lastPlaneResult
        val K = intrinsicsFromMat(cameraMatrix)
        val calibration = CalibrationState(scaleGeometric, depthScale, isCalibrated = true)

        return if (plane != null && plane.confidence > 0.7f && plane.homography != null) {
            when (val r = measurementEngine.measure(u1, v1, u2, v2, K, plane.normalVec, plane.distanceRaw, calibration)) {
                is MeasurementResult.Success -> mapOf(
                    "distanceMm"    to r.distanceMm,
                    "method"        to "geometric",
                    "isApproximate" to false,
                )
                is MeasurementResult.Error -> buildError(r.name)
                else -> buildError("unknown")
            }
        } else {
            // Depth fallback — JS side passes frame pixel coords already in frame space
            val depthResult = depthEngine.infer(
                Mat(), // no frame available at measurement time — depth engine reuses last inference
                u1.toInt(), v1.toInt(), u2.toInt(), v2.toInt()
            )
            when (depthResult) {
                is DepthResult.NotReady -> buildError("depth_not_ready")
                is DepthResult.OOM -> buildError("depth_oom")
                is DepthResult.Success -> when (val r = measurementEngine.measureDepth(
                    u1, v1, u2, v2, K,
                    depthResult.depthAtP1, depthResult.depthAtP2,
                    calibration,
                )) {
                    is MeasurementResult.Success -> mapOf(
                        "distanceMm"    to r.distanceMm,
                        "method"        to "depth",
                        "isApproximate" to true,
                    )
                    is MeasurementResult.Error -> buildError(r.name)
                    else -> buildError("unknown")
                }
            }
        }
    }

    private fun performCalibration(args: Map<String, Any>): Map<String, Any> {
        val u1 = (args["u1"] as? Number)?.toDouble() ?: return buildError("missing_u1")
        val v1 = (args["v1"] as? Number)?.toDouble() ?: return buildError("missing_v1")
        val u2 = (args["u2"] as? Number)?.toDouble() ?: return buildError("missing_u2")
        val v2 = (args["v2"] as? Number)?.toDouble() ?: return buildError("missing_v2")
        val knownMm = (args["knownMm"] as? Number)?.toDouble() ?: return buildError("missing_knownMm")
        val depthAtP1 = (args["depthAtP1"] as? Number)?.toFloat() ?: 1f
        val depthAtP2 = (args["depthAtP2"] as? Number)?.toFloat() ?: 1f

        val plane = lastPlaneResult ?: return buildError("no_plane")
        val K = intrinsicsFromMat(cameraMatrix)

        return when (val r = measurementEngine.calibrate(
            u1, v1, u2, v2, K,
            plane.normalVec, plane.distanceRaw,
            depthAtP1, depthAtP2,
            knownMm,
        )) {
            is kotlin.Result -> if (r.isSuccess) {
                val cal = r.getOrThrow()
                mapOf(
                    "scaleGeometric" to cal.scaleGeometric,
                    "depthScale"     to cal.depthScale,
                )
            } else {
                buildError(r.exceptionOrNull()?.message ?: "calibration_failed")
            }
        }
    }

    private fun startDepthLoad(args: Map<String, Any>): Map<String, Any> {
        if (tfliteLoadStarted || depthEngine.isReady) return mapOf("status" to "already_loading")
        tfliteLoadStarted = true
        val modelFile = modelDownloader.modelFile
        scope.launch {
            depthEngine.load(modelFile)
        }
        return mapOf("status" to "loading")
    }

    private fun buildCameraMatrix(cameraId: String, width: Int, height: Int): Mat {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val K = Mat.eye(3, 3, org.opencv.core.CvType.CV_64F)
        try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val intrinsics = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            if (intrinsics != null && intrinsics.size >= 4) {
                // LENS_INTRINSIC_CALIBRATION: [fx, fy, cx, cy, skew]
                K.put(0, 0, intrinsics[0].toDouble())  // fx
                K.put(1, 1, intrinsics[1].toDouble())  // fy
                K.put(0, 2, intrinsics[2].toDouble())  // cx
                K.put(1, 2, intrinsics[3].toDouble())  // cy
                Log.d(TAG, "Using LENS_INTRINSIC_CALIBRATION: fx=${intrinsics[0]} fy=${intrinsics[1]}")
                return K
            }
        } catch (e: Exception) {
            Log.w(TAG, "LENS_INTRINSIC_CALIBRATION unavailable — using heuristic fallback", e)
        }
        // Standard heuristic: fx=fy=width*1.2, principal point at center (~5% error on most phones)
        val fx = width * 1.2
        K.put(0, 0, fx); K.put(1, 1, fx)
        K.put(0, 2, width / 2.0); K.put(1, 2, height / 2.0)
        return K
    }

    private fun intrinsicsFromMat(K: Mat): CameraIntrinsics {
        if (K.empty()) return CameraIntrinsics(1200.0, 1200.0, 540.0, 720.0)
        return CameraIntrinsics(
            fx = K.get(0, 0)[0],
            fy = K.get(1, 1)[0],
            cx = K.get(0, 2)[0],
            cy = K.get(1, 2)[0],
        )
    }

    private fun buildError(reason: String): Map<String, Any> = mapOf("error" to reason)

    override fun finalize() {
        planeDetector.close()
        depthEngine.close()
        cameraMatrix.release()
    }
}
