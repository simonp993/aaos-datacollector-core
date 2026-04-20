package com.porsche.datacollector.collector.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.datacollector.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PackageCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Package"

    private var packageReceiver: BroadcastReceiver? = null

    override suspend fun start() {
        logger.i(TAG, "Starting package monitoring")

        // Emit full installed package list on startup
        collectInstalledPackages()

        // Register for package change broadcasts
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                val eventId = when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> "package.installed"
                    Intent.ACTION_PACKAGE_REMOVED -> "package.removed"
                    Intent.ACTION_PACKAGE_REPLACED -> "package.updated"
                    else -> return
                }
                telemetry.send(
                    TelemetryEvent(
                        eventId = eventId,
                        payload = mapOf("package" to packageName),
                    ),
                )
            }
        }
        packageReceiver = receiver

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter)
    }

    override fun stop() {
        packageReceiver?.let { context.unregisterReceiver(it) }
        packageReceiver = null
        logger.i(TAG, "Stopped")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collectInstalledPackages() {
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            val packageList = packages.map { pkg ->
                mapOf(
                    "package" to pkg.packageName,
                    "versionName" to pkg.versionName,
                    "versionCode" to pkg.longVersionCode,
                    "firstInstall" to pkg.firstInstallTime,
                    "lastUpdate" to pkg.lastUpdateTime,
                )
            }

            telemetry.send(
                TelemetryEvent(
                    eventId = "package.inventory",
                    payload = mapOf(
                        "count" to packages.size,
                        "packages" to packageList,
                    ),
                ),
            )
            logger.i(TAG, "Collected ${packages.size} installed packages")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to collect installed packages", e)
        }
    }

    companion object {
        private const val TAG = "PackageCollector"
    }
}
