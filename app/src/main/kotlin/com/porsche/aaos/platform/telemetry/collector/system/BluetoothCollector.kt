package com.porsche.aaos.platform.telemetry.collector.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class BluetoothCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Bluetooth"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false
    private var bluetoothManager: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null

    // Batched HFP indicator samples: [timestampMillis, deviceAddress, batteryLevel, signalStrength]
    private val indicatorSamples = mutableListOf<List<Any>>()

    // Track connected profiles per device for change detection
    private val connectedProfiles = mutableMapOf<String, MutableSet<String>>()

    private val profileProxies = mutableMapOf<Int, BluetoothProfile>()

    private val monitoredProfiles = listOf(
        BluetoothProfile.A2DP,
        BluetoothProfile.HEADSET,
        BluetoothProfile.HID_HOST,
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(
                BluetoothDevice.EXTRA_DEVICE,
            ) ?: return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    sendConnectionEvent(device, "connected")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    connectedProfiles.remove(device.address)
                    sendConnectionEvent(device, "disconnected")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.BOND_NONE,
                    )
                    sendBondEvent(device, state)
                }
            }
        }
    }

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting Bluetooth monitoring")

        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager = bm
        adapter = bm.adapter

        if (adapter == null) {
            logger.w(TAG, "BluetoothAdapter not available")
            return
        }

        registerReceiver()
        openProfileProxies()

        // Initial startup snapshot after proxies connect
        delay(STAGGER_DELAY_MS)
        sendStartupSnapshot()

        // Poll HFP indicators every 5s, flush every 60s
        var sampleCount = 0
        while (running && coroutineContext.isActive) {
            pollIndicators()
            sampleCount++

            if (sampleCount >= SAMPLES_PER_BATCH) {
                flushIndicators()
                pollProfileChanges()
                sampleCount = 0
            }
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        try {
            context.unregisterReceiver(receiver)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Receiver may not be registered
        }
        closeProfileProxies()
        bluetoothManager = null
        adapter = null
        connectedProfiles.clear()
        indicatorSamples.clear()
        logger.i(TAG, "Stopped")
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
    }

    private fun openProfileProxies() {
        val bt = adapter ?: return
        for (profileId in monitoredProfiles) {
            bt.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        profileProxies[profile] = proxy
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        profileProxies.remove(profile)
                    }
                },
                profileId,
            )
        }
    }

    private fun closeProfileProxies() {
        val bt = adapter ?: return
        for ((profileId, proxy) in profileProxies) {
            bt.closeProfileProxy(profileId, proxy)
        }
        profileProxies.clear()
    }

    private fun sendStartupSnapshot() {
        val bt = adapter ?: return

        // Bonded devices
        val bondedDevices = bt.bondedDevices?.map { device ->
            mapOf(
                "address" to device.address,
                "name" to (device.name ?: "unknown"),
                "type" to deviceTypeString(device.type),
                "bondState" to "bonded",
            )
        } ?: emptyList()

        // Connected devices per profile
        val connectedDevices = mutableListOf<Map<String, Any>>()
        for ((profileId, proxy) in profileProxies) {
            for (device in proxy.connectedDevices) {
                val profileName = profileName(profileId)
                connectedProfiles.getOrPut(device.address) { mutableSetOf() }.add(profileName)
                connectedDevices.add(
                    mapOf(
                        "address" to device.address,
                        "name" to (device.name ?: "unknown"),
                        "profile" to profileName,
                    ),
                )
            }
        }

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Bluetooth_Snapshot",
                    "trigger" to "startup",
                    "metadata" to mapOf(
                        "adapterEnabled" to (bt.isEnabled),
                        "adapterName" to (bt.name ?: "unknown"),
                        "bondedDevices" to bondedDevices,
                        "connectedDevices" to connectedDevices,
                    ),
                ),
            ),
        )
        logger.d(TAG, "Startup snapshot: ${bondedDevices.size} bonded, ${connectedDevices.size} connected")
    }

    private fun sendConnectionEvent(device: BluetoothDevice, event: String) {
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Bluetooth_Connection",
                    "trigger" to "system",
                    "metadata" to mapOf(
                        "event" to event,
                        "address" to device.address,
                        "name" to (device.name ?: "unknown"),
                        "type" to deviceTypeString(device.type),
                    ),
                ),
            ),
        )
    }

    private fun sendBondEvent(device: BluetoothDevice, state: Int) {
        val stateStr = when (state) {
            BluetoothDevice.BOND_BONDED -> "bonded"
            BluetoothDevice.BOND_BONDING -> "bonding"
            BluetoothDevice.BOND_NONE -> "none"
            else -> "unknown"
        }
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Bluetooth_BondState",
                    "trigger" to "system",
                    "metadata" to mapOf(
                        "address" to device.address,
                        "name" to (device.name ?: "unknown"),
                        "bondState" to stateStr,
                    ),
                ),
            ),
        )
    }

    private fun pollIndicators() {
        // Read battery level from bonded connected devices (available via HFP metadata)
        val bt = adapter ?: return
        for (device in bt.bondedDevices ?: emptySet()) {
            if (!isDeviceConnected(device)) continue

            @Suppress("MagicNumber")
            val batteryLevel = device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int
            val sample = listOf(
                System.currentTimeMillis(),
                device.address,
                batteryLevel,
            )
            indicatorSamples.add(sample)
        }
    }

    private fun flushIndicators() {
        if (indicatorSamples.isEmpty()) return
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Bluetooth_DeviceIndicators",
                    "trigger" to "heartbeat",
                    "metadata" to mapOf(
                        "sampleSchema" to listOf(
                            "timestampMillis",
                            "deviceAddress",
                            "batteryLevel",
                        ),
                        "samples" to indicatorSamples.toList(),
                    ),
                ),
            ),
        )
        indicatorSamples.clear()
    }

    private fun pollProfileChanges() {
        val currentProfiles = mutableMapOf<String, MutableSet<String>>()
        for ((profileId, proxy) in profileProxies) {
            for (device in proxy.connectedDevices) {
                currentProfiles.getOrPut(device.address) { mutableSetOf() }
                    .add(profileName(profileId))
            }
        }

        // Detect changes
        val allAddresses = (connectedProfiles.keys + currentProfiles.keys).toSet()
        for (address in allAddresses) {
            val previous = connectedProfiles[address] ?: emptySet()
            val current = currentProfiles[address] ?: emptySet()
            if (previous != current) {
                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "Bluetooth_ProfileChange",
                            "trigger" to "system",
                            "metadata" to mapOf(
                                "address" to address,
                                "previousProfiles" to previous.sorted(),
                                "currentProfiles" to current.sorted(),
                            ),
                        ),
                    ),
                )
            }
        }
        connectedProfiles.clear()
        connectedProfiles.putAll(currentProfiles)
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return profileProxies.values.any { proxy ->
            proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
        }
    }

    private fun deviceTypeString(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "le"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> "unknown"
    }

    private fun profileName(profileId: Int): String = when (profileId) {
        BluetoothProfile.A2DP -> "a2dp"
        BluetoothProfile.HEADSET -> "hfp"
        BluetoothProfile.HID_HOST -> "hid"
        else -> "profile_$profileId"
    }

    companion object {
        private const val TAG = "BluetoothCollector"
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val SAMPLES_PER_BATCH = 12 // 12 × 5s = 60s flush
        private const val STAGGER_DELAY_MS = 4_000L
    }
}
