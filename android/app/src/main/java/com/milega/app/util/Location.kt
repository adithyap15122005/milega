package com.milega.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

data class Place(
    val lat: Double,
    val lng: Double,
    val name: String,
    val locality: String = "",
    val city: String = "",
    val pincode: String = "",
    val addressLine: String = "",
)

object Locations {
    /** Quick picks for people who don't share location. */
    val presets = listOf(
        Place(12.9299, 77.5826, "Jayanagar, Bengaluru", "Jayanagar", "Bengaluru", "560041"),
        Place(12.9352, 77.6245, "Koramangala, Bengaluru", "Koramangala", "Bengaluru", "560034"),
        Place(12.9719, 77.6412, "Indiranagar, Bengaluru", "Indiranagar", "Bengaluru", "560038"),
        Place(12.3051, 76.6551, "Mysuru", "Mysuru", "Mysuru", "570001"),
        Place(28.6315, 77.2167, "Connaught Place, New Delhi", "Connaught Place", "New Delhi", "110001"),
        Place(19.0596, 72.8295, "Bandra, Mumbai", "Bandra", "Mumbai", "400050"),
    )

    /** Caller must hold a location permission. Returns null if the phone can't get a fix. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Place? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val loc = try {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
                ?: client.lastLocation.await()
        } catch (e: Exception) {
            null
        } ?: return null
        val address = reverse(context, loc.latitude, loc.longitude)
        return address ?: Place(loc.latitude, loc.longitude, "%.4f, %.4f".format(Locale.US, loc.latitude, loc.longitude))
    }

    @Suppress("DEPRECATION")
    suspend fun reverse(context: Context, lat: Double, lng: Double): Place? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            Geocoder(context, Locale("en", "IN")).getFromLocation(lat, lng, 1)?.firstOrNull()?.toPlace(lat, lng)
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    suspend fun search(context: Context, query: String): List<Place> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent() || query.isBlank()) return@withContext emptyList()
        try {
            Geocoder(context, Locale("en", "IN")).getFromLocationName("$query, India", 5)
                ?.map { it.toPlace(it.latitude, it.longitude) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun Address.toPlace(lat: Double, lng: Double): Place {
        val area: String? = subLocality
        val town: String? = this.locality ?: subAdminArea ?: adminArea
        val label = listOfNotNull(area, town).distinct().joinToString(", ").ifBlank {
            getAddressLine(0) ?: "%.4f, %.4f".format(Locale.US, lat, lng)
        }
        return Place(
            lat = lat, lng = lng, name = label,
            locality = area ?: thoroughfare ?: "",
            city = town ?: "",
            pincode = postalCode ?: "",
            addressLine = getAddressLine(0) ?: "",
        )
    }
}
