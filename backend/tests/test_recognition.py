"""Photo-based inventory: AI suggests, the shopkeeper decides."""

import io
import json
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw

from app.main import create_app
from app.seed import seed_catalog
from app.services.recognition import ClaudeVisionProvider
from app.services.recognition.providers import parse_claude_payload

from conftest import Api, make_settings


def photo(blank: bool = False) -> bytes:
    img = Image.new("RGB", (900, 700), (128, 128, 128) if blank else "white")
    if not blank:
        d = ImageDraw.Draw(img)
        for i in range(0, 900, 30):  # high-contrast detail so it isn't flagged as blurry
            d.rectangle([i, 100, i + 12, 600], fill="black")
        d.text((50, 30), "TATA SALT 1 kg", fill="black")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def line(text, left, top, width=None, height=40, block=None):
    width = width or 22 * len(text)
    return {"text": text, "left": left, "top": top, "right": left + width, "bottom": top + height, "block": block}


SALT_AND_MAGGI = [
    # pack 1 (top-left)
    line("TATA", 40, 40), line("SALT", 40, 90), line("Iodised", 40, 140), line("1 kg", 40, 190),
    # pack 2 (far right)
    line("MAGGI", 600, 40), line("2-MINUTE NOODLES", 600, 90), line("Masala", 600, 140), line("70g", 600, 190),
    # a stray label that matches nothing
    line("Best before 9 months", 300, 600),
]


def upload(client, headers, ocr, mode="incoming", image=None, request_id=None):
    data = {"mode": mode, "ocr_lines": json.dumps(ocr)}
    if request_id:
        data["client_request_id"] = request_id
    r = client.post("/api/v1/recognition/jobs", headers=headers, data=data,
                    files={"image": ("scan.png", image or photo(), "image/png")})
    assert r.status_code == 202, r.text
    # TestClient runs background tasks before returning, so the result is ready.
    job = client.get(f"/api/v1/recognition/jobs/{r.json()['id']}", headers=headers)
    assert job.status_code == 200
    return job.json()


def stock(client, headers, listing_id):
    return client.get(f"/api/v1/inventory/listings/{listing_id}", headers=headers).json()["on_hand"]


def test_scan_suggests_products_but_never_changes_stock_by_itself(client, shop_setup):
    """Scenario 1: barcode-free onboarding with human confirmation."""
    owner, salt = shop_setup["owner"], shop_setup["salt"]
    job = upload(client, owner, SALT_AND_MAGGI)
    assert job["status"] == "needs_review"
    assert job["provider"] == "on_device_ocr"
    names = {c["matches"][0]["display_name"]: c for c in job["candidates"]}
    assert set(names) == {"Tata Salt 1 kg", "Maggi 2-Minute Masala Noodles 70 g"}
    salt_c = names["Tata Salt 1 kg"]
    assert salt_c["confidence"] == "high"
    assert salt_c["suggested_variant_id"] == salt["variant_id"]
    assert salt_c["suggested_quantity"] is None  # OCR cannot count: shopkeeper enters it
    assert salt_c["matches"][0]["current_on_hand"] == 10

    assert stock(client, owner, salt["id"]) == 10  # nothing changed yet

    maggi_c = names["Maggi 2-Minute Masala Noodles 70 g"]
    r = client.post(f"/api/v1/recognition/jobs/{job['id']}/confirm", headers=owner, json={"items": [
        {"candidate_id": salt_c["id"], "variant_id": salt_c["matches"][0]["id"], "quantity": 12},
        {"candidate_id": maggi_c["id"], "variant_id": maggi_c["matches"][0]["id"], "quantity": 15, "price_paise": 1400},
    ]})
    assert r.status_code == 200, r.text
    assert stock(client, owner, salt["id"]) == 22
    maggi = client.get(f"/api/v1/inventory/listings/{shop_setup['maggi']['id']}", headers=owner).json()
    assert maggi["on_hand"] == 20 and maggi["price_paise"] == 1400

    events = client.get(f"/api/v1/inventory/listings/{salt['id']}/events", headers=owner).json()["events"]
    assert events[0]["source"] == "photo_scan"
    assert events[0]["reference_type"] == "recognition_job" and events[0]["reference_id"] == job["id"]

    again = client.post(f"/api/v1/recognition/jobs/{job['id']}/confirm", headers=owner, json={"items": [
        {"candidate_id": salt_c["id"], "variant_id": salt_c["matches"][0]["id"], "quantity": 12}]})
    assert again.status_code == 409  # a double tap can't add stock twice
    assert stock(client, owner, salt["id"]) == 22


def test_ambiguous_pack_gives_options_instead_of_a_guess(client, shop_setup):
    job = upload(client, shop_setup["owner"], [line("MAGGI", 40, 40), line("Masala Noodles", 40, 90)])
    cand = job["candidates"][0]
    assert cand["confidence"] != "high"
    assert cand["suggested_variant_id"] is None
    sizes = {m["size_label"] for m in cand["matches"]}
    assert {"70 g", "280 g"} <= sizes


