"""Bengaluru discovery: localities, location-aware search, Google provenance, product pages, saved, reports."""

from __future__ import annotations

import json

import httpx
import pytest

from app.models import SearchLog, Shop
from app.services.places import PlacesClient, PlacesError
from app.services.usage import UsageMeter

JAYANAGAR = (12.9299, 77.5826)
WHITEFIELD = (12.9698, 77.7500)


class FakePlaces:
    """Stands in for Google Places in tests: records calls, returns canned results."""

    def __init__(self, places=None, fail: str | None = None):
        self.calls: list[tuple] = []
        self.places = places or []
        self.fail = fail

    def _maybe_fail(self):
        if self.fail:
            raise PlacesError(self.fail)

    def search_nearby(self, lat, lng, radius_m, *, types, max_results=20, language="en"):
        self.calls.append(("nearby", types, radius_m))
        self._maybe_fail()
        return {"places": [dict(p) for p in self.places], "next_page_token": None}

    def search_text(self, query, **kw):
        self.calls.append(("text", query, kw.get("restrict_to_city")))
        self._maybe_fail()
        return {"places": [dict(p) for p in self.places], "next_page_token": None}

    def details(self, place_id, **kw):
        self.calls.append(("details", place_id))
        self._maybe_fail()
        for p in self.places:
            if p["place_id"] == place_id:
                return {**p, "rating": 4.3, "user_rating_count": 120, "open_now": True, "weekday_hours": []}
        raise PlacesError("not_found")

    def autocomplete(self, text, **kw):
        self.calls.append(("autocomplete", text))
        self._maybe_fail()
        return [{"place_id": "ChIJ-auto", "text": f"{text}, Bengaluru", "main_text": text, "secondary_text": "Bengaluru"}]


def google_place(pid: str, name: str, lat: float, lng: float, primary="hardware_store") -> dict:
    return {"place_id": pid, "name": name, "address": "Whitefield, Bengaluru", "full_address": "Whitefield, Bengaluru",
            "lat": lat, "lng": lng, "primary_type": primary, "type_label": "Hardware store", "category": "hardware",
            "maps_uri": f"https://maps.google.com/?cid={pid}", "business_status": "OPERATIONAL",
            "provenance": "google", "merchant_state": "google_discovered", "reservable": False}


def enable_places(app, fake):
    app.state.places = fake
    app.state.settings = app.state.settings.with_overrides(google_places_api_key="test-key")


# ------------------------------------------------------------------ localities


def test_localities_fuzzy_kannada_and_aliases(client):
    names = lambda q: [l["slug"] for l in client.get("/api/v1/localities", params={"q": q}).json()["localities"]]
    assert names("jp nagar")[0] == "jp-nagar"
    assert names("koramangla")[0] == "koramangala"      # typo
    assert names("ಜಯನಗರ")[0] == "jayanagar"             # Kannada
    assert names("halasuru")[0] == "ulsoor"             # alternate name
    everything = client.get("/api/v1/localities").json()
    assert len(everything["localities"]) >= 31
    assert everything["google_status"] == "skipped"     # Google not configured in tests


def test_locality_autocomplete_uses_google_when_configured(app, client):
    fake = FakePlaces()
    enable_places(app, fake)
    body = client.get("/api/v1/localities", params={"q": "Kalyan Nagar"}).json()
    assert body["google_status"] == "ok"
    assert body["google_predictions"][0]["place_id"] == "ChIJ-auto"
    assert body["attribution"]["provider"] == "Google Maps"


def test_nearest_locality(client):
    assert client.get("/api/v1/localities/nearest", params={"lat": 12.9301, "lng": 77.5830}).json()["locality"]["slug"] == "jayanagar"
    assert client.get("/api/v1/localities/nearest", params={"lat": 28.6, "lng": 77.2}).json()["locality"] is None


# -------------------------------------------------------------- location search


def test_search_uses_locality_named_in_query(api, client, shop_setup):
    r = client.get("/api/v1/search", params={"q": "Where can I find Tata Salt 1 kg in Jayanagar?"})
    body = r.json()
    assert r.status_code == 200, body
    assert body["location"]["source"] == "query_locality"
    assert body["location"]["locality"]["slug"] == "jayanagar"
    assert body["intent"] == "product"
    assert body["results"][0]["variant"]["display_name"] == "Tata Salt 1 kg"
    assert body["results"][0]["offers"][0]["distance_m"] is not None
    assert body["results"][0]["offers"][0]["unit_price"] == {"paise": 2700, "per": "kg"}


