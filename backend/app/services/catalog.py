"""Product catalogue: creation, matching and query understanding.

The same matcher serves three callers:
* customer search ("aashirvaad atta 5 kg"),
* AI recognition (brand / name / size read from a package), and
* voice commands ("add 20 packets of maggi").
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from sqlalchemy import or_, select
from sqlalchemy.orm import Session, joinedload

from ..errors import bad_request
from ..models import Category, Product, ProductVariant, ProductVerification
from .text import build_search_text, expand, normalize, token_match_score, tokenize
from .units import _SIZE_UNITS, SELL_UNITS, Size, format_size, parse_size, parse_sizes

_UNIT_WORDS = set(_SIZE_UNITS) | {"ltr", "kg", "gm"}

STOPWORDS = {
    "near", "me", "nearby", "around", "where", "can", "i", "get", "buy", "today", "now", "tonight",
    "shop", "shops", "store", "stores", "selling", "sells", "sell", "available", "availability",
    "the", "a", "an", "in", "at", "for", "of", "with", "to", "find", "want", "need", "is", "are",
    "any", "which", "who", "has", "have", "please", "pls", "plz", "and", "or", "my", "some",
    "rs", "inr", "rupees", "rupee", "price", "cheap", "cheapest", "best", "good", "new", "open",
    "kahan", "kaha", "milega", "chahiye", "hai", "ka", "ki", "ke", "mein", "me", "se", "kam",
    "कहाँ", "कहां", "मिलेगा", "चाहिए", "है", "का", "की", "के", "में", "से", "कम",
    "ಎಲ್ಲಿ", "ಸಿಗುತ್ತದೆ", "ಬೇಕು", "ಇದೆ",
    "packet", "packets", "pack", "pkt", "pcs", "piece", "pieces", "unit", "units",
    "पैकेट", "ಪ್ಯಾಕೆಟ್",
}

CATEGORY_WORDS = {
    "kirana": "staples", "grocery": "staples", "groceries": "staples", "provision": "staples",
    "general": None, "stationery": "stationery", "stationary": "stationery",
    "electrical": "electrical", "electricals": "electrical", "electric": "electrical",
    "hardware": "hardware", "medical": "personal_care", "pharmacy": "personal_care",
    "vegetable": "fruits_vegetables", "vegetables": "fruits_vegetables", "fruits": "fruits_vegetables",
    "dairy": "dairy",
}

_PRICE_PATTERNS = [
    re.compile(r"(?:under|below|less than|upto|up to|within|max|maximum|<|<=)\s*(?:rs\.?|inr)?\s*(\d+(?:\.\d+)?)"),
    re.compile(r"(\d+(?:\.\d+)?)\s*(?:rs|rupees|रुपये|रुपए)?\s*(?:se kam|से कम|ke andar|tak|तक|ಒಳಗೆ)"),
]


@dataclass
class ParsedQuery:
    raw: str
    terms: list[str]
    size: Size | None = None
    max_price_paise: int | None = None
    category: str | None = None
    ignored: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "terms": self.terms,
            "size": format_size(self.size.value, self.size.unit) if self.size else None,
            "max_price_paise": self.max_price_paise,
            "category": self.category,
        }


def parse_query(raw: str) -> ParsedQuery:
    text = normalize(raw)
    max_price = None
    for pattern in _PRICE_PATTERNS:
        m = pattern.search(text)
        if m:
            max_price = int(round(float(m.group(1)) * 100))
            text = text[: m.start()] + " " + text[m.end():]
            break
    sizes = parse_sizes(text)
    size = sizes[0] if sizes else None
    for s in sorted(sizes, key=lambda s: s.span[0], reverse=True):
        text = text[: s.span[0]] + " " + text[s.span[1]:]
    terms: list[str] = []
    category = None
    ignored: list[str] = []
    for tok in tokenize(text):
        if tok in CATEGORY_WORDS:
            category = CATEGORY_WORDS[tok] or category
            ignored.append(tok)
        elif tok in STOPWORDS:
            ignored.append(tok)
        else:
            terms.append(tok)
    return ParsedQuery(raw=raw, terms=terms, size=size, max_price_paise=max_price, category=category, ignored=ignored)


def score_variant(terms: list[str], variant: ProductVariant, *, brand_hint: str | None = None) -> float:
    """Fraction of query terms found in the variant's searchable text (0..1)."""
    if not terms:
        return 0.0
    tokens = set(variant.search_text.split())
    total = 0.0
    for term in terms:
        total += token_match_score(term, tokens)
    coverage = total / len(terms)
    # Prefer the variant whose own name is fully explained by the query, so
    # "tata salt" ranks "Tata Salt" above "Tata Salt Lite".
    name_tokens = [
        t for t in tokenize(variant.display_name)
        if not any(c.isdigit() for c in t) and t not in _UNIT_WORDS
    ]
    term_set = set(terms)
    covered = sum(1 for nt in name_tokens if token_match_score(nt, term_set) >= 0.85)
    specificity = covered / len(name_tokens) if name_tokens else 1.0
    score = coverage * (0.85 + 0.15 * specificity)
    if brand_hint and variant.product.brand:
        brand_tokens = set(tokenize(variant.product.brand))
        if any(token_match_score(t, brand_tokens) >= 0.85 for t in tokenize(brand_hint)):
            score = min(1.0, score + 0.1)
        else:
            score *= 0.75
    return score


