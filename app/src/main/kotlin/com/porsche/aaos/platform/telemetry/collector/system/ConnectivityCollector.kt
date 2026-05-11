package com.porsche.aaos.platform.telemetry.collector.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class ConnectivityCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Connectivity"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false
    private var connectivityManager: ConnectivityManager? = null
    private var wifiManager: WifiManager? = null
    private var telephonyManager: TelephonyManager? = null

    // Active networks tracked via callbacks
    private val activeNetworks = mutableSetOf<Network>()

    // Batched samples per transport: transport → list of [timestampMillis, dbm, downKbps, upKbps]
    private val samplesByTransport = mutableMapOf<String, MutableList<List<Any>>>()

    // WiFi detail tracking per network (enriches Available/Lost events)
    // Limitation: SSID/BSSID require runtime ACCESS_FINE_LOCATION + location enabled.
    // Without it, Android returns empty SSID and dummy BSSID (02:00:00:00:00:00).
    private val wifiSnapshots = mutableMapOf<Network, Map<String, Any>>()

    // Tethering state tracking
    private var lastTetheringActive = false

    // Cache transport type per network — NetworkCapabilities may already be null in onLost
    private val networkTransports = mutableMapOf<Network, String>()

    // Current primary transport for change detection (priority: wifi > cellular > ethernet > bt > vpn)
    @Volatile
    private var currentTransport: String = "none"

    // Debounce handler for TransportChanged — suppresses intermediate states
    private val handler = Handler(Looper.getMainLooper())
    private var pendingTransportChange: Runnable? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback(
        FLAG_INCLUDE_LOCATION_INFO,
    ) {
        override fun onAvailable(network: Network) {
            activeNetworks.add(network)
            val transport = getTransport(network)
            networkTransports[network] = transport
            // For WiFi, defer TransportChanged until onCapabilitiesChanged provides SSID
            if (transport != "wifi") checkTransportChanged()
        }

        override fun onLost(network: Network) {
            activeNetworks.remove(network)
            checkTransportChanged()
            // Clean up after transport change detection
            networkTransports.remove(network)
            wifiSnapshots.remove(network)
        }

        @Suppress("DEPRECATION")
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
            val wi = caps.transportInfo as? WifiInfo ?: return
            val snap = buildWifiSnapshot(wi)
            val prev = wifiSnapshots[network]
            wifiSnapshots[network] = snap
            if (prev == null) {
                // First real WiFi details — now emit deferred TransportChanged with SSID
                checkTransportChanged()
                return
            }
            // Emit only on BSSID change (= roaming)
            if (prev["bssid"] == snap["bssid"]) return
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Connectivity_WifiRoam",
                        "trigger" to "system",
                        "metadata" to snap,
                    ),
                ),
            )
        }
    }

    private val tetheringReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_WIFI_AP_STATE_CHANGED -> handleWifiApState(intent)
                ACTION_WIFI_STATE_CHANGED -> handleWifiState(intent)
            }
        }
    }

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting connectivity monitoring")

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val request = NetworkRequest.Builder().build()
        cm.registerNetworkCallback(request, networkCallback)

        // Register WiFi AP + WiFi state broadcasts
        val tetherFilter = IntentFilter().apply {
            addAction(ACTION_WIFI_AP_STATE_CHANGED)
            addAction(ACTION_WIFI_STATE_CHANGED)
        }
        context.registerReceiver(tetheringReceiver, tetherFilter, Context.RECEIVER_EXPORTED)
        lastTetheringActive = isTetheringActive()

        // Stagger initial delay to spread flush bursts across collectors
        delay(STAGGER_DELAY_MS)

        // Poll + flush loop
        var sampleCount = 0
        while (running && coroutineContext.isActive) {
            pollActiveNetworks()
            sampleCount++

            if (sampleCount >= SAMPLES_PER_BATCH) {
                flush()
                sampleCount = 0
            }
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        connectivityManager?.unregisterNetworkCallback(networkCallback)
        try {
            context.unregisterReceiver(tetheringReceiver)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.d(TAG, "Receiver not registered: ${e.message}")
        }
        connectivityManager = null
        wifiManager = null
        telephonyManager = null
        activeNetworks.clear()
        samplesByTransport.clear()
        wifiSnapshots.clear()
        networkTransports.clear()
        currentTransport = "none"
        pendingTransportChange?.let { handler.removeCallbacks(it) }
        pendingTransportChange = null
        lastTetheringActive = false
        logger.i(TAG, "Stopped")
    }

    private fun pollActiveNetworks() {
        val cm = connectivityManager ?: return
        val operatorName = telephonyManager?.networkOperatorName.orEmpty()
        for (network in activeNetworks.toList()) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val transport = resolveTransport(caps)
            val sample = listOf(
                System.currentTimeMillis(),
                caps.signalStrength,
                caps.linkDownstreamBandwidthKbps,
                caps.linkUpstreamBandwidthKbps,
                operatorName,
            )
            samplesByTransport.getOrPut(transport) { mutableListOf() }.add(sample)
        }
    }

    private fun flush() {
        val tethering = isTetheringActive()

        for ((transport, samples) in samplesByTransport) {
            if (samples.isEmpty()) continue
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Connectivity_SignalStrength",
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "transport" to transport,
                            "tetheringActive" to tethering,
                            "sampleSchema" to listOf(
                                "timestampMillis",
                                "signalStrengthDbm",
                                "maxDownstreamBandwidthKbps",
                                "maxUpstreamBandwidthKbps",
                                "operatorName",
                            ),
                            "samples" to samples.toList(),
                        ),
                    ),
                ),
            )
        }
        samplesByTransport.clear()
    }

    private fun getTransport(network: Network): String {
        val caps = connectivityManager?.getNetworkCapabilities(network) ?: return "unknown"
        return resolveTransport(caps)
    }

    private fun checkTransportChanged() {
        // Debounce: cancel any pending emit, schedule a new one after TRANSPORT_DEBOUNCE_MS.
        // This collapses rapid wifi→none→ethernet into a single wifi→ethernet event.
        pendingTransportChange?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { emitTransportChanged() }
        pendingTransportChange = runnable
        handler.postDelayed(runnable, TRANSPORT_DEBOUNCE_MS)
    }

    private fun emitTransportChanged() {
        pendingTransportChange = null
        val newTransport = resolvePrimaryTransport()
        if (newTransport == currentTransport) return
        val previous = currentTransport
        currentTransport = newTransport
        val metadata = buildMap<String, Any> {
            put("previous", previous)
            put("current", newTransport)
            if (newTransport == "wifi") {
                for (net in activeNetworks) {
                    wifiSnapshots[net]?.let { putAll(it); return@buildMap }
                }
            }
        }
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Connectivity_TransportChanged",
                    "trigger" to "system",
                    "metadata" to metadata,
                ),
            ),
        )
        logger.d(TAG, "Transport: $previous \u2192 $newTransport")
    }

    private fun resolvePrimaryTransport(): String {
        val transports = activeNetworks.mapNotNull { networkTransports[it] }.toSet()
        return when {
            "wifi" in transports -> "wifi"
            "cellular" in transports -> "cellular"
            "ethernet" in transports -> "ethernet"
            "bluetooth" in transports -> "bluetooth"
            "vpn" in transports -> "vpn"
            else -> "none"
        }
    }

    private fun resolveTransport(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "unknown"
    }

    @Suppress("DEPRECATION")
    private fun buildWifiSnapshot(wi: WifiInfo): Map<String, Any> = buildMap {
        put("ssid", wi.ssid?.removeSurrounding("\"") ?: "?")
        put("bssid", wi.bssid ?: "?")
        put("freq", wi.frequency)
        put("linkSpeed", wi.linkSpeed)
    }

    @Suppress("DEPRECATION")
    private fun isTetheringActive(): Boolean {
        return try {
            val wm = wifiManager ?: return false
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wm) as Boolean
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.d(TAG, "isWifiApEnabled unavailable: ${e.message}")
            false
        }
    }

    private fun handleWifiApState(intent: Intent) {
        val state = intent.getIntExtra(EXTRA_WIFI_AP_STATE, WIFI_AP_STATE_DISABLED)
        // Only emit on final states — ignore transient enabling/disabling
        val enabled = when (state) {
            WIFI_AP_STATE_ENABLED -> true
            WIFI_AP_STATE_DISABLED -> false
            else -> return
        }
        if (enabled == lastTetheringActive) return
        lastTetheringActive = enabled
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Hotspot_StateChanged",
                    "trigger" to "system",
                    "metadata" to mapOf("enabled" to enabled),
                ),
            ),
        )
        logger.d(TAG, "Hotspot: ${if (enabled) "on" else "off"}")
    }

    private fun handleWifiState(intent: Intent) {
        val state = intent.getIntExtra(
            WifiManager.EXTRA_WIFI_STATE,
            WifiManager.WIFI_STATE_UNKNOWN,
        )
        // Only emit on final states — ignore transient enabling/disabling
        val enabled = when (state) {
            WifiManager.WIFI_STATE_ENABLED -> true
            WifiManager.WIFI_STATE_DISABLED -> false
            else -> return
        }
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Wifi_StateChanged",
                    "trigger" to "system",
                    "metadata" to mapOf("enabled" to enabled),
                ),
            ),
        )
        logger.d(TAG, "WiFi: ${if (enabled) "on" else "off"}")
    }

    companion object {
        private const val TAG = "ConnectivityCollector"
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val SAMPLES_PER_BATCH = 12 // 12 × 5s = 60s
        private const val STAGGER_DELAY_MS = 2_000L
        private const val TRANSPORT_DEBOUNCE_MS = 300L

        // WiFi AP state constants (hidden in WifiManager)
        private const val WIFI_AP_STATE_DISABLED = 11
        private const val WIFI_AP_STATE_ENABLED = 13

        // Broadcast actions
        private const val ACTION_WIFI_AP_STATE_CHANGED =
            "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val ACTION_WIFI_STATE_CHANGED =
            "android.net.wifi.WIFI_STATE_CHANGED"
        private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    }
}