def test_size_and_locality_parsed_together(client, shop_setup):
    body = client.get("/api/v1/search", params={"q": "5 kg atta near JP Nagar"}).json()
    assert body["location"]["locality"]["slug"] == "jp-nagar"
    assert body["interpretation"]["size"] == "5 kg"
    assert "atta" in body["interpretation"]["terms"]


def test_shop_category_intent(api, admin, client):
    owner = api.login("9876500101", "Suresh")
    shop = api.register_shop(owner, name="Sri Ganesh Electricals", categories=("electrical", "hardware"))
    api.verify_shop(admin, shop["id"])
    body = client.get("/api/v1/search", params={"q": "Electrical shops in Jayanagar"}).json()
    assert body["intent"] == "shop_category"
    assert body["interpretation"]["shop_category"] == "electrical"
    assert [s["name"] for s in body["shops"]] == ["Sri Ganesh Electricals"]
    assert body["shops"][0]["merchant_state"] == "verified_merchant"
    assert body["external_hint"]["category"] == "electrical"


def test_landmark_is_geocoded_through_places(app, client, shop_setup):
    fake = FakePlaces([google_place("ChIJ-rvce", "RV College of Engineering", 12.9237, 77.4987)])
    enable_places(app, fake)
    body = client.get("/api/v1/search", params={"q": "hardware shop near RV College of Engineering"}).json()
    assert body["location"]["source"] == "landmark"
    assert body["location"]["landmark"]["name"] == "RV College of Engineering"
    assert ("text", "rv college of engineering, Bengaluru", True) in fake.calls


def test_landmark_without_places_is_reported_not_guessed(client, shop_setup):
    body = client.get("/api/v1/search", params={"q": "salt near RV College of Engineering"}).json()
    assert body["location"]["source"] == "none"
    assert body["location"]["unresolved_landmark"] == "rv college of engineering"


def test_search_pagination_and_fresh_filter(api, client, shop_setup):
    body = client.get("/api/v1/search", params={"q": "maggi", "lat": JAYANAGAR[0], "lng": JAYANAGAR[1], "limit": 1}).json()
    assert body["total_results"] >= 1 and len(body["results"]) == 1
    fresh = client.get("/api/v1/search", params={"q": "tata salt", "lat": JAYANAGAR[0], "lng": JAYANAGAR[1],
                                                 "fresh_only": True}).json()
    assert all(o["availability"]["status"] in ("in_stock", "likely") for r in fresh["results"] for o in r["offers"])


def test_search_is_logged_with_rounded_coordinates(app, client, shop_setup):
    client.get("/api/v1/search", params={"q": "tata salt", "lat": 12.929912, "lng": 77.582634})
    with app.state.db.session() as db:
        row = db.query(SearchLog).order_by(SearchLog.id.desc()).first()
        assert row.query == "tata salt" and row.lat_r == 12.93 and row.lng_r == 77.58


def test_suggest(client, shop_setup):
    body = client.get("/api/v1/search/suggest", params={"q": "tata"}).json()
    assert any(p["display_name"].startswith("Tata Salt") for p in body["products"])
    assert client.get("/api/v1/search/suggest", params={"q": "jaya"}).json()["localities"][0]["slug"] == "jayanagar"
    assert client.get("/api/v1/search/suggest", params={"q": "pharmacy"}).json()["categories"][0]["slug"] == "pharmacy"


# ------------------------------------------------------------------ discovery


