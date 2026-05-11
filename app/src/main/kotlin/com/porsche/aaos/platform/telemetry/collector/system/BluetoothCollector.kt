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

    // Cache device names — Android clears BluetoothDevice.name before sending BOND_NONE,
    // so the name must be cached while the device is still bonded.
    private val deviceNames = mutableMapOf<String, String>()

    // Track connected profiles per device for change detection
    private val connectedProfiles = mutableMapOf<String, MutableSet<String>>()

    // Debounce HFP connecting→connected: track last connect time per device+profile
    private val lastConnectTime = mutableMapOf<String, Long>()

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
                BluetoothAdapter.ACTION_STATE_CHANGED -> handleAdapterStateChanged(intent)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    // Clean up tracked state when physical link drops
                    device?.let {
                        connectedProfiles.remove(it.address)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    device?.let {
                        val state = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.BOND_NONE,
                        )
                        sendDeviceList(bondStateString(state), it)
                    }
                }
                // Profile-level connection changes (what happens when you switch Phone/Music)
                // Limitation: CarPlay does not disconnect BT profiles — the phone keeps BT
                // connected while routing audio/data over the Aptiv CarPlay stack. Switching
                // between BT and CarPlay is invisible at the BT profile level.
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
        sendDeviceList("startup", changedDevice = null)

        // Poll profile changes periodically
        while (running && coroutineContext.isActive) {
            delay(POLL_INTERVAL_MS)
            pollProfileChanges()
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
        deviceNames.clear()
        lastConnectTime.clear()
        logger.i(TAG, "Stopped")
    }

    @Suppress("DiscouragedPrivateApi")
    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            // Adapter on/off
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
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

    private fun sendDeviceList(reason: String, changedDevice: BluetoothDevice?) {
        val bt = adapter ?: return

        // Refresh name cache from current bonded devices
        bt.bondedDevices?.forEach { device ->
            device.name?.let { deviceNames[device.address] = it }
        }

        // Columnar: [addr, name, type, connected, profiles]
        val devices = bt.bondedDevices?.map { device ->
            val connected = isDeviceConnected(device)
            val profiles = mutableListOf<String>()
            for ((profileId, proxy) in profileProxies) {
                if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                    profiles.add(profileName(profileId))
                }
            }
            listOf(
                device.address,
                deviceNames[device.address] ?: device.name ?: "?",
                deviceTypeString(device.type),
                connected,
                profiles.sorted().joinToString(",").ifEmpty { null },
            )
        } ?: emptyList()

        // Seed connected profiles tracker on startup
        if (reason == "startup") {
            for ((profileId, proxy) in profileProxies) {
                for (device in proxy.connectedDevices) {
                    connectedProfiles.getOrPut(device.address) { mutableSetOf() }
                        .add(profileName(profileId))
                }
            }
        }

        val changedName = changedDevice?.let {
            deviceNames[it.address] ?: it.name ?: "?"
        }
        val metadata = buildMap<String, Any> {
            put("reason", reason)
            put("schema", listOf("addr", "name", "type", "connected", "profiles"))
            put("devices", devices)
            if (changedDevice != null && changedName != null) {
                put("changed", listOf(changedDevice.address, changedName))
            }
        }

        // Clean up names of devices no longer bonded
        if (reason == "unbonded") {
            val bondedAddrs = bt.bondedDevices?.map { it.address }?.toSet() ?: emptySet()
            deviceNames.keys.retainAll(bondedAddrs + (changedDevice?.address ?: ""))
        }

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Bluetooth_DeviceList",
                    "trigger" to if (reason == "startup") "startup" else "system",
                    "metadata" to metadata,
                ),
            ),
        )
        logger.d(TAG, "DeviceList ($reason): ${devices.size} bonded")
    }

    private fun handleAdapterStateChanged(intent: Intent) {
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        // Only emit on final states, not transient (turning_on/turning_off)
        val enabled = when (state) {
            BluetoothAdapter.STATE_ON -> true
            BluetoothAdapter.STATE_OFF -> false
            else -> return
        }
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Bluetooth_StateChanged",
                    "trigger" to "system",
                    "metadata" to mapOf("enabled" to enabled),
                ),
            ),
        )
        logger.d(TAG, "Adapter state: ${if (enabled) "on" else "off"}")
    }

    private fun bondStateString(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "bonding"
        BluetoothDevice.BOND_NONE -> "unbonded"
        else -> "unknown"
    }

    private fun handleProfileStateChange(intent: Intent, device: BluetoothDevice) {
        val previousState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
        val newState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)

        val profile = when (intent.action) {
            ACTION_A2DP_CONNECTION_STATE_CHANGED -> "a2dp"
            ACTION_HFP_CONNECTION_STATE_CHANGED -> "hfp"
            else -> "?"
        }

        // Log all transitions for diagnostics
        logger.d(
            TAG,
            "Profile $profile ${device.name}: ${profileStateString(previousState)}" +
                "→${profileStateString(newState)}",
        )

        // Only emit when a profile actually connects or disconnects from a connected state.
        // Skip connecting→disconnected transitions — the BT stack cycles through bonded
        // devices trying to establish HFP, producing noise for devices that aren't nearby.
        val isRealConnect = newState == BluetoothProfile.STATE_CONNECTED
        val isRealDisconnect = previousState == BluetoothProfile.STATE_CONNECTED &&
            newState == BluetoothProfile.STATE_DISCONNECTED
        if (!isRealConnect && !isRealDisconnect) return

        // Debounce: suppress duplicate connects within CONNECT_DEBOUNCE_MS
        val key = "${device.address}:$profile"
        val now = System.currentTimeMillis()
        if (isRealConnect) {
            val lastTime = lastConnectTime[key] ?: 0L
            if (now - lastTime < CONNECT_DEBOUNCE_MS) {
                logger.d(TAG, "Debounced duplicate $profile connect for ${device.name}")
                return
            }
            lastConnectTime[key] = now
        }

        // Update name cache
        device.name?.let { deviceNames[device.address] = it }

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Bluetooth_ProfileState",
                    "trigger" to "system",
                    "metadata" to mapOf(
                        "addr" to device.address,
                        "name" to (deviceNames[device.address] ?: device.name ?: "?"),
                        "profile" to profile,
                        "from" to profileStateString(previousState),
                        "to" to profileStateString(newState),
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
                    "actionName" to "Bluetooth_ActiveDevice",
                    "trigger" to "system",
                    "metadata" to mapOf(
                        "profile" to profile,
                        "addr" to (device?.address ?: "none"),
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
                                "addr" to address,
                                "from" to previous.sorted(),
                                "to" to current.sorted(),
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
        private const val POLL_INTERVAL_MS = 60_000L // Profile change poll interval
        private const val PROXY_CONNECT_DELAY_MS = 6_000L
        private const val CONNECT_DEBOUNCE_MS = 30_000L // Suppress duplicate connects

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
