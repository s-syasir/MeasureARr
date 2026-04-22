package com.measurearr

import com.facebook.react.bridge.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Exposed to JS for model download + readiness checks
class ModelDownloaderModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val downloader = ModelDownloader(reactContext)
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun getName() = "ModelDownloader"

    @ReactMethod
    fun isModelReady(promise: Promise) {
        promise.resolve(downloader.isModelReady())
    }

    @ReactMethod
    fun downloadModel(promise: Promise) {
        scope.launch {
            downloader.download { state ->
                when (state) {
                    is DownloadState.Progress -> {
                        val params = Arguments.createMap().apply {
                            putDouble("bytesReceived", state.bytesReceived.toDouble())
                            putDouble("totalBytes", state.totalBytes.toDouble())
                        }
                        reactApplicationContext
                            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                            .emit("modelDownloadProgress", params)
                    }
                    is DownloadState.Error -> {
                        promise.reject("DOWNLOAD_ERROR", state.message)
                    }
                    DownloadState.HashMismatch -> {
                        promise.reject("HASH_MISMATCH", "Model file corrupted — will re-download")
                    }
                    DownloadState.Complete -> {
                        promise.resolve(true)
                    }
                }
            }
        }
    }

    @ReactMethod
    fun addListener(eventName: String) { /* required by RN event emitter */ }

    @ReactMethod
    fun removeListeners(count: Int) { /* required by RN event emitter */ }
}
