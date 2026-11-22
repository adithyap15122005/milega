"""Voice inventory commands in English, Hindi and Kannada (incl. romanised).

Speech-to-text happens on the phone (Android SpeechRecognizer, en-IN / hi-IN /
kn-IN). This module turns the transcript into a *proposal*:

    "Add 20 packets of Maggi"        -> add 20 to Maggi
    "पांच टाटा नमक बिक गए"           -> sell 5 Tata Salt
    "ಪಾರ್ಲೆ ಜಿ ಖಾಲಿ"                   -> mark Parle-G out of stock

It never changes stock. The app shows the proposal and the shopkeeper taps
Confirm, which calls the normal inventory endpoint with ``source=voice``.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import InventoryBalance, ShopListing
from .catalog import ParsedQuery, confidence_level, rank_variants
from .text import normalize, tokenize
from .units import from_milli, parse_sizes, unit_label

ACTION_PHRASES: dict[str, list[str]] = {
    # Checked in this order; longest phrase first within each action.
    "out_of_stock": [
        "out of stock", "sold out", "no stock", "not available", "finished", "khatam", "khatm",
        "khatam ho gaya", "stock nahi", "खत्म", "ख़त्म", "खतम", "स्टॉक में नहीं", "स्टॉक नहीं",
        "ಖಾಲಿ", "ಮುಗಿದಿದೆ", "ಮುಗಿಯಿತು", "ಸ್ಟಾಕ್ ಇಲ್ಲ", "khaali", "khali", "mugidide",
    ],
    "sell": [
        "sold", "sell", "sale", "gone", "minus", "reduce", "decrease", "less",
        "बिक गया", "बिक गए", "बिक गये", "बिका", "बिके", "बेचा", "बेच दिया", "बेचे", "घटाओ", "कम करो",
        "bik gaya", "bik gaye", "bika", "becha", "bech diya", "ghatao", "kam karo",
        "ಮಾರಾಟ", "ಮಾರಿದೆ", "ಮಾರಿದ್ದೇನೆ", "ಮಾರಾಯ್ತು", "ಮಾರಿದೆವು", "ಕಡಿಮೆ ಮಾಡಿ", "maaride", "maratta",
    ],
    "add": [
        "add", "added", "received", "receive", "got", "came", "arrived", "bought", "purchased",
        "restock", "stock in", "plus", "increase", "new stock",
        "जोड़ो", "जोड़ दो", "जोड़", "डालो", "डाल दो", "आया", "आए", "आये", "आ गया", "आ गए", "आ गये",
        "मंगाया", "बढ़ाओ", "add karo", "daalo", "dalo", "aaya", "aaye", "aa gaye", "jodo",
        "ಸೇರಿಸಿ", "ಸೇರಿಸು", "ಬಂದಿದೆ", "ಬಂತು", "ಬಂದಿವೆ", "ಹಾಕಿ", "serisi", "bandide",
    ],
    "set": [
        "set", "set to", "make it", "change to", "update to", "stock is", "count is", "total", "we have",
        "कुल", "ಒಟ್ಟು", "kul",
    ],
}

NUMBER_WORDS: dict[str, Decimal] = {}
_NUM_SOURCE = {
    # English
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7,
    "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14,
    "fifteen": 15, "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19, "twenty": 20,
    "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90,
    "hundred": 100, "dozen": 12, "half": 0.5,
    # Hindi
    "एक": 1, "दो": 2, "तीन": 3, "चार": 4, "पांच": 5, "पाँच": 5, "छह": 6, "छः": 6, "छे": 6, "सात": 7,
    "आठ": 8, "नौ": 9, "दस": 10, "ग्यारह": 11, "बारह": 12, "तेरह": 13, "चौदह": 14, "पंद्रह": 15,
    "सोलह": 16, "सत्रह": 17, "अठारह": 18, "उन्नीस": 19, "बीस": 20, "पच्चीस": 25, "तीस": 30,
    "चालीस": 40, "पचास": 50, "साठ": 60, "सत्तर": 70, "अस्सी": 80, "नब्बे": 90, "सौ": 100,
    "आधा": 0.5, "डेढ़": 1.5, "ढाई": 2.5, "दर्जन": 12,
    "ek": 1, "teen": 3, "char": 4, "chaar": 4, "paanch": 5, "panch": 5, "chhe": 6, "saat": 7,
    "aath": 8, "nau": 9, "das": 10, "barah": 12, "pandrah": 15, "bees": 20, "pachees": 25,
    "pachchis": 25, "tees": 30, "chalees": 40, "pachaas": 50, "pachas": 50, "sau": 100,
    "aadha": 0.5, "dedh": 1.5, "dhai": 2.5, "darjan": 12,
    # Kannada
    "ಒಂದು": 1, "ಎರಡು": 2, "ಮೂರು": 3, "ನಾಲ್ಕು": 4, "ಐದು": 5, "ಆರು": 6, "ಏಳು": 7, "ಎಂಟು": 8,
    "ಒಂಬತ್ತು": 9, "ಹತ್ತು": 10, "ಹನ್ನೆರಡು": 12, "ಹದಿನೈದು": 15, "ಇಪ್ಪತ್ತು": 20, "ಇಪ್ಪತ್ತೈದು": 25,
    "ಮೂವತ್ತು": 30, "ನಲವತ್ತು": 40, "ಐವತ್ತು": 50, "ನೂರು": 100, "ಅರ್ಧ": 0.5,
    "ondu": 1, "eradu": 2, "mooru": 3, "naalku": 4, "aidu": 5, "elu": 7, "entu": 8,
    "ombattu": 9, "hattu": 10, "ippattu": 20, "aivattu": 50, "nooru": 100,
}
for _w, _v in _NUM_SOURCE.items():
    NUMBER_WORDS[_w] = Decimal(str(_v))
# Romanised Hindi "do" (two) clashes with English; only used when nothing else matches.
_WEAK_NUMBER_WORDS = {"do": Decimal(2)}

FILLER = {
    "of", "the", "a", "an", "please", "pls", "my", "in", "to", "it", "from", "and", "has", "have",
    "been", "is", "are", "was", "were", "today", "now", "stock", "inventory", "item", "items",
    "packet", "packets", "pack", "packs", "pkt", "pcs", "pc", "piece", "pieces", "unit", "units",
    "box", "boxes", "bottle", "bottles", "nos", "number",
    "पैकेट", "को", "का", "की", "के", "में", "है", "हैं", "गए", "गया", "दो", "करो", "हो",
    "ಪ್ಯಾಕೆಟ್", "ಅನ್ನು", "ಇದೆ", "ಆಗಿದೆ",
    "karo", "kar", "ho", "gaya", "gaye", "hai", "ko", "ka", "ki", "ke",
}


@dataclass
class VoiceParse:
    action: str | None
    quantity: Decimal | None
    size_hint: str | None
    product_text: str
    problems: list[str] = field(default_factory=list)


def _clean(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    text = normalize(text)
    return " " + re.sub(r"[,.!?;:।]", " ", text) + " "


def _take_phrase(text: str, phrases: list[str]) -> tuple[bool, str]:
    for phrase in sorted(phrases, key=len, reverse=True):
        needle = f" {phrase} "
        if needle in text:
            return True, text.replace(needle, " ", 1)
    return False, text


def _to_number(token: str) -> Decimal | None:
    if any(ch.isdigit() for ch in token):
        try:
            return Decimal(str(float(token)))  # float() accepts Devanagari/Kannada digits too
        except ValueError:
            return None
    return NUMBER_WORDS.get(token)


def parse_command(transcript: str) -> VoiceParse:
    text = _clean(transcript)
    problems: list[str] = []
    action = None
    for candidate, phrases in ACTION_PHRASES.items():
        found, text = _take_phrase(text, phrases)
        if found:
            action = candidate
            break

    # Pack sizes ("5 kg", "70g") describe the product, not how many.
    sizes = parse_sizes(text)
    size_hint = None
    if sizes:
        first = sizes[0]
        size_hint = text[first.span[0]:first.span[1]].strip()

    tokens = tokenize(text)
    quantity: Decimal | None = None
    rest: list[str] = []
    i = 0
    size_tokens = set(tokenize(" ".join(text[s.span[0]:s.span[1]] for s in sizes)))
    while i < len(tokens):
        tok = tokens[i]
        value = _to_number(tok)
        if value is not None and quantity is None and tok not in size_tokens:
            total = value
            j = i + 1
            while j < len(tokens):  # "twenty five", "two hundred"
                nxt = _to_number(tokens[j])
                if nxt is None or tokens[j] in size_tokens:
                    break
                total = total * nxt if nxt == 100 else total + nxt
                j += 1
            quantity = total
            i = j
            continue
        if tok not in FILLER and tok not in size_tokens:
            rest.append(tok)
        i += 1
    if quantity is None:
        for idx, tok in enumerate(rest):
            if tok in _WEAK_NUMBER_WORDS:
                quantity = _WEAK_NUMBER_WORDS[tok]
                rest.pop(idx)
                break
    if action is None:
        problems.append("no_action")
    if quantity is None and action in ("add", "sell", "set"):
        problems.append("no_quantity")
    product_text = " ".join(rest)
    if not product_text:
        problems.append("no_product")
    return VoiceParse(action=action, quantity=quantity, size_hint=size_hint, product_text=product_text, problems=problems)


def interpret(db: Session, shop_id: int, transcript: str, language: str | None = None) -> dict:
    parsed = parse_command(transcript)
    listings = {
        l.variant_id: l
        for l in db.execute(
            select(ShopListing).where(ShopListing.shop_id == shop_id, ShopListing.is_active.is_(True))
        ).scalars()
    }
    matches: list[dict] = []
    if parsed.product_text and listings:
        query = ParsedQuery(raw=parsed.product_text, terms=tokenize(parsed.product_text))
        if parsed.size_hint:
            sizes = parse_sizes(parsed.size_hint)
            query.size = sizes[0] if sizes else None
        ranked = rank_variants(db, query, limit=4, min_score=0.5, variant_ids=set(listings))
        if not ranked and query.size is not None:  # size may describe a loose quantity
            query.size = None
            ranked = rank_variants(db, query, limit=4, min_score=0.5, variant_ids=set(listings))
        for variant, score in ranked:
            listing = listings[variant.id]
            bal = db.get(InventoryBalance, listing.id)
            matches.append({
                "listing_id": listing.id,
                "name": listing.custom_name or variant.display_name,
                "score": score,
                "on_hand": from_milli(bal.on_hand_milli) if bal else 0,
                "sell_unit": variant.sell_unit,
                "unit_label": unit_label(variant.sell_unit),
            })

    quantity = parsed.quantity
    problems = list(parsed.problems)
    top = matches[0] if matches else None
    # "sold 2 kg rice" for a loose item: the size *is* the quantity.
    if quantity is None and top and parsed.size_hint and top["sell_unit"] in {"kg", "g", "l", "ml", "m"}:
        sizes = parse_sizes(parsed.size_hint)
        if sizes:
            s = sizes[0]
            factor = {"kg": 1000, "g": 1, "l": 1000, "ml": 1, "m": 100}[top["sell_unit"]]
            if (s.unit == "g" and top["sell_unit"] in {"kg", "g"}) or (s.unit == "ml" and top["sell_unit"] in {"l", "ml"}) or (s.unit == "cm" and top["sell_unit"] == "m"):
                quantity = Decimal(str(s.value / factor))
                problems = [p for p in problems if p != "no_quantity"]

    if not matches and "no_product" not in problems:
        problems.append("product_not_found")
    level = confidence_level(matches[0]["score"], matches[1]["score"] if len(matches) > 1 else None) if matches else "low"
    if len(matches) == 1 and matches[0]["score"] >= 0.75:
        level = "high"
    needs_choice = len(matches) > 1 and level != "high"
    proposal = None
    # Only propose a specific product when it is unambiguous; otherwise the app
    # asks the shopkeeper to pick from `matches` first.
    if top and parsed.action and (quantity is not None or parsed.action == "out_of_stock") and level == "high":
        if parsed.action == "out_of_stock":
            proposal = {"listing_id": top["listing_id"], "mode": "out_of_stock", "quantity": 0,
                        "new_on_hand": 0}
        elif parsed.action == "set":
            proposal = {"listing_id": top["listing_id"], "mode": "set", "quantity": float(quantity),
                        "new_on_hand": float(quantity)}
        else:
            sign = 1 if parsed.action == "add" else -1
            proposal = {"listing_id": top["listing_id"], "mode": parsed.action, "quantity": float(quantity),
                        "new_on_hand": round(top["on_hand"] + sign * float(quantity), 3)}
    return {
        "transcript": transcript,
        "language": language,
        "action": parsed.action,
        "quantity": float(quantity) if quantity is not None else None,
        "product_text": parsed.product_text,
        "matches": matches,
        "confidence": level,
        "needs_choice": needs_choice,
        "proposal": proposal,
        "problems": problems,
        # Stock is only changed after the shopkeeper confirms in the app.
        "requires_confirmation": True,
    }
