"""Voice commands in English, Hindi and Kannada produce proposals, never direct changes."""

from decimal import Decimal

import pytest

from app.services.voice import parse_command


@pytest.mark.parametrize("text, action, qty, product", [
    ("Add 20 packets of Maggi", "add", 20, "maggi"),
    ("Five packets of Tata Salt sold", "sell", 5, "tata salt"),
    ("Make Parle-G out of stock", "out_of_stock", None, "make parle g"),
    ("twenty five tata salt received", "add", 25, "tata salt"),
    ("set tata salt to 8", "set", 8, "tata salt"),
    ("पांच टाटा नमक बिक गए", "sell", 5, "टाटा नमक"),
    ("मैगी के बीस पैकेट आए", "add", 20, "मैगी"),
    ("ಪಾರ್ಲೆ ಜಿ ಖಾಲಿ", "out_of_stock", None, "ಪಾರ್ಲೆ ಜಿ"),
    ("ಹತ್ತು ಮ್ಯಾಗಿ ಸೇರಿಸಿ", "add", 10, "ಮ್ಯಾಗಿ"),
    ("maggi bees packet aaye", "add", 20, "maggi"),
])
def test_parse_command(text, action, qty, product):
    parsed = parse_command(text)
    assert parsed.action == action
    assert parsed.quantity == (Decimal(qty) if qty is not None else None)
    assert parsed.product_text == product


def test_pack_size_is_not_mistaken_for_quantity():
    parsed = parse_command("add 12 aashirvaad atta 5 kg")
    assert parsed.quantity == 12
    assert parsed.size_hint == "5 kg"


def _interpret(client, headers, text):
    r = client.post("/api/v1/voice/interpret", headers=headers, json={"text": text, "language": "en"})
    assert r.status_code == 200, r.text
    return r.json()


def test_interpret_proposes_but_does_not_change_stock(client, shop_setup):
    owner, maggi = shop_setup["owner"], shop_setup["maggi"]
    result = _interpret(client, owner, "Add 20 packets of Maggi")
    assert result["requires_confirmation"] is True
    assert result["proposal"] == {"listing_id": maggi["id"], "mode": "add", "quantity": 20.0, "new_on_hand": 25.0}
    on_hand = client.get(f"/api/v1/inventory/listings/{maggi['id']}", headers=owner).json()["on_hand"]
    assert on_hand == 5


def test_hindi_sale_command_matches_listing(client, shop_setup):
    result = _interpret(client, shop_setup["owner"], "पांच टाटा नमक बिक गए")
    assert result["proposal"]["listing_id"] == shop_setup["salt"]["id"]
    assert result["proposal"]["mode"] == "sell" and result["proposal"]["new_on_hand"] == 5


def test_ambiguous_command_asks_to_choose(api, client, shop_setup):
    owner = shop_setup["owner"]
    api.add_listing(owner, "maggi masala noodles 280 g", 3, 5600)
    result = _interpret(client, owner, "add 5 maggi")
    assert result["needs_choice"] is True
    assert result["proposal"] is None
    assert len(result["matches"]) >= 2


def test_loose_goods_quantity_from_weight(api, client, shop_setup):
    owner = shop_setup["owner"]
    rice = api.add_listing(owner, "sona masoori rice loose", 20, 6200)
    result = _interpret(client, owner, "sold 2 kg sona masoori rice")
    assert result["proposal"]["listing_id"] == rice["id"]
    assert result["proposal"]["quantity"] == 2.0 and result["proposal"]["new_on_hand"] == 18.0


def test_unknown_product_is_reported(client, shop_setup):
    result = _interpret(client, shop_setup["owner"], "add 5 bananas")
    assert result["proposal"] is None
    assert "product_not_found" in result["problems"]
