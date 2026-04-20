package com.measurearr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer

private const val TAG = "DepthEngine"
private const val MODEL_INPUT = 518
private const val ASSET_NAME = "depth_metric_indoor.onnx"

sealed class DepthResult {
    data class Success(val depthAtP1: Float, val depthAtP2: Float) : DepthResult()
    object NotReady : DepthResult()
    object OOM : DepthResult()
}

class DepthEngine(private val context: Context) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    // Depth Anything V2 — ImageNet mean/std normalisation
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std  = floatArrayOf(0.229f, 0.224f, 0.225f)

    val isReady: Boolean get() = session != null

    fun load() {
        if (isReady) return
        try {
            // Copy from APK assets to filesDir so ONNX Runtime can mmap the file.
            val dest = File(context.filesDir, ASSET_NAME)
            if (!dest.exists()) {
                context.assets.open(ASSET_NAME).use { src ->
                    dest.outputStream().use { dst -> src.copyTo(dst) }
                }
                Log.d(TAG, "Model copied to ${dest.absolutePath}")
            }
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            session = ortEnv.createSession(dest.absolutePath, opts)
            env = ortEnv
            Log.d(TAG, "ONNX session ready — ${dest.length() / 1_000_000} MB")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM loading model", e)
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed — is depth_metric_indoor.onnx in assets?", e)
        }
    }

    /**
     * Run one forward pass and return the metric depth (in METRES) at the two
     * tap coordinates.  Coordinates are in full-resolution FRAME space.
     */
    fun infer(frameRgba: Mat, p1x: Int, p1y: Int, p2x: Int, p2y: Int): DepthResult {
        val sess = session ?: return DepthResult.NotReady
        val ortEnv = env ?: return DepthResult.NotReady

        // Resize to model input and convert to Bitmap
        val resized = Mat()
        val bitmap: Bitmap
        try {
            Imgproc.resize(frameRgba, resized, Size(MODEL_INPUT.toDouble(), MODEL_INPUT.toDouble()))
            bitmap = Bitmap.createBitmap(MODEL_INPUT, MODEL_INPUT, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resized, bitmap)
        } finally {
            resized.release()
        }

        val n = MODEL_INPUT * MODEL_INPUT
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, MODEL_INPUT, 0, 0, MODEL_INPUT, MODEL_INPUT)
        bitmap.recycle()

        // Build CHW float buffer with ImageNet normalisation: (x/255 - mean) / std
        val buf = FloatBuffer.allocate(3 * n)
        for (i in 0 until n) buf.put(((pixels[i] shr 16 and 0xFF) / 255f - mean[0]) / std[0])
        for (i in 0 until n) buf.put(((pixels[i] shr  8 and 0xFF) / 255f - mean[1]) / std[1])
        for (i in 0 until n) buf.put(((pixels[i]        and 0xFF) / 255f - mean[2]) / std[2])
        buf.rewind()

        val inputTensor = OnnxTensor.createTensor(
            ortEnv, buf,
            longArrayOf(1L, 3L, MODEL_INPUT.toLong(), MODEL_INPUT.toLong()),
        )

        val outputs = try {
            sess.run(mapOf("pixel_values" to inputTensor))
        } catch (e: OutOfMemoryError) {
            inputTensor.close()
            return DepthResult.OOM
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            inputTensor.close()
            return DepthResult.NotReady
        } finally {
            inputTensor.close()
        }

        // Output tensor: float32 [1, H, W] — depth in metres (metric model)
        @Suppress("UNCHECKED_CAST")
        val depthMap: Array<FloatArray> = (outputs[0].value as Array<Array<FloatArray>>)[0]
        outputs.close()

        val sx = MODEL_INPUT.toFloat() / frameRgba.cols()
        val sy = MODEL_INPUT.toFloat() / frameRgba.rows()

        // Sample a 9×9 neighbourhood and return the median of non-edge pixels.
        // Edge pixels (where depth jumps sharply vs the centre) are excluded because
        // the model interpolates foreground/background depth at object boundaries,
        // producing values that correspond to neither surface.
        fun sampleMedian(px: Int, py: Int): Float {
            val cx = (px * sx).toInt().coerceIn(0, MODEL_INPUT - 1)
            val cy = (py * sy).toInt().coerceIn(0, MODEL_INPUT - 1)
            val centre = depthMap[cy][cx].coerceAtLeast(0.01f)
            // Edge threshold: exclude pixels whose depth differs from centre by >25%.
            // This removes background-bleed pixels at object boundaries while keeping
            // pixels on curved or slightly uneven surfaces (which differ by <25%).
            val edgeThresh = centre * 0.25f
            val half = 4
            val vals = ArrayList<Float>(81)
            for (dy in -half..half) {
                val r = (cy + dy).coerceIn(0, MODEL_INPUT - 1)
                for (dx in -half..half) {
                    val c = (cx + dx).coerceIn(0, MODEL_INPUT - 1)
                    val v = depthMap[r][c]
                    if (v > 0.01f && kotlin.math.abs(v - centre) <= edgeThresh) vals.add(v)
                }
            }
            // Fall back to unfiltered median if edge filter removed everything
            // (can happen when tapping a very thin object where the whole neighbourhood is edge).
            if (vals.size < 5) {
                val fallback = ArrayList<Float>(81)
                for (dy in -half..half) {
                    val r = (cy + dy).coerceIn(0, MODEL_INPUT - 1)
                    for (dx in -half..half) {
                        val c = (cx + dx).coerceIn(0, MODEL_INPUT - 1)
                        val v = depthMap[r][c]
                        if (v > 0.01f) fallback.add(v)
                    }
                }
                if (fallback.isEmpty()) return centre
                fallback.sort()
                return fallback[fallback.size / 2]
            }
            vals.sort()
            return vals[vals.size / 2]
        }

        return DepthResult.Success(sampleMedian(p1x, p1y), sampleMedian(p2x, p2y))
    }

    fun close() {
        session?.close(); session = null
        env?.close();     env = null
    }
}
