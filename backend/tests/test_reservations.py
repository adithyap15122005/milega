"""Reservation lifecycle, stock holds and overselling protection."""

import threading
from datetime import timedelta

from sqlalchemy import update

from app.db import utcnow
from app.errors import AppError
from app.models import InventoryBalance, Reservation, User
from app.services.reservations import create_reservation


def _stock(client, headers, listing_id):
    return client.get(f"/api/v1/inventory/listings/{listing_id}", headers=headers).json()


def _reserve(client, headers, listing_id, quantity, key=None):
    h = dict(headers)
    if key:
        h["Idempotency-Key"] = key
    return client.post("/api/v1/reservations", headers=h, json={"listing_id": listing_id, "quantity": quantity})


def test_pending_confirm_collect_updates_stock(api, client, shop_setup):
    owner, salt_id = shop_setup["owner"], shop_setup["salt"]["id"]
    customer = api.login("9876520001", "Meena")
    r = _reserve(client, customer, salt_id, 2)
    assert r.status_code == 201, r.text
    res = r.json()
    assert res["status"] == "pending" and res["items"][0]["quantity"] == 2
    s = _stock(client, owner, salt_id)
    assert (s["on_hand"], s["reserved"], s["available"]) == (10, 2, 8)

    notes = client.get("/api/v1/notifications", headers=owner).json()["notifications"]
    assert notes[0]["kind"] == "reservation_new"

    shop_list = client.get("/api/v1/shop/reservations", headers=owner).json()["reservations"]
    assert shop_list[0]["customer"]["phone_last4"] == "0001"  # full number stays private

    assert client.post(f"/api/v1/shop/reservations/{res['id']}/confirm", headers=owner).json()["status"] == "confirmed"
    assert client.post(f"/api/v1/shop/reservations/{res['id']}/collected", headers=owner).json()["status"] == "collected"
    s = _stock(client, owner, salt_id)
    assert (s["on_hand"], s["reserved"], s["available"]) == (8, 0, 8)
    kinds = [n["kind"] for n in client.get("/api/v1/notifications", headers=customer).json()["notifications"]]
    assert "reservation_confirmed" in kinds and "reservation_collected" in kinds


def test_cancel_and_reject_release_the_hold(api, client, shop_setup):
    owner, salt_id = shop_setup["owner"], shop_setup["salt"]["id"]
    customer = api.login("9876520002")
    a = _reserve(client, customer, salt_id, 3).json()
    b = _reserve(client, customer, salt_id, 4).json()
    assert _stock(client, owner, salt_id)["reserved"] == 7
    assert client.post(f"/api/v1/reservations/{a['id']}/cancel", headers=customer).json()["status"] == "cancelled"
    assert client.post(f"/api/v1/shop/reservations/{b['id']}/reject", headers=owner,
                       json={"note": "Damaged packets"}).json()["status"] == "rejected"
    s = _stock(client, owner, salt_id)
    assert (s["on_hand"], s["reserved"]) == (10, 0)


def test_expired_reservations_release_stock(app, api, client, shop_setup):
    owner, salt_id = shop_setup["owner"], shop_setup["salt"]["id"]
    customer = api.login("9876520003")
    res = _reserve(client, customer, salt_id, 5).json()
    with app.state.db.session() as db:
        db.execute(update(Reservation).where(Reservation.id == res["id"])
                   .values(expires_at=utcnow() - timedelta(minutes=1)))
    listing = client.get("/api/v1/shop/reservations?status=all", headers=owner).json()["reservations"]
    assert listing[0]["status"] == "expired"
    assert _stock(client, owner, salt_id)["reserved"] == 0
    # An expired reservation can no longer be confirmed.
    r = client.post(f"/api/v1/shop/reservations/{res['id']}/confirm", headers=owner)
    assert r.status_code == 409 and r.json()["error"]["code"] == "invalid_transition"


def test_cannot_reserve_more_than_available(api, client, shop_setup):
    customer = api.login("9876520004")
    r = _reserve(client, customer, shop_setup["maggi"]["id"], 6)
    assert r.status_code == 409 and r.json()["error"]["code"] == "insufficient_stock"


