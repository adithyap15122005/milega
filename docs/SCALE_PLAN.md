# Milega — Path to ₹100 Crore in 5 Years

**Goal:** ₹100 crore in annual recurring revenue by the end of Year 5.

**Thesis:** Milega makes the neighbourhood shop searchable. That one change opens
four revenue pools around it: shop software, supplier ordering, brand intelligence
and credit.

> Every number below is a planning assumption to be checked in pilots. None of it is
> a forecast. Each assumption is written down so the team can replace it with
> measured data as it arrives.

---

## 1. Why this can be big

* India has **~1.2–1.3 crore kirana and independent shops** (FMCG retail estimates
  vary). They do ~80% of grocery retail, and almost none of their stock is searchable
  online.
* Customers already ask "*bhaiya, yeh milega?*" on the phone and on WhatsApp. Milega
  turns that question into a search across every shop in the neighbourhood.
* Quick commerce (Blinkit, Zepto, Instamart) built dark stores. **Milega uses the
  stores that already exist.** It has no warehouse, no inventory risk and no delivery
  fleet, so it is capital-light.
* The hard part for competitors is **keeping stock data fresh** in shops with no
  POS. Our moat is a camera-first and voice-first update loop that a busy shopkeeper
  actually uses. Every confirmed scan becomes labelled training data, and that data
  makes recognition cheaper and better over time.

## 2. Revenue model (Year-5 mix for ₹100 Cr ARR)

| Stream | What it is | Y5 assumption | Y5 ARR |
|---|---|---|---|
| **Milega Pro** (shop SaaS) | Free: listing, OCR scans, reservations. Pro at **₹299/month**: AI vision scans with counts, invoice import, low-stock reorder lists, sales insights, WhatsApp catalogue, priority placement | 60,000 paying shops (20% of 3 lakh active) | **₹21.5 Cr** |
| **Supplier ordering** (B2B) | Reorder suggestions from the ledger → one-tap orders to the shop's existing distributors / FMCG partners; **~2% take rate** | ₹2,000 Cr GMV ≈ ₹55k/month per ordering shop across ~30k shops | **₹40 Cr** |
| **Brand & distribution intelligence** | Aggregated, anonymised **shelf availability by pincode** and price-point data for FMCG / electrical / mobile-accessory brands; sponsored product placement in search | ~40 brand contracts × ₹60 L/yr | **₹25 Cr** |
| **Credit referrals** | Working-capital loans from NBFC / bank partners, underwritten on inventory-ledger data (shop consent); ~2% referral fee | ₹500 Cr disbursed | **₹10 Cr** |
| **Local ads** | Paid "featured shop" slots in a locality | — | **₹4 Cr** |
| **Total** | | | **≈ ₹100 Cr** |

Customers stay free: no fees on search or reservations. Payment happens at the shop.

## 3. Five-year build-up

| Year | Footprint | Active shops | Customer MAU | ARR | What unlocks the next year |
|---|---|---|---|---|---|
| **Y1** | 4–5 dense Bengaluru neighbourhoods (Jayanagar, JP Nagar, BTM, Basavanagudi) | 2,000 | 50k | ₹0.3 Cr (pilots) | Proof that shops keep stock fresh: ≥60% of listings confirmed weekly; ≥85% of reservations fulfilled |
| **Y2** | All of Bengaluru, Mysuru, Hubballi-Dharwad | 20,000 | 5 L | ₹3 Cr | Pro launch; first FMCG data pilots; Hindi + Kannada voice at scale |
| **Y3** | Karnataka + Hyderabad, Chennai, Pune | 80,000 | 25 L | ₹15 Cr | Supplier ordering live with 3–4 distributor networks; ONDC seller-side integration |
| **Y4** | 10 metros + Tier-2 hubs | 1.8 L | 60 L | ₹45 Cr | Credit partners; on-device recognition model cuts vision cost by ~10× |
| **Y5** | 20+ cities | 3 L | 1 Cr | **₹100 Cr** | Brand intelligence as a product line; regional languages beyond hi/kn |

Three lakh active shops is about **2.5% of India's independent retailers**. The plan
depends on density, not national coverage. A customer only stays when most shops
within 1–2 km are listed. **We go neighbourhood by neighbourhood and never spread
thin.**

## 4. Go-to-market

**Shops (supply first, by locality)**
* *Milega Mitra* field agents walk a locality. In 15 minutes they photograph a shop's
  first 50–100 products with the owner, so the shop goes live during the visit.
  Target CAC is ₹300–600 per activated shop.
