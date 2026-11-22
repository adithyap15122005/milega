package com.milega.app.ui.auth

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.currentLanguage
import com.milega.app.data.ApiException
import com.milega.app.ui.Routes
import com.milega.app.ui.components.PrimaryButton
import com.milega.app.ui.theme.BrandColors
import com.milega.app.util.errorText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SplashScreen(container: AppContainer, go: (String) -> Unit) {
    LaunchedEffect(Unit) {
        val s = container.session.load()
        if (!s.loggedIn) {
            go(if (s.language == null) Routes.WELCOME else Routes.LOGIN)
            return@LaunchedEffect
        }
        try {
            container.session.applyUser(container.api.me())
        } catch (e: ApiException) {
            if (e.status == 401) return@LaunchedEffect // AppNavigation sends the user to login
        }
        val fresh = container.session.current
        container.scheduleNotifications()
        go(if (fresh.mode == "shop" && fresh.hasShop) Routes.S_HOME else Routes.C_HOME)
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(72)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun BrandMark(size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(BrandColors.Green),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_launcher_foreground), null, tint = Color.Unspecified, modifier = Modifier.size((size * 1.4).dp))
    }
}

@Composable
fun WelcomeScreen(container: AppContainer, onContinue: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxWidth().weight(1f).background(
                Brush.verticalGradient(listOf(BrandColors.Green, Color(0xFF0F8A63)))
            ),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(Modifier.safeDrawingPadding().padding(28.dp)) {
                BrandMark(64)
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.app_name), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tagline), color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.welcome_body), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Column(Modifier.padding(24.dp).safeDrawingPadding()) {
            Text(stringResource(R.string.choose_language), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            listOf("en" to "English", "hi" to "हिन्दी", "kn" to "ಕನ್ನಡ").forEach { (code, label) ->
                LanguageOption(label, selected = currentLanguage() == code) {
                    scope.launch {
                        container.session.update { it.copy(language = code) }
                        if (currentLanguage() == code) onContinue()
                        else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code)) // recreates the screen
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().height(60.dp),
    ) {
        Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun LoginScreen(container: AppContainer, onCodeSent: (phone: String, devOtp: String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showServer by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(container.session.current.serverUrl) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        BrandMark(52)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.login_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter(Char::isDigit).take(10); error = null },
            label = { Text(stringResource(R.string.mobile_number)) },
            prefix = { Text("+91 ") },
            singleLine = true,
            isError = error != null,
            supportingText = { if (error != null) Text(error!!) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = stringResource(R.string.send_code),
            loading = loading,
            enabled = phone.length == 10,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    loading = true
                    try {
                        if (showServer) container.session.update { it.copy(serverUrl = server.trim()) }
                        val resp = container.api.requestOtp(phone)
                        onCodeSent(resp.phone, resp.devOtp)
                    } catch (e: Exception) {
                        error = errorText(context, e)
                    } finally {
                        loading = false
                    }
                }
            },
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { showServer = !showServer }) { Text(stringResource(R.string.developer_settings)) }
        if (showServer) {
            OutlinedTextField(
                value = server, onValueChange = { server = it }, singleLine = true,
                label = { Text(stringResource(R.string.server_address)) },
                supportingText = { Text(stringResource(R.string.server_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun OtpScreen(container: AppContainer, phone: String, devOtp: String?, onBack: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var devCode by remember { mutableStateOf(devOtp) }
    var countdown by remember { mutableIntStateOf(30) }
    LaunchedEffect(countdown) { if (countdown > 0) { delay(1000); countdown -= 1 } }

    fun verify() = scope.launch {
        loading = true
        try {
            val auth = container.api.verifyOtp(phone, code, currentLanguage())
            container.session.applyUser(auth.user, auth.accessToken)
            container.scheduleNotifications()
            onDone(
                when {
                    auth.user.shop != null -> Routes.S_HOME
                    auth.isNew -> Routes.ROLE
                    else -> Routes.C_HOME
                }
            )
        } catch (e: Exception) {
            error = errorText(context, e)
        } finally {
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.otp_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.otp_body, phone), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        if (devCode != null) {
            // Only present when the server runs in development mode without SMS.
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.dev_mode), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.dev_otp_is, devCode!!), style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { code = devCode!! }) { Text(stringResource(R.string.fill)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6); error = null },
            label = { Text(stringResource(R.string.six_digit_code)) },
            singleLine = true,
            isError = error != null,
            supportingText = { if (error != null) Text(error!!) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 8.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton(stringResource(R.string.verify), onClick = { verify() }, enabled = code.length == 6, loading = loading,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        TextButton(
            enabled = countdown == 0,
            onClick = {
                scope.launch {
                    try {
                        devCode = container.api.requestOtp(phone).devOtp
                        countdown = 30
                    } catch (e: Exception) {
                        error = errorText(context, e)
                    }
                }
            },
        ) { Text(if (countdown > 0) stringResource(R.string.resend_in, countdown) else stringResource(R.string.resend)) }
    }
}

@Composable
fun RoleScreen(onCustomer: () -> Unit, onShop: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.role_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        RoleCard(Icons.Filled.ShoppingBag, stringResource(R.string.role_customer), stringResource(R.string.role_customer_body),
            MaterialTheme.colorScheme.tertiaryContainer, onCustomer)
        Spacer(Modifier.height(14.dp))
        RoleCard(Icons.Filled.Storefront, stringResource(R.string.role_shop), stringResource(R.string.role_shop_body),
            MaterialTheme.colorScheme.primaryContainer, onShop)
    }
}

@Composable
private fun RoleCard(icon: ImageVector, title: String, body: String, tint: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(tint), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(30.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
