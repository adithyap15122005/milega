# Milega — Architecture

> Make every local shop digitally discoverable and every local product searchable.

## 1. System overview

```
 ┌──────────────────────────── Android app (Kotlin + Jetpack Compose) ────────────────────────────┐
 │  Customer mode                         │  Shopkeeper mode (large type, 4 big actions)          │
 │  • search / map / compare / reserve    │  • Scan products  → CameraX + ML Kit OCR (on-device)  │
 │  • my reservations, location picker    │  • Inventory +/- (optimistic, idempotent retries)     │
 │                                        │  • Voice update   → Android speech (en/hi/kn-IN)      │
 │  English · हिन्दी · ಕನ್ನಡ (per-app locale) │  • Reservations, history, low-stock alerts            │
 └───────────────────────────────┬─────────────────────────────────────────────────────────────────┘
                                 │ HTTPS / JSON (JWT)        multipart photo + OCR lines
 ┌───────────────────────────────▼──────────── FastAPI modular monolith ──────────────────────────┐
 │ auth (phone OTP, JWT, roles) · shops · catalogue · inventory ledger · search · reservations    │
 │ recognition pipeline (provider interface) · voice intent parser · notifications · admin · audit │
 │ background: reservation expiry sweeper · recognition jobs (FastAPI BackgroundTasks → queue)    │
 └───────┬──────────────────────────┬───────────────────────────────┬─────────────────────────────┘
         │                          │                               │
   PostgreSQL (SQLite in dev)   Object storage (local / S3)    Vision provider (optional)
   ledger + balances + geo      re-encoded JPEGs, no EXIF      Claude vision, structured JSON
         │
   Admin dashboard (/admin, static page on the same API)
```

**Why a modular monolith:** one deployable, one database transaction boundary for
inventory + reservations (correctness matters more than service count at this
stage). Modules are separated by folder (`app/services/*`) and talk through plain
function calls, so any one of them (search, recognition) can be lifted into its own
service later without changing the data model.

## 2. Repository layout

```
backend/
  app/
    main.py            app factory, middleware, error handling, sweeper
    config.py          env-based settings (refuses unsafe production config)
    db.py              engine/session, UTC datetimes, SQLite BEGIN IMMEDIATE
    models.py          18 tables (see §3)
    security.py        OTP hashing, JWT, role + shop-isolation dependencies
    ratelimit.py       sliding-window limiter (swap for Redis at scale)
    routers/           auth, shops, inventory(+catalogue), commerce(search,
                       reservations, voice, notifications), recognition, admin, media
    services/
      inventory.py     THE ledger: conditional UPDATE … RETURNING + event row
      reservations.py  state machine, holds, expiry, exactly-once transitions
      catalog.py       query parsing, catalogue matching, provisional products
      search.py        nearby offers for the same variant, honest freshness
      geo.py           bbox + haversine, IST opening hours
      voice.py         en/hi/kn command parser → proposal (never applies)
      units.py         milli-quantities, pack-size parsing (1kg == 1000 g)
      text.py          multilingual tokenising, synonyms, typo tolerance
      storage.py       image validation, EXIF stripping, quality check
      recognition/     types (validated), providers, OCR parsing, pipeline
    admin_ui/index.html
    seed.py, seed_data.py   96-variant Indian retail catalogue + demo shops
  migrations/          Alembic (0001_initial_schema)
  tests/               66 tests
android/app/src/main/java/com/milega/app/
  data/                DTOs, MilegaApi (OkHttp), SessionStore (DataStore), NotificationWorker
  util/                Media (compress), Ocr (ML Kit), Locations, Format
  ui/                  theme, components, auth, customer/*, shop/*, navigation
```

## 3. Data model

| Area | Tables | Notes |
|---|---|---|
| Identity | `users`, `otp_challenges` | phone is the identity; OTP stored as HMAC, 5 attempts max |
| Shops | `shops`, `shop_hours`, `categories` | `status`: pending → active (admin verifies location) / suspended |
| Catalogue | `products`, `product_variants`, `product_photos`, `product_verifications` | one canonical variant (e.g. *Tata Salt 1 kg*) shared by all shops; `provisional` variants from shopkeepers go to a review queue; admins merge duplicates |
| Inventory | `shop_listings`, `inventory_balances`, `inventory_events` | listing = shop's price for a variant; balance = on_hand / reserved (milli-units); events = append-only ledger with `on_hand_after` |
| AI | `recognition_jobs`, `recognition_candidates` | every suggestion, its confidence and what the person finally applied |
| Commerce | `reservations`, `reservation_items` | short human code, expiry, idempotency key |
| Ops | `notifications`, `audit_logs` | polled by the app; admin actions audited |

Quantities are integer **milli-units** (`1 pc = 1000`, `1.5 kg = 1500`) so loose goods
work without float drift; money is integer **paise**.

## 4. Inventory engine (the trust layer)

Every stock change goes through `services/inventory.apply_change`:

```sql
UPDATE inventory_balances
   SET on_hand_milli = on_hand_milli + :d_on_hand,
       reserved_milli = reserved_milli + :d_reserved, version = version + 1, ...
 WHERE listing_id = :id
   AND on_hand_milli + :d_on_hand >= 0                       -- never negative
   AND (:hold = 0 OR on_hand_milli - reserved_milli >= :q)   -- reservation holds
RETURNING on_hand_milli, reserved_milli;
```

