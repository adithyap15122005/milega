package com.milega.app.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.local.PendingStockOp
import com.milega.app.data.sync.projectOnHand
import com.milega.app.data.EventDto
import com.milega.app.data.ListingDto
import com.milega.app.data.ListingPatch
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.LoadableContent
import com.milega.app.ui.components.PriceField
import com.milega.app.ui.components.QuantityField
import com.milega.app.ui.components.isDecimalUnit
import com.milega.app.ui.components.paiseToText
import com.milega.app.ui.components.priceToPaise
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.theme.BrandColors
import com.milega.app.ui.theme.ShopkeeperTheme
import com.milega.app.util.errorText
import com.milega.app.util.qty
import com.milega.app.util.qtyWithUnit
import com.milega.app.util.rupees
import com.milega.app.util.timeAgo
import kotlinx.coroutines.launch
import java.util.UUID

private enum class Dialog { Received, Sold, SetCount, OutOfStock, Edit, Remove }

@Composable
fun ItemDetailScreen(container: AppContainer, nav: NavController, listingId: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listing = rememberLoadable(listingId) { container.api.listing(listingId) }
    val events = rememberLoadable(listingId) { container.api.listingEvents(listingId).events }
    var dialog by remember { mutableStateOf<Dialog?>(null) }

    val ops by container.outbox.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val mine = ops.filter { it.listingId == listingId }
    val unsynced = mine.count { it.state != PendingStockOp.STATE_FAILED }
    var reviewing by remember { mutableStateOf<PendingStockOp?>(null) }
    // Once this product's queued changes reach the server, show the server's numbers again.
    LaunchedEffect(unsynced) {
        if (unsynced == 0) {
            listing.load(silent = true)
            events.load(silent = true)
        }
    }

    /** Saved on the phone first (offline-safe), then synced exactly once by the outbox. */
    @Suppress("UNUSED_PARAMETER")
    fun adjust(mode: String, quantity: Double, key: String) = scope.launch {
        val item = listing.data ?: return@launch
        val shopId = container.session.current.shopId ?: return@launch
        container.outbox.enqueue(shopId, item, mode, quantity)
        dialog = null
        snackbar.showSnackbar(context.getString(R.string.saved_on_phone))
    }

    ShopkeeperTheme {
        AppScaffold(title = listing.data?.name ?: "", onBack = { nav.popBackStack() }, snackbar = snackbar) { padding ->
            LoadableContent(listing) { serverItem ->
                val item = serverItem.copy(onHand = projectOnHand(serverItem.onHand, mine))
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp)) {
                    item {
                        OutboxBanner(mine, onReview = { reviewing = mine.firstOrNull { it.state == PendingStockOp.STATE_FAILED } })
                        StockHeader(item)
                    }
                    item {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BigAction(stringResource(R.string.received), Icons.Filled.Add, Modifier.weight(1f)) { dialog = Dialog.Received }
                            BigAction(stringResource(R.string.sold), Icons.Filled.Remove, Modifier.weight(1f)) { dialog = Dialog.Sold }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BigAction(stringResource(R.string.set_count), Icons.Filled.Tune, Modifier.weight(1f)) { dialog = Dialog.SetCount }
                            BigAction(stringResource(R.string.mark_out_of_stock), Icons.Filled.Block, Modifier.weight(1f)) { dialog = Dialog.OutOfStock }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { dialog = Dialog.Edit }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Edit, null); Spacer(Modifier.padding(3.dp)); Text(stringResource(R.string.edit_price_name))
                            }
                            OutlinedButton(onClick = { dialog = Dialog.Remove }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.remove_product))
                            }
                        }
                    }
                    item {
                        Text(stringResource(R.string.history), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                    }
                    items(events.data.orEmpty(), key = { it.id }) { e ->
                        HistoryRow(e)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                when (dialog) {
                    Dialog.Received, Dialog.Sold, Dialog.SetCount -> QuantityDialog(
                        item = item, kind = dialog!!, onDismiss = { dialog = null },
                        onConfirm = { q, key ->
                            adjust(when (dialog) { Dialog.Received -> "add"; Dialog.Sold -> "sell"; else -> "set" }, q, key)
                        },
                    )
                    Dialog.OutOfStock -> ConfirmDialog(
                        title = stringResource(R.string.mark_out_of_stock_q, item.name),
                        body = if (item.reserved > 0) stringResource(R.string.out_of_stock_reserved_body, qty(item.reserved)) else null,
                        confirm = stringResource(R.string.mark_out_of_stock),
                        onDismiss = { dialog = null },
                        onConfirm = { adjust("out_of_stock", 0.0, UUID.randomUUID().toString()) },
                    )
                    Dialog.Edit -> EditDialog(item, onDismiss = { dialog = null }) { price, name ->
                        scope.launch {
                            try {
                                listing.set(container.api.updateListing(listingId, ListingPatch(pricePaise = price, customName = name, clearPrice = price == null)))
                                dialog = null
                            } catch (e: Exception) {
                                snackbar.showSnackbar(errorText(context, e))
                            }
                        }
                    }
                    Dialog.Remove -> ConfirmDialog(
                        title = stringResource(R.string.remove_q, item.name),
                        body = stringResource(R.string.remove_body),
                        confirm = stringResource(R.string.remove_product),
                        onDismiss = { dialog = null },
                        onConfirm = {
                            scope.launch {
                                try {
                                    container.api.removeListing(listingId)
                                    nav.popBackStack()
                                } catch (e: Exception) {
                                    dialog = null
                                    snackbar.showSnackbar(errorText(context, e))
                                }
                            }
                        },
                    )
                    null -> {}
                }
            }
        }
    }
    reviewing?.let { op ->
        FailedChangeDialog(op, onRetry = { scope.launch { container.outbox.retry(op.id) }; reviewing = null },
            onDiscard = { scope.launch { container.outbox.discard(op.id) }; reviewing = null }, onDismiss = { reviewing = null })
    }
}

