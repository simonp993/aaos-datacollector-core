package com.porsche.aaos.platform.telemetry.collector.media

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import com.porsche.aaos.platform.telemetry.core.logging.Logger
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
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private var sessionManager: MediaSessionManager? = null
    private val activeCallbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var userSwitchReceiver: BroadcastReceiver? = null

    // Previous state per controller package for prev/current payloads
    private val previousPlaybackState = mutableMapOf<String, Map<String, Any?>>()
    private val previousMetadata = mutableMapOf<String, Map<String, Any?>>()
    
    // Track the currently playing package (for source/app switching detection)
    private var currentlyPlayingPackage: String? = null
    
    // Track when a source change just occurred to suppress duplicate state changes
    // and to emit a cross-source track change when new source metadata arrives.
    private data class RecentSourceChange(
        val fromPackage: String,
        val toPackage: String,
        val timestamp: Long,
        val trackEmitted: Boolean = false,
    )
    private data class RecentStoppedSource(val packageName: String, val timestamp: Long)
    private data class RecentSkipIntent(val direction: String, val timestamp: Long)
    private var recentSourceChange: RecentSourceChange? = null
    private var recentStoppedSource: RecentStoppedSource? = null
    private val recentSkipIntentByPackage = mutableMapOf<String, RecentSkipIntent>()
    private val recentTrackChangeAtByPackage = mutableMapOf<String, Long>()
    private val SOURCE_CHANGE_SUPPRESS_MS = 3000L // Suppress old-source stop callbacks after source switch
    private val SKIP_INTENT_WINDOW_MS = 4000L
    private val POSITION_JUMP_THRESHOLD_MS = 3000L
    private val POSITION_JUMP_SUPPRESS_AFTER_TRACK_CHANGE_MS = 2000L
    
    // Track pending metadata changes per package (for debouncing)
    private data class PendingMetadataChange(
        val pkg: String,
        val prevTrack: Map<String, Any?>?,
        val currTrack: Map<String, Any?>,
        val timestamp: Long,
        var emitted: Boolean = false,
    )
    private val pendingMetadataChanges = mutableMapOf<String, PendingMetadataChange>()
    private val METADATA_DEBOUNCE_MS = 300L // Wait 300ms for metadata to fully load

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
            registerUserSwitchReceiver()
        }
    }

    override fun stop() {
        userSwitchReceiver?.let { context.unregisterReceiver(it) }
        userSwitchReceiver = null
        sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        
        // Flush any pending metadata changes
        pendingMetadataChanges.forEach { (pkg, pending) ->
            if (!pending.emitted && pending.prevTrack != null) {
                logger.d(TAG, "Flushing pending metadata change for $pkg on collector stop")
                emitTrackChangeEvent(pkg, pending.prevTrack, pending.currTrack, "Media_TrackChanged")
            }
        }
        pendingMetadataChanges.clear()
        
        // Clear current playing package and recent source change
        currentlyPlayingPackage = null
        recentSourceChange = null
        recentStoppedSource = null
        recentSkipIntentByPackage.clear()
        recentTrackChangeAtByPackage.clear()
        
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

    private fun registerUserSwitchReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val newUserId = intent.getIntExtra(EXTRA_USER_HANDLE, -1)
                if (newUserId < 0) return
                logger.i(TAG, "User switched to $newUserId, re-registering media sessions")
                onUserSwitched(newUserId)
            }
        }
        userSwitchReceiver = receiver
        context.registerReceiver(
            receiver,
            IntentFilter(ACTION_USER_SWITCHED),
            Context.RECEIVER_EXPORTED,
        )
    }

    private fun onUserSwitched(userId: Int) {
        val manager = sessionManager ?: return
        manager.removeOnActiveSessionsChangedListener(sessionListener)
        unregisterAllCallbacks()

        // Reset per-session state for the new user
        previousPlaybackState.clear()
        previousMetadata.clear()
        currentlyPlayingPackage = null
        recentSourceChange = null
        recentStoppedSource = null
        recentSkipIntentByPackage.clear()
        recentTrackChangeAtByPackage.clear()
        pendingMetadataChanges.clear()

        addSessionListenerForUser(manager, userId)
        @Suppress("UNCHECKED_CAST")
        val sessions = getActiveSessionsForUser(manager, userId)
        logger.i(TAG, "Re-registered for user $userId, found ${sessions.size} session(s)")
        sessions.forEach { controller -> registerCallback(controller) }
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
        val currentState = state?.state ?: PlaybackState.STATE_NONE
        val currentPosition = state?.position ?: 0L
        val pkg = controller.packageName ?: "unknown"

        val previous = previousPlaybackState[pkg]
        val previousState = (previous?.get("state") as? Int) ?: PlaybackState.STATE_NONE
        val now = System.currentTimeMillis()

        val current = mapOf(
            "package" to pkg,
            "state" to currentState,
            "stateLabel" to playbackStateLabel(currentState),
            "position" to currentPosition,
            "sampledAt" to now,
        )

        if (currentState == previousState) {
            maybeEmitPositionJumpEvent(controller, pkg, previous, currentState, currentPosition)
            previousPlaybackState[pkg] = current
            return
        }

        // Capture explicit skip intents from media session state.
        // This also covers "back to restart current song" where metadata may not change.
        if (currentState == PlaybackState.STATE_SKIPPING_TO_NEXT || currentState == PlaybackState.STATE_SKIPPING_TO_PREVIOUS) {
            val direction = if (currentState == PlaybackState.STATE_SKIPPING_TO_NEXT) "forward" else "backward"
            recentSkipIntentByPackage[pkg] = RecentSkipIntent(direction, System.currentTimeMillis())
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Media_TrackSkipped",
                        "trigger" to "user",
                        "metadata" to mapOf(
                            "package" to pkg,
                            "direction" to direction,
                        ),
                    ),
                ),
            )
            previousPlaybackState[pkg] = current
            return
        }

        // Detect app/source switching
        val isPlayingOrPaused = currentState == PlaybackState.STATE_PLAYING || currentState == PlaybackState.STATE_PAUSED

        val stoppedCandidate = recentStoppedSource
        val stoppedPreviousPkg =
            if (stoppedCandidate != null && (now - stoppedCandidate.timestamp) < SOURCE_CHANGE_SUPPRESS_MS) {
                stoppedCandidate.packageName
            } else {
                null
            }
        val sourceCandidate = currentlyPlayingPackage ?: stoppedPreviousPkg

        if (isPlayingOrPaused && sourceCandidate != pkg) {
            // App is switching to a different package
            logger.d(TAG, "App switch detected: $sourceCandidate → $pkg")
            
            // Emit source change event and record it
            val previousPkg = sourceCandidate
            if (previousPkg != null) {
                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "Media_SourceChanged",
                            "trigger" to "user",
                            "metadata" to mapOf(
                                "previous" to mapOf("package" to previousPkg),
                                "current" to mapOf("package" to pkg),
                            ),
                        ),
                    ),
                )
                // Record this source change to suppress individual state events
                recentSourceChange = RecentSourceChange(previousPkg, pkg, now)
            }
            currentlyPlayingPackage = pkg
            recentStoppedSource = null
            previousPlaybackState[pkg] = current
            return  // Don't emit a separate state change for the app start
        } else if (previousState != PlaybackState.STATE_NONE && (currentState == PlaybackState.STATE_NONE || currentState == PlaybackState.STATE_STOPPED)) {
            // App stopped playing
            currentlyPlayingPackage = null
            
            // Check if this stop is part of a recent source change suppression window
            if (recentSourceChange != null &&
                pkg == recentSourceChange!!.fromPackage &&
                (now - recentSourceChange!!.timestamp) < SOURCE_CHANGE_SUPPRESS_MS) {
                // Suppress this stop event as it's part of the source change
                logger.d(TAG, "Suppressing stop event for $pkg (part of source change)")
                previousPlaybackState[pkg] = current
                return
            }

            // Keep a short-lived marker to detect reverse-order source switches (STOP first, PLAY later).
            recentStoppedSource = RecentStoppedSource(pkg, now)
            if (currentlyPlayingPackage == pkg) {
                currentlyPlayingPackage = null
            }
        }

        // Suppress noisy ERROR callbacks from inactive/background media sessions.
        if (currentState == PlaybackState.STATE_ERROR && pkg != currentlyPlayingPackage) {
            logger.d(TAG, "Suppressing ERROR state for inactive package $pkg")
            previousPlaybackState[pkg] = current
            return
        }

        // Emit consolidated state change event
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Media_StateChanged",
                    "trigger" to "user",
                    "metadata" to mapOf(
                        "package" to pkg,
                        "state" to playbackStateLabel(currentState),
                        "position" to currentPosition,
                    ),
                ),
            ),
        )
        previousPlaybackState[pkg] = current
    }

    private fun sendMetadataEvent(controller: MediaController, metadata: android.media.MediaMetadata?) {
        val pkg = controller.packageName ?: "unknown"
        
        // If metadata is null or has null title/artist, defer emission (wait for real data)
        if (metadata == null || (metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) == null &&
                                  metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) == null)) {
            logger.d(TAG, "Skipping null metadata for $pkg, deferring emission")
            // Cancel any pending change for this package
            pendingMetadataChanges.remove(pkg)
            return
        }

        val current = mapOf(
            "package" to pkg,
            "title" to metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
            "artist" to metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
            "duration" to metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION),
        )

        // If metadata belongs to the new source right after source switch,
        // emit one cross-source track change (old track -> new track).
        val sourceChange = recentSourceChange
        if (sourceChange != null && !sourceChange.trackEmitted && pkg == sourceChange.toPackage) {
            val previousSourceTrack = previousMetadata[sourceChange.fromPackage]
            if (previousSourceTrack != null) {
                emitTrackChangeEvent(
                    pkg,
                    previousSourceTrack,
                    current,
                    "Media_TrackChanged",
                    resolveSkipDirection(pkg),
                )
                previousMetadata[pkg] = current
                pendingMetadataChanges.remove(pkg)
                recentSourceChange = sourceChange.copy(trackEmitted = true)
                return
            }
        }

        val previous = previousMetadata[pkg]

        // De-duplicate if title+artist match (duration can vary by small margin due to rounding)
        if (previous != null && isSameTrack(previous, current)) {
            logger.d(TAG, "Same track for $pkg, skipping")
            return
        }

        // Check if there's a pending metadata change already stored
        val pendingChange = pendingMetadataChanges[pkg]
        val now = System.currentTimeMillis()

        if (pendingChange != null && !pendingChange.emitted) {
            // There's a pending change, check if new metadata is different
            if (isSameTrack(pendingChange.currTrack, current)) {
                // Same track as pending, just update the timestamp (still waiting)
                pendingMetadataChanges[pkg] = pendingChange.copy(timestamp = now)
                return
            } else {
                // Different track now, emit the pending change first before storing the new one
                logger.d(TAG, "Emitting pending track change for $pkg before new track")
                emitTrackChangeEvent(
                    pkg,
                    pendingChange.prevTrack,
                    pendingChange.currTrack,
                    "Media_TrackChanged",
                    resolveSkipDirection(pkg),
                )
                pendingMetadataChanges[pkg] = pendingChange.copy(emitted = true)
                previousMetadata[pkg] = pendingChange.currTrack
            }
        }

        // Store the new metadata change as pending (wait for debounce window)
        logger.d(TAG, "Storing pending metadata change for $pkg")
        pendingMetadataChanges[pkg] = PendingMetadataChange(pkg, previous, current, now)

        // Emit after debounce delay using a simple timer approach
        // (In a real app, you'd use a coroutine delay, but we're in a callback)
        // For now, emit immediately if previous exists and is different
        if (previous != null && !isSameTrack(previous, current)) {
            logger.d(TAG, "Emitting track event for $pkg")
            emitTrackChangeEvent(
                pkg,
                previous,
                current,
                "Media_TrackChanged",
                resolveSkipDirection(pkg),
            )
            pendingMetadataChanges[pkg]?.emitted = true
            previousMetadata[pkg] = current
        } else if (previous == null) {
            // First track, just store it without emitting
            logger.d(TAG, "First track for $pkg, storing without emit")
            previousMetadata[pkg] = current
            pendingMetadataChanges[pkg]?.emitted = true
        }
    }

    private fun emitTrackChangeEvent(
        pkg: String,
        previous: Map<String, Any?>?,
        current: Map<String, Any?>,
        actionName: String,
        direction: String? = null,
    ) {
        recentTrackChangeAtByPackage[pkg] = System.currentTimeMillis()

        val previousForTelemetry = previous?.let { mapOf("package" to (it["package"] ?: pkg)) }
        val currentForTelemetry = mapOf("package" to (current["package"] ?: pkg))
        val metadata = mutableMapOf<String, Any?>(
            "previous" to previousForTelemetry,
            "current" to currentForTelemetry,
            "direction" to (direction ?: "unknown"),
        )

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to actionName,
                    "trigger" to "user",
                    "metadata" to metadata,
                ),
            ),
        )
    }

    private fun resolveSkipDirection(pkg: String): String? {
        val intent = recentSkipIntentByPackage[pkg] ?: return null
        val ageMs = System.currentTimeMillis() - intent.timestamp
        if (ageMs > SKIP_INTENT_WINDOW_MS) {
            recentSkipIntentByPackage.remove(pkg)
            return null
        }
        recentSkipIntentByPackage.remove(pkg)
        return intent.direction
    }

    private fun maybeEmitPositionJumpEvent(
        controller: MediaController,
        pkg: String,
        previousState: Map<String, Any?>?,
        currentState: Int,
        currentPosition: Long,
    ) {
        if (previousState == null) return
        val now = System.currentTimeMillis()
        val lastTrackChangeAt = recentTrackChangeAtByPackage[pkg]
        if (lastTrackChangeAt != null && (now - lastTrackChangeAt) < POSITION_JUMP_SUPPRESS_AFTER_TRACK_CHANGE_MS) {
            return
        }
        if (currentState != PlaybackState.STATE_PLAYING && currentState != PlaybackState.STATE_PAUSED) {
            return
        }

        val previousPosition = previousState["position"] as? Long ?: return
        val previousSampledAt = previousState["sampledAt"] as? Long

        val observedDeltaMs = currentPosition - previousPosition
        val deltaFromExpectedMs = if (previousSampledAt != null) {
            val elapsedWallMs = now - previousSampledAt
            observedDeltaMs - elapsedWallMs
        } else {
            observedDeltaMs
        }
        if (kotlin.math.abs(deltaFromExpectedMs) < POSITION_JUMP_THRESHOLD_MS) return

        // Limit to same-track jumps to avoid duplicate signal on actual track changes.
        val previousTrack = previousMetadata[pkg] ?: return
        val metadata = controller.metadata ?: return
        val currentTrack = mapOf(
            "package" to pkg,
            "title" to metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
            "artist" to metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
            "duration" to metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION),
        )
        if (!isSameTrack(previousTrack, currentTrack)) return

        val direction = if (deltaFromExpectedMs < 0) "backward" else "forward"
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Media_PositionJumped",
                    "trigger" to "user",
                    "metadata" to mapOf(
                        "package" to pkg,
                        "direction" to direction,
                        "previousPosition" to previousPosition,
                        "currentPosition" to currentPosition,
                        "deltaMs" to observedDeltaMs,
                        "deltaFromExpectedMs" to deltaFromExpectedMs,
                        "state" to playbackStateLabel(currentState),
                    ),
                ),
            ),
        )
    }

    private fun isSameTrack(
        previous: Map<String, Any?>,
        current: Map<String, Any?>,
    ): Boolean {
        // Same track de-duplication only applies within the same package/source.
        if (previous["package"] != current["package"]) {
            return false
        }

        // Same track if title and artist are identical
        if (previous["title"] != current["title"] || previous["artist"] != current["artist"]) {
            return false
        }
        // Allow small duration differences (rounding tolerance ~400ms)
        val prevDuration = previous["duration"] as? Long ?: return false
        val currentDuration = current["duration"] as? Long ?: return false
        val durationDiff = kotlin.math.abs(prevDuration - currentDuration)
        return durationDiff < 400 // tolerance in milliseconds
    }

    private fun playbackStateLabel(state: Int?): String = when (state) {
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
        PlaybackState.STATE_REWINDING -> "REWINDING"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_ERROR -> "ERROR"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
        else -> "UNKNOWN($state)"
    }

    companion object {
        private const val TAG = "MediaPlaybackCollector"
        private const val ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED"
        private const val EXTRA_USER_HANDLE = "android.intent.extra.user_handle"
    }
}
