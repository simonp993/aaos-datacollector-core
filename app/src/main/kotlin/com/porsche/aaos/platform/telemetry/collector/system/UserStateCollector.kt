package com.porsche.aaos.platform.telemetry.collector.system

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Monitors Android user lifecycle events on AAOS multi-user systems.
 *
 * Emits:
 * - **User_StateSnapshot** at startup with the current foreground user and all known users
 * - **User_StateChanged** on USER_SWITCHED (previous + current foreground user)
 * - **User_Added** / **User_Removed** when users are created or destroyed (e.g. ephemeral guests)
 */
class UserStateCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "UserState"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private var userSwitchReceiver: BroadcastReceiver? = null
    private var userLifecycleReceiver: BroadcastReceiver? = null

    @Volatile
    private var currentForegroundUser: Int = -1

    override suspend fun start() {
        logger.i(TAG, "Starting user state monitoring")

        currentForegroundUser = getCurrentUserId()
        val allUsers = discoverUserIds()

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to ACTION_SNAPSHOT,
                    "trigger" to "startup",
                    "metadata" to buildUsersMetadata(),
                ),
            ),
        )
        logger.i(TAG, "Snapshot: foreground=$currentForegroundUser, all=$allUsers")

        registerUserSwitchReceiver()
        registerUserLifecycleReceiver()
    }

    override fun stop() {
        userSwitchReceiver?.let { context.unregisterReceiver(it) }
        userSwitchReceiver = null
        userLifecycleReceiver?.let { context.unregisterReceiver(it) }
        userLifecycleReceiver = null
        logger.i(TAG, "Stopped")
    }

    private fun registerUserSwitchReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val newUserId = intent.getIntExtra(EXTRA_USER_HANDLE, -1)
                if (newUserId < 0) return
                val previousUser = currentForegroundUser
                currentForegroundUser = newUserId

                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to ACTION_CHANGED,
                            "trigger" to "user",
                            "metadata" to buildUsersMetadata(
                                previousForegroundUser = previousUser,
                            ),
                        ),
                    ),
                )
                logger.i(TAG, "User switched: $previousUser → $newUserId")
            }
        }
        userSwitchReceiver = receiver
        context.registerReceiver(
            receiver,
            IntentFilter(ACTION_USER_SWITCHED),
            Context.RECEIVER_EXPORTED,
        )
    }

    private fun registerUserLifecycleReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val userId = intent.getIntExtra(EXTRA_USER_HANDLE, -1)
                if (userId < 0) return
                val action = intent.action ?: return

                val event = when (action) {
                    ACTION_USER_ADDED -> "added"
                    ACTION_USER_REMOVED -> "removed"
                    else -> return
                }

                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to ACTION_LIFECYCLE,
                            "trigger" to "system",
                            "metadata" to buildSingleUserMetadata(userId, event),
                        ),
                    ),
                )
                logger.i(TAG, "$ACTION_LIFECYCLE: event=$event userId=$userId")
            }
        }
        userLifecycleReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_USER_ADDED)
            addAction(ACTION_USER_REMOVED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun getCurrentUserId(): Int {
        return try {
            val method = ActivityManager::class.java.getDeclaredMethod("getCurrentUser")
            method.invoke(null) as Int
        } catch (e: Exception) {
            logger.w(TAG, "Cannot determine current user, defaulting to 0: ${e.message}")
            0
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun discoverUserIds(): List<Int> {
        val um = context.getSystemService(Context.USER_SERVICE) ?: return listOf(0)

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

        logger.w(TAG, "All user discovery methods failed, defaulting to user 0")
        return listOf(0)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun buildUserRow(userId: Int, previousForegroundUser: Int? = null): List<Any?> {
        val um = context.getSystemService(Context.USER_SERVICE)
        var name: String? = null
        var flags: Int? = null

        if (um != null) {
            try {
                val getUserInfoMethod = um.javaClass
                    .getMethod("getUserInfo", Int::class.javaPrimitiveType)
                val userInfo = getUserInfoMethod.invoke(um, userId)
                if (userInfo != null) {
                    name = userInfo.javaClass.getField("name").get(userInfo) as? String
                    flags = userInfo.javaClass.getField("flags").getInt(userInfo)
                }
            } catch (e: Exception) {
                logger.d(TAG, "getUserInfo($userId) failed: ${e.message}")
            }
        }

        val isFg = if (userId == currentForegroundUser) 1 else 0
        return if (previousForegroundUser != null) {
            val wasFg = if (userId == previousForegroundUser) 1 else 0
            listOf(userId, wasFg, isFg, name, flags)
        } else {
            listOf(userId, isFg, name, flags)
        }
    }

    private fun buildUsersMetadata(
        previousForegroundUser: Int? = null,
    ): Map<String, Any?> {
        val userIds = discoverUserIds()
        val schema = if (previousForegroundUser != null) USER_SCHEMA_SWITCHED else USER_SCHEMA
        return mapOf(
            "userSchema" to schema,
            "users" to userIds.map { buildUserRow(it, previousForegroundUser) },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun buildSingleUserMetadata(userId: Int, event: String): Map<String, Any?> {
        val um = context.getSystemService(Context.USER_SERVICE)
        var name: String? = null
        var flags: Int? = null

        if (um != null) {
            try {
                val getUserInfoMethod = um.javaClass
                    .getMethod("getUserInfo", Int::class.javaPrimitiveType)
                val userInfo = getUserInfoMethod.invoke(um, userId)
                if (userInfo != null) {
                    name = userInfo.javaClass.getField("name").get(userInfo) as? String
                    flags = userInfo.javaClass.getField("flags").getInt(userInfo)
                }
            } catch (e: Exception) {
                logger.d(TAG, "getUserInfo($userId) failed: ${e.message}")
            }
        }

        return mapOf(
            "event" to event,
            "id" to userId,
            "name" to name,
            "flags" to flags,
        )
    }

    companion object {
        private const val TAG = "UserStateCollector"
        private const val ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED"
        private const val ACTION_USER_ADDED = "android.intent.action.USER_ADDED"
        private const val ACTION_USER_REMOVED = "android.intent.action.USER_REMOVED"
        private const val EXTRA_USER_HANDLE = "android.intent.extra.user_handle"

        private const val ACTION_SNAPSHOT = "User_StateSnapshot"
        private const val ACTION_CHANGED = "User_StateChanged"
        private const val ACTION_LIFECYCLE = "User_LifecycleChanged"

        private val USER_SCHEMA = listOf("id", "currentForegroundUser", "name", "flags")
        private val USER_SCHEMA_SWITCHED = listOf(
            "id",
            "previousForegroundUser",
            "currentForegroundUser",
            "name",
            "flags",
        )
    }
}
