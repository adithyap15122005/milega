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
import com.milega.app.data.SessionStore
import com.milega.app.data.NotificationWorker
import org.osmdroid.config.Configuration
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class MilegaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            tileFileSystemCacheMaxBytes = 60L * 1024 * 1024 // keep map cache small on budget phones
        }
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
    val session = SessionStore(context)
    val api = MilegaApi(session)
    private val appContext = context.applicationContext
    private var categoryCache: List<CategoryDto>? = null

    suspend fun categories(): List<CategoryDto> =
        categoryCache ?: api.categories().categories.also { categoryCache = it }

    /** Poll for reservation updates in the background (every 15 min, the Android minimum). */
    fun scheduleNotifications() {
        val request = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(appContext)
            .enqueueUniquePeriodicWork("notifications", ExistingPeriodicWorkPolicy.KEEP, request)
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
