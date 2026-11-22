package com.milega.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("milega_session")

/** A Milega merchant the customer opened recently (our own data; Google places are not stored). */
@Serializable
data class RecentShop(val id: Long, val name: String, val locality: String = "", val merchantState: String = "verified_merchant")

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
    val localitySlug: String? = null,
    val lastSeenNotificationId: Long = 0,
    val shownNotificationIds: Set<Long> = emptySet(),
    val recentSearches: List<String> = emptyList(),
    val recentShops: List<RecentShop> = emptyList(),
    val pushToken: String? = null,
) {
    val loggedIn: Boolean get() = token != null
    val hasShop: Boolean get() = shopId != null
    val hasLocation: Boolean get() = lat != null && lng != null
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
        val localitySlug = stringPreferencesKey("locality_slug")
        val lastSeen = longPreferencesKey("last_seen_notification")
        val shownIds = stringPreferencesKey("shown_notification_ids")
        val recentSearches = stringPreferencesKey("recent_searches")
        val recentShops = stringPreferencesKey("recent_shops")
        val pushToken = stringPreferencesKey("push_token")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val recentShopsSerializer = ListSerializer(RecentShop.serializer())
    private val searchesSerializer = ListSerializer(String.serializer())
    private val lock = Mutex()

    @Volatile
    var current: Session = Session()
        private set

    val flow: Flow<Session> = context.dataStore.data.map { it.toSession() }

    suspend fun load(): Session = flow.first().also { current = it }

    suspend fun update(transform: (Session) -> Session): Session = lock.withLock {
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
            p.put(K.localitySlug, next.localitySlug)
            p[K.lastSeen] = next.lastSeenNotificationId
            p[K.shownIds] = next.shownNotificationIds.sortedDescending().take(100).joinToString(",")
            p[K.recentSearches] = json.encodeToString(searchesSerializer, next.recentSearches.take(10))
            p[K.recentShops] = json.encodeToString(recentShopsSerializer, next.recentShops.take(10))
            p.put(K.pushToken, next.pushToken)
        }
        current = next
        next
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

    suspend fun addRecentSearch(q: String) {
        val clean = q.trim()
        if (clean.length < 2) return
        update { s -> s.copy(recentSearches = (listOf(clean) + s.recentSearches.filterNot { it.equals(clean, ignoreCase = true) }).take(10)) }
    }

    suspend fun clearRecentSearches() = update { it.copy(recentSearches = emptyList()) }

    suspend fun addRecentShop(shop: RecentShop) =
        update { s -> s.copy(recentShops = (listOf(shop) + s.recentShops.filterNot { it.id == shop.id }).take(10)) }

    /** True the first time a notification id is seen (push and polling share this, so nothing shows twice). */
    suspend fun markNotificationShown(id: Long): Boolean {
        var fresh = false
        update { s ->
            fresh = id !in s.shownNotificationIds
            s.copy(shownNotificationIds = s.shownNotificationIds + id, lastSeenNotificationId = maxOf(s.lastSeenNotificationId, id))
        }
        return fresh
    }

    suspend fun logout() = update {
        Session(serverUrl = it.serverUrl, language = it.language, lat = it.lat, lng = it.lng,
            placeName = it.placeName, localitySlug = it.localitySlug, recentSearches = it.recentSearches)
    }

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
        localitySlug = this[K.localitySlug],
        lastSeenNotificationId = this[K.lastSeen] ?: 0,
        shownNotificationIds = this[K.shownIds].orEmpty().split(",").mapNotNull { it.toLongOrNull() }.toSet(),
        recentSearches = decode(this[K.recentSearches], searchesSerializer) ?: emptyList(),
        recentShops = decode(this[K.recentShops], recentShopsSerializer) ?: emptyList(),
        pushToken = this[K.pushToken],
    )

    private fun <T> decode(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): T? =
        if (raw.isNullOrBlank()) null else runCatching { json.decodeFromString(serializer, raw) }.getOrNull()

    private fun MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }
}
