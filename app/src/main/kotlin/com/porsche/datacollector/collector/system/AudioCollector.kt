package com.porsche.datacollector.collector.system

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.sportapps.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class AudioCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Audio"

    @Volatile
    private var running = false

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting audio monitoring")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        while (running && coroutineContext.isActive) {
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            telemetry.send(
                TelemetryEvent(
                    eventId = "audio.state",
                    payload = mapOf(
                        "musicVolume" to audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                        "ringVolume" to audioManager.getStreamVolume(AudioManager.STREAM_RING),
                        "alarmVolume" to audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
                        "navVolume" to audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
                        "isMicMuted" to audioManager.isMicrophoneMute,
                        "mode" to audioManager.mode,
                        "outputDevices" to outputDevices.map { deviceLabel(it) },
                    ),
                ),
            )

            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    private fun deviceLabel(device: AudioDeviceInfo): String =
        "${device.productName} (type=${device.type})"

    companion object {
        private const val TAG = "AudioCollector"
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
