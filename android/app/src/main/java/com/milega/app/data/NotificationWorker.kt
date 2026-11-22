package com.milega.app.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.milega.app.MainActivity
import com.milega.app.MilegaApp
import com.milega.app.R
import com.milega.app.container

/**
 * Shows a system notification for new reservation events while the app is closed.
 * Polling keeps the MVP free of a push dependency; swap for FCM to get instant alerts.
 */
class NotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.container
        val session = container.session.load()
        if (!session.loggedIn) return Result.success()
        val fresh = try {
            container.api.notifications(unreadOnly = true).notifications.filter { it.id > session.lastSeenNotificationId }
        } catch (e: Exception) {
            return Result.retry()
        }
        if (fresh.isEmpty()) return Result.success()
        val allowed = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33
        if (allowed) {
            val intent = PendingIntent.getActivity(
                applicationContext, 0,
                Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            fresh.take(5).forEach { n ->
                val notification = NotificationCompat.Builder(applicationContext, MilegaApp.CHANNEL_RESERVATIONS)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(n.title)
                    .setContentText(n.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .build()
                try {
                    NotificationManagerCompat.from(applicationContext).notify(n.id.toInt(), notification)
                } catch (e: SecurityException) {
                    // permission revoked between check and notify
                }
            }
        }
        container.session.update { it.copy(lastSeenNotificationId = fresh.maxOf { n -> n.id }) }
        return Result.success()
    }
}
