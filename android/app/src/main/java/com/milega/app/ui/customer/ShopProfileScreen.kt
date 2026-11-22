package com.milega.app.ui.customer

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.RecentShop
import com.milega.app.data.ReportBody
import com.milega.app.data.SaveBody
import com.milega.app.data.ShopDto
import com.milega.app.data.ShopProduct
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.AvailabilityLine
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.InfoBanner
import com.milega.app.ui.components.LoadableContent
import com.milega.app.ui.components.MapPreview
import com.milega.app.ui.components.MerchantBadge
import com.milega.app.ui.components.OpenStatus
import com.milega.app.ui.components.Pill
import com.milega.app.ui.components.SectionHeader
import com.milega.app.ui.components.dial
import com.milega.app.ui.components.openDirections
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.theme.Milega
import com.milega.app.ui.theme.Space
import com.milega.app.util.errorText
import com.milega.app.util.rupees
import kotlinx.coroutines.launch

private val WEEKDAYS = listOf(R.string.mon, R.string.tue, R.string.wed, R.string.thu, R.string.fri, R.string.sat, R.string.sun)

@Composable
fun ShopProfileScreen(container: AppContainer, shopId: Long, onBack: () -> Unit, onReservations: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val session = container.session.current
    val shop = rememberLoadable(shopId) { container.api.shop(shopId, session.lat, session.lng) }
    val products = rememberLoadable(shopId) { container.api.shopProducts(shopId).products }
    var saved by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var reserveTarget by remember { mutableStateOf<ReserveTarget?>(null) }
    var reporting by remember { mutableStateOf(false) }

    LaunchedEffect(shopId) {
        if (session.loggedIn) saved = runCatching { container.api.saved().shops.any { it.id == shopId } }.getOrDefault(false)
    }
    LaunchedEffect(shop.data) {
        shop.data?.let { container.session.addRecentShop(RecentShop(it.id, it.name, it.locality, it.merchantState)) }
    }

    AppScaffold(
        title = shop.data?.name ?: "", onBack = onBack, snackbar = snackbar,
        actions = {
            IconButton(onClick = {
                scope.launch {
                    try {
                        if (saved) container.api.unsave(shopId = shopId) else container.api.save(SaveBody(shopId = shopId))
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
            IconButton(onClick = {
                val s = shop.data ?: return@IconButton
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_shop_text, s.name, s.locality, "milega://shop/${s.id}")), null))
            }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
        },
    ) { padding ->
        LoadableContent(shop) { s ->
            LazyColumn(contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = padding.calculateTopPadding(), bottom = Space.xxxl)) {
                item { ShopHeader(s, onReport = { reporting = true }) }
                item {
                    SectionHeader(stringResource(R.string.products_here), stringResource(R.string.products_here_sub))
                    OutlinedTextField(
                        value = filter, onValueChange = { filter = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        placeholder = { Text(stringResource(R.string.search_in_shop)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Space.sm))
                }
                val list = products.data
                if (list != null) {
                    val needle = filter.trim().lowercase()
                    val shown = if (needle.isEmpty()) list else list.filter {
                        it.name.lowercase().contains(needle) || (it.brand?.lowercase()?.contains(needle) == true)
                    }
                    if (shown.isEmpty()) item {
                        EmptyState(Icons.Filled.Inventory2,
                            stringResource(if (list.isEmpty()) R.string.shop_no_inventory_yet else R.string.no_products_here), null)
                    }
                    items(shown, key = { it.listingId }) { p ->
                        ProductRow(p, reservable = s.reservable) {
                            reserveTarget = ReserveTarget(p.listingId, p.name, s.name, p.pricePaise, p.unitLabel, p.sellUnit, p.availability.available)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                item {
                    Spacer(Modifier.height(Space.lg))
                    InfoBanner(stringResource(R.string.pickup_note), Icons.Filled.Info)
                }
            }
        }
    }
    reserveTarget?.let { target ->
        ReserveDialog(container, target, onDismiss = { reserveTarget = null; products.load(silent = true) },
            onViewReservations = { reserveTarget = null; onReservations() })
    }
    if (reporting) ReportDialog(container, "shop", shopId.toString(), onDone = { msg ->
        reporting = false
        if (msg != null) scope.launch { snackbar.showSnackbar(msg) }
    })
}

@Composable
private fun ShopHeader(s: ShopDto, onReport: () -> Unit) {
    val context = LocalContext.current
    Column {
        MapPreview(s.lat, s.lng, s.name, Modifier.fillMaxWidth().height(150.dp))
        Spacer(Modifier.height(Space.lg))
        Text(s.name, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Space.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MerchantBadge(s.merchantState)
            OpenStatus(s.openNow)
        }
        Spacer(Modifier.height(Space.sm))
        Text(listOf(s.addressLine.ifBlank { s.locality }, s.city).filter { it.isNotBlank() }.distinct().joinToString(", "),
            style = MaterialTheme.typography.bodyMedium)
        distanceLabel(s.distanceM)?.let {
            Text(stringResource(R.string.away_straight_line, it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val today = java.time.LocalDate.now().dayOfWeek.value - 1
        s.hours.firstOrNull { it.weekday == today }?.let { h ->
            Text(if (h.isClosed) stringResource(R.string.closed_today) else stringResource(R.string.hours_today, h.opens, h.closes),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.hours.any { it.isClosed }) {
            Text(stringResource(R.string.weekly_off, s.hours.filter { it.isClosed }.joinToString { context.getString(WEEKDAYS[it.weekday]) }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.categories.isNotEmpty()) {
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.categories.take(3).forEach { Pill(it.replace('_', ' ').replaceFirstChar { c -> c.uppercase() }, MaterialTheme.colorScheme.primary) }
            }
        }
        if (s.merchantState == "unverified_merchant") {
            Spacer(Modifier.height(Space.md))
            InfoBanner(stringResource(R.string.unverified_explainer), Icons.Filled.Info, tone = Milega.colors.warning)
        }
        if (s.merchantState == "demo") {
            Spacer(Modifier.height(Space.md))
            InfoBanner(stringResource(R.string.demo_explainer), Icons.Filled.Info, tone = Milega.colors.demo)
        }
        Spacer(Modifier.height(Space.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ShopActionButton(stringResource(R.string.directions), Icons.Filled.Directions,
                { openDirections(context, s.lat, s.lng) }, Modifier.weight(1f))
            s.phone?.let { phone ->
                ShopActionButton(stringResource(R.string.call), Icons.Filled.Call, { dial(context, phone) }, Modifier.weight(1f))
            }
            ShopActionButton(stringResource(R.string.report), Icons.Filled.Flag, onReport, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProductRow(p: ShopProduct, reservable: Boolean, onReserve: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            AvailabilityLine(p.availability)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text((p.pricePaise?.let { rupees(it) } ?: stringResource(R.string.price_unknown)) + if (p.sellUnit != "pc") " /${p.unitLabel}" else "",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (reservable && p.availability.status != "out_of_stock") {
                FilledTonalButton(onClick = onReserve, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.reserve))
                }
            }
        }
    }
}

/** Report wrong or suspicious information. Returns a confirmation message (or null if cancelled). */
@Composable
fun ReportDialog(container: AppContainer, targetType: String, targetId: String, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reasons = listOf(
        "wrong_location" to R.string.reason_wrong_location, "permanently_closed" to R.string.reason_closed,
        "wrong_info" to R.string.reason_wrong_info, "wrong_price" to R.string.reason_wrong_price,
        "fake_or_spam" to R.string.reason_fake, "duplicate" to R.string.reason_duplicate, "other" to R.string.reason_other,
    )
    var reason by remember { mutableStateOf("wrong_info") }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!sending) onDone(null) },
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column {
                reasons.forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(reason == key, role = Role.RadioButton) { reason = key },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reason == key, onClick = null)
                        Spacer(Modifier.width(Space.sm))
                        Text(stringResource(label))
                    }
                }
                OutlinedTextField(note, { note = it.take(500) }, label = { Text(stringResource(R.string.reason_optional)) },
                    modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !sending, onClick = {
                scope.launch {
                    sending = true
                    try {
                        container.api.report(ReportBody(targetType, targetId, reason, note.ifBlank { null }))
                        onDone(context.getString(R.string.report_thanks))
                    } catch (e: Exception) {
                        error = errorText(context, e)
                    } finally {
                        sending = false
                    }
                }
            }) { Text(stringResource(R.string.send)) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}
