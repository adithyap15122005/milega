"""Bengaluru-wide shop discovery: localities, Milega merchants and Google-listed businesses.

Two kinds of results are always kept apart:

* **Milega merchants** (``provenance=milega``): shops that registered on Milega.
  Only these can have inventory and reservations.
* **Google-discovered businesses** (``provenance=google``): live results from Google
  Places. Shown for discovery and directions only — never with assumed stock,
  prices or reservations, and never stored except for their place ID.

Duplicates are removed only by a *source-aware* link: a merchant that claimed a
Google place (admin-verified ``google_place_id``) replaces that Google result.
Businesses are never merged because their names look alike.
"""

from __future__ import annotations

import logging
import re

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import Settings
from ..db import release_sqlite_lock
from ..models import InventoryBalance, Locality, Shop, ShopListing
from ..reference_data import (
    BENGALURU_BOUNDS,
    CATEGORY_DISCOVERY,
    GENERAL_RETAIL_TYPES,
    LOCALITIES,
)
from .geo import haversine_m, nearby_shops, shop_public_view
from .places import ATTRIBUTION, LIST_FIELDS, LOCATION_FIELDS, PlacesClient, PlacesError
from .text import build_search_text, normalize, token_match_score, tokenize

log = logging.getLogger("milega.discovery")


# ------------------------------------------------------------------ visibility


def customer_statuses(settings: Settings) -> tuple[str, ...]:
    return ("active", "pending") if settings.show_unverified_merchants else ("active",)


def in_bengaluru(lat: float, lng: float) -> bool:
    (lo_lat, lo_lng), (hi_lat, hi_lng) = BENGALURU_BOUNDS["low"], BENGALURU_BOUNDS["high"]
    return lo_lat <= lat <= hi_lat and lo_lng <= lng <= hi_lng


# ------------------------------------------------------------------ localities


def seed_localities(db: Session) -> int:
    existing = {loc.slug: loc for loc in db.execute(select(Locality)).scalars()}
    created = 0
    for slug, name, kn, lat, lng, radius, aliases in LOCALITIES:
        text = build_search_text(name, kn, aliases=aliases)
        loc = existing.get(slug)
        if loc is None:
            db.add(Locality(slug=slug, name=name, name_kn=kn, lat=lat, lng=lng, radius_m=radius,
                            aliases=aliases, search_text=text, source="curated"))
            created += 1
        elif loc.source == "curated":
            loc.name, loc.name_kn, loc.lat, loc.lng = name, kn, lat, lng
            loc.radius_m, loc.aliases, loc.search_text = radius, aliases, text
    db.flush()
    return created


def locality_view(loc: Locality) -> dict:
    return {"slug": loc.slug, "name": loc.name, "name_kn": loc.name_kn, "city": loc.city,
            "lat": loc.lat, "lng": loc.lng, "radius_m": loc.radius_m, "source": "milega"}


def search_localities(db: Session, q: str, limit: int = 12) -> list[tuple[Locality, float]]:
    rows = db.execute(select(Locality).where(Locality.is_active.is_(True))).scalars().all()
    terms = tokenize(q)
    if not terms:
        return [(loc, 1.0) for loc in sorted(rows, key=lambda r: r.name)][:limit]
    qnorm = normalize(q)
    scored = []
    for loc in rows:
        tokens = set(loc.search_text.split())
        score = sum(token_match_score(t, tokens) for t in terms) / len(terms)
        if normalize(loc.name).startswith(qnorm) or normalize(loc.name_kn or "").startswith(qnorm):
            score = max(score, 0.97)  # the area's own name beats an alias ("jaya" -> Jayanagar, not JP Nagar)
        elif any(normalize(a).startswith(qnorm) for a in loc.aliases or []):
            score = max(score, 0.95)
        if score >= 0.6:
            scored.append((loc, round(score, 3)))
    scored.sort(key=lambda x: (-x[1], x[0].name))
    return scored[:limit]


