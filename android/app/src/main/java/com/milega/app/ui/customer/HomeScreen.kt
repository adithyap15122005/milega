package com.milega.app.ui.customer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.currentLanguage
import com.milega.app.data.CategoryDto
import com.milega.app.data.ExternalPlace
import com.milega.app.data.NearbyItem
import com.milega.app.data.RecentShop
import com.milega.app.data.ShopDto
import com.milega.app.ui.components.CategoryTile
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.GoogleAttribution
import com.milega.app.ui.components.HeroSearchBar
import com.milega.app.ui.components.SectionHeader
import com.milega.app.ui.components.SkeletonCard
import com.milega.app.ui.components.SkeletonTile
import com.milega.app.ui.theme.Milega
import com.milega.app.ui.theme.Radius
import com.milega.app.ui.theme.Space
import com.milega.app.util.errorText
import com.milega.app.util.parseInstant
import com.milega.app.util.rupees
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalTime
import java.time.OffsetDateTime

/** Everything the home screen shows. Each list is real server data; empty lists hide their section. */
data class HomeData(
    val merchants: List<ShopDto> = emptyList(),
    val external: List<ExternalPlace> = emptyList(),
    val externalStatus: String = "disabled",
    val nearby: List<NearbyItem> = emptyList(),
    val saved: List<ShopDto> = emptyList(),
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    var data by mutableStateOf<HomeData?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var categories by mutableStateOf<List<CategoryDto>>(emptyList())
        private set
    var unread by mutableStateOf(0)
        private set
    private var loadedFor: Pair<Double, Double>? = null

    fun load(lat: Double?, lng: Double?, force: Boolean = false) {
        viewModelScope.launch {
            if (categories.isEmpty()) categories = runCatching { container.categories() }.getOrDefault(emptyList())
            unread = runCatching { container.api.notifications(unreadOnly = true).unread }.getOrDefault(unread)
        }
        if (lat == null || lng == null) {
            data = HomeData()
            return
        }
        if (!force && loadedFor == lat to lng && data != null) return
        loadedFor = lat to lng
        viewModelScope.launch {
            error = null
            if (force || data == null) data = null
            try {
                val discover = async { container.api.discover(lat, lng, radiusKm = 2.0, lang = currentLanguage()) }
                val nearby = async { runCatching { container.api.productsNearby(lat, lng).items }.getOrDefault(emptyList()) }
                val saved = async {
                    if (container.session.current.loggedIn) runCatching { container.api.saved().shops }.getOrDefault(emptyList())
                    else emptyList()
                }
                val d = discover.await()
                data = HomeData(d.merchants, d.external, d.externalStatus, nearby.await(), saved.await())
            } catch (e: Exception) {
                error = e
            }
        }
    }
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onSearch: (String) -> Unit,
    onCategory: (String) -> Unit,
    onShop: (Long) -> Unit,
    onPlace: (String) -> Unit,
    onProduct: (Long) -> Unit,
    onLocation: () -> Unit,
    onNotifications: () -> Unit,
    onAccount: () -> Unit,
    onExplore: () -> Unit,
) {
    val context = LocalContext.current
    val session by container.session.flow.collectAsStateWithLifecycle(initialValue = container.session.current)
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    LaunchedEffect(session.lat, session.lng) { vm.load(session.lat, session.lng) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onSearch)
        }
    }
    HomeContent(
        name = session.name,
        placeName = session.placeName,
        hasLocation = session.hasLocation,
        unread = vm.unread,
        categories = vm.categories,
        lang = currentLanguage(),
        data = vm.data,
        error = vm.error?.let { errorText(context, it) },
        recent = session.recentShops,
        onRetry = { vm.load(session.lat, session.lng, force = true) },
        onSearchClick = { onSearch("") },
        onVoice = {
            try {
                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag(currentLanguage())))
            } catch (e: ActivityNotFoundException) {
                onSearch("")
            }
        },
        onExample = onSearch,
        onCategory = onCategory,
        onShop = onShop,
        onPlace = onPlace,
        onProduct = onProduct,
        onLocation = onLocation,
        onNotifications = onNotifications,
        onAccount = onAccount,
        onExplore = onExplore,
    )
}

private val EXPLAINED_STATUSES = setOf("not_configured", "quota_exceeded", "denied", "unavailable", "outside_service_area")

fun speechTag(lang: String) = when (lang) {
    "hi" -> "hi-IN"
    "kn" -> "kn-IN"
    else -> "en-IN"
}

