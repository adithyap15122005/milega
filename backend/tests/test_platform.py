"""Production plumbing: SMS OTP providers, distributed rate limits, push delivery, offline sync receipts."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import fakeredis
import httpx
import pytest

from app.config import Settings
from app.errors import AppError
from app.models import InventoryEvent, Notification, NotificationDevice
from app.ratelimit import MemoryRateLimiter, RedisRateLimiter
from app.services.push import PushSender, dispatch_pending
from app.services.sms import Msg91Sms, TwilioVerifySms

# -------------------------------------------------------------------- SMS OTP


def test_msg91_sends_dlt_flow_and_reports_failures():
    seen = {}

    def ok(request: httpx.Request):
        seen["auth"] = request.headers["authkey"]
        seen["body"] = request.read().decode()
        return httpx.Response(200, json={"type": "success"})

    Msg91Sms("auth", "tmpl", transport=httpx.MockTransport(ok)).send("+919876543210", "123456")
    assert seen["auth"] == "auth" and '"mobiles":"919876543210"' in seen["body"].replace(" ", "")
    assert '"otp":"123456"' in seen["body"].replace(" ", "")

    bad = Msg91Sms("auth", "tmpl", transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"type": "error"})))
    with pytest.raises(AppError) as exc:
        bad.send("+919876543210", "123456")
    assert exc.value.code == "sms_unavailable"


def test_twilio_verify_login_keeps_our_attempt_limits(app, client):
    state = {"started": 0}

    def handler(request: httpx.Request):
        if request.url.path.endswith("/Verifications"):
            state["started"] += 1
            return httpx.Response(201, json={"status": "pending"})
        code = dict(x.split("=") for x in request.read().decode().split("&"))["Code"]
        return httpx.Response(200, json={"status": "approved" if code == "424242" else "pending"})

    app.state.sms = TwilioVerifySms("AC1", "token", "VA1", transport=httpx.MockTransport(handler))
    r = client.post("/api/v1/auth/otp/request", json={"phone": "9876512345"})
    assert r.status_code == 200 and "dev_otp" not in r.json() and state["started"] == 1
    wrong = client.post("/api/v1/auth/otp/verify", json={"phone": "9876512345", "code": "111111"})
    assert wrong.json()["error"]["code"] == "otp_invalid"
    ok = client.post("/api/v1/auth/otp/verify", json={"phone": "9876512345", "code": "424242"})
    assert ok.status_code == 200 and ok.json()["access_token"]
    # The challenge is consumed: the same code cannot be replayed.
    again = client.post("/api/v1/auth/otp/verify", json={"phone": "9876512345", "code": "424242"})
    assert again.json()["error"]["code"] == "otp_expired"


def test_wrong_codes_lock_the_challenge(api, client):
    client.post("/api/v1/auth/otp/request", json={"phone": "9876512399"})
    for _ in range(5):
        client.post("/api/v1/auth/otp/verify", json={"phone": "9876512399", "code": "000000"})
    r = client.post("/api/v1/auth/otp/verify", json={"phone": "9876512399", "code": "000000"})
    assert r.json()["error"]["code"] == "otp_attempts"


def test_production_refuses_half_configured_sms():
    with pytest.raises(RuntimeError) as exc:
        Settings(app_env="production", jwt_secret="x" * 40, expose_dev_otp=False, sms_provider="msg91",
                 database_url="postgresql+psycopg://u:p@db/milega").validate()
    assert "MSG91_AUTH_KEY" in str(exc.value)


# --------------------------------------------------------------- rate limiting


def test_redis_limits_are_shared_between_replicas():
    server = fakeredis.FakeServer()
    replica_a = RedisRateLimiter(fakeredis.FakeRedis(server=server))
    replica_b = RedisRateLimiter(fakeredis.FakeRedis(server=server))
    results = [replica_a.hit("otp:1.2.3.4", 3, 60), replica_b.hit("otp:1.2.3.4", 3, 60),
               replica_a.hit("otp:1.2.3.4", 3, 60), replica_b.hit("otp:1.2.3.4", 3, 60)]
    assert results == [True, True, True, False]
    assert replica_a.hit("otp:5.6.7.8", 3, 60)  # separate key
    assert replica_a.healthy()


def test_redis_outage_degrades_to_local_limits():
    class Broken:
        def pipeline(self, transaction=True):
            raise ConnectionError("redis down")

        def ping(self):
            raise ConnectionError("redis down")

    limiter = RedisRateLimiter(Broken())
    assert [limiter.hit("k", 2, 60) for _ in range(3)] == [True, True, False]
    assert not limiter.healthy()
    assert MemoryRateLimiter().healthy()


# ------------------------------------------------------------------- push / FCM


class FakeSender(PushSender):
    configured = True

    def __init__(self, unregistered: set[str] | None = None):
        self.sent: list[tuple[str, dict]] = []
        self.unregistered = unregistered or set()

    def send(self, token, *, title, body, data):
        if token in self.unregistered:
            return "unregistered"
        self.sent.append((token, data))
        return "sent"


def _reserve(api, client, shop_setup, phone="9876500400", key="r-1"):
    customer = api.login(phone, "Divya")
    r = client.post("/api/v1/reservations", headers={**customer, "Idempotency-Key": key},
                    json={"listing_id": shop_setup["salt"]["id"], "quantity": 1})
    assert r.status_code == 201, r.text
    return customer, r.json()


def test_reservation_push_reaches_shopkeeper_once(app, api, client, shop_setup):
    owner = shop_setup["owner"]
    assert client.post("/api/v1/devices", headers=owner, json={"token": "tok-shop-" + "x" * 20}).json()["registered"]
    client.post("/api/v1/devices", headers=owner, json={"token": "tok-dead-" + "x" * 20})
    _reserve(api, client, shop_setup)
    sender = FakeSender(unregistered={"tok-dead-" + "x" * 20})
    stats = dispatch_pending(app.state.db, sender)
    assert stats["sent"] >= 1
    assert {t for t, _ in sender.sent} == {"tok-shop-" + "x" * 20}
    kinds = [d["kind"] for _, d in sender.sent]
    assert "reservation_new" in kinds  # (the setup's "shop verified" notice is pushed too)
    ids = [d["notification_id"] for _, d in sender.sent]
    assert len(ids) == len(set(ids))
    # Second run finds nothing new: no duplicate pushes.
    before = len(sender.sent)
    assert dispatch_pending(app.state.db, sender)["sent"] == 0 and len(sender.sent) == before
    with app.state.db.session() as db:
        dead = db.query(NotificationDevice).filter(NotificationDevice.token.like("tok-dead-%")).one()
        assert dead.disabled_at is not None


def test_push_skipped_without_fcm_but_inbox_still_works(app, api, client, shop_setup):
    _reserve(api, client, shop_setup, phone="9876500401", key="r-2")
    stats = dispatch_pending(app.state.db, app.state.push)  # NullSender in tests
    assert stats["skipped"] >= 1
    inbox = client.get("/api/v1/notifications", headers=shop_setup["owner"]).json()
    assert inbox["notifications"]
    with app.state.db.session() as db:
        assert {n.push_status for n in db.query(Notification).all()} == {"skipped"}


def test_device_token_moves_between_accounts(app, api, client):
    a = api.login("9876500402", "A")
    b = api.login("9876500403", "B")
    token = "shared-token-" + "y" * 20
    client.post("/api/v1/devices", headers=a, json={"token": token})
    client.post("/api/v1/devices", headers=b, json={"token": token})
    with app.state.db.session() as db:
        rows = db.query(NotificationDevice).filter_by(token=token).all()
        assert len(rows) == 1
    assert client.delete(f"/api/v1/devices/{token}", headers=b).json() == {"registered": False}


# ---------------------------------------------------------- offline sync receipts


def test_offline_change_applies_once_and_keeps_recorded_time(app, client, shop_setup):
    owner, salt = shop_setup["owner"], shop_setup["salt"]
    recorded = (datetime.now(timezone.utc) - timedelta(hours=3)).replace(microsecond=0)
    body = {"mode": "sell", "quantity": 2, "client_recorded_at": recorded.isoformat()}
    headers = {**owner, "Idempotency-Key": "outbox-7f3a"}
    first = client.post(f"/api/v1/inventory/listings/{salt['id']}/adjust", headers=headers, json=body).json()
    # The app restarts and the outbox retries the same operation after reconnecting.
    second = client.post(f"/api/v1/inventory/listings/{salt['id']}/adjust", headers=headers, json=body).json()
    assert first["listing"]["on_hand"] == 8 and second["listing"]["on_hand"] == 8
    assert second["replayed"] is True
    assert first["event"]["client_recorded_at"].startswith(recorded.isoformat()[:16])
    with app.state.db.session() as db:
        assert db.query(InventoryEvent).filter(InventoryEvent.listing_id == salt["id"],
                                               InventoryEvent.event_type == "sale").count() == 1


def test_offline_change_with_bad_clock_is_not_trusted(client, shop_setup):
    owner, salt = shop_setup["owner"], shop_setup["salt"]
    future = (datetime.now(timezone.utc) + timedelta(days=2)).isoformat()
    r = client.post(f"/api/v1/inventory/listings/{salt['id']}/adjust", headers={**owner, "Idempotency-Key": "k-future"},
                    json={"mode": "add", "quantity": 1, "client_recorded_at": future}).json()
    assert r["event"]["client_recorded_at"] is None


def test_offline_conflict_is_reported_not_forced(client, shop_setup):
    """An offline 'sold 20' that no longer fits the real stock is rejected; stock never goes negative."""
    owner, maggi = shop_setup["owner"], shop_setup["maggi"]
    r = client.post(f"/api/v1/inventory/listings/{maggi['id']}/adjust", headers={**owner, "Idempotency-Key": "k-over"},
                    json={"mode": "sell", "quantity": 20})
    assert r.status_code == 409 and r.json()["error"]["code"] == "insufficient_stock"
    assert client.get(f"/api/v1/inventory/listings/{maggi['id']}", headers=owner).json()["on_hand"] == 5
