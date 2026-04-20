package com.porsche.datacollector.collector.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.datacollector.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ConnectivityCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Connectivity"

    private var connectivityManager: ConnectivityManager? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            telemetry.send(
                TelemetryEvent(
                    eventId = "connectivity.capabilities",
                    payload = mapOf(
                        "hasWifi" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        "hasCellular" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                        "hasEthernet" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                        "hasBluetooth" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH),
                        "hasVpn" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        "downstreamBandwidthKbps" to capabilities.linkDownstreamBandwidthKbps,
                        "upstreamBandwidthKbps" to capabilities.linkUpstreamBandwidthKbps,
                    ),
                ),
            )
        }

        override fun onLost(network: Network) {
            telemetry.send(
                TelemetryEvent(
                    eventId = "connectivity.lost",
                    payload = mapOf("network" to network.toString()),
                ),
            )
        }
    }

    override suspend fun start() {
        logger.i(TAG, "Starting connectivity monitoring")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
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
