package com.bgmonitoring.snapshot

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SnapshotPaths {
    private val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    data class SnapshotLocation(
        val rootDir: File,
        val meta: File,
        val jobScheduler: File,
        val standby: File,
        val deviceIdle: File,
        val appOps: File
    )

    fun create(context: Context, targetPackage: String, timestamp: Long = System.currentTimeMillis()): SnapshotLocation {
        val dir = File(
            context.filesDir,
            "snapshots/$targetPackage/${formatter.format(Date(timestamp))}"
        )
        dir.mkdirs()
        return SnapshotLocation(
            rootDir = dir,
            meta = File(dir, "meta.txt"),
            jobScheduler = File(dir, "jobscheduler.txt"),
            standby = File(dir, "standby_bucket.txt"),
            deviceIdle = File(dir, "deviceidle.txt"),
            appOps = File(dir, "appops.txt")
        )
    }

    fun utcString(timestamp: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(timestamp))
    }
}
