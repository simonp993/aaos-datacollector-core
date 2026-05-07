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

    // Batched sensor samples: [timestampMillis, x, y, z] or [timestampMillis, lux]
    private val accelSamples = mutableListOf<List<Any>>()
    private val gyroSamples = mutableListOf<List<Any>>()
    private val lightSamples = mutableListOf<List<Any>>()

    // Batched battery samples: [timestampMillis, level, charging, tempTenthsC, status]
    private val batterySamples = mutableListOf<List<Any>>()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting sensor and battery monitoring (batched, ${SAMPLE_INTERVAL_MS * SAMPLES_PER_BATCH / 1000}s flush)")

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm

        registerSensor(sm, Sensor.TYPE_ACCELEROMETER, "accelerometer")
        registerSensor(sm, Sensor.TYPE_GYROSCOPE, "gyroscope")
        registerSensor(sm, Sensor.TYPE_LIGHT, "ambient_light")

        // Poll battery every 5s, flush all batches every 60s
        delay(STAGGER_DELAY_MS) // Stagger to spread flush bursts
        var sampleCount = 0
        while (running && coroutineContext.isActive) {
            collectBatterySample()
            sampleCount++

            if (sampleCount >= SAMPLES_PER_BATCH) {
                flushBattery()
                flushSensors()
                sampleCount = 0
            }
            delay(SAMPLE_INTERVAL_MS)
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
                val ts = System.currentTimeMillis()
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> synchronized(accelSamples) {
                        accelSamples.add(
                            listOf(ts, event.values[0], event.values[1], event.values[2]),
                        )
                    }
                    Sensor.TYPE_GYROSCOPE -> synchronized(gyroSamples) {
                        gyroSamples.add(
                            listOf(ts, event.values[0], event.values[1], event.values[2]),
                        )
                    }
                    Sensor.TYPE_LIGHT -> synchronized(lightSamples) {
                        lightSamples.add(listOf(ts, event.values[0]))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
        }
        sensorListeners.add(listener)
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun collectBatterySample() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val pct = if (scale > 0) (level * 100 / scale) else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING

            batterySamples.add(
                listOf(System.currentTimeMillis(), pct, charging, temperature, status),
            )
        }
    }

    private fun flushBattery() {
        if (batterySamples.isEmpty()) return
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Battery_Level",
                    "trigger" to "heartbeat",
                    "metadata" to mapOf(
                        "sampleSchema" to listOf(
                            "timestampMillis",
                            "level",
                            "charging",
                            "temperatureTenthsC",
                            "status",
                        ),
                        "samples" to batterySamples.toList(),
                    ),
                ),
            ),
        )
        batterySamples.clear()
    }

    private fun flushSensors() {
        synchronized(accelSamples) {
            if (accelSamples.isNotEmpty()) {
                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "Sensor_Accelerometer",
                            "trigger" to "heartbeat",
                            "metadata" to mapOf(
                                "sampleSchema" to listOf("timestampMillis", "x", "y", "z"),
                                "samples" to accelSamples.toList(),
                            ),
                        ),
                    ),
                )
                accelSamples.clear()
            }
        }
        synchronized(gyroSamples) {
            if (gyroSamples.isNotEmpty()) {
                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "Sensor_Gyroscope",
                            "trigger" to "heartbeat",
                            "metadata" to mapOf(
                                "sampleSchema" to listOf("timestampMillis", "x", "y", "z"),
                                "samples" to gyroSamples.toList(),
                            ),
                        ),
                    ),
                )
                gyroSamples.clear()
            }
        }
        synchronized(lightSamples) {
            if (lightSamples.isNotEmpty()) {
                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "Sensor_AmbientLight",
                            "trigger" to "heartbeat",
                            "metadata" to mapOf(
                                "sampleSchema" to listOf("timestampMillis", "lux"),
                                "samples" to lightSamples.toList(),
                            ),
                        ),
                    ),
                )
                lightSamples.clear()
            }
        }
    }

    companion object {
        private const val TAG = "SensorBatteryCollector"
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val SAMPLES_PER_BATCH = 12 // 12 × 5s = 60s flush
        private const val STAGGER_DELAY_MS = 3_000L
    }
}
