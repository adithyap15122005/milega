package com.milega.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.milega.app.data.CategoryDto
import com.milega.app.data.MilegaApi
import com.milega.app.data.NotificationWorker
import com.milega.app.data.SessionStore
import com.milega.app.data.local.MilegaDatabase
import com.milega.app.data.push.Push
import com.milega.app.data.sync.OutboxRepository
import java.util.Locale
import java.util.concurrent.TimeUnit

class MilegaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()
        Push.init(this)
        // Anything left in the offline outbox (e.g. after a reboot) syncs when the network is back.
        container.outbox.schedule()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESERVATIONS, getString(R.string.channel_reservations), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CHANNEL_RESERVATIONS = "reservations"
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val session = SessionStore(appContext)
    val api = MilegaApi(session)
    val database: MilegaDatabase by lazy { MilegaDatabase.create(appContext) }
    val outbox: OutboxRepository by lazy { OutboxRepository(appContext, database.outbox()) }
    private var categoryCache: List<CategoryDto>? = null

    suspend fun categories(): List<CategoryDto> =
        categoryCache ?: api.categories().categories.also { categoryCache = it }

    /** Background fallback for notifications (every 15 min, the Android minimum) + push token registration. */
    fun scheduleNotifications() {
        val request = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(appContext)
            .enqueueUniquePeriodicWork("notifications", ExistingPeriodicWorkPolicy.KEEP, request)
        Push.scheduleRegistration(appContext)
    }

    fun cancelNotifications() {
        WorkManager.getInstance(appContext).cancelUniqueWork("notifications")
    }
}

val Context.container: AppContainer get() = (applicationContext as MilegaApp).container

/** "en", "hi" or "kn" — the language the UI is currently shown in. */
fun currentLanguage(): String {
    val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val lang = (if (tag.isNotBlank()) tag else Locale.getDefault().toLanguageTag()).take(2)
    return if (lang in setOf("hi", "kn")) lang else "en"
}
