package com.porsche.datacollector.collector.system

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.HandlerThread
import android.os.Looper
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.core.logging.Logger
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
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
            val actionName = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
                MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                else -> return
            }

            telemetry.send(
                TelemetryEvent(
                    eventId = "input.touch",
                    payload = mapOf(
                        "display" to displayName,
                        "displayId" to displayId,
                        "action" to actionName,
                        "x" to event.x,
                        "y" to event.y,
                        "pointerCount" to event.pointerCount,
                    ),
                ),
            )
        }
    }

    companion object {
        private const val TAG = "TouchInputCollector"
    }
}
