package com.milega.app.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.ui.auth.LoginScreen
import com.milega.app.ui.auth.OtpScreen
import com.milega.app.ui.auth.RoleScreen
import com.milega.app.ui.auth.SplashScreen
import com.milega.app.ui.auth.WelcomeScreen
import com.milega.app.ui.customer.DiscoverScreen
import com.milega.app.ui.customer.LocationScreen
import com.milega.app.ui.customer.MyReservationsScreen
import com.milega.app.ui.customer.SearchScreen
import com.milega.app.ui.customer.ShopDetailScreen
import com.milega.app.ui.shop.AddProductScreen
import com.milega.app.ui.shop.InventoryScreen
import com.milega.app.ui.shop.ItemDetailScreen
import com.milega.app.ui.shop.RegisterShopScreen
import com.milega.app.ui.shop.ScanReviewScreen
import com.milega.app.ui.shop.ScanScreen
import com.milega.app.ui.shop.ShopHomeScreen
import com.milega.app.ui.shop.ShopReservationsScreen
import com.milega.app.ui.shop.VoiceScreen

object Routes {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val LOGIN = "login"
    const val ROLE = "role"
    const val C_HOME = "c/home"
    const val C_RESERVATIONS = "c/reservations"
    const val C_LOCATION = "c/location"
    const val ACCOUNT = "account"
    const val S_REGISTER = "s/register"
    const val S_HOME = "s/home"
    const val S_ADD = "s/add"
    const val S_VOICE = "s/voice"
    const val S_RESERVATIONS = "s/reservations"

    fun otp(phone: String, devOtp: String?) = "otp/${Uri.encode(phone)}?dev=${devOtp ?: ""}"
    fun search(q: String = "", category: String? = null) = "c/search?q=${Uri.encode(q)}&category=${category ?: ""}"
    fun shop(id: Long) = "c/shop/$id"
    fun inventory(filter: String = "all") = "s/inventory?filter=$filter"
    fun item(id: Long) = "s/item/$id"
    fun scan(mode: String = "incoming") = "s/scan?mode=$mode"
    fun review(jobId: String) = "s/review/$jobId"
}

/** Navigate and make [route] the only screen on the back stack. */
fun NavController.resetTo(route: String) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
}

@Composable
fun AppNavigation(container: AppContainer) {
    val nav: NavHostController = rememberNavController()

    LaunchedEffect(Unit) {
        container.api.authExpired.collect {
            container.session.logout()
            container.cancelNotifications()
            nav.resetTo(Routes.LOGIN)
        }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.SPLASH,
        enterTransition = { fadeIn(tween(160)) },
        exitTransition = { fadeOut(tween(120)) },
    ) {
        composable(Routes.SPLASH) { SplashScreen(container) { nav.resetTo(it) } }
        composable(Routes.WELCOME) { WelcomeScreen(container) { nav.navigate(Routes.LOGIN) } }
        composable(Routes.LOGIN) { LoginScreen(container, onCodeSent = { phone, dev -> nav.navigate(Routes.otp(phone, dev)) }) }
        composable(
            "otp/{phone}?dev={dev}",
            arguments = listOf(
                navArgument("phone") { type = NavType.StringType },
                navArgument("dev") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            OtpScreen(
                container = container,
                phone = entry.arguments?.getString("phone").orEmpty(),
                devOtp = entry.arguments?.getString("dev").orEmpty().ifBlank { null },
                onBack = { nav.popBackStack() },
                onDone = { nav.resetTo(it) },
            )
        }
        composable(Routes.ROLE) {
            RoleScreen(onCustomer = { nav.resetTo(Routes.C_HOME) }, onShop = { nav.navigate(Routes.S_REGISTER) })
        }

        // ------------------------------------------------------- customer
        composable(Routes.C_HOME) { DiscoverScreen(container, nav) }
        composable(
            "c/search?q={q}&category={category}",
            arguments = listOf(
                navArgument("q") { type = NavType.StringType; defaultValue = "" },
                navArgument("category") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            SearchScreen(container, nav, entry.arguments?.getString("q").orEmpty(),
                entry.arguments?.getString("category").orEmpty().ifBlank { null })
        }
        composable("c/shop/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ShopDetailScreen(container, nav, entry.arguments!!.getLong("id"))
        }
        composable(Routes.C_RESERVATIONS) { MyReservationsScreen(container, nav) }
        composable(Routes.C_LOCATION) { LocationScreen(container) { nav.popBackStack() } }
        composable(Routes.ACCOUNT) { AccountScreen(container, nav) }

        // ---------------------------------------------------- shopkeeper
        composable(Routes.S_REGISTER) {
            RegisterShopScreen(container, onBack = { nav.popBackStack() }, onDone = { nav.resetTo(Routes.S_HOME) })
        }
        composable(Routes.S_HOME) { ShopHomeScreen(container, nav) }
        composable(
            "s/inventory?filter={filter}",
            arguments = listOf(navArgument("filter") { type = NavType.StringType; defaultValue = "all" }),
        ) { entry -> InventoryScreen(container, nav, entry.arguments?.getString("filter") ?: "all") }
        composable("s/item/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ItemDetailScreen(container, nav, entry.arguments!!.getLong("id"))
        }
        composable(Routes.S_ADD) { AddProductScreen(container, nav) }
        composable(
            "s/scan?mode={mode}",
            arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = "incoming" }),
        ) { entry -> ScanScreen(container, nav, entry.arguments?.getString("mode") ?: "incoming") }
        composable("s/review/{jobId}", arguments = listOf(navArgument("jobId") { type = NavType.StringType })) { entry ->
            ScanReviewScreen(container, nav, entry.arguments!!.getString("jobId")!!)
        }
        composable(Routes.S_VOICE) { VoiceScreen(container, nav) }
        composable(Routes.S_RESERVATIONS) { ShopReservationsScreen(container, nav) }
    }
}

@Composable
fun CustomerBottomBar(nav: NavController, current: String) {
    NavigationBar {
        listOf(
            Triple(Routes.C_HOME, Icons.Filled.Explore, R.string.nav_discover),
            Triple(Routes.C_RESERVATIONS, Icons.Filled.Bookmark, R.string.nav_reservations),
            Triple(Routes.ACCOUNT, Icons.Filled.Person, R.string.nav_account),
        ).forEach { (route, icon, label) ->
            NavigationBarItem(
                selected = current == route,
                onClick = {
                    if (current != route) nav.navigate(route) {
                        popUpTo(Routes.C_HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(icon, null) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}
