package com.porsche.datacollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.core.logging.Logger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DataCollectorService : Service() {

    @Inject
    lateinit var collectors: Set<@JvmSuppressWildcards Collector>

    @Inject
    lateinit var logger: Logger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Master enable/disable switch for each collector.
     * Set `true` to enable, `false` to disable.
     * Collectors not listed here default to **enabled**.
     */
    private val collectorEnabled = mapOf(
        "Audio" to true,
        "TouchInput" to true,
        "MediaPlayback" to true,
        "TimeChange" to true,
        "AppLifecycle" to true,
        "CarInfo" to false,
        "Connectivity" to false,
        "DriveState" to false,
        "Memory" to false,
        "NetworkStats" to true,
        "Package" to false,
        "Process" to false,
        "SensorBattery" to false,
        "Telephony" to false,
        "VehicleProperty" to true,
    )

    override fun onCreate() {
        super.onCreate()
        logger.i(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startCollectors()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.i(TAG, "onStartCommand — returning START_STICKY")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        logger.i(TAG, "Service destroyed — stopping collectors")
        stopCollectors()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCollectors() {
        var started = 0
        collectors.forEach { collector ->
            val enabled = collectorEnabled.getOrDefault(collector.name, true)
            if (!enabled) {
                logger.i(TAG, "Collector ${collector.name} is DISABLED — skipping")
                return@forEach
            }
            serviceScope.launch {
                try {
                    logger.i(TAG, "Starting collector: ${collector.name}")
                    collector.start()
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    logger.e(TAG, "Collector ${collector.name} failed", e)
                }
            }
            started++
        }
        logger.i(TAG, "Started $started/${collectors.size} collectors")
    }

    private fun stopCollectors() {
        collectors.forEach { collector ->
            try {
                collector.stop()
                logger.i(TAG, "Stopped collector: ${collector.name}")
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.e(TAG, "Error stopping collector ${collector.name}", e)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Data collection service"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Data Collector")
            .setContentText("Collecting vehicle and system data")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "DataCollectorService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "datacollector_service"
        private const val CHANNEL_NAME = "Data Collector"

        fun start(context: Context) {
            val intent = Intent(context, DataCollectorService::class.java)
            context.startForegroundService(intent)
        }
    }
}
