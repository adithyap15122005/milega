package com.milega.app

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.milega.app.ui.components.MerchantBadge
import com.milega.app.ui.customer.HomeContent
import com.milega.app.ui.customer.HomeData
import com.milega.app.ui.shop.SellerDashboard
import com.milega.app.ui.theme.MilegaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h2400dp-xxhdpi")
class UiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun trustBadgesAlwaysSpellOutTheState() {
        compose.setContent {
            MilegaTheme {
                Column {
                    MerchantBadge("verified_merchant")
                    MerchantBadge("unverified_merchant")
                    MerchantBadge("google_discovered")
                    MerchantBadge("demo")
                }
            }
        }
        compose.onNodeWithText("Verified Milega shop").assertIsDisplayed()
        compose.onNodeWithText("Awaiting verification").assertIsDisplayed()
        compose.onNodeWithText("Listed on Google Maps · not on Milega").assertIsDisplayed()
        compose.onNodeWithText("Sample shop").assertIsDisplayed()
    }

    @Test
    fun homeShowsOnlySectionsBackedByData() {
        val data = HomeData(merchants = listOf(SampleData.merchant), externalStatus = "ok")
        compose.setContent { home(data) }
        compose.onNodeWithText("What are you looking for in Bengaluru?").assertIsDisplayed()
        compose.onNodeWithText("Shops near you").assertExists()
        compose.onAllNodesWithText("Sri Lakshmi Provision Stores").onFirst().assertExists() // carousel + "New on Milega"
        compose.onNodeWithText("New on Milega").assertExists()
        compose.onNodeWithText("Compare prices").assertDoesNotExist()       // no comparable products
        compose.onNodeWithText("In stock near you").assertDoesNotExist()    // no confirmed stock
        compose.onNodeWithText("More shops on Google Maps").assertDoesNotExist()
    }

    @Test
    fun googleBusinessesAreLabelledAndAttributed() {
        compose.setContent { home(SampleData.home) }
        compose.onNodeWithText("More shops on Google Maps").assertExists()
        compose.onNodeWithText("Vijaylaxmi Hardware").assertExists()
        compose.onNodeWithText("On Google Maps").assertExists()
        compose.onNodeWithText("Business information from Google Maps").assertExists()
        compose.onNodeWithText("Compare prices").assertExists()
    }

    @Test
    fun homeAsksForLocationWithoutGps() {
        var asked = 0
        compose.setContent {
            MilegaTheme {
                HomeContent(null, null, hasLocation = false, unread = 0, categories = SampleData.categories, lang = "en",
                    data = HomeData(), error = null, recent = emptyList(), onRetry = {}, onSearchClick = {}, onVoice = {},
                    onExample = {}, onCategory = {}, onShop = {}, onPlace = {}, onProduct = {}, onLocation = { asked++ },
                    onNotifications = {}, onAccount = {}, onExplore = {})
            }
        }
        compose.onNodeWithText("Where are you?").assertExists()
        compose.onNodeWithText("Choose location").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun sellerSeesUnsyncedChangesAndRequests() {
        compose.setContent {
            MilegaTheme {
                SellerDashboard(SampleData.sellerSummary, listOf(op(11, "sell", 1.0, "k1")), {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("1 change(s) saved on this phone — they sync when you’re online").assertExists()
        compose.onAllNodesWithText("2 new requests").onFirst().assertExists() // banner + tile
        compose.onNodeWithText("Inventory health").assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun home(data: HomeData) = MilegaTheme {
        HomeContent("Asha", "Jayanagar", hasLocation = true, unread = 2, categories = SampleData.categories, lang = "en",
            data = data, error = null, recent = emptyList(), onRetry = {}, onSearchClick = {}, onVoice = {}, onExample = {},
            onCategory = {}, onShop = {}, onPlace = {}, onProduct = {}, onLocation = {}, onNotifications = {}, onAccount = {},
            onExplore = {}, now = LocalTime.of(10, 0))
    }
}
