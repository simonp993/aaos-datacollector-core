package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ConnectivityCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Connectivity"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private var connectivityManager: ConnectivityManager? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Connectivity_CapabilitiesChanged",
                        "trigger" to "system",
                        "metadata" to mapOf(
                            "hasWifi" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                            "hasCellular" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                            "hasEthernet" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                            "hasBluetooth" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH),
                            "hasVpn" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                            "downstreamBandwidthKbps" to capabilities.linkDownstreamBandwidthKbps,
                            "upstreamBandwidthKbps" to capabilities.linkUpstreamBandwidthKbps,
                        ),
                    ),
                ),
            )
        }

        override fun onAvailable(network: Network) {
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Connectivity_NetworkAvailable",
                        "trigger" to "system",
                        "metadata" to mapOf("network" to network.toString()),
                    ),
                ),
            )
        }

        override fun onLost(network: Network) {
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Connectivity_NetworkLost",
                        "trigger" to "system",
                        "metadata" to mapOf("network" to network.toString()),
                    ),
                ),
            )
        }
    }

    override suspend fun start() {
        logger.i(TAG, "Starting connectivity monitoring")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val request = NetworkRequest.Builder().build()
        cm.registerNetworkCallback(request, networkCallback)
    }

    override fun stop() {
        connectivityManager?.unregisterNetworkCallback(networkCallback)
        connectivityManager = null
        logger.i(TAG, "Stopped")
    }

    companion object {
        private const val TAG = "ConnectivityCollector"
    }
}
