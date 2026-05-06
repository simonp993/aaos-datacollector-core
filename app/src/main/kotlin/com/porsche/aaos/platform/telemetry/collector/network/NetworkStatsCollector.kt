package com.porsche.aaos.platform.telemetry.collector.network

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class NetworkStatsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "NetworkStats"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting network stats monitoring")
        val networkStatsManager =
            context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

        while (running && coroutineContext.isActive) {
            emitTotalStats(networkStatsManager)
            emitPerUidStats(networkStatsManager)
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun emitTotalStats(networkStatsManager: NetworkStatsManager) {
        try {
            val now = System.currentTimeMillis()
            val start = now - POLL_INTERVAL_MS

            val wifiBucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_WIFI, null, start, now,
            )
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Network_WifiTotal",
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "rxBytes" to wifiBucket.rxBytes,
                            "txBytes" to wifiBucket.txBytes,
                        ),
                    ),
                ),
            )

            val mobileBucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE, null, start, now,
            )
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Network_MobileTotal",
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "rxBytes" to mobileBucket.rxBytes,
                            "txBytes" to mobileBucket.txBytes,
                        ),
                    ),
                ),
            )
        } catch (e: Exception) {
            logger.w(TAG, "Failed to collect total network stats", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun emitPerUidStats(networkStatsManager: NetworkStatsManager) {
        try {
            val now = System.currentTimeMillis()
            val start = now - POLL_INTERVAL_MS
            val pm = context.packageManager
            val perApp = mutableListOf<Map<String, Any?>>()

            for ((type, label) in NETWORK_TYPES) {
                val summary = networkStatsManager.querySummary(type, null, start, now)
                val bucket = NetworkStats.Bucket()
                while (summary.hasNextBucket()) {
                    summary.getNextBucket(bucket)
                    if (bucket.rxBytes > 0 || bucket.txBytes > 0) {
                        val packages = pm.getPackagesForUid(bucket.uid)?.toList()
                        perApp.add(
                            mapOf(
                                "uid" to bucket.uid,
                                "packages" to (packages ?: SYSTEM_UID_NAMES[bucket.uid]?.let { listOf(it) }),
                                "network" to label,
                                "rxBytes" to bucket.rxBytes,
                                "txBytes" to bucket.txBytes,
                                "state" to bucket.state,
                            ),
                        )
                    }
                }
                summary.close()
            }

            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Network_PerAppStats",
                        "trigger" to "heartbeat",
                        "metadata" to mapOf("apps" to perApp),
                    ),
                ),
            )
        } catch (e: Exception) {
            logger.w(TAG, "Failed to collect per-app network stats", e)
        }
    }

    companion object {
        private const val TAG = "NetworkStatsCollector"
        private const val POLL_INTERVAL_MS = 60_000L
        private val NETWORK_TYPES = listOf(
            ConnectivityManager.TYPE_WIFI to "wifi",
            ConnectivityManager.TYPE_MOBILE to "mobile",
        )
        private val SYSTEM_UID_NAMES = mapOf(
            0 to "root",
            1000 to "system",
            1001 to "radio",
            1010 to "wifi",
            1013 to "mediaserver",
            1020 to "mdnsr",
            1021 to "dns",
            1051 to "ntp",
            1052 to "drm",
            1073 to "networkstack",
        )
    }
}
