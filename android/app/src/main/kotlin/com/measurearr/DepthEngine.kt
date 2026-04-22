package com.measurearr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "DepthEngine"
private const val MODEL_INPUT_SIZE = 518  // Depth Anything V2 Small fixed input
private const val FLOAT_BYTES = 4

sealed class DepthResult {
    data class Success(val depthAtP1: Float, val depthAtP2: Float) : DepthResult()
    object NotReady : DepthResult()
    object OOM : DepthResult()
}

class DepthEngine(private val context: Context) {

    private var interpreter: Interpreter? = null

    // Pre-allocated buffers — 3.1MB input tensor, reused every inference, never GC'd per-frame
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3 * FLOAT_BYTES)
        .order(ByteOrder.nativeOrder())

    private val outputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * FLOAT_BYTES)
        .order(ByteOrder.nativeOrder())

    val isReady: Boolean get() = interpreter != null
    var oomOccurred: Boolean = false
        private set

    fun load(modelFile: File) {
        if (isReady) return
        try {
            val opts = Interpreter.Options().apply {
                numThreads = 2
                useNNAPI = false  // no Google delegate
            }
            interpreter = Interpreter(modelFile, opts)
            Log.d(TAG, "TFLite model loaded: ${modelFile.name}")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM loading TFLite model — depth engine disabled", e)
            oomOccurred = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
        }
    }

    // frameRgba: full-res RGBA Mat from VisionCamera
    // p1, p2: tap coordinates in FRAME space (already converted from screen coords)
    fun infer(frameRgba: Mat, p1x: Int, p1y: Int, p2x: Int, p2y: Int): DepthResult {
        val interp = interpreter ?: return DepthResult.NotReady

        val resized = Mat()
        val bitmap: Bitmap
        try {
            Imgproc.resize(frameRgba, resized, Size(MODEL_INPUT_SIZE.toDouble(), MODEL_INPUT_SIZE.toDouble()))
            bitmap = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resized, bitmap)
        } finally {
            resized.release()
        }

        // Scale tap coordinates to model input space
        val scaleX = MODEL_INPUT_SIZE.toFloat() / frameRgba.cols()
        val scaleY = MODEL_INPUT_SIZE.toFloat() / frameRgba.rows()
        val mp1x = (p1x * scaleX).toInt().coerceIn(0, MODEL_INPUT_SIZE - 1)
        val mp1y = (p1y * scaleY).toInt().coerceIn(0, MODEL_INPUT_SIZE - 1)
        val mp2x = (p2x * scaleX).toInt().coerceIn(0, MODEL_INPUT_SIZE - 1)
        val mp2y = (p2y * scaleY).toInt().coerceIn(0, MODEL_INPUT_SIZE - 1)

        // Fill pre-allocated input buffer (normalize pixels to [0,1])
        inputBuffer.rewind()
        for (row in 0 until MODEL_INPUT_SIZE) {
            for (col in 0 until MODEL_INPUT_SIZE) {
                val pixel = bitmap.getPixel(col, row)
                inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f)  // R
                inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)   // G
                inputBuffer.putFloat((pixel and 0xFF) / 255f)            // B
            }
        }
        bitmap.recycle()

        outputBuffer.rewind()
        try {
            interp.run(inputBuffer, outputBuffer)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during TFLite inference", e)
            oomOccurred = true
            close()
            return DepthResult.OOM
        }

        // Sample depth at the two tap points (bilinear interpolation, single-channel float output)
        val depthP1 = sampleDepth(outputBuffer, mp1x, mp1y)
        val depthP2 = sampleDepth(outputBuffer, mp2x, mp2y)

        return DepthResult.Success(depthP1, depthP2)
    }

    private fun sampleDepth(buf: ByteBuffer, x: Int, y: Int): Float {
        val idx = (y * MODEL_INPUT_SIZE + x) * FLOAT_BYTES
        return buf.getFloat(idx)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
