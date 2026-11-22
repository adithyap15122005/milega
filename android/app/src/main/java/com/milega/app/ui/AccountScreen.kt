package com.milega.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.currentLanguage
import com.milega.app.data.MePatch
import com.milega.app.ui.components.SectionTitle
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(container: AppContainer, nav: NavController) {
    val scope = rememberCoroutineScope()
    val session by container.session.flow.collectAsStateWithLifecycle(initialValue = container.session.current)
    var showDev by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(session.serverUrl) }
    val inShopMode = session.mode == "shop" && session.hasShop

    Scaffold(
        bottomBar = { if (!inShopMode) CustomerBottomBar(nav, Routes.ACCOUNT) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp)) {
            item {
                if (inShopMode) TextButton(onClick = { nav.popBackStack() }) { Text("← " + stringResource(R.string.back)) }
                Text(session.name ?: stringResource(R.string.nav_account), style = MaterialTheme.typography.headlineMedium)
                Text(session.phone ?: "", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SectionTitle(stringResource(R.string.language))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en" to "English", "hi" to "हिन्दी", "kn" to "ಕನ್ನಡ").forEach { (code, label) ->
                        FilterChip(
                            selected = currentLanguage() == code,
                            onClick = {
                                scope.launch {
                                    container.session.update { it.copy(language = code) }
                                    try { container.api.updateMe(MePatch(preferredLanguage = code)) } catch (_: Exception) {}
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.my_shop))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (session.hasShop) {
                            Text(session.shopName ?: "", style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(if (session.shopStatus == "active") R.string.shop_live else R.string.shop_pending),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val toShop = !inShopMode
                                    container.session.update { it.copy(mode = if (toShop) "shop" else "customer") }
                                    nav.resetTo(if (toShop) Routes.S_HOME else Routes.C_HOME)
                                }
                            }) {
                                Icon(if (inShopMode) Icons.Filled.ShoppingBag else Icons.Filled.Storefront, null)
                                Spacer(Modifier.padding(4.dp))
                                Text(stringResource(if (inShopMode) R.string.switch_to_shopping else R.string.switch_to_shop))
                            }
                        } else {
                            Text(stringResource(R.string.own_a_shop), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = { nav.navigate(Routes.S_REGISTER) }) {
                                Icon(Icons.Filled.Storefront, null)
                                Spacer(Modifier.padding(4.dp))
                                Text(stringResource(R.string.register_shop))
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.privacy))
                Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { showDev = !showDev }) { Text(stringResource(R.string.developer_settings)) }
                if (showDev) {
                    OutlinedTextField(server, { server = it }, singleLine = true, label = { Text(stringResource(R.string.server_address)) },
                        supportingText = { Text(stringResource(R.string.server_hint)) }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { scope.launch { container.session.update { it.copy(serverUrl = server.trim()) } } }) {
                        Text(stringResource(R.string.save))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            container.session.logout()
                            container.cancelNotifications()
                            nav.resetTo(Routes.LOGIN)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null)
                    Spacer(Modifier.padding(4.dp))
                    Text(stringResource(R.string.log_out))
                }
            }
        }
    }
}
