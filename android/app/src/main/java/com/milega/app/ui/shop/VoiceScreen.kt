package com.milega.app.ui.shop

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.currentLanguage
import com.milega.app.data.AdjustBody
import com.milega.app.data.MilegaApi
import com.milega.app.data.Proposal
import com.milega.app.data.VoiceMatch
import com.milega.app.data.VoiceResp
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.PrimaryButton
import com.milega.app.ui.theme.BrandColors
import com.milega.app.ui.theme.ShopkeeperTheme
import com.milega.app.util.errorText
import com.milega.app.util.qty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

class VoiceViewModel(private val api: MilegaApi) : ViewModel() {
    var language by mutableStateOf(currentLanguage())
    var typed by mutableStateOf("")
    var working by mutableStateOf(false)
        private set
    var result by mutableStateOf<VoiceResp?>(null)
        private set
    var proposal by mutableStateOf<Proposal?>(null)
        private set
    var proposalName by mutableStateOf("")
        private set
    var proposalUnit by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
    val done = mutableStateListOf<String>()
    private var key = UUID.randomUUID().toString()

    fun interpret(text: String, onError: (Throwable) -> Unit) = viewModelScope.launch {
        working = true
        message = null
        proposal = null
        try {
            val r = api.interpretVoice(text, language)
            result = r
            key = UUID.randomUUID().toString()
            r.proposal?.let { p ->
                val m = r.matches.firstOrNull { it.listingId == p.listingId }
                setProposal(p, m)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        } finally {
            working = false
        }
    }

    private fun setProposal(p: Proposal, m: VoiceMatch?) {
        proposal = p
        proposalName = m?.name ?: ""
        proposalUnit = m?.unitLabel ?: ""
    }

    /** Shopkeeper picked one of several possible products. */
    fun choose(match: VoiceMatch) {
        val r = result ?: return
        val action = r.action ?: return
        val q = r.quantity ?: 0.0
        val newOnHand = when (action) {
            "add" -> match.onHand + q
            "sell" -> match.onHand - q
            "set" -> q
            else -> 0.0
        }
        setProposal(Proposal(match.listingId, action, q, newOnHand), match)
    }

    fun confirm(onError: (Throwable) -> Unit) = viewModelScope.launch {
        val p = proposal ?: return@launch
        working = true
        try {
            val r = api.adjust(p.listingId, AdjustBody(p.mode, p.quantity, source = "voice"), key)
            done.add(0, "${r.listing.name}: ${qty(r.listing.onHand)} ${r.listing.unitLabel}")
            proposal = null
            result = null
            typed = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        } finally {
            working = false
        }
    }

    fun cancel() {
        proposal = null
        result = null
    }
}

private fun speechTag(lang: String) = when (lang) {
    "hi" -> "hi-IN"
    "kn" -> "kn-IN"
    else -> "en-IN"
}

@Composable
fun VoiceScreen(container: AppContainer, nav: NavController) {
    val context = LocalContext.current
    val vm: VoiceViewModel = viewModel { VoiceViewModel(container.api) }
    val onError: (Throwable) -> Unit = { vm.message = errorText(context, it) }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                vm.typed = text
                vm.interpret(text, onError)
            }
        }
    }

    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag(vm.language))
            .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.speak_now))
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.message = context.getString(R.string.speech_unavailable)
        }
    }

    ShopkeeperTheme {
        AppScaffold(title = stringResource(R.string.tile_voice), onBack = { nav.popBackStack() }) { padding ->
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("en" to "English", "hi" to "हिन्दी", "kn" to "ಕನ್ನಡ").forEach { (code, label) ->
                            FilterChip(selected = vm.language == code, onClick = { vm.language = code }, label = { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            onClick = { listen() }, enabled = !vm.working, shape = CircleShape, color = BrandColors.Green,
                            shadowElevation = 6.dp, modifier = Modifier.size(120.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                if (vm.working) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                                else Icon(Icons.Filled.Mic, stringResource(R.string.tap_to_speak), tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(56.dp))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.tap_to_speak), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.try_saying), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val examples = when (vm.language) {
                        "hi" -> listOf("मैगी के बीस पैकेट आए", "पांच टाटा नमक बिक गए", "पारले जी खत्म")
                        "kn" -> listOf("ಹತ್ತು ಮ್ಯಾಗಿ ಸೇರಿಸಿ", "ಐದು ಟಾಟಾ ಉಪ್ಪು ಮಾರಾಟ", "ಪಾರ್ಲೆ ಜಿ ಖಾಲಿ")
                        else -> listOf("Add 20 packets of Maggi", "Five Tata Salt sold", "Make Parle-G out of stock")
                    }
                    examples.forEach { ex ->
                        Text("“$ex”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier
                            .clickable { vm.typed = ex; vm.interpret(ex, onError) }.padding(vertical = 4.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = vm.typed, onValueChange = { vm.typed = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.or_type_it)) },
                        trailingIcon = {
                            IconButton(onClick = { if (vm.typed.isNotBlank()) vm.interpret(vm.typed, onError) }) {
                                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (vm.typed.isNotBlank()) vm.interpret(vm.typed, onError) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    vm.message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
                item {
                    val r = vm.result
                    val p = vm.proposal
                    if (r != null) {
                        Spacer(Modifier.height(16.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.heard, r.transcript), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                if (p != null) {
                                    Text(proposalText(p, vm.proposalName, vm.proposalUnit), style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text(stringResource(R.string.stock_will_be, qty(p.newOnHand), vm.proposalUnit),
                                        style = MaterialTheme.typography.bodyMedium)
                                    if (p.newOnHand < 0) {
                                        Text(stringResource(R.string.not_enough_stock_voice), color = MaterialTheme.colorScheme.error)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PrimaryButton(stringResource(R.string.confirm), onClick = { vm.confirm(onError) },
                                            enabled = p.newOnHand >= 0, loading = vm.working, modifier = Modifier.weight(1f))
                                        OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.height(52.dp)) { Text(stringResource(R.string.cancel)) }
                                    }
                                } else if (r.matches.isNotEmpty() && r.action != null && (r.quantity != null || r.action == "out_of_stock")) {
                                    Text(stringResource(R.string.which_one), style = MaterialTheme.typography.titleMedium)
                                    r.matches.forEach { m ->
                                        OutlinedButton(onClick = { vm.choose(m) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                            Text("${m.name} (${qty(m.onHand)} ${m.unitLabel})")
                                        }
                                    }
                                } else {
                                    Text(problemText(r), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
                if (vm.done.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(R.string.updated_just_now), style = MaterialTheme.typography.titleMedium)
                        vm.done.forEach { line ->
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, null, tint = BrandColors.Good, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(line)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun proposalText(p: Proposal, name: String, unit: String): String = when (p.mode) {
    "add" -> stringResource(R.string.proposal_add, qty(p.quantity), unit, name)
    "sell" -> stringResource(R.string.proposal_sell, qty(p.quantity), unit, name)
    "set" -> stringResource(R.string.proposal_set, name, qty(p.quantity), unit)
    else -> stringResource(R.string.proposal_out, name)
}

@Composable
private fun problemText(r: VoiceResp): String = when {
    "no_action" in r.problems -> stringResource(R.string.voice_no_action)
    "no_quantity" in r.problems -> stringResource(R.string.voice_no_quantity)
    "product_not_found" in r.problems || "no_product" in r.problems -> stringResource(R.string.voice_no_product)
    else -> stringResource(R.string.voice_not_sure)
}
