"""Customer search: query understanding → place → catalogue match → nearby merchant offers.

Deterministic by design. No LLM is involved, and nothing here can invent stock or
prices:

* **Place:** "near JP Nagar" or "in Jayanagar" set the search centre from the curated
  locality list. A landmark ("near RV College of Engineering") is geocoded through
  Google Places when that is configured.
* **Product:** brand, name, size, synonyms (English / Hinglish / Hindi / Kannada),
  typos and price limits are matched against the shared catalogue.
* **Shop intent:** "electrical shops", "pharmacy near me" return merchants of that
  category. Google-listed businesses are fetched separately (``/discover``) and are
  never mixed into inventory results.

Offers always compare the *same variant*, and each one carries the freshness label
from the shop's last confirmation.
"""

from __future__ import annotations

import logging

from sqlalchemy import select
from sqlalchemy.orm import Session, joinedload

from ..config import Settings
from ..db import release_sqlite_lock
from ..errors import bad_request
from ..models import Category, ProductVariant, SearchLog, Shop, ShopListing
from ..reference_data import SHOP_CATEGORY_WORDS
from .catalog import parse_query, rank_variants, variant_view
from .discovery import (
    customer_statuses,
    extract_location,
    locality_view,
    merchant_views,
    resolve_landmark,
    search_localities,
)
from .geo import haversine_m, nearby_shops
from .places import PlacesClient
from .products import _offers_for, _summary
from .text import token_match_score, tokenize

log = logging.getLogger("milega.search")

_STATUS_RANK = {"in_stock": 0, "likely": 1, "unverified": 2, "out_of_stock": 3}
_SHOP_WORDS = {"shop", "shops", "store", "stores", "dukan", "दुकान", "ಅಂಗಡಿ"}


def search_products(
    db: Session,
    settings: Settings,
    *,
    q: str,
    lat: float | None,
    lng: float | None,
    radius_km: float | None,
    sort: str = "relevance",
    category: str | None = None,
    limit: int = 20,
    offset: int = 0,
    fresh_only: bool = False,
    places: PlacesClient | None = None,
    user_id: int | None = None,
    language: str = "en",
) -> dict:
    q = (q or "").strip()
    if len(q) > 200:
        raise bad_request("query_too_long", "Search text is too long")

    # 1. Where?
    locality, landmark_phrase, rest = extract_location(db, q)
    location: dict = {"source": "device" if lat is not None and lng is not None else "none",
                      "lat": lat, "lng": lng, "locality": None, "landmark": None}
    if locality is not None:
        lat, lng = locality.lat, locality.lng
        location.update(source="query_locality", lat=lat, lng=lng, locality=locality_view(locality))
    elif landmark_phrase:
        if places is not None:
            release_sqlite_lock(db)
        landmark = resolve_landmark(places, landmark_phrase, language=language)
        if landmark:
            lat, lng = landmark["lat"], landmark["lng"]
            location.update(source="landmark", lat=lat, lng=lng, landmark=landmark)
        else:
            location["unresolved_landmark"] = landmark_phrase

    # 2. What?
    parsed = parse_query(rest)
    if category:
        parsed.category = category
    asked_for_shop = any(w in _SHOP_WORDS for w in tokenize(rest))
    shop_category = None
    shop_words = [t for t in parsed.terms if t in SHOP_CATEGORY_WORDS]
    if shop_words and (asked_for_shop or len(shop_words) == len(parsed.terms)):
        shop_category = SHOP_CATEGORY_WORDS[shop_words[0]]
        parsed.terms = [t for t in parsed.terms if t not in shop_words]
    if not parsed.terms and parsed.category and asked_for_shop:
        shop_category = shop_category or parsed.category

    if parsed.terms:
        intent = "product"
    elif shop_category or parsed.category:
        intent = "shop_category"
    elif locality is not None or location.get("landmark"):
        intent = "locality"
    else:
        raise bad_request("empty_query", "Type what you are looking for")

    # 3. Which shops are in range?
    radius_m = min(max((radius_km or settings.search_default_radius_km), 0.5), 25.0) * 1000
    statuses = customer_statuses(settings)
    if lat is not None and lng is not None:
        found = nearby_shops(db, lat, lng, radius_m, statuses=statuses, include_demo=settings.show_demo_data)
    else:
        query = select(Shop).where(Shop.status.in_(statuses)).limit(500)
        if not settings.show_demo_data:
            query = query.where(Shop.is_demo.is_(False))
        found = [(s, None) for s in db.execute(query).scalars()]
    shop_map = {s.id: (s, d) for s, d in found}

    interpretation = parsed.as_dict()
    interpretation["shop_category"] = shop_category
    response: dict = {
        "query": q,
        "intent": intent,
        "interpretation": interpretation,
        "location": location,
        "radius_km": radius_m / 1000,
        "shops_in_range": len(shop_map),
        "shops": _matching_shops(db, found, parsed.terms, shop_category or (parsed.category if intent != "product" else None), intent),
        "results": [],
        "total_results": 0,
        "offset": offset,
        "external_hint": {
            "available": settings.places_configured,
            "query": " ".join(parsed.terms) or None,
            "category": shop_category or parsed.category,
        },
        "distance_note": "Distances are straight-line, not travel distance.",
    }

    if intent != "locality" and shop_map:
        response["results"], response["total_results"] = _product_results(
            db, parsed, shop_map, sort=sort, fresh_only=fresh_only, limit=limit, offset=offset,
        )
    _log_search(db, q, intent, user_id, location, response["total_results"] + len(response["shops"]))
    return response