@Composable
fun HomeContent(
    name: String?,
    placeName: String?,
    hasLocation: Boolean,
    unread: Int,
    categories: List<CategoryDto>,
    lang: String,
    data: HomeData?,
    error: String?,
    recent: List<RecentShop>,
    onRetry: () -> Unit,
    onSearchClick: () -> Unit,
    onVoice: () -> Unit,
    onExample: (String) -> Unit,
    onCategory: (String) -> Unit,
    onShop: (Long) -> Unit,
    onPlace: (String) -> Unit,
    onProduct: (Long) -> Unit,
    onLocation: () -> Unit,
    onNotifications: () -> Unit,
    onAccount: () -> Unit,
    onExplore: () -> Unit,
    now: LocalTime = LocalTime.now(),
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = Space.xxxl),
    ) {
        // ---------------------------------------------------------- header + hero
        item {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = Radius.xl, bottomEnd = Radius.xl))
                    .background(Milega.colors.brandSurface)
                    .statusBarsPadding()
                    .padding(start = Space.gutter, end = Space.sm, top = Space.sm, bottom = Space.xxl),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium,
                            color = Milega.colors.onBrandSurface, fontWeight = FontWeight.ExtraBold)
                        LocalityChip(placeName, onLocation)
                    }
                    IconButton(onClick = onNotifications, modifier = Modifier.size(Space.touch)) {
                        BadgedBox(badge = { if (unread > 0) Badge(containerColor = Milega.colors.accent) { Text("$unread") } }) {
                            Icon(Icons.Filled.Notifications, stringResource(R.string.notifications), tint = Milega.colors.onBrandSurface)
                        }
                    }
                    IconButton(onClick = onAccount, modifier = Modifier.size(Space.touch)) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Milega.colors.accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text((name?.firstOrNull() ?: 'M').uppercase(), color = Milega.colors.onAccent,
                                style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                Spacer(Modifier.height(Space.xl))
                Text(greeting(now, name), style = MaterialTheme.typography.bodyLarge,
                    color = Milega.colors.onBrandSurface.copy(alpha = 0.85f))
                Spacer(Modifier.height(Space.xs))
                Text(stringResource(R.string.home_hero_title), style = MaterialTheme.typography.headlineLarge,
                    color = Milega.colors.onBrandSurface, modifier = Modifier.padding(end = Space.md))
                Spacer(Modifier.height(Space.lg))
                Box(Modifier.padding(end = Space.md)) {
                    HeroSearchBar(stringResource(R.string.search_hint), onClick = onSearchClick, onVoice = onVoice)
                }
                Spacer(Modifier.height(Space.md))
                val examples = listOf(R.string.example_q1, R.string.example_q2, R.string.example_q3, R.string.example_q4)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), contentPadding = PaddingValues(end = Space.md)) {
                    items(examples) { res ->
                        val q = stringResource(res)
                        SuggestionChip(
                            onClick = { onExample(q) }, label = { Text(q) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Milega.colors.onBrandSurface.copy(alpha = 0.10f),
                                labelColor = Milega.colors.onBrandSurface,
                            ),
                            border = BorderStroke(1.dp, Milega.colors.onBrandSurface.copy(alpha = 0.25f)),
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------- categories
        if (categories.isNotEmpty()) {
            item { Box(Modifier.padding(horizontal = Space.gutter)) { SectionHeader(stringResource(R.string.shop_by_category)) } }
            item {
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().height(232.dp),
                    contentPadding = PaddingValues(horizontal = Space.lg),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    items(categories.filter { it.slug != "other" }, key = { it.slug }) { c ->
                        CategoryTile(c.slug, c.localName(lang), onClick = { onCategory(c.slug) }, modifier = Modifier.width(84.dp))
                    }
                }
            }
        }

        if (!hasLocation) {
            item { Box(Modifier.padding(Space.gutter)) { LocationPrompt(onLocation) } }
            return@LazyColumn
        }

        if (error != null && data == null) {
            item { EmptyState(Icons.Filled.Storefront, error, null, stringResource(R.string.retry), onRetry) }
            return@LazyColumn
        }

        // ---------------------------------------------------------- merchants
        item {
            Box(Modifier.padding(horizontal = Space.gutter)) {
                SectionHeader(stringResource(R.string.shops_near_you), stringResource(R.string.shops_near_you_sub),
                    stringResource(R.string.map), onExplore)
            }
        }
        if (data == null) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    items(3) { SkeletonTile(232.dp, 160.dp) }
                }
            }
            items(3) { Box(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) { SkeletonCard() } }
            return@LazyColumn
        }
        if (data.merchants.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(Radius.lg), color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.padding(horizontal = Space.gutter).fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.no_merchants_here), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Space.lg))
                }
            }
        } else {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    items(data.merchants.take(12), key = { it.id }) { s -> MerchantCard(s, onClick = { onShop(s.id) }) }
                }
            }
        }

        // ---------------------------------------------------- products in stock
        if (data.nearby.isNotEmpty()) {
            item { Box(Modifier.padding(horizontal = Space.gutter)) { SectionHeader(stringResource(R.string.in_stock_near_you), stringResource(R.string.in_stock_near_you_sub)) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    items(data.nearby, key = { it.variant.id }) { item -> ProductTile(item, onClick = { onProduct(item.variant.id) }) }
                }
            }
        }

        val comparable = data.nearby.filter { it.offers >= 2 }
        if (comparable.isNotEmpty()) {
            item { Box(Modifier.padding(horizontal = Space.gutter)) { SectionHeader(stringResource(R.string.compare_prices), stringResource(R.string.compare_prices_sub)) } }
            items(comparable.take(4), key = { "cmp-" + it.variant.id }) { item ->
                Box(Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) { CompareRow(item) { onProduct(item.variant.id) } }
            }
        }

        val fresh = data.merchants.filter { m ->
            val v = parseInstant(m.verifiedAt)
            v != null && Duration.between(v, OffsetDateTime.now()).toDays() <= 30
        }
        if (fresh.isNotEmpty()) {
            item { Box(Modifier.padding(horizontal = Space.gutter)) { SectionHeader(stringResource(R.string.new_on_milega)) } }
            items(fresh.take(3), key = { "new-" + it.id }) { s ->
                Box(Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) { MerchantRow(s) { onShop(s.id) } }
            }
        }

        if (data.saved.isNotEmpty()) {
            item { Box(Modifier.padding(horizontal = Space.gutter)) { SectionHeader(stringResource(R.string.saved_shops)) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    items(data.saved, key = { "saved-" + it.id }) { s -> MerchantCard(s, onClick = { onShop(s.id) }) }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item { Box(Modifier.padding(horizontal = Space.gutter)) { SectionHeader(stringResource(R.string.recently_viewed)) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    items(recent, key = { "recent-" + it.id }) { r ->
                        SuggestionChip(onClick = { onShop(r.id) }, label = { Text(r.name, maxLines = 1) },
                            icon = { Icon(Icons.Filled.Storefront, null, Modifier.size(18.dp)) })
                    }
                }
            }
        }

        // -------------------------------------------------- Google-listed shops
        if (data.external.isNotEmpty() || data.externalStatus in EXPLAINED_STATUSES) {
            item {
                Box(Modifier.padding(horizontal = Space.gutter)) {
                    SectionHeader(stringResource(R.string.more_shops_google), stringResource(R.string.more_shops_google_sub),
                        if (data.external.isNotEmpty()) stringResource(R.string.see_on_map) else null, onExplore)
                }
            }
            if (data.external.isEmpty()) {
                item { Box(Modifier.padding(horizontal = Space.gutter)) { ExternalStatusNote(data.externalStatus) } }
            }
            items(data.external.take(6), key = { it.placeId }) { p ->
                Box(Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) { ExternalPlaceRow(p) { onPlace(p.placeId) } }
            }
            if (data.external.isNotEmpty()) item {
                GoogleAttribution(Modifier.padding(horizontal = Space.gutter, vertical = Space.sm))
            }
        }
        item { StraightLineNote(Modifier.padding(horizontal = Space.gutter)) }
    }
}

