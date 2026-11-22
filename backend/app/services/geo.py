"""Geospatial helpers.

Nearby search uses an indexed bounding-box query on (lat, lng) followed by an
exact great-circle (haversine) filter. This works identically on SQLite and
PostgreSQL. At city scale the same interface can be backed by PostGIS
``ST_DWithin`` on a geography column (see docs/ARCHITECTURE.md).
"""

from __future__ import annotations

import math
from datetime import datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Shop

EARTH_RADIUS_M = 6_371_000
IST = timezone(timedelta(hours=5, minutes=30))  # India has no DST


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def bounding_box(lat: float, lng: float, radius_m: float) -> tuple[float, float, float, float]:
    dlat = radius_m / 111_320
    dlng = radius_m / (111_320 * max(math.cos(math.radians(lat)), 0.01))
    return lat - dlat, lat + dlat, lng - dlng, lng + dlng


def nearby_shops(
    db: Session, lat: float, lng: float, radius_m: float, *, statuses=("active",), category: str | None = None
) -> list[tuple[Shop, float]]:
    min_lat, max_lat, min_lng, max_lng = bounding_box(lat, lng, radius_m)
    rows = db.execute(
        select(Shop).where(
            Shop.status.in_(statuses),
            Shop.lat.between(min_lat, max_lat),
            Shop.lng.between(min_lng, max_lng),
        )
    ).scalars().all()
    result = []
    for shop in rows:
        if category and category not in (shop.categories or []):
            continue
        d = haversine_m(lat, lng, shop.lat, shop.lng)
        if d <= radius_m:
            result.append((shop, d))
    result.sort(key=lambda x: x[1])
    return result


def open_now(shop: Shop, now: datetime | None = None) -> bool | None:
    """True/False when hours are known, None when the shop hasn't set hours."""
    if not shop.hours:
        return None
    local = (now or datetime.now(timezone.utc)).astimezone(IST)
    today = next((h for h in shop.hours if h.weekday == local.weekday()), None)
    if today is None or today.is_closed:
        return False
    hhmm = local.strftime("%H:%M")
    if today.opens <= today.closes:
        return today.opens <= hhmm < today.closes
    return hhmm >= today.opens or hhmm < today.closes  # overnight hours


def shop_public_view(shop: Shop, distance_m: float | None = None) -> dict:
    return {
        "id": shop.id,
        "name": shop.name,
        "description": shop.description,
        "address_line": shop.address_line,
        "locality": shop.locality,
        "city": shop.city,
        "pincode": shop.pincode,
        "lat": shop.lat,
        "lng": shop.lng,
        "categories": shop.categories or [],
        "verified": shop.status == "active" and shop.location_verified,
        "is_demo": shop.is_demo,
        "open_now": open_now(shop),
        "hours": [
            {"weekday": h.weekday, "opens": h.opens, "closes": h.closes, "is_closed": h.is_closed}
            for h in shop.hours
        ],
        "phone": shop.owner.phone if shop.show_phone else None,
        "distance_m": round(distance_m) if distance_m is not None else None,
    }
