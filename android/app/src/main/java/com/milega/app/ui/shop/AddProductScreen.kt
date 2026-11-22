package com.milega.app.ui.shop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.currentLanguage
import com.milega.app.data.AddListingBody
import com.milega.app.data.NewProduct
import com.milega.app.data.VariantDto
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.CategoryAvatar
import com.milega.app.ui.components.Pill
import com.milega.app.ui.components.PriceField
import com.milega.app.ui.components.PrimaryButton
import com.milega.app.ui.components.QuantityField
import com.milega.app.ui.components.isDecimalUnit
import com.milega.app.ui.components.priceToPaise
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.customer.localName
import com.milega.app.ui.theme.BrandColors
import com.milega.app.ui.theme.ShopkeeperTheme
import com.milega.app.util.errorText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val SELL_UNITS = listOf("pc" to R.string.unit_pcs, "kg" to R.string.unit_kg, "l" to R.string.unit_l, "m" to R.string.unit_m, "dozen" to R.string.unit_dozen)

fun unitLabelFor(sellUnit: String) = when (sellUnit) {
    "pc" -> "pcs"
    "l" -> "L"
    else -> sellUnit
}

/** What the shopkeeper picked: an existing catalogue product or a new one they typed. */
sealed interface PickedProduct {
    val displayName: String
    val sellUnit: String

    data class Existing(val variant: VariantDto) : PickedProduct {
        override val displayName get() = variant.displayName
        override val sellUnit get() = variant.sellUnit
    }

    data class New(val product: NewProduct) : PickedProduct {
        override val displayName get() = listOfNotNull(product.brand, product.name, product.sizeLabel).joinToString(" ")
        override val sellUnit get() = product.sellUnit
    }
}

@Composable
fun AddProductScreen(container: AppContainer, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<PickedProduct?>(null) }
    var quantity by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ShopkeeperTheme {
        AppScaffold(title = stringResource(R.string.add_product), onBack = { nav.popBackStack() }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                val chosen = picked
                if (chosen == null) {
                    CatalogPicker(container, Modifier.weight(1f)) { picked = it }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(chosen.displayName, style = MaterialTheme.typography.titleLarge)
                        if (chosen is PickedProduct.New) Pill(stringResource(R.string.new_product_review), BrandColors.Neutral)
                        TextButton(onClick = { picked = null }) { Text(stringResource(R.string.change_product)) }
                        Spacer(Modifier.height(12.dp))
                        QuantityField(quantity, { quantity = it }, unitLabelFor(chosen.sellUnit), isDecimalUnit(chosen.sellUnit),
                            Modifier.fillMaxWidth(), stringResource(R.string.quantity_in_stock))
                        Spacer(Modifier.height(12.dp))
                        PriceField(price, { price = it }, Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                        Spacer(Modifier.height(20.dp))
                        PrimaryButton(
                            stringResource(R.string.add_to_my_shop), loading = saving, modifier = Modifier.fillMaxWidth(),
                            enabled = quantity.toDoubleOrNull() != null,
                            onClick = {
                                scope.launch {
                                    saving = true
                                    error = null
                                    try {
                                        val body = when (chosen) {
                                            is PickedProduct.Existing -> AddListingBody(variantId = chosen.variant.id,
                                                quantity = quantity.toDouble(), pricePaise = priceToPaise(price))
                                            is PickedProduct.New -> AddListingBody(newProduct = chosen.product,
                                                quantity = quantity.toDouble(), pricePaise = priceToPaise(price))
                                        }
                                        container.api.addListing(body)
                                        nav.popBackStack()
                                    } catch (e: Exception) {
                                        error = errorText(context, e)
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Search the shared catalogue; fall back to creating a new (provisional) product. */
@Composable
fun CatalogPicker(container: AppContainer, modifier: Modifier = Modifier, initialQuery: String = "", onPick: (PickedProduct) -> Unit) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<VariantDto>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList(); return@LaunchedEffect
        }
        delay(300)
        results = try { container.api.catalogSearch(query.trim()).variants } catch (e: Exception) { emptyList() }
    }
    Column(modifier) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text(stringResource(R.string.search_catalog_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 520.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(results, key = { it.id }) { v ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(PickedProduct.Existing(v)) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryAvatar(v.category, 40)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(unitLabelFor(v.sellUnit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (v.status == "provisional") Pill(stringResource(R.string.under_review), BrandColors.Neutral)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Icon(Icons.Filled.AddBox, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cant_find_add_new))
        }
    }
    if (creating) {
        NewProductDialog(container, initialName = query, onDismiss = { creating = false }) {
            creating = false
            onPick(PickedProduct.New(it))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewProductDialog(container: AppContainer, initialName: String, onDismiss: () -> Unit, onCreate: (NewProduct) -> Unit) {
    val lang = currentLanguage()
    val categories = rememberLoadable { container.categories() }
    var name by remember { mutableStateOf(initialName) }
    var brand by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("other") }
    var unit by remember { mutableStateOf("pc") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_product)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it.take(160) }, label = { Text(stringResource(R.string.product_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(brand, { brand = it.take(80) }, label = { Text(stringResource(R.string.brand_optional)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(size, { size = it.take(40) }, label = { Text(stringResource(R.string.size_optional)) },
                    placeholder = { Text("500 g, 1 L, 25W…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.sold_by), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SELL_UNITS.forEach { (key, label) ->
                        FilterChip(selected = unit == key, onClick = { unit = key }, label = { Text(stringResource(label)) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.category), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.data.orEmpty().forEach { c ->
                        FilterChip(selected = category == c.slug, onClick = { category = c.slug }, label = { Text(c.localName(lang)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.trim().length >= 2, onClick = {
                onCreate(NewProduct(name.trim(), brand.trim().ifBlank { null }, size.trim().ifBlank { null }, category, unit))
            }) { Text(stringResource(R.string.next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
