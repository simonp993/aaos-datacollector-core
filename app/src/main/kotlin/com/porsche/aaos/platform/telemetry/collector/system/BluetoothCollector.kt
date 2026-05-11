package com.porsche.aaos.platform.telemetry.collector.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
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

    // Track last known battery level per device for on-change emission
    private val lastBatteryLevel = mutableMapOf<String, Int>()

    // Track connected profiles per device for change detection
    private val connectedProfiles = mutableMapOf<String, MutableSet<String>>()

    private val profileProxies = mutableMapOf<Int, BluetoothProfile>()

    private val monitoredProfiles = listOf(
        BluetoothProfile.A2DP,
        BluetoothProfile.HEADSET,
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            logger.d(TAG, "Received: ${intent.action}")
            val device = intent.getParcelableExtra<BluetoothDevice>(
                BluetoothDevice.EXTRA_DEVICE,
            )

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    // Clean up tracked state when physical link drops
                    device?.let {
                        connectedProfiles.remove(it.address)
                        lastBatteryLevel.remove(it.address)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    device?.let {
                        val state = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.BOND_NONE,
                        )
                        sendBondEvent(it, state)
                    }
                }
                // Profile-level connection changes (what happens when you switch Phone/Music)
                ACTION_A2DP_CONNECTION_STATE_CHANGED,
                ACTION_HFP_CONNECTION_STATE_CHANGED -> {
                    device?.let { handleProfileStateChange(intent, it) }
                }
                // Active device switched (which phone is active for audio/calls)
                ACTION_A2DP_ACTIVE_DEVICE_CHANGED -> {
                    sendActiveDeviceChanged(device, "a2dp")
                }
                ACTION_HFP_ACTIVE_DEVICE_CHANGED -> {
                    sendActiveDeviceChanged(device, "hfp")
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

        // Wait for profile proxies to connect before snapshot
        delay(PROXY_CONNECT_DELAY_MS)
        sendStartupSnapshot()

        // Poll battery + profile changes periodically
        var pollCount = 0
        while (running && coroutineContext.isActive) {
            pollBatteryChanges()
            pollCount++

            if (pollCount >= POLLS_PER_PROFILE_CHECK) {
                pollProfileChanges()
                pollCount = 0
            }
            delay(POLL_INTERVAL_MS)
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
        lastBatteryLevel.clear()
        logger.i(TAG, "Stopped")
    }

    @Suppress("DiscouragedPrivateApi")
    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            // ACL-level (cleanup tracked state when physical link drops)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            // Profile-level (Music/Phone connect/disconnect per device)
            addAction(ACTION_A2DP_CONNECTION_STATE_CHANGED)
            addAction(ACTION_HFP_CONNECTION_STATE_CHANGED)
            // Active device switches (which phone is active for audio/calls)
            addAction(ACTION_A2DP_ACTIVE_DEVICE_CHANGED)
            addAction(ACTION_HFP_ACTIVE_DEVICE_CHANGED)
        }
        // Service runs as singleUser (user 0), but BT broadcasts target the driver user.
        // Use registerReceiverAsUser(ALL) to receive broadcasts from all users.
        try {
            val userAll = UserHandle::class.java.getField("ALL").get(null) as UserHandle
            val method = context.javaClass.getMethod(
                "registerReceiverAsUser",
                BroadcastReceiver::class.java,
                UserHandle::class.java,
                IntentFilter::class.java,
                String::class.java,
                Handler::class.java,
                Int::class.javaPrimitiveType,
            )
            method.invoke(
                context,
                receiver,
                userAll,
                filter,
                null,
                Handler(Looper.getMainLooper()),
                Context.RECEIVER_EXPORTED,
            )
            logger.i(TAG, "Registered receiver for ALL users (context=${context.javaClass.name})")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Fallback: try 5-parameter overload without flags
            try {
                val userAll = UserHandle::class.java.getField("ALL").get(null) as UserHandle
                val fallback = context.javaClass.getMethod(
                    "registerReceiverAsUser",
                    BroadcastReceiver::class.java,
                    UserHandle::class.java,
                    IntentFilter::class.java,
                    String::class.java,
                    Handler::class.java,
                )
                fallback.invoke(context, receiver, userAll, filter, null, null)
                logger.i(TAG, "Registered receiver for ALL users (5-param fallback)")
            } catch (@Suppress("TooGenericExceptionCaught") e2: Exception) {
                // Last resort: standard registration (only receives user 0 broadcasts)
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                logger.w(TAG, "Fell back to user-0-only receiver: ${e2.message}")
            }
        }
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

    private fun handleProfileStateChange(intent: Intent, device: BluetoothDevice) {
        val previousState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
        val newState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)

        // Only emit when a profile actually connects or disconnects from a connected state.
        // Skip connecting→disconnected transitions — the BT stack cycles through bonded
        // devices trying to establish HFP, producing noise for devices that aren't nearby.
        val isRealConnect = newState == BluetoothProfile.STATE_CONNECTED
        val isRealDisconnect = previousState == BluetoothProfile.STATE_CONNECTED &&
            newState == BluetoothProfile.STATE_DISCONNECTED
        if (!isRealConnect && !isRealDisconnect) return

        val profile = when (intent.action) {
            ACTION_A2DP_CONNECTION_STATE_CHANGED -> "a2dp"
            ACTION_HFP_CONNECTION_STATE_CHANGED -> "hfp"
            else -> "unknown"
        }
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Bluetooth_ProfileState",
                    "trigger" to "system",
                    "metadata" to mapOf(
                        "address" to device.address,
                        "name" to (device.name ?: "unknown"),
                        "profile" to profile,
                        "previousState" to profileStateString(previousState),
                        "newState" to profileStateString(newState),
                    ),
                ),
            ),
        )
    }

    private fun sendActiveDeviceChanged(device: BluetoothDevice?, profile: String) {
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Bluetooth_ActiveDeviceChanged",
                    "trigger" to "system",
                    "metadata" to mapOf(
                        "profile" to profile,
                        "address" to (device?.address ?: "none"),
                        "name" to (device?.name ?: "none"),
                    ),
                ),
            ),
        )
    }

    private fun profileStateString(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "unknown"
    }

    private fun pollBatteryChanges() {
        val bt = adapter ?: return
        for (device in bt.bondedDevices ?: emptySet()) {
            if (!isDeviceConnected(device)) continue

            val batteryLevel = try {
                @Suppress("MagicNumber")
                device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                -1
            }

            val previous = lastBatteryLevel[device.address]
            if (previous == batteryLevel) continue
            lastBatteryLevel[device.address] = batteryLevel

            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Bluetooth_BatteryLevel",
                        "trigger" to "onChange",
                        "metadata" to mapOf(
                            "address" to device.address,
                            "name" to (device.name ?: "unknown"),
                            "batteryLevel" to batteryLevel,
                            "previousLevel" to (previous ?: "initial"),
                        ),
                    ),
                ),
            )
            logger.d(TAG, "Battery changed: ${device.name} $previous → $batteryLevel")
        }

        // Clean up tracked devices that are no longer connected
        val connectedAddresses = (bt.bondedDevices ?: emptySet())
            .filter { isDeviceConnected(it) }
            .map { it.address }
            .toSet()
        lastBatteryLevel.keys.retainAll(connectedAddresses)
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
        // Try hidden isConnected() method (works for classic BT)
        val connected = try {
            device.javaClass.getMethod("isConnected").invoke(device) as Boolean
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            false
        }
        if (connected) return true

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
        else -> "profile_$profileId"
    }

    companion object {
        private const val TAG = "BluetoothCollector"
        private const val POLL_INTERVAL_MS = 30_000L
        private const val POLLS_PER_PROFILE_CHECK = 2 // 2 × 30s = 60s profile poll
        private const val PROXY_CONNECT_DELAY_MS = 6_000L

        // Profile-level broadcast actions (fired when Music/Phone connects/disconnects per device)
        private const val ACTION_A2DP_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        private const val ACTION_HFP_CONNECTION_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"

        // Active device switched (which phone is active for audio/calls)
        private const val ACTION_A2DP_ACTIVE_DEVICE_CHANGED =
            "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
        private const val ACTION_HFP_ACTIVE_DEVICE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.ACTIVE_DEVICE_CHANGED"
    }
}
