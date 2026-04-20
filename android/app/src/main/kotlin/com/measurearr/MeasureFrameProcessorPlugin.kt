package com.measurearr

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.mrousavy.camera.frameprocessors.Frame
import com.mrousavy.camera.frameprocessors.FrameProcessorPlugin
import com.mrousavy.camera.frameprocessors.FrameProcessorPluginRegistry
import com.mrousavy.camera.frameprocessors.VisionCameraProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

private const val TAG = "MeasurePlugin"

// VisionCamera v4 Kotlin FrameProcessorPlugin (not Nitro Modules — overkill at 10fps)
@Suppress("unused")
class MeasureFrameProcessorPlugin(
    proxy: VisionCameraProxy,
    options: Map<String, Any>?,
) : FrameProcessorPlugin() {

    private val context: Context = proxy.context
    private val planeDetector = PlaneDetector()

    private var cameraMatrix: Mat = Mat()
    private var frameCount = 0
    private var lastPlaneResult: PlaneResult? = null

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
            else           -> buildError("unknown_action:$action")
        }
    }

    private fun processFrame(frame: Frame, args: Map<String, Any>): Map<String, Any> {
        frameCount++
        // Run plane detection every 3rd frame to avoid stutter
        if (frameCount % 3 != 0) {
            return mapOf(
                "confidence" to (lastPlaneResult?.confidence?.toDouble() ?: 0.0),
                "skipped" to true,
            )
        }

        val image = frame.image ?: return buildError("no_image")
        val cameraId = args["cameraId"] as? String

        if (cameraMatrix.empty() && cameraId != null) {
            cameraMatrix = buildCameraMatrix(cameraId, frame.width, frame.height)
        }

        val frameMat = imageToRgbaMat(image) ?: return buildError("unsupported_format")
        val result = try {
            planeDetector.detect(frameMat, cameraMatrix)
        } catch (e: Exception) {
            Log.e(TAG, "PlaneDetector.detect failed: ${e.javaClass.simpleName}: ${e.message}", e)
            frameMat.release()
            return mapOf("confidence" to 0.0, "hasPlane" to false, "skipped" to false)
        }

        // Cache a model-input-sized copy so depth inference has a real frame at tap time
        val depthCache = Mat()
        Imgproc.resize(frameMat, depthCache, Size(DEPTH_CACHE_SIZE.toDouble(), DEPTH_CACHE_SIZE.toDouble()))
        val cacheBytes = ByteArray((depthCache.total() * depthCache.channels()).toInt())
        depthCache.get(0, 0, cacheBytes)
        synchronized(sharedFrameBuffer) {
            if (sharedFrameBuffer.size >= FRAME_BUFFER_SIZE) sharedFrameBuffer.removeFirst()
            sharedFrameBuffer.addLast(cacheBytes)
        }
        sharedLastFrameOrigW = frameMat.cols()
        sharedLastFrameOrigH = frameMat.rows()
        depthCache.release()

        frameMat.release()

        lastPlaneResult = result
        sharedLastPlaneResult = result
        if (!cameraMatrix.empty()) sharedCameraMatrix = cameraMatrix

        return mapOf(
            "confidence" to result.confidence.toDouble(),
            "hasPlane"   to (result.homography != null),
            "skipped"    to false,
        )
    }

    // Convert android.media.Image to RGBA Mat so PlaneDetector can run cvtColor(RGBA2GRAY).
    // Handles YUV_420_888 (common on Android) and RGBA_8888 (VisionCamera RGB mode).
    private fun imageToRgbaMat(image: android.media.Image): Mat? {
        val w = image.width
        val h = image.height
        return when (image.format) {
            ImageFormat.YUV_420_888 -> {
                val yPlane = image.planes[0]
                val uPlane = image.planes[1]
                val vPlane = image.planes[2]
                val yBuf = yPlane.buffer.duplicate()
                val uBuf = uPlane.buffer.duplicate()
                val vBuf = vPlane.buffer.duplicate()
                val yStride = yPlane.rowStride
                val uvStride = uPlane.rowStride
                val uvPixelStride = uPlane.pixelStride

                // Build NV21 (VU interleaved) for OpenCV's COLOR_YUV2RGBA_NV21
                val nv21 = ByteArray(w * h + w * (h / 2))
                for (row in 0 until h) {
                    yBuf.position(row * yStride)
                    yBuf.get(nv21, row * w, minOf(w, yBuf.remaining()))
                }
                var uvPos = w * h
                for (row in 0 until h / 2) {
                    for (col in 0 until w / 2) {
                        val idx = row * uvStride + col * uvPixelStride
                        nv21[uvPos++] = vBuf.get(idx)
                        nv21[uvPos++] = uBuf.get(idx)
                    }
                }
                val yuvMat = Mat(h + h / 2, w, CvType.CV_8UC1)
                yuvMat.put(0, 0, nv21)
                val rgba = Mat()
                Imgproc.cvtColor(yuvMat, rgba, Imgproc.COLOR_YUV2RGBA_NV21)
                yuvMat.release()
                rgba
            }
            PixelFormat.RGBA_8888 -> {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride  // should be 4
                val buf = plane.buffer.duplicate()
                // Copy row-by-row to strip stride padding, producing a packed RGBA mat.
                val packed = ByteArray(h * w * 4)
                for (row in 0 until h) {
                    buf.position(row * rowStride)
                    buf.get(packed, row * w * 4, w * pixelStride)
                }
                val mat = Mat(h, w, CvType.CV_8UC4)
                mat.put(0, 0, packed)
                mat
            }
            else -> {
                Log.w(TAG, "Unsupported image format: ${image.format}")
                null
            }
        }
    }

    companion object {
        // Shared state read by MeasurementBridgeModule for JS-side measure/calibrate calls
        @Volatile var sharedLastPlaneResult: PlaneResult? = null
        @Volatile var sharedCameraMatrix: Mat = Mat()
        val sharedMeasurementEngine = MeasurementEngine()

        // Rolling buffer of the last 3 processed frames (at DEPTH_CACHE_SIZE resolution).
        // MeasurementBridgeModule runs inference on all available frames at tap time
        // and medians the depth results to reduce frame-to-frame model variance.
        const val DEPTH_CACHE_SIZE = 518
        const val FRAME_BUFFER_SIZE = 3
        val sharedFrameBuffer = ArrayDeque<ByteArray>(FRAME_BUFFER_SIZE)
        @Volatile var sharedLastFrameOrigW: Int = 0
        @Volatile var sharedLastFrameOrigH: Int = 0

        // Single-frame alias kept for any callers that only need the most recent frame.
        val sharedLastFrameBytes: ByteArray? get() = sharedFrameBuffer.lastOrNull()

        // Single shared DepthEngine — created lazily when first plugin instance is registered.
        @Volatile var sharedDepthEngine: DepthEngine? = null

        fun register(appContext: android.content.Context) {
            if (sharedDepthEngine == null) {
                sharedDepthEngine = DepthEngine(appContext)
                // Load depth model from bundled assets in the background at startup.
                CoroutineScope(Dispatchers.IO + Job()).launch {
                    sharedDepthEngine?.load()
                }
            }
            FrameProcessorPluginRegistry.addFrameProcessorPlugin("measure") { proxy, options ->
                MeasureFrameProcessorPlugin(proxy, options)
            }
        }
    }

    private fun buildCameraMatrix(cameraId: String, width: Int, height: Int): Mat {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val K = Mat.eye(3, 3, org.opencv.core.CvType.CV_64F)
        try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val intrinsics = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val activeArray: Rect? = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (intrinsics != null && intrinsics.size >= 4 && activeArray != null) {
                // Scale from native sensor resolution to actual captured frame resolution.
                // LENS_INTRINSIC_CALIBRATION is defined for the full sensor pixel array.
                val sx = width.toDouble() / activeArray.width()
                val sy = height.toDouble() / activeArray.height()
                K.put(0, 0, intrinsics[0] * sx)  // fx
                K.put(1, 1, intrinsics[1] * sy)  // fy
                K.put(0, 2, intrinsics[2] * sx)  // cx
                K.put(1, 2, intrinsics[3] * sy)  // cy
                Log.d(TAG, "Scaled intrinsics: fx=${intrinsics[0]*sx} fy=${intrinsics[1]*sy} (sensor ${activeArray.width()}x${activeArray.height()} → frame ${width}x${height})")
                return K
            }
        } catch (e: Exception) {
            Log.w(TAG, "LENS_INTRINSIC_CALIBRATION unavailable — using heuristic fallback", e)
        }
        val fx = width * 1.2
        K.put(0, 0, fx); K.put(1, 1, fx)
        K.put(0, 2, width / 2.0); K.put(1, 2, height / 2.0)
        return K
    }

    private fun buildError(reason: String): Map<String, Any> = mapOf("error" to reason)

    protected fun finalize() {
        planeDetector.close()
        sharedDepthEngine?.close()
        cameraMatrix.release()
    }
}
