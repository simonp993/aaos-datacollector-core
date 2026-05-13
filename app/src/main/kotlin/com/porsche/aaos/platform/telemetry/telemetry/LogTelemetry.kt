package com.porsche.aaos.platform.telemetry.telemetry

import android.content.Context
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject

class LogTelemetry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : Telemetry {

    private val logFile: File by lazy { createLogFile() }
    private var fileStream: FileOutputStream? = null

    override fun send(event: TelemetryEvent) {
        val root = JSONObject()
        root.put("signalId", event.signalId)
        root.put("payload", toJson(event.payload))
        root.put("timestamp", event.timestamp)
        val json = root.toString().replace("\\/", "/")
        logger.i(TAG, json)
        appendToFile(json)
    }

    private fun appendToFile(json: String) {
        try {
            if (fileStream == null) {
                logFile.parentFile?.mkdirs()
                fileStream = FileOutputStream(logFile, true)
                logger.i(TAG, "Logging to file: ${logFile.absolutePath}")
            }
            fileStream?.apply {
                write(json.toByteArray())
                write('\n'.code)
                flush()
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to write to file: ${e.message}")
        }
    }

    private fun createLogFile(): File {
        val dateStamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val dir = File(context.filesDir, LOG_SUBDIR)
        dir.mkdirs()
        enforceStorageCap(dir)
        return File(dir, "telemetry_$dateStamp.jsonl")
    }

    /**
     * If total log directory exceeds [MAX_TOTAL_BYTES], deletes oldest files
     * until under the cap. At ~500 bytes/event and ~1 event/sec, a 1-hour
     * drive ≈ 1.8 MB, so 100 MB holds ~55 hours of continuous logging.
     */
    private fun enforceStorageCap(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var totalBytes = files.sumOf { it.length() }
        for (file in files) {
            if (totalBytes <= MAX_TOTAL_BYTES) break
            val size = file.length()
            if (file.delete()) {
                totalBytes -= size
                logger.i(TAG, "Deleted old log: ${file.name} (${size / 1024}KB), remaining: ${totalBytes / 1024}KB")
            }
        }
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
        private const val LOG_SUBDIR = "telemetry-logs"
        private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024 // 1 GB
    }
}
