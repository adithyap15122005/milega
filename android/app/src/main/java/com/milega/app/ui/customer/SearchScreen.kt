package com.milega.app.ui.customer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.currentLanguage
import com.milega.app.data.DiscoverResp
import com.milega.app.data.Offer
import com.milega.app.data.SearchResp
import com.milega.app.data.SearchResult
import com.milega.app.data.SuggestResp
import com.milega.app.ui.components.AvailabilityLine
import com.milega.app.ui.components.CategoryAvatar
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.ErrorState
import com.milega.app.ui.components.GoogleAttribution
import com.milega.app.ui.components.InfoBanner
import com.milega.app.ui.components.MerchantBadge
import com.milega.app.ui.components.SkeletonCard
import com.milega.app.ui.components.openDirections
import com.milega.app.ui.components.unitPriceText
import com.milega.app.ui.theme.Milega
import com.milega.app.ui.theme.Radius
import com.milega.app.ui.theme.Space
import com.milega.app.util.errorText
import com.milega.app.util.rupees
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchViewModel(private val container: AppContainer) : ViewModel() {
    var query by mutableStateOf("")
    var sort by mutableStateOf("relevance")
    var freshOnly by mutableStateOf(false)
    var category by mutableStateOf<String?>(null)
    var result by mutableStateOf<SearchResp?>(null)
        private set
    var suggestions by mutableStateOf<SuggestResp?>(null)
        private set
    var google by mutableStateOf<DiscoverResp?>(null)
        private set
    var googleLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var started = false
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    fun onType(text: String) {
        query = text
        suggestJob?.cancel()
        if (text.trim().length < 2) {
            suggestions = null
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250) // debounce: don't call the server on every keystroke
            val s = container.session.current
            suggestions = runCatching { container.api.suggest(text, s.lat, s.lng) }.getOrNull()
        }
    }

    fun search() {
        if (query.isBlank() && category == null) return
        suggestJob?.cancel()
        suggestions = null
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            loading = true
            error = null
            google = null
            try {
                val s = container.session.current
                if (query.isNotBlank()) container.session.addRecentSearch(query)
                val r = container.api.searchFull(query.trim(), s.lat, s.lng, sort, category, freshOnly = freshOnly)
                result = r
                if (r.intent != "product") loadGoogle(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                loading = false
            }
        }
    }

    /** Google-listed shops for the same place / kind of shop. Never mixed into Milega inventory results. */
    fun loadGoogle(r: SearchResp? = result) {
        val res = r ?: return
        val lat = res.location.lat ?: container.session.current.lat ?: return
        val lng = res.location.lng ?: container.session.current.lng ?: return
        viewModelScope.launch {
            googleLoading = true
            google = runCatching {
                container.api.discover(lat, lng, radiusKm = 3.0, category = res.externalHint.category,
                    q = res.externalHint.query, lang = currentLanguage())
            }.getOrNull()
            googleLoading = false
        }
    }
}

