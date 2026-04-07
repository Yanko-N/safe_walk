package com.example.safe_walk

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class SensorHandler(context: Context, private val viewModel: SafeWalkViewModel) :
    SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    fun start() {
        if (lightSensor == null) {
            viewModel.hasLightSensor = false
            Log.e("SensorHandler", "Light sensor not found on this device")
        } else {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI)
        }
        
        if (accelSensor == null) {
            Log.e("SensorHandler", "Accelerometer not found on this device")
        } else {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val lux = event.values[0]
                viewModel.updateLightLevel(lux)
                // Log para verificar se o sensor está a disparar no Logcat
                Log.d("SensorHandler", "Light level: $lux lux")
            }
            Sensor.TYPE_ACCELEROMETER -> {
                viewModel.checkFallDetection(event.values)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
