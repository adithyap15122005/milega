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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.data.CandidateDto
import com.milega.app.data.ConfirmItem
import com.milega.app.data.ConfirmResp
import com.milega.app.data.JobDto
import com.milega.app.data.MatchDto
import com.milega.app.data.MilegaApi
import com.milega.app.data.NewProduct
import com.milega.app.ui.Routes
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.ConfidencePill
import com.milega.app.ui.components.EmptyState
import com.milega.app.ui.components.ErrorState
import com.milega.app.ui.components.LoadingState
import com.milega.app.ui.components.Pill
import com.milega.app.ui.components.PriceField
import com.milega.app.ui.components.PrimaryButton
import com.milega.app.ui.components.QuantityField
import com.milega.app.ui.components.isDecimalUnit
import com.milega.app.ui.components.paiseToText
import com.milega.app.ui.components.priceToPaise
import com.milega.app.ui.resetTo
import com.milega.app.ui.theme.BrandColors
import com.milega.app.ui.theme.ShopkeeperTheme
import com.milega.app.util.errorText
import com.milega.app.util.qty
import com.milega.app.util.qtyWithUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One line the shopkeeper is reviewing. Starts from an AI suggestion or is added by hand. */
class ReviewRow(val candidate: CandidateDto?) {
    var include by mutableStateOf(candidate == null || candidate.matches.isNotEmpty())
    var selected by mutableStateOf<MatchDto?>(candidate?.matches?.firstOrNull { it.id == candidate.suggestedVariantId })
    var newProduct by mutableStateOf<NewProduct?>(null)
    var newProductName by mutableStateOf<String?>(null)
    var quantity by mutableStateOf(candidate?.suggestedQuantity?.let { qty(it) } ?: "")
    var price by mutableStateOf(
        paiseToText(candidate?.invoiceUnitPricePaise ?: selected?.currentPricePaise)
    )

    val sellUnit get() = selected?.sellUnit ?: newProduct?.sellUnit ?: "pc"
    val unitLabel get() = selected?.unitLabel ?: unitLabelFor(sellUnit)
    val hasProduct get() = selected != null || newProduct != null

    fun choose(match: MatchDto) {
        selected = match
        newProduct = null
        newProductName = null
        if (price.isBlank()) price = paiseToText(candidate?.invoiceUnitPricePaise ?: match.currentPricePaise)
    }

    fun isValid(mode: String): Boolean {
        if (!include) return true
        val q = quantity.toDoubleOrNull() ?: return false
        return hasProduct && (if (mode == "shelf") q >= 0 else q > 0)
    }
}

class ReviewViewModel(private val api: MilegaApi, private val jobId: String) : ViewModel() {
    var job by mutableStateOf<JobDto?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var result by mutableStateOf<ConfirmResp?>(null)
        private set
    val rows = mutableStateListOf<ReviewRow>()

    fun load() = viewModelScope.launch {
        error = null
        try {
            val j = api.job(jobId)
            job = j
            if (rows.isEmpty()) rows.addAll(j.candidates.map { ReviewRow(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }

    fun save(onError: (Throwable) -> Unit) = viewModelScope.launch {
        val items = rows.filter { it.include }.map { r ->
            ConfirmItem(
                candidateId = r.candidate?.id,
                variantId = r.selected?.id,
                newProduct = if (r.selected == null) r.newProduct else null,
                quantity = r.quantity.toDouble(),
                pricePaise = priceToPaise(r.price),
            )
        }
        saving = true
        try {
            result = api.confirmJob(jobId, items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        } finally {
            saving = false
        }
    }
}

@Composable
fun ScanReviewScreen(container: AppContainer, nav: NavController, jobId: String) {
    val context = LocalContext.current
    val vm: ReviewViewModel = viewModel(key = jobId) { ReviewViewModel(container.api, jobId) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<ReviewRow?>(null) }
    LaunchedEffect(jobId) { if (vm.job == null) vm.load() }

    ShopkeeperTheme {
        AppScaffold(
            title = stringResource(R.string.check_items),
            onBack = { nav.popBackStack() },
            actions = {
                if (vm.job?.status == "needs_review" && vm.result == null) {
                    TextButton(onClick = {
                        container.api.let { api ->
                            kotlinx.coroutines.MainScope().launch {
                                try { api.discardJob(jobId) } catch (_: Exception) {}
                            }
                        }
                        nav.popBackStack()
                    }) { Text(stringResource(R.string.discard)) }
                }
            },
        ) { padding ->
            val job = vm.job
            val done = vm.result
            when {
                done != null -> SavedSummary(done, padding, onHome = { nav.resetTo(Routes.S_HOME) }, onScanMore = {
                    nav.popBackStack()
                })
                job == null && vm.error != null -> ErrorState(errorText(context, vm.error!!)) { vm.load() }
                job == null -> LoadingState()
                job.status == "failed" -> Column(Modifier.padding(padding)) {
                    EmptyState(
                        Icons.Filled.ReportProblem,
                        job.errorMessage ?: stringResource(R.string.scan_failed),
                        stringResource(R.string.scan_failed_body),
                        stringResource(R.string.add_manually),
                        { nav.navigate(Routes.S_ADD) },
                    )
                    TextButton(onClick = { nav.popBackStack() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.retake_photo))
                    }
                }
                job.status != "needs_review" -> EmptyState(Icons.Filled.CheckCircle, stringResource(R.string.error_scan_done), null,
                    stringResource(R.string.go_home), { nav.resetTo(Routes.S_HOME) })
                else -> Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { ReviewHeader(job) }
                        itemsIndexed(vm.rows) { index, row ->
                            RowCard(row, job.mode, onPick = { picking = row }, onRemove = { if (row.candidate == null) vm.rows.removeAt(index) })
                        }
                        item {
                            OutlinedButton(onClick = {
                                val row = ReviewRow(null)
                                vm.rows.add(row)
                                picking = row
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Add, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.add_item_manually))
                            }
                        }
                    }
                    val included = vm.rows.count { it.include }
                    val allValid = vm.rows.all { it.isValid(job.mode) } && included > 0
                    Surface(shadowElevation = 8.dp) {
                        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                            if (!allValid && included > 0) {
                                Text(stringResource(R.string.fill_missing), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                            }
                            PrimaryButton(
                                text = stringResource(if (job.mode == "shelf") R.string.update_counts else R.string.add_n_items, included),
                                enabled = allValid, loading = vm.saving, modifier = Modifier.fillMaxWidth(),
                                onClick = { saveError = null; vm.save { saveError = errorText(context, it) } },
                            )
                        }
                    }
                }
            }
        }
    }

    picking?.let { row ->
        AlertDialog(
            onDismissRequest = { picking = null },
            confirmButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.close)) } },
            title = { Text(stringResource(R.string.which_product)) },
            text = {
                CatalogPicker(container, Modifier.height(480.dp), initialQuery = row.candidate?.label?.take(40) ?: "") { picked ->
                    when (picked) {
                        is PickedProduct.Existing -> row.choose(
                            MatchDto(picked.variant.id, picked.variant.displayName, picked.variant.brand, picked.variant.category,
                                picked.variant.sizeLabel, picked.variant.sellUnit, picked.variant.status, 1.0,
                                unitLabel = unitLabelFor(picked.variant.sellUnit))
                        )
                        is PickedProduct.New -> {
                            row.selected = null
                            row.newProduct = picked.product
                            row.newProductName = picked.displayName
                        }
                    }
                    row.include = true
                    picking = null
                }
            },
        )
    }
}

