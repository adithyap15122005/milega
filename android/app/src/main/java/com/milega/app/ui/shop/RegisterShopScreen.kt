package com.milega.app.ui.shop

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.currentLanguage
import com.milega.app.data.HoursDto
import com.milega.app.data.ShopBody
import com.milega.app.ui.components.AppScaffold
import com.milega.app.ui.components.MapPin
import com.milega.app.ui.components.OsmMap
import com.milega.app.ui.components.PrimaryButton
import com.milega.app.ui.components.SectionTitle
import com.milega.app.ui.components.rememberLoadable
import com.milega.app.ui.customer.localName
import com.milega.app.ui.theme.ShopkeeperTheme
import com.milega.app.util.Locations
import com.milega.app.util.Place
import com.milega.app.util.errorText
import kotlinx.coroutines.launch

private val HOUR_PRESETS = listOf("08:00" to "21:00", "09:00" to "22:00", "07:00" to "23:00", "10:00" to "20:00")
private val DAY_LABELS = listOf(R.string.mon, R.string.tue, R.string.wed, R.string.thu, R.string.fri, R.string.sat, R.string.sun)

/** Minimal onboarding: name, location, what you sell, hours. Everything else is optional later. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegisterShopScreen(container: AppContainer, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = currentLanguage()
    val categories = rememberLoadable { container.categories() }

    var name by rememberSaveable { mutableStateOf("") }
    var place by remember { mutableStateOf<Place?>(null) }
    var source by remember { mutableStateOf("gps") }
    var addressLine by rememberSaveable { mutableStateOf("") }
    var locality by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var pincode by rememberSaveable { mutableStateOf("") }
    var addressQuery by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    var opens by rememberSaveable { mutableStateOf("09:00") }
    var closes by rememberSaveable { mutableStateOf("21:00") }
    val closedDays = remember { mutableStateListOf<Int>() }
    var locating by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun apply(p: Place, how: String) {
        place = p
        source = how
        addressLine = p.addressLine
        locality = p.locality
        city = p.city
        pincode = p.pincode.filter(Char::isDigit).take(6)
    }

    fun locate() = scope.launch {
        locating = true
        val p = Locations.current(context)
        locating = false
        if (p != null) apply(p, "gps") else error = context.getString(R.string.location_failed)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locate() else error = context.getString(R.string.location_denied)
    }
    val timeRegex = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    val valid = name.trim().length >= 2 && place != null && chosen.isNotEmpty() &&
        timeRegex.matches(opens) && timeRegex.matches(closes) && (pincode.isEmpty() || pincode.length == 6)

    ShopkeeperTheme {
        AppScaffold(title = stringResource(R.string.register_shop), onBack = onBack) { padding ->
            Column(
                Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.register_intro), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(name, { name = it.take(120) }, label = { Text(stringResource(R.string.shop_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())

                SectionTitle(stringResource(R.string.shop_location))
                Text(stringResource(R.string.stand_in_shop), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    stringResource(R.string.use_current_location), icon = Icons.Filled.MyLocation, loading = locating,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) locate() else permission.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(addressQuery, { addressQuery = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.or_search_address)) }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.padding(4.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            val found = Locations.search(context, addressQuery).firstOrNull()
                            if (found != null) apply(found, "manual") else error = context.getString(R.string.place_not_found)
                        }
                    }) { Text(stringResource(R.string.find)) }
                }
                place?.let { p ->
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        OsmMap(p.lat, p.lng, listOf(MapPin(0, p.lat, p.lng, name.ifBlank { "Shop" })),
                            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)),
                            zoom = 17.0, interactive = false, youAreHere = false)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(addressLine, { addressLine = it.take(255) }, label = { Text(stringResource(R.string.address)) },
                        modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(locality, { locality = it.take(120) }, label = { Text(stringResource(R.string.area)) },
                            singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(pincode, { pincode = it.filter(Char::isDigit).take(6) }, label = { Text(stringResource(R.string.pincode)) },
                            singleLine = true, modifier = Modifier.weight(0.7f))
                    }
                    OutlinedTextField(city, { city = it.take(80) }, label = { Text(stringResource(R.string.city)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                SectionTitle(stringResource(R.string.what_do_you_sell))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.data.orEmpty().forEach { c ->
                        FilterChip(
                            selected = c.slug in chosen,
                            onClick = { if (c.slug in chosen) chosen.remove(c.slug) else chosen.add(c.slug) },
                            label = { Text(c.localName(lang)) },
                        )
                    }
                }

                SectionTitle(stringResource(R.string.opening_hours))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HOUR_PRESETS.forEach { (o, c) ->
                        FilterChip(selected = opens == o && closes == c, onClick = { opens = o; closes = c }, label = { Text("$o – $c") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(opens, { opens = it.take(5) }, label = { Text(stringResource(R.string.opens)) },
                        singleLine = true, modifier = Modifier.weight(1f), isError = !timeRegex.matches(opens))
                    OutlinedTextField(closes, { closes = it.take(5) }, label = { Text(stringResource(R.string.closes)) },
                        singleLine = true, modifier = Modifier.weight(1f), isError = !timeRegex.matches(closes))
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.weekly_holiday), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_LABELS.forEachIndexed { day, label ->
                        FilterChip(selected = day in closedDays,
                            onClick = { if (day in closedDays) closedDays.remove(day) else closedDays.add(day) },
                            label = { Text(stringResource(label)) })
                    }
                }

                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    stringResource(R.string.create_shop), enabled = valid, loading = saving, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val p = place ?: return@PrimaryButton
                        scope.launch {
                            saving = true
                            error = null
                            try {
                                container.api.registerShop(
                                    ShopBody(
                                        name = name.trim(), lat = p.lat, lng = p.lng, addressLine = addressLine.trim(),
                                        locality = locality.trim(), city = city.trim(), pincode = pincode,
                                        categories = chosen.toList(), preferredLanguage = lang, locationSource = source,
                                        hours = (0..6).map { HoursDto(it, opens, closes, it in closedDays) },
                                    )
                                )
                                container.session.applyUser(container.api.me())
                                container.session.update { it.copy(mode = "shop") }
                                onDone()
                            } catch (e: Exception) {
                                error = errorText(context, e)
                            } finally {
                                saving = false
                            }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.verification_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
