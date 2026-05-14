package com.porsche.aaos.platform.telemetry.collector.system

import android.car.Car
import android.car.CarAppFocusManager
import android.content.Context
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import javax.inject.Inject

/**
 * Collects navigation app-focus ownership changes via CarAppFocusManager.
 *
 * ## Multi-user limitation (MIB4)
 *
 * On MIB4 the telemetry service runs as user-0 (system), but the active HU user
 * is user-13. CarAppFocusManager's OnAppFocusChangedListener and getAppTypeOwner()
 * are scoped per-user — a user-0 process cannot observe focus changes owned by
 * user-13 apps. The same limitation affects LocationManager, SensorManager, and
 * other system managers whose state is user-isolated.
 *
 * ## Current workaround
 *
 * This collector polls `dumpsys car_service` (which reports cross-user state) and
 * parses the AppFocusService section to detect who holds navigation focus
 * (APP_FOCUS_TYPE_NAVIGATION = 1). The owning PID is resolved to a package name
 * via `dumpsys activity processes`.
 *
 * ## Ideal architecture (future)
 *
 * To avoid dumpsys polling and get real-time callbacks, part of the data collection
 * logic should run in the active user context (user-13). This could be achieved by:
 * - A bound service component running as the current user that queries
 *   CarAppFocusManager, LocationManager, etc. on behalf of the user-0 host service
 * - Communication between user-0 host and user-13 satellite via AIDL or ContentProvider
 * This would eliminate the need for dumpsys parsing and enable instant event delivery.
 *
 * ## Emits
 *
 * - Navigation_FocusChanged: when the navigation focus owner changes
 *   (gained, lost, or switched to a different package).
 *
 * The owner package name is classified:
 * - "carplay" if the owner contains "carplay"
 * - "android_auto" if it contains "androidauto" or "android.auto"
 * - "native" if it contains known native nav hints (mapbox, here, nav, maps, etc.)
 * - "none" if no app holds navigation focus
 */
class NavigationCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Navigation"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private var car: Car? = null
    private var appFocusManager: CarAppFocusManager? = null

    @Volatile
    private var previousOwner: String? = null

    @Volatile
    private var previousSourceType: String = "none"

    override suspend fun start() {
        logger.i(TAG, "Starting navigation monitoring (poll-based via dumpsys)")

        val carInstance = try {
            Car.createCar(context)
        } catch (e: Exception) {
            logger.e(TAG, "Car.createCar failed: ${e.message}")
            null
        }

        if (carInstance == null) {
            logger.w(TAG, "Car.createCar returned null; navigation monitoring unavailable")
            return
        }
        car = carInstance

        val manager = try {
            carInstance.getCarManager(Car.APP_FOCUS_SERVICE) as? CarAppFocusManager
        } catch (e: Exception) {
            logger.e(TAG, "Failed to get CarAppFocusManager: ${e.message}")
            null
        }

        if (manager == null) {
            logger.w(TAG, "CarAppFocusManager unavailable")
            return
        }
        appFocusManager = manager

        // Register listener optimistically (may work on other platforms)
        try {
            manager.addFocusListener(
                { appType, active ->
                    if (appType == CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION) {
                        logger.d(TAG, "Listener callback: active=$active")
                        val owner = readNavFocusOwnerFromDumpsys()
                        emitIfChanged(owner, trigger = if (active) "user" else "system")
                    }
                },
                CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
            )
            logger.d(TAG, "OnAppFocusChangedListener registered (optimistic)")
        } catch (e: Exception) {
            logger.w(TAG, "addFocusListener failed: ${e.message}")
        }

        // Emit initial snapshot unconditionally so the dashboard knows the state from t=0
        val initialOwner = readNavFocusOwnerFromDumpsys()
        emitSnapshot(initialOwner)

        // Set tracking state so emitIfChanged can detect the next change
        previousOwner = initialOwner
        previousSourceType = classifyOwner(initialOwner)
        logger.i(TAG, "Initial state: owner=${previousOwner ?: "none"}, type=$previousSourceType")

        // Poll loop — fallback for when callbacks don't fire (MIB4 multi-user)
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)
            val owner = readNavFocusOwnerFromDumpsys()
            emitIfChanged(owner, trigger = "system")
        }
    }

    override fun stop() {
        appFocusManager = null
        car?.disconnect()
        car = null
        logger.i(TAG, "Stopped")
    }

    /**
     * Parses `dumpsys car_service` AppFocusService section to find the navigation focus owner.
     *
     * Format:
     * ```
     * **AppFocusService**
     * mActiveAppTypes:{1}
     * ClientInfo{mUid=1301000,mPid=4953,owned={1}}
     * ```
     *
     * If mActiveAppTypes contains "1" (NAV), we find the ClientInfo that owns {1}
     * and resolve its PID to a package name.
     */
    private fun readNavFocusOwnerFromDumpsys(): String? {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "dumpsys car_service | sed -n '/AppFocusService/,/^[*]/p'"),
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (output.isBlank()) {
                logger.d(TAG, "dumpsys AppFocus section empty")
                return null
            }

            parseNavOwnerFromSection(output)
        } catch (e: Exception) {
            logger.d(TAG, "dumpsys car_service failed: ${e.message}")
            null
        }
    }

    private fun parseNavOwnerFromSection(section: String): String? {
        // Check if navigation focus type (1) is active
        val activeTypesMatch = ACTIVE_TYPES_PATTERN.find(section)
        if (activeTypesMatch == null) {
            logger.d(TAG, "No mActiveAppTypes found in section (${section.length} chars)")
            return null
        }
        val activeTypes = activeTypesMatch.groupValues[1]
        if (!activeTypes.split(",").map { it.trim() }.contains("1")) return null

        // Find the ClientInfo that owns navigation focus type 1
        for (match in CLIENT_INFO_PATTERN.findAll(section)) {
            val pid = match.groupValues[2].toIntOrNull() ?: continue
            val ownedTypes = match.groupValues[3]
            if (ownedTypes.split(",").map { it.trim() }.contains("1")) {
                val pkg = resolvePackageForPid(pid)
                logger.d(TAG, "Nav focus owner PID=$pid -> pkg=$pkg")
                return pkg
            }
        }
        logger.d(TAG, "mActiveAppTypes has 1 but no ClientInfo owns it")
        return null
    }

    private fun resolvePackageForPid(pid: Int): String? {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "dumpsys activity processes | grep 'PID #$pid:'"),
            )
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            // Format: "PID #4953: ProcessRecord{hash 4953:com.aptiv.carplay/u13s1000}"
            val match = PID_PACKAGE_PATTERN.find(output)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            logger.d(TAG, "Failed to resolve PID $pid: ${e.message}")
            null
        }
    }

    private fun emitSnapshot(owner: String?) {
        val sourceType = classifyOwner(owner)
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Navigation_FocusSnapshot",
                    "trigger" to "system",
                    "metadata" to mapOf(
                        "currentOwner" to owner,
                        "currentSourceType" to sourceType,
                    ),
                ),
            ),
        )
        logger.i(TAG, "Navigation focus snapshot: owner=${owner ?: "none"}, type=$sourceType")
    }

    private fun emitIfChanged(owner: String?, trigger: String) {
        val sourceType = classifyOwner(owner)

        if (owner == previousOwner && sourceType == previousSourceType) {
            return
        }

        val reason = when {
            previousOwner == null && owner != null -> "gained"
            previousOwner != null && owner == null -> "lost"
            previousOwner != null && owner != null -> "changed"
            else -> "unknown"
        }

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Navigation_FocusChanged",
                    "trigger" to trigger,
                    "metadata" to mapOf(
                        "reason" to reason,
                        "previousOwner" to previousOwner,
                        "currentOwner" to owner,
                        "previousSourceType" to previousSourceType,
                        "currentSourceType" to sourceType,
                    ),
                ),
            ),
        )

        logger.i(
            TAG,
            "Navigation focus: reason=$reason, " +
                "$previousSourceType/${previousOwner ?: "none"} -> $sourceType/${owner ?: "none"}",
        )

        previousOwner = owner
        previousSourceType = sourceType
    }

    companion object {
        private const val TAG = "NavigationCollector"
        private const val POLL_INTERVAL_MS = 5_000L

        private val ACTIVE_TYPES_PATTERN = Regex("""mActiveAppTypes:\{([^}]*)\}""")
        private val CLIENT_INFO_PATTERN = Regex("""ClientInfo\{mUid=(\d+),mPid=(\d+),owned=\{([^}]*)\}\}""")
        // Matches: "PID #4953: ProcessRecord{hash 4953:com.aptiv.carplay/u13s1000}"
        private val PID_PACKAGE_PATTERN = Regex("""ProcessRecord\{[0-9a-f]+ \d+:([^/]+)/""")

        private val CARPLAY_HINTS = listOf("carplay")
        private val ANDROID_AUTO_HINTS = listOf("androidauto", "android.auto")
        private val NATIVE_NAV_HINTS = listOf(
            "mapbox", "here", "nav", "maps", "map", "waze", "tomtom",
        )

        private fun classifyOwner(owner: String?): String {
            if (owner == null) return "none"
            val normalized = owner.lowercase()
            return when {
                CARPLAY_HINTS.any { normalized.contains(it) } -> "carplay"
                ANDROID_AUTO_HINTS.any { normalized.contains(it) } -> "android_auto"
                NATIVE_NAV_HINTS.any { normalized.contains(it) } -> "native"
                else -> "unknown"
            }
        }
    }
}