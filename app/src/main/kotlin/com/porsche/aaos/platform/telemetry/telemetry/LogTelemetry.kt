package com.porsche.aaos.platform.telemetry.telemetry

import com.porsche.aaos.platform.telemetry.core.logging.Logger
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject

class LogTelemetry @Inject constructor(
    private val logger: Logger,
) : Telemetry {

    override fun send(event: TelemetryEvent) {
        val root = JSONObject()
        root.put("signalId", event.signalId)
        root.put("payload", toJson(event.payload))
        root.put("timestamp", event.timestamp)
        logger.d(TAG, root.toString().replace("\\/", "/"))
    }

    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj ->
            value.forEach { (k, v) -> obj.put(k.toString(), toJson(v)) }
        }
        is List<*> -> JSONArray().also { arr ->
            value.forEach { arr.put(toJson(it)) }
        }
        is Number, is Boolean -> value
        else -> value.toString()
    }

    companion object {
        private const val TAG = "LogTelemetry"
    }
}
