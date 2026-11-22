package com.milega.app.ui.shop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.ListingDto
import com.milega.app.data.local.PendingStockOp
import com.milega.app.data.sync.projectOnHand
import com.milega.app.ui.Routes
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.ErrorState
import com.milega.app.ui.components.InfoBanner
import com.milega.app.ui.components.LoadingState
import com.milega.app.ui.components.Pill
import com.milega.app.ui.theme.BrandColors
import com.milega.app.ui.theme.Milega
import com.milega.app.ui.theme.Radius
import com.milega.app.ui.theme.ShopkeeperTheme
import com.milega.app.ui.theme.Space
import com.milega.app.util.errorText
import com.milega.app.util.qty
import com.milega.app.util.rupees
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class InventoryViewModel(private val container: AppContainer) : ViewModel() {
    var items by mutableStateOf<List<ListingDto>?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var filter by mutableStateOf("all")
    private var loadJob: Job? = null

    fun load(debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounce) delay(300)
            loading = true
            error = null
            try {
                items = container.api.inventory(query.ifBlank { null }, filter).items
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                loading = false
            }
        }
    }

    /** One tap = one unit. Saved on the phone first, so it survives no network and app restarts. */
    fun tap(item: ListingDto, add: Boolean, projected: Double) {
        if (!add && projected < 1.0) return
        val shopId = container.session.current.shopId ?: return
        viewModelScope.launch { container.outbox.enqueue(shopId, item, if (add) "add" else "sell", 1.0) }
    }

    fun retry(op: PendingStockOp) = viewModelScope.launch { container.outbox.retry(op.id) }
    fun discard(op: PendingStockOp) = viewModelScope.launch { container.outbox.discard(op.id) }
}

@Composable
fun InventoryScreen(container: AppContainer, nav: NavController, initialFilter: String) {
    val context = LocalContext.current
    val vm: InventoryViewModel = viewModel { InventoryViewModel(container).apply { filter = initialFilter } }
    val ops by container.outbox.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val unsynced = ops.count { it.state != PendingStockOp.STATE_FAILED }
    var reviewing by remember { mutableStateOf<PendingStockOp?>(null) }

    LaunchedEffect(Unit) { vm.load() }
    // When the outbox drains, refresh from the server (the source of truth).
    LaunchedEffect(unsynced) { if (unsynced == 0) vm.load(debounce = true) }

    ShopkeeperTheme {
        AppScaffold(
            title = stringResource(R.string.tile_inventory),
            onBack = { nav.popBackStack() },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.S_ADD) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.add_product)) },
                    containerColor = Milega.colors.brandSurface, contentColor = Milega.colors.onBrandSurface,
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutboxBanner(ops, onReview = { reviewing = ops.firstOrNull { it.state == PendingStockOp.STATE_FAILED } })
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it; vm.load(debounce = true) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.search_my_products)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    val filters = listOf("all" to R.string.filter_all, "low" to R.string.low_stock, "out" to R.string.out_of_stock)
                    items(filters) { (key, label) ->
                        FilterChip(selected = vm.filter == key, onClick = { vm.filter = key; vm.load() }, label = { Text(stringResource(label)) })
                    }
                }
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val list = vm.items
                when {
                    list == null && vm.error != null -> ErrorState(errorText(context, vm.error!!)) { vm.load() }
                    list == null -> LoadingState()
                    list.isEmpty() -> EmptyState(
                        Icons.Filled.Inventory2,
                        stringResource(if (vm.query.isBlank() && vm.filter == "all") R.string.inventory_empty else R.string.no_matches),
                        if (vm.query.isBlank() && vm.filter == "all") stringResource(R.string.inventory_empty_body) else null,
                        stringResource(R.string.tile_scan), { nav.navigate(Routes.scan()) },
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = Space.lg, end = Space.lg, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        items(list, key = { it.id }) { item ->
                            val mine = ops.filter { it.listingId == item.id }
                            val projected = projectOnHand(item.onHand, mine)
                            StockRow(
                                item, projected = projected,
                                pending = mine.count { it.state != PendingStockOp.STATE_FAILED },
                                failed = mine.firstOrNull { it.state == PendingStockOp.STATE_FAILED },
                                onOpen = { nav.navigate(Routes.item(item.id)) },
                                onMinus = { vm.tap(item, add = false, projected = projected) },
                                onPlus = { vm.tap(item, add = true, projected = projected) },
                                onReview = { reviewing = it },
                            )
                        }
                    }
                }
            }
        }
    }

    reviewing?.let { op ->
        FailedChangeDialog(op, onRetry = { vm.retry(op); reviewing = null }, onDiscard = { vm.discard(op); reviewing = null },
            onDismiss = { reviewing = null })
    }
}

