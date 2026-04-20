package com.measurearr

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Watches TYPE_GRAVITY. Emits "tiltWarning" events when the phone pitches past 70°
 * (user is holding phone nearly vertical — measurements will be inaccurate).
 */
class TiltModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), SensorEventListener {

    override fun getName() = "TiltModule"

    private val sensorManager by lazy {
        reactContext.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
    }
    private var listenerCount = 0
    private var lastWarning = false

    @ReactMethod
    fun addListener(eventName: String) {
        if (++listenerCount == 1) startListening()
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        listenerCount = maxOf(0, listenerCount - count)
        if (listenerCount == 0) stopListening()
    }

    private fun startListening() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Gravity vector; angle from vertical = acos(gz / |g|)
        val gx = event.values[0].toDouble()
        val gy = event.values[1].toDouble()
        val gz = event.values[2].toDouble()
        val mag = sqrt(gx * gx + gy * gy + gz * gz)
        if (mag < 1e-6) return

        val angleFromVertical = Math.toDegrees(acos(gz / mag))
        val isTilted = angleFromVertical > 70.0

        if (isTilted != lastWarning) {
            lastWarning = isTilted
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("tiltWarning", isTilted)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    override fun invalidate() {
        super.invalidate()
        stopListening()
    }
}
