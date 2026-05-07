package com.porsche.aaos.platform.telemetry.collector.system

import android.car.Car
import android.car.media.CarAudioManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
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

    // Tracks the last emitted state to suppress duplicate emissions.
    private var lastEmittedState: Map<String, Any>? = null

    // Debounce: collapse rapid volume changes into a single event.
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null
    private var preChangeState: Map<String, Any>? = null

    // Maps groupIndex → primary context name from car_audio_configuration.xml
    private var groupContextNames: Map<Int, String> = emptyMap()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting audio monitoring")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Connect to Car service for AAOS volume monitoring
        var callbackRegistered = false
        try {
            val carInstance = Car.createCar(context) ?: throw IllegalStateException("Car.createCar returned null")
            car = carInstance
            val cam = carInstance.getCarManager(Car.AUDIO_SERVICE) as CarAudioManager
            carAudioManager = cam
            groupContextNames = parseVolumeGroupContexts()
            if (groupContextNames.isNotEmpty()) {
                logger.i(TAG, "Resolved volume group contexts: $groupContextNames")
            }

            val callback = object : CarAudioManager.CarVolumeCallback() {
                override fun onGroupVolumeChanged(zoneId: Int, groupId: Int, flags: Int) {
                    if (running) {
                        logger.d(TAG, "Car volume changed: zone=$zoneId group=$groupId")
                        emitIfChanged(audioManager, cam)
                    }
                }

                override fun onMasterMuteChanged(zoneId: Int, flags: Int) {
                    if (running) {
                        logger.d(TAG, "Car master mute changed: zone=$zoneId")
                        emitIfChanged(audioManager, cam)
                    }
                }

                override fun onGroupMuteChanged(zoneId: Int, groupId: Int, flags: Int) {
                    if (running) {
                        logger.d(TAG, "Car group mute changed: zone=$zoneId group=$groupId")
                        emitIfChanged(audioManager, cam)
                    }
                }
            }
            volumeCallback = callback
            cam.registerCarVolumeCallback(callback)
            callbackRegistered = true
            logger.i(TAG, "Registered CarVolumeCallback — event-driven mode")
        } catch (e: Exception) {
            logger.w(TAG, "Car audio not available, falling back to poll-only: ${e.message}")
        }

        // Register mic mute callback for real-time detection
        try {
            audioManager.registerAudioDeviceCallback(
                object : android.media.AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                        if (running) emitIfChanged(audioManager, carAudioManager)
                    }
                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                        if (running) emitIfChanged(audioManager, carAudioManager)
                    }
                },
                debounceHandler,
            )
            logger.i(TAG, "Registered AudioDeviceCallback")
        } catch (e: Exception) {
            logger.w(TAG, "AudioDeviceCallback registration failed: ${e.message}")
        }

        // Emit initial state once.
        emitIfChanged(audioManager, carAudioManager)

        // Periodic re-emit every 60s regardless of change (debugging aid).
        // Callbacks still trigger immediate emission on actual changes.
        while (running && coroutineContext.isActive) {
            delay(KEEP_ALIVE_MS)
            emitState(audioManager, carAudioManager)
        }
    }

    override fun stop() {
        running = false
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        debounceRunnable = null
        preChangeState = null
        volumeCallback?.let { cb ->
            carAudioManager?.unregisterCarVolumeCallback(cb)
        }
        volumeCallback = null
        carAudioManager = null
        car?.disconnect()
        car = null
        logger.i(TAG, "Stopped")
    }

    private fun emitIfChanged(audioManager: AudioManager, cam: CarAudioManager?) {
        val metadata = buildAudioState(audioManager, cam)
        if (metadata == lastEmittedState) return
        scheduleDebounceEmit(audioManager, cam)
    }

    /**
     * Debounce volume changes: captures the pre-change state on the first change,
     * then waits [DEBOUNCE_MS] after the last change before emitting a single event
     * with both previous and current volume state.
     */
    private fun scheduleDebounceEmit(audioManager: AudioManager, cam: CarAudioManager?) {
        // Capture the state before this burst of changes started
        if (preChangeState == null) {
            preChangeState = lastEmittedState
        }
        // Cancel any pending emission and reschedule
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (running) {
                emitDebouncedState(audioManager, cam)
            }
        }
        debounceRunnable = runnable
        debounceHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    private fun emitDebouncedState(audioManager: AudioManager, cam: CarAudioManager?) {
        val currentState = buildAudioState(audioManager, cam)
        val previous = preChangeState
        preChangeState = null
        debounceRunnable = null
        lastEmittedState = currentState

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Audio_VolumeStateChanged",
                    "trigger" to "user",
                    "metadata" to mapOf(
                        "previous" to previous,
                        "current" to currentState,
                    ),
                ),
            ),
        )
    }

    private fun emitState(audioManager: AudioManager, cam: CarAudioManager?) {
        val currentState = buildAudioState(audioManager, cam)
        lastEmittedState = currentState
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Audio_VolumeStateChanged",
                    "trigger" to "heartbeat",
                    "metadata" to mapOf(
                        "previous" to null,
                        "current" to currentState,
                    ),
                ),
            ),
        )
    }

    private fun buildAudioState(
        audioManager: AudioManager,
        cam: CarAudioManager?,
    ): Map<String, Any> {
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
                    val key = groupContextNames[groupId]
                        ?: "group${groupId}__car_audio_configuration_xml_read_error"
                    volumeGroups[key] = "$vol/$max"
                }
            } catch (e: Exception) {
                logger.w(TAG, "Error reading car volume groups: ${e.message}")
            }
        }

        val metadata = mutableMapOf<String, Any>(
            "isMicMuted" to audioManager.isMicrophoneMute,
            "mode" to audioManager.mode,
            "outputDevices" to uniqueDevices,
        )
        if (volumeGroups.isNotEmpty()) {
            metadata["carVolumeGroups"] = volumeGroups
        }
        return metadata
    }

    private fun deviceLabel(device: AudioDeviceInfo): String =
        "${device.productName} (type=${device.type})"

    /**
     * Parses /vendor/etc/car_audio_configuration.xml to build a map of
     * volume group index → primary audio context name for the primary zone.
     * Returns an empty map if the file is missing or unparseable.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun parseVolumeGroupContexts(): Map<Int, String> {
        val file = File(CAR_AUDIO_CONFIG_PATH)
        if (!file.exists()) {
            logger.w(TAG, "car_audio_configuration.xml not found at $CAR_AUDIO_CONFIG_PATH")
            return emptyMap()
        }
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            file.inputStream().use { stream ->
                parser.setInput(stream, null)
                parseGroupContexts(parser)
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to parse car_audio_configuration.xml: ${e.message}")
            emptyMap()
        }
    }

    private fun parseGroupContexts(parser: XmlPullParser): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        var inPrimaryZone = false
        var groupIndex = 0
        val currentGroupContexts = mutableListOf<String>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "zone" -> {
                            inPrimaryZone = parser.getAttributeValue(null, "isPrimary") == "true"
                        }
                        "group" -> {
                            if (inPrimaryZone) currentGroupContexts.clear()
                        }
                        "context" -> {
                            if (inPrimaryZone) {
                                parser.getAttributeValue(null, "context")?.let {
                                    currentGroupContexts.add(it)
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "group" -> {
                            if (inPrimaryZone && currentGroupContexts.isNotEmpty()) {
                                result[groupIndex] = currentGroupContexts.joinToString("__")
                                groupIndex++
                            }
                        }
                        "zone" -> {
                            if (inPrimaryZone) return result
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }

    companion object {
        private const val TAG = "AudioCollector"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val KEEP_ALIVE_MS = 60_000L
        private const val DEBOUNCE_MS = 500L
        private const val MAX_DEVICES = 5
        private const val CAR_AUDIO_CONFIG_PATH = "/vendor/etc/car_audio_configuration.xml"
    }
}
