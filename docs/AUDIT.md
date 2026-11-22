# Milega 2.0 — Repository audit and change report

Date: 22 Nov 2026. Scope: the whole repository (Android app, FastAPI backend, admin console,
migrations, tests, docs) before and after the Milega 2.0 work.

## 1. Baseline (before)

| Area | What existed | Main gaps found |
|---|---|---|
| Backend | FastAPI modular monolith; SQLAlchemy 2 + Alembic (`0001`); OTP auth with roles and shop isolation; append-only inventory ledger with conditional UPDATEs and idempotency keys; reservation state machine with exactly-once transitions; OCR/vision recognition with human confirmation; multilingual catalogue matching | Discovery limited to Milega's own shops; no locality model; search ignored place names; no provenance model for external businesses; 12 categories; no saved shops, reports, product pages; console-only OTP; polling-only notifications; per-process rate limits; no PostGIS/trigram path; no readiness endpoint or provider budgets |
| Android | Kotlin + Compose; customer + shopkeeper modes; CameraX + ML Kit OCR; voice updates; English/Hindi/Kannada | osmdroid tiles; generic Material look; one tab bar for reservations; inventory taps lost when offline after app close; no push; no deep links; no tests |
| Admin | Single static page (stats, shops, catalogue, queue, reservations, audit) | No coverage view, recognition quality, trust & safety, analytics or system health |
| Data | 96-variant catalogue, 7 demo shops near Jayanagar | Not sufficient for Bengaluru-wide discovery (by design; see §3) |
| Tests | 66 backend tests | No Android tests; no migration round-trip; no Places/SMS/push/Redis tests |

Baseline run: **66/66 backend tests passed** before any change.

## 2. What changed (after)

### Backend (`backend/`)
* **Bengaluru localities**: 73 curated neighbourhoods (English, Kannada, alternate spellings) with
  approximate centres; fuzzy search; nearest-locality lookup; Google Places Autocomplete for
  anywhere else in Bengaluru (bounded to the city rectangle, session tokens).
* **39 shop categories** mapped to validated Google place types (types verified against the
  live API) or text queries where Google has no type.
* **Discovery with provenance** (`/api/v1/discover`): Milega merchants and Google-listed
  businesses returned separately. Google results carry `provenance=google`,
  `merchant_state=google_discovered`, `reservable=false`, attribution, straight-line distance.
  Only place IDs are ever stored. Merchants can claim their Google listing (≤500 m from their
  pin; admin confirms); a confirmed claim replaces the Google result. **No name-based merging.**
* **Four trust states** for every shop: verified merchant, unverified merchant (hidden from
  customers by default), Google-discovered business, demo (only when `SHOW_DEMO_DATA`).
* **Search**: understands "near JP Nagar", "in Jayanagar", Kannada locality names, landmarks
  ("near RV College of Engineering", geocoded through Places), shop-category intent
  ("electrical shops"), price limits, sizes, typos, Hindi/Kannada aliases; pagination;
  "updated recently" filter; type-ahead suggestions; anonymised search logs (~1 km rounding).
* **Product pages**: per-shop offers with unit prices (₹/kg, ₹/L, ₹/piece), freshness, other pack
  sizes and substitutes. Unknown prices stay unknown.
* **Saved shops**, **user reports**, **merchant Google-listing claims**, **push device registry**.
* **SMS OTP**: MSG91 (DLT flow) and Twilio Verify providers; our expiry/attempt/rate limits kept;
  production refuses half-configured providers.
* **Push**: FCM HTTP v1 sender with an outbox on `notifications` (claimed with a conditional
  UPDATE, so replicas never double-send; unregistered tokens disabled; app de-duplicates).
* **Redis rate limiter** shared across replicas, falling back to in-process limits if Redis is down.
* **Provider budget**: daily Google Places cap counted in the database across replicas.
* **PostGIS + pg_trgm** (migration `0002`): generated `geography` column + GIST index and
  trigram indexes on PostgreSQL; radius queries use `ST_DWithin` automatically when present.
* **Observability**: `/health/ready`, request metrics, slow-request counter, optional Sentry,
  per-provider usage and error counters.
* **Admin API**: coverage, recognition quality, trust signals, measured analytics, system health,
  report resolution, claim review, duplicate flags on pending shops.

