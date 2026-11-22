package com.milega.app.ui.customer

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.clustering.Clustering
import com.google.maps.android.compose.rememberCameraPositionState
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.currentLanguage
import com.milega.app.data.CategoryDto
import com.milega.app.data.DiscoverResp
import com.milega.app.data.ExternalPlace
import com.milega.app.data.ShopDto
import com.milega.app.ui.components.CategoryAvatar
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.GoogleAttribution
import com.milega.app.ui.components.InfoBanner
import com.milega.app.ui.components.MapsAvailable
import com.milega.app.ui.components.MerchantBadge
import com.milega.app.ui.components.categoryIcon
import com.milega.app.ui.components.openDirections
import com.milega.app.ui.theme.Milega
import com.milega.app.ui.theme.Radius
import com.milega.app.ui.theme.Space
import com.milega.app.util.errorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val BENGALURU = LatLng(12.9716, 77.5946)

/** A pin on the Explore map: a Milega merchant or a Google-listed business. */
sealed class MapPinItem(private val pos: LatLng, private val label: String, private val z: Float) : ClusterItem {
    override fun getPosition(): LatLng = pos
    override fun getTitle(): String = label
    override fun getSnippet(): String? = null
    override fun getZIndex(): Float = z

    class Merchant(val shop: ShopDto) : MapPinItem(LatLng(shop.lat, shop.lng), shop.name, 2f)
    class Place(val place: ExternalPlace) : MapPinItem(LatLng(place.lat ?: 0.0, place.lng ?: 0.0), place.name, 1f)
}

class ExploreViewModel(private val container: AppContainer) : ViewModel() {
    var result by mutableStateOf<DiscoverResp?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var searchedCenter by mutableStateOf<LatLng?>(null)
        private set
    var categories by mutableStateOf<List<CategoryDto>>(emptyList())
        private set
    private var job: Job? = null

    fun loadCategories() = viewModelScope.launch {
        if (categories.isEmpty()) categories = runCatching { container.categories() }.getOrDefault(emptyList())
    }

    fun search(center: LatLng, radiusKm: Double, category: String?) {
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            error = null
            try {
                result = container.api.discover(center.latitude, center.longitude, radiusKm, category, lang = currentLanguage())
                searchedCenter = center
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                loading = false
            }
        }
    }
}

