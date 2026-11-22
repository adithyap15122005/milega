package com.milega.app.ui.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.ExternalPlace
import com.milega.app.data.SaveBody
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.GoogleAttribution
import com.milega.app.ui.components.InfoBanner
import com.milega.app.ui.components.LoadableContent
import com.milega.app.ui.components.MapPreview
import com.milega.app.ui.components.MerchantBadge
import com.milega.app.ui.components.OpenStatus
import com.milega.app.ui.components.SectionHeader
import com.milega.app.ui.components.dial
import com.milega.app.ui.components.openDirections
import com.milega.app.ui.components.openUrl
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.theme.Milega
import com.milega.app.ui.theme.Space
import com.milega.app.util.errorText
import kotlinx.coroutines.launch

/** Prefill for "Own this shop? Join Milega": the owner confirms or edits everything. */
data class ClaimPrefill(val placeId: String, val name: String, val lat: Double, val lng: Double, val address: String)

/**
 * A business listed on Google Maps. Shown for discovery and directions only: no Milega stock,
 * prices or reservations are implied. If a merchant has a verified claim on it, we open their
 * Milega profile instead.
 */
@Composable
fun PlaceProfileScreen(
    container: AppContainer,
    placeId: String,
    onBack: () -> Unit,
    onMilegaShop: (Long) -> Unit,
    onClaim: (ClaimPrefill) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val session = container.session.current
    val state = rememberLoadable(placeId) { container.api.place(placeId, session.lat, session.lng) }
    var saved by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }

    LaunchedEffect(state.data?.milegaShopId) { state.data?.milegaShopId?.let(onMilegaShop) }
    LaunchedEffect(placeId) {
        if (session.loggedIn) saved = runCatching { container.api.saved().places.any { it.placeId == placeId } }.getOrDefault(false)
    }

    AppScaffold(
        title = state.data?.place?.name ?: "", onBack = onBack, snackbar = snackbar,
        actions = {
            IconButton(onClick = {
                scope.launch {
                    try {
                        if (saved) container.api.unsave(placeId = placeId) else container.api.save(SaveBody(placeId = placeId))
                        saved = !saved
                    } catch (e: Exception) {
                        snackbar.showSnackbar(errorText(context, e))
                    }
                }
            }) {
                Icon(if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    stringResource(if (saved) R.string.unsave else R.string.save_shop),
                    tint = if (saved) Milega.colors.accent else MaterialTheme.colorScheme.onSurface)
            }
        },
    ) { padding ->
        LoadableContent(state) { resp ->
            val place = resp.place ?: return@LoadableContent
            PlaceContent(place, PaddingValues(top = padding.calculateTopPadding()),
                onReport = { reporting = true },
                onClaim = {
                    if (place.lat != null && place.lng != null) onClaim(ClaimPrefill(place.placeId, place.name, place.lat, place.lng, place.fullAddress))
                })
        }
    }
    if (reporting) ReportDialog(container, "place", placeId, onDone = { msg ->
        reporting = false
        if (msg != null) scope.launch { snackbar.showSnackbar(msg) }
    })
}

@Composable
fun PlaceContent(place: ExternalPlace, padding: PaddingValues, onReport: () -> Unit, onClaim: () -> Unit) {
    val context = LocalContext.current
    var showHours by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = padding.calculateTopPadding(), bottom = Space.xxxl)) {
        item {
            if (place.lat != null && place.lng != null) MapPreview(place.lat, place.lng, place.name, Modifier.fillMaxWidth().height(150.dp))
            Spacer(Modifier.height(Space.lg))
            Text(place.name, style = MaterialTheme.typography.headlineMedium)
            place.typeLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MerchantBadge("google_discovered")
                OpenStatus(place.openNow)
            }
            Spacer(Modifier.height(Space.sm))
            RatingLine(place.rating, place.userRatingCount)
            Spacer(Modifier.height(Space.xs))
            Text(place.fullAddress.ifBlank { place.address }, style = MaterialTheme.typography.bodyMedium)
            distanceLabel(place.distanceM)?.let {
                Text(stringResource(R.string.away_straight_line, it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (place.weekdayHours.isNotEmpty()) {
                TextButton(onClick = { showHours = !showHours }) {
                    Text(stringResource(if (showHours) R.string.hide_hours else R.string.show_hours))
                }
                if (showHours) place.weekdayHours.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(Space.lg))
            InfoBanner(stringResource(R.string.google_place_explainer), Icons.Filled.Info, tone = Milega.colors.google)
            Spacer(Modifier.height(Space.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                ShopActionButton(stringResource(R.string.directions), Icons.Filled.Directions, {
                    if (place.lat != null && place.lng != null) openDirections(context, place.lat, place.lng, placeId = place.placeId)
                    else place.mapsUri?.let { openUrl(context, it) }
                }, Modifier.weight(1f))
                place.phone?.let { phone ->
                    ShopActionButton(stringResource(R.string.call), Icons.Filled.Call, { dial(context, phone) }, Modifier.weight(1f))
                }
                place.website?.let { url ->
                    ShopActionButton(stringResource(R.string.website), Icons.Filled.Language, { openUrl(context, url) }, Modifier.weight(1f))
                }
                ShopActionButton(stringResource(R.string.report), Icons.Filled.Flag, onReport, Modifier.weight(1f))
            }
            SectionHeader(stringResource(R.string.own_this_shop), stringResource(R.string.own_this_shop_sub))
            OutlinedButton(onClick = onClaim, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Filled.Storefront, null)
                Spacer(Modifier.height(0.dp))
                Text("  " + stringResource(R.string.join_milega))
            }
            Spacer(Modifier.height(Space.lg))
            GoogleAttribution()
        }
    }
}

