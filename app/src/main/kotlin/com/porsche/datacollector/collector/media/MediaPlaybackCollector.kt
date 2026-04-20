package com.porsche.datacollector.collector.media

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.sportapps.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
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
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = manager

        manager.addOnActiveSessionsChangedListener(sessionListener, null)

        // Capture current sessions immediately
        manager.getActiveSessions(null).forEach { controller -> registerCallback(controller) }
    }

    override fun stop() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        unregisterAllCallbacks()
        sessionManager = null
        logger.i(TAG, "Stopped")
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
