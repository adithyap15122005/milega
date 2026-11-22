"""Inventory ledger: every change is an event and balances always agree with it."""


def _adjust(client, headers, listing_id, mode, quantity=0, key=None):
    h = dict(headers)
    if key:
        h["Idempotency-Key"] = key
    return client.post(f"/api/v1/inventory/listings/{listing_id}/adjust", headers=h,
                       json={"mode": mode, "quantity": quantity})


def _events(client, headers, listing_id):
    return client.get(f"/api/v1/inventory/listings/{listing_id}/events", headers=headers).json()["events"]


def test_new_listing_records_initial_stock_event(client, shop_setup):
    salt = shop_setup["salt"]
    assert salt["on_hand"] == 10 and salt["available"] == 10 and salt["price_paise"] == 2700
    events = _events(client, shop_setup["owner"], salt["id"])
    assert [e["event_type"] for e in events] == ["initial_stock"]
    assert events[0]["delta_on_hand"] == 10
    assert events[0]["verification"] == "shopkeeper_confirmed"


def test_sell_add_set_and_out_of_stock_keep_ledger_consistent(client, shop_setup):
    owner, salt_id = shop_setup["owner"], shop_setup["salt"]["id"]
    assert _adjust(client, owner, salt_id, "sell", 3).json()["listing"]["on_hand"] == 7
    assert _adjust(client, owner, salt_id, "add", 5).json()["listing"]["on_hand"] == 12
    r = _adjust(client, owner, salt_id, "set", 4).json()
    assert r["listing"]["on_hand"] == 4 and r["event"]["delta_on_hand"] == -8
    r = _adjust(client, owner, salt_id, "out_of_stock").json()
    assert r["listing"]["on_hand"] == 0 and r["listing"]["out_of_stock"] is True

    events = list(reversed(_events(client, owner, salt_id)))
    assert [e["event_type"] for e in events] == ["initial_stock", "sale", "restock", "adjustment", "out_of_stock"]
    running = 0
    for e in events:
        running += e["delta_on_hand"]
        assert e["on_hand_after"] == running  # ledger and balance never drift


def test_cannot_sell_more_than_in_stock(client, shop_setup):
    owner, salt_id = shop_setup["owner"], shop_setup["salt"]["id"]
    r = _adjust(client, owner, salt_id, "sell", 11)
    assert r.status_code == 409
    assert r.json()["error"]["code"] == "insufficient_stock"
    assert client.get(f"/api/v1/inventory/listings/{salt_id}", headers=owner).json()["on_hand"] == 10


def test_retried_adjustment_is_applied_once(client, shop_setup):
    owner, salt_id = shop_setup["owner"], shop_setup["salt"]["id"]
    first = _adjust(client, owner, salt_id, "sell", 2, key="tap-123")
    second = _adjust(client, owner, salt_id, "sell", 2, key="tap-123")
    assert first.status_code == second.status_code == 200
    assert second.json()["replayed"] is True
    assert client.get(f"/api/v1/inventory/listings/{salt_id}", headers=owner).json()["on_hand"] == 8
    assert len(_events(client, owner, salt_id)) == 2


def test_pieces_need_whole_numbers(client, shop_setup):
    r = _adjust(client, shop_setup["owner"], shop_setup["salt"]["id"], "sell", 1.5)
    assert r.status_code == 400 and r.json()["error"]["code"] == "whole_number_required"


def test_loose_goods_use_decimal_kilograms(api, client, shop_setup):
    owner = shop_setup["owner"]
    rice = api.add_listing(owner, "sona masoori rice loose", 12.5, 6200)
    assert rice["sell_unit"] == "kg" and rice["on_hand"] == 12.5
    r = _adjust(client, owner, rice["id"], "sell", 2.25)
    assert r.json()["listing"]["on_hand"] == 10.25


def test_price_change_and_name_correction(client, admin, shop_setup):
    owner, salt_id = shop_setup["owner"], shop_setup["salt"]["id"]
    r = client.patch(f"/api/v1/inventory/listings/{salt_id}", headers=owner,
                     json={"price_paise": 2600, "custom_name": "Tata Namak 1kg"})
    assert r.status_code == 200
    assert r.json()["price_paise"] == 2600 and r.json()["name"] == "Tata Namak 1kg"
    queue = client.get("/api/v1/admin/verifications", headers=admin).json()["verifications"]
    assert any(v["kind"] == "name_correction" for v in queue)


def test_manually_added_unknown_product_is_provisional(client, admin, shop_setup):
    r = client.post("/api/v1/inventory/listings", headers=shop_setup["owner"], json={
        "new_product": {"name": "Homemade Mango Pickle", "brand": "Sri Ganesh", "size_label": "250 g",
                        "category": "staples"},
        "quantity": 6, "price_paise": 9000,
    })
    assert r.status_code == 201, r.text
    assert r.json()["variant_status"] == "provisional" and r.json()["on_hand"] == 6
    queue = client.get("/api/v1/admin/verifications", headers=admin).json()["verifications"]
    assert any(v["kind"] == "provisional_product" for v in queue)


def test_cannot_remove_product_with_open_reservations(api, client, shop_setup):
    owner, salt_id = shop_setup["owner"], shop_setup["salt"]["id"]
    customer = api.login("9876511111")
    res = client.post("/api/v1/reservations", headers=customer, json={"listing_id": salt_id, "quantity": 1}).json()
    r = client.delete(f"/api/v1/inventory/listings/{salt_id}", headers=owner)
    assert r.status_code == 409 and r.json()["error"]["code"] == "has_reservations"
    client.post(f"/api/v1/shop/reservations/{res['id']}/reject", headers=owner, json={"note": "sorry"})
    assert client.delete(f"/api/v1/inventory/listings/{salt_id}", headers=owner).status_code == 200
    items = client.get("/api/v1/inventory", headers=owner).json()["items"]
    assert salt_id not in {i["id"] for i in items}


def test_summary_shows_low_stock_and_activity(client, shop_setup):
    owner = shop_setup["owner"]
    _adjust(client, owner, shop_setup["maggi"]["id"], "set", 2)
    summary = client.get("/api/v1/inventory/summary", headers=owner).json()
    assert summary["total_products"] == 2
    assert summary["low_stock"] == 1
    assert summary["attention"][0]["id"] == shop_setup["maggi"]["id"]
    assert summary["recent_activity"][0]["event_type"] == "adjustment"
