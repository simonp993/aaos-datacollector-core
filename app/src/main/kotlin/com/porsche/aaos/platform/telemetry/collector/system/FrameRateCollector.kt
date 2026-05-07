package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Display
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Measures system-wide frame rate using Choreographer vsync callbacks.
 *
 * Approach: Choreographer is tied to the primary display's vsync (60Hz on MIB4).
 * All displays share the same composition pipeline — display 0 is the "pacesetter",
 * displays 1-3 are "followers". A frame drop on the primary = jank on all screens.
 * Per-display FPS is therefore redundant; if one lags, they all lag in sync.
 *
 * Per-display FPS would require dumpsys SurfaceFlinger (needs platform signature / UID 1000).
 * On production devices this works; on dev emulators it doesn't. The Choreographer approach
 * works universally and is sufficient for detecting smoothness drops.
 *
 * Payload (FrameRate_Current): emitted every 60s with 12 samples (5s each).
 *   sampleSchema: [timestampMillis, frames, dropped, fps]
 *   - timestampMillis: Unix timestamp (milliseconds since 1970-01-01)
 *   - frames: vsync callbacks fired in the 5s window (target: 300 at 60Hz)
 *   - dropped: frames that took >1.5x expected period (jank indicator)
 *   - fps: frames / 5.0 — effective frame rate
 *   Example: [1778082427000, 300, 0, 60.0] = "at that moment, 300 frames in 5s, 0 dropped, 60fps smooth"
 *
 * Event (Display_StateChanged): emitted instantly when any display turns ON or OFF.
 */
class FrameRateCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "FrameRate"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private var batchJob: Job? = null
    private var choreographerThread: HandlerThread? = null
    private var choreographer: Choreographer? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var displayStates: MutableMap<Int, Int> = mutableMapOf()

    // Vsync timing tracking
    private val frameCount = AtomicInteger(0)
    private val droppedFrames = AtomicInteger(0)
    private val lastFrameTimeNanos = AtomicLong(0)
    private var expectedFramePeriodNanos = 16_666_666L // default 60Hz

    override suspend fun start() {
        logger.i(TAG, "Starting frame rate monitoring (Choreographer + DisplayManager)")

        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val primaryDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        expectedFramePeriodNanos = (1_000_000_000.0 / primaryDisplay.refreshRate).toLong()

        // Start handler thread for choreographer and display listener
        val thread = HandlerThread("FrameRateChoreographer").apply { start() }
        choreographerThread = thread

        // Track initial display states
        displayManager.displays.forEach { displayStates[it.displayId] = it.state }

        // Listen for display state changes (on/off)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                val display = displayManager.getDisplay(displayId) ?: return
                val newState = display.state
                val prevState = displayStates[displayId]
                if (prevState != null && prevState != newState) {
                    val isOn = newState == Display.STATE_ON
                    val wasOn = prevState == Display.STATE_ON
                    if (isOn != wasOn) {
                        telemetry.send(
                            TelemetryEvent(
                                signalId = signalId,
                                payload = mapOf(
                                    "actionName" to "Display_StateChanged",
                                    "trigger" to "system",
                                    "metadata" to mapOf(
                                        "displayId" to displayId,
                                        "state" to if (isOn) "ON" else "OFF",
                                    ),
                                ),
                            ),
                        )
                        logger.d(TAG, "Display $displayId state: ${displayStateLabel(prevState)} -> ${displayStateLabel(newState)}")
                    }
                }
                displayStates[displayId] = newState
            }

            override fun onDisplayAdded(displayId: Int) {
                val display = displayManager.getDisplay(displayId) ?: return
                displayStates[displayId] = display.state
            }

            override fun onDisplayRemoved(displayId: Int) {
                displayStates.remove(displayId)
            }
        }
        displayListener = listener
        displayManager.registerDisplayListener(listener, Handler(thread.looper))

        Handler(thread.looper).post {
            choreographer = Choreographer.getInstance()
            postNextFrame()
        }

        // Batch job: collect samples every 5s, emit every 60s
        batchJob = CoroutineScope(Dispatchers.Default).launch {
            val samples = mutableListOf<List<Any>>()

            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)

                val frames = frameCount.getAndSet(0)
                val dropped = droppedFrames.getAndSet(0)
                val fps = String.format("%.1f", frames / (SAMPLE_INTERVAL_MS / 1000.0)).toDouble()

                // Compact: [timestampMillis, frameCount, droppedFrames, measuredFps]
                samples.add(listOf(System.currentTimeMillis(), frames, dropped, fps))

                if (samples.size >= SAMPLES_PER_BATCH) {
                    telemetry.send(
                        TelemetryEvent(
                            signalId = signalId,
                            payload = mapOf(
                                "actionName" to "Display_FrameRate",
                                "trigger" to "heartbeat",
                                "metadata" to mapOf(
                                    "sampleSchema" to listOf("timestampMillis", "frames", "dropped", "fps"),
                                    "samples" to samples.toList(),
                                ),
                            ),
                        ),
                    )
                    logger.d(TAG, "Emitted ${samples.size} samples")
                    samples.clear()
                }
            }
        }
    }

    override fun stop() {
        batchJob?.cancel()
        batchJob = null
        displayListener?.let {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.unregisterDisplayListener(it)
        }
        displayListener = null
        choreographerThread?.quitSafely()
        choreographerThread = null
        choreographer = null
        logger.i(TAG, "Stopped")
    }

    private fun postNextFrame() {
        choreographer?.postFrameCallback { frameTimeNanos ->
            val lastTime = lastFrameTimeNanos.getAndSet(frameTimeNanos)
            if (lastTime > 0) {
                val delta = frameTimeNanos - lastTime
                frameCount.incrementAndGet()
                // If the frame took more than 1.5x the expected period, count as dropped
                val droppedCount = ((delta / expectedFramePeriodNanos) - 1).coerceAtLeast(0)
                if (droppedCount > 0) {
                    droppedFrames.addAndGet(droppedCount.toInt())
                }
            }
            postNextFrame()
        }
    }

    companion object {
        private const val TAG = "FrameRateCollector"
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val SAMPLES_PER_BATCH = 12

        private fun displayStateLabel(state: Int): String = when (state) {
            Display.STATE_OFF -> "OFF"
            Display.STATE_ON -> "ON"
            Display.STATE_DOZE -> "DOZE"
            Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
            Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
            else -> "UNKNOWN($state)"
        }
    }
}
