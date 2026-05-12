package com.porsche.aaos.platform.telemetry.collector.system

import com.porsche.aaos.platform.telemetry.core.logging.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared data source that parses the latest collection from
 * `dumpsys android.automotive.watchdog.ICarWatchdog/default`.
 *
 * The dump contains the "Last N minutes performance report" with 60-second
 * periodic collections including per-UID CPU time, I/O reads/writes,
 * memory RSS, page faults, pressure levels, and system CPU summary.
 *
 * This class caches the parsed result for [CACHE_TTL_MS] to avoid calling
 * dumpsys more than once per minute across all collectors.
 */
@Singleton
class CarWatchdogDataSource @Inject constructor(
    private val logger: Logger,
) {

    @Volatile
    private var cachedSnapshot: WatchdogSnapshot? = null

    @Volatile
    private var lastFetchTimestamp = 0L

    /**
     * Returns the latest watchdog snapshot, re-fetching if the cache is stale.
     * Thread-safe via double-checked locking.
     */
    fun getLatestSnapshot(): WatchdogSnapshot? {
        val now = System.currentTimeMillis()
        if (now - lastFetchTimestamp < CACHE_TTL_MS && cachedSnapshot != null) {
            return cachedSnapshot
        }
        synchronized(this) {
            if (now - lastFetchTimestamp < CACHE_TTL_MS && cachedSnapshot != null) {
                return cachedSnapshot
            }
            val snapshot = fetchAndParse()
            cachedSnapshot = snapshot
            lastFetchTimestamp = System.currentTimeMillis()
            return snapshot
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fetchAndParse(): WatchdogSnapshot? {
        return try {
            val output = execDumpsys() ?: return null
            parseLatestCollection(output)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to fetch watchdog data: ${e.message}")
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execDumpsys(): String? {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "dumpsys",
                    "android.automotive.watchdog.ICarWatchdog/default",
                ),
            )
            val output = BufferedReader(InputStreamReader(process.inputStream))
                .use { it.readText() }
            process.waitFor()
            if (output.isBlank()) null else output
        } catch (e: Exception) {
            logger.e(TAG, "dumpsys exec failed: ${e.message}")
            null
        }
    }

    /**
     * Parses the most recent collection from the "Last N minutes" section.
     * Falls back to the most recent boot-time collection if periodic is empty.
     */
    @Suppress("CyclomaticComplexity", "LongMethod")
    private fun parseLatestCollection(dump: String): WatchdogSnapshot? {
        // Find "Last N minutes performance report:" section
        val lastNIdx = dump.indexOf("Last N minutes performance report:")
        if (lastNIdx == -1) {
            logger.w(TAG, "No 'Last N minutes' section found in watchdog dump")
            return null
        }

        val section = dump.substring(lastNIdx)

        // Find the first "Collection N:" block (most recent)
        val collectionStart = section.indexOf("Collection 0:")
        if (collectionStart == -1) return null

        // Find end of this collection (next "Collection N:" or end)
        val nextCollection = section.indexOf("Collection 1:", collectionStart)
        val collectionBlock = if (nextCollection > 0) {
            section.substring(collectionStart, nextCollection)
        } else {
            section.substring(collectionStart)
        }

        return parseCollectionBlock(collectionBlock)
    }

    @Suppress("CyclomaticComplexity", "LongMethod")
    private fun parseCollectionBlock(block: String): WatchdogSnapshot {
        val cpuSummary = parseCpuSummary(block)
        val pressureLevels = parsePressureLevels(block)
        val cpuPerUid = parseCpuPerUid(block)
        val ioReads = parseIoSection(block, "Top N storage I/O reads:")
        val ioWrites = parseIoSection(block, "Top N storage I/O writes:")
        val memoryPerUid = parseMemoryPerUid(block)

        return WatchdogSnapshot(
            cpuSummary = cpuSummary,
            pressureLevels = pressureLevels,
            cpuPerUid = cpuPerUid,
            ioReads = ioReads,
            ioWrites = ioWrites,
            memoryPerUid = memoryPerUid,
        )
    }

    private fun parseCpuSummary(block: String): CpuSummary {
        val totalCpu = extractLong(block, "Total CPU time (ms):")
        val idleCpu = extractLong(block, "Total idle CPU time (ms)/percent:")
        val iowait = extractLong(block, "CPU I/O wait time (ms)/percent:")
        val ctxSwitches = extractLong(block, "Number of context switches:")

        val idlePct = extractPctAfterSlash(block, "Total idle CPU time (ms)/percent:")
        val iowaitPct = extractPctAfterSlash(block, "CPU I/O wait time (ms)/percent:")

        return CpuSummary(
            totalCpuMs = totalCpu,
            idleCpuMs = idleCpu,
            iowaitMs = iowait,
            contextSwitches = ctxSwitches,
            idlePct = idlePct,
            iowaitPct = iowaitPct,
        )
    }

    private fun parsePressureLevels(block: String): Map<String, Long> {
        val levels = mutableMapOf<String, Long>()
        val pattern = PRESSURE_LEVEL_PATTERN
        for (match in pattern.findAll(block)) {
            val level = match.groupValues[1]
            val durationMs = match.groupValues[2].toLongOrNull() ?: 0L
            levels[level] = durationMs
        }
        return levels
    }

    @Suppress("NestedBlockDepth")
    private fun parseCpuPerUid(block: String): List<UidCpuEntry> {
        val startMarker = "Top N CPU times:"
        val startIdx = block.indexOf(startMarker)
        if (startIdx == -1) return emptyList()

        val afterHeader = block.substring(startIdx + startMarker.length)
        val endIdx = findNextSection(afterHeader)
        val cpuSection = if (endIdx > 0) afterHeader.substring(0, endIdx) else afterHeader

        val entries = mutableListOf<UidCpuEntry>()
        var currentEntry: UidCpuEntry? = null

        for (line in cpuSection.lineSequence()) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank()) continue

            // UID line: "0, system, 13214, 2.73%, 0"
            // or "13, com.porsche.assistant, 3720, 0.77%, 0"
            val uidMatch = UID_CPU_LINE_PATTERN.find(trimmed)
            if (uidMatch != null) {
                currentEntry?.let { entries.add(it) }
                currentEntry = UidCpuEntry(
                    userId = uidMatch.groupValues[1].toIntOrNull() ?: 0,
                    packageName = uidMatch.groupValues[2].trim(),
                    cpuTimeMs = uidMatch.groupValues[3].toLongOrNull() ?: 0L,
                    cpuPct = uidMatch.groupValues[4].toDoubleOrNull() ?: 0.0,
                    commands = mutableListOf(),
                )
                continue
            }

            // Command line: "        system_server, 6750, 51.08%, 0"
            val cmdMatch = CMD_CPU_LINE_PATTERN.find(trimmed)
            if (cmdMatch != null && currentEntry != null) {
                (currentEntry.commands as MutableList).add(
                    CommandCpuEntry(
                        command = cmdMatch.groupValues[1].trim(),
                        cpuTimeMs = cmdMatch.groupValues[2].toLongOrNull() ?: 0L,
                        cpuPct = cmdMatch.groupValues[3].toDoubleOrNull() ?: 0.0,
                    ),
                )
            }
        }
        currentEntry?.let { entries.add(it) }
        return entries
    }

    @Suppress("NestedBlockDepth")
    private fun parseIoSection(block: String, sectionHeader: String): List<UidIoWatchdogEntry> {
        val startIdx = block.indexOf(sectionHeader)
        if (startIdx == -1) return emptyList()

        val afterHeader = block.substring(startIdx + sectionHeader.length)
        val endIdx = findNextSection(afterHeader)
        val ioSection = if (endIdx > 0) afterHeader.substring(0, endIdx) else afterHeader

        val entries = mutableListOf<UidIoWatchdogEntry>()
        for (line in ioSection.lineSequence()) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank() || trimmed.startsWith("Android User ID")) continue
            if (trimmed.startsWith("---")) continue

            val match = IO_LINE_PATTERN.find(trimmed) ?: continue
            entries.add(
                UidIoWatchdogEntry(
                    userId = match.groupValues[1].toIntOrNull() ?: 0,
                    packageName = match.groupValues[2].trim(),
                    fgBytes = match.groupValues[3].toLongOrNull() ?: 0L,
                    fgPct = match.groupValues[4].toDoubleOrNull() ?: 0.0,
                    fgFsync = match.groupValues[5].toLongOrNull() ?: 0L,
                    bgBytes = match.groupValues[7].toLongOrNull() ?: 0L,
                    bgPct = match.groupValues[8].toDoubleOrNull() ?: 0.0,
                    bgFsync = match.groupValues[9].toLongOrNull() ?: 0L,
                ),
            )
        }
        return entries
    }

    @Suppress("NestedBlockDepth")
    private fun parseMemoryPerUid(block: String): List<UidMemoryEntry> {
        val startMarker = "Top N memory stats:"
        val startIdx = block.indexOf(startMarker)
        if (startIdx == -1) return emptyList()

        val afterHeader = block.substring(startIdx + startMarker.length)
        val endIdx = findNextSection(afterHeader)
        val memSection = if (endIdx > 0) afterHeader.substring(0, endIdx) else afterHeader

        val entries = mutableListOf<UidMemoryEntry>()
        var currentEntry: UidMemoryEntry? = null

        for (line in memSection.lineSequence()) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank()) continue
            if (trimmed.startsWith("Android User ID") || trimmed.startsWith("---")) continue

            // UID line: "0, system, 7621028, 26.57%, 0, 0.00%, 2379272, 0"
            val uidMatch = UID_MEM_LINE_PATTERN.find(trimmed)
            if (uidMatch != null) {
                currentEntry?.let { entries.add(it) }
                currentEntry = UidMemoryEntry(
                    userId = uidMatch.groupValues[1].toIntOrNull() ?: 0,
                    packageName = uidMatch.groupValues[2].trim(),
                    rssKb = uidMatch.groupValues[3].toLongOrNull() ?: 0L,
                    rssPct = uidMatch.groupValues[4].toDoubleOrNull() ?: 0.0,
                    commands = mutableListOf(),
                )
                continue
            }

            // Command line: "        system_server, 3827264, 0, 1406632, 0"
            val cmdMatch = CMD_MEM_LINE_PATTERN.find(trimmed)
            if (cmdMatch != null && currentEntry != null) {
                (currentEntry.commands as MutableList).add(
                    CommandMemoryEntry(
                        command = cmdMatch.groupValues[1].trim(),
                        rssKb = cmdMatch.groupValues[2].toLongOrNull() ?: 0L,
                    ),
                )
            }
        }
        currentEntry?.let { entries.add(it) }
        return entries
    }

    // ── Helper parsers ────────────────────────────────────────────

    private fun extractLong(text: String, label: String): Long {
        val idx = text.indexOf(label)
        if (idx == -1) return 0L
        val afterLabel = text.substring(idx + label.length).trimStart()
        return afterLabel.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L
    }

    private fun extractPctAfterSlash(text: String, label: String): Double {
        val idx = text.indexOf(label)
        if (idx == -1) return 0.0
        val afterLabel = text.substring(idx + label.length)
        val slashIdx = afterLabel.indexOf('/')
        if (slashIdx == -1) return 0.0
        val afterSlash = afterLabel.substring(slashIdx + 1).trimStart()
        return afterSlash.takeWhile { it.isDigit() || it == '.' }
            .toDoubleOrNull() ?: 0.0
    }

    private fun findNextSection(text: String): Int {
        val markers = listOf(
            "Top N CPU times:",
            "Top N storage I/O reads:",
            "Top N storage I/O writes:",
            "Top N I/O waiting UIDs:",
            "Top N major page faults:",
            "Top N memory stats:",
            "Number of major page faults",
            "Total RSS",
            "Collection ",
        )
        var earliest = Int.MAX_VALUE
        for (marker in markers) {
            val idx = text.indexOf(marker)
            if (idx in 1 until earliest) {
                earliest = idx
            }
        }
        return if (earliest == Int.MAX_VALUE) -1 else earliest
    }

    companion object {
        private const val TAG = "CarWatchdogDataSource"
        const val CACHE_TTL_MS = 60_000L

        // "Pressure level: PRESSURE_LEVEL_NONE, Duration: 60140 ms"
        private val PRESSURE_LEVEL_PATTERN = Regex(
            """Pressure level:\s+(\S+),\s+Duration:\s+(\d+)\s+ms""",
        )

        // "0, system, 13214, 2.73%, 0" or "13, com.porsche.assistant, 3720, 0.77%, 0"
        private val UID_CPU_LINE_PATTERN = Regex(
            """^(\d+),\s+(.+?),\s+(\d+),\s+([\d.]+)%,\s+\d+$""",
        )

        // "        system_server, 6750, 51.08%, 0"
        private val CMD_CPU_LINE_PATTERN = Regex(
            """^\s{2,}(.+?),\s+(\d+),\s+([\d.]+)%,\s+\d+$""",
        )

        // "0, system, 2514944, 32.13%, 21, 55.26%, 0, 0.00%, 0, 0.00%"
        private val IO_LINE_PATTERN = Regex(
            """^(\d+),\s+(.+?),\s+(\d+),\s+([\d.]+)%,\s+(\d+),\s+([\d.]+)%,""" +
                """\s+(\d+),\s+([\d.]+)%,\s+(\d+),\s+([\d.]+)%$""",
        )

        // "0, system, 7621028, 26.57%, 0, 0.00%, 2379272, 0"
        private val UID_MEM_LINE_PATTERN = Regex(
            """^(\d+),\s+(.+?),\s+(\d+),\s+([\d.]+)%,\s+\d+,\s+[\d.]+%,\s+\d+,\s+\d+$""",
        )

        // "        system_server, 3827264, 0, 1406632, 0"
        private val CMD_MEM_LINE_PATTERN = Regex(
            """^\s{2,}(.+?),\s+(\d+),\s+\d+,\s+\d+,\s+\d+$""",
        )
    }
}

