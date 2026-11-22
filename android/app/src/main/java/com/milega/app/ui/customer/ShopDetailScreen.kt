package com.milega.app.ui.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.ShopDto
import com.milega.app.data.ShopProduct
import com.milega.app.ui.Routes
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.AvailabilityLine
import com.milega.app.ui.components.DemoBadge
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.LoadableContent
import com.milega.app.ui.components.MapPin
import com.milega.app.ui.components.OsmMap
import com.milega.app.ui.components.Pill
import com.milega.app.ui.components.dial
import com.milega.app.ui.components.openDirections
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.theme.BrandColors
import com.milega.app.util.distance
import com.milega.app.util.rupees

private val WEEKDAYS = listOf(R.string.mon, R.string.tue, R.string.wed, R.string.thu, R.string.fri, R.string.sat, R.string.sun)

@Composable
fun ShopDetailScreen(container: AppContainer, nav: NavController, shopId: Long) {
    val session = container.session.current
    val shop = rememberLoadable(shopId) { container.api.shop(shopId, session.lat, session.lng) }
    val products = rememberLoadable(shopId) { container.api.shopProducts(shopId).products }
    var filter by rememberSaveable { mutableStateOf("") }
    var reserveTarget by remember { mutableStateOf<ReserveTarget?>(null) }

    AppScaffold(title = shop.data?.name ?: "", onBack = { nav.popBackStack() }) { padding ->
        LoadableContent(shop) { s ->
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp)) {
                item { ShopHeader(s) }
                item {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = filter, onValueChange = { filter = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        placeholder = { Text(stringResource(R.string.search_in_shop)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                val list = products.data
                if (list != null) {
                    val needle = filter.trim().lowercase()
                    val shown = if (needle.isEmpty()) list else list.filter {
                        it.name.lowercase().contains(needle) || (it.brand?.lowercase()?.contains(needle) == true)
                    }
                    if (shown.isEmpty()) item { EmptyState(Icons.Filled.Inventory2, stringResource(R.string.no_products_here), null) }
                    items(shown, key = { it.listingId }) { p ->
                        ProductRow(p) {
                            reserveTarget = ReserveTarget(p.listingId, p.name, s.name, p.pricePaise, p.unitLabel, p.sellUnit, p.availability.available)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
    reserveTarget?.let { target ->
        ReserveDialog(container, target, onDismiss = { reserveTarget = null; products.load(silent = true) }, onViewReservations = {
            reserveTarget = null
            nav.navigate(Routes.C_RESERVATIONS)
        })
    }
}

@Composable
private fun ShopHeader(s: ShopDto) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column {
            OsmMap(s.lat, s.lng, listOf(MapPin(s.id, s.lat, s.lng, s.name)),
                Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                zoom = 16.0, interactive = false, youAreHere = false)
            Column(Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (s.openNow) {
                        true -> Pill(stringResource(R.string.open_now), BrandColors.Good)
                        false -> Pill(stringResource(R.string.closed_now), BrandColors.Neutral)
                        null -> {}
                    }
                    if (s.verified) Pill(stringResource(R.string.verified_shop), BrandColors.Good)
                    if (s.isDemo) DemoBadge()
                }
                Spacer(Modifier.height(8.dp))
                Text(listOf(s.addressLine.ifBlank { s.locality }, s.city).filter { it.isNotBlank() }.distinct().joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium)
                distance(context, s.distanceM)?.let {
                    Text(stringResource(R.string.away_straight_line, it), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val today = java.time.LocalDate.now().dayOfWeek.value - 1
                s.hours.firstOrNull { it.weekday == today }?.let { h ->
                    Text(
                        if (h.isClosed) stringResource(R.string.closed_today)
                        else stringResource(R.string.hours_today, h.opens, h.closes),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.hours.any { it.isClosed }) {
                    Text(stringResource(R.string.weekly_off, s.hours.filter { it.isClosed }.joinToString { context.getString(WEEKDAYS[it.weekday]) }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { openDirections(context, s.lat, s.lng) }) {
                        Icon(Icons.Filled.Directions, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.directions))
                    }
                    s.phone?.let { phone ->
                        OutlinedButton(onClick = { dial(context, phone) }) {
                            Icon(Icons.Filled.Call, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.call))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductRow(p: ShopProduct, onReserve: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            AvailabilityLine(p.availability)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(rupees(p.pricePaise) + if (p.sellUnit != "pc") " /${p.unitLabel}" else "",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (p.availability.status != "out_of_stock") {
                FilledTonalButton(onClick = onReserve, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(stringResource(R.string.reserve))
                }
            }
        }
    }
}
