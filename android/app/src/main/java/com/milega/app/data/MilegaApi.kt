package com.milega.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A failure the UI can explain. [code] is the stable server error code (or "network"). */
class ApiException(val status: Int, val code: String, message: String, val details: JsonObject? = null) : Exception(message)

@OptIn(ExperimentalSerializationApi::class)
class MilegaApi(private val session: SessionStore) {

    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _authExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when the server rejects our token; the UI sends the user back to login. */
    val authExpired: SharedFlow<Unit> = _authExpired

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @PublishedApi
    internal fun encode(body: String): RequestBody = body.toRequestBody(jsonType)

    @PublishedApi
    internal suspend fun call(
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val base = session.current.serverUrl.trim().let { if (it.endsWith("/")) it else "$it/" }.toHttpUrlOrNull()
            ?: throw ApiException(0, "bad_server_url", "Server address is not valid")
        val url = base.newBuilder().addPathSegments(path.trimStart('/')).apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v.toString()) }
        }.build()
        val requestBody = body ?: if (method == "POST" || method == "PATCH") "{}".toRequestBody(jsonType) else null
        val request = Request.Builder().url(url).method(method, requestBody).apply {
            session.current.token?.let { header("Authorization", "Bearer $it") }
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw parseError(resp.code, text)
                text
            }
        } catch (e: IOException) {
            throw ApiException(0, "network", "Could not reach the server. Check your internet connection.")
        }
    }

    private fun parseError(status: Int, text: String): ApiException {
        if (status == 401) _authExpired.tryEmit(Unit)
        return try {
            val err = json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject
            ApiException(
                status,
                err["code"]?.jsonPrimitive?.content ?: "error",
                err["message"]?.jsonPrimitive?.content ?: "Something went wrong",
                err["details"] as? JsonObject,
            )
        } catch (e: Exception) {
            ApiException(status, "http_$status", "Something went wrong ($status)")
        }
    }

    suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap()): T =
        json.decodeFromString(call("GET", path, query))

    suspend inline fun <reified B, reified T> post(path: String, body: B, headers: Map<String, String> = emptyMap()): T =
        json.decodeFromString(call("POST", path, body = encode(json.encodeToString(body)), headers = headers))

    suspend inline fun <reified T> postEmpty(path: String): T = json.decodeFromString(call("POST", path))

    suspend inline fun <reified B, reified T> patch(path: String, body: B): T =
        json.decodeFromString(call("PATCH", path, body = encode(json.encodeToString(body))))

    suspend inline fun <reified T> delete(path: String): T = json.decodeFromString(call("DELETE", path))

    // ------------------------------------------------------------------ auth

    suspend fun requestOtp(phone: String): OtpRequestResp = post("api/v1/auth/otp/request", OtpRequestBody(phone))
    suspend fun verifyOtp(phone: String, code: String, language: String?): AuthResp =
        post("api/v1/auth/otp/verify", OtpVerifyBody(phone, code, preferredLanguage = language))
    suspend fun me(): UserDto = get("api/v1/me")
    suspend fun updateMe(patch: MePatch): UserDto = patch("api/v1/me", patch)

    // ----------------------------------------------------------------- shops

    suspend fun registerShop(body: ShopBody): ShopDto = post("api/v1/shops", body)
    suspend fun myShop(): ShopDto = get("api/v1/shops/mine")
    suspend fun nearbyShops(lat: Double, lng: Double, radiusKm: Double = 3.0, category: String? = null): NearbyShopsResp =
        get("api/v1/shops/nearby", mapOf("lat" to lat, "lng" to lng, "radius_km" to radiusKm, "category" to category))
    suspend fun shop(id: Long, lat: Double?, lng: Double?): ShopDto = get("api/v1/shops/$id", mapOf("lat" to lat, "lng" to lng))
    suspend fun shopProducts(id: Long, q: String? = null): ShopProductsResp = get("api/v1/shops/$id/products", mapOf("q" to q))
    suspend fun categories(): CategoriesResp = get("api/v1/catalog/categories")

    // ---------------------------------------------------------------- search

    suspend fun search(q: String, lat: Double?, lng: Double?, sort: String, category: String? = null): SearchResp =
        get("api/v1/search", mapOf("q" to q, "lat" to lat, "lng" to lng, "sort" to sort, "category" to category))

    // ------------------------------------------------------------- inventory

    suspend fun catalogSearch(q: String): VariantsResp = get("api/v1/catalog/search", mapOf("q" to q))
    suspend fun inventory(q: String? = null, filter: String = "all"): InventoryResp =
        get("api/v1/inventory", mapOf("q" to q, "filter" to filter))
    suspend fun summary(): SummaryDto = get("api/v1/inventory/summary")
    suspend fun listing(id: Long): ListingDto = get("api/v1/inventory/listings/$id")
    suspend fun addListing(body: AddListingBody): ListingDto = post("api/v1/inventory/listings", body)
    suspend fun updateListing(id: Long, body: ListingPatch): ListingDto = patch("api/v1/inventory/listings/$id", body)
    suspend fun removeListing(id: Long): OkResp = delete("api/v1/inventory/listings/$id")
    suspend fun listingEvents(id: Long): EventsResp = get("api/v1/inventory/listings/$id/events")

    /** [idempotencyKey] makes retries after a dropped connection safe: the server applies it once. */
    suspend fun adjust(id: Long, body: AdjustBody, idempotencyKey: String = UUID.randomUUID().toString()): AdjustResp =
        post("api/v1/inventory/listings/$id/adjust", body, mapOf("Idempotency-Key" to idempotencyKey))

    // ---------------------------------------------------------- reservations

    suspend fun reserve(body: ReservationBody, idempotencyKey: String): ReservationDto =
        post("api/v1/reservations", body, mapOf("Idempotency-Key" to idempotencyKey))
    suspend fun myReservations(): ReservationsResp = get("api/v1/reservations/mine")
    suspend fun cancelReservation(id: Long): ReservationDto = postEmpty("api/v1/reservations/$id/cancel")
    suspend fun shopReservations(status: String): ReservationsResp = get("api/v1/shop/reservations", mapOf("status" to status))
    suspend fun shopReservationAction(id: Long, action: String, note: String? = null): ReservationDto =
        post("api/v1/shop/reservations/$id/$action", ShopActionBody(note))
    suspend fun notifications(unreadOnly: Boolean = false): NotificationsResp =
        get("api/v1/notifications", mapOf("unread_only" to unreadOnly))
    suspend fun markNotificationsRead(): OkResp = postEmpty("api/v1/notifications/read")

    // ----------------------------------------------------------- recognition

    suspend fun providerStatus(): ProviderStatus = get("api/v1/recognition/provider")

    suspend fun uploadScan(file: File, mode: String, ocr: List<OcrLineDto>, requestId: String): JobDto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("mode", mode)
            .addFormDataPart("ocr_lines", json.encodeToString(ocr))
            .addFormDataPart("client_request_id", requestId)
            .addFormDataPart("image", "scan.jpg", file.asRequestBody("image/jpeg".toMediaType()))
            .build()
        return json.decodeFromString(call("POST", "api/v1/recognition/jobs", body = body))
    }

    suspend fun job(id: String): JobDto = get("api/v1/recognition/jobs/$id")
    suspend fun confirmJob(id: String, items: List<ConfirmItem>): ConfirmResp = post("api/v1/recognition/jobs/$id/confirm", ConfirmBody(items))
    suspend fun discardJob(id: String): OkResp = postEmpty("api/v1/recognition/jobs/$id/discard")

    // ----------------------------------------------------------------- voice

    suspend fun interpretVoice(text: String, language: String?): VoiceResp = post("api/v1/voice/interpret", VoiceBody(text, language))
}
