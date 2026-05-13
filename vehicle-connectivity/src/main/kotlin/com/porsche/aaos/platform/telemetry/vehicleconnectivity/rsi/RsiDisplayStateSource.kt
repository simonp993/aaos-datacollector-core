package com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi

import android.content.Context
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import de.esolutions.fw.android.rsi.client.rx.IRsiAdmin
import de.esolutions.fw.android.rsi.client.rx.RsiAdmin
import de.esolutions.fw.rudi.mcp.popups.MCP_PopupsApi.PopupsForDisplayEnum
import de.esolutions.fw.rudi.mcp.popups.registry.MCP_PopupsTracker
import de.esolutions.fw.rudi.viwi.service.hidservice.v1.registry.HIDServiceTracker
import de.esolutions.fw.rudi.viwi.service.instrumentclusterconfiguration.v2.registry.InstrumentClusterConfigurationTracker
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Observes display on/off state and brightness via RSI typed RUDI proxies:
 * - MCP_Popups DISPLAY_OFF_POPUP for center, passenger, hud on/off
 * - HIDService valueControls for center/passenger/console brightness offset (-5..5)
 * - InstrumentClusterConfiguration valueControls for IC brightness (-5..5)
 *
 * Uses per-element `getPopup()` / `retrieveValueControlObject()` for reliable
 * ongoing updates. The bulk `subscribe*Objects()` pattern only delivers the
 * initial FullResourceUpdate on MIB4.
 */
