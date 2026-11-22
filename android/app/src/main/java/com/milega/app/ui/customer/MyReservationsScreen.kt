package com.milega.app.ui.customer

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.ReservationDto
import com.milega.app.ui.Routes
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.LoadableContent
import com.milega.app.ui.components.Pill
import com.milega.app.ui.components.openDirections
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.theme.BrandColors
import com.milega.app.util.errorText
import com.milega.app.util.minutesUntil
import com.milega.app.util.qtyWithUnit
import com.milega.app.util.rupees
import com.milega.app.util.timeAgo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun reservationColor(status: String): Color = when (status) {
    "pending" -> BrandColors.Warn
    "confirmed" -> BrandColors.Good
    "collected" -> BrandColors.Indigo
    else -> BrandColors.Neutral
}

@Composable
fun reservationStatusLabel(status: String): String = stringResource(
    when (status) {
        "pending" -> R.string.status_pending
        "confirmed" -> R.string.status_confirmed
        "collected" -> R.string.status_collected
        "rejected" -> R.string.status_rejected
        "cancelled" -> R.string.status_cancelled
        else -> R.string.status_expired
    }
)

@Composable
fun MyReservationsScreen(container: AppContainer, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberLoadable { container.api.myReservations().reservations }
    var toCancel by remember { mutableStateOf<ReservationDto?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        while (true) { // keep statuses fresh while the screen is open
            delay(20_000)
            state.load(silent = true)
        }
    }

    com.milega.app.ui.components.AppScaffold(title = stringResource(R.string.my_reservations), onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            LoadableContent(state) { list ->
                if (list.isEmpty()) {
                    EmptyState(Icons.Filled.Bookmark, stringResource(R.string.no_reservations), stringResource(R.string.no_reservations_body),
                        stringResource(R.string.start_searching), { nav.navigate(Routes.search()) })
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.id }) { r ->
                            ReservationCard(r, onCancel = { toCancel = r }, onDirections = { openDirections(context, r.shop.lat, r.shop.lng) })
                        }
                    }
                }
            }
        }
    }

    toCancel?.let { r ->
        AlertDialog(
            onDismissRequest = { toCancel = null },
            title = { Text(stringResource(R.string.cancel_reservation_q)) },
            text = { Text(stringResource(R.string.cancel_reservation_body, r.code)) },
            confirmButton = {
                TextButton(onClick = {
                    toCancel = null
                    scope.launch {
                        try {
                            container.api.cancelReservation(r.id)
                            state.load(silent = true)
                        } catch (e: Exception) {
                            message = errorText(context, e)
                        }
                    }
                }) { Text(stringResource(R.string.yes_cancel)) }
            },
            dismissButton = { TextButton(onClick = { toCancel = null }) { Text(stringResource(R.string.keep)) } },
        )
    }
}

@Composable
private fun ReservationCard(r: ReservationDto, onCancel: () -> Unit, onDirections: () -> Unit) {
    val context = LocalContext.current
    val active = r.status == "pending" || r.status == "confirmed"
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.shop.name, style = MaterialTheme.typography.titleMedium)
                    Text(timeAgo(context, r.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Pill(reservationStatusLabel(r.status), reservationColor(r.status))
            }
            Spacer(Modifier.height(10.dp))
            r.items.forEach { item ->
                Row {
                    Text("${qtyWithUnit(item.quantity, item.unitLabel)} × ${item.name}", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(rupees(item.unitPricePaise?.let { Math.round(it * item.quantity) }), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (active) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.show_code), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(r.code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    val mins = (minutesUntil(r.expiresAt) ?: 0).coerceAtLeast(0)
                    Text(
                        if (r.status == "pending") stringResource(R.string.waiting_for_shop, mins)
                        else stringResource(R.string.collect_within, mins),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (r.shopNote != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.shop_says, r.shopNote), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onDirections) {
                        Icon(Icons.Filled.Directions, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.directions))
                    }
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
            } else if (r.shopNote != null && r.status == "rejected") {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.shop_says, r.shopNote), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