def nearest_locality(db: Session, lat: float, lng: float) -> Locality | None:
    best, best_d = None, None
    for loc in db.execute(select(Locality).where(Locality.is_active.is_(True))).scalars():
        d = haversine_m(lat, lng, loc.lat, loc.lng)
        if best_d is None or d < best_d:
            best, best_d = loc, d
    return best if best_d is not None and best_d <= 6000 else None


_LEADING = r"(?:(?:near|in|at|around|from|by|close to|nearby)\s+)?"
_NEAR_RE = re.compile(r"(?:^|\s)(?:near|close to|around|opposite|opp)\s+(.+)$")
_NOT_LANDMARKS = {"me", "here", "my place", "my home", "home", "by", "me now", "my location"}


def extract_location(db: Session, raw: str) -> tuple[Locality | None, str | None, str]:
    """Find a Bengaluru locality (or a landmark after "near") inside a search query.

    Returns (locality, landmark_phrase, remaining_text). The remaining text has the
    place words removed so product matching isn't confused by them.
    """
    # Sentence punctuation would block whole-word matching ("…in Jayanagar?"). Keep
    # "." and "-" because pack sizes ("1.5 kg") and names ("Parle-G") use them.
    text = f" {' '.join(re.sub(r'[?!,;:()]+', ' ', normalize(raw)).split())} "
    phrases: list[tuple[str, Locality]] = []
    for loc in db.execute(select(Locality).where(Locality.is_active.is_(True))).scalars():
        for phrase in {loc.name, loc.name_kn or "", *(loc.aliases or [])}:
            p = normalize(phrase)
            if len(p) >= 3:
                phrases.append((p, loc))
    phrases.sort(key=lambda x: -len(x[0]))
    for phrase, loc in phrases:
        m = re.search(rf"(?<!\S){_LEADING}{re.escape(phrase)}(?!\S)", text)
        if m:
            remaining = (text[: m.start()] + " " + text[m.end():]).strip()
            return loc, None, " ".join(remaining.split())
    m = _NEAR_RE.search(text.strip())
    if m:
        phrase = m.group(1).strip()
        if phrase and phrase not in _NOT_LANDMARKS and len(phrase) >= 4:
            remaining = text.strip()[: m.start()].strip()
            return None, phrase, remaining
    return None, None, " ".join(text.split())


def resolve_landmark(places: PlacesClient | None, phrase: str, *, language: str = "en") -> dict | None:
    """Geocode a landmark ("RV College of Engineering") inside Bengaluru via Places Text Search."""
    if places is None:
        return None
    try:
        found = places.search_text(f"{phrase}, Bengaluru", max_results=1, restrict_to_city=True,
                                   language=language, fields=",".join(f"places.{f}" for f in LOCATION_FIELDS.split(",")))
    except PlacesError as exc:
        log.info("landmark lookup unavailable: %s", exc.code)
        return None
    place = (found.get("places") or [None])[0]
    if not place or place.get("lat") is None:
        return None
    return {"name": place["name"], "lat": place["lat"], "lng": place["lng"], "place_id": place["place_id"],
            "source": "google"}


# ------------------------------------------------------------------- merchants


def merchant_views(db: Session, found: list[tuple[Shop, float | None]]) -> list[dict]:
    ids = [s.id for s, _ in found]
    counts = dict(db.execute(
        select(ShopListing.shop_id, func.count())
        .join(InventoryBalance, InventoryBalance.listing_id == ShopListing.id)
        .where(ShopListing.shop_id.in_(ids), ShopListing.is_active.is_(True),
               InventoryBalance.on_hand_milli > InventoryBalance.reserved_milli)
        .group_by(ShopListing.shop_id)
    ).all()) if ids else {}
    last_update = dict(db.execute(
        select(InventoryBalance.shop_id, func.max(InventoryBalance.stock_confirmed_at))
        .where(InventoryBalance.shop_id.in_(ids)).group_by(InventoryBalance.shop_id)
    ).all()) if ids else {}
    out = []
    for shop, distance in found:
        view = shop_public_view(shop, distance)
        updated = last_update.get(shop.id)
        view["products_in_stock"] = counts.get(shop.id, 0)
        view["inventory_updated_at"] = updated.isoformat() if updated else None
        out.append(view)
    return out