@Composable
fun SearchScreen(
    container: AppContainer,
    initialQuery: String,
    initialCategory: String?,
    onProduct: (Long) -> Unit,
    onShop: (Long) -> Unit,
    onPlace: (String) -> Unit,
    onLocation: () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val vm: SearchViewModel = viewModel { SearchViewModel(container) }
    val session by container.session.flow.collectAsStateWithLifecycle(initialValue = container.session.current)
    var tab by rememberSaveable { mutableStateOf(0) }
    var reserveTarget by remember { mutableStateOf<ReserveTarget?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (!vm.started) {
            vm.started = true
            vm.query = initialQuery
            vm.category = initialCategory
            if (initialQuery.isNotBlank() || initialCategory != null) vm.search() else runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(vm.result?.intent) { tab = if (vm.result?.intent == "product" || vm.result == null) 0 else 1 }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.query = it; vm.search() }
        }
    }
    fun run() {
        focus.clearFocus()
        vm.search()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ------------------------------------------------------------ search box
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.onType(it) },
            placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = {
                Row {
                    if (vm.query.isNotEmpty()) IconButton(onClick = { vm.onType("") }) { Icon(Icons.Filled.Close, stringResource(R.string.clear)) }
                    IconButton(onClick = {
                        try {
                            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag(currentLanguage())))
                        } catch (_: ActivityNotFoundException) {
                        }
                    }) { Icon(Icons.Filled.Mic, stringResource(R.string.voice_search)) }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(Radius.lg),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm).focusRequester(focusRequester),
        )
        // ------------------------------------------------------------ location line
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOn, null, Modifier.size(16.dp), tint = Milega.colors.accent)
            val loc = vm.result?.location
            val label = when (loc?.source) {
                "query_locality" -> stringResource(R.string.results_near, loc.locality?.name ?: "")
                "landmark" -> stringResource(R.string.results_near, loc.landmark?.name ?: "")
                else -> session.placeName?.let { stringResource(R.string.results_near, it) } ?: stringResource(R.string.set_location)
            }
            TextButton(onClick = onLocation) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        val suggestions = vm.suggestions
        val result = vm.result
        when {
            suggestions != null && vm.query.isNotBlank() -> SuggestionList(suggestions, currentLanguage(),
                onQuery = { vm.query = it; run() },
                onCategory = { vm.category = it; vm.query = ""; run() },
                onLocality = { loc ->
                    scope.launch {
                        container.session.update { it.copy(lat = loc.lat, lng = loc.lng, placeName = loc.name, localitySlug = loc.slug) }
                        run()
                    }
                },
                onShop = onShop)
            result == null && vm.error != null -> ErrorState(errorText(context, vm.error!!)) { run() }
            result == null && !vm.loading -> RecentSearches(session.recentSearches,
                onPick = { vm.query = it; run() },
                onClear = { scope.launch { container.session.clearRecentSearches() } })
            result == null -> Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) { repeat(4) { SkeletonCard() } }
            else -> {
                PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(selected = tab == 0, onClick = { tab = 0 },
                        text = { Text(stringResource(R.string.tab_products, result.totalResults)) })
                    Tab(selected = tab == 1, onClick = { tab = 1; if (vm.google == null && !vm.googleLoading && result.intent == "product") Unit },
                        text = { Text(stringResource(R.string.tab_shops, result.shops.size + (vm.google?.external?.size ?: 0))) })
                }
                if (tab == 0) ProductResults(vm, result, onProduct, onShop, onReserve = { reserveTarget = it })
                else ShopResults(vm, result, onShop, onPlace)
            }
        }
    }
    reserveTarget?.let { target ->
        ReserveDialog(container, target, onDismiss = { reserveTarget = null }, onViewReservations = { reserveTarget = null })
    }
}

@Composable
private fun ProductResults(vm: SearchViewModel, result: SearchResp, onProduct: (Long) -> Unit, onShop: (Long) -> Unit,
                           onReserve: (ReserveTarget) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                val sorts = listOf("relevance" to R.string.sort_best, "nearest" to R.string.sort_nearest,
                    "price" to R.string.sort_price, "availability" to R.string.sort_availability)
                items(sorts) { (key, label) ->
                    FilterChip(selected = vm.sort == key, onClick = { vm.sort = key; vm.search() }, label = { Text(stringResource(label)) })
                }
                item {
                    FilterChip(selected = vm.freshOnly, onClick = { vm.freshOnly = !vm.freshOnly; vm.search() },
                        label = { Text(stringResource(R.string.fresh_only)) })
                }
            }
        }
        result.location.unresolvedLandmark?.let {
            item { InfoBanner(stringResource(R.string.landmark_not_found, it), Icons.Filled.LocationOn, tone = Milega.colors.warning) }
        }
        val interp = result.interpretation
        if (interp.size != null || interp.maxPricePaise != null) {
            item {
                Text(listOfNotNull(interp.size?.let { stringResource(R.string.only_size, it) },
                    interp.maxPricePaise?.let { stringResource(R.string.under_price, rupees(it)) }).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (result.results.isEmpty()) {
            item {
                EmptyState(Icons.Filled.SearchOff,
                    stringResource(if (result.shopsInRange == 0) R.string.no_shops_in_range else R.string.no_results),
                    stringResource(R.string.no_results_body))
            }
            if (result.externalHint.available) item {
                OutlinedButton(onClick = { vm.loadGoogle() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.TravelExplore, null)
                    Spacer(Modifier.width(Space.sm))
                    Text(stringResource(R.string.find_shops_on_google))
                }
            }
        }
        items(result.results, key = { it.variant.id }) { r ->
            ResultCard(r, onOpen = { onProduct(r.variant.id) }, onShop = onShop, onReserve = { o ->
                onReserve(ReserveTarget(o.listingId, r.variant.displayName, o.shop.name, o.pricePaise, o.unitLabel,
                    r.variant.sellUnit, o.availability.available))
            })
        }
        item { StraightLineNote() }
    }
}

@Composable
private fun ShopResults(vm: SearchViewModel, result: SearchResp, onShop: (Long) -> Unit, onPlace: (String) -> Unit) {
    LaunchedEffect(result) { if (vm.google == null && result.externalHint.available && result.intent == "product") vm.loadGoogle() }
    LazyColumn(contentPadding = PaddingValues(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (result.shops.isNotEmpty()) {
            item { Text(stringResource(R.string.milega_merchants), style = MaterialTheme.typography.titleMedium) }
            items(result.shops, key = { "m" + it.id }) { s -> MerchantRow(s) { onShop(s.id) } }
        }
        val google = vm.google
        item {
            Text(stringResource(R.string.more_shops_google), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Space.md))
            if (result.intent == "product") Text(stringResource(R.string.google_not_confirmed), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            vm.googleLoading -> items(3) { SkeletonCard() }
            google == null -> item { ExternalStatusNote(if (result.externalHint.available) "unavailable" else "not_configured") }
            google.external.isEmpty() -> item { ExternalStatusNote(google.externalStatus) }
            else -> {
                items(google.external, key = { "g" + it.placeId }) { p -> ExternalPlaceRow(p) { onPlace(p.placeId) } }
                item { GoogleAttribution() }
            }
        }
        if (result.shops.isEmpty() && google?.external.isNullOrEmpty() && !vm.googleLoading) {
            item { EmptyState(Icons.Filled.Storefront, stringResource(R.string.nothing_here), stringResource(R.string.nothing_here_body)) }
        }
        item { StraightLineNote() }
    }
}

@Composable
private fun ResultCard(r: SearchResult, onOpen: () -> Unit, onShop: (Long) -> Unit, onReserve: (Offer) -> Unit) {
    var expanded by rememberSaveable(r.variant.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(Radius.lg), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(Space.lg)) {
            Row(Modifier.clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                CategoryAvatar(r.variant.category, 48)
                Spacer(Modifier.width(Space.md))
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
            shown.forEach { offer ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = Space.md))
                OfferRow(offer, onShop = { onShop(offer.shop.id) }, onReserve = { onReserve(offer) })
            }
            Row {
                if (r.offers.size > 3) TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) stringResource(R.string.show_less) else stringResource(R.string.show_all_shops, r.offers.size))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpen) { Text(stringResource(R.string.compare_details)) }
            }
        }
    }
}

