"""Customer product search: query understanding -> catalogue match -> nearby offers.

Offers always compare the *same variant* (Tata Salt 1 kg is never compared with
Tata Salt 500 g) and carry an honest availability label derived from when the
shop last confirmed its stock.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session, joinedload

from ..config import Settings
from ..errors import bad_request
from ..models import InventoryBalance, ProductVariant, Shop, ShopListing
from .catalog import parse_query, rank_variants, variant_view
from .geo import haversine_m, nearby_shops, open_now
from .inventory import freshness
from .units import unit_label

_STATUS_RANK = {"in_stock": 0, "likely": 1, "unverified": 2, "out_of_stock": 3}


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
) -> dict:
    q = (q or "").strip()
    if len(q) > 200:
        raise bad_request("query_too_long", "Search text is too long")
    parsed = parse_query(q)
    if category:
        parsed.category = category
    if not parsed.terms and not parsed.category:
        raise bad_request("empty_query", "Type what you are looking for")

    radius_m = min(max((radius_km or settings.search_default_radius_km), 0.5), 25.0) * 1000
    if lat is not None and lng is not None:
        shops = nearby_shops(db, lat, lng, radius_m)
    else:
        shops = [(s, None) for s in db.execute(select(Shop).where(Shop.status == "active").limit(500)).scalars()]
    shop_map = {s.id: (s, d) for s, d in shops}
    response = {"query": q, "interpretation": parsed.as_dict(), "radius_km": radius_m / 1000,
                "shops_in_range": len(shop_map), "results": []}
    if not shop_map:
        return response

    # Variants actually listed by shops in range.
    listed_ids = set(db.execute(
        select(ShopListing.variant_id).where(
            ShopListing.shop_id.in_(shop_map.keys()), ShopListing.is_active.is_(True)
        )
    ).scalars())
    if not listed_ids:
        return response

    if parsed.terms:
        ranked = rank_variants(db, parsed, limit=limit, min_score=0.6, variant_ids=listed_ids)
        if ranked:
            # Drop weak partial matches when strong ones exist ("नमक" shouldn't list "नमकीन").
            cutoff = ranked[0][1] * 0.85
            ranked = [(v, s) for v, s in ranked if s >= cutoff]
        if parsed.category:
            ranked = [(v, s) for v, s in ranked if v.product.category_slug == parsed.category] or ranked
    else:
        variants = db.execute(
            select(ProductVariant).options(joinedload(ProductVariant.product))
            .where(ProductVariant.id.in_(listed_ids))
        ).unique().scalars().all()
        ranked = [(v, 1.0) for v in variants if v.product.category_slug == parsed.category][:limit]
    if not ranked:
        return response

    scores = {v.id: s for v, s in ranked}
    rows = db.execute(
        select(ShopListing, InventoryBalance)
        .join(InventoryBalance, InventoryBalance.listing_id == ShopListing.id)
        .where(
            ShopListing.variant_id.in_(scores.keys()),
            ShopListing.shop_id.in_(shop_map.keys()),
            ShopListing.is_active.is_(True),
        )
    ).all()

    offers_by_variant: dict[int, list[dict]] = {}
    for listing, balance in rows:
        if parsed.max_price_paise is not None and (
            listing.price_paise is None or listing.price_paise > parsed.max_price_paise
        ):
            continue
        shop, distance = shop_map[listing.shop_id]
        avail = freshness(balance)
        offers_by_variant.setdefault(listing.variant_id, []).append({
            "listing_id": listing.id,
            "price_paise": listing.price_paise,
            "distance_m": round(distance) if distance is not None else None,
            "availability": avail,
            "reservable": avail["status"] != "out_of_stock",
            "unit_label": unit_label(listing.variant.sell_unit),
            "shop": {
                "id": shop.id, "name": shop.name, "locality": shop.locality, "lat": shop.lat, "lng": shop.lng,
                "verified": shop.location_verified, "is_demo": shop.is_demo, "open_now": open_now(shop),
            },
        })

    def offer_key(o: dict):
        rank = _STATUS_RANK[o["availability"]["status"]]
        dist = o["distance_m"] if o["distance_m"] is not None else 1e12
        price = o["price_paise"] if o["price_paise"] is not None else 1e12
        out = 1 if rank == 3 else 0
        if sort == "price":
            return (out, price, dist)
        if sort == "availability":
            return (rank, dist)
        return (out, dist, price)  # nearest / relevance

    results = []
    for variant, score in ranked:
        offers = offers_by_variant.get(variant.id)
        if not offers:
            continue
        offers.sort(key=offer_key)
        prices = [o["price_paise"] for o in offers if o["price_paise"] is not None and o["reservable"]]
        results.append({
            "variant": variant_view(variant),
            "match_score": score,
            "offers": offers,
            "min_price_paise": min(prices) if prices else None,
            "in_stock_count": sum(1 for o in offers if o["reservable"]),
        })

    if sort == "price":
        results.sort(key=lambda r: (r["min_price_paise"] is None, r["min_price_paise"] or 0, -r["match_score"]))
    else:
        results.sort(key=lambda r: (-r["match_score"], offer_key(r["offers"][0])))
    response["results"] = results
    return response


def distance_from(lat: float | None, lng: float | None, shop: Shop) -> float | None:
    if lat is None or lng is None:
        return None
    return haversine_m(lat, lng, shop.lat, shop.lng)