def test_discover_separates_google_from_milega(api, admin, app, client, shop_setup):
    fake = FakePlaces([google_place("ChIJ-a", "New Mahendra Hardware", WHITEFIELD[0], WHITEFIELD[1] + 0.002),
                       google_place("ChIJ-b", "Closed Store", WHITEFIELD[0], WHITEFIELD[1])])
    fake.places[1]["business_status"] = "CLOSED_PERMANENTLY"
    enable_places(app, fake)
    body = client.get("/api/v1/discover", params={"lat": WHITEFIELD[0], "lng": WHITEFIELD[1], "category": "hardware"}).json()
    assert body["external_status"] == "ok"
    assert [p["name"] for p in body["external"]] == ["New Mahendra Hardware"]
    ext = body["external"][0]
    assert ext["provenance"] == "google" and ext["reservable"] is False and ext["merchant_state"] == "google_discovered"
    assert ext["distance_m"] > 0
    assert body["attribution"]["provider"] == "Google Maps"
    assert fake.calls[0][0] == "nearby" and "hardware_store" in fake.calls[0][1]
    # Categories without a Google type fall back to text search.
    client.get("/api/v1/discover", params={"lat": WHITEFIELD[0], "lng": WHITEFIELD[1], "category": "paint"})
    assert fake.calls[-1][0] == "text" and "paint shop" in fake.calls[-1][1]


def test_discover_without_google_still_returns_merchants(client, shop_setup):
    body = client.get("/api/v1/discover", params={"lat": JAYANAGAR[0], "lng": JAYANAGAR[1]}).json()
    assert body["external_status"] == "not_configured"
    assert body["external"] == []
    assert body["merchants"][0]["provenance"] == "milega"
    assert body["merchants"][0]["products_in_stock"] == 2


def test_discover_provider_failure_degrades(app, client, shop_setup):
    enable_places(app, FakePlaces(fail="unavailable"))
    body = client.get("/api/v1/discover", params={"lat": JAYANAGAR[0], "lng": JAYANAGAR[1]}).json()
    assert body["external_status"] == "unavailable"
    assert body["merchants"]


def test_discover_outside_bengaluru_skips_google(app, client):
    fake = FakePlaces([google_place("ChIJ-x", "Delhi Store", 28.6, 77.2)])
    enable_places(app, fake)
    body = client.get("/api/v1/discover", params={"lat": 28.6, "lng": 77.2}).json()
    assert body["external_status"] == "outside_service_area" and fake.calls == []


def test_external_place_detail_never_offers_reservations(app, client):
    enable_places(app, FakePlaces([google_place("ChIJ-a", "New Mahendra Hardware", *WHITEFIELD)]))
    body = client.get("/api/v1/places/ChIJ-a", params={"lat": WHITEFIELD[0], "lng": WHITEFIELD[1]}).json()
    assert body["place"]["reservable"] is False
    assert body["place"]["rating"] == 4.3
    assert body["milega_shop_id"] is None
    assert client.get("/api/v1/places/ChIJ-missing").status_code == 404


def test_merchant_claim_is_source_aware(api, admin, app, client, shop_setup):
    fake = FakePlaces([google_place("ChIJ-mine", "Test Kirana", JAYANAGAR[0] + 0.0005, JAYANAGAR[1]),
                       google_place("ChIJ-far", "Far Away", JAYANAGAR[0] + 0.05, JAYANAGAR[1])])
    enable_places(app, fake)
    owner, shop = shop_setup["owner"], shop_setup["shop"]
    assert client.post("/api/v1/shops/mine/google-place", headers=owner, json={"place_id": "ChIJ-far"}).json()["error"]["code"] == "place_too_far"
    r = client.post("/api/v1/shops/mine/google-place", headers=owner, json={"place_id": "ChIJ-mine"})
    assert r.json()["place_link_status"] == "pending"
    # Pending claim: Google result still shown separately (no merge on a mere claim).
    disc = client.get("/api/v1/discover", params={"lat": JAYANAGAR[0], "lng": JAYANAGAR[1]}).json()
    assert "ChIJ-mine" in [p["place_id"] for p in disc["external"]]
    r = client.post(f"/api/v1/admin/shops/{shop['id']}/place-link", headers=admin, json={"approve": True})
    assert r.json()["place_link_status"] == "verified"
    disc = client.get("/api/v1/discover", params={"lat": JAYANAGAR[0], "lng": JAYANAGAR[1]}).json()
    assert "ChIJ-mine" not in [p["place_id"] for p in disc["external"]]
    assert disc["merchants"][0]["google_place_id"] == "ChIJ-mine"
    assert client.get("/api/v1/places/ChIJ-mine").json()["milega_shop_id"] == shop["id"]
    # A second merchant cannot claim the same Google listing.
    other = api.login("9876500102", "Imposter")
    api.register_shop(other, name="Test Kirana Copy")
    r = client.post("/api/v1/shops/mine/google-place", headers=other, json={"place_id": "ChIJ-mine"})
    assert r.status_code == 409 and r.json()["error"]["code"] == "place_already_claimed"


