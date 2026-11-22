package com.milega.app.ui.shop

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.EventDto
import com.milega.app.data.SummaryDto
import com.milega.app.data.local.PendingStockOp
import com.milega.app.ui.Routes
import com.milega.app.ui.components.ErrorState
import com.milega.app.ui.components.InfoBanner
import com.milega.app.ui.components.MerchantBadge
import com.milega.app.ui.components.Pill
import com.milega.app.ui.components.SectionHeader
import com.milega.app.ui.components.SkeletonCard
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.theme.BrandColors
import com.milega.app.ui.theme.Milega
import com.milega.app.ui.theme.Radius
import com.milega.app.ui.theme.ShopkeeperTheme
import com.milega.app.ui.theme.Space
import com.milega.app.util.errorText
import com.milega.app.util.qty
import com.milega.app.util.qtyWithUnit
import com.milega.app.util.timeAgo
import kotlinx.coroutines.delay

@Composable
fun ShopHomeScreen(container: AppContainer, nav: NavController) {
    val context = LocalContext.current
    val summary = rememberLoadable { container.api.summary() }
    val ops by container.outbox.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        while (true) { // new reservations show up without pulling (push also arrives when configured)
            delay(30_000)
            summary.load(silent = true)
        }
    }
    ShopkeeperTheme {
        val s = summary.data
        when {
            s != null -> SellerDashboard(
                s, ops,
                onScan = { nav.navigate(Routes.scan()) },
                onInventory = { nav.navigate(Routes.inventory(it)) },
                onVoice = { nav.navigate(Routes.S_VOICE) },
                onReservations = { nav.navigate(Routes.S_RESERVATIONS) },
                onItem = { nav.navigate(Routes.item(it)) },
                onAccount = { nav.navigate(Routes.ACCOUNT) },
                onRefresh = { summary.load() },
            )
            summary.error != null -> Box(Modifier.statusBarsPadding()) { ErrorState(errorText(context, summary.error!!)) { summary.load() } }
            else -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                repeat(5) { SkeletonCard() }
            }
        }
    }
}

