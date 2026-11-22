package com.milega.app

import com.milega.app.data.AltProduct
import com.milega.app.data.Availability
import com.milega.app.data.CategoryDto
import com.milega.app.data.EventDto
import com.milega.app.data.ExternalPlace
import com.milega.app.data.ListingDto
import com.milega.app.data.NearbyItem
import com.milega.app.data.Offer
import com.milega.app.data.OfferShop
import com.milega.app.data.OfferSummary
import com.milega.app.data.ProductDetailResp
import com.milega.app.data.ReservationCounts
import com.milega.app.data.ShopDto
import com.milega.app.data.ShopRef
import com.milega.app.data.SummaryDto
import com.milega.app.data.UnitPrice
import com.milega.app.data.VariantDetail
import com.milega.app.data.VariantDto
import com.milega.app.ui.customer.HomeData
import java.time.OffsetDateTime

/**
 * Fixtures for UI tests and screenshots. They mirror real API shapes; nothing here is shown
 * in the app. Shop names are the clearly-labelled development sample shops.
 */
object SampleData {
    private val now = OffsetDateTime.now()

    val categories = listOf(
        CategoryDto("staples", "Kirana & Grocery", "किराना", "ದಿನಸಿ", "grain"),
        CategoryDto("pharmacy", "Pharmacies", "दवा की दुकान", "ಔಷಧಾಲಯ", "pharmacy"),
        CategoryDto("hardware", "Hardware", "हार्डवेयर", "ಹಾರ್ಡ್‌ವೇರ್", "wrench"),
        CategoryDto("electrical", "Electrical Supplies", "इलेक्ट्रिकल", "ವಿದ್ಯುತ್ ಸಾಮಗ್ರಿ", "bolt"),
        CategoryDto("stationery", "Stationery", "स्टेशनरी", "ಲೇಖನ ಸಾಮಗ್ರಿ", "pencil"),
        CategoryDto("bakery", "Bakeries", "बेकरी", "ಬೇಕರಿ", "bread"),
        CategoryDto("mobile_accessories", "Mobiles & Accessories", "मोबाइल", "ಮೊಬೈಲ್", "phone"),
        CategoryDto("fruits_vegetables", "Fruits & Vegetables", "फल", "ಹಣ್ಣು", "leaf"),
        CategoryDto("clothing", "Clothing", "कपड़े", "ಬಟ್ಟೆ", "shirt"),
        CategoryDto("paint", "Paint Shops", "पेंट", "ಬಣ್ಣ", "paint"),
    )

    val merchant = ShopDto(
        id = 1, name = "Sri Lakshmi Provision Stores", locality = "Jayanagar 4th Block", city = "Bengaluru",
        lat = 12.9299, lng = 77.5826, categories = listOf("staples", "snacks"), verified = true, openNow = true,
        distanceM = 180.0, productsInStock = 42, merchantState = "verified_merchant",
        inventoryUpdatedAt = now.minusHours(2).toString(), verifiedAt = now.minusDays(4).toString(),
    )
    val merchant2 = merchant.copy(id = 2, name = "Sai Electricals & Hardware", locality = "South End Circle",
        categories = listOf("electrical", "hardware"), distanceM = 820.0, productsInStock = 18, openNow = false,
        verifiedAt = now.minusDays(90).toString())

    val googlePlace = ExternalPlace(
        placeId = "ChIJ-sample", name = "Vijaylaxmi Hardware", address = "Whitefield Main Road, Bengaluru",
        fullAddress = "Whitefield Main Road, Thubarahalli, Bengaluru 560066", typeLabel = "Hardware store",
        category = "hardware", distanceM = 640.0, rating = 4.3, userRatingCount = 212, openNow = true,
        weekdayHours = listOf("Monday: 9:00 AM – 9:00 PM", "Sunday: Closed"),
    )

    private val salt = VariantDto(id = 3, displayName = "Tata Salt 1 kg", brand = "Tata", category = "staples", sizeLabel = "1 kg")
    private val atta = VariantDto(id = 1, displayName = "Aashirvaad Shudh Chakki Atta 5 kg", brand = "Aashirvaad", category = "staples", sizeLabel = "5 kg")
    val nearby = listOf(
        NearbyItem(salt, offers = 3, minPricePaise = 2700, nearestM = 180.0),
        NearbyItem(atta, offers = 2, minPricePaise = 28500, nearestM = 410.0),
        NearbyItem(VariantDto(id = 9, displayName = "Parle-G Original Glucose Biscuits 250 g", category = "snacks"), offers = 1, minPricePaise = 2500),
    )

    val home = HomeData(merchants = listOf(merchant, merchant2), external = listOf(googlePlace), externalStatus = "ok",
        nearby = nearby, saved = emptyList())

    fun offer(id: Long, shop: String, price: Long?, status: String, distance: Double) = Offer(
        listingId = id, variantId = 3, pricePaise = price, unitPrice = price?.let { UnitPrice(it, "kg") }, distanceM = distance,
        availability = Availability(status, available = if (status == "out_of_stock") 0.0 else 8.0,
            stockConfirmedAt = now.minusHours(3).toString(), ageHours = 3.0),
        reservable = status != "out_of_stock", unitLabel = "pcs",
        shop = OfferShop(id, shop, "Jayanagar", lat = 12.93, lng = 77.58, verified = true, openNow = true),
    )

    val product = ProductDetailResp(
        variant = VariantDetail(3, "Tata Salt 1 kg", "Tata", "Salt", "staples", "1 kg"),
        offers = listOf(offer(1, "Sri Lakshmi Provision Stores", 2700, "in_stock", 180.0),
            offer(2, "Annapoorna Kirana & General", 2800, "likely", 1450.0),
            offer(3, "Fresh Mart Vegetables & Groceries", null, "out_of_stock", 2100.0)),
        summary = OfferSummary(2700, 2800, inStockCount = 2, shopCount = 3),
        substitutes = listOf(AltProduct(VariantDto(5, "Tata Salt Lite 1 kg", category = "staples"), 4500, 4500, 1, 1, UnitPrice(4500, "kg"))),
        radiusKm = 5.0,
    )

    private fun listing(id: Long, name: String, onHand: Double, low: Boolean = false) = ListingDto(
        id = id, variantId = id, name = name, pricePaise = 2700, onHand = onHand, available = onHand, lowStock = low,
        outOfStock = onHand <= 0.0,
    )

    val sellerSummary = SummaryDto(
        shop = ShopRef(1, "Sri Lakshmi Provision Stores", "active"),
        totalProducts = 42, outOfStock = 2, lowStock = 5,
        attention = listOf(listing(11, "Maggi 2-Minute Masala Noodles 70 g", 2.0, low = true), listing(12, "Fortune Sunflower Oil 1 L", 0.0)),
        reservations = ReservationCounts(pending = 2, confirmed = 1),
        recentActivity = listOf(
            EventDto(1, 11, "sale", "voice", deltaOnHand = -2.0, onHandAfter = 2.0, createdAt = now.minusMinutes(12).toString(), name = "Maggi 2-Minute Masala Noodles 70 g"),
            EventDto(2, 13, "restock", "photo_scan", deltaOnHand = 24.0, onHandAfter = 30.0, createdAt = now.minusHours(1).toString(), name = "Parle-G 250 g"),
        ),
    )
}
