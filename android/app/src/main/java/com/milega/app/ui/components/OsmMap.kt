package com.milega.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

data class MapPin(val id: Long, val lat: Double, val lng: Double, val title: String, val snippet: String = "")

/**
 * OpenStreetMap view (no API key). Shows shop pins plus the customer's chosen location.
 * Distances in the app are straight-line; turn-by-turn directions open the phone's map app.
 */
@Composable
fun OsmMap(
    centerLat: Double,
    centerLng: Double,
    pins: List<MapPin>,
    modifier: Modifier = Modifier,
    zoom: Double = 15.0,
    interactive: Boolean = true,
    youAreHere: Boolean = true,
    onPinClick: (MapPin) -> Unit = {},
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(zoom)
            if (!interactive) setOnTouchListener { _, _ -> true }
        }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, update = { map ->
            map.overlays.clear()
            if (youAreHere) {
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(centerLat, centerLng)
                    title = context.getString(com.milega.app.R.string.you_are_here)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = androidx.core.content.ContextCompat.getDrawable(context, com.milega.app.R.drawable.ic_you_are_here)
                })
            }
            pins.forEach { pin ->
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(pin.lat, pin.lng)
                    title = pin.title
                    snippet = pin.snippet
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { marker, _ ->
                        marker.showInfoWindow()
                        onPinClick(pin)
                        true
                    }
                })
            }
            map.controller.setCenter(GeoPoint(centerLat, centerLng))
            map.invalidate()
        })
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        ) {
            Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
        }
    }
}

/** Opens walking directions in Google Maps (or the browser if Maps isn't installed). */
fun openDirections(context: Context, lat: Double, lng: Double) {
    val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=walking")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: Exception) {
        // No browser or maps app: nothing sensible to do.
    }
}

fun dial(context: Context, phone: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
    } catch (e: Exception) {
    }
}
