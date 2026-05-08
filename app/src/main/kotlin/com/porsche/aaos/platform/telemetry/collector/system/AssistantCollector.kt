package com.porsche.aaos.platform.telemetry.collector.system

import android.car.Car
import android.car.CarAppFocusManager
import android.car.CarProjectionManager
import android.content.Context
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import javax.inject.Inject

class AssistantCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Assistant"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private var car: Car? = null
    private var appFocusManager: CarAppFocusManager? = null
    private var projectionManager: CarProjectionManager? = null

    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private var appFocusListener: CarAppFocusManager.OnAppFocusChangedListener? = null
    private var projectionStatusListener: CarProjectionManager.ProjectionStatusListener? = null

    @Volatile
    private var projectionPackage: String? = null

    @Volatile
    private var activePackage: String? = null

    @Volatile
    private var activeStartedAtMillis: Long? = null

    override suspend fun start() {
        logger.i(TAG, "Starting assistant monitoring")

        val carInstance = try {
            Car.createCar(context)
        } catch (e: Exception) {
            logger.e(TAG, "Car.createCar failed: ${e.message}")
            null
        }

        if (carInstance == null) {
            logger.w(TAG, "Car.createCar returned null; assistant monitoring unavailable")
            return
        }
        car = carInstance

        connectProjection(carInstance)
        connectVoiceCommandFocus(carInstance)
    }

    override fun stop() {
        val currentActive = activePackage
        if (currentActive != null) {
            emitSessionEnded(
                assistantPackage = currentActive,
                endedAtMillis = System.currentTimeMillis(),
                trigger = "system",
                reason = "collector_stopped",
            )
        }

        try {
            appFocusListener?.let { appFocusManager?.removeFocusListener(it) }
        } catch (e: Exception) {
            logger.d(TAG, "removeFocusListener failed: ${e.message}")
        }

        try {
            projectionStatusListener?.let { projectionManager?.unregisterProjectionStatusListener(it) }
        } catch (e: Exception) {
            logger.d(TAG, "unregisterProjectionStatusListener failed: ${e.message}")
        }

        appFocusListener = null
        projectionStatusListener = null
        appFocusManager = null
        projectionManager = null

        car?.disconnect()
        car = null

        callbackExecutor.shutdownNow()
        logger.i(TAG, "Stopped")
    }

    private fun connectProjection(carInstance: Car) {
        try {
            val manager = carInstance.getCarManager(Car.PROJECTION_SERVICE) as? CarProjectionManager
            if (manager == null) {
                logger.w(TAG, "CarProjectionManager unavailable")
                return
            }
            projectionManager = manager

            projectionStatusListener = CarProjectionManager.ProjectionStatusListener { _, _, statuses ->
                val active = statuses.firstOrNull { it.isActive }
                val activePackage = active?.packageName
                if (projectionPackage == activePackage) return@ProjectionStatusListener
                projectionPackage = activePackage
                logger.d(TAG, "Projection status changed: activePackage=$activePackage")
            }

            manager.registerProjectionStatusListener(requireNotNull(projectionStatusListener))
            logger.i(TAG, "CarProjectionManager listener registered")
        } catch (e: SecurityException) {
            logger.w(TAG, "No permission for projection status monitoring: ${e.message}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to register CarProjectionManager listener: ${e.message}")
        }
    }

    private fun connectVoiceCommandFocus(carInstance: Car) {
        try {
            val manager = carInstance.getCarManager(Car.APP_FOCUS_SERVICE) as? CarAppFocusManager
            if (manager == null) {
                logger.w(TAG, "CarAppFocusManager unavailable")
                return
            }
            appFocusManager = manager

            appFocusListener = CarAppFocusManager.OnAppFocusChangedListener { appType, active ->
                if (appType != CarAppFocusManager.APP_FOCUS_TYPE_VOICE_COMMAND) return@OnAppFocusChangedListener
                val owners = safeGetVoiceOwners()
                handleVoiceFocusChanged(owners = owners, focusActive = active)
            }

            manager.addFocusListener(
                requireNotNull(appFocusListener),
                CarAppFocusManager.APP_FOCUS_TYPE_VOICE_COMMAND,
            )
            logger.i(TAG, "CarAppFocusManager VOICE_COMMAND listener registered")
        } catch (e: SecurityException) {
            logger.w(TAG, "No permission for CarAppFocusManager voice command focus: ${e.message}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to register CarAppFocusManager VOICE_COMMAND listener: ${e.message}")
        }
    }

    private fun safeGetVoiceOwners(): List<String> {
        val manager = appFocusManager ?: return emptyList()
        return try {
            manager.getAppTypeOwner(CarAppFocusManager.APP_FOCUS_TYPE_VOICE_COMMAND)
                .orEmpty()
                .distinct()
                .sorted()
        } catch (e: Exception) {
            logger.d(TAG, "getAppTypeOwner(VOICE_COMMAND) failed: ${e.message}")
            emptyList()
        }
    }

    private fun handleVoiceFocusChanged(owners: List<String>, focusActive: Boolean) {
        val now = System.currentTimeMillis()

        if (!focusActive || owners.isEmpty()) {
            // Voice command focus released
            val previous = activePackage ?: return
            emitSessionEnded(
                assistantPackage = previous,
                endedAtMillis = now,
                trigger = "user",
                reason = "focus_released",
            )
            activePackage = null
            activeStartedAtMillis = null
            return
        }

        // Resolve package: prefer projection package if it matches an owner, else first owner
        val resolvedPackage = owners.firstOrNull { it == projectionPackage } ?: owners.first()

        if (resolvedPackage == activePackage) return

        // If something was already active, end it first
        if (activePackage != null) {
            emitSessionEnded(
                assistantPackage = activePackage,
                endedAtMillis = now,
                trigger = "user",
                reason = "focus_changed",
            )
        }

        activePackage = resolvedPackage
        activeStartedAtMillis = now

        val inferred = inferSource(resolvedPackage, projectionPackage)

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Assistant_SessionStarted",
                    "trigger" to "user",
                    "metadata" to mapOf(
                        "assistantPackage" to resolvedPackage,
                        "sourceType" to inferred.sourceType,
                        "assistantName" to inferred.assistantName,
                        "projectionPackage" to projectionPackage,
                        "startedAtMillis" to now,
                    ),
                ),
            ),
        )

        logger.i(
            TAG,
            "Assistant session started: package=$resolvedPackage, " +
                "sourceType=${inferred.sourceType}, assistantName=${inferred.assistantName}",
        )
    }

    private fun emitSessionEnded(
        assistantPackage: String?,
        endedAtMillis: Long,
        trigger: String,
        reason: String,
    ) {
        val startedAt = activeStartedAtMillis
        val durationMillis = if (startedAt != null) (endedAtMillis - startedAt).coerceAtLeast(0L) else null
        val inferred = if (assistantPackage != null) inferSource(assistantPackage, projectionPackage) else null

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Assistant_SessionEnded",
                    "trigger" to trigger,
                    "metadata" to mapOf(
                        "assistantPackage" to assistantPackage,
                        "sourceType" to (inferred?.sourceType ?: "unknown"),
                        "assistantName" to inferred?.assistantName,
                        "projectionPackage" to projectionPackage,
                        "startedAtMillis" to startedAt,
                        "endedAtMillis" to endedAtMillis,
                        "durationMillis" to durationMillis,
                        "reason" to reason,
                    ),
                ),
            ),
        )

        logger.i(
            TAG,
            "Assistant session ended: package=${assistantPackage ?: "null"}, " +
                "sourceType=${inferred?.sourceType ?: "unknown"}, " +
                "durationMillis=${durationMillis ?: -1}, reason=$reason",
        )
    }

    companion object {
        private const val TAG = "AssistantCollector"

        private val CARPLAY_HINTS = listOf("carplay")
        private val ANDROID_AUTO_HINTS = listOf("androidauto", "android.auto")

        private fun inferSource(pkg: String, projectionPackage: String?): SourceInference {
            val normalized = pkg.lowercase()

            // Projection package takes priority for source classification
            val effectivePkg = if (projectionPackage != null &&
                pkg == projectionPackage
            ) projectionPackage else pkg

            val sourceType = when {
                CARPLAY_HINTS.any { effectivePkg.lowercase().contains(it) } -> "carplay"
                ANDROID_AUTO_HINTS.any { effectivePkg.lowercase().contains(it) } -> "android_auto"
                normalized.contains("google") || normalized.contains("assistant") -> "native"
                else -> "unknown"
            }

            val assistantName = when (sourceType) {
                "carplay" -> "Siri"
                "android_auto" -> "Google Assistant"
                "native" -> "Google Assistant"
                else -> null
            }

            return SourceInference(sourceType = sourceType, assistantName = assistantName)
        }
    }

    private data class SourceInference(
        val sourceType: String,
        val assistantName: String?,
    )
}