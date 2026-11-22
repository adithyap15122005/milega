package com.milega.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.Blender
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CarRepair
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiFoodBeverage
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Plumbing
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Sanitizer
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsCricket
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.milega.app.R
import com.milega.app.data.Availability
import com.milega.app.data.UnitPrice
import com.milega.app.ui.theme.BrandColors
import com.milega.app.ui.theme.Milega
import com.milega.app.ui.theme.Radius
import com.milega.app.ui.theme.Space
import com.milega.app.util.errorText
import com.milega.app.util.qty
import com.milega.app.util.rupees
import com.milega.app.util.timeAgo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ------------------------------------------------------------- data loading

/** Minimal async state holder for screens: loading / error / data with reload(). */
class Loadable<T>(private val scope: CoroutineScope, private val block: suspend () -> T) {
    var data by mutableStateOf<T?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    fun load(silent: Boolean = false) {
        scope.launch {
            if (!silent) loading = true
            error = null
            try {
                data = block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent || data == null) error = e
            } finally {
                loading = false
            }
        }
    }

    fun set(value: T) {
        data = value
    }
}

@Composable
fun <T> rememberLoadable(vararg keys: Any?, block: suspend () -> T): Loadable<T> {
    val scope = rememberCoroutineScope()
    val loadable = remember(*keys) { Loadable(scope, block) }
    LaunchedEffect(loadable) { loadable.load() }
    return loadable
}

@Composable
fun <T> LoadableContent(state: Loadable<T>, loading: @Composable () -> Unit = { LoadingState() }, content: @Composable (T) -> Unit) {
    val data = state.data
    when {
        data != null -> content(data)
        state.error != null -> ErrorState(errorText(LocalContext.current, state.error!!)) { state.load() }
        else -> loading()
    }
}

// ---------------------------------------------------------------- scaffolds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    snackbar: SnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

// ------------------------------------------------------------------- states

@Composable
fun LoadingState(modifier: Modifier = Modifier.fillMaxSize()) {
    Column(modifier.padding(Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        repeat(4) { SkeletonCard() }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?) {
    EmptyState(icon = Icons.Filled.CloudOff, title = message, body = null,
        actionLabel = if (onRetry != null) stringResource(R.string.retry) else null, onAction = onRetry)
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Column(modifier.padding(horizontal = Space.xxxl, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(76.dp).clip(RoundedCornerShape(Radius.lg)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(Space.lg))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.lg))
            OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = Space.touch)) { Text(actionLabel) }
        }
    }
}

// ---------------------------------------------------------------- skeletons

@Composable
fun SkeletonBlock(modifier: Modifier, shape: RoundedCornerShape = RoundedCornerShape(Radius.xs)) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "skeletonAlpha",
    )
    Box(modifier.clip(shape).alpha(alpha).background(Milega.colors.skeleton))
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier.fillMaxWidth()) {
    val loading = stringResource(R.string.loading)
    Surface(
        modifier = modifier.semantics { contentDescription = loading },
        shape = RoundedCornerShape(Radius.md), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(Space.lg), verticalAlignment = Alignment.CenterVertically) {
            SkeletonBlock(Modifier.size(48.dp), RoundedCornerShape(Radius.sm))
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                SkeletonBlock(Modifier.fillMaxWidth(0.7f).height(14.dp))
                Spacer(Modifier.height(Space.sm))
                SkeletonBlock(Modifier.fillMaxWidth(0.45f).height(12.dp))
            }
        }
    }
}

@Composable
fun SkeletonTile(width: Dp = 220.dp, height: Dp = 140.dp) {
    SkeletonBlock(Modifier.width(width).height(height), RoundedCornerShape(Radius.lg))
}

// ------------------------------------------------------------------ buttons

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    accent: Boolean = false,
) {
    Button(
        onClick = onClick, enabled = enabled && !loading, modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(Radius.md),
        colors = if (accent) ButtonDefaults.buttonColors(containerColor = Milega.colors.accent, contentColor = Milega.colors.onAccent)
        else ButtonDefaults.buttonColors(),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(Space.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = Space.xxl, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = Space.xxl, bottom = Space.md), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = Space.touch)) { Text(action) }
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(color = color.copy(alpha = 0.12f), contentColor = color, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
fun DemoBadge() = MerchantBadge("demo")

@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
}