def test_collected_reservation_cannot_be_cancelled(api, client, shop_setup):
    owner = shop_setup["owner"]
    customer = api.login("9876520005")
    res = _reserve(client, customer, shop_setup["salt"]["id"], 1).json()
    client.post(f"/api/v1/shop/reservations/{res['id']}/collected", headers=owner)
    r = client.post(f"/api/v1/reservations/{res['id']}/cancel", headers=customer)
    assert r.status_code == 409 and r.json()["error"]["code"] == "invalid_transition"
    assert _stock(client, owner, shop_setup["salt"]["id"])["on_hand"] == 9  # collected exactly once


def test_retried_reservation_is_created_once(api, client, shop_setup):
    customer = api.login("9876520006")
    first = _reserve(client, customer, shop_setup["salt"]["id"], 2, key="res-abc").json()
    second = _reserve(client, customer, shop_setup["salt"]["id"], 2, key="res-abc").json()
    assert first["id"] == second["id"]
    assert _stock(client, shop_setup["owner"], shop_setup["salt"]["id"])["reserved"] == 2


def test_unverified_shop_cannot_take_reservations(api, client):
    owner = api.login("9876520007")
    api.register_shop(owner, name="Brand New Shop")
    listing = api.add_listing(owner, "tata salt 1 kg", 5)
    customer = api.login("9876520008")
    r = _reserve(client, customer, listing["id"], 1)
    assert r.status_code == 409 and r.json()["error"]["code"] == "shop_unavailable"


def test_shopkeeper_cannot_reserve_from_own_shop(client, shop_setup):
    r = _reserve(client, shop_setup["owner"], shop_setup["salt"]["id"], 1)
    assert r.status_code == 403


def test_concurrent_customers_cannot_both_get_the_last_item(app, api, client, shop_setup):
    """Scenario 6: eight customers press Reserve on the final packet at the same moment."""
    owner, maggi_id = shop_setup["owner"], shop_setup["maggi"]["id"]
    client.post(f"/api/v1/inventory/listings/{maggi_id}/adjust", headers=owner, json={"mode": "set", "quantity": 1})
    user_ids = []
    for i in range(8):
        api.login(f"98765300{i:02d}")
    with app.state.db.session() as db:
        user_ids = [u.id for u in db.query(User).filter(User.phone.like("+9198765300%")).all()]
    assert len(user_ids) == 8

    barrier = threading.Barrier(len(user_ids))
    results: list[str] = []
    lock = threading.Lock()

    def attempt(uid: int) -> None:
        barrier.wait()
        try:
            with app.state.db.session() as db:
                create_reservation(db, app.state.settings, customer=db.get(User, uid), listing_id=maggi_id,
                                   quantity=1, idempotency_key=None)
            outcome = "ok"
        except AppError as exc:
            outcome = exc.code
        with lock:
            results.append(outcome)

    threads = [threading.Thread(target=attempt, args=(uid,)) for uid in user_ids]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert results.count("ok") == 1, results
    assert results.count("insufficient_stock") == 7, results
    with app.state.db.session() as db:
        bal = db.get(InventoryBalance, maggi_id)
        assert (bal.on_hand_milli, bal.reserved_milli) == (1000, 1000)


def test_hold_is_rejected_by_the_database_condition_even_with_stale_reads(app, api, shop_setup):
    """The guard lives in the UPDATE itself, not in an earlier read."""
    owner_id_listing = shop_setup["maggi"]["id"]
    api.login("9876540001")
    api.login("9876540002")
    with app.state.db.session() as db:
        a, b = db.query(User).filter(User.phone.in_(["+919876540001", "+919876540002"])).order_by(User.id).all()
        a_id, b_id = a.id, b.id
    with app.state.db.session() as db:
        create_reservation(db, app.state.settings, customer=db.get(User, a_id), listing_id=owner_id_listing,
                           quantity=5, idempotency_key=None)
    try:
        with app.state.db.session() as db:
            create_reservation(db, app.state.settings, customer=db.get(User, b_id), listing_id=owner_id_listing,
                               quantity=1, idempotency_key=None)
        raise AssertionError("second hold should have failed")
    except AppError as exc:
        assert exc.code == "insufficient_stock"
