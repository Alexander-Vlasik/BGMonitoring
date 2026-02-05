package com.bgmonitoring.ui.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.bgmonitoring.R

object SnapshotNotification {
    private const val CHANNEL_ID = "snapshot_capture"
    const val NOTIFICATION_ID = 1001
    const val FOREGROUND_SERVICE_TYPE = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    fun build(context: Context, text: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Snapshot capture")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    fun progress(context: Context, text: String, progress: Int, max: Int): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Snapshot capture")
            .setContentText(text)
            .setProgress(max, progress, progress == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Snapshot capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
