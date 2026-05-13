package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
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

    // Batched samples: [gpsTimeMillis, latitude, longitude, provider, providerEnabled, reason]
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

            registerProvider(lm, listener)
        }

        // Stagger initial delay to spread flush bursts across collectors
        delay(STAGGER_DELAY_MS)

        // Sample every 5 seconds and flush every 60 seconds (12 samples per window).
        var samplesSinceFlush = 0
        var samplesSinceProviderRetry = 0
        while (running && coroutineContext.isActive) {
            delay(SAMPLE_INTERVAL_MS)
            samplePoint()
            samplesSinceFlush++
            samplesSinceProviderRetry++

            if (samplesSinceFlush >= (FLUSH_INTERVAL_MS / SAMPLE_INTERVAL_MS).toInt()) {
                flush()
                samplesSinceFlush = 0
            }

            // Periodically retry GPS if we're on a fallback or no provider
            if (samplesSinceProviderRetry >= PROVIDER_RETRY_SAMPLES) {
                samplesSinceProviderRetry = 0
                retryGpsProvider()
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

    @Suppress("MissingPermission")
    private fun registerProvider(lm: LocationManager, listener: LocationListener) {
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                logger.w(TAG, "No location provider available")
                return
            }
        }

        logger.i(TAG, "Using provider: $provider")
        activeProvider = provider

        lm.requestLocationUpdates(
            provider,
            SAMPLE_INTERVAL_MS,
            0f, // No distance filter — time-based only
            listener,
        )
    }

    private fun retryGpsProvider() {
        val lm = locationManager ?: return
        val listener = locationListener ?: return
        // Already on GPS — nothing to do
        if (activeProvider == LocationManager.GPS_PROVIDER) return
        // Check if GPS became available
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return

        logger.i(TAG, "GPS provider now available, switching from $activeProvider")
        @Suppress("MissingPermission")
        lm.removeUpdates(listener)
        activeProvider = LocationManager.GPS_PROVIDER
        latestLocation = null // Clear stale network fix
        @Suppress("MissingPermission")
        lm.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            SAMPLE_INTERVAL_MS,
            0f,
            listener,
        )
    }

    private fun samplePoint() {
        val provider = activeProvider
        val providerEnabled = provider?.let { locationManager?.isProviderEnabled(it) } ?: false
        val location = latestLocation

        // Detect stale fix: if location's elapsed realtime age exceeds threshold, treat as lost
        val ageMs = location?.let {
            val elapsedNow = SystemClock.elapsedRealtimeNanos()
            (elapsedNow - it.elapsedRealtimeNanos) / 1_000_000L
        }
        val isStale = ageMs != null && ageMs > STALE_THRESHOLD_MS

        val reason = when {
            provider == null -> "provider_unavailable"
            !providerEnabled -> "provider_disabled"
            location == null -> "no_fix"
            isStale -> "stale"
            else -> "ok"
        }

        val hasValidFix = reason == "ok"
        val latitude: Double? = if (hasValidFix) location?.latitude else null
        val longitude: Double? = if (hasValidFix) location?.longitude else null
        val timestamp: Long = if (hasValidFix) location!!.time else System.currentTimeMillis()

        val row: List<Any?> = listOf(
            timestamp,
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
                                "gpsTimeMillis",
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
        private const val STALE_THRESHOLD_MS = 15_000L // 3x sample interval = stale
        private const val PROVIDER_RETRY_SAMPLES = 12 // Retry provider every 60s (12 × 5s)
    }
}
