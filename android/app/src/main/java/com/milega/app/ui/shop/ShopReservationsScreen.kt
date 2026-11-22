package com.milega.app.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.ReservationDto
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.LoadableContent
import com.milega.app.ui.components.Pill
import com.milega.app.ui.components.PrimaryButton
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.customer.reservationColor
import com.milega.app.ui.customer.reservationStatusLabel
import com.milega.app.ui.theme.ShopkeeperTheme
import com.milega.app.util.errorText
import com.milega.app.util.minutesUntil
import com.milega.app.util.qtyWithUnit
import com.milega.app.util.rupees
import com.milega.app.util.timeAgo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ShopReservationsScreen(container: AppContainer, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf("active") }
    val state = rememberLoadable(tab) { container.api.shopReservations(tab).reservations }
    var rejecting by remember { mutableStateOf<ReservationDto?>(null) }
    var busy by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(tab) {
        while (true) {
            delay(15_000)
            state.load(silent = true)
        }
    }

    fun act(r: ReservationDto, action: String, note: String? = null) = scope.launch {
        busy = r.id
        try {
            container.api.shopReservationAction(r.id, action, note)
            state.load(silent = true)
        } catch (e: Exception) {
            snackbar.showSnackbar(errorText(context, e))
            state.load(silent = true)
        } finally {
            busy = null
        }
    }

    ShopkeeperTheme {
        AppScaffold(title = stringResource(R.string.tile_reservations), onBack = { nav.popBackStack() }, snackbar = snackbar) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == "active", onClick = { tab = "active" }, label = { Text(stringResource(R.string.active)) })
                    FilterChip(selected = tab == "closed", onClick = { tab = "closed" }, label = { Text(stringResource(R.string.past)) })
                }
                LoadableContent(state) { list ->
                    if (list.isEmpty()) {
                        EmptyState(Icons.Filled.EventAvailable, stringResource(R.string.no_shop_reservations),
                            stringResource(R.string.no_shop_reservations_body))
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(list, key = { it.id }) { r ->
                                ShopReservationCard(
                                    r, busy = busy == r.id,
                                    onConfirm = { act(r, "confirm") },
                                    onCollected = { act(r, "collected") },
                                    onReject = { rejecting = r },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    rejecting?.let { r ->
        var reason by remember(r.id) { mutableStateOf("") }
        val quick = listOf(stringResource(R.string.reason_out_of_stock), stringResource(R.string.reason_closing), stringResource(R.string.reason_damaged))
        AlertDialog(
            onDismissRequest = { rejecting = null },
            title = { Text(stringResource(R.string.reject_q, r.code)) },
            text = {
                Column {
                    quick.forEach { q ->
                        FilterChip(selected = reason == q, onClick = { reason = q }, label = { Text(q) })
                    }
                    OutlinedTextField(reason, { reason = it.take(200) }, label = { Text(stringResource(R.string.reason_optional)) },
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    rejecting = null
                    act(r, "reject", reason.ifBlank { null })
                }) { Text(stringResource(R.string.reject)) }
            },
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ShopReservationCard(r: ReservationDto, busy: Boolean, onConfirm: () -> Unit, onCollected: () -> Unit, onReject: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.code, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text(
                        "${r.customer?.name ?: ""} ••${r.customer?.phoneLast4 ?: ""} · ${timeAgo(context, r.createdAt)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Pill(reservationStatusLabel(r.status), reservationColor(r.status))
            }
            Spacer(Modifier.height(10.dp))
            r.items.forEach { item ->
                Row {
                    Text("${qtyWithUnit(item.quantity, item.unitLabel)} × ${item.name}", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall)
                    Text(rupees(item.unitPricePaise?.let { Math.round(it * item.quantity) }), style = MaterialTheme.typography.bodyMedium)
                }
            }
            r.customerNote?.let {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.customer_says, it), style = MaterialTheme.typography.bodySmall)
            }
            if (r.status == "pending" || r.status == "confirmed") {
                val mins = (minutesUntil(r.expiresAt) ?: 0).coerceAtLeast(0)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(if (r.status == "pending") R.string.respond_within else R.string.held_for, mins),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.status == "pending") {
                        PrimaryButton(stringResource(R.string.confirm_keep_aside), onClick = onConfirm, loading = busy, modifier = Modifier.weight(1f))
                    } else {
                        PrimaryButton(stringResource(R.string.mark_collected), onClick = onCollected, loading = busy, modifier = Modifier.weight(1f))
                    }
                    OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.height(52.dp)) { Text(stringResource(R.string.reject)) }
                }
            }
        }
    }
}
