package com.measurearr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val TAG = "ModelDownloader"

// Pinned in source — update when releasing a new model
private const val MODEL_URL =
    "https://github.com/measurearr/measurearr/releases/download/v0.1.0/depth_anything_v2_small.tflite"
private const val MODEL_SHA256 =
    "REPLACE_WITH_ACTUAL_SHA256_BEFORE_RELEASE"
private const val MODEL_FILENAME = "depth_anything_v2_small.tflite"

sealed class DownloadState {
    data class Progress(val bytesReceived: Long, val totalBytes: Long) : DownloadState()
    data class Error(val message: String, val retryable: Boolean) : DownloadState()
    object HashMismatch : DownloadState()
    object Complete : DownloadState()
}

class ModelDownloader(private val context: Context) {

    val modelFile: File
        get() = File(context.filesDir, MODEL_FILENAME)

    // Returns true if model is present AND hash-verified
    fun isModelReady(): Boolean {
        val file = modelFile
        if (!file.exists() || file.length() == 0L) return false
        return verifySha256(file)
    }

    suspend fun download(onProgress: (DownloadState) -> Unit) = withContext(Dispatchers.IO) {
        val dest = modelFile
        val tmpFile = File(context.filesDir, "$MODEL_FILENAME.tmp")

        try {
            val url = URL(MODEL_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                onProgress(DownloadState.Error("HTTP ${conn.responseCode}", retryable = true))
                return@withContext
            }

            val totalBytes = conn.contentLengthLong
            var bytesReceived = 0L

            conn.inputStream.use { input ->
                tmpFile.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        bytesReceived += n
                        onProgress(DownloadState.Progress(bytesReceived, totalBytes))
                    }
                }
            }
            conn.disconnect()
        } catch (e: IOException) {
            Log.e(TAG, "Download failed", e)
            tmpFile.delete()
            onProgress(DownloadState.Error(e.message ?: "Network error", retryable = true))
            return@withContext
        }

        // Verify hash before moving into place — treats partial file as mismatch
        if (!verifySha256(tmpFile)) {
            Log.w(TAG, "Hash mismatch on downloaded file — re-downloading next time")
            tmpFile.delete()
            onProgress(DownloadState.HashMismatch)
            return@withContext
        }

        tmpFile.renameTo(dest)
        Log.d(TAG, "Model ready: ${dest.absolutePath}")
        onProgress(DownloadState.Complete)
    }

    private fun verifySha256(file: File): Boolean {
        if (MODEL_SHA256 == "REPLACE_WITH_ACTUAL_SHA256_BEFORE_RELEASE") {
            Log.w(TAG, "SHA-256 not set — skipping verification (dev build only)")
            return true
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val match = actual.equals(MODEL_SHA256, ignoreCase = true)
            if (!match) Log.e(TAG, "SHA-256 mismatch: expected $MODEL_SHA256 got $actual")
            match
        } catch (e: Exception) {
            Log.e(TAG, "Hash verification failed", e)
            false
        }
    }
}