/** Stateless dashboard (also used for screenshot tests). */
@Composable
fun SellerDashboard(
    s: SummaryDto,
    ops: List<PendingStockOp>,
    onScan: () -> Unit,
    onInventory: (String) -> Unit,
    onVoice: () -> Unit,
    onReservations: () -> Unit,
    onItem: (Long) -> Unit,
    onAccount: () -> Unit,
    onRefresh: () -> Unit,
) {
    val state = when (s.shop.status) {
        "active" -> "verified_merchant"
        else -> "unverified_merchant"
    }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = Space.xxxl)) {
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = Radius.xl, bottomEnd = Radius.xl))
                    .background(Milega.colors.brandSurface).statusBarsPadding()
                    .padding(start = Space.gutter, end = Space.sm, top = Space.sm, bottom = Space.xxl),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.my_shop), style = MaterialTheme.typography.labelLarge,
                        color = Milega.colors.onBrandSurface.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh), tint = Milega.colors.onBrandSurface) }
                    IconButton(onClick = onAccount) { Icon(Icons.Filled.AccountCircle, stringResource(R.string.nav_account), tint = Milega.colors.onBrandSurface) }
                }
                Text(s.shop.name, style = MaterialTheme.typography.headlineLarge, color = Milega.colors.onBrandSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.sm))
                Surface(shape = RoundedCornerShape(50), color = Color.White) { MerchantBadge(state) }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Space.gutter)) {
                Spacer(Modifier.height(Space.lg))
                if (s.shop.status == "pending") {
                    InfoBanner(stringResource(R.string.pending_verification_banner), Icons.Filled.HourglassTop, tone = Milega.colors.warning)
                    Spacer(Modifier.height(Space.sm))
                }
                OutboxBanner(ops, onReview = { onInventory("all") })
                if (s.reservations.pending > 0) {
                    Surface(onClick = onReservations, shape = RoundedCornerShape(Radius.md), color = Milega.colors.accent,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Space.sm)) {
                        Row(Modifier.padding(Space.lg), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.NotificationsActive, null, tint = Milega.colors.onAccent)
                            Spacer(Modifier.width(Space.md))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.new_requests, s.reservations.pending), style = MaterialTheme.typography.titleMedium,
                                    color = Milega.colors.onAccent)
                                Text(stringResource(R.string.respond_quickly), style = MaterialTheme.typography.bodySmall,
                                    color = Milega.colors.onAccent)
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    ActionTile(stringResource(R.string.tile_scan), stringResource(R.string.tile_scan_sub), Icons.Filled.PhotoCamera,
                        BrandColors.Green, Modifier.weight(1f), onClick = onScan)
                    ActionTile(stringResource(R.string.tile_inventory), stringResource(R.string.products_count, s.totalProducts),
                        Icons.Filled.Inventory2, Color(0xFF2E4B9A), Modifier.weight(1f)) { onInventory("all") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    ActionTile(stringResource(R.string.tile_voice), stringResource(R.string.tile_voice_sub), Icons.Filled.Mic,
                        Color(0xFFB0521F), Modifier.weight(1f), onClick = onVoice)
                    ActionTile(stringResource(R.string.tile_reservations),
                        if (s.reservations.pending > 0) stringResource(R.string.new_requests, s.reservations.pending)
                        else stringResource(R.string.to_collect, s.reservations.confirmed),
                        Icons.Filled.EventAvailable, BrandColors.Rose, Modifier.weight(1f), badge = s.reservations.pending,
                        onClick = onReservations)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Space.gutter)) {
                SectionHeader(stringResource(R.string.inventory_health))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    StatTile(stringResource(R.string.stat_in_stock), (s.totalProducts - s.outOfStock).coerceAtLeast(0), Milega.colors.success,
                        Modifier.weight(1f)) { onInventory("all") }
                    StatTile(stringResource(R.string.low_stock), s.lowStock, Milega.colors.warning, Modifier.weight(1f)) { onInventory("low") }
                    StatTile(stringResource(R.string.out_of_stock), s.outOfStock, Milega.colors.danger, Modifier.weight(1f)) { onInventory("out") }
                }
            }
        }
        if (s.attention.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = Space.gutter)) {
                    SectionHeader(stringResource(R.string.needs_attention))
                    Surface(shape = RoundedCornerShape(Radius.md), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column {
                            s.attention.forEachIndexed { i, item ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.fillMaxWidth().clickable { onItem(item.id) }.heightIn(min = 56.dp).padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
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
        }
        item { Box(Modifier.padding(horizontal = Space.gutter)) { SectionHeader(stringResource(R.string.recent_activity)) } }
        if (s.recentActivity.isEmpty()) {
            item {
                Text(stringResource(R.string.no_activity), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.gutter))
            }
        } else {
            item {
                Surface(shape = RoundedCornerShape(Radius.md), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.padding(horizontal = Space.gutter)) {
                    Column {
                        s.recentActivity.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ActivityRow(e) { onItem(e.listingId) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(
    title: String, subtitle: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, badge: Int = 0, onClick: () -> Unit,
) {
    val desc = "$title. $subtitle"
    Surface(onClick = onClick, modifier = modifier.aspectRatio(1.15f).semantics { contentDescription = desc },
        color = color, shape = RoundedCornerShape(Radius.lg)) {
        Column(Modifier.padding(Space.lg).fillMaxWidth(), verticalArrangement = Arrangement.SpaceBetween) {
            Row {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.weight(1f))
                if (badge > 0) Badge(containerColor = Color.White, contentColor = color) {
                    Text(badge.toString(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(2.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(Radius.md), color = color.copy(alpha = 0.10f), modifier = modifier.heightIn(min = 76.dp)) {
        Column(Modifier.padding(Space.md)) {
            Text("$value", style = MaterialTheme.typography.headlineMedium, color = color, fontWeight = FontWeight.ExtraBold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
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
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(eventLabel(e), sourceLabel(e.source), timeAgo(context, e.createdAt)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val delta = if (e.deltaOnHand != 0.0) e.deltaOnHand else -e.deltaReserved
        Text((if (delta > 0) "+" else "") + qty(delta), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (delta >= 0) BrandColors.Good else MaterialTheme.colorScheme.onSurface)
    }
}
