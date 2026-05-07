package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class LocationCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Location"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    // Batched samples: [timestampMillis, lat, lng, alt, speedMps, bearing, accuracyM]
    private val samples = mutableListOf<List<Any>>()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting location monitoring (${SAMPLE_INTERVAL_MS / 1000}s sample, ${FLUSH_INTERVAL_MS / 1000}s flush)")

        withContext(Dispatchers.Main) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    synchronized(samples) {
                        samples.add(
                            listOf(
                                System.currentTimeMillis(),
                                location.latitude,
                                location.longitude,
                                location.altitude,
                                location.speed,
                                location.bearing,
                                location.accuracy,
                            ),
                        )
                    }
                }

                override fun onProviderEnabled(provider: String) {
                    logger.d(TAG, "Provider enabled: $provider")
                }

                override fun onProviderDisabled(provider: String) {
                    logger.d(TAG, "Provider disabled: $provider")
                }

                @Deprecated("Deprecated in API 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                    // Required override for older API levels
                }
            }
            locationListener = listener

            @Suppress("MissingPermission") // System-privileged app with platform key
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> {
                    logger.w(TAG, "No location provider available")
                    return@withContext
                }
            }

            logger.i(TAG, "Using provider: $provider")

            @Suppress("MissingPermission")
            lm.requestLocationUpdates(
                provider,
                SAMPLE_INTERVAL_MS,
                0f, // No distance filter — time-based only
                listener,
            )
        }

        // Stagger initial delay to spread flush bursts across collectors
        delay(STAGGER_DELAY_MS)

        // Flush loop
        while (running && coroutineContext.isActive) {
            delay(FLUSH_INTERVAL_MS)
            flush()
        }
    }

    override fun stop() {
        running = false
        locationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
        }
        locationListener = null
        locationManager = null
        logger.i(TAG, "Stopped")
    }

    private fun flush() {
        synchronized(samples) {
            if (samples.isEmpty()) return
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Location_Position",
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "sampleSchema" to listOf(
                                "timestampMillis",
                                "latitude",
                                "longitude",
                                "altitude",
                                "speedMps",
                                "bearing",
                                "accuracyMeters",
                            ),
                            "samples" to samples.toList(),
                        ),
                    ),
                ),
            )
            samples.clear()
        }
    }

    companion object {
        private const val TAG = "LocationCollector"
        private const val SAMPLE_INTERVAL_MS = 5_000L // 5s GPS updates
        private const val FLUSH_INTERVAL_MS = 60_000L // Batch flush every 60s
        private const val STAGGER_DELAY_MS = 4_000L
    }
}
