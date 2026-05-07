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
     * Per-collector:
     *   adb logcat | grep "DataCollector:LogTelemetry.*AudioCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*AppLifecycleCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*NetworkStatsCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*TouchInputCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*VehiclePropertyCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*MemoryCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*MediaPlaybackCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*TimeChangeCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*CarInfoCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*ConnectivityCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*DriveStateCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*PackageCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*ProcessCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*SensorBatteryCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*TelephonyCollector"
     *   adb logcat | grep "DataCollector:LogTelemetry.*FrameRateCollector"
     *
     * Multiple collectors:
     *   adb logcat | grep -E "DataCollector:LogTelemetry.*(AppLifecycle|MediaPlayback)"
     *
     * Add -s emulator-5554 or -s 172.16.250.248:5555 to target a specific device.
     *
     * ── General TODOs (ordered by dependency — do top-first) ───────────────────
     *
     * DONE G1: Homogeneous payload structure — see docs/guides/signal-structure-guidelines.md
     * DONE G2: Consistent timestamps — all use epochMillis (Long). Removed epochSec, iso8601.
     *
     * TODO G3: Event correlation — add a shared session/correlation ID so events from the
     *          same drive session or time window can be linked across collectors.
     *
     * TODO G4: Multi-user coverage — ensure all collectors query data for user 0 AND user 10.
     *          Example: mapbox/navigation context. (Talk to Benni, check coreservices solution.)
     *          NetworkStatsCollector already done; apply pattern to others.
     *
     * TODO G5: Stagger cyclic collectors — add random offset (0–60s) to each 60s poll so they
     *          don't all fire at the same instant. Reduces burst load on telemetry sink.
     *
     * TODO G6: Power impact analysis — profile CPU/battery impact of DataCollector on the
     *          system. Identify hot collectors and tune intervals.
     *
     * TODO G7: Make events smaller — once structure is stable, minimize payload size while
     *          keeping the same information (shorter keys, omit null fields, etc.).
     *
     * TODO G8: Dev vs prod flavour — some verbose signals (e.g. per-frame, per-touch) should
     *          only emit in dev builds or at reduced frequency in prod.
     *
     * TODO G9: Add payload documentation comments to every collector class explaining what
     *          each field means, units, and when the event fires.
     *
     * TODO G10: Auto-document action names — generate a registry/table of all action name
     *           strings emitted by collectors (annotation processor or build-time script).
     *
     * TODO G11: Combine HeartbeatCollector + SystemCollector? Evaluate if they can merge.
     *
     * TODO G12: Display on/off state — detect and emit when displays are turned on/off.
     *
     * TODO G13: App startup times — capture time-to-first-frame per app.
     *           (Blocked — discuss with Mathieu, not possible currently.)
     *
     * TODO G14: FrameRate schema — use an efficient schema (deltas/run-length) if frame rate
     *           data volume becomes a concern.
     * 
     * TODO G15: Add unit and system and maybe e2etests
     *
     * ── Per-Collector TODOs (ordered: broken > wrong data > needs tuning > verify only) ──
     *
     * --- BROKEN (emitting nothing) ---
     *
     * TODO C1: ConnectivityCollector — emits nothing. Fix event emission logic.
     * TODO C2: DriveStateCollector — emits nothing. Fix event emission logic.
     * TODO C3: PackageCollector — emits nothing. Fix event emission logic.
     * TODO C4: ProcessCollector — emits nothing. Fix event emission logic.
     * TODO C5: CarInfoCollector — empty metadata, no trigger. Fix.
     *
     * --- WRONG DATA ---
     *
     * TODO C6: VehiclePropertyCollector — `previous` is always null (likely polling, not
     *          on-change). Switch to on-change subscription; emit previous+current. Batch:
     *          collect changes with timestamps for 60s, send all at once (same property can
     *          appear multiple times). Rename action → "VHAL parameters changed".
     *
     * TODO C7: MediaPlaybackCollector — weird/malformed payloads. Investigate and fix.
     *
     * TODO C8: TimeChangeCollector — manual user time change shows trigger="system" (wrong).
     *          Also missing `previous` value.
     *
     * TODO C9: TelephonyCollector — seems incorrect, no trigger field. Fix trigger logic.
     *
     * --- NEEDS TUNING ---
     *
     * TODO C10: SensorBatteryCollector — data looks correct but polling too fast. Reduce
     *           interval or switch to on-change.
     *
     * TODO C11: FrameRateCollector — verify display state changes are logged. Check payload
     *           structure matches expected schema.
     *
     * --- WORKING ON EMULATOR, VERIFY ON REAL DEVICE ---
     *
     * TODO C12: AudioCollector — works on emulator (mute button not functional on emu).
     *           Needs real device testing.
     *
     * TODO C13: AppLifecycleCollector — works on emulator. Needs real device testing.
     *           (App startup time: not possible currently, revisit with Mathieu later.)
     *
     * TODO C14: NetworkStatsCollector — works on emulator (multi-user verified).
     *           Needs real device testing.
     *
     * TODO C15: TouchInputCollector — works on emulator. Needs real device testing.
     *
     * TODO C16: MemoryCollector — needs emulator + real device testing.
     *           Test cmd: adb shell am send-trim-memory com.porsche.aaos.platform.telemetry
     *           RUNNING_MODERATE | RUNNING_LOW | RUNNING_CRITICAL
     *
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
