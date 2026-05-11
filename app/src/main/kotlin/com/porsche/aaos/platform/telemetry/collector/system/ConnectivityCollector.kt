package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            activeNetworks.add(network)
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Connectivity_Available",
                        "trigger" to "system",
                        "metadata" to mapOf(
                            "network" to network.toString(),
                            "transport" to getTransport(network),
                        ),
                    ),
                ),
            )
        }

        override fun onLost(network: Network) {
            activeNetworks.remove(network)
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Connectivity_Lost",
                        "trigger" to "system",
                        "metadata" to mapOf(
                            "network" to network.toString(),
                            "transport" to getTransport(network),
                        ),
                    ),
                ),
            )
        }
    }

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting connectivity monitoring (${SAMPLE_INTERVAL_MS / 1000}s sample, ${FLUSH_INTERVAL_MS / 1000}s flush)")

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val request = NetworkRequest.Builder().build()
        cm.registerNetworkCallback(request, networkCallback)

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
        connectivityManager = null
        wifiManager = null
        telephonyManager = null
        activeNetworks.clear()
        samplesByTransport.clear()
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

    private fun resolveTransport(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "unknown"
    }

    @Suppress("DEPRECATION")
    private fun isTetheringActive(): Boolean {
        return try {
            val wm = wifiManager ?: return false
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wm) as Boolean
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "ConnectivityCollector"
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val FLUSH_INTERVAL_MS = 60_000L
        private const val SAMPLES_PER_BATCH = 12 // 12 × 5s = 60s
        private const val STAGGER_DELAY_MS = 2_000L
    }
}