/** "3 changes waiting for internet" / "1 change needs your attention". */
@Composable
fun OutboxBanner(ops: List<PendingStockOp>, onReview: () -> Unit) {
    val failed = ops.count { it.state == PendingStockOp.STATE_FAILED }
    val waiting = ops.size - failed
    if (failed > 0) {
        InfoBanner(stringResource(R.string.outbox_failed, failed), Icons.Filled.ErrorOutline,
            Modifier.padding(horizontal = Space.lg, vertical = Space.sm), tone = Milega.colors.danger,
            action = stringResource(R.string.review), onAction = onReview)
    } else if (waiting > 0) {
        InfoBanner(stringResource(R.string.outbox_waiting, waiting), Icons.Filled.CloudOff,
            Modifier.padding(horizontal = Space.lg, vertical = Space.sm), tone = Milega.colors.warning)
    }
}

@Composable
fun FailedChangeDialog(op: PendingStockOp, onRetry: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.ErrorOutline, null) },
        title = { Text(stringResource(R.string.change_not_saved_title)) },
        text = {
            Column {
                Text(stringResource(R.string.change_not_saved_body, op.listingName, describe(op)))
                Spacer(Modifier.padding(4.dp))
                Text(op.errorMessage ?: op.errorCode ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) } },
        dismissButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard)) } },
    )
}

@Composable
private fun describe(op: PendingStockOp): String = when (op.mode) {
    "add" -> stringResource(R.string.op_add, qty(op.quantity), op.unitLabel)
    "sell" -> stringResource(R.string.op_sell, qty(op.quantity), op.unitLabel)
    "set" -> stringResource(R.string.op_set, qty(op.quantity), op.unitLabel)
    else -> stringResource(R.string.op_out)
}

@Composable
private fun StockRow(
    item: ListingDto, projected: Double, pending: Int, failed: PendingStockOp?,
    onOpen: () -> Unit, onMinus: () -> Unit, onPlus: () -> Unit, onReview: (PendingStockOp) -> Unit,
) {
    val desc = stringResource(R.string.stock_row_desc, item.name, qty(projected), item.unitLabel)
    Surface(
        shape = RoundedCornerShape(Radius.md), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (failed != null) Milega.colors.danger else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).semantics { contentDescription = desc },
    ) {
        Row(Modifier.padding(start = 14.dp, end = Space.sm, top = Space.md, bottom = Space.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text((item.pricePaise?.let { rupees(it) } ?: stringResource(R.string.no_price)) + if (item.sellUnit != "pc") "/${item.unitLabel}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        item.overcommitted -> Pill(stringResource(R.string.over_reserved), BrandColors.Bad)
                        projected <= 0.0 -> Pill(stringResource(R.string.out_of_stock), BrandColors.Bad)
                        item.lowStock -> Pill(stringResource(R.string.low_stock), BrandColors.Warn)
                    }
                    if (item.variantStatus == "provisional") Pill(stringResource(R.string.under_review), BrandColors.Neutral)
                }
                if (item.reserved > 0) {
                    Text(stringResource(R.string.reserved_count, qty(item.reserved)), style = MaterialTheme.typography.labelSmall,
                        color = BrandColors.Rose)
                }
                if (failed != null) {
                    TextButton(onClick = { onReview(failed) }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.not_saved_tap), color = Milega.colors.danger, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            FilledTonalIconButton(onClick = onMinus, enabled = projected >= 1, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Remove, stringResource(R.string.sold_one))
            }
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(qty(projected), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pending > 0) Icon(Icons.Filled.CloudUpload, stringResource(R.string.saving), Modifier.size(12.dp),
                        tint = Milega.colors.warning)
                    Text(item.unitLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Add, stringResource(R.string.received_one))
            }
        }
    }
}
