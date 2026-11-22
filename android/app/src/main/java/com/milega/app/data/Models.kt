package com.milega.app.data

import kotlinx.serialization.Serializable

// Field names are converted to snake_case by the JSON configuration in MilegaApi.

// ---------------------------------------------------------------- auth / user

@Serializable
data class ShopRef(val id: Long, val name: String, val status: String)

@Serializable
data class UserDto(
    val id: Long,
    val phone: String,
    val name: String? = null,
    val role: String = "customer",
    val preferredLanguage: String = "en",
    val shop: ShopRef? = null,
)

@Serializable
data class OtpRequestBody(val phone: String)

@Serializable
data class OtpRequestResp(val sent: Boolean = true, val phone: String = "", val expiresIn: Int = 300, val devOtp: String? = null)

@Serializable
data class OtpVerifyBody(val phone: String, val code: String, val name: String? = null, val preferredLanguage: String? = null)

@Serializable
data class AuthResp(val accessToken: String, val isNew: Boolean = false, val user: UserDto)

@Serializable
data class MePatch(val name: String? = null, val preferredLanguage: String? = null)

// ---------------------------------------------------------------------- shops

@Serializable
data class HoursDto(val weekday: Int, val opens: String = "09:00", val closes: String = "21:00", val isClosed: Boolean = false)

@Serializable
data class ShopDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    val addressLine: String = "",
    val locality: String = "",
    val city: String = "",
    val pincode: String = "",
    val lat: Double,
    val lng: Double,
    val categories: List<String> = emptyList(),
    val verified: Boolean = false,
    val isDemo: Boolean = false,
    val openNow: Boolean? = null,
    val hours: List<HoursDto> = emptyList(),
    val phone: String? = null,
    val distanceM: Double? = null,
    val status: String? = null,
    val locationVerified: Boolean? = null,
    val visibleToCustomers: Boolean? = null,
    val productsInStock: Int? = null,
)

@Serializable
data class NearbyShopsResp(val shops: List<ShopDto> = emptyList())

@Serializable
data class ShopBody(
    val name: String,
    val lat: Double,
    val lng: Double,
    val addressLine: String = "",
    val locality: String = "",
    val city: String = "",
    val pincode: String = "",
    val categories: List<String> = emptyList(),
    val preferredLanguage: String = "en",
    val hours: List<HoursDto>? = null,
    val locationSource: String = "gps",
)

@Serializable
data class CategoryDto(val slug: String, val nameEn: String, val nameHi: String, val nameKn: String, val icon: String = "box")

@Serializable
data class CategoriesResp(val categories: List<CategoryDto> = emptyList())

// ------------------------------------------------------------------ catalogue

@Serializable
data class VariantDto(
    val id: Long,
    val displayName: String,
    val brand: String? = null,
    val productName: String? = null,
    val category: String = "other",
    val sizeLabel: String? = null,
    val sellUnit: String = "pc",
    val isLoose: Boolean = false,
    val status: String = "verified",
    val score: Double? = null,
)

@Serializable
data class VariantsResp(val variants: List<VariantDto> = emptyList())

@Serializable
data class NewProduct(
    val name: String,
    val brand: String? = null,
    val sizeLabel: String? = null,
    val category: String = "other",
    val sellUnit: String = "pc",
)

// ------------------------------------------------------------------ inventory

@Serializable
data class ListingDto(
    val id: Long,
    val variantId: Long,
    val name: String,
    val catalogName: String = "",
    val brand: String? = null,
    val category: String = "other",
    val sizeLabel: String? = null,
    val sellUnit: String = "pc",
    val unitLabel: String = "pcs",
    val isLoose: Boolean = false,
    val variantStatus: String = "verified",
    val pricePaise: Long? = null,
    val onHand: Double = 0.0,
    val reserved: Double = 0.0,
    val available: Double = 0.0,
    val lowStock: Boolean = false,
    val outOfStock: Boolean = false,
    val overcommitted: Boolean = false,
    val stockConfirmedAt: String? = null,
    val updatedAt: String? = null,
    val isActive: Boolean = true,
)