# ------------------------------------------------------------------- discovery


def discover(
    db: Session,
    settings: Settings,
    places: PlacesClient | None,
    *,
    lat: float,
    lng: float,
    radius_km: float = 2.0,
    category: str | None = None,
    q: str | None = None,
    include_external: bool = True,
    page_token: str | None = None,
    language: str = "en",
) -> dict:
    radius_m = min(max(radius_km, 0.3), 15.0) * 1000
    found = nearby_shops(db, lat, lng, radius_m, statuses=customer_statuses(settings), category=category,
                         include_demo=settings.show_demo_data, limit=200)
    if q:
        terms = tokenize(q)
        found = [(s, d) for s, d in found
                 if terms and sum(token_match_score(t, set(tokenize(s.name))) for t in terms) / len(terms) >= 0.8]
    merchants = merchant_views(db, found[:60]) if not page_token else []

    external: list[dict] = []
    status = "disabled"
    next_token = None
    if include_external:
        if places is None or not settings.places_configured:
            status = "not_configured"
        elif not in_bengaluru(lat, lng):
            status = "outside_service_area"
        else:
            try:
                release_sqlite_lock(db)
                result = _query_google(places, lat=lat, lng=lng, radius_m=radius_m, category=category, q=q,
                                       page_token=page_token, language=language)
                status = "ok"
                next_token = result.get("next_page_token")
                linked = set(db.execute(
                    select(Shop.google_place_id).where(Shop.google_place_id.is_not(None),
                                                       Shop.place_link_status == "verified")
                ).scalars())
                for p in result["places"]:
                    if not p.get("place_id") or p["place_id"] in linked:
                        continue  # the merchant's own Milega profile is shown instead
                    if p.get("business_status") == "CLOSED_PERMANENTLY" or p.get("lat") is None:
                        continue
                    p["distance_m"] = round(haversine_m(lat, lng, p["lat"], p["lng"]))
                    external.append(p)
                external.sort(key=lambda p: p["distance_m"])
            except PlacesError as exc:
                status = exc.code
    return {
        "center": {"lat": lat, "lng": lng},
        "radius_km": radius_m / 1000,
        "category": category,
        "merchants": merchants,
        "external": external,
        "external_status": status,
        "next_page_token": next_token,
        "attribution": ATTRIBUTION if external else None,
        "distance_note": "Distances are straight-line, not travel distance.",
    }


def _query_google(places: PlacesClient, *, lat: float, lng: float, radius_m: float, category: str | None,
                  q: str | None, page_token: str | None, language: str) -> dict:
    if q or page_token:
        text = q or ""
        if category and not q:
            text = CATEGORY_DISCOVERY.get(category, ([], "shop"))[1]
        return places.search_text(f"{text} in Bengaluru" if "bengaluru" not in text.lower() else text,
                                  lat=lat, lng=lng, radius_m=radius_m, page_token=page_token, language=language)
    if category:
        types, text_query = CATEGORY_DISCOVERY.get(category, ([], None))
        if types:
            return places.search_nearby(lat, lng, radius_m, types=types, language=language)
        return places.search_text(f"{text_query} in Bengaluru", lat=lat, lng=lng, radius_m=radius_m,
                                  language=language)
    return places.search_nearby(lat, lng, radius_m, types=GENERAL_RETAIL_TYPES, language=language)


__all__ = [
    "LIST_FIELDS", "customer_statuses", "discover", "extract_location", "locality_view", "merchant_views",
    "nearest_locality", "resolve_landmark", "search_localities", "seed_localities",
]
