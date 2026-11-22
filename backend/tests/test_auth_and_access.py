"""Authentication, role-based access and shop-level data isolation."""


def test_otp_login_creates_customer(api, client):
    headers = api.login("98765 43210", "Asha")
    me = client.get("/api/v1/me", headers=headers).json()
    assert me["role"] == "customer"
    assert me["phone"] == "+919876543210"
    assert me["name"] == "Asha"
    assert me["shop"] is None


def test_invalid_phone_is_rejected(client):
    r = client.post("/api/v1/auth/otp/request", json={"phone": "12345"})
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "invalid_phone"


def test_wrong_otp_attempts_are_limited(client):
    r = client.post("/api/v1/auth/otp/request", json={"phone": "9876543211"})
    real = r.json()["dev_otp"]
    wrong = "000000" if real != "000000" else "111111"
    for _ in range(5):
        r = client.post("/api/v1/auth/otp/verify", json={"phone": "9876543211", "code": wrong})
        assert r.status_code == 400
        assert r.json()["error"]["code"] == "otp_invalid"
    # Even the right code no longer works for this challenge.
    r = client.post("/api/v1/auth/otp/verify", json={"phone": "9876543211", "code": real})
    assert r.status_code == 429


def test_unauthenticated_requests_are_rejected(client):
    assert client.get("/api/v1/me").status_code == 401
    assert client.get("/api/v1/inventory").status_code == 401
    assert client.get("/api/v1/me", headers={"Authorization": "Bearer nonsense"}).status_code == 401


def test_customer_cannot_use_shopkeeper_or_admin_endpoints(api, client):
    headers = api.login("9876543212")
    r = client.get("/api/v1/inventory", headers=headers)
    assert r.status_code == 403 and r.json()["error"]["code"] == "no_shop"
    assert client.get("/api/v1/admin/stats", headers=headers).status_code == 403


def test_registering_a_shop_makes_user_a_shopkeeper(api, client):
    headers = api.login("9876543213")
    shop = api.register_shop(headers)
    assert shop["status"] == "pending"  # needs admin verification before customers see it
    assert client.get("/api/v1/me", headers=headers).json()["role"] == "shopkeeper"
    r = client.post("/api/v1/shops", headers=headers, json={"name": "Second", "lat": 12.9, "lng": 77.5})
    assert r.status_code == 409


def test_shopkeepers_cannot_touch_other_shops(api, client, shop_setup):
    other = api.login("9876500002")
    api.register_shop(other, name="Other Store")
    salt_id = shop_setup["salt"]["id"]
    r = client.post(f"/api/v1/inventory/listings/{salt_id}/adjust", headers=other,
                    json={"mode": "sell", "quantity": 1})
    assert r.status_code == 404
    assert client.get(f"/api/v1/inventory/listings/{salt_id}", headers=other).status_code == 404
    assert client.delete(f"/api/v1/inventory/listings/{salt_id}", headers=other).status_code == 404
    # The real owner's stock is untouched.
    r = client.get(f"/api/v1/inventory/listings/{salt_id}", headers=shop_setup["owner"])
    assert r.json()["on_hand"] == 10


def test_admin_can_suspend_shop_and_it_disappears(api, client, admin, shop_setup):
    shop_id = shop_setup["shop"]["id"]
    r = client.post(f"/api/v1/admin/shops/{shop_id}/suspend", headers=admin, json={"reason": "test"})
    assert r.status_code == 200
    assert client.get(f"/api/v1/shops/{shop_id}").status_code == 404
    r = client.get("/api/v1/inventory", headers=shop_setup["owner"])
    assert r.status_code == 403 and r.json()["error"]["code"] == "shop_suspended"