@Serializable
data class InventoryResp(val items: List<ListingDto> = emptyList(), val count: Int = 0)

@Serializable
data class EventDto(
    val id: Long,
    val listingId: Long,
    val eventType: String,
    val source: String,
    val deltaOnHand: Double = 0.0,
    val deltaReserved: Double = 0.0,
    val onHandAfter: Double = 0.0,
    val reservedAfter: Double = 0.0,
    val verification: String = "",
    val referenceType: String? = null,
    val note: String? = null,
    val createdAt: String,
    val unitLabel: String = "pcs",
    val name: String? = null,
)

@Serializable
data class EventsResp(val events: List<EventDto> = emptyList())

@Serializable
data class ReservationCounts(val pending: Int = 0, val confirmed: Int = 0)

@Serializable
data class SummaryDto(
    val shop: ShopRef,
    val totalProducts: Int = 0,
    val outOfStock: Int = 0,
    val lowStock: Int = 0,
    val attention: List<ListingDto> = emptyList(),
    val reservations: ReservationCounts = ReservationCounts(),
    val recentActivity: List<EventDto> = emptyList(),
)

@Serializable
data class AddListingBody(
    val variantId: Long? = null,
    val newProduct: NewProduct? = null,
    val quantity: Double = 0.0,
    val pricePaise: Long? = null,
)

@Serializable
data class ListingPatch(val pricePaise: Long? = null, val customName: String? = null, val clearPrice: Boolean = false)

@Serializable
data class AdjustBody(val mode: String, val quantity: Double = 0.0, val source: String = "manual", val note: String? = null)

@Serializable
data class AdjustResp(val listing: ListingDto, val event: EventDto, val replayed: Boolean = false, val warning: String? = null)

@Serializable
data class OkResp(val ok: Boolean = true)

// --------------------------------------------------------------------- search

@Serializable
data class Interpretation(val terms: List<String> = emptyList(), val size: String? = null, val maxPricePaise: Long? = null, val category: String? = null)

@Serializable
data class Availability(val status: String, val available: Double = 0.0, val stockConfirmedAt: String? = null, val ageHours: Double? = null)

@Serializable
data class OfferShop(
    val id: Long,
    val name: String,
    val locality: String = "",
    val lat: Double,
    val lng: Double,
    val verified: Boolean = false,
    val isDemo: Boolean = false,
    val openNow: Boolean? = null,
)

@Serializable
data class Offer(
    val listingId: Long,
    val pricePaise: Long? = null,
    val distanceM: Double? = null,
    val availability: Availability,
    val reservable: Boolean = false,
    val unitLabel: String = "pcs",
    val shop: OfferShop,
)

@Serializable
data class SearchResult(
    val variant: VariantDto,
    val matchScore: Double = 0.0,
    val offers: List<Offer> = emptyList(),
    val minPricePaise: Long? = null,
    val inStockCount: Int = 0,
)

@Serializable
data class SearchResp(
    val query: String = "",
    val interpretation: Interpretation = Interpretation(),
    val radiusKm: Double = 5.0,
    val shopsInRange: Int = 0,
    val results: List<SearchResult> = emptyList(),
)

@Serializable
data class ShopProduct(
    val listingId: Long,
    val variantId: Long,
    val name: String,
    val brand: String? = null,
    val category: String = "other",
    val sizeLabel: String? = null,
    val pricePaise: Long? = null,
    val unitLabel: String = "pcs",
    val sellUnit: String = "pc",
    val availability: Availability,
)

@Serializable
data class ShopProductsResp(val shopId: Long, val products: List<ShopProduct> = emptyList())

// --------------------------------------------------------------- reservations

@Serializable
data class ReservationBody(val listingId: Long, val quantity: Double, val note: String? = null)

@Serializable
data class ShopActionBody(val note: String? = null)

@Serializable
data class ResShop(
    val id: Long, val name: String, val locality: String = "", val city: String = "",
    val addressLine: String = "", val lat: Double = 0.0, val lng: Double = 0.0,
)

