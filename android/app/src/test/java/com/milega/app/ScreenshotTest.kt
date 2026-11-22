package com.milega.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.milega.app.ui.customer.HomeContent
import com.milega.app.ui.customer.HomeData
import com.milega.app.ui.customer.PlaceContent
import com.milega.app.ui.customer.ProductContent
import com.milega.app.ui.shop.SellerDashboard
import com.milega.app.ui.theme.MilegaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime

/**
 * Renders key screens to PNG on the JVM (no emulator) for design review:
 *   gradle :app:recordRoborazziDebug  ->  docs/screenshots/android/
 * Uses sample data shaped exactly like the API. Maps are not rendered off-device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String) = compose.onRoot().captureRoboImage("../../docs/screenshots/android/$name.png")

    private fun home(data: HomeData, dark: Boolean) {
        compose.setContent {
            MilegaTheme(darkTheme = dark) {
                Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                    HomeContent("Asha", "Jayanagar", hasLocation = true, unread = 2, categories = SampleData.categories,
                        lang = "en", data = data, error = null, recent = emptyList(), onRetry = {}, onSearchClick = {},
                        onVoice = {}, onExample = {}, onCategory = {}, onShop = {}, onPlace = {}, onProduct = {},
                        onLocation = {}, onNotifications = {}, onAccount = {}, onExplore = {}, now = LocalTime.of(10, 0))
                }
            }
        }
    }

    @Test fun homeLight() { home(SampleData.home, dark = false); shot("01_home_light") }
    @Test fun homeDark() { home(SampleData.home, dark = true); shot("02_home_dark") }
    @Test fun homeEmptyArea() { home(HomeData(externalStatus = "not_configured"), dark = false); shot("03_home_empty_area") }

    @Test
    fun product() {
        compose.setContent {
            MilegaTheme {
                Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                    ProductContent(SampleData.product, PaddingValues(), onShop = {}, onProduct = {}, onReserve = {})
                }
            }
        }
        shot("04_product_page")
    }

    @Test
    fun googlePlace() {
        compose.setContent {
            MilegaTheme {
                Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                    PlaceContent(SampleData.googlePlace, PaddingValues(), onReport = {}, onClaim = {})
                }
            }
        }
        shot("05_google_listed_shop")
    }

    @Test
    fun sellerDashboard() {
        compose.setContent {
            MilegaTheme {
                SellerDashboard(SampleData.sellerSummary, listOf(op(11, "sell", 1.0, "k1")), {}, {}, {}, {}, {}, {}, {})
            }
        }
        shot("06_seller_dashboard")
    }

    @Test
    fun sellerDashboardDark() {
        compose.setContent {
            MilegaTheme(darkTheme = true) {
                SellerDashboard(SampleData.sellerSummary, emptyList(), {}, {}, {}, {}, {}, {}, {})
            }
        }
        shot("07_seller_dashboard_dark")
    }
}