@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun ExploreScreen(container: AppContainer, initialCategory: String?, onShop: (Long) -> Unit, onPlace: (String) -> Unit) {
    val context = LocalContext.current
    val vm: ExploreViewModel = viewModel { ExploreViewModel(container) }
    val session = container.session.current
    val start = if (session.lat != null && session.lng != null) LatLng(session.lat, session.lng) else BENGALURU
    var category by rememberSaveable { mutableStateOf(initialCategory) }
    var radiusKm by rememberSaveable { mutableStateOf(2.0) }
    var listMode by rememberSaveable { mutableStateOf(!MapsAvailable) }
    var selected by remember { mutableStateOf<MapPinItem?>(null) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(start, zoomFor(radiusKm)) }
    var movedAway by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.loadCategories()
        if (vm.result == null) vm.search(start, radiusKm, category)
    }
    LaunchedEffect(camera) {
        snapshotFlow { camera.isMoving to camera.position.target }.collect { (moving, target) ->
            val last = vm.searchedCenter
            if (!moving && last != null) movedAway = metersBetween(last, target) > radiusKm * 1000 * 0.35
        }
    }
    fun searchHere(center: LatLng = camera.position.target) {
        selected = null
        movedAway = false
        vm.search(center, radiusKm, category)
    }

    val result = vm.result
    val pins = remember(result) {
        result?.merchants.orEmpty().map { MapPinItem.Merchant(it) } +
            result?.external.orEmpty().filter { it.lat != null && it.lng != null }.map { MapPinItem.Place(it) }
    }
    val canShowLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!listMode && MapsAvailable) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                properties = MapProperties(isMyLocationEnabled = canShowLocation),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = canShowLocation,
                    mapToolbarEnabled = false),
                contentPadding = PaddingValues(top = 132.dp, bottom = 96.dp),
                onMapClick = { selected = null },
            ) {
                Clustering(
                    items = pins,
                    onClusterItemClick = { selected = it; true },
                    clusterItemContent = { item -> PinBubble(item) },
                )
            }
        } else {
            ExploreList(result, vm.error?.let { errorText(context, it) }, onShop, onPlace, onRetry = { searchHere(start) })
        }

        // ------------------------------------------------ filters (top overlay)
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = Space.sm)) {
            Text(stringResource(R.string.explore_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = Space.gutter))
            Spacer(Modifier.height(Space.sm))
            LazyRow(contentPadding = PaddingValues(horizontal = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                item {
                    FilterChip(selected = category == null, onClick = { category = null; searchHere() },
                        label = { Text(stringResource(R.string.all_shops)) }, colors = chipColors())
                }
                items(vm.categories.filter { it.slug != "other" }, key = { it.slug }) { c ->
                    FilterChip(
                        selected = category == c.slug,
                        onClick = { category = if (category == c.slug) null else c.slug; searchHere() },
                        label = { Text(c.localName(currentLanguage())) },
                        leadingIcon = { Icon(categoryIcon(c.slug), null, Modifier.size(18.dp)) },
                        colors = chipColors(),
                    )
                }
            }
            Spacer(Modifier.height(Space.xs))
            LazyRow(contentPadding = PaddingValues(horizontal = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(listOf(1.0, 2.0, 5.0)) { km ->
                    FilterChip(selected = radiusKm == km, onClick = { radiusKm = km; searchHere() },
                        label = { Text(stringResource(R.string.within_km, km.toInt())) }, colors = chipColors())
                }
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Space.xs))
            AnimatedVisibility(movedAway && !vm.loading && !listMode, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                FilledTonalButton(onClick = { searchHere() }, modifier = Modifier.padding(top = Space.sm)) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.search_this_area))
                }
            }
        }

        // ------------------------------------------------ bottom: selection / toggle
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(Space.lg)) {
            AnimatedVisibility(selected != null && !listMode, enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()) {
                selected?.let { SelectedCard(it, onShop, onPlace, onClose = { selected = null }) }
            }
            if (MapsAvailable) {
                Spacer(Modifier.height(Space.md))
                ExtendedFloatingActionButton(
                    onClick = { listMode = !listMode; selected = null },
                    icon = { Icon(if (listMode) Icons.Filled.Map else Icons.AutoMirrored.Filled.List, null) },
                    text = { Text(stringResource(if (listMode) R.string.map_view else R.string.list_view)) },
                    containerColor = Milega.colors.brandSurface, contentColor = Milega.colors.onBrandSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            if (!listMode && result?.attribution != null) GoogleAttribution(Modifier.align(Alignment.End).padding(top = 4.dp))
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surface,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
private fun PinBubble(item: MapPinItem) {
    val merchant = item is MapPinItem.Merchant
    val slug = when (item) {
        is MapPinItem.Merchant -> item.shop.categories.firstOrNull()
        is MapPinItem.Place -> item.place.category
    }
    Box(
        Modifier.size(if (merchant) 40.dp else 32.dp).clip(CircleShape)
            .background(if (merchant) MaterialTheme.colorScheme.primary else Color.White)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(categoryIcon(slug), item.title,
            tint = if (merchant) MaterialTheme.colorScheme.onPrimary else Milega.colors.google,
            modifier = Modifier.size(if (merchant) 22.dp else 18.dp))
    }
}

@Composable
private fun SelectedCard(item: MapPinItem, onShop: (Long) -> Unit, onPlace: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(Radius.lg), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.Top) {
                val slug = if (item is MapPinItem.Merchant) item.shop.categories.firstOrNull() else (item as MapPinItem.Place).place.category
                CategoryAvatar(slug, 44)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    when (item) {
                        is MapPinItem.Merchant -> {
                            Text(listOfNotNull(item.shop.locality.ifBlank { null }, distanceLabel(item.shop.distanceM)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            MerchantBadge(item.shop.merchantState, compact = true)
                        }
                        is MapPinItem.Place -> {
                            Text(listOfNotNull(item.place.typeLabel, distanceLabel(item.place.distanceM)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            MerchantBadge("google_discovered", compact = true)
                        }
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
            }
            Spacer(Modifier.height(Space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                FilledTonalButton(onClick = {
                    when (item) {
                        is MapPinItem.Merchant -> onShop(item.shop.id)
                        is MapPinItem.Place -> onPlace(item.place.placeId)
                    }
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Storefront, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.view_shop))
                }
                FilledTonalButton(onClick = {
                    val p = item.position
                    openDirections(context, p.latitude, p.longitude, placeId = (item as? MapPinItem.Place)?.place?.placeId)
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Directions, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.directions))
                }
            }
        }
    }
}

@Composable
private fun ExploreList(result: DiscoverResp?, error: String?, onShop: (Long) -> Unit, onPlace: (String) -> Unit, onRetry: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = 150.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "explore-list" },
    ) {
        if (!MapsAvailable) item { InfoBanner(stringResource(R.string.map_unavailable), Icons.Filled.Map) }
        if (result == null) {
            if (error != null) item { EmptyState(Icons.Filled.Storefront, error, null, stringResource(R.string.retry), onRetry) }
            return@LazyColumn
        }
        if (result.merchants.isNotEmpty()) {
            item { Text(stringResource(R.string.milega_merchants), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.sm)) }
            items(result.merchants, key = { "m" + it.id }) { s -> MerchantRow(s) { onShop(s.id) } }
        }
        if (result.external.isNotEmpty()) {
            item { Text(stringResource(R.string.more_shops_google), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.md)) }
            items(result.external, key = { "g" + it.placeId }) { p -> ExternalPlaceRow(p) { onPlace(p.placeId) } }
            item { GoogleAttribution() }
        } else {
            item { ExternalStatusNote(result.externalStatus) }
        }
        if (result.merchants.isEmpty() && result.external.isEmpty()) {
            item { EmptyState(Icons.Filled.Storefront, stringResource(R.string.nothing_here), stringResource(R.string.nothing_here_body)) }
        }
        item { StraightLineNote() }
    }
}

private fun zoomFor(radiusKm: Double): Float = when {
    radiusKm <= 1.0 -> 15.5f
    radiusKm <= 2.0 -> 14.5f
    else -> 13.2f
}

private fun metersBetween(a: LatLng, b: LatLng): Float {
    val out = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
    return out[0]
}

