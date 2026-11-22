package com.milega.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.milega.app.AppContainer
import com.milega.app.R
import com.milega.app.ui.auth.LoginScreen
import com.milega.app.ui.auth.OtpScreen
import com.milega.app.ui.auth.RoleScreen
import com.milega.app.ui.auth.SplashScreen
import com.milega.app.ui.auth.WelcomeScreen
import com.milega.app.ui.customer.ExploreScreen
import com.milega.app.ui.customer.HomeScreen
import com.milega.app.ui.customer.LocationPickerScreen
import com.milega.app.ui.customer.MyReservationsScreen
import com.milega.app.ui.customer.PlaceProfileScreen
import com.milega.app.ui.customer.ProductScreen
import com.milega.app.ui.customer.SavedScreen
import com.milega.app.ui.customer.SearchScreen
import com.milega.app.ui.customer.ShopProfileScreen
import com.milega.app.ui.shop.AddProductScreen
import com.milega.app.ui.shop.InventoryScreen
import com.milega.app.ui.shop.ItemDetailScreen
import com.milega.app.ui.shop.RegisterShopScreen
import com.milega.app.ui.shop.ScanReviewScreen
import com.milega.app.ui.shop.ScanScreen
import com.milega.app.ui.shop.ShopHomeScreen
import com.milega.app.ui.shop.ShopReservationsScreen
import com.milega.app.ui.shop.VoiceScreen
import kotlinx.coroutines.flow.first

object Routes {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val LOGIN = "login"
    const val ROLE = "role"
    const val C_HOME = "c/home"
    const val C_SAVED = "c/saved"
    const val C_RESERVATIONS = "c/reservations"
    const val C_LOCATION = "c/location"
    const val ACCOUNT = "account"
    const val S_HOME = "s/home"
    const val S_ADD = "s/add"
    const val S_VOICE = "s/voice"
    const val S_RESERVATIONS = "s/reservations"
    const val S_REGISTER = "s/register"

    const val C_EXPLORE_PATTERN = "c/explore?category={category}"
    const val C_SEARCH_PATTERN = "c/search?q={q}&category={category}"

    fun otp(phone: String, devOtp: String?) = "otp/${Uri.encode(phone)}?dev=${devOtp ?: ""}"
    fun search(q: String = "", category: String? = null) = "c/search?q=${Uri.encode(q)}&category=${category ?: ""}"
    fun explore(category: String? = null) = "c/explore?category=${category ?: ""}"
    fun shop(id: Long) = "c/shop/$id"
    fun place(placeId: String) = "c/place/${Uri.encode(placeId)}"
    fun product(id: Long) = "c/product/$id"
    fun inventory(filter: String = "all") = "s/inventory?filter=$filter"
    fun item(id: Long) = "s/item/$id"
    fun scan(mode: String = "incoming") = "s/scan?mode=$mode"
    fun review(jobId: String) = "s/review/$jobId"
    fun register(placeId: String? = null, name: String? = null, lat: Double? = null, lng: Double? = null, address: String? = null) =
        "$S_REGISTER?placeId=${Uri.encode(placeId ?: "")}&name=${Uri.encode(name ?: "")}" +
            "&lat=${lat ?: ""}&lng=${lng ?: ""}&address=${Uri.encode(address ?: "")}"
}

/** Navigate and make [route] the only screen on the back stack. */
fun NavController.resetTo(route: String) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
}

private data class Tab(val route: String, val pattern: String, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab(Routes.C_HOME, Routes.C_HOME, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.explore(), Routes.C_EXPLORE_PATTERN, R.string.nav_explore, Icons.Outlined.Explore, Icons.Filled.Explore),
    Tab(Routes.search(), Routes.C_SEARCH_PATTERN, R.string.nav_search, Icons.Outlined.Search, Icons.Filled.Search),
    Tab(Routes.C_SAVED, Routes.C_SAVED, R.string.nav_saved, Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite),
    Tab(Routes.ACCOUNT, Routes.ACCOUNT, R.string.nav_account, Icons.Outlined.Person, Icons.Filled.Person),
)

@Composable
fun AppNavigation(container: AppContainer, pendingRoute: String? = null, onRouteConsumed: () -> Unit = {}) {
    val nav: NavHostController = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val showTabs = current?.route in TABS.map { it.pattern } && container.session.current.mode != "shop"

    LaunchedEffect(Unit) {
        container.api.authExpired.collect {
            container.session.logout()
            container.cancelNotifications()
            nav.resetTo(Routes.LOGIN)
        }
    }
    // Open the screen a notification asked for, once we're past splash/login.
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        nav.currentBackStackEntryFlow.first {
            it.destination.route !in setOf(Routes.SPLASH, Routes.WELCOME, Routes.LOGIN, "otp/{phone}?dev={dev}")
        }
        if (container.session.current.loggedIn) nav.navigate(route) { launchSingleTop = true }
        onRouteConsumed()
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(showTabs, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TABS.forEach { tab ->
                        val selected = current?.hierarchy?.any { it.route == tab.pattern } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                            label = { Text(stringResource(tab.label)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        val bottom = PaddingValues(bottom = inner.calculateBottomPadding())
        NavHost(
            navController = nav,
            startDestination = Routes.SPLASH,
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(120)) },
        ) {
            authGraph(nav, container)
            customerGraph(nav, container)
            shopGraph(nav, container)
        }
    }
}

private fun androidx.navigation.NavGraphBuilder.authGraph(nav: NavHostController, container: AppContainer) {
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
        RoleScreen(onCustomer = { nav.resetTo(Routes.C_HOME) }, onShop = { nav.navigate(Routes.register()) })
    }
}

