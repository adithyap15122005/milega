"""Customer discovery: correct shop, same variant, honest availability."""

from datetime import timedelta

from sqlalchemy import update

from app.db import utcnow
from app.models import InventoryBalance

from conftest import JAYANAGAR


def _search(client, q, lat=JAYANAGAR[0], lng=JAYANAGAR[1], **params):
    r = client.get("/api/v1/search", params={"q": q, "lat": lat, "lng": lng, **params})
    assert r.status_code == 200, r.text
    return r.json()


def test_customer_finds_nearby_shop_with_price_and_stock(client, shop_setup):
    result = _search(client, "Tata Salt 1 kg near me", lat=12.9310, lng=77.5830)
    top = result["results"][0]
    assert top["variant"]["display_name"] == "Tata Salt 1 kg"
    offer = top["offers"][0]
    assert offer["shop"]["id"] == shop_setup["shop"]["id"]
    assert offer["price_paise"] == 2700
    assert offer["availability"]["status"] == "in_stock"
    assert offer["availability"]["available"] == 10
    assert 0 < offer["distance_m"] < 300
    assert offer["reservable"] is True


def test_unverified_shops_are_not_shown(api, client):
    owner = api.login("9876560001")
    api.register_shop(owner, name="Pending Shop")
    api.add_listing(owner, "tata salt 1 kg", 5)
    assert _search(client, "tata salt")["results"] == []


def test_different_pack_sizes_are_never_mixed(client, shop_setup):
    # The shop only sells Maggi 70 g.
    assert _search(client, "maggi 280 g")["results"] == []
    names = [r["variant"]["display_name"] for r in _search(client, "maggi 70g")["results"]]
    assert names == ["Maggi 2-Minute Masala Noodles 70 g"]


def test_stock_correction_is_reflected_for_customers(client, shop_setup):
    """Scenario 4."""
    owner, salt_id = shop_setup["owner"], shop_setup["salt"]["id"]
    client.post(f"/api/v1/inventory/listings/{salt_id}/adjust", headers=owner, json={"mode": "out_of_stock"})
    offer = _search(client, "tata salt")["results"][0]["offers"][0]
    assert offer["availability"]["status"] == "out_of_stock" and offer["reservable"] is False
    client.post(f"/api/v1/inventory/listings/{salt_id}/adjust", headers=owner, json={"mode": "set", "quantity": 4})
    offer = _search(client, "tata salt")["results"][0]["offers"][0]
    assert offer["availability"]["status"] == "in_stock" and offer["availability"]["available"] == 4


def test_old_stock_is_labelled_honestly(app, client, shop_setup):
    salt_id = shop_setup["salt"]["id"]

    def age(hours):
        with app.state.db.session() as db:
            db.execute(update(InventoryBalance).where(InventoryBalance.listing_id == salt_id)
                       .values(stock_confirmed_at=utcnow() - timedelta(hours=hours)))
        return _search(client, "tata salt")["results"][0]["offers"][0]["availability"]["status"]

    assert age(2) == "in_stock"
    assert age(40) == "likely"
    assert age(24 * 5) == "unverified"


def test_far_away_shops_are_excluded(client, shop_setup):
    assert _search(client, "tata salt", lat=12.2958, lng=76.6394)["results"] == []  # Mysuru


def test_price_filter(client, shop_setup):
    assert _search(client, "tata salt under 20")["results"] == []
    assert _search(client, "tata salt under ₹30")["results"]


def test_sort_by_lowest_price(api, client, admin, shop_setup):
    other = api.login("9876560002")
    shop = api.register_shop(other, name="Cheaper Store", lat=12.9400, lng=77.5900)
    api.verify_shop(admin, shop["id"])
    api.add_listing(other, "tata salt 1 kg", 5, price_paise=2500)
    by_price = _search(client, "tata salt 1 kg", sort="price")["results"][0]["offers"]
    assert [o["price_paise"] for o in by_price] == [2500, 2700]
    nearest = _search(client, "tata salt 1 kg", sort="nearest")["results"][0]["offers"]
    assert nearest[0]["shop"]["id"] == shop_setup["shop"]["id"]


def test_hindi_kannada_and_typos(client, shop_setup):
    assert _search(client, "नमक")["results"][0]["variant"]["display_name"] == "Tata Salt 1 kg"
    assert _search(client, "ಮ್ಯಾಗಿ")["results"][0]["variant"]["display_name"].startswith("Maggi")
    assert _search(client, "tata slat")["results"][0]["variant"]["display_name"] == "Tata Salt 1 kg"


def test_empty_query_is_rejected(client):
    r = client.get("/api/v1/search", params={"q": "near me"})
    assert r.status_code == 400 and r.json()["error"]["code"] == "empty_query"