def test_unclear_photo_fails_cleanly_without_inventing_items(client, shop_setup):
    """Scenario 5."""
    job = upload(client, shop_setup["owner"], [], image=photo(blank=True))
    assert job["status"] == "failed"
    assert job["error_code"] == "photo_unclear"
    assert job["candidates"] == []
    assert stock(client, shop_setup["owner"], shop_setup["salt"]["id"]) == 10


def test_quantity_is_required_and_failed_confirm_changes_nothing(client, shop_setup):
    owner = shop_setup["owner"]
    job = upload(client, owner, SALT_AND_MAGGI)
    c = job["candidates"][0]
    r = client.post(f"/api/v1/recognition/jobs/{job['id']}/confirm", headers=owner, json={"items": [
        {"candidate_id": c["id"], "variant_id": c["matches"][0]["id"], "quantity": 0}]})
    assert r.status_code == 400 and r.json()["error"]["code"] == "quantity_required"
    # The job is still open and can be confirmed properly.
    r = client.post(f"/api/v1/recognition/jobs/{job['id']}/confirm", headers=owner, json={"items": [
        {"candidate_id": c["id"], "variant_id": c["matches"][0]["id"], "quantity": 3}]})
    assert r.status_code == 200


def test_upload_retry_does_not_create_a_second_job(client, shop_setup):
    owner = shop_setup["owner"]
    a = upload(client, owner, SALT_AND_MAGGI, request_id="upload-1")
    b = upload(client, owner, SALT_AND_MAGGI, request_id="upload-1")
    assert a["id"] == b["id"]


def test_unknown_local_product_can_be_added_as_provisional(client, shop_setup):
    owner = shop_setup["owner"]
    job = upload(client, owner, [line("SRI GANESH", 40, 40), line("Homemade Mango Pickle", 40, 90), line("250 g", 40, 140)])
    assert job["status"] == "needs_review"
    cand = job["candidates"][0]
    assert cand["matches"] == [] and cand["confidence"] == "low"
    r = client.post(f"/api/v1/recognition/jobs/{job['id']}/confirm", headers=owner, json={"items": [{
        "candidate_id": cand["id"], "quantity": 6, "price_paise": 9000,
        "new_product": {"name": "Homemade Mango Pickle", "brand": "Sri Ganesh", "size_label": "250 g",
                        "category": "staples"},
    }]})
    assert r.status_code == 200, r.text
    assert r.json()["applied"][0]["provisional"] is True


def test_invoice_scan_reads_rows_and_imports_after_review(client, shop_setup):
    owner = shop_setup["owner"]
    ocr = [
        line("Sri Venkateshwara Traders", 40, 20),
        line("Item", 40, 100), line("Qty", 500, 100), line("Rate", 620, 100), line("Amount", 760, 100),
        line("1 TATA SALT 1KG", 40, 160), line("20", 500, 162), line("24.00", 620, 158), line("480.00", 760, 160),
        line("2 PARLE G 250G", 40, 220), line("48", 500, 222), line("22.50", 620, 219), line("1,080.00", 760, 221),
        line("Total", 40, 300), line("1,560.00", 760, 300),
    ]
    job = upload(client, owner, ocr, mode="invoice")
    assert job["status"] == "needs_review", job
    assert job["supplier_name"] == "Sri Venkateshwara Traders"
    rows = {c["matches"][0]["display_name"]: c for c in job["candidates"]}
    salt_row = rows["Tata Salt 1 kg"]
    assert salt_row["suggested_quantity"] == 20 and salt_row["invoice_unit_price_paise"] == 2400
    parle_row = rows["Parle-G Original Glucose Biscuits 250 g"]
    assert parle_row["suggested_quantity"] == 48
    r = client.post(f"/api/v1/recognition/jobs/{job['id']}/confirm", headers=owner, json={"items": [
        {"candidate_id": salt_row["id"], "variant_id": salt_row["matches"][0]["id"], "quantity": 20},
        {"candidate_id": parle_row["id"], "variant_id": parle_row["matches"][0]["id"], "quantity": 48,
         "price_paise": 2500},
    ]})
    assert r.status_code == 200, r.text
    assert stock(client, owner, shop_setup["salt"]["id"]) == 30
    events = client.get(f"/api/v1/inventory/listings/{shop_setup['salt']['id']}/events", headers=owner).json()["events"]
    assert events[0]["event_type"] == "invoice_import" and events[0]["source"] == "invoice_scan"


