package com.milega.app.ui.customer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.ReservationBody
import com.milega.app.data.ReservationDto
import com.milega.app.ui.components.PrimaryButton
import com.milega.app.ui.components.QuantityField
import com.milega.app.ui.components.isDecimalUnit
import com.milega.app.util.errorText
import com.milega.app.util.minutesUntil
import com.milega.app.util.qty
import com.milega.app.util.rupees
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ReserveDialog(container: AppContainer, target: ReserveTarget, onDismiss: () -> Unit, onViewReservations: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var quantity by remember { mutableStateOf("1") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<ReservationDto?>(null) }
    // Same key for retries of this one reservation: a flaky network can't create two.
    val idempotencyKey = remember { UUID.randomUUID().toString() }
    val q = quantity.toDoubleOrNull() ?: 0.0
    val valid = q > 0 && q <= target.available

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(if (done == null) stringResource(R.string.reserve_title) else stringResource(R.string.reserve_sent_title)) },
        text = {
            val reservation = done
            if (reservation == null) {
                Column {
                    Text(target.productName, style = MaterialTheme.typography.titleMedium)
                    Text(target.shopName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.price_each, rupees(target.pricePaise), target.unitLabel), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    QuantityField(quantity, { quantity = it; error = null }, target.unitLabel, isDecimalUnit(target.sellUnit),
                        Modifier.fillMaxWidth(), stringResource(R.string.quantity))
                    if (q > target.available) {
                        Text(stringResource(R.string.only_available, qty(target.available)), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (target.pricePaise != null && q > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.estimated_total, rupees(Math.round(target.pricePaise * q))),
                            style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.reserve_explainer), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                Column {
                    Text(stringResource(R.string.reservation_code), style = MaterialTheme.typography.labelLarge)
                    Text(reservation.code, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.reserve_pending_body, reservation.shop.name, (minutesUntil(reservation.expiresAt) ?: 0).coerceAtLeast(0)),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (done == null) {
                PrimaryButton(stringResource(R.string.reserve), enabled = valid, loading = loading, onClick = {
                    scope.launch {
                        loading = true
                        try {
                            done = container.api.reserve(ReservationBody(target.listingId, q), idempotencyKey)
                        } catch (e: Exception) {
                            error = errorText(context, e)
                        } finally {
                            loading = false
                        }
                    }
                })
            } else {
                TextButton(onClick = onViewReservations) { Text(stringResource(R.string.view_reservations)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text(stringResource(if (done == null) R.string.cancel else R.string.done))
            }
        },
    )
}
