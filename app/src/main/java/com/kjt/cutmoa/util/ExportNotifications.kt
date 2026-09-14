package com.kjt.cutmoa.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import android.content.pm.ServiceInfo

private const val CHANNEL_ID = "video_export"
private const val CHANNEL_NAME = "영상 처리"

/**
 * One shared notification channel/builder for both the merge worker and the
 * overlay-export worker — same look, same progress semantics.
 */
object ExportNotifications {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun foregroundInfo(
        context: Context,
        notificationId: Int,
        title: String,
        progressPercent: Int,
    ): ForegroundInfo {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$progressPercent%")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setProgress(100, progressPercent, progressPercent <= 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
