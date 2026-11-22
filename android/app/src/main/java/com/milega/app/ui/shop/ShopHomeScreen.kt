package com.milega.app.ui.shop

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.EventDto
import com.milega.app.data.SummaryDto
import com.milega.app.ui.Routes
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.LoadableContent
import com.milega.app.ui.components.Pill
import com.milega.app.ui.components.SectionTitle
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.theme.BrandColors
import com.milega.app.ui.theme.ShopkeeperTheme
import com.milega.app.util.qty
import com.milega.app.util.qtyWithUnit
import com.milega.app.util.timeAgo
import kotlinx.coroutines.delay

@Composable
fun ShopHomeScreen(container: AppContainer, nav: NavController) {
    val summary = rememberLoadable { container.api.summary() }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        while (true) { // new reservations show up without pulling
            delay(30_000)
            summary.load(silent = true)
        }
    }

    ShopkeeperTheme {
        AppScaffold(
            title = summary.data?.shop?.name ?: container.session.current.shopName ?: stringResource(R.string.my_shop),
            actions = {
                IconButton(onClick = { summary.load() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
                IconButton(onClick = { nav.navigate(Routes.ACCOUNT) }) { Icon(Icons.Filled.AccountCircle, stringResource(R.string.nav_account)) }
            },
        ) { padding ->
            LoadableContent(summary) { s -> HomeContent(s, nav, padding) }
        }
    }
}

@Composable
private fun HomeContent(s: SummaryDto, nav: NavController, padding: PaddingValues) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp)) {
        if (s.shop.status == "pending") {
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.HourglassTop, null)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.pending_verification_banner), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(stringResource(R.string.tile_scan), stringResource(R.string.tile_scan_sub), Icons.Filled.PhotoCamera,
                    BrandColors.Green, Modifier.weight(1f)) { nav.navigate(Routes.scan()) }
                ActionTile(stringResource(R.string.tile_inventory), stringResource(R.string.products_count, s.totalProducts),
                    Icons.Filled.Inventory2, BrandColors.Indigo, Modifier.weight(1f)) { nav.navigate(Routes.inventory()) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(stringResource(R.string.tile_voice), stringResource(R.string.tile_voice_sub), Icons.Filled.Mic,
                    Color(0xFFB86E00), Modifier.weight(1f)) { nav.navigate(Routes.S_VOICE) }
                ActionTile(stringResource(R.string.tile_reservations),
                    if (s.reservations.pending > 0) stringResource(R.string.new_requests, s.reservations.pending)
                    else stringResource(R.string.to_collect, s.reservations.confirmed),
                    Icons.Filled.EventAvailable, BrandColors.Rose, Modifier.weight(1f), badge = s.reservations.pending,
                ) { nav.navigate(Routes.S_RESERVATIONS) }
            }
        }
        if (s.attention.isNotEmpty()) {
            item {
                SectionTitle(stringResource(R.string.needs_attention))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column {
                        s.attention.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth().clickable { nav.navigate(Routes.item(item.id)) }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(qtyWithUnit(item.onHand, item.unitLabel), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                when {
                                    item.overcommitted -> Pill(stringResource(R.string.over_reserved), BrandColors.Bad)
                                    item.outOfStock -> Pill(stringResource(R.string.out_of_stock), BrandColors.Bad)
                                    else -> Pill(stringResource(R.string.low_stock), BrandColors.Warn)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.recent_activity)) }
        if (s.recentActivity.isEmpty()) {
            item {
                Text(stringResource(R.string.no_activity), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column {
                        s.recentActivity.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ActivityRow(e) { nav.navigate(Routes.item(e.listingId)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(1.05f),
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.SpaceBetween) {
            Row {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.weight(1f))
                if (badge > 0) Badge(containerColor = Color.White, contentColor = color) {
                    Text(badge.toString(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(2.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun eventLabel(e: EventDto): String = stringResource(
    when (e.eventType) {
        "initial_stock" -> R.string.ev_initial
        "restock" -> R.string.ev_restock
        "sale" -> R.string.ev_sale
        "adjustment" -> R.string.ev_adjustment
        "out_of_stock" -> R.string.ev_out
        "reconciliation" -> R.string.ev_reconciliation
        "invoice_import" -> R.string.ev_invoice
        "reservation_hold" -> R.string.ev_hold
        "reservation_release" -> R.string.ev_release
        "reservation_collected" -> R.string.ev_collected
        else -> R.string.ev_other
    }
)

@Composable
fun sourceLabel(source: String): String? = when (source) {
    "voice" -> stringResource(R.string.src_voice)
    "photo_scan" -> stringResource(R.string.src_photo)
    "shelf_scan" -> stringResource(R.string.src_shelf)
    "invoice_scan" -> stringResource(R.string.src_invoice)
    else -> null
}

@Composable
private fun ActivityRow(e: EventDto, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(eventLabel(e), sourceLabel(e.source), timeAgo(context, e.createdAt)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val delta = if (e.deltaOnHand != 0.0) e.deltaOnHand else -e.deltaReserved
        Text(
            (if (delta > 0) "+" else "") + qty(delta),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (delta >= 0) BrandColors.Good else MaterialTheme.colorScheme.onSurface,
        )
    }
}