class RsiDisplayStateSource(
    private val context: Context,
    private val logger: Logger,
) : DisplayStateSource {

    private val rsiAdmin: IRsiAdmin by lazy { RsiAdmin.start(context) }
    private val disposables = CompositeDisposable()
    private val previousStates = ConcurrentHashMap<String, String>()
    private val previousBrightness = ConcurrentHashMap<String, Int>()

    override fun observeDisplayStates(): Flow<DisplayStateEvent> =
        observePopupDisplays()

    override fun observeDisplayBrightness(): Flow<DisplayBrightnessEvent> = merge(
        observeHidServiceBrightness(),
        observeIcBrightness(),
    )

    override fun start() {
        logger.d(TAG, "DisplayStateSource started")
    }

    override fun stop() {
        disposables.clear()
        previousStates.clear()
        previousBrightness.clear()
        logger.d(TAG, "DisplayStateSource stopped")
    }

    /**
     * Subscribes to DISPLAY_OFF_POPUP for center, passenger, and HUD displays
     * via MCP_PopupsTracker typed RUDI proxy.
     *
     * Uses per-element `getPopup()` which reliably pushes ongoing updates.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun observePopupDisplays(): Flow<DisplayStateEvent> = callbackFlow {
        val localDisposables = CompositeDisposable()

        val popupDisposable = MCP_PopupsTracker.connectMCP_Popups(rsiAdmin)
            .subscribe(
                { optService ->
                    if (!optService.isPresent) {
                        logger.w(TAG, "MCP_Popups service not available")
                        return@subscribe
                    }
                    val api = optService.get().instance.main()
                    subscribePopupDisplay(api, PopupsForDisplayEnum.DRIVER_DISPLAY, DISPLAY_CENTER, localDisposables)
                    subscribePopupDisplay(api, PopupsForDisplayEnum.CO_DRIVER_DISPLAY, DISPLAY_PASSENGER, localDisposables)
                    subscribePopupDisplay(api, PopupsForDisplayEnum.HUD_DISPLAY, DISPLAY_HUD, localDisposables)
                    subscribePopupDisplay(api, PopupsForDisplayEnum.REAR_DISPLAY, DISPLAY_FOND, localDisposables)
                },
                { error ->
                    logger.e(TAG, "MCP_Popups connection error: ${error.message}")
                },
            )
        localDisposables.add(popupDisposable)
        disposables.add(popupDisposable)

        awaitClose {
            localDisposables.dispose()
        }
    }

    /**
     * Subscribes to a single display's DISPLAY_OFF_POPUP and emits state events.
     *
     * The popup `requested` field indicates display-off intent:
     * - requested=true → display is OFF
     * - requested=false → display is ON
     */
    @Suppress("TooGenericExceptionCaught")
    private fun kotlinx.coroutines.channels.ProducerScope<DisplayStateEvent>.subscribePopupDisplay(
        api: de.esolutions.fw.rudi.mcp.popups.MCP_PopupsApi,
        displayEnum: PopupsForDisplayEnum,
        displayName: String,
        localDisposables: CompositeDisposable,
    ) {
        val popupDisposable = api.getPopup(displayEnum, POPUP_NAME_DISPLAY_OFF)
            .subscribe(
                { popup ->
                    val ts = System.currentTimeMillis()
                    val state = if (popup.requested) STATE_OFF else STATE_ON
                    val trigger = popup.requestData.orElse(null)
                    val previous = previousStates.put(displayName, state)

                    if (previous != null && previous == state) return@subscribe

                    val event = DisplayStateEvent(
                        display = displayName,
                        state = state,
                        previousState = previous,
                        trigger = trigger,
                        timestamp = ts,
                    )
                    logger.d(TAG, "Display state: $displayName → $state (trigger=$trigger)")
                    trySend(event)
                },
                { error ->
                    logger.e(TAG, "Error subscribing to $displayName popup: ${error.message}")
                },
            )
        localDisposables.add(popupDisposable)
    }

    // --- Brightness via HIDService (center, passenger, console) ---

    /**
     * Subscribes to individual HIDService brightness controls by UUID.
     *
     * Uses per-element `retrieveValueControlObject(uuid)` instead of bulk
     * `subscribeValueControlObjects()` — the bulk subscribe only delivers the
     * initial FullResourceUpdate on MIB4 and does not push ongoing changes.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun observeHidServiceBrightness(): Flow<DisplayBrightnessEvent> = callbackFlow {
        val localDisposables = CompositeDisposable()

        val serviceDisposable = HIDServiceTracker.connectHIDService(rsiAdmin)
            .subscribe(
                { optService ->
                    if (!optService.isPresent) {
                        logger.w(TAG, "HIDService not available")
                        return@subscribe
                    }
                    val valueControls = optService.get().instance.valueControls()

                    for ((uuid, display) in BRIGHTNESS_UUID_MAP) {
                        val sub = valueControls.retrieveValueControlObject(uuid)
                            .subscribe(
                                { obj ->
                                    handleHidBrightness(obj, display)?.let { trySend(it) }
                                },
                                { error ->
                                    logger.e(TAG, "HIDService brightness error for $display: ${error.message}")
                                },
                            )
                        localDisposables.add(sub)
                    }
                },
                { error ->
                    logger.e(TAG, "HIDService connection error: ${error.message}")
                },
            )
        localDisposables.add(serviceDisposable)
        disposables.add(serviceDisposable)

        awaitClose { localDisposables.dispose() }
    }

    private fun handleHidBrightness(
        obj: de.esolutions.fw.rudi.viwi.service.hidservice.v1.ValueControlObject,
        display: String,
    ): DisplayBrightnessEvent? {
        val currentValue = obj.currentValue.orElse(null) ?: return null
        val minValue = obj.minValue.orElse(BRIGHTNESS_MIN)
        val maxValue = obj.maxValue.orElse(BRIGHTNESS_MAX)
        val ts = System.currentTimeMillis()
        val previous = previousBrightness.put(display, currentValue)
        if (previous != null && previous == currentValue) return null

        logger.d(TAG, "Brightness: $display = $currentValue (was $previous)")
        return DisplayBrightnessEvent(
            display = display,
            value = currentValue,
            previousValue = previous,
            minValue = minValue,
            maxValue = maxValue,
            timestamp = ts,
        )
    }

    // --- Brightness via InstrumentClusterConfiguration (IC) ---

    /**
     * Subscribes to IC brightness by UUID.
     * IC brightness uses Float (-5.0..5.0) which we round to Int for consistency.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun observeIcBrightness(): Flow<DisplayBrightnessEvent> = callbackFlow {
        val localDisposables = CompositeDisposable()

        val serviceDisposable = InstrumentClusterConfigurationTracker
            .connectInstrumentClusterConfiguration(rsiAdmin)
            .subscribe(
                { optService ->
                    if (!optService.isPresent) {
                        logger.w(TAG, "InstrumentClusterConfiguration not available")
                        return@subscribe
                    }
                    val valueControls = optService.get().instance.valueControls()

                    val sub = valueControls.retrieveValueControlObject(IC_BRIGHTNESS_UUID)
                        .subscribe(
                            { obj ->
                                handleIcBrightness(obj)?.let { trySend(it) }
                            },
                            { error ->
                                logger.e(TAG, "IC brightness error: ${error.message}")
                            },
                        )
                    localDisposables.add(sub)
                },
                { error ->
                    logger.e(TAG, "InstrumentClusterConfiguration connection error: ${error.message}")
                },
            )
        localDisposables.add(serviceDisposable)
        disposables.add(serviceDisposable)

        awaitClose { localDisposables.dispose() }
    }

    private fun handleIcBrightness(
        obj: de.esolutions.fw.rudi.viwi.service.instrumentclusterconfiguration.v2.ValueControlObject,
    ): DisplayBrightnessEvent? {
        val currentValue = obj.currentValue.orElse(null)?.toInt() ?: return null
        val minValue = obj.minValue.orElse(BRIGHTNESS_MIN.toFloat()).toInt()
        val maxValue = obj.maxValue.orElse(BRIGHTNESS_MAX.toFloat()).toInt()
        val ts = System.currentTimeMillis()
        val previous = previousBrightness.put(DISPLAY_CLUSTER, currentValue)
        if (previous != null && previous == currentValue) return null

        logger.d(TAG, "IC brightness: $currentValue (was $previous)")
        return DisplayBrightnessEvent(
            display = DISPLAY_CLUSTER,
            value = currentValue,
            previousValue = previous,
            minValue = minValue,
            maxValue = maxValue,
            timestamp = ts,
        )
    }

    companion object {
        private const val TAG = "RsiDisplayStateSource"

        // Popup resource name — confirmed on MIB4 Cayenne via logcat
        private const val POPUP_NAME_DISPLAY_OFF = "DISPLAY_OFF_POPUP"

        // Logical display names
        private const val DISPLAY_CENTER = "center"
        private const val DISPLAY_PASSENGER = "passenger"
        private const val DISPLAY_HUD = "hud"
        private const val DISPLAY_FOND = "fond"
        private const val DISPLAY_CLUSTER = "cluster"
        private const val DISPLAY_CONSOLE = "console"

        // States
        private const val STATE_ON = "on"
        private const val STATE_OFF = "off"

        private const val BRIGHTNESS_MIN = -5
        private const val BRIGHTNESS_MAX = 5

        /**
         * Known HIDService brightnessOffset UUIDs → display names.
         * Deterministic v5 UUIDs derived from RSI resource paths, stable on MIB4.
         */
        @Suppress("MaxLineLength")
        private val BRIGHTNESS_UUID_MAP = mapOf(
            UUID.fromString("0a3ecbf3-bb09-53a1-8834-2ba90685dde5") to DISPLAY_CENTER,
            UUID.fromString("5939f78f-0024-599a-9028-58ba8f93b7a0") to DISPLAY_PASSENGER,
            UUID.fromString("d2f5f0e6-30b2-5757-841c-c4d3c2d66f24") to DISPLAY_CONSOLE,
        )

        /**
         * IC brightness UUID — confirmed on MIB4 Cayenne via logcat.
         */
        private val IC_BRIGHTNESS_UUID: UUID =
            UUID.fromString("fbd54570-443a-5ab3-9d82-c7456f5c3ed0")
    }
}
