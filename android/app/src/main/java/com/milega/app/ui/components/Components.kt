package com.milega.app.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiFoodBeverage
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sanitizer
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milega.app.R
import com.milega.app.data.Availability
import com.milega.app.ui.theme.BrandColors
import com.milega.app.util.errorText
import com.milega.app.util.qty
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
fun <T> LoadableContent(state: Loadable<T>, content: @Composable (T) -> Unit) {
    val data = state.data
    when {
        data != null -> content(data)
        state.error != null -> ErrorState(errorText(LocalContext.current, state.error!!)) { state.load() }
        else -> LoadingState()
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
                title = { Text(title, maxLines = 1) },
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
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
    Column(modifier.padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

// ------------------------------------------------------------------ buttons

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, icon: ImageVector? = null) {
    Button(onClick = onClick, enabled = enabled && !loading, modifier = modifier.heightIn(min = 52.dp)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.14f), contentColor = color, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
fun DemoBadge() = Pill(stringResource(R.string.demo_badge), BrandColors.Indigo)

@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = { onValueChange(qty((parse() - step).coerceAtLeast(0.0))) }, modifier = Modifier.size(48.dp)) {
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
        FilledTonalIconButton(onClick = { onValueChange(qty(parse() + step)) }, modifier = Modifier.size(48.dp)) {
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

fun categoryIcon(slug: String): ImageVector = when (slug) {
    "staples" -> Icons.Filled.ShoppingBasket
    "snacks" -> Icons.Filled.Cookie
    "beverages" -> Icons.Filled.EmojiFoodBeverage
    "dairy" -> Icons.Filled.LocalDrink
    "fruits_vegetables" -> Icons.Filled.Grass
    "personal_care" -> Icons.Filled.Spa
    "household" -> Icons.Filled.Home
    "stationery" -> Icons.Filled.Edit
    "electrical" -> Icons.Filled.Bolt
    "hardware" -> Icons.Filled.Build
    "mobile_accessories" -> Icons.Filled.Smartphone
    "personal" -> Icons.Filled.Sanitizer
    else -> Icons.Filled.Inventory2
}

@Composable
fun CategoryAvatar(slug: String, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(categoryIcon(slug), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size((size * 0.55).dp)) }
}