def test_demo_shops_hidden_when_demo_data_disabled(app, client, shop_setup, api):
    with app.state.db.session() as db:
        db.get(Shop, shop_setup["shop"]["id"]).is_demo = True
    params = {"q": "tata salt", "lat": JAYANAGAR[0], "lng": JAYANAGAR[1]}
    shown = client.get("/api/v1/search", params=params).json()
    assert shown["results"][0]["offers"][0]["shop"]["merchant_state"] == "demo"
    app.state.settings = app.state.settings.with_overrides(show_demo_data=False)
    assert client.get("/api/v1/search", params=params).json()["results"] == []
    customer = api.login("9876500199", "Asha")
    r = client.post("/api/v1/reservations", headers={**customer, "Idempotency-Key": "demo-1"},
                    json={"listing_id": shop_setup["salt"]["id"], "quantity": 1})
    assert r.status_code == 409 and r.json()["error"]["code"] == "shop_unavailable"


# -------------------------------------------------------------- product pages


def test_product_page_offers_unit_prices_and_other_sizes(api, client, shop_setup):
    owner = shop_setup["owner"]
    api.add_listing(owner, "maggi masala noodles 280 g", 4, 5600)
    variant = shop_setup["maggi"]["variant_id"]
    body = client.get(f"/api/v1/products/{variant}", params={"lat": JAYANAGAR[0], "lng": JAYANAGAR[1]}).json()
    assert body["variant"]["display_name"].startswith("Maggi")
    offer = body["offers"][0]
    assert offer["price_paise"] == 1500
    assert offer["unit_price"] == {"paise": round(1500 * 1000 / 70), "per": "kg"}
    sizes = {s["variant"]["size_label"]: s for s in body["other_sizes"]}
    assert sizes["280 g"]["min_price_paise"] == 5600 and sizes["280 g"]["in_stock_count"] == 1
    assert "560 g" in sizes and sizes["560 g"]["shop_count"] == 0  # known size, no seller: no invented price
    assert sizes["560 g"]["min_price_paise"] is None
    assert client.get("/api/v1/products/999999").status_code == 404


def test_available_nearby(client, shop_setup):
    items = client.get("/api/v1/products/nearby", params={"lat": JAYANAGAR[0], "lng": JAYANAGAR[1]}).json()["items"]
    names = {i["variant"]["display_name"] for i in items}
    assert "Tata Salt 1 kg" in names


# --------------------------------------------------------------- saved / reports


def test_saved_shops_and_places(api, client, shop_setup):
    me = api.login("9876500300", "Meera")
    shop_id = shop_setup["shop"]["id"]
    assert client.post("/api/v1/saved", headers=me, json={"shop_id": shop_id}).status_code == 201
    assert client.post("/api/v1/saved", headers=me, json={"shop_id": shop_id}).status_code == 201  # idempotent
    client.post("/api/v1/saved", headers=me, json={"place_id": "ChIJ-saved"})
    assert client.post("/api/v1/saved", headers=me, json={}).status_code == 422
    body = client.get("/api/v1/saved", headers=me).json()
    assert [s["id"] for s in body["shops"]] == [shop_id]
    assert body["places"][0]["place_id"] == "ChIJ-saved" and set(body["places"][0]) == {"place_id", "saved_at"}
    client.delete("/api/v1/saved", headers=me, params={"shop_id": shop_id})
    assert client.get("/api/v1/saved", headers=me).json()["shops"] == []
    assert client.get("/api/v1/saved").status_code == 401


