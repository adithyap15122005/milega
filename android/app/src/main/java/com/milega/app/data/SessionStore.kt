package com.milega.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.milega.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("milega_session")

/** Everything the app remembers between launches. The token never leaves this store except in API calls. */
data class Session(
    val token: String? = null,
    val userId: Long = 0,
    val name: String? = null,
    val phone: String? = null,
    val role: String = "customer",
    val shopId: Long? = null,
    val shopName: String? = null,
    val shopStatus: String? = null,
    /** "customer" or "shop": which experience the user last used. */
    val mode: String = "customer",
    val language: String? = null,
    val serverUrl: String = BuildConfig.DEFAULT_API_URL,
    val lat: Double? = null,
    val lng: Double? = null,
    val placeName: String? = null,
    val lastSeenNotificationId: Long = 0,
) {
    val loggedIn: Boolean get() = token != null
    val hasShop: Boolean get() = shopId != null
}

class SessionStore(private val context: Context) {
    private object K {
        val token = stringPreferencesKey("token")
        val userId = longPreferencesKey("user_id")
        val name = stringPreferencesKey("name")
        val phone = stringPreferencesKey("phone")
        val role = stringPreferencesKey("role")
        val shopId = longPreferencesKey("shop_id")
        val shopName = stringPreferencesKey("shop_name")
        val shopStatus = stringPreferencesKey("shop_status")
        val mode = stringPreferencesKey("mode")
        val language = stringPreferencesKey("language")
        val serverUrl = stringPreferencesKey("server_url")
        val lat = doublePreferencesKey("lat")
        val lng = doublePreferencesKey("lng")
        val placeName = stringPreferencesKey("place_name")
        val lastSeen = longPreferencesKey("last_seen_notification")
    }

    @Volatile
    var current: Session = Session()
        private set

    val flow: Flow<Session> = context.dataStore.data.map { it.toSession() }

    suspend fun load(): Session = flow.first().also { current = it }

    suspend fun update(transform: (Session) -> Session): Session {
        val next = transform(load())
        context.dataStore.edit { p ->
            p.put(K.token, next.token)
            p[K.userId] = next.userId
            p.put(K.name, next.name)
            p.put(K.phone, next.phone)
            p[K.role] = next.role
            if (next.shopId != null) p[K.shopId] = next.shopId else p.remove(K.shopId)
            p.put(K.shopName, next.shopName)
            p.put(K.shopStatus, next.shopStatus)
            p[K.mode] = next.mode
            p.put(K.language, next.language)
            p[K.serverUrl] = next.serverUrl
            if (next.lat != null && next.lng != null) {
                p[K.lat] = next.lat
                p[K.lng] = next.lng
            } else {
                p.remove(K.lat); p.remove(K.lng)
            }
            p.put(K.placeName, next.placeName)
            p[K.lastSeen] = next.lastSeenNotificationId
        }
        current = next
        return next
    }

    /** Apply the server's view of the user (role / shop may change after registration or verification). */
    suspend fun applyUser(user: UserDto, token: String? = null) = update {
        it.copy(
            token = token ?: it.token,
            userId = user.id,
            name = user.name,
            phone = user.phone,
            role = user.role,
            shopId = user.shop?.id,
            shopName = user.shop?.name,
            shopStatus = user.shop?.status,
            mode = if (user.shop != null && (it.mode == "shop" || token != null)) "shop" else if (user.shop == null) "customer" else it.mode,
        )
    }

    suspend fun logout() = update { Session(serverUrl = it.serverUrl, language = it.language, lat = it.lat, lng = it.lng, placeName = it.placeName) }

    private fun Preferences.toSession() = Session(
        token = this[K.token],
        userId = this[K.userId] ?: 0,
        name = this[K.name],
        phone = this[K.phone],
        role = this[K.role] ?: "customer",
        shopId = this[K.shopId],
        shopName = this[K.shopName],
        shopStatus = this[K.shopStatus],
        mode = this[K.mode] ?: "customer",
        language = this[K.language],
        serverUrl = this[K.serverUrl] ?: BuildConfig.DEFAULT_API_URL,
        lat = this[K.lat],
        lng = this[K.lng],
        placeName = this[K.placeName],
        lastSeenNotificationId = this[K.lastSeen] ?: 0,
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }
}