def candidate_variants(db: Session, terms: list[str], *, limit: int = 400, include_provisional: bool = True) -> list[ProductVariant]:
    """Cheap SQL prefilter (substring on any term or synonym prefix), scored in Python."""
    probes: set[str] = set()
    for term in terms:
        for opt in expand(term):
            probes.add(opt[:4] if len(opt) > 4 and not any(c.isdigit() for c in opt) else opt)
    if not probes:
        return []
    statuses = ["verified", "provisional"] if include_provisional else ["verified"]
    q = (
        select(ProductVariant)
        .options(joinedload(ProductVariant.product))
        .where(ProductVariant.status.in_(statuses))
        .where(or_(*[ProductVariant.search_text.contains(p) for p in probes]))
        .limit(limit)
    )
    return list(db.execute(q).unique().scalars().all())


def match_variants(
    db: Session,
    text: str,
    *,
    brand: str | None = None,
    size_text: str | None = None,
    limit: int = 5,
    min_score: float = 0.45,
    variant_ids: set[int] | None = None,
) -> list[tuple[ProductVariant, float]]:
    """Rank catalogue variants for free text (+ optional brand / size hints).

    When the size is known, variants of a *different* size are excluded and the
    same size is rewarded; when it is unknown, all sizes stay as options so the
    shopkeeper can pick (never silently choose a pack size).
    """
    parsed = parse_query(" ".join(filter(None, [brand, text, size_text])))
    return rank_variants(db, parsed, brand=brand, limit=limit, min_score=min_score, variant_ids=variant_ids)


def rank_variants(
    db: Session,
    parsed: ParsedQuery,
    *,
    brand: str | None = None,
    limit: int = 5,
    min_score: float = 0.45,
    variant_ids: set[int] | None = None,
) -> list[tuple[ProductVariant, float]]:
    size = parsed.size
    terms = parsed.terms
    if not terms:
        return []
    pool = candidate_variants(db, terms, limit=2000 if variant_ids is not None else 400)
    if variant_ids is not None:
        pool = [v for v in pool if v.id in variant_ids]
    scored: list[tuple[ProductVariant, float]] = []
    for v in pool:
        s = score_variant(terms, v, brand_hint=brand)
        if size is not None:
            if v.size_value is not None and v.size_unit == size.unit:
                if not size.matches(v.size_value, v.size_unit):
                    continue  # never offer a different pack size as the same thing
            else:
                s *= 0.9  # size unknown for this variant: keep, but below exact-size matches
        if v.status == "provisional":
            s *= 0.9
        if s >= min_score:
            scored.append((v, round(s, 3)))
    scored.sort(key=lambda x: (-x[1], x[0].size_value or 0, x[0].display_name))
    return scored[:limit]


def confidence_level(top: float, second: float | None, *, provider_confidence: float = 1.0) -> str:
    combined = min(top, provider_confidence)
    margin = top - (second or 0.0)
    if combined >= 0.8 and margin >= 0.12:
        return "high"
    if combined >= 0.55:
        return "medium"
    return "low"


def variant_view(v: ProductVariant) -> dict:
    return {
        "id": v.id,
        "display_name": v.display_name,
        "brand": v.product.brand,
        "product_name": v.product.name,
        "category": v.product.category_slug,
        "size_label": v.size_label,
        "sell_unit": v.sell_unit,
        "is_loose": v.is_loose,
        "status": v.status,
        "image_key": v.image_key,
    }


def create_variant(
    db: Session,
    *,
    name: str,
    brand: str | None,
    category: str,
    size_label: str | None,
    sell_unit: str = "pc",
    aliases: list[str] | None = None,
    status: str = "provisional",
    created_by: int | None = None,
    shop_id: int | None = None,
) -> ProductVariant:
    name = (name or "").strip()
    if len(name) < 2:
        raise bad_request("invalid_name", "Enter the product name")
    if sell_unit not in SELL_UNITS:
        raise bad_request("invalid_unit", "Unknown unit")
    if db.get(Category, category) is None:
        category = "other"
    brand = (brand or "").strip() or None
    size_label = (size_label or "").strip() or None
    size_value, size_unit = parse_size(size_label)

    # Re-use an existing variant when it is clearly the same product.
    probe = " ".join(filter(None, [brand, name, size_label]))
    for existing, score in match_variants(db, probe, brand=brand, size_text=size_label, limit=1, min_score=0.95):
        same_size = (size_value is None and existing.size_value is None) or (
            size_value is not None and existing.size_unit == size_unit
            and abs((existing.size_value or 0) - size_value) < 1e-6
        )
        if same_size:
            return existing

    product = Product(brand=brand, name=name, category_slug=category)
    db.add(product)
    db.flush()
    display = " ".join(filter(None, [brand if brand and brand.lower() not in name.lower() else None, name, size_label]))
    variant = ProductVariant(
        product_id=product.id,
        display_name=display,
        size_label=size_label,
        size_value=size_value,
        size_unit=size_unit,
        sell_unit=sell_unit,
        is_loose=sell_unit in {"kg", "g", "l", "ml", "m"},
        aliases=aliases or [],
        search_text=build_search_text(brand, name, size_label, category, aliases=aliases),
        status=status,
        created_by_user_id=created_by,
    )
    db.add(variant)
    db.flush()
    variant.product = product
    if status == "provisional":
        db.add(ProductVerification(
            kind="provisional_product", variant_id=variant.id, shop_id=shop_id, submitted_by=created_by,
            payload={"name": name, "brand": brand, "size_label": size_label, "category": category},
        ))
    return variant


def refresh_search_text(variant: ProductVariant) -> None:
    variant.search_text = build_search_text(
        variant.product.brand, variant.product.name, variant.size_label, variant.product.category_slug,
        variant.display_name, aliases=variant.aliases,
    )
