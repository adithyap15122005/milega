# Milega — release readiness checklist

Tick each item before a public pilot. Items marked **(blocker)** must be done.

## Keys and secrets
- [ ] **(blocker)** Rotate the Google Maps key that was shared during development.
- [ ] **(blocker)** Create an **Android** key: API restriction *Maps SDK for Android*; application
      restriction *Android apps* → package `com.milega.app` + release signing SHA-1
      (and the debug SHA-1 `D6:7F:99:63:CC:BF:17:25:E0:A4:71:4C:D0:A3:2A:3B:C5:01:BF:73` for development builds).
      Put it in `android/local.properties` as `MAPS_API_KEY` (or CI secret).
- [ ] **(blocker)** Create a **server** key: API restriction *Places API (New)*; application
      restriction *IP addresses* of the API servers. Set `GOOGLE_PLACES_SERVER_API_KEY`.
- [ ] Google Cloud: quota on Places API (New) per day, billing budget + alerts; set
      `PLACES_DAILY_REQUEST_LIMIT` below the quota.
- [ ] `JWT_SECRET` ≥ 32 random characters; `EXPOSE_DEV_OTP=false`; secrets in a secret manager.
- [ ] No secrets in git history (`git log -p | grep -E "AIza|sk-ant-"` returns nothing).

## Backend
- [ ] **(blocker)** PostgreSQL 15+ with PostGIS and pg_trgm available; run `alembic upgrade head`;
      confirm `shops.geog` exists (`/api/v1/admin/system` → database `postgresql`).
- [ ] **(blocker)** SMS: `SMS_PROVIDER=msg91` (DLT-registered template containing the OTP) or
      `twilio_verify`; send a real OTP to a test number.
- [ ] `REDIS_URL` set when running more than one API replica; `/health/ready` shows rate limiter healthy.
- [ ] FCM: service-account JSON mounted, `FCM_PROJECT_ID` + `FCM_CREDENTIALS_FILE` set; send a test reservation.
- [ ] `APP_ENV=production` (the server refuses unsafe settings); `SHOW_DEMO_DATA=false`.
- [ ] `python -m app.seed --catalog` (catalogue + categories + localities only, no demo shops).
- [ ] Backups: daily PostgreSQL snapshots + point-in-time recovery; restore drill done once.
- [ ] Migration rollback rehearsed on a copy (`alembic downgrade 0001` then `upgrade head`).
- [ ] `SENTRY_DSN` (or equivalent) set; alerting on 5xx rate, stuck recognition jobs, push failures.
- [ ] HTTPS only; CORS restricted to the admin origin.
- [ ] Load test search, discover and reservations at expected peak (not yet done).

## Android
- [ ] **(blocker)** Run on at least 3 real phones (one low-end, Android 8–10) and one tablet.
- [ ] Release signing config; `versionCode` bumped; R8 build installs and runs.
- [ ] Maps: Explore loads tiles, clustering works, "search this area" refreshes; no-key build shows list fallback.
- [ ] Location: works with permission denied (locality picker), coarse only, and fine.
- [ ] Offline: airplane mode → change stock → force-stop app → reboot → reconnect → change appears
      once on the server (check `/api/v1/inventory/listings/{id}/events`).
- [ ] Push: reservation notification arrives; tapping opens Reservations; no duplicate from polling.
- [ ] Camera scan in all three modes; Hindi and Kannada voice commands.
- [ ] TalkBack pass on Home, Search, Shop, Reserve, Seller dashboard; font scale 1.3×/2.0× pass.
- [ ] Dark mode pass.
- [ ] Measure cold start and scroll performance (Macrobenchmark) on the low-end phone.
- [ ] Play Console: privacy policy (location, phone number, photos), data-safety form, content rating.

## Operations
- [ ] Admin accounts listed in `ADMIN_PHONES`; at least two admins.
- [ ] Merchant verification SOP: check pin on map, photo of storefront, owner phone; review duplicate flags.
- [ ] Report handling SLA (e.g. 48 h) and escalation for fake shops.
- [ ] Customer support contact in the app and Play listing.