* Distributor partnerships: FMCG distributor salesmen already visit every kirana
  weekly. Paying them per activated shop turns an existing route into an onboarding
  channel.
* Shopkeeper referral: one free Pro month for each shop referred within 2 km.

**Customers (demand, per locality, after supply)**
* Apartment associations and resident WhatsApp groups: "*Check which shop near you
  has it before you walk.*"
* SEO pages per locality and product ("Tata Salt 1 kg in Jayanagar — 4 shops, from ₹26").
* In-shop QR standees: "*Scan to see everything this shop has.*"

## 5. Unit economics to watch

| Metric | Healthy target | Why |
|---|---|---|
| Shop activation (≥30 products in 7 days) | 70% | Empty shops kill search quality |
| Weekly active shopkeepers | 60% | Fresh stock is the product |
| Listings confirmed in the last 72 h | 50%+ | Drives the "In stock" label |
| Search → reservation conversion | 5–8% | Proves intent |
| Reservation fulfilment rate | ≥ 85% | Trust; shops with low rates get ranked lower |
| CAC per activated shop | ≤ ₹600 | Paid back by Pro in < 3 months, or by B2B take in ~1 month |
| Free → Pro conversion | 15–20% by month 6 | Main SaaS driver |

**AI cost per scan** (list prices as of Nov 2026; one photo ≈ 2–3k input tokens and about 1k output tokens):
* On-device OCR path (free tier): **₹0**. It runs on the phone.
* Claude Opus 5.5 vision ($4 input / $20 output per million tokens): about **$0.03 ≈ ₹2.5 per scan**.
  At 40 scans a month per Pro shop that is about ₹100 against ₹299 of revenue. It works, but the margin is thin.
* Ways to lower that cost (owners: product and engineering):
  1. Escalate to the vision model only when the on-device OCR match is not confident.
  2. Evaluate a cheaper model tier against the same scans before switching.
  3. Train an on-device detector on the confirmed scans Milega already stores (`product_photos` plus the shopkeepers' corrections).

## 6. Technology milestones (mapped to the plan)

| When | Milestone |
|---|---|
| Y1 | Current MVP plus: SMS OTP provider, FCM push, offline outbox for stock taps, Play Store release, crash reporting |
| Y1–Y2 | PostgreSQL with PostGIS; job queue for recognition; per-locality search index; product images CDN |
| Y2 | Reorder suggestions from ledger velocity; WhatsApp catalogue share; invoice-scan accuracy work |
| Y3 | Supplier ordering APIs; ONDC seller-side network participant for kiranas; POS/billing integrations (Vyapar-style apps) |
| Y4 | On-device product detector; demand forecasting; credit data pipeline with explicit consent |
| Y5 | Multi-region active-active; brand analytics product with privacy safeguards (k-anonymity on aggregates) |

## 7. Risks and how the product already addresses them

| Risk | Mitigation |
|---|---|
| **Stale stock** (biggest risk) | Freshness labels instead of fake real-time claims; reservations confirmed by the shop; 30-second voice/photo updates; ranking rewards fresh shops |
| Shopkeepers churn after onboarding | Value on day 1 (customers find them); low-stock and reorder features make Milega the shop's stock book |
| Quick-commerce price/speed competition | We compete on *immediacy without delivery fees* and long-tail items (hardware, electrical, stationery) that dark stores do not carry |
| AI mistakes | Human confirmation before any stock change; audit trail; correction UI |
| Data privacy (DPDP Act 2023) | Minimal personal data; phone numbers masked; consent-based credit sharing; India-region hosting |
| Fake / duplicate shops | Admin verification of location before going live; suspension tools; audit log |

## 8. Funding shape (indicative)

* **Pre-seed / Seed (₹4–8 Cr):** Bengaluru density pilot to 2,000 shops. Prove freshness and fulfilment metrics.
* **Series A (~₹40–60 Cr) in Y2:** multi-city expansion, Pro monetisation, data pilots.
* **Series B in Y3–Y4:** supplier ordering and credit. These are the large revenue lines.

**Next 90 days:**
1. Ship this MVP to 50 shops in two Jayanagar blocks.
2. Instrument the metrics in §5.
3. Run a 4-week freshness experiment comparing voice/photo nudges against none.
4. Sign one FMCG distributor for the onboarding channel.