@Composable
fun OfferRow(offer: Offer, onShop: () -> Unit, onReserve: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = Space.md), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onShop)) {
            Text(offer.shop.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(listOfNotNull(distanceLabel(offer.distanceM),
                    when (offer.shop.openNow) { true -> stringResource(R.string.open_now); false -> stringResource(R.string.closed_now); null -> null }
                ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (offer.shop.merchantState != "verified_merchant") MerchantBadge(offer.shop.merchantState, compact = true)
            }
            Spacer(Modifier.height(2.dp))
            AvailabilityLine(offer.availability)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(offer.pricePaise?.let { rupees(it) } ?: stringResource(R.string.price_unknown),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            unitPriceText(offer.unitPrice)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                IconButton(onClick = { openDirections(context, offer.shop.lat, offer.shop.lng) }) {
                    Icon(Icons.Filled.Directions, stringResource(R.string.directions), tint = MaterialTheme.colorScheme.primary)
                }
                if (offer.reservable) FilledTonalButton(onClick = onReserve, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.reserve))
                }
            }
        }
    }
}

@Composable
private fun SuggestionList(
    s: SuggestResp, lang: String, onQuery: (String) -> Unit, onCategory: (String) -> Unit,
    onLocality: (com.milega.app.data.LocalityDto) -> Unit, onShop: (Long) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(vertical = Space.sm)) {
        items(s.products, key = { "p" + it.id }) { v ->
            SuggestRow(Icons.Filled.Search, v.displayName, null) { onQuery(v.displayName) }
        }
        items(s.categories, key = { "c" + it.slug }) { c ->
            SuggestRow(com.milega.app.ui.components.categoryIcon(c.slug), c.localName(lang), stringResource(R.string.category)) { onCategory(c.slug) }
        }
        items(s.localities, key = { "l" + it.slug }) { l ->
            SuggestRow(Icons.Filled.LocationOn, l.name, stringResource(R.string.set_as_location)) { onLocality(l) }
        }
        items(s.shops, key = { "s" + it.id }) { shop ->
            SuggestRow(Icons.Filled.Storefront, shop.name, listOfNotNull(shop.locality.ifBlank { null }, distanceLabel(shop.distanceM)).joinToString(" · ")) { onShop(shop.id) }
        }
        if (s.products.isEmpty() && s.categories.isEmpty() && s.localities.isEmpty() && s.shops.isEmpty()) {
            item { SuggestRow(Icons.Filled.Search, stringResource(R.string.search_for, s.query), null) { onQuery(s.query) } }
        }
    }
}

@Composable
private fun SuggestRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = Space.gutter),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.lg))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(vertical = Space.sm)) {
        if (recent.isEmpty()) {
            item {
                EmptyState(Icons.Filled.Search, stringResource(R.string.search_empty_title), stringResource(R.string.search_empty_body))
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.recent_searches), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
                }
            }
            items(recent) { q -> SuggestRow(Icons.Filled.History, q, null) { onPick(q) } }
        }
    }
}
