package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class SensorBatteryCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "SensorBattery"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false
    private var sensorManager: SensorManager? = null
    private val sensorListeners = mutableListOf<SensorEventListener>()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting sensor and battery monitoring")

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm

        registerSensor(sm, Sensor.TYPE_ACCELEROMETER, "accelerometer")
        registerSensor(sm, Sensor.TYPE_GYROSCOPE, "gyroscope")
        registerSensor(sm, Sensor.TYPE_LIGHT, "ambient_light")

        // Poll battery state
        while (running && coroutineContext.isActive) {
            collectBatteryState()
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        val sm = sensorManager
        if (sm != null) {
            sensorListeners.forEach { sm.unregisterListener(it) }
        }
        sensorListeners.clear()
        sensorManager = null
        logger.i(TAG, "Stopped")
    }

    private fun registerSensor(sm: SensorManager, sensorType: Int, label: String) {
        val sensor = sm.getDefaultSensor(sensorType)
        if (sensor == null) {
            logger.w(TAG, "Sensor $label not available")
            return
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val action = label.split("_")
                    .joinToString("") { it.replaceFirstChar(Char::uppercase) }
                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "Sensor_${action}Changed",
                            "metadata" to mapOf(
                                "values" to event.values.toList(),
                                "accuracy" to event.accuracy,
                            ),
                        ),
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // No-op
            }
        }
        sensorListeners.add(listener)
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun collectBatteryState() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)

            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Battery_StatePolled",
                        "metadata" to mapOf(
                            "level" to if (scale > 0) (level * 100 / scale) else -1,
                            "charging" to (status == BatteryManager.BATTERY_STATUS_CHARGING),
                            "temperatureTenthsC" to temperature,
                            "status" to status,
                        ),
                    ),
                ),
            )
        }
    }

    companion object {
        private const val TAG = "SensorBatteryCollector"
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
