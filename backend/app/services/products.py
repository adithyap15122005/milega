"""Product detail pages and "available near you" — built only from merchant-confirmed listings.

Nothing here estimates a price or availability: unknown prices stay ``None`` and
every offer carries the freshness label derived from the shop's last confirmation.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session, joinedload

from ..config import Settings
from ..errors import not_found
from ..models import InventoryBalance, ProductVariant, Shop, ShopListing
from .catalog import parse_query, rank_variants, variant_view
from .discovery import customer_statuses
from .geo import nearby_shops, open_now, merchant_state
from .inventory import freshness
from .units import unit_label, unit_price

_STATUS_RANK = {"in_stock": 0, "likely": 1, "unverified": 2, "out_of_stock": 3}


def offer_view(listing: ShopListing, balance: InventoryBalance, shop: Shop, distance: float | None) -> dict:
    variant = listing.variant
    avail = freshness(balance)
    return {
        "listing_id": listing.id,
        "variant_id": variant.id,
        "price_paise": listing.price_paise,
        "unit_price": unit_price(listing.price_paise, variant.size_value, variant.size_unit, variant.sell_unit),
        "distance_m": round(distance) if distance is not None else None,
        "availability": avail,
        "reservable": avail["status"] != "out_of_stock" and shop.status == "active",
        "unit_label": unit_label(variant.sell_unit),
        "shop": {
            "id": shop.id, "name": shop.name, "locality": shop.locality, "address_line": shop.address_line,
            "lat": shop.lat, "lng": shop.lng, "verified": shop.location_verified, "is_demo": shop.is_demo,
            "merchant_state": merchant_state(shop), "open_now": open_now(shop),
        },
    }


def _offers_for(db: Session, variant_ids: list[int], shop_map: dict[int, tuple[Shop, float | None]]) -> dict[int, list[dict]]:
    if not variant_ids or not shop_map:
        return {}
    rows = db.execute(
        select(ShopListing, InventoryBalance)
        .options(joinedload(ShopListing.variant))
        .join(InventoryBalance, InventoryBalance.listing_id == ShopListing.id)
        .where(ShopListing.variant_id.in_(variant_ids), ShopListing.shop_id.in_(shop_map.keys()),
               ShopListing.is_active.is_(True))
    ).all()
    out: dict[int, list[dict]] = {}
    for listing, balance in rows:
        shop, distance = shop_map[listing.shop_id]
        out.setdefault(listing.variant_id, []).append(offer_view(listing, balance, shop, distance))
    for offers in out.values():
        offers.sort(key=lambda o: (o["availability"]["status"] == "out_of_stock",
                                   o["distance_m"] if o["distance_m"] is not None else 1e12,
                                   o["price_paise"] if o["price_paise"] is not None else 1e12))
    return out


def _summary(offers: list[dict]) -> dict:
    priced = [o for o in offers if o["price_paise"] is not None and o["reservable"]]
    return {
        "min_price_paise": min((o["price_paise"] for o in priced), default=None),
        "max_price_paise": max((o["price_paise"] for o in priced), default=None),
        "in_stock_count": sum(1 for o in offers if o["reservable"]),
        "shop_count": len(offers),
    }


def product_detail(db: Session, settings: Settings, variant_id: int, *, lat: float | None, lng: float | None,
                   radius_km: float | None) -> dict:
    variant = db.execute(
        select(ProductVariant).options(joinedload(ProductVariant.product)).where(ProductVariant.id == variant_id)
    ).scalar_one_or_none()
    if variant is None or variant.status == "merged":
        raise not_found("Product")
    radius_m = min(max(radius_km or settings.search_default_radius_km, 0.5), 25.0) * 1000
    statuses = customer_statuses(settings)
    if lat is not None and lng is not None:
        found = nearby_shops(db, lat, lng, radius_m, statuses=statuses, include_demo=settings.show_demo_data)
    else:
        q = select(Shop).where(Shop.status.in_(statuses)).limit(500)
        if not settings.show_demo_data:
            q = q.where(Shop.is_demo.is_(False))
        found = [(s, None) for s in db.execute(q).scalars()]
    shop_map = {s.id: (s, d) for s, d in found}

    # Other pack sizes of the same product, and substitutes from other brands.
    sizes = db.execute(
        select(ProductVariant).options(joinedload(ProductVariant.product))
        .where(ProductVariant.product_id == variant.product_id, ProductVariant.id != variant.id,
               ProductVariant.status.in_(["verified", "provisional"]))
    ).scalars().all()
    generic = [t for t in parse_query(variant.product.name).terms if len(t) >= 3]
    substitutes = []
    if generic:
        parsed = parse_query(" ".join(generic))
        parsed.size = None
        for v, _score in rank_variants(db, parsed, limit=12, min_score=0.6):
            if v.product_id != variant.product_id and v.product.category_slug == variant.product.category_slug:
                substitutes.append(v)
        substitutes = substitutes[:6]

    ids = [variant.id] + [v.id for v in sizes] + [v.id for v in substitutes]
    offers = _offers_for(db, ids, shop_map)
    main_offers = offers.get(variant.id, [])

    def alt(v: ProductVariant) -> dict:
        o = offers.get(v.id, [])
        best = next((x for x in o if x["reservable"] and x["price_paise"] is not None), None)
        return {"variant": variant_view(v), **_summary(o), "best_unit_price": best["unit_price"] if best else None}

    return {
        "variant": {**variant_view(variant), "size_value": variant.size_value, "size_unit": variant.size_unit,
                    "aliases": variant.aliases or [], "description": variant.product.description},
        "offers": main_offers,
        "summary": _summary(main_offers),
        "other_sizes": [alt(v) for v in sorted(sizes, key=lambda v: v.size_value or 0)],
        "substitutes": [alt(v) for v in substitutes],
        "radius_km": radius_m / 1000,
        "pickup_note": "Reserve, then pay and collect at the shop. Milega does not take payments.",
        "distance_note": "Distances are straight-line, not travel distance.",
    }


def available_nearby(db: Session, settings: Settings, *, lat: float, lng: float, radius_km: float = 3.0,
                     limit: int = 12) -> list[dict]:
    """Recently confirmed, in-stock products near the customer (one entry per product)."""
    found = nearby_shops(db, lat, lng, min(max(radius_km, 0.5), 10.0) * 1000,
                         statuses=customer_statuses(settings), include_demo=settings.show_demo_data, limit=80)
    shop_map = {s.id: (s, d) for s, d in found}
    if not shop_map:
        return []
    rows = db.execute(
        select(ShopListing, InventoryBalance)
        .options(joinedload(ShopListing.variant).joinedload(ProductVariant.product))
        .join(InventoryBalance, InventoryBalance.listing_id == ShopListing.id)
        .where(ShopListing.shop_id.in_(shop_map.keys()), ShopListing.is_active.is_(True),
               InventoryBalance.on_hand_milli > InventoryBalance.reserved_milli,
               InventoryBalance.stock_confirmed_at.is_not(None))
        .order_by(InventoryBalance.stock_confirmed_at.desc())
        .limit(600)
    ).all()
    by_variant: dict[int, dict] = {}
    for listing, balance in rows:
        shop, distance = shop_map[listing.shop_id]
        offer = offer_view(listing, balance, shop, distance)
        if offer["availability"]["status"] not in ("in_stock", "likely"):
            continue
        entry = by_variant.setdefault(listing.variant_id, {
            "variant": variant_view(listing.variant), "offers": 0, "min_price_paise": None,
            "nearest_m": None, "freshest": offer["availability"]["stock_confirmed_at"],
        })
        entry["offers"] += 1
        if offer["price_paise"] is not None and (entry["min_price_paise"] is None or offer["price_paise"] < entry["min_price_paise"]):
            entry["min_price_paise"] = offer["price_paise"]
        if offer["distance_m"] is not None and (entry["nearest_m"] is None or offer["distance_m"] < entry["nearest_m"]):
            entry["nearest_m"] = offer["distance_m"]
    items = list(by_variant.values())
    items.sort(key=lambda e: (-(e["offers"]), e["nearest_m"] or 1e12))
    return items[:limit]
