package com.porsche.aaos.platform.telemetry.collector.vehicle

import android.os.Build
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.vehicleplatform.VhalPropertyIds
import com.porsche.aaos.platform.telemetry.vehicleplatform.VhalPropertyService
import javax.inject.Inject

class CarInfoCollector @Inject constructor(
    private val vhalPropertyService: VhalPropertyService,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "CarInfo"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    override suspend fun start() {
        logger.i(TAG, "Collecting car info (one-shot)")

        val info = mutableMapOf<String, Any?>()

        readProperty<String>(VhalPropertyIds.INFO_VIN, "vin")?.let { info["vin"] = it }
        readProperty<String>(VhalPropertyIds.INFO_MAKE, "make")?.let { info["make"] = it }
        readProperty<String>(VhalPropertyIds.INFO_MODEL, "model")?.let { info["model"] = it }
        readProperty<Int>(VhalPropertyIds.INFO_MODEL_YEAR, "model_year")?.let { info["model_year"] = it }
        readIntArrayProperty(VhalPropertyIds.INFO_FUEL_TYPE, "fuel_type")?.let { info["fuel_type"] = it }
        readIntArrayProperty(VhalPropertyIds.INFO_EV_CONNECTOR_TYPE, "ev_connector")?.let { info["ev_connector"] = it }

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Vehicle_Info",
                    "trigger" to "system",
                    "metadata" to info,
                ),
            ),
        )
        logger.i(TAG, "Car info collected: ${info.keys}")

        // System metadata — one-shot at startup
        val systemInfo = mutableMapOf<String, Any?>(
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkLevel" to Build.VERSION.SDK_INT,
            "securityPatch" to Build.VERSION.SECURITY_PATCH,
            "buildDisplay" to Build.DISPLAY,
            "buildFingerprint" to Build.FINGERPRINT,
            "buildIncremental" to Build.VERSION.INCREMENTAL,
            "buildType" to Build.TYPE,
            "brand" to Build.BRAND,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "board" to Build.BOARD,
            "hardware" to Build.HARDWARE,
            "serial" to getSerial(),
        )

        // Porsche/MIB4-specific props via SystemProperties
        val porscheProps = mapOf(
            "porscheBuildVersion" to "ro.boot.build_version",
            "hardwareName" to "ro.boot.hardware_name",
            "hardwareRevision" to "ro.boot.hardware.revision",
            "hardwareRegion" to "ro.boot.hardware_region",
            "hardwareFeature" to "ro.boot.hardware_feature",
            "gnssModel" to "ro.odm.aptiv.gnss.model_name_year",
            "kernelVersion" to "ro.kernel.version",
        )
        for ((key, prop) in porscheProps) {
            getSystemProperty(prop)?.let { systemInfo[key] = it }
        }

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "System_Info",
                    "trigger" to "system",
                    "metadata" to systemInfo,
                ),
            ),
        )
        logger.i(TAG, "System info collected: ${systemInfo.keys}")
    }

    override fun stop() {
        // One-shot collector — nothing to stop
    }

    private fun <T : Any> readProperty(propertyId: Int, label: String): T? = try {
        vhalPropertyService.readProperty<T>(propertyId)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        logger.w(TAG, "Failed to read $label ($propertyId)", e)
        null
    }

    private fun readIntArrayProperty(propertyId: Int, label: String): IntArray? = try {
        val raw = vhalPropertyService.readProperty<Any>(propertyId)
        when (raw) {
            is IntArray -> raw
            is Array<*> -> IntArray(raw.size) { (raw[it] as Number).toInt() }
            else -> null
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        logger.w(TAG, "Failed to read $label ($propertyId)", e)
        null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun getSystemProperty(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        val value = get.invoke(null, key) as? String
        value?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        logger.w(TAG, "Failed to read system property $key", e)
        null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun getSerial(): String? = try {
        Build::class.java.getMethod("getSerial").invoke(null) as? String
    } catch (e: Exception) {
        getSystemProperty("ro.boot.serialno")
    }

    companion object {
        private const val TAG = "CarInfoCollector"
    }
}
