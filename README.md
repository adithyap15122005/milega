# Milega — every local shop, searchable

**Milega** ("you'll find it") is an AI-assisted hyperlocal commerce platform for Indian
neighbourhood retail: kirana, stationery, electrical, hardware and mobile-accessory shops.

* **Shopkeepers** use an Android phone to put their stock online by **photographing
  products**, by speaking (**English, हिन्दी, ಕನ್ನಡ**) or by tapping +/-. No barcode
  scanner, computer or POS is needed.
* **Customers** search "*Aashirvaad atta 5 kg near me*", compare price and freshness
  across nearby shops, and **reserve** before walking over.
* **Admins** verify shop locations, clean the shared catalogue and watch reservations.

AI suggests and a person confirms. Stock never changes from an AI guess, and
customers never see "real-time" claims the data can't support.

| Part | Tech | Where |
|---|---|---|
| Android app (customer + shopkeeper modes) | Kotlin, Jetpack Compose, CameraX, ML Kit OCR (on-device), Android speech, OpenStreetMap | [`android/`](android) |
| API (modular monolith) | Python 3.12+, FastAPI, SQLAlchemy 2, Alembic, PostgreSQL / SQLite | [`backend/`](backend) |
| Admin dashboard | Static page served by the API at `/admin` | [`backend/app/admin_ui`](backend/app/admin_ui/index.html) |
| Docs | Architecture, scale plan to ₹100 Cr | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/SCALE_PLAN.md`](docs/SCALE_PLAN.md) |

---

## 1. Run the backend (Windows PowerShell)

Requires Python 3.12+.

```powershell
cd "D:\local commerce\backend"
```

```powershell
python -m venv .venv
```

```powershell
.venv\Scripts\python -m pip install -r requirements-dev.txt
```

```powershell
.venv\Scripts\python -m app.seed
```

```powershell
.venv\Scripts\python -m uvicorn app.asgi:app --host 0.0.0.0 --port 8000
```

* API docs: <http://localhost:8000/docs> · Admin: <http://localhost:8000/admin> · Health: `/health`
* `app.seed` loads a **96-product Indian retail catalogue** (with Hindi/Kannada
  aliases) and **7 clearly-labelled sample shops** around Jayanagar, Bengaluru. The
  shops are flagged `is_demo` and show a "Sample shop" badge. Seed data is refused when
  `APP_ENV=production`; run `python -m app.seed --catalog` there.
* Development login: any 10-digit mobile. With no SMS provider configured, the OTP is
  shown on screen and in the server log, clearly marked *development mode*.

| Demo account | Mobile |
|---|---|
| Admin | 90000 00001 |
| Customer | 90000 00100 |
| Shopkeepers (Sri Lakshmi Provision Stores, …) | 90000 00011 – 90000 00016 |
| Shop waiting for verification (New Balaji Stores) | 90000 00017 |

Configuration lives in environment variables. Copy `backend/.env.example` to
`backend/.env` to change them. The server refuses to start in `APP_ENV=production` if
it finds a default JWT secret, console OTPs or SQLite.

### PostgreSQL (production-like)

```powershell
docker compose up --build
```

```powershell
docker compose exec api python -m app.seed
```

Migrations: `alembic upgrade head` runs automatically in the container. To run it
manually, set `DATABASE_URL` and run `.venv\Scripts\alembic upgrade head`.

## 2. Build the Android app

Requires Android Studio (bundled JDK 21) and the Android SDK (platform 36.1).

* **Android Studio:** open the `android/` folder, wait for Gradle sync, then run on an emulator or phone.
* **Command line:**

```powershell
cd "D:\local commerce\android"
```

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to `android\app\build\outputs\apk\debug\app-debug.apk`. The debug
APK is about 68 MB because it bundles both offline OCR models and is not shrunk. A
release build with R8 is much smaller.

**Low on C: drive space?** Gradle caches about 1.5 GB. Point it at another drive
before building:

```powershell
$env:GRADLE_USER_HOME = "D:\local commerce\.tooling\gradle-home"
```

**Pointing the app at your server:** the debug build defaults to `http://10.0.2.2:8000/`,
which is the host machine as seen from the Android emulator. On a real phone on the
same Wi-Fi, open **Login → Developer settings** (or **Account → Developer settings**)
and enter `http://<your-PC-IP>:8000/`. Debug builds allow plain HTTP; release builds
require HTTPS.

## 3. Photo-based recognition

Two interchangeable providers sit behind one interface (`backend/app/services/recognition/providers.py`):

