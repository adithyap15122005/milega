package com.milega.app.ui.customer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.currentLanguage
import com.milega.app.data.CategoryDto
import com.milega.app.data.ShopDto
import com.milega.app.ui.CustomerBottomBar
import com.milega.app.ui.Routes
import com.milega.app.ui.components.CategoryAvatar
import com.milega.app.ui.components.DemoBadge
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.LoadingState
import com.milega.app.ui.components.Pill
import com.milega.app.ui.components.SectionTitle
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.theme.BrandColors
import com.milega.app.util.distance
import com.milega.app.util.errorText

fun CategoryDto.localName(lang: String) = when (lang) {
    "hi" -> nameHi
    "kn" -> nameKn
    else -> nameEn
}

@Composable
fun DiscoverScreen(container: AppContainer, nav: NavController) {
    val context = LocalContext.current
    val session by container.session.flow.collectAsStateWithLifecycle(initialValue = container.session.current)
    val lang = currentLanguage()
    val categories = rememberLoadable { container.categories() }
    val lat = session.lat
    val lng = session.lng
    val shops = rememberLoadable(lat, lng) {
        if (lat != null && lng != null) container.api.nearbyShops(lat, lng, 3.0).shops else emptyList()
    }

    Scaffold(bottomBar = { CustomerBottomBar(nav, Routes.C_HOME) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                Row(
                    Modifier.clickable { nav.navigate(Routes.C_LOCATION) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.LocationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        session.placeName ?: stringResource(R.string.set_location),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.discover_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(14.dp))
                Surface(
                    onClick = { nav.navigate(Routes.search()) },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            item {
                val examples = listOf(
                    stringResource(R.string.example_q1), stringResource(R.string.example_q2),
                    stringResource(R.string.example_q3), stringResource(R.string.example_q4),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(examples) { q -> AssistChip(onClick = { nav.navigate(Routes.search(q)) }, label = { Text(q) }) }
                }
            }
            if (lat == null) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.location_needed_title), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.location_needed_body), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = { nav.navigate(Routes.C_LOCATION) }) { Text(stringResource(R.string.choose_location)) }
                        }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.shop_by_category)) }
            item {
                val cats = categories.data.orEmpty().filter { it.slug != "other" }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    cats.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { c ->
                                Column(
                                    Modifier.weight(1f).clickable { nav.navigate(Routes.search(category = c.slug)) }.padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    CategoryAvatar(c.slug, 52)
                                    Spacer(Modifier.height(6.dp))
                                    Text(c.localName(lang), style = MaterialTheme.typography.labelMedium, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (lat != null) {
                item { SectionTitle(stringResource(R.string.shops_near_you)) }
                val list = shops.data
                when {
                    list == null && shops.error != null -> item {
                        EmptyState(Icons.Filled.Storefront, errorText(context, shops.error!!), null,
                            stringResource(R.string.retry), { shops.load() })
                    }
                    list == null -> item { LoadingState(Modifier.fillMaxWidth().height(120.dp)) }
                    list.isEmpty() -> item {
                        EmptyState(Icons.Filled.Storefront, stringResource(R.string.no_shops_nearby), stringResource(R.string.no_shops_nearby_body))
                    }
                    else -> items(list, key = { it.id }) { shop ->
                        ShopCard(shop) { nav.navigate(Routes.shop(shop.id)) }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ShopCard(shop: ShopDto, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryAvatar(shop.categories.firstOrNull() ?: "other", 48)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shop.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (shop.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, stringResource(R.string.verified_shop), tint = BrandColors.Good, modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    listOfNotNull(shop.locality.ifBlank { null }, distance(context, shop.distanceM)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (shop.openNow) {
                        true -> Pill(stringResource(R.string.open_now), BrandColors.Good)
                        false -> Pill(stringResource(R.string.closed_now), BrandColors.Neutral)
                        null -> {}
                    }
                    if (shop.productsInStock != null) Pill(stringResource(R.string.products_count, shop.productsInStock), BrandColors.Indigo)
                    if (shop.isDemo) DemoBadge()
                }
            }
        }
    }
}

@Composable
internal fun FullScreenBox(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
