package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.HandlerThread
import android.os.Looper
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Captures touch events per display using the framework-level
 * InputManager.monitorGestureInput system API (accessed via reflection).
 *
 * Requires `android.permission.MONITOR_INPUT` (signature-level, platform-signed).
 *
 * InputMonitor is accessed via reflection to avoid hidden API classloading issues.
 * InputChannel and InputEventReceiver are resolved from compile-only stubs and
 * exist in the framework at runtime.
 */
class TouchInputCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "TouchInput"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false
    private var monitorThread: HandlerThread? = null

    // InputMonitor is a hidden class — store as Any to avoid direct classloading
    private val monitors = mutableListOf<Any>()
    private val receivers = mutableListOf<InputEventReceiver>()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting touch input monitoring via InputManager")

        val thread = HandlerThread("TouchInputMonitor").apply { start() }
        monitorThread = thread

        val displayManager = context.getSystemService(DisplayManager::class.java)
        val inputManager = context.getSystemService(InputManager::class.java)

        for (display in displayManager.displays) {
            try {
                setupMonitor(inputManager, display.displayId, display.name, thread.looper)
                logger.i(TAG, "Monitoring display ${display.displayId} (${display.name})")
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.e(
                    TAG,
                    "Failed to monitor display ${display.displayId} (${display.name})",
                    e,
                )
            }
        }

        if (monitors.isEmpty()) {
            logger.w(TAG, "No displays could be monitored - stopping thread")
            thread.quitSafely()
            return
        }

        logger.i(TAG, "Monitoring ${monitors.size} display(s)")

        suspendCancellableCoroutine<Nothing> { cont ->
            cont.invokeOnCancellation { stop() }
        }
    }

    override fun stop() {
        running = false
        receivers.forEach { receiver ->
            try {
                receiver.dispose()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.e(TAG, "Error disposing receiver", e)
            }
        }
        receivers.clear()
        monitors.forEach { monitor ->
            closeMonitor(monitor)
        }
        monitors.clear()
        monitorThread?.quitSafely()
        monitorThread = null
        logger.i(TAG, "Stopped")
    }

    private fun closeMonitor(monitor: Any) {
        try {
            // InputMonitor implements Closeable
            if (monitor is Closeable) {
                monitor.close()
            } else {
                monitor.javaClass.getMethod("close").invoke(monitor)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.e(TAG, "Error closing monitor", e)
        }
    }

    @Suppress("LongMethod")
    private fun setupMonitor(
        inputManager: InputManager,
        displayId: Int,
        displayName: String,
        looper: Looper,
    ) {
        // Call hidden InputManager.monitorGestureInput(String, int) → InputMonitor
        val monitorMethod = InputManager::class.java.getDeclaredMethod(
            "monitorGestureInput",
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        val monitor = monitorMethod.invoke(
            inputManager,
            "datacollector-$displayId",
            displayId,
        ) ?: error("monitorGestureInput returned null for display $displayId")

        monitors.add(monitor)

        // Call InputMonitor.getInputChannel() → InputChannel (via reflection)
        val getChannelMethod = monitor.javaClass.getMethod("getInputChannel")
        val channel = getChannelMethod.invoke(monitor) as InputChannel

        val receiver = TouchEventReceiver(channel, looper, displayId, displayName)
        receivers.add(receiver)
    }

    private inner class TouchEventReceiver(
        channel: InputChannel,
        looper: Looper,
        private val displayId: Int,
        private val displayName: String,
    ) : InputEventReceiver(channel, looper) {

        private var gestureStartX = 0f
        private var gestureStartY = 0f
        private var gestureStartTime = 0L
        private var moveCount = 0
        private var maxPointerCount = 0
        private var inGesture = false

        override fun onInputEvent(event: InputEvent) {
            try {
                if (event is MotionEvent && running) {
                    handleMotionEvent(event)
                }
            } finally {
                finishInputEvent(event, false)
            }
        }

        private fun handleMotionEvent(event: MotionEvent) {
            // Track max finger count across the entire gesture
            if (event.pointerCount > maxPointerCount) {
                maxPointerCount = event.pointerCount
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.x
                    gestureStartY = event.y
                    gestureStartTime = event.eventTime
                    moveCount = 0
                    maxPointerCount = event.pointerCount
                    inGesture = true
                    sendTouchEvent("Touch_Down", event)
                }
                MotionEvent.ACTION_MOVE -> {
                    moveCount++
                }
                MotionEvent.ACTION_UP -> {
                    if (inGesture && moveCount > 0) {
                        sendSwipeSummary(event)
                    }
                    sendTouchEvent("Touch_Up", event)
                    inGesture = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (inGesture && moveCount > 0) {
                        sendSwipeSummary(event)
                    }
                    sendTouchEvent("Touch_Cancel", event)
                    inGesture = false
                }
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP,
                -> {
                    // No separate event — maxPointerCount captures multi-finger info
                }
            }
        }

        private fun sendTouchEvent(actionName: String, event: MotionEvent) {
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to actionName,
                        "trigger" to "user",
                        "metadata" to mapOf(
                            "display" to displayName,
                            "displayId" to displayId,
                            "x" to event.x,
                            "y" to event.y,
                            "pointerCount" to maxPointerCount,
                        ),
                    ),
                ),
            )
        }

        private fun sendSwipeSummary(endEvent: MotionEvent) {
            val durationMs = endEvent.eventTime - gestureStartTime
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Touch_Swipe",
                        "trigger" to "user",
                        "metadata" to mapOf(
                            "display" to displayName,
                            "displayId" to displayId,
                            "startX" to gestureStartX,
                            "startY" to gestureStartY,
                            "endX" to endEvent.x,
                            "endY" to endEvent.y,
                            "durationMs" to durationMs,
                            "moveCount" to moveCount,
                            "pointerCount" to maxPointerCount,
                        ),
                    ),
                ),
            )
        }
    }

    companion object {
        private const val TAG = "TouchInputCollector"
    }
}
