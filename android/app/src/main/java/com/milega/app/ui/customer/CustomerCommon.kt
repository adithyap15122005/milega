package com.milega.app.ui.customer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milega.app.R
import com.milega.app.data.CategoryDto
import com.milega.app.data.ExternalPlace
import com.milega.app.data.ShopDto
import com.milega.app.ui.components.CategoryAvatar
import com.milega.app.ui.components.MerchantBadge
import com.milega.app.ui.components.OpenStatus
import com.milega.app.ui.components.Pill
import com.milega.app.ui.theme.Milega
import com.milega.app.ui.theme.Radius
import com.milega.app.ui.theme.Space
import com.milega.app.util.distance
import com.milega.app.util.timeAgo

fun CategoryDto.localName(lang: String) = when (lang) {
    "hi" -> nameHi
    "kn" -> nameKn
    else -> nameEn
}

/** "1.2 km away" — always labelled straight-line elsewhere on the screen. */
@Composable
fun distanceLabel(meters: Double?): String? = distance(LocalContext.current, meters)

/** Carousel card for a Milega merchant. */
@Composable
fun MerchantCard(shop: ShopDto, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        modifier = modifier.width(232.dp),
        shape = RoundedCornerShape(Radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryAvatar(shop.categories.firstOrNull() ?: "other", 44)
                Spacer(Modifier.weight(1f))
                OpenStatus(shop.openNow)
            }
            Spacer(Modifier.height(Space.md))
            Text(shop.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(shop.locality.ifBlank { null }, distanceLabel(shop.distanceM)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            Spacer(Modifier.height(Space.sm))
            MerchantBadge(shop.merchantState, compact = true)
            if ((shop.productsInStock ?: 0) > 0) {
                Spacer(Modifier.height(Space.sm))
                Text(
                    stringResource(R.string.products_in_stock_updated, shop.productsInStock ?: 0,
                        timeAgo(context, shop.inventoryUpdatedAt)),
                    style = MaterialTheme.typography.labelSmall, color = Milega.colors.success, maxLines = 2,
                )
            }
        }
    }
}

/** Full-width list row for a Milega merchant. */
@Composable
fun MerchantRow(shop: ShopDto, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(Modifier.padding(Space.md), verticalAlignment = Alignment.CenterVertically) {
            CategoryAvatar(shop.categories.firstOrNull() ?: "other", 48)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(shop.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(shop.locality.ifBlank { null }, distanceLabel(shop.distanceM)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(Space.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MerchantBadge(shop.merchantState, compact = true)
                    OpenStatus(shop.openNow)
                }
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A business listed on Google Maps (not a Milega partner: no stock, no reservations). */
@Composable
fun ExternalPlaceRow(place: ExternalPlace, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(Modifier.padding(Space.md), verticalAlignment = Alignment.CenterVertically) {
            CategoryAvatar(place.category ?: "other", 48, MaterialTheme.colorScheme.surfaceVariant, Milega.colors.google)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(place.typeLabel, distanceLabel(place.distanceM)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (place.address.isNotBlank()) Text(place.address, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.xs))
                MerchantBadge("google_discovered", compact = true)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RatingLine(rating: Double?, count: Int?) {
    if (rating == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, null, tint = Milega.colors.warning, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.google_rating, "%.1f".format(rating), count ?: 0), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun LocationPrompt(onChoose: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(Radius.lg),
    ) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(Space.sm))
                Text(stringResource(R.string.location_needed_title), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.location_needed_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(Space.md))
            OutlinedButton(onClick = onChoose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.choose_location)) }
        }
    }
}

@Composable
fun ExternalStatusNote(status: String) {
    val text = when (status) {
        "not_configured" -> R.string.google_off
        "quota_exceeded" -> R.string.google_quota
        "denied", "unavailable" -> R.string.google_unavailable
        "outside_service_area" -> R.string.google_outside
        else -> null
    } ?: return
    Pill(stringResource(text), Milega.colors.neutral)
}

/** Distance disclaimer shown under lists that show distances. */
@Composable
fun StraightLineNote(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.distance_note), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(vertical = Space.sm))
}

@Composable
fun ShopActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(Radius.md), color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.heightIn(min = 64.dp),
    ) {
        Column(Modifier.padding(vertical = Space.sm, horizontal = Space.xs), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
        }
    }
}