/**
 * The four trust states, always spelled out (never colour alone):
 * verified Milega merchant · awaiting verification · listed on Google Maps · sample shop.
 */
@Composable
fun MerchantBadge(state: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Milega.colors
    when (state) {
        "verified_merchant" -> Pill(stringResource(if (compact) R.string.badge_verified_short else R.string.badge_verified), c.success, modifier, Icons.Filled.Verified)
        "unverified_merchant" -> Pill(stringResource(R.string.badge_unverified), c.warning, modifier, Icons.Filled.HourglassTop)
        "google_discovered" -> Pill(stringResource(if (compact) R.string.badge_google_short else R.string.badge_google), c.google, modifier, Icons.Filled.Map)
        "demo" -> Pill(stringResource(R.string.demo_badge), c.demo, modifier, Icons.Filled.Science)
        else -> {}
    }
}

@Composable
fun OpenStatus(openNow: Boolean?) {
    when (openNow) {
        true -> Pill(stringResource(R.string.open_now), Milega.colors.success)
        false -> Pill(stringResource(R.string.closed_now), Milega.colors.neutral)
        null -> {}
    }
}

@Composable
fun InfoBanner(text: String, icon: ImageVector, modifier: Modifier = Modifier, tone: Color = Milega.colors.info,
               action: String? = null, onAction: (() -> Unit)? = null) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.md), color = tone.copy(alpha = 0.10f)) {
        Row(Modifier.padding(horizontal = Space.lg, vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tone, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.md))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun GoogleAttribution(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.google_attribution), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** The big tap-target search bar used as the hero on Home and at the top of Search. */
@Composable
fun HeroSearchBar(hint: String, onClick: () -> Unit, onVoice: (() -> Unit)?, modifier: Modifier = Modifier) {
    val desc = stringResource(R.string.search)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth().heightIn(min = 58.dp).semantics { contentDescription = desc },
    ) {
        Row(Modifier.padding(start = Space.lg, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Space.md))
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onVoice != null) IconButton(onClick = onVoice) {
                Icon(Icons.Filled.Mic, stringResource(R.string.voice_search), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ------------------------------------------------------------ availability

fun availabilityColor(status: String): Color = when (status) {
    "in_stock" -> BrandColors.Good
    "likely" -> BrandColors.Okay
    "unverified" -> BrandColors.Warn
    else -> BrandColors.Neutral
}

@Composable
fun availabilityLabel(a: Availability): String = when (a.status) {
    "in_stock" -> stringResource(R.string.avail_in_stock, qty(a.available))
    "likely" -> stringResource(R.string.avail_likely)
    "unverified" -> stringResource(R.string.avail_unverified)
    else -> stringResource(R.string.avail_out)
}

/** Availability plus *when it was last confirmed*: we never imply real-time stock. */
@Composable
fun AvailabilityLine(a: Availability, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(availabilityColor(a.status))
        Spacer(Modifier.width(6.dp))
        Text(availabilityLabel(a), style = MaterialTheme.typography.labelMedium, color = availabilityColor(a.status))
        if (a.status != "out_of_stock" && a.stockConfirmedAt != null) {
            Text(" · " + stringResource(R.string.updated_ago, timeAgo(context, a.stockConfirmedAt)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun unitPriceText(u: UnitPrice?): String? = u?.let {
    stringResource(R.string.unit_price, rupees(it.paise), when (it.per) {
        "kg" -> stringResource(R.string.per_kg)
        "L" -> stringResource(R.string.per_litre)
        else -> stringResource(R.string.per_piece)
    })
}

@Composable
fun ConfidencePill(level: String) {
    when (level) {
        "high" -> Pill(stringResource(R.string.confidence_high), BrandColors.Good)
        "medium" -> Pill(stringResource(R.string.confidence_medium), BrandColors.Warn)
        else -> Pill(stringResource(R.string.confidence_low), BrandColors.Bad)
    }
}

// ----------------------------------------------------------------- quantity

/**
 * A big +/- stepper with a typed field. [decimal] allows 0.5 steps (loose goods by kg / L).
 * Empty text means "not entered yet" — we never pre-fill a guess.
 */
@Composable
fun QuantityField(
    value: String,
    onValueChange: (String) -> Unit,
    unitLabel: String,
    decimal: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val step = if (decimal) 0.5 else 1.0
    fun parse() = value.toDoubleOrNull() ?: 0.0
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        FilledTonalIconButton(onClick = { onValueChange(qty((parse() - step).coerceAtLeast(0.0))) }, modifier = Modifier.size(Space.touch)) {
            Icon(Icons.Filled.Remove, stringResource(R.string.decrease))
        }
        OutlinedTextField(
            value = value,
            onValueChange = { text ->
                val filtered = text.filter { it.isDigit() || (decimal && it == '.') }.take(8)
                onValueChange(filtered)
            },
            label = { Text(label ?: unitLabel) },
            suffix = { Text(unitLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(onClick = { onValueChange(qty(parse() + step)) }, modifier = Modifier.size(Space.touch)) {
            Icon(Icons.Filled.Add, stringResource(R.string.increase))
        }
    }
}

@Composable
fun PriceField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: String = stringResource(R.string.selling_price)) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() || it == '.' }.take(9)) },
        label = { Text(label) },
        prefix = { Text("₹") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

fun priceToPaise(text: String): Long? = text.toDoubleOrNull()?.let { Math.round(it * 100) }

fun paiseToText(paise: Long?): String = when {
    paise == null -> ""
    paise % 100 == 0L -> (paise / 100).toString()
    else -> "%.2f".format(paise / 100.0)
}

fun isDecimalUnit(sellUnit: String) = sellUnit in setOf("kg", "l", "m", "dozen")

// ---------------------------------------------------------------- category

fun categoryIcon(slug: String?): ImageVector = when (slug) {
    "staples" -> Icons.Filled.ShoppingBasket
    "supermarket" -> Icons.Filled.LocalGroceryStore
    "snacks" -> Icons.Filled.Cookie
    "beverages" -> Icons.Filled.EmojiFoodBeverage
    "dairy" -> Icons.Filled.LocalDrink
    "bakery" -> Icons.Filled.BakeryDining
    "fruits_vegetables" -> Icons.Filled.Grass
    "personal_care" -> Icons.Filled.Spa
    "household" -> Icons.Filled.Home
    "stationery" -> Icons.Filled.Edit
    "books" -> Icons.AutoMirrored.Filled.MenuBook
    "electrical" -> Icons.Filled.Bolt
    "hardware" -> Icons.Filled.Build
    "electronics" -> Icons.Filled.Tv
    "mobile_accessories" -> Icons.Filled.Smartphone
    "computer_accessories" -> Icons.Filled.Computer
    "plumbing" -> Icons.Filled.Plumbing
    "paint" -> Icons.Filled.FormatPaint
    "home_appliances" -> Icons.Filled.Blender
    "furniture" -> Icons.Filled.Chair
    "kitchenware" -> Icons.Filled.Restaurant
    "clothing" -> Icons.Filled.Checkroom
    "footwear" -> Icons.Filled.Hiking
    "tailoring" -> Icons.Filled.ContentCut
    "jewellery" -> Icons.Filled.Diamond
    "cosmetics" -> Icons.Filled.Face
    "pharmacy" -> Icons.Filled.LocalPharmacy
    "medical_supplies" -> Icons.Filled.MedicalServices
    "personal" -> Icons.Filled.Sanitizer
    "florist" -> Icons.Filled.LocalFlorist
    "toys" -> Icons.Filled.Toys
    "sports" -> Icons.Filled.SportsCricket
    "bicycles" -> Icons.Filled.PedalBike
    "auto_parts" -> Icons.Filled.CarRepair
    "pet_supplies" -> Icons.Filled.Pets
    "gifts" -> Icons.Filled.CardGiftcard
    "home_decor" -> Icons.Filled.Weekend
    "wholesale" -> Icons.Filled.Warehouse
    "agri_supplies" -> Icons.Filled.Agriculture
    "other" -> Icons.Filled.Storefront
    else -> Icons.Filled.Inventory2
}

@Composable
fun CategoryAvatar(slug: String?, size: Int = 44, container: Color = MaterialTheme.colorScheme.primaryContainer,
                   content: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 3.2).dp)).background(container),
        contentAlignment = Alignment.Center,
    ) { Icon(categoryIcon(slug), null, tint = content, modifier = Modifier.size((size * 0.52).dp)) }
}

/** Large category tile for the home grid. */
@Composable
fun CategoryTile(slug: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(Radius.md)).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Space.sm, horizontal = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CategoryAvatar(slug, 56, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center)
    }
}