def test_reports_flow_to_admin(api, admin, client, shop_setup):
    me = api.login("9876500301", "Kiran")
    r = client.post("/api/v1/reports", headers=me, json={"target_type": "shop", "target_id": str(shop_setup["shop"]["id"]),
                                                         "reason": "wrong_location", "note": "Pin is one street off"})
    assert r.status_code == 201
    reports = client.get("/api/v1/admin/reports", headers=admin).json()["reports"]
    assert reports[0]["reason"] == "wrong_location"
    client.post(f"/api/v1/admin/reports/{reports[0]['id']}/resolve", headers=admin, json={"status": "resolved"})
    assert client.get("/api/v1/admin/reports", headers=admin).json()["reports"] == []
    assert client.get("/api/v1/admin/reports", headers=me).status_code == 403


def test_admin_consoles(api, admin, client, shop_setup):
    client.get("/api/v1/search", params={"q": "unicorn powder", "lat": JAYANAGAR[0], "lng": JAYANAGAR[1]})
    cov = client.get("/api/v1/admin/coverage", headers=admin).json()
    jay = next(l for l in cov["localities"] if l["slug"] == "jayanagar")
    assert jay["verified"] == 1 and jay["listings"] == 2 and jay["fresh_7d"] == 2
    analytics = client.get("/api/v1/admin/analytics", headers=admin).json()
    assert analytics["zero_result_queries"][0]["query"] == "unicorn powder"
    system = client.get("/api/v1/admin/system", headers=admin).json()
    assert system["integrations"]["google_places"] == "not_configured"
    assert system["integrations"]["push"] == "polling_only"
    assert client.get("/api/v1/admin/recognition", headers=admin).status_code == 200
    assert client.get("/api/v1/admin/trust", headers=admin).json()["open_reports"] == 0
    ready = client.get("/health/ready").json()
    assert ready["ok"] and ready["checks"]["database"]


# ------------------------------------------------------------ Places HTTP client


def _mock_places(handler):
    return httpx.MockTransport(handler)


def test_places_client_requests_only_needed_fields(app):
    seen = {}

    def handler(request: httpx.Request):
        seen["mask"] = request.headers.get("X-Goog-FieldMask")
        seen["key"] = request.headers.get("X-Goog-Api-Key")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"places": [{
            "id": "ChIJ1", "displayName": {"text": "Shri Vijaylaxmi Hardware"}, "location": {"latitude": 12.97, "longitude": 77.75},
            "primaryType": "hardware_store", "primaryTypeDisplayName": {"text": "Hardware store"},
            "shortFormattedAddress": "Whitefield, Bengaluru", "businessStatus": "OPERATIONAL"}]})

    client = PlacesClient("k", meter=UsageMeter(app.state.db), daily_limit=10, transport=_mock_places(handler))
    out = client.search_nearby(12.97, 77.75, 2000, types=["hardware_store"])
    assert out["places"][0]["name"] == "Shri Vijaylaxmi Hardware"
    assert out["places"][0]["category"] == "hardware"
    assert "rating" not in seen["mask"] and "OpeningHours" not in seen["mask"]  # cheaper SKU for lists
    assert seen["body"]["rankPreference"] == "DISTANCE" and seen["key"] == "k"


def test_places_daily_budget_and_error_mapping(app):
    calls = {"n": 0}

    def ok(request):
        calls["n"] += 1
        return httpx.Response(200, json={"places": []})

    meter = UsageMeter(app.state.db)
    client = PlacesClient("k", meter=meter, daily_limit=2, transport=_mock_places(ok))
    client.search_text("bakery")
    client.search_text("bakery")
    with pytest.raises(PlacesError) as exc:
        client.search_text("bakery")
    assert exc.value.code == "quota_exceeded" and calls["n"] == 2
    assert sum(r["count"] for r in meter.today() if r["provider"] == "google_places") == 2

    denied = PlacesClient("k", transport=_mock_places(lambda r: httpx.Response(403, json={"error": {"status": "PERMISSION_DENIED"}})))
    with pytest.raises(PlacesError) as exc:
        denied.search_text("x")
    assert exc.value.code == "denied"

    def boom(request):
        raise httpx.ConnectTimeout("slow network")

    with pytest.raises(PlacesError) as exc:
        PlacesClient("k", transport=_mock_places(boom)).search_text("x")
    assert exc.value.code == "unavailable"
    with pytest.raises(PlacesError) as exc:
        PlacesClient("").search_text("x")
    assert exc.value.code == "not_configured"
