package com.milega.app.ui.shop

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.AdjustBody
import com.milega.app.data.ListingDto
import com.milega.app.data.MilegaApi
import com.milega.app.ui.Routes
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.ErrorState
import com.milega.app.ui.components.LoadingState
import com.milega.app.ui.components.Pill
import com.milega.app.ui.theme.BrandColors
import com.milega.app.ui.theme.ShopkeeperTheme
import com.milega.app.util.errorText
import com.milega.app.util.qty
import com.milega.app.util.rupees
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** A stock change that failed to reach the server; retried with the same key so it applies once. */
data class PendingChange(val listingId: Long, val mode: String, val quantity: Double, val key: String, val name: String)

class InventoryViewModel(private val api: MilegaApi) : ViewModel() {
    var items by mutableStateOf<List<ListingDto>?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var filter by mutableStateOf("all")
    /** listingId -> number of taps still being saved (shown as a small sync indicator). */
    val saving = mutableStateMapOf<Long, Int>()
    private var loadJob: Job? = null

    fun load(debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounce) delay(300)
            loading = true
            error = null
            try {
                items = api.inventory(query.ifBlank { null }, filter).items
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                loading = false
            }
        }
    }

    /** One tap = one unit. Updates the screen immediately, then saves. */
    fun tap(item: ListingDto, add: Boolean, onFailed: (PendingChange, Throwable) -> Unit) {
        val change = PendingChange(item.id, if (add) "add" else "sell", 1.0, UUID.randomUUID().toString(), item.name)
        if (!add && item.onHand < 1.0) return
        items = items?.map {
            if (it.id == item.id) it.copy(onHand = it.onHand + if (add) 1.0 else -1.0, available = it.available + if (add) 1.0 else -1.0) else it
        }
        send(change, onFailed)
    }

    fun send(change: PendingChange, onFailed: (PendingChange, Throwable) -> Unit) {
        saving[change.listingId] = (saving[change.listingId] ?: 0) + 1
        viewModelScope.launch {
            try {
                val result = api.adjust(change.listingId, AdjustBody(change.mode, change.quantity), change.key)
                items = items?.map { if (it.id == result.listing.id) result.listing else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    val fresh = api.listing(change.listingId)
                    items = items?.map { if (it.id == fresh.id) fresh else it }
                } catch (_: Exception) {
                }
                onFailed(change, e)
            } finally {
                val left = (saving[change.listingId] ?: 1) - 1
                if (left <= 0) saving.remove(change.listingId) else saving[change.listingId] = left
            }
        }
    }
}

@Composable
fun InventoryScreen(container: AppContainer, nav: NavController, initialFilter: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val vm: InventoryViewModel = viewModel { InventoryViewModel(container.api).apply { filter = initialFilter } }
    LaunchedEffect(Unit) { vm.load() }

    fun onFailed(change: PendingChange, e: Throwable) {
        scope.launch {
            val result = snackbar.showSnackbar(
                message = context.getString(R.string.not_saved, change.name) + " " + errorText(context, e),
                actionLabel = context.getString(R.string.retry),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) vm.send(change, ::onFailed)
        }
    }

    ShopkeeperTheme {
        AppScaffold(
            title = stringResource(R.string.tile_inventory),
            onBack = { nav.popBackStack() },
            snackbar = snackbar,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.S_ADD) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.add_product)) },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it; vm.load(debounce = true) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.search_my_products)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { it.id }) { item ->
                            StockRow(
                                item, saving = (vm.saving[item.id] ?: 0) > 0,
                                onOpen = { nav.navigate(Routes.item(item.id)) },
                                onMinus = { vm.tap(item, add = false, ::onFailed) },
                                onPlus = { vm.tap(item, add = true, ::onFailed) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StockRow(item: ListingDto, saving: Boolean, onOpen: () -> Unit, onMinus: () -> Unit, onPlus: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(rupees(item.pricePaise) + if (item.sellUnit != "pc") "/${item.unitLabel}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        item.overcommitted -> Pill(stringResource(R.string.over_reserved), BrandColors.Bad)
                        item.outOfStock -> Pill(stringResource(R.string.out_of_stock), BrandColors.Bad)
                        item.lowStock -> Pill(stringResource(R.string.low_stock), BrandColors.Warn)
                    }
                    if (item.variantStatus == "provisional") Pill(stringResource(R.string.under_review), BrandColors.Neutral)
                }
                if (item.reserved > 0) {
                    Text(stringResource(R.string.reserved_count, qty(item.reserved)), style = MaterialTheme.typography.labelSmall,
                        color = BrandColors.Rose)
                }
            }
            FilledTonalIconButton(onClick = onMinus, enabled = item.onHand >= 1, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Remove, stringResource(R.string.sold_one))
            }
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(qty(item.onHand), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (saving) Icon(Icons.Filled.CloudUpload, stringResource(R.string.saving), Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.unitLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Add, stringResource(R.string.received_one))
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}
