package com.bgmonitoring.snapshot

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

/**
 * Parses snapshot folders, emits summary.json/summary.md per snapshot, and builds a timeline.
 * Best-effort regex parsing tolerant to OEM/Android version differences.
 */
class SnapshotAnalyzer {

    fun analyze(packageDir: File): AnalysisResult {
        val snapshots = loadSnapshots(packageDir)
        if (snapshots.isEmpty()) return AnalysisResult.NotEnoughData
        val ordered = snapshots.sortedBy { it.timestamp }
        val timeline = buildTimeline(ordered)
        val result = AnalysisResult.Success(
            coverage = Coverage(
                count = ordered.size,
                startTs = ordered.first().timestamp,
                endTs = ordered.last().timestamp
            ),
            timeline = timeline
        )
        writeAggregateSummary(packageDir, result)
        return result
    }

    private fun writeAggregateSummary(packageDir: File, result: AnalysisResult.Success) {
        try {
            val json = JSONObject().apply {
                put("coverage", JSONObject().apply {
                    put("count", result.coverage.count)
                    put("startTs", result.coverage.startTs)
                    put("endTs", result.coverage.endTs)
                })
                result.timeline.entries.lastOrNull()?.let { latest ->
                    put("latest", JSONObject().apply {
                        put("dir", latest.dirName)
                        put("timestamp", latest.timestamp)
                        put("bucket", latest.bucket)
                        latest.quota?.let { q ->
                            put("quotaRemainingTimeMs", q.timeMs)
                            put("quotaRemainingCount", q.remainingCount)
                        }
                        put("jobs", JSONObject().apply {
                            put("total", latest.jobStats.total)
                            put("blockedWithinQuota", latest.jobStats.blockedWithinQuota)
                            put("blockedIdle", latest.jobStats.blockedIdle)
                        })
                    })
                }
            }
            File(packageDir, "analysis_summary.json").writeText(json.toString(2))
            File(packageDir, "analysis_summary.md").writeText(buildAggregateMarkdown(result))
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun buildAggregateMarkdown(result: AnalysisResult.Success): String = buildString {
        appendLine("# Analysis Summary")
        appendLine("- snapshots: ${result.coverage.count}")
        appendLine("- range: ${fmtUtc(result.coverage.startTs)} – ${fmtUtc(result.coverage.endTs)}")
        val latest = result.timeline.entries.lastOrNull()
        if (latest != null) {
            appendLine("- latest bucket: ${latest.bucket}")
            latest.quota?.let { q ->
                appendLine("- latest quota: remainingTime=${q.timeMs ?: -1}ms remainingCount=${q.remainingCount ?: -1}")
            }
            appendLine("- latest jobs: total=${latest.jobStats.total} blockedQuota=${latest.jobStats.blockedWithinQuota} blockedIdle=${latest.jobStats.blockedIdle}")
        }
    }

            private fun fmtUtc(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.US).format(java.util.Date(ts))

    private fun loadSnapshots(packageDir: File): List<SnapshotData> {
        if (!packageDir.exists()) return emptyList()
        return packageDir.listFiles()?.mapNotNull { snapDir ->
            val meta = File(snapDir, "meta.txt")
            val standby = File(snapDir, "standby_bucket.txt")
            val jobs = File(snapDir, "jobscheduler.txt")
            val deviceIdle = File(snapDir, "deviceidle.txt")
            val appops = File(snapDir, "appops.txt")
            try {
                val metaText = meta.readText()
                val (bucketName, bucketId) = parseBucket(standby.readText(), jobs.readText())
                SnapshotData(
                    dirName = snapDir.name,
                    timestamp = parseMetaTimestamp(metaText),
                    targetPackage = extractTarget(metaText),
                    bucket = bucketName,
                    bucketId = bucketId,
                    jobStats = parseJobs(jobs.readText()),
                    appQuota = parseAppQuota(jobs.readText()),
                    appOps = parseAppOps(appops.readText()),
                    deviceIdle = parseDeviceIdle(deviceIdle.readText())
                )
            } catch (_: Exception) {
                null
            }
        } ?: emptyList()
    }

    private fun buildTimeline(snaps: List<SnapshotData>): Timeline {
        val entries = mutableListOf<TimelineEntry>()
        var prev: SnapshotData? = null
        for (snap in snaps) {
            val bucketChange = if (prev?.bucket != snap.bucket) snap.bucket else null
            val jobDelta = snap.jobStats - (prev?.jobStats ?: JobStats())
            val appOpsChanges = diffAppOps(prev?.appOps, snap.appOps)
            entries += TimelineEntry(
                dirName = snap.dirName,
                timestamp = snap.timestamp,
                bucket = snap.bucket,
                bucketChanged = bucketChange != null,
                jobStats = snap.jobStats,
                jobDelta = jobDelta,
                cancels = snap.jobStats.cancels,
                quota = snap.jobStats.quota,
                appOpsChanges = appOpsChanges,
                deviceIdle = snap.deviceIdle
            )
            prev = snap
        }
        return Timeline(entries)
    }

    private fun parseMetaTimestamp(text: String): Long {
        val line = text.lineSequence().firstOrNull { it.startsWith("timestamp_utc=") }
        return line?.removePrefix("timestamp_utc=")?.let { SnapshotPaths.parseUtc(it) } ?: 0L
    }

    private fun extractTarget(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("target_package=") }
            ?.removePrefix("target_package=")

    private fun parseBucket(standbyText: String, jobsText: String): Pair<String, Int?> {
        val tokens = listOf("ACTIVE", "WORKING_SET", "FREQUENT", "RARE", "RESTRICTED", "EXEMPTED")
        val upper = standbyText.uppercase(Locale.US)
        tokens.firstOrNull { upper.contains(it) }?.let { return it to nameToId(it) }
        val line = jobsText.lineSequence().firstOrNull { it.contains("Standby bucket", ignoreCase = true) }
        if (line != null) {
            val num = Regex("(\\d{2})").find(line)?.value?.toIntOrNull()
            if (num != null) return bucketFromId(num) to num
            tokens.firstOrNull { line.uppercase(Locale.US).contains(it) }?.let { return it to nameToId(it) }
        }
        return "UNKNOWN" to null
    }

    private fun parseJobs(text: String): JobStats {
        val lines = text.lineSequence().toList()
        val total = lines.count { it.contains("JOB #") || it.contains("JobInfo{") }
        fun countToken(token: String) = lines.count { it.contains(token, ignoreCase = true) }
        val cancels = lines.filter { it.contains("CANCEL", ignoreCase = true) }
            .map { CancelEntry(reason = extractReason(it)) }
        val quota = extractQuota(lines)
        val constraintsBlocked = countConstraints(lines)
        val blockedWithinQuota = constraintsBlocked["WITHIN_QUOTA"] ?: 0
        val blockedIdle = constraintsBlocked["IDLE"] ?: 0
        return JobStats(
            total = total,
            running = countToken("RUNNING"),
            pending = countToken("PENDING"),
            waiting = countToken("WAITING"),
            cancelled = countToken("CANCELLED"),
            finished = countToken("FINISHED"),
            cancels = cancels,
            quota = quota,
            blockedWithinQuota = blockedWithinQuota,
            blockedIdle = blockedIdle,
            constraintsBlocked = constraintsBlocked
        )
    }

    private fun countConstraints(lines: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        lines.forEach { line ->
            if (line.contains("Unsatisfied", ignoreCase = true) || line.contains("NOT SATISFIED", ignoreCase = true)) {
                val tokens = line.substringAfter(": ", "").split(",", " ").map { it.trim() }.filter { it.isNotBlank() }
                tokens.forEach { t ->
                    val key = t.uppercase(Locale.US)
                    map[key] = (map[key] ?: 0) + 1
                }
            }
        }
        return map
    }

    private fun extractReason(line: String): String =
        line.substringAfter("CANCEL", missingDelimiterValue = "").trim()
            .ifBlank { "unknown" }

    private fun extractQuota(lines: List<String>): QuotaInfo? {
        val timeRegex = Pattern.compile("Remaining execution time:?\\s*([-\\w]+)", Pattern.CASE_INSENSITIVE)
        val countRegex = Pattern.compile("remaining num job starts:?\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
        var time: Long? = null
        var count: Int? = null
        lines.forEach { line ->
            val t = timeRegex.matcher(line)
            if (t.find()) time = parseDurationToMs(t.group(1))
            val c = countRegex.matcher(line)
            if (c.find()) count = c.group(1)?.toIntOrNull()
        }
        return if (time == null && count == null) null else QuotaInfo(timeMs = time, remainingCount = count)
    }

    private fun parseDeviceIdle(text: String): DeviceIdleInfo {
        val state = text.lineSequence().firstOrNull { it.contains("mState=") }
            ?.substringAfter("mState=")?.trim()
        val deep = text.lineSequence().firstOrNull { it.contains("mDeepEnabled=") }
            ?.substringAfter("mDeepEnabled=")?.trim()
        return DeviceIdleInfo(state = state, deepEnabled = deep)
    }

    private fun parseAppOps(text: String): Map<String, String> {
        return text.lineSequence()
            .mapNotNull { line ->
                val parts = line.split(":").map { it.trim() }
                if (parts.size >= 3) {
                    val op = parts[0]
                    val mode = parts.last()
                    op to mode
                } else null
            }
            .toMap()
    }

    private fun diffAppOps(prev: Map<String, String>?, cur: Map<String, String>): List<AppOpChange> {
        if (prev == null) return cur.map { AppOpChange(it.key, from = null, to = it.value) }
        val keys = (prev.keys + cur.keys).distinct()
        return keys.mapNotNull { key ->
            val before = prev[key]
            val after = cur[key]
            if (before == after) null else AppOpChange(key, before, after)
        }
    }

    private fun parseAppQuota(text: String): QuotaInfo? {
        val lines = text.lineSequence().toList()
        var remTime: Long? = null
        var remCount: Int? = null
        lines.forEach { line ->
            if (line.contains("remaining execution time", ignoreCase = true)) {
                remTime = parseDurationToMs(line.substringAfter("time").substringAfter("=").substringBefore(" ").trim())
            }
            if (line.contains("remaining num job starts", ignoreCase = true)) {
                remCount = Regex("(\\d+)").find(line)?.value?.toIntOrNull()
            }
        }
        return if (remTime == null && remCount == null) null else QuotaInfo(timeMs = remTime, remainingCount = remCount)
    }

    private fun parseDurationToMs(token: String?): Long? {
        if (token == null) return null
        return try {
            when {
                token.endsWith("ms") -> token.removeSuffix("ms").toLong()
                token.endsWith("s") -> (token.removeSuffix("s").toDouble() * 1000).toLong()
                token.endsWith("m") -> (token.removeSuffix("m").toDouble() * 60_000).toLong()
                token.endsWith("h") -> (token.removeSuffix("h").toDouble() * 3_600_000).toLong()
                else -> token.toLongOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }
}

data class SnapshotData(
    val dirName: String,
    val timestamp: Long,
    val targetPackage: String?,
    val bucket: String,
    val bucketId: Int?,
    val jobStats: JobStats,
    val appQuota: QuotaInfo?,
    val appOps: Map<String, String>,
    val deviceIdle: DeviceIdleInfo
)

data class Coverage(val count: Int, val startTs: Long, val endTs: Long)
data class Timeline(val entries: List<TimelineEntry>)

data class TimelineEntry(
    val dirName: String,
    val timestamp: Long,
    val bucket: String,
    val bucketChanged: Boolean,
    val jobStats: JobStats,
    val jobDelta: JobStats,
    val cancels: List<CancelEntry>,
    val quota: QuotaInfo?,
    val appOpsChanges: List<AppOpChange>,
    val deviceIdle: DeviceIdleInfo
)

data class JobStats(
    val total: Int = 0,
    val running: Int = 0,
    val pending: Int = 0,
    val waiting: Int = 0,
    val cancelled: Int = 0,
    val finished: Int = 0,
    val cancels: List<CancelEntry> = emptyList(),
    val quota: QuotaInfo? = null,
    val blockedWithinQuota: Int = 0,
    val blockedIdle: Int = 0,
    val constraintsBlocked: Map<String, Int> = emptyMap()
) {
    operator fun minus(other: JobStats) = JobStats(
        total = total - other.total,
        running = running - other.running,
        pending = pending - other.pending,
        waiting = waiting - other.waiting,
        cancelled = cancelled - other.cancelled,
        finished = finished - other.finished,
        cancels = cancels, // deltas for cancels not computed
        quota = quota,
        blockedWithinQuota = blockedWithinQuota - other.blockedWithinQuota,
        blockedIdle = blockedIdle - other.blockedIdle,
        constraintsBlocked = constraintsBlocked
    )
}

data class CancelEntry(val reason: String)
data class QuotaInfo(val timeMs: Long?, val remainingCount: Int?)
data class AppOpChange(val op: String, val from: String?, val to: String?)
data class DeviceIdleInfo(val state: String?, val deepEnabled: String?)

sealed interface AnalysisResult {
    data class Success(val coverage: Coverage, val timeline: Timeline) : AnalysisResult
    object NotEnoughData : AnalysisResult
    data class Error(val message: String) : AnalysisResult
}

private fun bucketFromId(id: Int): String = when (id) {
    10 -> "ACTIVE"
    20 -> "WORKING_SET"
    30 -> "FREQUENT"
    40 -> "RARE"
    45 -> "RESTRICTED"
    50 -> "NEVER"
    else -> "UNKNOWN"
}

private fun nameToId(name: String): Int? = when (name.uppercase(Locale.US)) {
    "ACTIVE" -> 10
    "WORKING_SET" -> 20
    "FREQUENT" -> 30
    "RARE" -> 40
    "RESTRICTED" -> 45
    "NEVER" -> 50
    else -> null
}