…followed in the same transaction by an `inventory_events` row (type, source, actor,
verification, reference, resulting balances). If no row is returned the change is
rejected with `409 insufficient_stock`. This is safe under PostgreSQL READ COMMITTED
(the second writer re-evaluates the `WHERE` after the first commits), so two
customers can never both reserve the last packet (tested with 8 concurrent threads).

* **Idempotency:** client sends `Idempotency-Key`; the key is unique in the ledger and
  the change runs in a SAVEPOINT, so a retried tap or a dropped connection applies once.
* **Freshness:** `stock_confirmed_at` only moves when a *person at the shop* sets or
  confirms stock (manual, voice, scans). Customers see *In stock (2 h ago)*, *Likely
  (1–3 days)*, *Ask shop to confirm (>3 days)* or *Out of stock* — never "real-time".
* **Overselling outside the app:** a shopkeeper can sell below the reserved amount
  (reality wins); the listing is flagged *over-reserved* and the app asks them to
  review reservations.

## 5. Reservation state machine

```
           confirm            collected
 pending ───────────▶ confirmed ─────────▶ collected   (on_hand -= q, reserved -= q)
    │                    │
    └──── reject / cancel / expire ────▶ rejected | cancelled | expired  (reserved -= q)
```

Each transition is `UPDATE reservations SET status=:new WHERE id=:id AND status IN (:allowed)`;
only the winner releases or collects stock. A sweeper expires overdue holds every 30 s
and the shop/customer screens expire lazily on read.

## 6. Photo → inventory pipeline

1. **Phone:** CameraX capture → downscale to ≤1600 px JPEG (~300 KB) → ML Kit text
   recognition (Latin + Devanagari, offline) → upload photo + OCR lines with boxes.
   The compressed file and a `client_request_id` are kept so *Retry upload* never
   creates a duplicate job.
2. **Server stage 1:** validate the image (real JPEG/PNG/WEBP, size, pixels), re-encode
   (drops EXIF/GPS), blur/exposure check, store privately.
3. **Provider** (interface `analyze(image, mode, ocr_lines) -> ProviderResult`):
   * `on_device_ocr` (free default): clusters OCR lines by position (≈ one pack),
     rebuilds invoice rows from coordinates; never estimates counts.
   * `claude_vision` (when an Anthropic key is configured): structured JSON output
     constrained by a JSON schema, then re-validated with Pydantic; counts are only
     pre-filled when the model marks them reliable; refusals/timeouts become clear
     failures.
4. **Matching:** each detection is matched to the catalogue (brand, name tokens,
   synonyms incl. Hindi/Kannada aliases, typo tolerance). A *different pack size is
   never offered as the same product*; unknown size → all sizes offered.
   Confidence = min(provider, match) with a margin rule; only **high** confidence is
   pre-selected — medium/low show options.
5. **Review (human in the loop):** the shopkeeper picks/corrects products, enters or
   confirms quantities and prices, or adds new products (become `provisional`).
6. **Confirm:** the job is claimed exactly once, stock is applied through the ledger
   with `source=photo_scan | shelf_scan | invoice_scan` and a reference to the job.
   **AI output never changes stock on its own.**

Modes: **New stock** (adds), **Shelf check** (sets the recounted number only for rows
the shopkeeper keeps ticked), **Supplier bill** (adds invoice quantities, pre-fills
unit price).

## 7. Search

`parse_query` extracts pack size (`5kg`, `2-inch`, `25W`), price limits (`under ₹100`,
`100 से कम`), category words (`hardware shops`), and drops filler (`near me`,
`where can I get`, `मिलेगा`). Variants are scored, weak partial matches are dropped,
then offers are fetched only from **active, verified** shops within the radius and
sorted by relevance / distance / price / availability. Distances are straight-line and
labelled as such; directions open the phone's maps app.

## 8. Security & privacy

* Phone OTP (dev mode shows the code; production refuses to start with console SMS,
  default JWT secret or SQLite). JWT with `token_version` for log-out-everywhere.
* Role checks (`customer`, `shopkeeper`, `admin`) and **shop-level isolation**: every
  shop-scoped query filters by the caller's shop id; other shops' rows return 404.
* Rate limits on OTP, search, reservations, scans, voice.
* Uploads re-encoded (no EXIF/GPS, no polyglot payloads); recognition photos are
  private to the shop and admins.
* Customers' phone numbers are never shown to shops (last 4 digits only).
* Audit log for admin and sensitive shop actions.

## 9. Scaling path

| Stage | Change |
|---|---|
| ~1k shops (now) | single API + managed PostgreSQL; local/S3 storage |
| ~20k shops | Redis for rate limits & caching; recognition on a job queue (RQ/Celery/SQS) with autoscaled workers; FCM push instead of polling; CDN for catalogue images |
| ~100k shops | PostGIS `geography` + GIST index for nearby queries; search index (OpenSearch) per city with Indic analyzers; read replicas; partition `inventory_events` by month |
| 300k+ shops | split search and recognition into services; city-sharded write paths; on-device detector model trained on confirmed scans to cut vision cost |

The geo service is isolated in `services/geo.py`, search in `services/search.py`, and
the vision provider behind an interface, so each step is a contained change.