private fun androidx.navigation.NavGraphBuilder.customerGraph(nav: NavHostController, container: AppContainer) {
    composable(Routes.C_HOME) {
        HomeScreen(
            container,
            onSearch = { nav.navigate(Routes.search(it)) },
            onCategory = { nav.navigate(Routes.search(category = it)) },
            onShop = { nav.navigate(Routes.shop(it)) },
            onPlace = { nav.navigate(Routes.place(it)) },
            onProduct = { nav.navigate(Routes.product(it)) },
            onLocation = { nav.navigate(Routes.C_LOCATION) },
            onNotifications = { nav.navigate(Routes.C_RESERVATIONS) },
            onAccount = { nav.navigate(Routes.ACCOUNT) { launchSingleTop = true } },
            onExplore = { nav.navigate(Routes.explore()) { launchSingleTop = true } },
        )
    }
    composable(
        Routes.C_EXPLORE_PATTERN,
        arguments = listOf(navArgument("category") { type = NavType.StringType; defaultValue = "" }),
    ) { entry ->
        ExploreScreen(container, entry.arguments?.getString("category").orEmpty().ifBlank { null },
            onShop = { nav.navigate(Routes.shop(it)) }, onPlace = { nav.navigate(Routes.place(it)) })
    }
    composable(
        Routes.C_SEARCH_PATTERN,
        arguments = listOf(
            navArgument("q") { type = NavType.StringType; defaultValue = "" },
            navArgument("category") { type = NavType.StringType; defaultValue = "" },
        ),
    ) { entry ->
        SearchScreen(
            container, entry.arguments?.getString("q").orEmpty(), entry.arguments?.getString("category").orEmpty().ifBlank { null },
            onProduct = { nav.navigate(Routes.product(it)) },
            onShop = { nav.navigate(Routes.shop(it)) },
            onPlace = { nav.navigate(Routes.place(it)) },
            onLocation = { nav.navigate(Routes.C_LOCATION) },
        )
    }
    composable(Routes.C_SAVED) {
        SavedScreen(container, onShop = { nav.navigate(Routes.shop(it)) }, onPlace = { nav.navigate(Routes.place(it)) },
            onLogin = { nav.navigate(Routes.LOGIN) })
    }
    composable(Routes.ACCOUNT) { AccountScreen(container, nav) }
    composable(
        "c/shop/{id}",
        arguments = listOf(navArgument("id") { type = NavType.LongType }),
        deepLinks = listOf(navDeepLink { uriPattern = "milega://shop/{id}" }),
    ) { entry ->
        ShopProfileScreen(container, entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() },
            onReservations = { nav.navigate(Routes.C_RESERVATIONS) })
    }
    composable("c/place/{placeId}", arguments = listOf(navArgument("placeId") { type = NavType.StringType })) { entry ->
        PlaceProfileScreen(
            container, entry.arguments?.getString("placeId").orEmpty(),
            onBack = { nav.popBackStack() },
            onMilegaShop = { id -> nav.navigate(Routes.shop(id)) { popUpTo("c/place/{placeId}") { inclusive = true } } },
            onClaim = { p -> nav.navigate(Routes.register(p.placeId, p.name, p.lat, p.lng, p.address)) },
        )
    }
    composable(
        "c/product/{id}",
        arguments = listOf(navArgument("id") { type = NavType.LongType }),
        deepLinks = listOf(navDeepLink { uriPattern = "milega://product/{id}" }),
    ) { entry ->
        ProductScreen(container, entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() },
            onShop = { nav.navigate(Routes.shop(it)) }, onProduct = { nav.navigate(Routes.product(it)) },
            onReservations = { nav.navigate(Routes.C_RESERVATIONS) })
    }
    composable(Routes.C_RESERVATIONS, deepLinks = listOf(navDeepLink { uriPattern = "milega://reservations" })) {
        MyReservationsScreen(container, nav)
    }
    composable(Routes.C_LOCATION) { LocationPickerScreen(container) { nav.popBackStack() } }
}

private fun androidx.navigation.NavGraphBuilder.shopGraph(nav: NavHostController, container: AppContainer) {
    composable(
        "${Routes.S_REGISTER}?placeId={placeId}&name={name}&lat={lat}&lng={lng}&address={address}",
        arguments = listOf("placeId", "name", "lat", "lng", "address").map { navArgument(it) { type = NavType.StringType; defaultValue = "" } },
    ) { entry ->
        val a = entry.arguments
        RegisterShopScreen(
            container,
            prefillPlaceId = a?.getString("placeId").orEmpty().ifBlank { null },
            prefillName = a?.getString("name").orEmpty(),
            prefillLat = a?.getString("lat")?.toDoubleOrNull(),
            prefillLng = a?.getString("lng")?.toDoubleOrNull(),
            prefillAddress = a?.getString("address").orEmpty(),
            onBack = { nav.popBackStack() },
            onDone = { nav.resetTo(Routes.S_HOME) },
        )
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