@Composable
private fun LocalityChip(placeName: String?, onClick: () -> Unit) {
    val label = placeName ?: stringResource(R.string.set_location)
    val desc = stringResource(R.string.change_location_desc, label)
    Surface(
        onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.heightIn(min = Space.touch).semantics { contentDescription = desc },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOn, null, tint = Milega.colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = Milega.colors.onBrandSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = Milega.colors.onBrandSurface, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun greeting(now: LocalTime, name: String?): String {
    val first = name?.trim()?.split(" ")?.firstOrNull()?.ifBlank { null }
    val res = when {
        now.hour < 12 -> if (first != null) R.string.greeting_morning_name else R.string.greeting_morning
        now.hour < 17 -> if (first != null) R.string.greeting_afternoon_name else R.string.greeting_afternoon
        else -> if (first != null) R.string.greeting_evening_name else R.string.greeting_evening
    }
    return if (first != null) stringResource(res, first) else stringResource(res)
}

@Composable
private fun ProductTile(item: NearbyItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(Radius.lg), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.width(176.dp),
    ) {
        Column(Modifier.padding(Space.md)) {
            com.milega.app.ui.components.CategoryAvatar(item.variant.category, 40)
            Spacer(Modifier.height(Space.sm))
            Text(item.variant.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 40.dp))
            Spacer(Modifier.height(Space.xs))
            Text(
                if (item.minPricePaise != null) stringResource(R.string.from_price, rupees(item.minPricePaise)) else stringResource(R.string.price_not_listed),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.shops_count, item.offers), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CompareRow(item: NearbyItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(Radius.md), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        Row(Modifier.padding(Space.md), verticalAlignment = Alignment.CenterVertically) {
            com.milega.app.ui.components.CategoryAvatar(item.variant.category, 40)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(item.variant.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.compare_n_shops, item.offers), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.minPricePaise != null) Text(stringResource(R.string.from_price, rupees(item.minPricePaise)),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
