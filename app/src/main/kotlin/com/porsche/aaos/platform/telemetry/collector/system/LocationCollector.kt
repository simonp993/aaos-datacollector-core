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
    private var activeProvider: String? = null
    @Volatile
    private var latestLocation: Location? = null

    // Batched samples: [timestampMillis, latitude, longitude, provider, providerEnabled, reason]
    private val samples = mutableListOf<List<Any?>>()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting location monitoring (${SAMPLE_INTERVAL_MS / 1000}s sample, ${FLUSH_INTERVAL_MS / 1000}s flush)")

        withContext(Dispatchers.Main) {
            val systemContext = ensureSystemUserContext()
            val lm = systemContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    latestLocation = location
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
            activeProvider = provider

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

        // Sample every 5 seconds and flush every 60 seconds (12 samples per window).
        var samplesSinceFlush = 0
        while (running && coroutineContext.isActive) {
            delay(SAMPLE_INTERVAL_MS)
            samplePoint()
            samplesSinceFlush++

            if (samplesSinceFlush >= (FLUSH_INTERVAL_MS / SAMPLE_INTERVAL_MS).toInt()) {
                flush()
                samplesSinceFlush = 0
            }
        }
    }

    override fun stop() {
        running = false
        locationListener?.let { listener ->
            locationManager?.removeUpdates(listener)
        }
        locationListener = null
        locationManager = null
        activeProvider = null
        latestLocation = null
        logger.i(TAG, "Stopped")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun ensureSystemUserContext(): Context {
        return try {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val userHandle = userHandleClass
                .getMethod("of", Int::class.javaPrimitiveType)
                .invoke(null, 0)
            val systemContext = context.javaClass
                .getMethod("createContextAsUser", userHandleClass, Int::class.javaPrimitiveType)
                .invoke(context, userHandle, 0) as Context
            logger.i(TAG, "Using explicit user-0 context for location requests")
            systemContext
        } catch (e: Exception) {
            logger.w(TAG, "Cannot create user-0 context, using app context: ${e.message}")
            context
        }
    }

    private fun samplePoint() {
        val now = System.currentTimeMillis()
        val provider = activeProvider
        val providerEnabled = provider?.let { locationManager?.isProviderEnabled(it) } ?: false
        val location = latestLocation

        val reason = when {
            provider == null -> "provider_unavailable"
            !providerEnabled -> "provider_disabled"
            location == null -> "no_fix"
            else -> "ok"
        }

        val latitude: Double? = if (reason == "ok") location?.latitude else null
        val longitude: Double? = if (reason == "ok") location?.longitude else null

        val row: List<Any?> = listOf(
            now,
            latitude,
            longitude,
            provider,
            providerEnabled,
            reason,
        )

        synchronized(samples) {
            samples.add(row)
        }
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
                                "provider",
                                "providerEnabled",
                                "reason",
                            ),
                            "sampleIntervalMs" to SAMPLE_INTERVAL_MS,
                            "flushIntervalMs" to FLUSH_INTERVAL_MS,
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
