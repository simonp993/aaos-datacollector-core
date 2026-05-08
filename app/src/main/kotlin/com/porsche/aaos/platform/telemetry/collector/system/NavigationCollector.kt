package com.porsche.aaos.platform.telemetry.collector.system

import android.car.Car
import android.car.CarAppFocusManager
import android.car.CarProjectionManager
import android.car.cluster.ClusterHomeManager
import android.car.projection.ProjectionStatus
import android.content.Context
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Collects system-level navigation app-focus ownership changes.
 *
 * Emits single unified event:
 * - Navigation_FocusChanged: whenever navigation app-focus ownership changes
 *   (app gains/loses focus, or different app acquires focus).
 *
 * Also captures cluster navigation state presence to provide route guidance context,
 * but the primary signal is app-focus ownership change.
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
    private var projectionManager: CarProjectionManager? = null
    private var clusterHomeManager: ClusterHomeManager? = null

    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private var appFocusListener: CarAppFocusManager.OnAppFocusChangedListener? = null
    private var projectionStatusListener: CarProjectionManager.ProjectionStatusListener? = null
    private var clusterNavigationListener: ClusterHomeManager.ClusterNavigationStateListener? = null

    @Volatile
    private var previousOwner: String? = null

    @Volatile
    private var previousSourceType: String? = null

    @Volatile
    private var projectionPackage: String? = null

    @Volatile
    private var hasClusterNavState: Boolean = false

    @Volatile
    private var lastClusterPayloadSize: Int = 0

    override suspend fun start() {
        logger.i(TAG, "Starting navigation monitoring")

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

        connectAppFocus(carInstance)
        connectProjection(carInstance)
        connectClusterNavigation(carInstance)
    }

    override fun stop() {
        try {
            appFocusListener?.let { listener ->
                appFocusManager?.removeFocusListener(listener)
            }
        } catch (e: Exception) {
            logger.d(TAG, "removeFocusListener failed: ${e.message}")
        }

        try {
            projectionStatusListener?.let { listener ->
                projectionManager?.unregisterProjectionStatusListener(listener)
            }
        } catch (e: Exception) {
            logger.d(TAG, "unregisterProjectionStatusListener failed: ${e.message}")
        }

        try {
            clusterNavigationListener?.let { listener ->
                clusterHomeManager?.unregisterClusterNavigationStateListener(listener)
            }
        } catch (e: Exception) {
            logger.d(TAG, "unregisterClusterNavigationStateListener failed: ${e.message}")
        }

        appFocusListener = null
        projectionStatusListener = null
        clusterNavigationListener = null
        appFocusManager = null
        projectionManager = null
        clusterHomeManager = null

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
                if (projectionPackage == activePackage) {
                    return@ProjectionStatusListener
                }
                projectionPackage = activePackage
                logger.d(TAG, "Projection status changed: activePackage=$activePackage")

                val owners = safeGetNavigationOwners()
                emitFocusChanged(owners, trigger = "system")
            }

            manager.registerProjectionStatusListener(requireNotNull(projectionStatusListener))
            logger.i(TAG, "CarProjectionManager listener registered")
        } catch (e: SecurityException) {
            logger.w(TAG, "No permission for projection status monitoring: ${e.message}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to register CarProjectionManager listener: ${e.message}")
        }
    }

    private fun connectAppFocus(carInstance: Car) {
        try {
            val manager = carInstance.getCarManager(Car.APP_FOCUS_SERVICE) as? CarAppFocusManager
            if (manager == null) {
                logger.w(TAG, "CarAppFocusManager unavailable")
                return
            }
            appFocusManager = manager

            appFocusListener = CarAppFocusManager.OnAppFocusChangedListener { appType, active ->
                if (appType != CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION) return@OnAppFocusChangedListener
                val owners = safeGetNavigationOwners()
                emitFocusChanged(owners = owners, trigger = if (active) "user" else "system")
            }

            manager.addFocusListener(
                requireNotNull(appFocusListener),
                CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
            )

            // Emit initial focus state
            val initialOwners = safeGetNavigationOwners()
            emitFocusChanged(owners = initialOwners, trigger = "system")
            logger.i(TAG, "CarAppFocusManager listener registered")
        } catch (e: SecurityException) {
            logger.w(TAG, "No permission for CarAppFocusManager navigation focus: ${e.message}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to register CarAppFocusManager listener: ${e.message}")
        }
    }

    private fun connectClusterNavigation(carInstance: Car) {
        try {
            val manager = carInstance.getCarManager(Car.CLUSTER_HOME_SERVICE) as? ClusterHomeManager
            if (manager == null) {
                logger.w(TAG, "ClusterHomeManager unavailable")
                return
            }
            clusterHomeManager = manager

            clusterNavigationListener = ClusterHomeManager.ClusterNavigationStateListener { stateBytes ->
                val hasState = stateBytes.isNotEmpty()
                val payloadSize = stateBytes.size
                lastClusterPayloadSize = payloadSize

                if (hasClusterNavState == hasState) return@ClusterNavigationStateListener
                hasClusterNavState = hasState
                logger.d(TAG, "Cluster navigation state changed: hasState=$hasState, payloadSize=$payloadSize")
                emitRouteStateChanged(hasState)
            }

            manager.registerClusterNavigationStateListener(
                callbackExecutor,
                requireNotNull(clusterNavigationListener),
            )
            logger.i(TAG, "ClusterHomeManager navigation listener registered")
        } catch (e: SecurityException) {
            logger.w(TAG, "No permission for cluster navigation state monitoring: ${e.message}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to register ClusterHomeManager listener: ${e.message}")
        }
    }

    private fun safeGetNavigationOwners(): List<String> {
        val manager = appFocusManager ?: return emptyList()
        return try {
            manager.getAppTypeOwner(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION)
                .orEmpty()
                .distinct()
                .sorted()
        } catch (e: Exception) {
            logger.d(TAG, "getAppTypeOwner failed: ${e.message}")
            emptyList()
        }
    }

    private fun emitFocusChanged(owners: List<String>, trigger: String) {
        val inferred = inferSource(owners, projectionPackage)
        val currentOwner = inferred.owner
        val currentSourceType = inferred.sourceType

        // Only emit if something actually changed
        if (previousOwner == currentOwner && previousSourceType == currentSourceType) {
            return
        }

        // Determine the reason: gained focus, lost focus, or changed owner
        val reason = when {
            previousOwner == null && currentOwner != null -> "gained"
            previousOwner != null && currentOwner == null -> "lost"
            previousOwner != null && currentOwner != null -> "changed"
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
                        "currentOwner" to currentOwner,
                        "previousSourceType" to previousSourceType,
                        "currentSourceType" to currentSourceType,
                        "projectionPackage" to projectionPackage,
                        "hasClusterNavigationState" to hasClusterNavState,
                        "clusterPayloadSizeBytes" to lastClusterPayloadSize,
                    ),
                ),
            ),
        )

        logger.i(
            TAG,
            "Navigation focus changed: reason=$reason, " +
                "${previousSourceType ?: "none"}/${previousOwner ?: "null"} -> " +
                "$currentSourceType/${currentOwner ?: "null"}",
        )

        previousOwner = currentOwner
        previousSourceType = currentSourceType
    }

    private fun emitRouteStateChanged(routeActive: Boolean) {
        val owners = safeGetNavigationOwners()
        val inferred = inferSource(owners, projectionPackage)

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Navigation_RouteStateChanged",
                    "trigger" to "system",
                    "metadata" to mapOf(
                        "routeActive" to routeActive,
                        "owner" to inferred.owner,
                        "sourceType" to inferred.sourceType,
                        "clusterPayloadSizeBytes" to lastClusterPayloadSize,
                    ),
                ),
            ),
        )

        logger.i(
            TAG,
            "Navigation route state changed: routeActive=$routeActive, " +
                "sourceType=${inferred.sourceType}, owner=${inferred.owner ?: "null"}, " +
                "clusterPayloadSizeBytes=$lastClusterPayloadSize",
        )
    }

    companion object {
        private const val TAG = "NavigationCollector"

        private val NOISE_PACKAGES = setOf(
            "android",
            "com.android.car",
            "com.android.car.settings",
            "com.android.providers.settings",
            "com.android.location.fused",
        )

        private val CARPLAY_HINTS = listOf("carplay")
        private val ANDROID_AUTO_HINTS = listOf("androidauto", "android.auto")
        private val NATIVE_NAV_HINTS = listOf("nav", "maps", "map", "waze", "tomtom", "here")

        private fun inferSource(owners: List<String>, projectionPackage: String?): SourceInference {
            val ownersFiltered = owners.filterNot { it in NOISE_PACKAGES }

            if (projectionPackage != null) {
                return SourceInference(
                    owner = projectionPackage,
                    sourceType = when {
                        containsAny(projectionPackage, CARPLAY_HINTS) -> "carplay"
                        containsAny(projectionPackage, ANDROID_AUTO_HINTS) -> "android_auto"
                        else -> "projection"
                    },
                )
            }

            val candidate = ownersFiltered.firstOrNull {
                containsAny(it, CARPLAY_HINTS + ANDROID_AUTO_HINTS + NATIVE_NAV_HINTS)
            } ?: ownersFiltered.firstOrNull()

            val sourceType = when {
                candidate == null -> "none"
                containsAny(candidate, CARPLAY_HINTS) -> "carplay"
                containsAny(candidate, ANDROID_AUTO_HINTS) -> "android_auto"
                containsAny(candidate, NATIVE_NAV_HINTS) -> "native"
                else -> "unknown"
            }

            return SourceInference(owner = candidate, sourceType = sourceType)
        }

        private fun containsAny(value: String, hints: List<String>): Boolean {
            val normalized = value.lowercase()
            return hints.any { normalized.contains(it) }
        }
    }

    private data class SourceInference(
        val owner: String?,
        val sourceType: String,
    )
}