@Composable
private fun StockHeader(item: ListingDto) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp)) {
            if (item.name != item.catalogName && item.catalogName.isNotBlank()) {
                Text(item.catalogName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.in_stock_label), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(qtyWithUnit(item.onHand, item.unitLabel), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.selling_price), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(rupees(item.pricePaise), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            if (item.reserved > 0) {
                Text(stringResource(R.string.reserved_available, qty(item.reserved), qty(item.available)),
                    style = MaterialTheme.typography.bodyMedium, color = BrandColors.Rose)
            }
            Text(stringResource(R.string.customers_see_updated, timeAgo(context, item.stockConfirmedAt)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.overcommitted) {
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.padding(4.dp))
                        Text(stringResource(R.string.overcommitted_warning), color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun BigAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(56.dp)) {
        Icon(icon, null)
        Spacer(Modifier.padding(4.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun HistoryRow(e: EventDto) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(eventLabel(e), sourceLabel(e.source)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Text(timeAgo(context, e.createdAt) + (e.note?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            val delta = if (e.deltaOnHand != 0.0) e.deltaOnHand else -e.deltaReserved
            Text((if (delta > 0) "+" else "") + qty(delta), fontWeight = FontWeight.Bold,
                color = if (delta >= 0) BrandColors.Good else MaterialTheme.colorScheme.onSurface)
            Text("→ ${qty(e.onHandAfter)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuantityDialog(item: ListingDto, kind: Dialog, onDismiss: () -> Unit, onConfirm: (Double, String) -> Unit) {
    var value by remember { mutableStateOf(if (kind == Dialog.SetCount) qty(item.onHand) else "") }
    val key = remember { UUID.randomUUID().toString() } // retries of this dialog apply once
    val q = value.toDoubleOrNull()
    val valid = q != null && (if (kind == Dialog.SetCount) q >= 0 else q > 0) && !(kind == Dialog.Sold && q > item.onHand)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(when (kind) {
                Dialog.Received -> R.string.how_many_received
                Dialog.Sold -> R.string.how_many_sold
                else -> R.string.how_many_now
            }))
        },
        text = {
            Column {
                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                QuantityField(value, { value = it }, item.unitLabel, isDecimalUnit(item.sellUnit), Modifier.fillMaxWidth())
                if (kind == Dialog.Sold && q != null && q > item.onHand) {
                    Text(stringResource(R.string.only_available, qty(item.onHand)), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(q ?: 0.0, key) }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EditDialog(item: ListingDto, onDismiss: () -> Unit, onSave: (Long?, String?) -> Unit) {
    var price by remember { mutableStateOf(paiseToText(item.pricePaise)) }
    var name by remember { mutableStateOf(item.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_price_name)) },
        text = {
            Column {
                PriceField(price, { price = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it.take(200) }, label = { Text(stringResource(R.string.name_in_my_shop)) },
                    modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.name_correction_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(priceToPaise(price), name.trim().takeIf { it.isNotBlank() && it != item.catalogName })
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String?, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = if (body != null) {
            { Text(body) }
        } else null,
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