### Android (`android/`)
* New design system (`ui/theme/Theme.kt`): Forest/Green/Cream/Terracotta palette, Manrope (OFL),
  spacing/radius/elevation/motion tokens, light + dark schemes, larger seller typography.
* Navigation: Home · Explore · Search · Saved · Account, state restoration, `milega://` deep links,
  notification-tap routing.
* Home, Explore (Google Maps + clustering + category/radius filters + "search this area" + list
  fallback without a key), Search (suggestions, recent, voice, products/shops tabs), Saved,
  locality picker (GPS optional, curated areas, Google Autocomplete), shop profiles for all four
  states, Google place profile with "Join Milega" claim flow, product comparison page, reports.
* Seller dashboard redesign; **offline outbox** (Room + WorkManager): every stock change is saved
  on the phone first, synced oldest-first with its idempotency key, recovered after process death,
  conflicts parked for the shopkeeper (Retry / Discard), unsynced count shown, safe logout.
* Optional FCM push (from `local.properties`), de-duplicated with the 15-minute polling fallback.
* 481 strings in English, Hindi and Kannada.

### Admin console (`backend/app/admin_ui/index.html`)
Overview with operational alerts · merchant verification (duplicate flags, Google claims, history)
· Bengaluru coverage map · catalogue · review queue · AI recognition quality · reservations ·
trust & safety · measured analytics · system health · audit log. Keyboard shortcuts (1–9, `/`),
light/dark themes, no external scripts.

## 3. Deliberate non-goals (honesty constraints)

* **Not every Bengaluru shop is "indexed".** Google Places returns at most 20 results per request
  and no inventory. Milega shows Google-listed businesses for discovery only; stock, prices and
  reservations exist only for merchants who joined.
* **Google content is not stored** (place IDs only), and responses are not cached.
* **AI never changes stock** without shopkeeper confirmation; AI never invents availability or prices.
* **No payments**: reservations are pay-at-shop.

## 4. Verification performed

| Check | Result |
|---|---|
| Backend test suite | **103 passed** (66 original + 37 new) |
| Migration `0002` on SQLite: upgrade → `alembic check` (models = migrations) → downgrade → upgrade | passed |
| Live Google Places (your key): Whitefield hardware discovery | 20 Google-listed businesses, all non-reservable, attributed |
| Live landmark search "hardware shop near RV College of Engineering" | resolved to "R. V. College of Engineering" via Places |
| Live Kannada search "ಜಯನಗರ ಟಾಟಾ ಉಪ್ಪು" | locality Jayanagar + Tata Salt results |
| Live locality autocomplete "Kalyan Nagar" | Google predictions returned |
| Live end-to-end journey (search → compare → reserve → confirm → collect, double-collect refused) | passed (`.tooling/journey.py`, sample shops) |
| Admin console in browser (login, overview, coverage, system) | rendered, no script errors |
| Android `compileDebugKotlin` / `assembleDebug` | passed |
| Android JVM tests (outbox sync logic) | 7/7 passed |
| Android Robolectric tests (Room DAO, Compose UI) and Roborazzi screenshots | see README "Test status" |

## 5. Not verified / remaining blockers

* **No Android device or emulator** was available (no system image; C: drive full; hardware
  virtualisation reported off). The app has not been launched on a phone. Maps rendering, camera,
  location permission flows, FCM delivery and offline sync on a real device are untested.
* **PostgreSQL/PostGIS** paths are written and migration-guarded but were not executed (no
  PostgreSQL server here). SQLite paths are tested.
* **MSG91 / Twilio / FCM** were tested with mocked HTTP only (no accounts configured).
* **Kannada printed-text OCR**: ML Kit has no Kannada model; Kannada packs are read through
  their English/Hindi text or through the optional vision provider. No on-device Kannada OCR yet.
* **Performance targets** (60 fps, cold start) were **not measured**; no device to benchmark.
* **Accessibility**: 48 dp touch targets, content descriptions, headings and dark mode are
  implemented; TalkBack and large-font passes need a device.
* Your Google Maps key is currently **unrestricted** and was shared in chat: rotate it and create
  separately restricted keys (see the release checklist).
