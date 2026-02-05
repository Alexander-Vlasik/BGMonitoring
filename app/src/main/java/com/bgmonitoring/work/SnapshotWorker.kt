package com.bgmonitoring.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bgmonitoring.shizuku.ShizukuHelper
import com.bgmonitoring.snapshot.SnapshotCapturer
import com.bgmonitoring.ui.notifications.SnapshotNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SnapshotWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val target = inputData.getString(KEY_TARGET_PACKAGE).orEmpty()
        if (target.isBlank()) return Result.failure(workDataOf("reason" to "empty_target"))
        if (!ShizukuHelper.isAvailable() || !ShizukuHelper.hasPermission()) {
            return Result.retry()
        }

        setForeground(createForegroundInfo("Capturing snapshot for $target", progress = 1, max = 4))
        return withContext(Dispatchers.IO) {
            val capturer = SnapshotCapturer(applicationContext)
            capturer.capture(target, useForegroundService = true)
            Result.success()
        }
    }

    private fun createForegroundInfo(text: String, progress: Int = 0, max: Int = 0): ForegroundInfo {
        val notification = if (progress > 0) {
            SnapshotNotification.progress(applicationContext, text, progress, max)
        } else {
            SnapshotNotification.build(applicationContext, text)
        }
        return if (android.os.Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(
                SnapshotNotification.NOTIFICATION_ID,
                notification,
                SnapshotNotification.FOREGROUND_SERVICE_TYPE
            )
        } else {
            ForegroundInfo(SnapshotNotification.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val KEY_TARGET_PACKAGE = "target_package"

        fun schedulePeriodic(context: Context, targetPackage: String) {
            val req = PeriodicWorkRequestBuilder<SnapshotWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_TARGET_PACKAGE to targetPackage))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                uniqueName(targetPackage),
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }

        fun cancel(context: Context, targetPackage: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(targetPackage))
        }

        fun once(context: Context, targetPackage: String): java.util.UUID {
            val req = OneTimeWorkRequestBuilder<SnapshotWorker>()
                .setInputData(workDataOf(KEY_TARGET_PACKAGE to targetPackage))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName("once_$targetPackage"),
                androidx.work.ExistingWorkPolicy.REPLACE,
                req
            )
            return req.id
        }

        private fun uniqueName(pkg: String) = "snapshot_periodic_$pkg"
    }
}
