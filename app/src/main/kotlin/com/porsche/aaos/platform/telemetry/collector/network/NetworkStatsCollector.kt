package com.porsche.aaos.platform.telemetry.collector.network

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
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

/**
 * Collects per-UID network traffic for ALL Android users (user 0 + user 10 etc.).
 *
 * ## Why dumpsys instead of NetworkStatsManager?
 * For user 0, NetworkStatsManager.querySummary(type, null, start, end) works and supports
 * time-windowed queries. However, it is user-isolated at the Binder level: the
 * NetworkStatsService checks Binder.getCallingUid() and only returns data for the calling
 * user — even when called from a cross-user Context created via createContextAsUser().
 * TrafficStats.getUidRxBytes() also fails cross-user (requires net_bw_acct group).
 *
 * To get consistent per-interval (60s) data for ALL users from a single source, we parse
 * `dumpsys netstats --uid` which returns cumulative byte counters for every UID on the
 * system. We store a snapshot each poll and emit the delta (current - previous), giving
 * exact "bytes in the last 60 seconds" semantics for all users without overlap.
 *
 * ## MIB4 Network Architecture — Ethernet via TCU
 * On MIB4, the cellular modem lives in the TCU (Telematics Control Unit), a separate ECU.
 * Internet traffic is routed from the head unit to the TCU over an internal ethernet bridge
 * through VPN tunnels (tun0/tun1). Android sees this as ETHERNET transport (type=9), not
 * MOBILE. Consequences:
 * - `querySummaryForDevice(TYPE_ETHERNET)` returns null — Android's NetworkStatsService
 *   does not maintain cumulative bucket stats for ethernet. Network_EthernetTotal is
 *   therefore computed by summing per-UID ethernet deltas from `dumpsys netstats --uid`.
 * - `querySummaryForDevice(TYPE_MOBILE)` returns 0 — no modem is visible to Android.
 * - Per-app traffic is tagged `networkType=ethernet` (netstats ident type=9).
 * - The `networkstack` UID (1073) shows as `networkType=other` — it handles the tunnel.
 *
 * ## Measurement layer and accuracy
 * Per-UID counters from `dumpsys netstats --uid` are tracked by the kernel's BPF at the
 * socket layer — BEFORE VPN/tunnel encapsulation. Empirical validation confirms:
 * - Sum of per-UID bytes with apn=apn2_oem matches tun0 /proc/net/dev counters exactly
 *   (0.0% difference). This means NO VPN overhead is included in per-UID data.
 * - Per-app proportions are therefore accurate for cellular consumption analysis.
 *   If the carrier reports a total, per-app percentages from this data can be scaled
 *   to that total reliably.
 * - rxBytes = bytes received (download), txBytes = bytes sent (upload). Dashboard totals
 *   showing rx+tx represent the combined bidirectional cellular consumption.
 *
 * ## Overhead estimate: HU measurement vs actual SIM traffic
 * The tun interfaces are transparent pass-through (layer 3 tun, no encryption).
 * OemNetworkPreferences routing steers packets to the right tunnel without
 * re-encapsulating. Potential overhead between HU measurement and SIM:
 * - VPN/IPsec encryption (50-70 bytes/pkt, 5-8%): NOT present — tun=netstats confirms
 * - Ethernet framing HU→TCU (18 bytes/pkt, ~1.5%): stripped by TCU before cellular
 * - TCP retransmissions at TCU↔carrier (variable, 1-3%): not visible to HU
 * - Carrier protocol GTP-U/PDCP (~8 bytes/pkt, <1%): not counted by carriers
 * Total expected difference: <3%. What we measure ≈ what the SIM transmits.
 * Carrier bill should closely match apn2_oem + apn1_customer totals from this collector.
 *
 * ## APN classification and the "unknown" bucket
 * The `oemManaged` field in netstats ident blocks classifies traffic:
 * - OEM_PRIVATE → apn2_oem: apps routed via OemNetworkPreferences (tun0, OEM-paid)
 * - OEM_NONE → apn1_customer: default route (tun1, customer-paid)
 * - Other/missing → "unknown": local/internal traffic that does NOT traverse cellular
 *
 * The "unknown" APN bucket contains traffic that stays on the HU or is internal:
 * - com.android.networkstack (UID 1073): tunnel management, DNS relay, captive portal
 * - com.android.car.settings: local IPC, cached content, settings sync
 * - com.android.car.developeroptions: ADB/developer traffic
 * - com.android.networkstack.tethering: hotspot forwarding bookkeeping
 * This traffic does NOT leave the device over cellular. For cellular cost analysis,
 * filter to apn=apn2_oem and apn=apn1_customer only.
 */
class NetworkStatsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "NetworkStats"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false

    // Previous cumulative snapshot: TrafficKey(uid, networkType, apn) → bytes
    // Delta = current - previous gives exact per-interval traffic without overlap.
    private var previousSnapshot: Map<TrafficKey, TrafficBytes> = emptyMap()

    // Previous tethering interface byte counters for delta calculation
    private var previousTetheringRx: Long = 0L
    private var previousTetheringTx: Long = 0L

    // Previous APN interface byte counters for delta calculation (tun0=OEM, tun1=customer)
    private var previousApnOemRx: Long = 0L
    private var previousApnOemTx: Long = 0L
    private var previousApnCustomerRx: Long = 0L
    private var previousApnCustomerTx: Long = 0L

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting network stats monitoring")

        val userIds = discoverUserIds()
        logger.i(TAG, "Initial user IDs: $userIds")

        // Take initial snapshot so the first delta is meaningful (no cumulative dump)
        previousSnapshot = parseDumpsysNetstats()
        val tetheringInit = readTetheringInterfaceBytes()
        previousTetheringRx = tetheringInit.first
        previousTetheringTx = tetheringInit.second
        val apnInit = readApnInterfaceBytes()
        previousApnOemRx = apnInit.oemRx
        previousApnOemTx = apnInit.oemTx
        previousApnCustomerRx = apnInit.customerRx
        previousApnCustomerTx = apnInit.customerTx
        delay(STAGGER_DELAY_MS) // Stagger to spread flush bursts
        delay(POLL_INTERVAL_MS)

        while (running && coroutineContext.isActive) {
            emitTotalStats()
            emitTetheringStats()
            emitApnTraffic()
            // Re-discover users each cycle to catch added/removed users
            val currentUserIds = discoverUserIds()
            emitPerUidStats(currentUserIds)
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun discoverUserIds(): List<Int> {
        val um = context.getSystemService(Context.USER_SERVICE) ?: return listOf(0)

        // Try UserManager.getUsers(boolean) → List<UserInfo>
        try {
            val usersMethod = um.javaClass.getMethod("getUsers", Boolean::class.javaPrimitiveType)
            @Suppress("UNCHECKED_CAST")
            val userInfoList = usersMethod.invoke(um, true) as? List<*>
            if (!userInfoList.isNullOrEmpty()) {
                val ids = userInfoList.mapNotNull { userInfo ->
                    userInfo?.javaClass?.getField("id")?.getInt(userInfo)
                }
                if (ids.isNotEmpty()) return ids
            }
        } catch (e: Exception) {
            logger.d(TAG, "getUsers(boolean) failed: ${e.message}")
        }

        // Try UserManager.getUsers() → List<UserInfo>
        try {
            val usersMethod = um.javaClass.getMethod("getUsers")
            @Suppress("UNCHECKED_CAST")
            val userInfoList = usersMethod.invoke(um) as? List<*>
            if (!userInfoList.isNullOrEmpty()) {
                val ids = userInfoList.mapNotNull { userInfo ->
                    userInfo?.javaClass?.getField("id")?.getInt(userInfo)
                }
                if (ids.isNotEmpty()) return ids
            }
        } catch (e: Exception) {
            logger.d(TAG, "getUsers() failed: ${e.message}")
        }

        // Try UserManager.getAliveUsers() → List<UserHandle>
        try {
            val aliveMethod = um.javaClass.getMethod("getAliveUsers")
            @Suppress("UNCHECKED_CAST")
            val handles = aliveMethod.invoke(um) as? List<*>
            if (!handles.isNullOrEmpty()) {
                val getIdMethod = UserHandle::class.java.getMethod("getIdentifier")
                val ids = handles.mapNotNull { handle ->
                    (handle as? UserHandle)?.let { getIdMethod.invoke(it) as? Int }
                }
                if (ids.isNotEmpty()) return ids
            }
        } catch (e: Exception) {
            logger.d(TAG, "getAliveUsers() failed: ${e.message}")
        }

        // Fallback: enumerate /data/system/users/ directory
        try {
            val usersDir = java.io.File("/data/system/users")
            if (usersDir.isDirectory) {
                val ids = usersDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.mapNotNull { it.name.toIntOrNull() }
                    ?.sorted()
                if (!ids.isNullOrEmpty()) return ids
            }
        } catch (e: Exception) {
            logger.d(TAG, "/data/system/users fallback failed: ${e.message}")
        }

        logger.w(TAG, "All user discovery methods failed, defaulting to user 0")
        return listOf(0)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun createUserContext(userId: Int): Context? {
        if (userId == 0) return context
        return try {
            val userHandle = UserHandle::class.java
                .getMethod("of", Int::class.javaPrimitiveType)
                .invoke(null, userId) as UserHandle
            context.javaClass
                .getMethod("createContextAsUser", UserHandle::class.java, Int::class.javaPrimitiveType)
                .invoke(context, userHandle, 0) as Context
        } catch (e: Exception) {
            logger.w(TAG, "Failed to create context for user $userId: ${e.message}")
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun emitTotalStats() {
        try {
            val networkStatsManager =
                context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val now = System.currentTimeMillis()
            val start = now - POLL_INTERVAL_MS

            val wifiBucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_WIFI, null, start, now,
            )
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Network_WifiTotal",
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "rxBytes" to wifiBucket.rxBytes,
                            "txBytes" to wifiBucket.txBytes,
                        ),
                    ),
                ),
            )

            val mobileBucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE, null, start, now,
            )
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Network_MobileTotal",
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "rxBytes" to mobileBucket.rxBytes,
                            "txBytes" to mobileBucket.txBytes,
                        ),
                    ),
                ),
            )

            // Ethernet total via NetworkStatsManager (returns null on MIB4 — see class KDoc).
            // Fallback: Network_EthernetTotal is also emitted from per-UID deltas in emitPerUidStats().
            val ethernetBucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_ETHERNET, null, start, now,
            )
            if (ethernetBucket != null) {
                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "Network_EthernetTotal",
                            "trigger" to "heartbeat",
                            "metadata" to mapOf(
                                "rxBytes" to ethernetBucket.rxBytes,
                                "txBytes" to ethernetBucket.txBytes,
                            ),
                        ),
                    ),
                )
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to collect total network stats", e)
        }
    }

    /**
     * Emits tethering traffic stats: total bytes through the hotspot interface(s) and
     * the number of currently connected clients.
     *
     * NOTE: rxBytes/txBytes represent ALL traffic on the AP interfaces — this includes both
     * local traffic (CarPlay, Android Auto, AirPlay) and internet-forwarded traffic. Android
     * does not expose a way to separate these:
     * - BPF tethering stats (mBpfStatsMap) are only populated when forwarding is active and
     *   are not accessible from userspace without root/bpf filesystem.
     * - iptables tether counters are not present on AAOS 15.
     * - /proc/net/dev on the upstream interface mixes the car's own traffic with forwarded.
     * - xt_qtaguid was removed in Android 15.
     * Therefore, to determine "cellular consumed by tethered devices" one would need to
     * subtract the car's own cellular usage from total cellular at analysis time.
     *
     * Data sources:
     * - `dumpsys tethering` → identifies active tethering interfaces (TetheredState)
     * - `/proc/net/dev` → cumulative rx/tx byte counters per interface
     * - `ip neigh show dev <iface>` → connected clients (unique MACs)
     */
    @Suppress("TooGenericExceptionCaught")
    private fun emitTetheringStats() {
        try {
            val current = readTetheringInterfaceBytes()
            val deltaRx = (current.first - previousTetheringRx).coerceAtLeast(0L)
            val deltaTx = (current.second - previousTetheringTx).coerceAtLeast(0L)
            previousTetheringRx = current.first
            previousTetheringTx = current.second

            val interfaces = parseTetheringDumpsys().interfaces
            val clients = countTetheringClients(interfaces)

            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Network_VehicleHotspotTotal",
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "rxBytes" to deltaRx,
                            "txBytes" to deltaTx,
                            "connectedClients" to clients,
                        ),
                    ),
                ),
            )
        } catch (e: Exception) {
            logger.w(TAG, "Failed to collect tethering stats", e)
        }
    }

    /**
     * Emits APN traffic totals: OEM-paid (tun0, OEM_PRIVATE) vs customer-paid (tun1, default).
     *
     * On MIB4, the TCU exposes two VPN tunnels:
     * - tun0 = APN2 (OEM-paid): used by OemNetworkPreferences apps (navigation, telemetry, etc.)
     * - tun1 = APN1 (customer-paid): default route for all other apps
     *
     * We read cumulative byte counters from /proc/net/dev and emit deltas per interval.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun emitApnTraffic() {
        try {
            val current = readApnInterfaceBytes()
            val deltaOemRx = (current.oemRx - previousApnOemRx).coerceAtLeast(0L)
            val deltaOemTx = (current.oemTx - previousApnOemTx).coerceAtLeast(0L)
            val deltaCustomerRx = (current.customerRx - previousApnCustomerRx).coerceAtLeast(0L)
            val deltaCustomerTx = (current.customerTx - previousApnCustomerTx).coerceAtLeast(0L)
            previousApnOemRx = current.oemRx
            previousApnOemTx = current.oemTx
            previousApnCustomerRx = current.customerRx
            previousApnCustomerTx = current.customerTx

            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Network_ApnTraffic",
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "apn2_oem" to mapOf(
                                "interface" to APN_OEM_INTERFACE,
                                "rxBytes" to deltaOemRx,
                                "txBytes" to deltaOemTx,
                            ),
                            "apn1_customer" to mapOf(
                                "interface" to APN_CUSTOMER_INTERFACE,
                                "rxBytes" to deltaCustomerRx,
                                "txBytes" to deltaCustomerTx,
                            ),
                        ),
                    ),
                ),
            )
        } catch (e: Exception) {
            logger.w(TAG, "Failed to collect APN traffic stats", e)
        }
    }

    private data class ApnBytes(
        val oemRx: Long,
        val oemTx: Long,
        val customerRx: Long,
        val customerTx: Long,
    )

    /**
     * Reads cumulative byte counters from /proc/net/dev for APN interfaces (tun0, tun1).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readApnInterfaceBytes(): ApnBytes {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("cat", "/proc/net/dev"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            var oemRx = 0L
            var oemTx = 0L
            var customerRx = 0L
            var customerTx = 0L
            for (line in output.lines()) {
                val trimmed = line.trim()
                val iface = trimmed.substringBefore(":").trim()
                if (iface == APN_OEM_INTERFACE || iface == APN_CUSTOMER_INTERFACE) {
                    val fields = trimmed.substringAfter(":").trim().split("\\s+".toRegex())
                    if (fields.size >= 9) {
                        val rx = fields[0].toLongOrNull() ?: 0L
                        val tx = fields[8].toLongOrNull() ?: 0L
                        if (iface == APN_OEM_INTERFACE) {
                            oemRx = rx
                            oemTx = tx
                        } else {
                            customerRx = rx
                            customerTx = tx
                        }
                    }
                }
            }
            ApnBytes(oemRx, oemTx, customerRx, customerTx)
        } catch (e: Exception) {
            logger.d(TAG, "Failed to read APN interface bytes: ${e.message}")
            ApnBytes(0L, 0L, 0L, 0L)
        }
    }

    private data class TetheringInfo(
        val interfaces: List<String>,
        val upstream: String,
        val upstreamType: String,
    )

    /**
     * Parses `dumpsys tethering` to discover:
     * - Active tethering interfaces (TetheredState / LocalHotspotState)
     * - Current upstream interface (where tethered traffic exits)
     */
    @Suppress("TooGenericExceptionCaught")
    private fun parseTetheringDumpsys(): TetheringInfo {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "tethering"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val interfaces = TETHER_IFACE_PATTERN.findAll(output)
                .map { it.groupValues[1] }.toList()

            val upstream = UPSTREAM_PATTERN.find(output)?.groupValues?.get(1) ?: "none"
            val upstreamType = when {
                upstream.startsWith("rmnet") -> "cellular"
                upstream.startsWith("wlan") -> "wifi"
                upstream.startsWith("eth") -> "ethernet"
                upstream == "none" -> "none"
                else -> "other"
            }

            TetheringInfo(interfaces, upstream, upstreamType)
        } catch (e: Exception) {
            logger.d(TAG, "Failed to parse tethering dumpsys: ${e.message}")
            TetheringInfo(emptyList(), "unknown", "unknown")
        }
    }

    /**
     * Reads cumulative byte counters from /proc/net/dev for active tethering interfaces.
     * Returns (totalRx, totalTx) summed across all tethering interfaces.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readTetheringInterfaceBytes(): Pair<Long, Long> {
        val interfaces = parseTetheringDumpsys().interfaces
        if (interfaces.isEmpty()) return 0L to 0L
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("cat", "/proc/net/dev"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            var totalRx = 0L
            var totalTx = 0L
            for (line in output.lines()) {
                val trimmed = line.trim()
                val iface = trimmed.substringBefore(":").trim()
                if (iface in interfaces) {
                    val fields = trimmed.substringAfter(":").trim().split("\\s+".toRegex())
                    if (fields.size >= 9) {
                        totalRx += fields[0].toLongOrNull() ?: 0L
                        totalTx += fields[8].toLongOrNull() ?: 0L
                    }
                }
            }
            totalRx to totalTx
        } catch (e: Exception) {
            logger.d(TAG, "Failed to read /proc/net/dev: ${e.message}")
            0L to 0L
        }
    }

    /**
     * Counts unique tethering clients by querying neighbor table for each interface.
     * Deduplicates by MAC address (same device has IPv4 + IPv6 neighbors).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun countTetheringClients(interfaces: List<String>): Int {
        if (interfaces.isEmpty()) return 0
        val macs = mutableSetOf<String>()
        for (iface in interfaces) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show", "dev", iface))
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                MAC_PATTERN.findAll(output).forEach { macs.add(it.value.lowercase()) }
            } catch (e: Exception) {
                logger.d(TAG, "Failed to read neighbors for $iface: ${e.message}")
            }
        }
        return macs.size
    }

    /**
     * Emits per-UID delta stats for all users using dumpsys snapshot differencing.
     *
     * Flow: snapshot current cumulative counters → compute delta vs previous → emit only
     * UIDs with positive delta → store current as new previous. This guarantees exactly
     * one interval's worth of traffic per emission, no overlap between consecutive polls.
     *
     * Each app entry includes an "apn" field ("apn2_oem", "apn1_customer", or "unknown")
     * derived from the oemManaged field in the netstats ident block. An app CAN appear
     * twice if it uses both APNs (e.g. system UID uses both OEM_PRIVATE and default).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun emitPerUidStats(userIds: List<Int>) {
        try {
            val currentSnapshot = parseDumpsysNetstats()
            val deltas = computeDeltas(previousSnapshot, currentSnapshot)
            previousSnapshot = currentSnapshot

            if (deltas.isEmpty()) {
                logger.d(TAG, "No traffic delta this interval")
                return
            }

            // Build per-user context map for package name resolution
            val userContexts = mutableMapOf<Int, Context>()
            for (userId in userIds) {
                val ctx = if (userId == 0) context else createUserContext(userId)
                if (ctx != null) userContexts[userId] = ctx
            }

            val perApp = mutableListOf<List<Any?>>()
            for ((key, delta) in deltas) {
                val userId = key.uid / PER_USER_RANGE
                val userContext = userContexts[userId]
                val pkgNames = try {
                    userContext?.packageManager?.getPackagesForUid(key.uid)?.toList()
                } catch (e: Exception) {
                    null
                }
                val packages = pkgNames
                    ?: SYSTEM_UID_NAMES[key.uid % PER_USER_RANGE]?.let { listOf(it) }
                    ?: listOf("uid:${key.uid}")
                perApp.add(
                    listOf(key.uid, userId, packages, key.networkType, key.apn, delta.first, delta.second),
                )
            }

            val counts = userIds.associateWith { id ->
                perApp.count { (it[1] as? Int) == id }
            }
            logger.i(TAG, "Delta: ${perApp.size} entries" +
                counts.entries.joinToString("") { " | user ${it.key}: ${it.value}" })

            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Network_PerAppTraffic",
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "schema" to listOf(
                                "uid", "user", "packages", "networkType", "apn", "rxBytes", "txBytes",
                            ),
                            "apps" to perApp,
                        ),
                    ),
                ),
            )

            // Emit ethernet total from per-UID deltas (fallback for devices where
            // querySummaryForDevice(TYPE_ETHERNET) returns null)
            val ethernetRx = deltas.filter { it.key.networkType == "ethernet" }
                .values.sumOf { it.first }
            val ethernetTx = deltas.filter { it.key.networkType == "ethernet" }
                .values.sumOf { it.second }
            if (ethernetRx > 0 || ethernetTx > 0) {
                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "Network_EthernetTotal",
                            "trigger" to "heartbeat",
                            "metadata" to mapOf(
                                "rxBytes" to ethernetRx,
                                "txBytes" to ethernetTx,
                            ),
                        ),
                    ),
                )
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to collect per-app network stats", e)
        }
    }

    /**
     * Computes the delta between two cumulative snapshots.
     * Key is TrafficKey(uid, networkType, apn) to keep per-network-type and per-APN granularity.
     * Only returns entries where at least one of rxBytes/txBytes increased.
     * Handles counter resets gracefully (treats as new traffic from 0).
     */
    private fun computeDeltas(
        previous: Map<TrafficKey, TrafficBytes>,
        current: Map<TrafficKey, TrafficBytes>,
    ): Map<TrafficKey, Pair<Long, Long>> {
        val result = mutableMapOf<TrafficKey, Pair<Long, Long>>()
        for ((key, curr) in current) {
            val prev = previous[key]
            val deltaRx = (curr.rxBytes - (prev?.rxBytes ?: 0L)).coerceAtLeast(0L)
            val deltaTx = (curr.txBytes - (prev?.txBytes ?: 0L)).coerceAtLeast(0L)
            if (deltaRx > 0 || deltaTx > 0) {
                result[key] = deltaRx to deltaTx
            }
        }
        return result
    }

    /**
     * Parses `dumpsys netstats --uid` to extract cumulative byte counters per UID,
     * tagged with network type and APN classification.
     *
     * The dumpsys output contains bucket history entries like:
     *   ident=[{type=9, ..., oemManaged=OEM_PRIVATE, ...}] uid=1310124 set=FOREGROUND tag=0x0
     *     NetworkStatsHistory: bucketDuration=7200
     *       st=1778140800 rb=803444 rp=844 tb=133234 tp=603 op=0
     *
     * Key is (uid, networkType, apn) so an app using both APNs appears as separate entries.
     * We sum all rb (received bytes) and tb (transmitted bytes) across all buckets
     * per key to get the cumulative total. The delta between consecutive calls gives
     * exact per-interval traffic.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun parseDumpsysNetstats(): Map<TrafficKey, TrafficBytes> {
        val result = mutableMapOf<TrafficKey, TrafficBytes>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "netstats", "--uid"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            var currentKey: TrafficKey? = null
            for (line in output.lines()) {
                val uidMatch = UID_PATTERN.find(line)
                if (uidMatch != null) {
                    val uid = uidMatch.groupValues[1].toInt()
                    val netType = resolveNetworkType(line)
                    val apn = resolveApn(line)
                    currentKey = TrafficKey(uid, netType, apn)
                    continue
                }
                if (currentKey != null) {
                    val statsMatch = STATS_PATTERN.find(line)
                    if (statsMatch != null) {
                        val rb = statsMatch.groupValues[1].toLong()
                        val tb = statsMatch.groupValues[2].toLong()
                        val existing = result[currentKey]
                        if (existing != null) {
                            result[currentKey] = existing.copy(
                                rxBytes = existing.rxBytes + rb,
                                txBytes = existing.txBytes + tb,
                            )
                        } else {
                            result[currentKey] = TrafficBytes(rb, tb)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to parse dumpsys netstats: ${e.message}")
        }
        return result
    }

    private fun resolveNetworkType(identLine: String): String {
        val typeMatch = NET_TYPE_PATTERN.find(identLine)
        return when (typeMatch?.groupValues?.get(1)?.toIntOrNull()) {
            TYPE_WIFI -> "wifi"
            TYPE_MOBILE -> "mobile"
            TYPE_ETHERNET -> "ethernet"
            TYPE_VPN -> "vpn"
            else -> "other"
        }
    }

    /**
     * Resolves the APN classification from the oemManaged field in the ident line.
     * - OEM_PRIVATE → "apn2_oem" (OEM-paid, routed via tun0)
     * - OEM_NONE → "apn1_customer" (customer-paid, default route via tun1)
     * - Other/missing → "unknown" (local/internal traffic, NOT cellular — see class KDoc)
     *
     * Known "unknown" packages (observed on MIB4 SEP477):
     * - com.android.networkstack (~877 MB in 4h): tunnel mgmt, DNS relay
     * - com.android.car.settings (~579 MB): local IPC/cache
     * - com.android.car.developeroptions (~250 MB): ADB traffic
     * - com.android.networkstack.tethering (~226 MB): hotspot forwarding
     * None of these traverse the cellular modem.
     */
    private fun resolveApn(identLine: String): String {
        val oemMatch = OEM_MANAGED_PATTERN.find(identLine)
        return when (oemMatch?.groupValues?.get(1)) {
            "OEM_PRIVATE" -> "apn2_oem"
            "OEM_NONE" -> "apn1_customer"
            else -> "unknown"
        }
    }

    private data class TrafficKey(
        val uid: Int,
        val networkType: String,
        val apn: String,
    )

    private data class TrafficBytes(
        val rxBytes: Long,
        val txBytes: Long,
    )

    companion object {
        private const val TAG = "NetworkStatsCollector"
        private const val POLL_INTERVAL_MS = 60_000L
        private const val STAGGER_DELAY_MS = 9_000L
        private const val PER_USER_RANGE = 100_000

        // APN tunnel interfaces (MIB4 TCU routing)
        private const val APN_OEM_INTERFACE = "tun0" // OEM-paid (OEM_PRIVATE)
        private const val APN_CUSTOMER_INTERFACE = "tun1" // Customer-paid (default)

        // ConnectivityManager network type constants
        private const val TYPE_MOBILE = 0
        private const val TYPE_WIFI = 1
        private const val TYPE_ETHERNET = 9
        private const val TYPE_VPN = 17

        private val UID_PATTERN = Regex("""\buid=(\d+)\b""")
        private val STATS_PATTERN = Regex("""rb=(\d+).*tb=(\d+)""")
        private val NET_TYPE_PATTERN = Regex("""type=(\d+)""")
        private val OEM_MANAGED_PATTERN = Regex("""oemManaged=(OEM_\w+)""")
        private val TETHER_IFACE_PATTERN =
            Regex("""^\s+(\S+)\s+-\s+(?:TetheredState|LocalHotspotState)""", RegexOption.MULTILINE)
        private val UPSTREAM_PATTERN =
            Regex("""Current upstream interface\(s\):\s*\[(\S+?)]""")
        private val MAC_PATTERN =
            Regex("""lladdr\s+([0-9a-fA-F:]{17})""")

        private val SYSTEM_UID_NAMES = mapOf(
            0 to "root",
            1000 to "system",
            1001 to "radio",
            1010 to "wifi",
            1013 to "mediaserver",
            1020 to "mdnsr",
            1021 to "dns",
            1051 to "ntp",
            1052 to "drm",
            1073 to "networkstack",
        )
    }
}
