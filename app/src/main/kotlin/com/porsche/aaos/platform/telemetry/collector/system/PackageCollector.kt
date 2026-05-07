package com.porsche.aaos.platform.telemetry.collector.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PackageCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Package"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private var packageReceiver: BroadcastReceiver? = null

    override suspend fun start() {
        logger.i(TAG, "Starting package monitoring (multi-user, chunked)")

        val userIds = discoverUserIds()
        logger.i(TAG, "Monitoring user IDs: $userIds")

        // Emit chunked package inventory per user
        collectInstalledPackages(userIds)

        // Register for package change broadcasts for ALL users
        val receiver = object : BroadcastReceiver() {
            @Suppress("TooGenericExceptionCaught")
            override fun onReceive(ctx: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                val userId = intent.extras?.getInt("android.intent.extra.user_handle", 0) ?: 0
                val actionName = when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> "Package_Installed"
                    Intent.ACTION_PACKAGE_REMOVED -> "Package_Removed"
                    Intent.ACTION_PACKAGE_REPLACED -> "Package_Updated"
                    else -> return
                }
                val metadata = mutableMapOf<String, Any>(
                    "package" to packageName,
                    "userId" to userId,
                )
                // Resolve version info (not available for removals)
                if (actionName != "Package_Removed") {
                    try {
                        val pkgInfo = ctx.packageManager.getPackageInfo(packageName, 0)
                        metadata["versionName"] = pkgInfo.versionName ?: ""
                        metadata["versionCode"] = pkgInfo.longVersionCode
                    } catch (e: Exception) {
                        logger.d(TAG, "Could not resolve version for $packageName: ${e.message}")
                    }
                }
                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to actionName,
                            "trigger" to "system",
                            "metadata" to metadata,
                        ),
                    ),
                )
            }
        }
        packageReceiver = receiver

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // registerReceiverForAllUsers catches installs on user 10+
        try {
            val method = context.javaClass.getMethod(
                "registerReceiverForAllUsers",
                BroadcastReceiver::class.java,
                IntentFilter::class.java,
                String::class.java,
                android.os.Handler::class.java,
            )
            method.invoke(context, receiver, filter, null, null)
            logger.i(TAG, "Registered broadcast receiver for ALL users")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Fallback: register for current user only
            context.registerReceiver(receiver, filter)
            logger.w(TAG, "registerReceiverForAllUsers failed, using single-user: ${e.message}")
        }
    }

    override fun stop() {
        packageReceiver?.let { context.unregisterReceiver(it) }
        packageReceiver = null
        logger.i(TAG, "Stopped")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collectInstalledPackages(userIds: List<Int>) {
        for (userId in userIds) {
            try {
                val packages = getPackagesForUser(userId)
                // Emit in chunks to avoid logcat truncation
                val chunks = packages.chunked(CHUNK_SIZE)
                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    val packageList = chunk.map { pkg ->
                        listOf(
                            pkg.packageName,
                            pkg.versionName ?: "",
                            pkg.longVersionCode,
                        )
                    }
                    telemetry.send(
                        TelemetryEvent(
                            signalId = signalId,
                            payload = mapOf(
                                "actionName" to "Package_Inventory",
                                "trigger" to "system",
                                "metadata" to mapOf(
                                    "userId" to userId,
                                    "totalCount" to packages.size,
                                    "chunk" to chunkIndex + 1,
                                    "totalChunks" to chunks.size,
                                    "sampleSchema" to listOf("package", "versionName", "versionCode"),
                                    "packages" to packageList,
                                ),
                            ),
                        ),
                    )
                }
                logger.i(TAG, "User $userId: ${packages.size} packages (${chunks.size} chunks)")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to collect packages for user $userId", e)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    private fun getPackagesForUser(userId: Int): List<PackageInfo> {
        // Try hidden API: PackageManager.getInstalledPackagesAsUser(flags, userId)
        try {
            val pm = context.packageManager
            val method = pm.javaClass.getMethod(
                "getInstalledPackagesAsUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val result = method.invoke(pm, 0, userId) as? List<PackageInfo>
            if (!result.isNullOrEmpty()) return result
        } catch (e: Exception) {
            logger.d(TAG, "getInstalledPackagesAsUser failed for user $userId: ${e.message}")
        }

        // Fallback: createContextAsUser + getInstalledPackages (hidden API via reflection)
        try {
            val userHandle = UserHandle::class.java
                .getDeclaredMethod("of", Int::class.javaPrimitiveType)
                .invoke(null, userId) as UserHandle
            val createMethod = context.javaClass.getMethod(
                "createContextAsUser",
                UserHandle::class.java,
                Int::class.javaPrimitiveType,
            )
            val userContext = createMethod.invoke(context, userHandle, 0) as Context
            return userContext.packageManager.getInstalledPackages(0)
        } catch (e: Exception) {
            logger.d(TAG, "createContextAsUser fallback failed for user $userId: ${e.message}")
        }

        // Last resort: current user only
        return if (userId == 0) context.packageManager.getInstalledPackages(0) else emptyList()
    }

    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    private fun discoverUserIds(): List<Int> {
        val um = context.getSystemService(Context.USER_SERVICE) ?: return listOf(0)
        try {
            val usersMethod = um.javaClass.getMethod("getUsers", Boolean::class.javaPrimitiveType)
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
        try {
            val usersMethod = um.javaClass.getMethod("getUsers")
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
        return listOf(0)
    }

    companion object {
        private const val TAG = "PackageCollector"
        // ~30 packages × ~130 bytes each ≈ 3.9KB per chunk (stays under 4KB logcat/transport limit)
        private const val CHUNK_SIZE = 30
    }
}
