package com.milega.app.data.push

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.milega.app.BuildConfig
import com.milega.app.MainActivity
import com.milega.app.MilegaApp
import com.milega.app.R
import com.milega.app.container
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/**
 * Firebase Cloud Messaging, configured from android/local.properties (FIREBASE_*).
 * Without that configuration the app silently relies on in-app notifications and
 * the 15-minute background check in [com.milega.app.data.NotificationWorker].
 */
object Push {
    private const val TAG = "MilegaPush"

    val configured: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    fun init(context: Context): Boolean {
        if (!configured) return false
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build(),
                )
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Firebase init failed; using polling only", e)
            false
        }
    }

    /** Ask for (or refresh) this install's FCM token and register it with the Milega server. */
    fun scheduleRegistration(context: Context) {
        if (!configured) return
        val request = OneTimeWorkRequestBuilder<RegisterTokenWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("push-register", ExistingWorkPolicy.REPLACE, request)
    }

    /** Where a notification should take the user. */
    fun routeFor(kind: String?): String = when (kind) {
        "reservation_new", "reservation_cancelled" -> "s/reservations"
        null -> ""
        else -> if (kind.startsWith("reservation")) "c/reservations" else ""
    }

    /** Show a system notification once per server notification id (push and polling share the guard). */
    fun show(context: Context, id: Long?, title: String, body: String, kind: String?) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_ROUTE, routeFor(kind))
        val pending = PendingIntent.getActivity(
            context, (id ?: 0L).toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, MilegaApp.CHANNEL_RESERVATIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify((id ?: System.currentTimeMillis()).toInt(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }
}

class RegisterTokenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Push.init(applicationContext)) return Result.success()
        val container = applicationContext.container
        if (!container.session.load().loggedIn) return Result.success()
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            container.api.registerDevice(token, BuildConfig.VERSION_NAME)
            container.session.update { it.copy(pushToken = token) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

class MilegaMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.scheduleRegistration(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val id = data["notification_id"]?.toLongOrNull()
        val title = message.notification?.title ?: data["title"] ?: return
        val body = message.notification?.body ?: data["body"] ?: ""
        // Runs on a background thread; the DataStore write is tiny.
        val fresh = id == null || runBlocking { applicationContext.container.session.markNotificationShown(id) }
        if (fresh) Push.show(applicationContext, id, title, body, data["kind"])
    }
}
