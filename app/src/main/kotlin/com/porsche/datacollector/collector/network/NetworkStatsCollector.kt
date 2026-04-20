package com.porsche.datacollector.collector.network

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.sportapps.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class NetworkStatsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "NetworkStats"

    @Volatile
    private var running = false

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting network stats monitoring")
        val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

        while (running && coroutineContext.isActive) {
            collectTotalStats(networkStatsManager)
            collectPerUidStats(networkStatsManager)
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collectTotalStats(networkStatsManager: NetworkStatsManager) {
        try {
            val now = System.currentTimeMillis()
            val start = now - POLL_INTERVAL_MS

            // WiFi total
            val wifiBucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_WIFI,
                null,
                start,
                now,
            )
            telemetry.send(
                TelemetryEvent(
                    eventId = "network.total.wifi",
                    payload = mapOf(
                        "rxBytes" to wifiBucket.rxBytes,
                        "txBytes" to wifiBucket.txBytes,
                    ),
                ),
            )

            // Mobile total
            val mobileBucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE,
                null,
                start,
                now,
            )
            telemetry.send(
                TelemetryEvent(
                    eventId = "network.total.mobile",
                    payload = mapOf(
                        "rxBytes" to mobileBucket.rxBytes,
                        "txBytes" to mobileBucket.txBytes,
                    ),
                ),
            )
        } catch (e: Exception) {
            logger.w(TAG, "Failed to collect total network stats", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collectPerUidStats(networkStatsManager: NetworkStatsManager) {
        try {
            val now = System.currentTimeMillis()
            val start = now - POLL_INTERVAL_MS
            val pm = context.packageManager

            val summary = networkStatsManager.querySummary(
                ConnectivityManager.TYPE_WIFI,
                null,
                start,
                now,
            )
            val bucket = android.app.usage.NetworkStats.Bucket()
            val perApp = mutableListOf<Map<String, Any?>>()

            while (summary.hasNextBucket()) {
                summary.getNextBucket(bucket)
                if (bucket.rxBytes > 0 || bucket.txBytes > 0) {
                    val packages = pm.getPackagesForUid(bucket.uid)
                    perApp.add(
                        mapOf(
                            "uid" to bucket.uid,
                            "packages" to packages?.toList(),
                            "rxBytes" to bucket.rxBytes,
                            "txBytes" to bucket.txBytes,
                            "state" to bucket.state,
                        ),
                    )
                }
            }
            summary.close()

            if (perApp.isNotEmpty()) {
                telemetry.send(
                    TelemetryEvent(
                        eventId = "network.per_app",
                        payload = mapOf("apps" to perApp),
                    ),
                )
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to collect per-app network stats", e)
        }
    }

    companion object {
        private const val TAG = "NetworkStatsCollector"
        private const val POLL_INTERVAL_MS = 10_000L
    }
}
