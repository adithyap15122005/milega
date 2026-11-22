package com.milega.app.ui.customer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.currentLanguage
import com.milega.app.data.LocalitiesResp
import com.milega.app.data.LocalityDto
import com.milega.app.data.PlacePrediction
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.GoogleAttribution
import com.milega.app.ui.components.PrimaryButton
import com.milega.app.ui.components.SectionTitle
import com.milega.app.ui.theme.Milega
import com.milega.app.ui.theme.Space
import com.milega.app.util.Locations
import com.milega.app.util.errorText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Pick where to shop: GPS (optional), a curated Bengaluru locality, or any Bengaluru place via
 * Google Places Autocomplete. Only the chosen point is stored on the phone.
 */
@Composable
fun LocationPickerScreen(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<LocalitiesResp?>(null) }
    var locating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // One autocomplete session per visit to this screen (Google bills per session).
    val sessionToken = remember { UUID.randomUUID().toString() }

    LaunchedEffect(query) {
        delay(if (query.isBlank()) 0 else 300)
        val s = container.session.current
        result = runCatching { container.api.localities(query, sessionToken, s.lat, s.lng, currentLanguage()) }.getOrNull()
    }

    fun choose(lat: Double, lng: Double, name: String, slug: String?) = scope.launch {
        container.session.update { it.copy(lat = lat, lng = lng, placeName = name, localitySlug = slug) }
        onDone()
    }

    fun locate() = scope.launch {
        locating = true
        message = null
        val place = Locations.current(context)
        if (place == null) {
            locating = false
            message = context.getString(R.string.location_failed)
            return@launch
        }
        val nearest = runCatching { container.api.nearestLocality(place.lat, place.lng).locality }.getOrNull()
        locating = false
        choose(place.lat, place.lng, nearest?.name ?: place.name, nearest?.slug)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locate() else message = context.getString(R.string.location_denied)
    }

    AppScaffold(title = stringResource(R.string.choose_location), onBack = onDone) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = padding.calculateTopPadding() + 8.dp, bottom = Space.xxxl)) {
            item {
                PrimaryButton(
                    stringResource(R.string.use_current_location), icon = Icons.Filled.MyLocation, loading = locating,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) locate() else permission.launch(
                            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                        )
                    },
                )
                message?.let {
                    Spacer(Modifier.height(Space.sm))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(Space.lg))
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.area_or_pincode)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val r = result
            if (r != null) {
                if (query.isNotBlank() && r.localities.isEmpty() && r.googlePredictions.isEmpty()) {
                    item { Text(stringResource(R.string.place_not_found), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = Space.lg)) }
                }
                if (query.isBlank()) item { SectionTitle(stringResource(R.string.bengaluru_areas)) }
                items(r.localities, key = { "l" + it.slug }) { loc ->
                    PlaceRow(Icons.Filled.Place, loc.name, loc.nameKn) { choose(loc.lat, loc.lng, loc.name, loc.slug) }
                }
                if (r.googlePredictions.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.more_places)) }
                    items(r.googlePredictions, key = { "g" + it.placeId }) { p ->
                        PlaceRow(Icons.Filled.Map, p.mainText.ifBlank { p.text }, p.secondaryText) { pickPrediction(container, p, sessionToken, scope, ::choose) { message = it } }
                    }
                    item { GoogleAttribution(Modifier.padding(top = Space.sm)) }
                }
            }
            item {
                Spacer(Modifier.height(Space.lg))
                Text(stringResource(R.string.location_privacy), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun pickPrediction(
    container: AppContainer, p: PlacePrediction, sessionToken: String, scope: kotlinx.coroutines.CoroutineScope,
    choose: (Double, Double, String, String?) -> Any, onError: (String) -> Unit,
) {
    scope.launch {
        try {
            val place = container.api.resolvePlace(p.placeId, sessionToken)
            choose(place.lat, place.lng, p.mainText.ifBlank { place.name }, null)
        } catch (e: Exception) {
            onError(e.message ?: "")
        }
    }
}

@Composable
private fun PlaceRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Milega.colors.accent)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
