package com.showhow.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Accelerometer variance over a short window. High means the phone is in a
 * hand; near zero means it is flat on the counter. That one number is what the
 * inHand Schmitt trigger in ModeEngine runs on.
 */
class MotionSource(private val context: Context) {

    fun variance(windowMs: Long = 500): Flow<Double> = callbackFlow {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) {
            trySend(0.0)
            awaitClose { }
            return@callbackFlow
        }
        val window = ArrayDeque<Double>()
        val cap = (windowMs / 20).toInt().coerceAtLeast(8)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // Magnitude, not per axis: rotating the phone is motion just as
                // much as shaking it is.
                val x = e.values[0].toDouble()
                val y = e.values[1].toDouble()
                val z = e.values[2].toDouble()
                window.addLast(sqrt(x * x + y * y + z * z))
                while (window.size > cap) window.removeFirst()
                if (window.size >= 4) {
                    val mean = window.average()
                    trySend(window.sumOf { (it - mean) * (it - mean) } / window.size)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }
}
