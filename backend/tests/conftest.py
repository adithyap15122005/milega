from __future__ import annotations

import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402
from app.seed import seed_catalog  # noqa: E402
from app.services.recognition import OcrTextProvider  # noqa: E402

JAYANAGAR = (12.9299, 77.5826)


def make_settings(tmp_path: Path, **overrides) -> Settings:
    base = Settings(
        database_url=f"sqlite:///{(tmp_path / 'test.db').as_posix()}",
        storage_dir=tmp_path / "uploads",
        admin_phones=("+919999900000",),
        vision_provider="ocr",
        run_background_jobs=False,
    )
    return base.with_overrides(**overrides)


class Api:
    """Small helper around TestClient with login and error-friendly calls."""

    def __init__(self, client: TestClient):
        self.client = client

    def login(self, phone: str, name: str | None = None) -> dict:
        r = self.client.post("/api/v1/auth/otp/request", json={"phone": phone})
        assert r.status_code == 200, r.text
        code = r.json()["dev_otp"]
        r = self.client.post("/api/v1/auth/otp/verify", json={"phone": phone, "code": code, "name": name})
        assert r.status_code == 200, r.text
        return {"Authorization": f"Bearer {r.json()['access_token']}"}

    def register_shop(self, headers: dict, name: str = "Test Kirana", lat: float = JAYANAGAR[0],
                      lng: float = JAYANAGAR[1], categories=("staples", "snacks")) -> dict:
        r = self.client.post("/api/v1/shops", headers=headers, json={
            "name": name, "lat": lat, "lng": lng, "locality": "Jayanagar", "city": "Bengaluru",
            "categories": list(categories),
        })
        assert r.status_code == 201, r.text
        return r.json()

    def verify_shop(self, admin_headers: dict, shop_id: int) -> None:
        r = self.client.post(f"/api/v1/admin/shops/{shop_id}/verify", headers=admin_headers)
        assert r.status_code == 200, r.text

    def variant_id(self, headers: dict, query: str) -> int:
        r = self.client.get("/api/v1/catalog/search", params={"q": query}, headers=headers)
        assert r.status_code == 200, r.text
        variants = r.json()["variants"]
        assert variants, f"no catalogue match for {query}"
        return variants[0]["id"]

    def add_listing(self, headers: dict, query: str, quantity: float, price_paise: int | None = 2700) -> dict:
        r = self.client.post("/api/v1/inventory/listings", headers=headers, json={
            "variant_id": self.variant_id(headers, query), "quantity": quantity, "price_paise": price_paise,
        })
        assert r.status_code == 201, r.text
        return r.json()


@pytest.fixture()
def app(tmp_path):
    application = create_app(make_settings(tmp_path), provider_factory=lambda s, has_ocr: OcrTextProvider())
    with application.state.db.session() as db:
        seed_catalog(db)
    return application


@pytest.fixture()
def client(app):
    with TestClient(app) as c:
        yield c


@pytest.fixture()
def api(client):
    return Api(client)


@pytest.fixture()
def admin(api):
    return api.login("+919999900000", "Admin")


@pytest.fixture()
def shop_setup(api, admin):
    """A verified shop with Tata Salt 1 kg (10 in stock) and Maggi 70 g (5 in stock)."""
    owner = api.login("9876500001", "Ravi")
    shop = api.register_shop(owner)
    api.verify_shop(admin, shop["id"])
    salt = api.add_listing(owner, "tata salt 1 kg", 10, 2700)
    maggi = api.add_listing(owner, "maggi masala noodles 70 g", 5, 1500)
    return {"owner": owner, "shop": shop, "salt": salt, "maggi": maggi}