def _matching_shops(db: Session, found: list[tuple[Shop, float | None]], terms: list[str],
                    category: str | None, intent: str) -> list[dict]:
    picked: list[tuple[Shop, float | None]] = []
    for shop, d in found:
        if intent == "locality":
            picked.append((shop, d))
        elif category and category in (shop.categories or []):
            picked.append((shop, d))
        elif terms:
            name_tokens = set(tokenize(shop.name))
            if sum(token_match_score(t, name_tokens) for t in terms) / len(terms) >= 0.85:
                picked.append((shop, d))
    return merchant_views(db, picked[:20])


def _product_results(db: Session, parsed, shop_map: dict, *, sort: str, fresh_only: bool, limit: int,
                     offset: int) -> tuple[list[dict], int]:
    listed_ids = set(db.execute(
        select(ShopListing.variant_id).where(ShopListing.shop_id.in_(shop_map.keys()), ShopListing.is_active.is_(True))
    ).scalars())
    if not listed_ids:
        return [], 0
    if parsed.terms:
        ranked = rank_variants(db, parsed, limit=60, min_score=0.6, variant_ids=listed_ids)
        if ranked:
            # Drop weak partial matches when strong ones exist ("नमक" shouldn't list "नमकीन").
            cutoff = ranked[0][1] * 0.85
            ranked = [(v, s) for v, s in ranked if s >= cutoff]
        if parsed.category:
            ranked = [(v, s) for v, s in ranked if v.product.category_slug == parsed.category] or ranked
    else:
        variants = db.execute(
            select(ProductVariant).options(joinedload(ProductVariant.product)).where(ProductVariant.id.in_(listed_ids))
        ).unique().scalars().all()
        ranked = [(v, 1.0) for v in variants if v.product.category_slug == parsed.category][:60]
    if not ranked:
        return [], 0

    offers_by_variant = _offers_for(db, [v.id for v, _ in ranked], shop_map)

    def offer_key(o: dict):
        rank = _STATUS_RANK[o["availability"]["status"]]
        dist = o["distance_m"] if o["distance_m"] is not None else 1e12
        price = o["price_paise"] if o["price_paise"] is not None else 1e12
        out = 1 if rank == 3 else 0
        if sort == "price":
            return (out, price, dist)
        if sort == "availability":
            return (rank, dist)
        return (out, dist, price)

    results = []
    for variant, score in ranked:
        offers = offers_by_variant.get(variant.id, [])
        if parsed.max_price_paise is not None:
            offers = [o for o in offers if o["price_paise"] is not None and o["price_paise"] <= parsed.max_price_paise]
        if fresh_only:
            offers = [o for o in offers if o["availability"]["status"] in ("in_stock", "likely")]
        if not offers:
            continue
        offers.sort(key=offer_key)
        results.append({"variant": variant_view(variant), "match_score": score, "offers": offers, **_summary(offers)})

    if sort == "price":
        results.sort(key=lambda r: (r["min_price_paise"] is None, r["min_price_paise"] or 0, -r["match_score"]))
    else:
        results.sort(key=lambda r: (-r["match_score"], offer_key(r["offers"][0])))
    return results[offset: offset + limit], len(results)


def _log_search(db: Session, q: str, intent: str, user_id: int | None, location: dict, count: int) -> None:
    try:
        with db.begin_nested():
            db.add(SearchLog(
                user_id=user_id, query=q[:200], intent=intent,
                locality_slug=(location.get("locality") or {}).get("slug"),
                lat_r=round(location["lat"], 2) if location.get("lat") is not None else None,
                lng_r=round(location["lng"], 2) if location.get("lng") is not None else None,
                result_count=count,
            ))
    except Exception:  # analytics must never break search
        log.exception("search log failed")


def suggest(db: Session, settings: Settings, q: str, *, lat: float | None = None, lng: float | None = None,
            limit: int = 6) -> dict:
    """Type-ahead suggestions: products, categories, localities and Milega shops."""
    q = (q or "").strip()[:80]
    if len(q) < 2:
        return {"query": q, "products": [], "categories": [], "localities": [], "shops": []}
    parsed = parse_query(q)
    products = [variant_view(v) for v, _ in rank_variants(db, parsed, limit=limit, min_score=0.55)] if parsed.terms else []
    terms = tokenize(q)
    categories = []
    for c in db.execute(select(Category).order_by(Category.sort_order)).scalars():
        tokens = set(tokenize(f"{c.name_en} {c.name_hi} {c.name_kn} {c.slug.replace('_', ' ')}"))
        if terms and sum(token_match_score(t, tokens) for t in terms) / len(terms) >= 0.85:
            categories.append({"slug": c.slug, "name_en": c.name_en, "name_hi": c.name_hi, "name_kn": c.name_kn,
                               "icon": c.icon})
    shop_q = select(Shop).where(Shop.status.in_(customer_statuses(settings)))
    if not settings.show_demo_data:
        shop_q = shop_q.where(Shop.is_demo.is_(False))
    shops = []
    for shop in db.execute(shop_q.limit(2000)).scalars():
        name_tokens = set(tokenize(shop.name))
        if terms and sum(token_match_score(t, name_tokens) for t in terms) / len(terms) >= 0.85:
            d = haversine_m(lat, lng, shop.lat, shop.lng) if lat is not None and lng is not None else None
            shops.append({"id": shop.id, "name": shop.name, "locality": shop.locality,
                          "distance_m": round(d) if d is not None else None})
    shops.sort(key=lambda s: s["distance_m"] if s["distance_m"] is not None else 1e12)
    return {
        "query": q,
        "products": products,
        "categories": categories[:4],
        "localities": [locality_view(loc) for loc, _ in search_localities(db, q, limit=4)],
        "shops": shops[:4],
    }


def distance_from(lat: float | None, lng: float | None, shop: Shop) -> float | None:
    if lat is None or lng is None:
        return None
    return haversine_m(lat, lng, shop.lat, shop.lng)