@Serializable
data class ResItem(val listingId: Long, val variantId: Long, val name: String, val quantity: Double, val unitLabel: String = "pcs", val unitPricePaise: Long? = null)

@Serializable
data class ResCustomer(val name: String = "Customer", val phoneLast4: String = "")

@Serializable
data class ReservationDto(
    val id: Long,
    val code: String,
    val status: String,
    val expiresAt: String,
    val createdAt: String,
    val confirmedAt: String? = null,
    val closedAt: String? = null,
    val customerNote: String? = null,
    val shopNote: String? = null,
    val shop: ResShop,
    val items: List<ResItem> = emptyList(),
    val customer: ResCustomer? = null,
)

@Serializable
data class ReservationsResp(val reservations: List<ReservationDto> = emptyList())

@Serializable
data class NotificationDto(val id: Long, val kind: String, val title: String, val body: String, val read: Boolean = false, val createdAt: String = "")

@Serializable
data class NotificationsResp(val unread: Int = 0, val notifications: List<NotificationDto> = emptyList())

// ---------------------------------------------------------------- recognition

@Serializable
data class OcrLineDto(val text: String, val block: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

@Serializable
data class ProviderStatus(val active: String = "on_device_ocr", val canCount: Boolean = false, val model: String? = null, val description: String = "")

@Serializable
data class MatchDto(
    val id: Long,
    val displayName: String,
    val brand: String? = null,
    val category: String = "other",
    val sizeLabel: String? = null,
    val sellUnit: String = "pc",
    val status: String = "verified",
    val score: Double = 0.0,
    val listingId: Long? = null,
    val currentOnHand: Double? = null,
    val currentPricePaise: Long? = null,
    val unitLabel: String = "pcs",
)

@Serializable
data class CandidateDto(
    val id: Long,
    val label: String,
    val brand: String? = null,
    val sizeText: String? = null,
    val detectedText: String? = null,
    val confidence: String = "low",
    val visibleCount: Int? = null,
    val countReliable: Boolean = false,
    val suggestedQuantity: Double? = null,
    val quantitySource: String? = null,
    val invoiceUnitPricePaise: Long? = null,
    val suggestedVariantId: Long? = null,
    val matches: List<MatchDto> = emptyList(),
    val status: String = "pending",
)

@Serializable
data class JobDto(
    val id: String,
    val mode: String,
    val status: String,
    val provider: String = "",
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val supplierName: String? = null,
    val createdAt: String = "",
    val candidates: List<CandidateDto> = emptyList(),
)

@Serializable
data class ConfirmItem(
    val candidateId: Long? = null,
    val variantId: Long? = null,
    val newProduct: NewProduct? = null,
    val quantity: Double,
    val pricePaise: Long? = null,
)

@Serializable
data class ConfirmBody(val items: List<ConfirmItem>)

@Serializable
data class AppliedDto(val listingId: Long, val name: String, val quantity: Double, val onHand: Double, val unitLabel: String = "pcs", val provisional: Boolean = false)

@Serializable
data class ConfirmResp(val jobId: String, val applied: List<AppliedDto> = emptyList())

// ---------------------------------------------------------------------- voice

@Serializable
data class VoiceBody(val text: String, val language: String? = null)

@Serializable
data class VoiceMatch(val listingId: Long, val name: String, val score: Double = 0.0, val onHand: Double = 0.0, val sellUnit: String = "pc", val unitLabel: String = "pcs")

@Serializable
data class Proposal(val listingId: Long, val mode: String, val quantity: Double = 0.0, val newOnHand: Double = 0.0)

@Serializable
data class VoiceResp(
    val transcript: String = "",
    val action: String? = null,
    val quantity: Double? = null,
    val productText: String = "",
    val matches: List<VoiceMatch> = emptyList(),
    val confidence: String = "low",
    val needsChoice: Boolean = false,
    val proposal: Proposal? = null,
    val problems: List<String> = emptyList(),
)
