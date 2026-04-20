package com.porsche.datacollector.collector.media

import android.app.ActivityManager
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.datacollector.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MediaPlaybackCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "MediaPlayback"

    private var sessionManager: MediaSessionManager? = null
    private val activeCallbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            logger.d(TAG, "Active sessions changed: ${controllers?.size ?: 0}")
            unregisterAllCallbacks()
            controllers?.forEach { controller -> registerCallback(controller) }
        }

    override suspend fun start() {
        logger.i(TAG, "Starting media playback monitoring")
        withContext(Dispatchers.Main) {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            sessionManager = manager

            // Service runs as user 0, but media sessions live on the foreground user.
            val userId = getCurrentUserId()
            logger.i(TAG, "Targeting user $userId for media sessions")

            addSessionListenerForUser(manager, userId)

            @Suppress("UNCHECKED_CAST")
            val sessions = getActiveSessionsForUser(manager, userId)
            logger.i(TAG, "Found ${sessions.size} active session(s)")
            sessions.forEach { controller -> registerCallback(controller) }
        }
    }

    override fun stop() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        unregisterAllCallbacks()
        sessionManager = null
        logger.i(TAG, "Stopped")
    }

    @Suppress("UNCHECKED_CAST")
    private fun getActiveSessionsForUser(
        manager: MediaSessionManager,
        userId: Int,
    ): List<MediaController> {
        return try {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val userHandle = userHandleClass.getDeclaredMethod("of", Int::class.javaPrimitiveType)
                .invoke(null, userId)
            val method = MediaSessionManager::class.java.getDeclaredMethod(
                "getActiveSessionsForUser",
                android.content.ComponentName::class.java,
                userHandleClass,
            )
            method.isAccessible = true
            method.invoke(manager, null, userHandle) as List<MediaController>
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.targetException else e
            logger.w(TAG, "getActiveSessionsForUser failed (${cause::class.simpleName}): ${cause.message}")
            manager.getActiveSessions(null)
        }
    }

    private fun addSessionListenerForUser(manager: MediaSessionManager, userId: Int) {
        try {
            // Signature: addOnActiveSessionsChangedListener(OnActiveSessionsChangedListener, ComponentName, int, Executor)
            val method = MediaSessionManager::class.java.getDeclaredMethod(
                "addOnActiveSessionsChangedListener",
                MediaSessionManager.OnActiveSessionsChangedListener::class.java,
                android.content.ComponentName::class.java,
                Int::class.javaPrimitiveType,
                java.util.concurrent.Executor::class.java,
            )
            method.isAccessible = true
            val mainExecutor = context.mainExecutor
            method.invoke(manager, sessionListener, null, userId, mainExecutor)
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.targetException else e
            logger.w(TAG, "Cross-user listener failed (${cause::class.simpleName}): ${cause.message}")
            manager.addOnActiveSessionsChangedListener(sessionListener, null)
        }
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

    private fun registerCallback(controller: MediaController) {
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                sendPlaybackEvent(controller, state)
            }

            override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                sendMetadataEvent(controller, metadata)
            }
        }
        controller.registerCallback(callback)
        activeCallbacks[controller] = callback

        // Emit current state immediately
        sendPlaybackEvent(controller, controller.playbackState)
        sendMetadataEvent(controller, controller.metadata)
    }

    private fun unregisterAllCallbacks() {
        activeCallbacks.forEach { (controller, callback) ->
            controller.unregisterCallback(callback)
        }
        activeCallbacks.clear()
    }

    private fun sendPlaybackEvent(controller: MediaController, state: PlaybackState?) {
        telemetry.send(
            TelemetryEvent(
                eventId = "media.playback_state",
                payload = mapOf(
                    "package" to controller.packageName,
                    "state" to (state?.state ?: PlaybackState.STATE_NONE),
                    "position" to (state?.position ?: 0L),
                ),
            ),
        )
    }

    private fun sendMetadataEvent(controller: MediaController, metadata: android.media.MediaMetadata?) {
        if (metadata == null) return
        telemetry.send(
            TelemetryEvent(
                eventId = "media.metadata",
                payload = mapOf(
                    "package" to controller.packageName,
                    "title" to metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                    "artist" to metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                    "duration" to metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION),
                ),
            ),
        )
    }

    companion object {
        private const val TAG = "MediaPlaybackCollector"
    }
}