| Provider | When | What it does |
|---|---|---|
| `on_device_ocr` (default, free) | No API key | ML Kit reads pack text **on the phone** (Latin + Devanagari); the server groups text by package and matches it to the catalogue. **It never estimates counts**: the shopkeeper enters quantities. |
| `claude_vision` | `ANTHROPIC_API_KEY` set (or `VISION_PROVIDER=claude`) | Claude (`VISION_MODEL`, default `claude-opus-5-5`) returns JSON constrained by a schema. The server validates it again. Counts are pre-filled **only** when the model marks them reliable. Refusal fallback is enabled. |

**To test on a phone:** log in as a shopkeeper and tap **Scan products** → **New stock**.
Photograph 3–6 packs with the labels facing the camera, such as Tata Salt, Maggi or
Parle-G. Then **Check and save**: pick or correct each product, enter quantities and
save. Also try **Shelf check** and **Supplier bill** (a printed bill photographed flat).

**To test without a phone:** `backend/tests/test_recognition.py` sends synthetic photos
plus OCR lines through the real upload → recognition → review → confirm path, for every
mode and failure case.

## 4. End-to-end workflow (all four apps against one server)

1. **Shopkeeper:** log in with a new number → *I own a shop* → register with GPS →
   *Scan products* (or *Add product*) → save stock.
2. **Admin:** open `/admin` → *Shops* → **Verify & publish** the new shop. Unverified
   shops never appear in customer search.
3. **Customer:** log in with another number → set location → search "*Tata salt 1 kg*" →
   see the shop with price, distance and *In stock · updated just now* → **Reserve**.
4. **Shopkeeper:** *Reservations* shows the request with a code → **Confirm** →
   **Collected**. On-hand stock drops by the reserved quantity exactly once.
5. **Shopkeeper:** *My inventory* → **−** or **Set count / Out of stock**. The
   customer's search reflects it right away.
6. **Voice:** *Voice update* → "पांच टाटा नमक बिक गए" → confirm the proposal.

## 5. Tests

```powershell
cd "D:\local commerce\backend"
```

```powershell
.venv\Scripts\python -m pytest
```

There are **66 tests**. They cover:
* OTP auth, role checks and shop isolation.
* The inventory ledger: event/balance consistency, idempotent retries, decimal kg vs whole pieces, overselling.
* Reservations: full lifecycle, expiry release, exactly-once transitions, and **8 concurrent customers for the last item → exactly 1 succeeds**.
* Search: same-variant comparison, freshness labels, price/size filters, Hindi/Kannada queries and typos.
* Recognition: suggestions never change stock, ambiguous packs give options, a blurry photo fails cleanly, invoice row parsing, shelf recount, Claude output validation and refusal handling.
* Voice commands in English, Hindi and Kannada.

## 6. Limitations (honest list)

* **OTP delivery:** only a development console provider. Plug in MSG91, Gupshup or
  Twilio Verify in `routers/auth.py::send_otp` before launch.
* **Notifications:** polled. The app polls while open, and WorkManager checks every
  15 minutes in the background. FCM push is the next step.
* **Offline:** stock taps update the screen instantly and retry safely with idempotency
  keys, but there is no persistent offline queue yet. Taps made with no network are
  retried only while the app stays open.
* **Free recognition** identifies products from **printed text only**. Packs with no
  legible text, or loose goods, need the vision provider or manual entry. It never counts items.
* **Kannada OCR:** ML Kit has no Kannada script model. Kannada works in voice, search
  and UI, but on-device OCR reads Kannada packs only through their English or Hindi text.
* **Geo search:** a bounding box plus haversine query, which is fine for tens of
  thousands of shops. The PostGIS migration path is in `docs/ARCHITECTURE.md`. Not yet
  load-tested on PostgreSQL in this environment (Docker was not running), though the
  Alembic migration was generated and verified on SQLite.
* **Maps:** OpenStreetMap tiles are fine for development. Use a commercial tile
  provider for production traffic. Distances are straight-line and labelled as such.
* **Android app:** compiled, but not yet run on a device or emulator in this
  environment, because no emulator image was installed. Check the main flows on a
  phone before a pilot.
* **Rate limiter:** in-memory, per process. Move to Redis when running several API replicas.

## 7. Next priorities

1. Pilot readiness: real SMS OTP, FCM push, crash reporting, Play Store internal testing track.
2. A persistent offline outbox for stock changes (Room plus WorkManager).
3. Escalate from OCR to vision only when the OCR match is not confident, to control AI cost.
4. Reorder suggestions from ledger velocity, which leads into supplier ordering (see the scale plan).
5. PostgreSQL + PostGIS in staging, with load tests for search and reservations.
6. Collect confirmed scans as training data for an on-device product detector.
