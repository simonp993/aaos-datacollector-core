package com.porsche.aaos.platform.telemetry.collector.system

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

class MemoryCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Memory"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private var callback: ComponentCallbacks2? = null
    private var previousLevel: Int? = null
    private var debugJob: Job? = null

    override suspend fun start() {
        logger.i(TAG, "Starting memory monitoring (event-driven via onTrimMemory + periodic snapshot)")

        // Poll every 5s, batch 12 samples, emit every 60s as Memory_LevelCurrent
        debugJob = CoroutineScope(Dispatchers.Default).launch {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalMem = memInfo.totalMem

            val samples = mutableListOf<List<Any>>()
            delay(STAGGER_DELAY_MS) // Stagger to spread flush bursts
            while (isActive) {
                activityManager.getMemoryInfo(memInfo)

                // Compact: [timestampMillis, availMb, trimLevel, lowMemory]
                samples.add(
                    listOf(
                        System.currentTimeMillis(),
                        memInfo.availMem / 1_048_576,
                        previousLevel ?: -1,
                        if (memInfo.lowMemory) 1 else 0,
                    ),
                )

                if (samples.size >= SAMPLES_PER_BATCH) {
                    telemetry.send(
                        TelemetryEvent(
                            signalId = signalId,
                            payload = mapOf(
                                "actionName" to "Memory_Usage",
                                "trigger" to "heartbeat",
                                "metadata" to mapOf(
                                    "totalMem" to totalMem,
                                    "sampleSchema" to listOf("timestampMillis", "availMb", "trimLevel", "lowMemory"),
                                    "samples" to samples.toList(),
                                ),
                            ),
                        ),
                    )
                    samples.clear()
                }

                delay(SAMPLE_INTERVAL_MS)
            }
        }

        val cb = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                val prev = previousLevel
                if (prev == level) return // no change
                previousLevel = level

                val activityManager =
                    context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)

                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "Memory_PressureChanged",
                            "trigger" to "system",
                            "metadata" to mapOf(
                                "previousLevel" to (prev?.let { trimLabel(it) } ?: "NONE"),
                                "currentLevel" to trimLabel(level),
                                "trimLevel" to level,
                                "totalMem" to memInfo.totalMem,
                                "availMem" to memInfo.availMem,
                                "availMb" to (memInfo.availMem / 1_048_576),
                                "lowMemory" to memInfo.lowMemory,
                            ),
                        ),
                    ),
                )
                logger.d(TAG, "Memory level changed: ${prev?.let { trimLabel(it) } ?: "NONE"} -> ${trimLabel(level)}, avail=${memInfo.availMem / 1_048_576}MB")
            }

            override fun onConfigurationChanged(newConfig: Configuration) { /* no-op */ }
            override fun onLowMemory() {
                onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
        }
        callback = cb
        context.registerComponentCallbacks(cb)
    }

    override fun stop() {
        debugJob?.cancel()
        debugJob = null
        callback?.let { context.unregisterComponentCallbacks(it) }
        callback = null
        logger.i(TAG, "Stopped")
    }

    companion object {
        private const val TAG = "MemoryCollector"
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val SAMPLES_PER_BATCH = 12
        private const val STAGGER_DELAY_MS = 5_000L

        private fun trimLabel(level: Int): String = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "UNKNOWN($level)"
        }
    }
}
