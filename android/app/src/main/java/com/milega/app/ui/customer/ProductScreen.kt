package com.milega.app.ui.customer

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.AltProduct
import com.milega.app.data.ProductDetailResp
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.CategoryAvatar
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.InfoBanner
import com.milega.app.ui.components.LoadableContent
import com.milega.app.ui.components.SectionHeader
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.components.unitPriceText
import com.milega.app.ui.theme.Radius
import com.milega.app.ui.theme.Space
import com.milega.app.util.rupees

@Composable
fun ProductScreen(container: AppContainer, variantId: Long, onBack: () -> Unit, onShop: (Long) -> Unit, onProduct: (Long) -> Unit,
                  onReservations: () -> Unit) {
    val session = container.session.current
    val state = rememberLoadable(variantId) { container.api.product(variantId, session.lat, session.lng) }
    var reserveTarget by remember { mutableStateOf<ReserveTarget?>(null) }
    AppScaffold(title = state.data?.variant?.displayName ?: "", onBack = onBack) { padding ->
        LoadableContent(state) { p ->
            ProductContent(p, PaddingValues(top = padding.calculateTopPadding()), onShop, onProduct, onReserve = { o ->
                reserveTarget = ReserveTarget(o.listingId, p.variant.displayName, o.shop.name, o.pricePaise, o.unitLabel,
                    p.variant.sellUnit, o.availability.available)
            })
        }
    }
    reserveTarget?.let { t ->
        ReserveDialog(container, t, onDismiss = { reserveTarget = null; state.load(silent = true) },
            onViewReservations = { reserveTarget = null; onReservations() })
    }
}

@Composable
fun ProductContent(p: ProductDetailResp, padding: PaddingValues, onShop: (Long) -> Unit, onProduct: (Long) -> Unit,
                   onReserve: (com.milega.app.data.Offer) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = padding.calculateTopPadding(), bottom = Space.xxxl)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryAvatar(p.variant.category, 64)
                Spacer(Modifier.width(Space.lg))
                Column(Modifier.weight(1f)) {
                    p.variant.brand?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    Text(p.variant.displayName, style = MaterialTheme.typography.headlineSmall)
                    p.variant.sizeLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(Space.lg))
            Surface(shape = RoundedCornerShape(Radius.lg), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.lg)) {
                    val s = p.summary
                    Text(
                        when {
                            s.minPricePaise == null -> stringResource(R.string.no_price_nearby)
                            s.minPricePaise == s.maxPricePaise -> rupees(s.minPricePaise)
                            else -> "${rupees(s.minPricePaise)} – ${rupees(s.maxPricePaise)}"
                        },
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(stringResource(R.string.in_stock_at_n_of_m, s.inStockCount, s.shopCount, p.radiusKm.toInt()),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item { SectionHeader(stringResource(R.string.sellers_nearby), stringResource(R.string.sellers_nearby_sub)) }
        if (p.offers.isEmpty()) {
            item { EmptyState(Icons.Filled.Inventory2, stringResource(R.string.no_sellers_nearby), stringResource(R.string.no_sellers_nearby_body)) }
        }
        items(p.offers, key = { it.listingId }) { o ->
            OfferRow(o, onShop = { onShop(o.shop.id) }, onReserve = { onReserve(o) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = Space.md))
        }
        if (p.otherSizes.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.other_sizes)) }
            item { AltRow(p.otherSizes, onProduct) }
        }
        if (p.substitutes.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.alternatives)) }
            item { AltRow(p.substitutes, onProduct) }
        }
        item {
            Spacer(Modifier.height(Space.xl))
            InfoBanner(stringResource(R.string.pickup_note), Icons.Filled.Info)
            StraightLineNote()
        }
    }
}

@Composable
private fun AltRow(items: List<AltProduct>, onProduct: (Long) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        items(items, key = { it.variant.id }) { a ->
            Surface(onClick = { onProduct(a.variant.id) }, shape = RoundedCornerShape(Radius.md), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.width(180.dp)) {
                Column(Modifier.padding(Space.md)) {
                    Text(a.variant.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(Space.xs))
                    Text(a.minPricePaise?.let { stringResource(R.string.from_price, rupees(it)) } ?: stringResource(R.string.not_sold_nearby),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    unitPriceText(a.bestUnitPrice)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