@Composable
private fun ReviewHeader(job: JobDto) {
    Column {
        Text(
            stringResource(if (job.provider == "claude_vision") R.string.review_intro_ai else R.string.review_intro_ocr),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        job.supplierName?.let { Text(stringResource(R.string.supplier, it), style = MaterialTheme.typography.titleSmall) }
        if (job.errorCode == "photo_quality_warning") {
            Spacer(Modifier.height(8.dp))
            Pill(stringResource(R.string.photo_unclear_check), BrandColors.Warn)
        }
    }
}

@Composable
private fun RowCard(row: ReviewRow, mode: String, onPick: () -> Unit, onRemove: () -> Unit) {
    val c = row.candidate
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.include, onCheckedChange = { row.include = it })
                Column(Modifier.weight(1f)) {
                    Text(c?.label ?: stringResource(R.string.manual_item), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    if (c?.detectedText != null && c.detectedText != c.label) {
                        Text("“${c.detectedText.take(80)}”", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                if (c != null) ConfidencePill(c.confidence) else IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.remove))
                }
            }
            if (!row.include) return@Column

            // ---- which product?
            Spacer(Modifier.height(6.dp))
            val matches = c?.matches.orEmpty()
            if (row.selected == null && row.newProduct == null && matches.isNotEmpty()) {
                Text(stringResource(R.string.choose_correct_product), style = MaterialTheme.typography.labelLarge, color = BrandColors.Warn)
            }
            matches.forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { row.choose(m) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = row.selected?.id == m.id, onClick = { row.choose(m) })
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (m.currentOnHand != null) stringResource(R.string.you_have, qtyWithUnit(m.currentOnHand, m.unitLabel))
                            else stringResource(R.string.new_in_your_shop),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val manualChoice = row.selected?.takeIf { sel -> matches.none { it.id == sel.id } }
            if (manualChoice != null || row.newProductName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = true, onClick = {})
                    Text(manualChoice?.displayName ?: row.newProductName!!, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (row.newProduct != null) Pill(stringResource(R.string.new_word), BrandColors.Neutral)
                }
            }
            TextButton(onClick = onPick) {
                Text(stringResource(if (matches.isEmpty() && !row.hasProduct) R.string.find_product else R.string.something_else))
            }

            // ---- how many?
            if (row.hasProduct) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
                if (mode == "shelf") {
                    val seen = c?.visibleCount
                    Text(
                        listOfNotNull(
                            seen?.let { stringResource(R.string.seen_on_shelf, it) },
                            row.selected?.currentOnHand?.let { stringResource(R.string.your_records, qty(it)) },
                        ).joinToString(" · ").ifBlank { stringResource(R.string.shelf_count_help) },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (c?.quantitySource == "ai_count") {
                    Text(stringResource(R.string.ai_counted_check), style = MaterialTheme.typography.bodySmall, color = BrandColors.Warn)
                } else if (c?.quantitySource == "invoice") {
                    Text(stringResource(R.string.from_bill_check), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                QuantityField(
                    row.quantity, { row.quantity = it }, row.unitLabel, isDecimalUnit(row.sellUnit), Modifier.fillMaxWidth(),
                    stringResource(if (mode == "shelf") R.string.actual_count else R.string.how_many_received),
                )
                Spacer(Modifier.height(8.dp))
                PriceField(row.price, { row.price = it }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SavedSummary(result: ConfirmResp, padding: PaddingValues, onHome: () -> Unit, onScanMore: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = BrandColors.Good)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.stock_updated), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(12.dp))
        }
        itemsIndexed(result.applied) { _, a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.titleSmall)
                    if (a.provisional) Text(stringResource(R.string.new_product_review), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.now_have, qtyWithUnit(a.onHand, a.unitLabel)), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(stringResource(R.string.scan_more), onClick = onScanMore, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.go_home)) }
        }
    }
}
