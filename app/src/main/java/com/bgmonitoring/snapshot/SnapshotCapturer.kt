package com.bgmonitoring.snapshot

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.bgmonitoring.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SnapshotCapturer(private val context: Context) {

    suspend fun capture(targetPackage: String, useForegroundService: Boolean): Result = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val location = SnapshotPaths.create(context, targetPackage, timestamp)
        val commands = listOf(
            "dumpsys jobscheduler $targetPackage" to location.jobScheduler,
            "am get-standby-bucket $targetPackage" to location.standby,
            "dumpsys deviceidle" to location.deviceIdle,
            "cmd appops get --uid $targetPackage" to location.appOps
        )

        val commandResults = commands.map { (cmd, file) ->
            val result = ShizukuShell.runCommand(cmd.split(" "))
            file.writeText(
                buildString {
                    appendLine("exitCode=${result.exitCode}")
                    if (result.stderr.isNotBlank()) appendLine("stderr=${result.stderr}")
                    appendLine("---")
                    append(result.stdout)
                }
            )
            cmd to result
        }

        writeMeta(location.meta, targetPackage, timestamp, useForegroundService, commandResults)
        Result.Success(location.rootDir)
    }

    private fun writeMeta(
        file: File,
        targetPackage: String,
        timestamp: Long,
        useForegroundService: Boolean,
        commandResults: List<Pair<String, ShizukuShell.Result>>
    ) {
        val localTs = SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ", Locale.US).format(Date(timestamp))
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        file.writeText(
            buildString {
                appendLine("timestamp_local=$localTs")
                appendLine("timestamp_utc=${SnapshotPaths.utcString(timestamp)}")
                appendLine("target_package=$targetPackage")
                appendLine("device_model=${Build.MODEL}")
                appendLine("sdk_int=${Build.VERSION.SDK_INT}")
                appendLine("app_version_name=${packageInfo.versionName}")
                appendLine("app_version_code=${packageInfo.longVersionCode}")
                appendLine("ignore_battery_optimizations=${pm.isIgnoringBatteryOptimizations(context.packageName)}")
                appendLine("foreground_service_used=$useForegroundService")
                commandResults.forEach { (cmd, res) ->
                    appendLine("command: $cmd -> exit=${res.exitCode}")
                }
            }
        )
    }

    sealed interface Result {
        data class Success(val dir: File) : Result
    }
}