def test_shelf_scan_recount_sets_the_count_shopkeeper_confirms(client, shop_setup):
    owner = shop_setup["owner"]
    job = upload(client, owner, SALT_AND_MAGGI[:4], mode="shelf")
    c = job["candidates"][0]
    r = client.post(f"/api/v1/recognition/jobs/{job['id']}/confirm", headers=owner, json={"items": [
        {"candidate_id": c["id"], "variant_id": c["matches"][0]["id"], "quantity": 7}]})
    assert r.status_code == 200
    assert stock(client, owner, shop_setup["salt"]["id"]) == 7
    events = client.get(f"/api/v1/inventory/listings/{shop_setup['salt']['id']}/events", headers=owner).json()["events"]
    assert events[0]["event_type"] == "reconciliation" and events[0]["delta_on_hand"] == -3


def test_non_image_upload_is_rejected(client, shop_setup):
    r = client.post("/api/v1/recognition/jobs", headers=shop_setup["owner"],
                    data={"mode": "incoming", "ocr_lines": "[]"},
                    files={"image": ("evil.png", b"<?php echo 1; ?>", "image/png")})
    assert r.status_code == 400 and r.json()["error"]["code"] == "invalid_image"


# ------------------------------------------------------------ Claude provider


def test_model_output_is_validated_and_clamped():
    result = parse_claude_payload({
        "image_usable": True, "image_issues": [], "supplier_name": None, "invoice_lines": [],
        "items": [
            {"label": "Tata Salt", "brand": "Tata", "product_name": "Salt", "size_text": "1 kg",
             "visible_count": 99999, "count_reliable": True, "identity_confidence": 1.7},
            "not-an-object",
            {"label": "Maggi", "visible_count": "twelve", "identity_confidence": "high"},
        ],
    }, mode="incoming")
    assert len(result.items) == 2
    assert result.items[0].visible_count is None  # absurd counts are dropped
    assert result.items[0].identity_confidence == 1.0
    assert result.items[1].visible_count is None and result.items[1].identity_confidence == 0.0


class FakeAnthropic:
    def __init__(self, payload: dict, stop_reason: str = "end_turn"):
        self.calls = []
        payload_text = json.dumps(payload)

        def create(**kwargs):
            self.calls.append(kwargs)
            return SimpleNamespace(stop_reason=stop_reason,
                                   content=[SimpleNamespace(type="text", text=payload_text)])

        self.beta = SimpleNamespace(messages=SimpleNamespace(create=create))


@pytest.fixture()
def claude_api(tmp_path):
    fake = FakeAnthropic({
        "image_usable": True, "image_issues": [], "invoice_lines": [], "supplier_name": None,
        "items": [
            {"label": "Tata Salt 1kg", "brand": "Tata", "product_name": "Salt", "size_text": "1 kg",
             "category": "staples", "visible_text": "TATA SALT", "visible_count": 12, "count_reliable": True,
             "identity_confidence": 0.95},
            {"label": "Maggi noodles", "brand": "Maggi", "product_name": "Masala Noodles", "size_text": "70 g",
             "category": "snacks", "visible_text": None, "visible_count": 30, "count_reliable": False,
             "identity_confidence": 0.9},
        ],
    })
    settings = make_settings(tmp_path, vision_provider="claude")
    app = create_app(settings, provider_factory=lambda s, has_ocr: ClaudeVisionProvider(s, client=fake))
    with app.state.db.session() as db:
        seed_catalog(db)
    with TestClient(app) as c:
        api = Api(c)
        owner = api.login("9876570001")
        api.register_shop(owner)
        yield c, owner, fake


def test_claude_counts_are_prefilled_only_when_reliable(claude_api):
    client, owner, fake = claude_api
    job = upload(client, owner, [])
    assert job["provider"] == "claude_vision" and job["status"] == "needs_review"
    by_name = {c["matches"][0]["display_name"]: c for c in job["candidates"]}
    salt = by_name["Tata Salt 1 kg"]
    assert salt["suggested_quantity"] == 12 and salt["quantity_source"] == "ai_count"
    maggi = by_name["Maggi 2-Minute Masala Noodles 70 g"]
    assert maggi["suggested_quantity"] is None  # model wasn't sure of the count
    call = fake.calls[0]
    assert call["model"] == "claude-opus-5-5"
    assert call["output_config"]["format"]["type"] == "json_schema"
    assert call["messages"][0]["content"][0]["type"] == "image"


def test_claude_refusal_becomes_a_clear_failure(tmp_path):
    fake = FakeAnthropic({}, stop_reason="refusal")
    app = create_app(make_settings(tmp_path), provider_factory=lambda s, has_ocr: ClaudeVisionProvider(s, client=fake))
    with app.state.db.session() as db:
        seed_catalog(db)
    with TestClient(app) as c:
        api = Api(c)
        owner = api.login("9876570002")
        api.register_shop(owner)
        job = upload(c, owner, [])
        assert job["status"] == "failed" and job["error_code"] == "provider_refused"
