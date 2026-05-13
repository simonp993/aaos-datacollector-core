package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Bundle
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Collects GPS position samples at [SAMPLE_INTERVAL_MS] intervals, flushed every
 * [FLUSH_INTERVAL_MS].
 *
 * ## MIB4 AAOS Location Architecture
 *
 * On MIB4 the service runs as user 0 (`singleUser="true"`) but the active driver profile
 * is user 13+. LocationManagerService marks user-0 UIDs as "inactive" in the multi-user
 * model and will NOT deliver location callbacks — regardless of:
 * - `ACCESS_BACKGROUND_LOCATION` permission
 * - `FOREGROUND_SERVICE_TYPE_LOCATION` capability (grants `L` cap but still inactive)
 * - `LOCATION_BYPASS` + `setLocationSettingsIgnored(true)` (bypasses settings, not user check)
 * - Passive provider registration
 * - Background throttle whitelist
 *
 * The only user-0 app that receives GPS is `com.here.mib4.psd` which is in the
 * `config_locationEmergencyBypassPackages` system resource overlay (not configurable at
 * runtime) AND has BTOP adj from a visible-process binding.
 *
 * **Solution**: We register a LocationManager listener optimistically (works if platform
 * restrictions are lifted in future firmware), and fall back to reading the GPS provider's
 * cached `last location` from `dumpsys location`. Other system apps (HERE, Mapbox,
 * Porsche Assistant, aptiv) keep GPS active at 100ms-1s intervals, so the cached fix is
 * always fresh.
 */
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
    @Volatile
    private var latestLocation: Location? = null

    // Batched samples: [gpsTimeMillis, latitude, longitude, provider, accuracy, reason]
    private val samples = mutableListOf<List<Any?>>()

    override suspend fun start() {
        running = true
        logger.i(
            TAG,
            "Starting location monitoring " +
                "(${SAMPLE_INTERVAL_MS / 1000}s sample, ${FLUSH_INTERVAL_MS / 1000}s flush)",
        )

        withContext(Dispatchers.Main) {
            registerLocationListener()
        }

        delay(STAGGER_DELAY_MS)

        var samplesSinceFlush = 0
        while (running && coroutineContext.isActive) {
            delay(SAMPLE_INTERVAL_MS)
            samplePoint()
            samplesSinceFlush++

            if (samplesSinceFlush >= SAMPLES_PER_FLUSH) {
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
        latestLocation = null
        logger.i(TAG, "Stopped")
    }

    /**
     * Registers a GPS listener optimistically. On MIB4 this listener will be marked
     * "inactive" and receive 0 callbacks, but we keep it registered so that if the
     * platform restriction is lifted (firmware update, profile change) we immediately
     * start receiving direct callbacks without a restart.
     */
    @Suppress("MissingPermission")
    private fun registerLocationListener() {
        val userContext = createForegroundUserContext()
        val lm = userContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm

        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            logger.w(TAG, "GPS provider not enabled")
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                latestLocation = location
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        locationListener = listener

        val request = buildLocationRequest()
        lm.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            request,
            context.mainExecutor,
            listener,
        )
        logger.i(TAG, "Registered GPS listener (optimistic, dumpsys fallback active)")
    }

    private fun buildLocationRequest(): LocationRequest {
        val builder = LocationRequest.Builder(SAMPLE_INTERVAL_MS)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateDistanceMeters(0f)

        // Enable bypass via reflection (@SystemApi, requires LOCATION_BYPASS permission)
        try {
            val method = LocationRequest.Builder::class.java
                .getMethod("setLocationSettingsIgnored", Boolean::class.javaPrimitiveType)
            method.invoke(builder, true)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.w(TAG, "Cannot set location bypass: ${e.message}")
        }

        return builder.build()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun createForegroundUserContext(): Context {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
            val userId = am?.javaClass?.getMethod("getCurrentUser")?.invoke(am) as? Int ?: 0
            val userHandle = Class.forName("android.os.UserHandle")
                .getMethod("of", Int::class.javaPrimitiveType)
                .invoke(null, userId)
            val userContext = context.javaClass
                .getMethod(
                    "createContextAsUser",
                    Class.forName("android.os.UserHandle"),
                    Int::class.javaPrimitiveType,
                )
                .invoke(context, userHandle, 0) as Context
            logger.i(TAG, "Using user-$userId context")
            userContext
        } catch (e: Exception) {
            logger.w(TAG, "Cannot create user context: ${e.message}")
            context
        }
    }

    private fun samplePoint() {
        // Primary: direct listener callback (works when platform doesn't throttle us)
        // Fallback: parse cached GPS fix from dumpsys location
        val location = latestLocation
        val dumpsysFix = if (location == null) readGpsFromDumpsys() else null
        val lat = location?.latitude ?: dumpsysFix?.first
        val lon = location?.longitude ?: dumpsysFix?.second
        val accuracy = location?.accuracy ?: dumpsysFix?.third

        val source = when {
            location != null -> "listener"
            dumpsysFix != null -> "dumpsys"
            else -> null
        }

        val reason = when {
            lat != null && lon != null -> "ok"
            source == null -> "no_fix"
            else -> "no_fix"
        }

        val row: List<Any?> = listOf(
            System.currentTimeMillis(),
            lat,
            lon,
            source ?: "gps",
            accuracy,
            reason,
        )

        synchronized(samples) {
            samples.add(row)
        }
    }

    /**
     * Parses the GPS provider's last known location from `dumpsys location`.
     * Format: `last location=Location[gps 48.839365,8.869153 hAcc=3.15 ...]`
     *
     * Returns (latitude, longitude, accuracy) or null if unavailable.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readGpsFromDumpsys(): Triple<Double, Double, Float>? {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("dumpsys", "location"),
            )
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var inGpsProvider = false
                for (line in reader.lineSequence()) {
                    if (line.contains("gps provider:")) {
                        inGpsProvider = true
                    }
                    if (inGpsProvider && line.contains("last location=Location[gps")) {
                        return@use line
                    }
                    // Stop after finding fused/network provider section
                    if (inGpsProvider && !line.startsWith(" ") && line.contains("provider:")) {
                        break
                    }
                }
                null
            }
            process.waitFor()

            output?.let { parseLocationLine(it) }
        } catch (e: Exception) {
            logger.w(TAG, "dumpsys location failed: ${e.message}")
            null
        }
    }

    private fun parseLocationLine(line: String): Triple<Double, Double, Float>? {
        // Location[gps 48.839365,8.869153 hAcc=3.15 ...]
        val coordMatch = COORD_PATTERN.find(line) ?: return null
        val lat = coordMatch.groupValues[1].toDoubleOrNull() ?: return null
        val lon = coordMatch.groupValues[2].toDoubleOrNull() ?: return null
        val accMatch = ACCURACY_PATTERN.find(line)
        val accuracy = accMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        return Triple(lat, lon, accuracy)
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
                                "source",
                                "accuracyMeters",
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
        private const val SAMPLE_INTERVAL_MS = 30_000L
        private const val FLUSH_INTERVAL_MS = 60_000L
        private const val STAGGER_DELAY_MS = 4_000L
        private val SAMPLES_PER_FLUSH = (FLUSH_INTERVAL_MS / SAMPLE_INTERVAL_MS).toInt()

        // Location[gps 48.839365,8.869153 ...]
        private val COORD_PATTERN = Regex("""Location\[gps\s+([-\d.]+),([-\d.]+)""")
        // hAcc=3.15
        private val ACCURACY_PATTERN = Regex("""hAcc=([-\d.]+)""")
    }
}
