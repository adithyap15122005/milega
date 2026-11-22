package com.milega.app.ui.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.MilegaApi
import com.milega.app.data.Offer
import com.milega.app.data.SearchResp
import com.milega.app.data.SearchResult
import com.milega.app.ui.Routes
import com.milega.app.ui.components.AvailabilityLine
import com.milega.app.ui.components.CategoryAvatar
import com.milega.app.ui.components.DemoBadge
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.ErrorState
import com.milega.app.ui.components.MapPin
import com.milega.app.ui.components.OsmMap
import com.milega.app.ui.components.openDirections
import com.milega.app.util.distance
import com.milega.app.util.errorText
import com.milega.app.util.rupees
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchViewModel(private val api: MilegaApi) : ViewModel() {
    var query by mutableStateOf("")
    var category by mutableStateOf<String?>(null)
    var sort by mutableStateOf("relevance")
    var result by mutableStateOf<SearchResp?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var started = false
    private var job: Job? = null

    fun search(lat: Double?, lng: Double?) {
        if (query.isBlank() && category == null) return
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            error = null
            try {
                result = api.search(query.trim(), lat, lng, sort, category)
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

/** What the customer picked to reserve. */
data class ReserveTarget(
    val listingId: Long,
    val productName: String,
    val shopName: String,
    val pricePaise: Long?,
    val unitLabel: String,
    val sellUnit: String,
    val available: Double,
)

@Composable
fun SearchScreen(container: AppContainer, nav: NavController, initialQuery: String, initialCategory: String?) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val vm: SearchViewModel = viewModel { SearchViewModel(container.api) }
    val session = container.session.current
    var mapMode by rememberSaveable { mutableStateOf(false) }
    var reserveTarget by remember { mutableStateOf<ReserveTarget?>(null) }

    LaunchedEffect(Unit) {
        if (!vm.started) {
            vm.started = true
            vm.query = initialQuery
            vm.category = initialCategory
            vm.search(session.lat, session.lng)
        }
    }
    fun run() {
        focus.clearFocus()
        vm.search(session.lat, session.lng)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            Row(Modifier.statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    trailingIcon = {
                        if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.clear)) }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.LocationOn, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { nav.navigate(Routes.C_LOCATION) }) {
                    Text(session.placeName ?: stringResource(R.string.set_location), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { mapMode = !mapMode }) {
                    Icon(if (mapMode) Icons.AutoMirrored.Filled.List else Icons.Filled.Map,
                        stringResource(if (mapMode) R.string.list_view else R.string.map_view))
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val sorts = listOf(
                    "relevance" to R.string.sort_best, "nearest" to R.string.sort_nearest,
                    "price" to R.string.sort_price, "availability" to R.string.sort_availability,
                )
                items(sorts) { (key, label) ->
                    FilterChip(selected = vm.sort == key, onClick = { vm.sort = key; run() }, label = { Text(stringResource(label)) })
                }
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp)) else Spacer(Modifier.height(10.dp))

            val result = vm.result
            when {
                vm.error != null && result == null -> ErrorState(errorText(context, vm.error!!)) { run() }
                result == null -> {}
                result.results.isEmpty() -> EmptyState(
                    Icons.Filled.SearchOff,
                    stringResource(if (result.shopsInRange == 0) R.string.no_shops_in_range else R.string.no_results),
                    stringResource(R.string.no_results_body),
                )
                mapMode -> SearchMap(result, session.lat ?: result.results[0].offers[0].shop.lat,
                    session.lng ?: result.results[0].offers[0].shop.lng) { nav.navigate(Routes.shop(it)) }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val interp = result.interpretation
                    if (interp.size != null || interp.maxPricePaise != null) {
                        item {
                            Text(
                                listOfNotNull(
                                    interp.size?.let { stringResource(R.string.only_size, it) },
                                    interp.maxPricePaise?.let { stringResource(R.string.under_price, rupees(it)) },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(result.results, key = { it.variant.id }) { r ->
                        ResultCard(r, onShop = { nav.navigate(Routes.shop(it)) }, onReserve = { offer ->
                            reserveTarget = ReserveTarget(offer.listingId, r.variant.displayName, offer.shop.name, offer.pricePaise,
                                offer.unitLabel, r.variant.sellUnit, offer.availability.available)
                        })
                    }
                    item {
                        Text(stringResource(R.string.distance_note), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    reserveTarget?.let { target ->
        ReserveDialog(container, target, onDismiss = { reserveTarget = null }, onViewReservations = {
            reserveTarget = null
            nav.navigate(Routes.C_RESERVATIONS)
        })
    }
}

@Composable
private fun ResultCard(r: SearchResult, onShop: (Long) -> Unit, onReserve: (Offer) -> Unit) {
    var expanded by rememberSaveable(r.variant.id) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryAvatar(r.variant.category, 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.variant.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (r.minPricePaise != null) stringResource(R.string.from_price_at_shops, rupees(r.minPricePaise), r.inStockCount)
                        else stringResource(R.string.shops_count, r.offers.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val shown = if (expanded) r.offers else r.offers.take(3)
            shown.forEachIndexed { i, offer ->
                if (i == 0) Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OfferRow(offer, onShop = { onShop(offer.shop.id) }, onReserve = { onReserve(offer) })
            }
            if (r.offers.size > 3) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) stringResource(R.string.show_less) else stringResource(R.string.show_all_shops, r.offers.size))
                }
            }
        }
    }
}

@Composable
private fun OfferRow(offer: Offer, onShop: () -> Unit, onReserve: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            TextButton(onClick = onShop, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(28.dp)) {
                Text(offer.shop.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    listOfNotNull(distance(context, offer.distanceM),
                        when (offer.shop.openNow) { true -> stringResource(R.string.open_now); false -> stringResource(R.string.closed_now); null -> null }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (offer.shop.isDemo) DemoBadge()
            }
            Spacer(Modifier.height(2.dp))
            AvailabilityLine(offer.availability)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(rupees(offer.pricePaise), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = { openDirections(context, offer.shop.lat, offer.shop.lng) }) {
                    Icon(Icons.Filled.Directions, stringResource(R.string.directions), tint = MaterialTheme.colorScheme.primary)
                }
                if (offer.reservable) FilledTonalButton(onClick = onReserve) { Text(stringResource(R.string.reserve)) }
            }
        }
    }
}

@Composable
private fun SearchMap(result: SearchResp, lat: Double, lng: Double, onShop: (Long) -> Unit) {
    val context = LocalContext.current
    val pins = result.results.flatMap { r ->
        r.offers.map { o ->
            MapPin(o.shop.id, o.shop.lat, o.shop.lng, o.shop.name,
                "${r.variant.displayName} · ${rupees(o.pricePaise)} · ${distance(context, o.distanceM) ?: ""}")
        }
    }.distinctBy { it.id }
    Column(Modifier.fillMaxSize()) {
        OsmMap(lat, lng, pins, Modifier.fillMaxWidth().weight(1f), zoom = 14.5, onPinClick = {})
        LazyColumn(Modifier.fillMaxWidth().height(180.dp), contentPadding = PaddingValues(12.dp)) {
            items(pins) { pin ->
                TextButton(onClick = { onShop(pin.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(pin.title, style = MaterialTheme.typography.titleSmall)
                        Text(pin.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
