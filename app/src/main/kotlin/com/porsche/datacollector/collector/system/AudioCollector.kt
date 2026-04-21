package com.porsche.datacollector.collector.system

import android.car.Car
import android.car.media.CarAudioManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.core.logging.Logger
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
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
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false
    private var car: Car? = null
    private var carAudioManager: CarAudioManager? = null
    private var volumeCallback: CarAudioManager.CarVolumeCallback? = null

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting audio monitoring")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Connect to Car service for AAOS volume monitoring
        try {
            val carInstance = Car.createCar(context) ?: throw IllegalStateException("Car.createCar returned null")
            car = carInstance
            val cam = carInstance.getCarManager(Car.AUDIO_SERVICE) as CarAudioManager
            carAudioManager = cam

            val callback = object : CarAudioManager.CarVolumeCallback() {
                override fun onGroupVolumeChanged(zoneId: Int, groupId: Int, flags: Int) {
                    if (running) {
                        logger.d(TAG, "Car volume changed: zone=$zoneId group=$groupId")
                        emitAudioState(audioManager, cam)
                    }
                }

                override fun onMasterMuteChanged(zoneId: Int, flags: Int) {
                    if (running) {
                        logger.d(TAG, "Car master mute changed: zone=$zoneId")
                        emitAudioState(audioManager, cam)
                    }
                }

                override fun onGroupMuteChanged(zoneId: Int, groupId: Int, flags: Int) {
                    if (running) {
                        logger.d(TAG, "Car group mute changed: zone=$zoneId group=$groupId")
                        emitAudioState(audioManager, cam)
                    }
                }
            }
            volumeCallback = callback
            cam.registerCarVolumeCallback(callback)
            logger.i(TAG, "Registered CarVolumeCallback")
        } catch (e: Exception) {
            logger.w(TAG, "Car audio not available, falling back to poll-only: ${e.message}")
        }

        // Initial emit + periodic fallback poll
        while (running && coroutineContext.isActive) {
            emitAudioState(audioManager, carAudioManager)
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        volumeCallback?.let { cb ->
            carAudioManager?.unregisterCarVolumeCallback(cb)
        }
        volumeCallback = null
        carAudioManager = null
        car?.disconnect()
        car = null
        logger.i(TAG, "Stopped")
    }

    private fun emitAudioState(audioManager: AudioManager, cam: CarAudioManager?) {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val uniqueDevices = outputDevices
            .distinctBy { "${it.type}-${it.productName}" }
            .take(MAX_DEVICES)
            .map { deviceLabel(it) }

        // Read car audio volume groups if available
        val volumeGroups = mutableMapOf<String, Any>()
        if (cam != null) {
            try {
                val groupCount = cam.volumeGroupCount
                for (groupId in 0 until groupCount) {
                    val vol = cam.getGroupVolume(groupId)
                    val max = cam.getGroupMaxVolume(groupId)
                    volumeGroups["group$groupId"] = "$vol/$max"
                }
            } catch (e: Exception) {
                logger.w(TAG, "Error reading car volume groups: ${e.message}")
            }
        }

        val metadata = mutableMapOf<String, Any>(
            "musicVolume" to audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            "ringVolume" to audioManager.getStreamVolume(AudioManager.STREAM_RING),
            "alarmVolume" to audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
            "navVolume" to audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
            "isMicMuted" to audioManager.isMicrophoneMute,
            "mode" to audioManager.mode,
            "outputDevices" to uniqueDevices,
        )
        if (volumeGroups.isNotEmpty()) {
            metadata["carVolumeGroups"] = volumeGroups
        }

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Audio_StatePolled",
                    "metadata" to metadata,
                ),
            ),
        )
    }

    private fun deviceLabel(device: AudioDeviceInfo): String =
        "${device.productName} (type=${device.type})"

    companion object {
        private const val TAG = "AudioCollector"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val MAX_DEVICES = 5
    }
}