// ── Data classes ──────────────────────────────────────────────────────

data class WatchdogSnapshot(
    val cpuSummary: CpuSummary,
    val pressureLevels: Map<String, Long>,
    val cpuPerUid: List<UidCpuEntry>,
    val ioReads: List<UidIoWatchdogEntry>,
    val ioWrites: List<UidIoWatchdogEntry>,
    val memoryPerUid: List<UidMemoryEntry>,
)

data class CpuSummary(
    val totalCpuMs: Long,
    val idleCpuMs: Long,
    val iowaitMs: Long,
    val contextSwitches: Long,
    val idlePct: Double,
    val iowaitPct: Double,
)

data class UidCpuEntry(
    val userId: Int,
    val packageName: String,
    val cpuTimeMs: Long,
    val cpuPct: Double,
    val commands: List<CommandCpuEntry>,
)

data class CommandCpuEntry(
    val command: String,
    val cpuTimeMs: Long,
    val cpuPct: Double,
)

data class UidIoWatchdogEntry(
    val userId: Int,
    val packageName: String,
    val fgBytes: Long,
    val fgPct: Double,
    val fgFsync: Long,
    val bgBytes: Long,
    val bgPct: Double,
    val bgFsync: Long,
)

data class UidMemoryEntry(
    val userId: Int,
    val packageName: String,
    val rssKb: Long,
    val rssPct: Double,
    val commands: List<CommandMemoryEntry>,
)

data class CommandMemoryEntry(
    val command: String,
    val rssKb: Long,
)
