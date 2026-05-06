package com.porsche.aaos.platform.telemetry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
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
     *
     * ── Logging architecture ───────────────────────────────────────────────────
     * Each collector logs its own lifecycle/debug messages (e.g. DataCollector:AudioCollector).
     * Additionally, every telemetry event is logged by the LogTelemetry sink
     * (DataCollector:LogTelemetry) with the structured payload. This means each event
     * appears twice in logcat: once from the collector, once from the telemetry sink.
     *
     * ── Logcat commands ────────────────────────────────────────────────────────
     * All DataCollector logs (all collectors + service):
     *   adb logcat | grep "DataCollector:"
     *
     * All telemetry payloads only (LogTelemetry output):
     *   adb logcat | grep "DataCollector:LogTelemetry"
     *
     * Per-collector telemetry events only:
     *   TODO: - make the events smaller with same information, make sure, that all events can be corelated, 
     *           make sure previous value and current exist, make sure all payloads or somewhat homogeneous, 
     *           check general implications of datacollector on the system power, maybe add dev and prod flavour for some signals,
     *           combine heartbeat and system?, Get turned on off state of displays? Make sure timestamps inside metadata have same format (epochSec)
     *           add comments explaining payloads to all collectors. Get the app startup times, make sure to have the FrameRate schema for efficiency if possible,
     *           document all action names somehow automatic, ensure that not all 60s cyclic things are send at the same time, maybe add some randomization to it, so they are more distributed over time.
     * 
     *   adb logcat | grep "DataCollector:LogTelemetry.*AudioCollector"         - Checked on emulator, mute button not working, TODO needs real device testing
     *   adb logcat | grep "DataCollector:LogTelemetry.*AppLifecycleCollector"  - Checked on emulator, TODO needs real device testing, test for App_TimeUntilStarted
     *   adb logcat | grep "DataCollector:LogTelemetry.*NetworkStatsCollector"  - Checked on emulator, TODO needs real device testing
     *   adb logcat | grep "DataCollector:LogTelemetry.*TouchInputCollector"    - Checked on emulator, TODO needs real device testing
     *   adb logcat | grep "DataCollector:LogTelemetry.*VehiclePropertyCollector" - TODO maybe change action name to VHAL parameters changed and batch changes since too many values are send, previous seems to be always null, i think they are on poll not on change, maybe it's possible to only send when a value changes. then send previous and current. Batch it if possible. So collect changes with timestamps for 60seconds and send all at once (same variable can appear multiple times). 
     *   adb logcat | grep "DataCollector:LogTelemetry.*MemoryCollector"        - TODO needs emulator + real device testing
     *               TODO adb shell am send-trim-memory com.porsche.aaos.platform.telemetry RUNNING_MODERATE or RUNNING_LOW or RUNNING_CRITICAL (for testing, add later)
     *   adb logcat | grep "DataCollector:LogTelemetry.*MediaPlaybackCollector" - TODO needs emulator + real device testing -> Fix, weird payloads    
     *   adb logcat | grep "DataCollector:LogTelemetry.*TimeChangeCollector"    - TODO needs emulator + real device testing -> Works, but a user manual change in the settings, had the trigger "system" on it, also no previous value
     *   adb logcat | grep "DataCollector:LogTelemetry.*CarInfoCollector"       - TODO needs emulator + real device testing -> empty metadata, no trigger
     *   adb logcat | grep "DataCollector:LogTelemetry.*ConnectivityCollector"  - TODO needs emulator + real device testing -> nothing yet
     *   adb logcat | grep "DataCollector:LogTelemetry.*DriveStateCollector"    - TODO needs emulator + real device testing -> nothing 
     *   adb logcat | grep "DataCollector:LogTelemetry.*PackageCollector"       - TODO needs emulator + real device testing -> nothing yet
     *   adb logcat | grep "DataCollector:LogTelemetry.*ProcessCollector"       - TODO needs emulator + real device testing -> nothing
     *   adb logcat | grep "DataCollector:LogTelemetry.*SensorBatteryCollector" - TODO needs emulator + real device testing -> seems correct, to fast polling
     *   adb logcat | grep "DataCollector:LogTelemetry.*TelephonyCollector"     - TODO needs emulator + real device testing -> seems incorrect, no trigger
     *   adb logcat | grep "DataCollector:LogTelemetry.*FrameRateCollector"     - TODO needs emulator + real device testing -> test if change of display state is logged, also check payload
     *
     * Multiple collectors:
     *   adb logcat | grep -E "DataCollector:LogTelemetry.*(AppLifecycle|MediaPlayback)"
     *
     * Add -s emulator-5554 or -s 172.16.250.248:5555 to target a specific device.
     * ──────────────────────────────────────────────────────────────────────────
     */
    private val collectorEnabled = mapOf(
        "Audio" to true,
        "TouchInput" to true,
        "MediaPlayback" to true,
        "TimeChange" to true,
        "AppLifecycle" to true,
        "CarInfo" to true,
        "Connectivity" to true,
        "DriveState" to true,
        "Memory" to true,
        "NetworkStats" to true,
        "Package" to true,
        "Process" to true,
        "SensorBattery" to true,
        "Telephony" to true,
        "VehicleProperty" to true,
        "FrameRate" to true,
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
