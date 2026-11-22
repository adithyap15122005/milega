package com.milega.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.milega.app.BuildConfig
import com.milega.app.R
import com.milega.app.ui.theme.Radius

/** True when a Maps SDK key was supplied at build time (android/local.properties: MAPS_API_KEY). */
val MapsAvailable: Boolean get() = BuildConfig.MAPS_ENABLED

/** Small non-interactive map ("lite mode": a cheap static map) with one pin. */
@Composable
fun MapPreview(lat: Double, lng: Double, title: String, modifier: Modifier = Modifier, zoom: Float = 16f) {
    if (!MapsAvailable) {
        MapUnavailable(modifier)
        return
    }
    val position = remember(lat, lng) { LatLng(lat, lng) }
    val camera = rememberCameraPositionState { this.position = CameraPosition.fromLatLngZoom(position, zoom) }
    GoogleMap(
        modifier = modifier.clip(RoundedCornerShape(Radius.lg)),
        cameraPositionState = camera,
        googleMapOptionsFactory = { GoogleMapOptions().liteMode(true).mapToolbarEnabled(false) },
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, scrollGesturesEnabled = false,
            zoomGesturesEnabled = false, rotationGesturesEnabled = false, tiltGesturesEnabled = false),
    ) {
        Marker(state = remember(position) { MarkerState(position) }, title = title)
    }
}

@Composable
fun MapUnavailable(modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(Radius.lg)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.map_unavailable), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Turn-by-turn directions in Google Maps (app, or browser if the app isn't installed). */
fun openDirections(context: Context, lat: Double, lng: Double, mode: String = "walking", placeId: String? = null) {
    val base = "https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=$mode"
    val uri = Uri.parse(if (placeId != null) "$base&destination_place_id=${Uri.encode(placeId)}" else base)
    openIntent(context, Intent(Intent.ACTION_VIEW, uri))
}

fun openUrl(context: Context, url: String) = openIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

fun dial(context: Context, phone: String) = openIntent(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))

private fun openIntent(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // No app can handle it (very rare): nothing sensible to do.
    }
}
