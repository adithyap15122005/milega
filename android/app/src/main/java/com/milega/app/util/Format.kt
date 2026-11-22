package com.milega.app.util

import android.content.Context
import com.milega.app.R
import com.milega.app.data.ApiException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.roundToInt

fun rupees(paise: Long?): String {
    if (paise == null) return "—"
    val whole = paise / 100
    val frac = paise % 100
    val grouped = indianGrouping(whole)
    return if (frac == 0L) "₹$grouped" else "₹$grouped.${frac.toString().padStart(2, '0')}"
}

/** 1234567 -> 12,34,567 (Indian digit grouping). */
private fun indianGrouping(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val last3 = s.takeLast(3)
    val rest = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
    return "$rest,$last3"
}

fun qty(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

fun qtyWithUnit(value: Double, unitLabel: String): String = "${qty(value)} $unitLabel"

fun distance(context: Context, meters: Double?): String? {
    if (meters == null) return null
    return if (meters < 1000) context.getString(R.string.distance_m, (meters / 10).roundToInt() * 10)
    else context.getString(R.string.distance_km, String.format(Locale.US, "%.1f", meters / 1000))
}

fun parseInstant(iso: String?): OffsetDateTime? = try {
    if (iso.isNullOrBlank()) null else OffsetDateTime.parse(iso)
} catch (e: Exception) {
    null
}

fun timeAgo(context: Context, iso: String?): String {
    val then = parseInstant(iso) ?: return context.getString(R.string.time_unknown)
    val minutes = Duration.between(then, OffsetDateTime.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> context.getString(R.string.time_just_now)
        minutes < 60 -> context.getString(R.string.time_min_ago, minutes)
        minutes < 60 * 24 -> context.getString(R.string.time_hours_ago, minutes / 60)
        else -> context.getString(R.string.time_days_ago, minutes / (60 * 24))
    }
}

fun minutesUntil(iso: String?): Long? {
    val then = parseInstant(iso) ?: return null
    return Duration.between(OffsetDateTime.now(), then).toMinutes()
}

/** Turn an API failure into a sentence in the user's language. */
fun errorText(context: Context, error: Throwable): String {
    if (error !is ApiException) return context.getString(R.string.error_generic)
    val res = when (error.code) {
        "network" -> R.string.error_network
        "bad_server_url" -> R.string.error_server_url
        "invalid_phone" -> R.string.error_invalid_phone
        "otp_invalid" -> R.string.error_otp_invalid
        "otp_expired" -> R.string.error_otp_expired
        "otp_attempts", "otp_rate_limited", "rate_limited" -> R.string.error_rate_limited
        "insufficient_stock" -> R.string.error_insufficient_stock
        "whole_number_required" -> R.string.error_whole_number
        "quantity_required", "invalid_quantity" -> R.string.error_quantity_required
        "shop_unavailable" -> R.string.error_shop_unavailable
        "too_many_reservations" -> R.string.error_too_many_reservations
        "invalid_transition" -> R.string.error_reservation_changed
        "has_reservations" -> R.string.error_has_reservations
        "job_not_reviewable" -> R.string.error_scan_done
        "invalid_image", "upload_too_large", "empty_upload" -> R.string.error_bad_photo
        "shop_suspended" -> R.string.error_shop_suspended
        else -> null
    }
    return if (res != null) context.getString(res) else error.message ?: context.getString(R.string.error_generic)
}

fun categoryName(slug: String, language: String, names: Map<String, Triple<String, String, String>>): String {
    val n = names[slug] ?: return slug.replace('_', ' ').replaceFirstChar { it.uppercase() }
    return when (language) {
        "hi" -> n.second
        "kn" -> n.third
        else -> n.first
    }
}
