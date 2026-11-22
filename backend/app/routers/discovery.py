"""Discovery API: localities, Bengaluru-wide shop discovery, product pages, saved shops, reports."""

from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, Field, model_validator
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..config import Settings
from ..db import release_sqlite_lock, utcnow
from ..errors import AppError, bad_request, conflict, not_found
from ..models import NotificationDevice, Report, SavedPlace, Shop, User
from ..ratelimit import rate_limit
from ..reference_data import CATEGORY_DISCOVERY
from ..security import ShopContext, client_ip, current_user, get_db, get_settings, optional_user, require_shop
from ..services.activity import audit
from ..services.discovery import (
    customer_statuses,
    discover,
    locality_view,
    merchant_views,
    nearest_locality,
    search_localities,
)
from ..services.geo import haversine_m, shops_in_bounds
from ..services.places import ATTRIBUTION, BASIC_FIELDS, DETAIL_FIELDS, LOCATION_FIELDS, PlacesClient, PlacesError
from ..services.products import available_nearby, product_detail
from ..services.search import suggest

router = APIRouter(prefix="/api/v1", tags=["discovery"])

Lang = Literal["en", "hi", "kn"]


def get_places(request: Request) -> PlacesClient | None:
    return getattr(request.app.state, "places", None)


def _lang(lang: str | None, user: User | None) -> str:
    return lang or (user.preferred_language if user else None) or "en"


def _places_error(exc: PlacesError) -> AppError:
    status = {"not_found": 404, "invalid": 400, "quota_exceeded": 503, "not_configured": 503}.get(exc.code, 502)
    return AppError(status, f"places_{exc.code}", {
        "not_configured": "Google Maps discovery is not set up on this server",
        "quota_exceeded": "Google Maps discovery is paused for today. Milega shops still work.",
        "denied": "Google Maps discovery is unavailable right now",
        "unavailable": "Google Maps could not be reached. Try again shortly.",
        "not_found": "That place could not be found",
        "invalid": "That place could not be found",
    }.get(exc.code, "Google Maps discovery is unavailable"))


# ------------------------------------------------------------------ localities


@router.get("/localities", dependencies=[rate_limit("localities", 120, 60)])
def localities(
    q: str = Query(default="", max_length=80),
    include_google: bool = True,
    session_token: str | None = Query(default=None, max_length=64),
    lat: float | None = Query(default=None, ge=-90, le=90),
    lng: float | None = Query(default=None, ge=-180, le=180),
    lang: Lang | None = None,
    user: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    places: PlacesClient | None = Depends(get_places),
):
    curated = [locality_view(loc) for loc, _ in search_localities(db, q, limit=40 if not q else 10)]
    google: list[dict] = []
    google_status = "skipped"
    if include_google and len(q.strip()) >= 3 and places is not None and settings.places_configured:
        try:
            release_sqlite_lock(db)
            google = places.autocomplete(q, session_token=session_token, lat=lat, lng=lng, language=_lang(lang, user))
            google_status = "ok"
        except PlacesError as exc:
            google_status = exc.code
    return {"localities": curated, "google_predictions": google, "google_status": google_status,
            "attribution": ATTRIBUTION if google else None}


@router.get("/localities/nearest")
def locality_nearest(lat: float = Query(ge=-90, le=90), lng: float = Query(ge=-180, le=180),
                     db: Session = Depends(get_db)):
    loc = nearest_locality(db, lat, lng)
    return {"locality": locality_view(loc) if loc else None}


@router.get("/places/resolve", dependencies=[rate_limit("places_resolve", 60, 60)])
def resolve_place(
    place_id: str = Query(max_length=255),
    session_token: str | None = Query(default=None, max_length=64),
    lang: Lang | None = None,
    user: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
    places: PlacesClient | None = Depends(get_places),
):
    """Coordinates for a chosen autocomplete suggestion (ends the autocomplete session)."""
    if places is None:
        raise _places_error(PlacesError("not_configured"))
    release_sqlite_lock(db)
    try:
        p = places.details(place_id, fields=LOCATION_FIELDS, language=_lang(lang, user), session_token=session_token)
    except PlacesError as exc:
        raise _places_error(exc) from exc
    return {"place_id": p["place_id"], "name": p["name"], "address": p["full_address"], "lat": p["lat"],
            "lng": p["lng"], "attribution": ATTRIBUTION}


# ------------------------------------------------------------------- discovery


@router.get("/discover", dependencies=[rate_limit("discover", 60, 60)])
def discover_shops(
    lat: float = Query(ge=-90, le=90),
    lng: float = Query(ge=-180, le=180),
    radius_km: float = Query(default=2.0, gt=0, le=15),
    category: str | None = Query(default=None, max_length=40),
    q: str | None = Query(default=None, max_length=120),
    include_external: bool = True,
    page_token: str | None = Query(default=None, max_length=1024),
    lang: Lang | None = None,
    user: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    places: PlacesClient | None = Depends(get_places),
):
    if category and category not in CATEGORY_DISCOVERY:
        raise bad_request("unknown_category", "Unknown category")
    return discover(db, settings, places, lat=lat, lng=lng, radius_km=radius_km, category=category, q=q,
                    include_external=include_external, page_token=page_token, language=_lang(lang, user))


@router.get("/discover/viewport", dependencies=[rate_limit("viewport", 120, 60)])
def discover_viewport(
    min_lat: float = Query(ge=-90, le=90), min_lng: float = Query(ge=-180, le=180),
    max_lat: float = Query(ge=-90, le=90), max_lng: float = Query(ge=-180, le=180),
    category: str | None = Query(default=None, max_length=40),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    """Milega merchants inside the visible map area (for clustering)."""
    if max_lat <= min_lat or max_lng <= min_lng or (max_lat - min_lat) > 1.0 or (max_lng - min_lng) > 1.0:
        raise bad_request("bad_viewport", "Zoom in to see shops in this area")
    shops = shops_in_bounds(db, min_lat, min_lng, max_lat, max_lng, statuses=customer_statuses(settings),
                            category=category, include_demo=settings.show_demo_data)
    return {"merchants": merchant_views(db, [(s, None) for s in shops])}


@router.get("/places/{place_id}", dependencies=[rate_limit("place_detail", 60, 60)])
def place_detail(
    place_id: str,
    fields: Literal["basic", "full"] = "full",
    lat: float | None = Query(default=None, ge=-90, le=90),
    lng: float | None = Query(default=None, ge=-180, le=180),
    lang: Lang | None = None,
    user: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
    places: PlacesClient | None = Depends(get_places),
):
    """A Google-listed business. If a Milega merchant has a verified claim on it, point there instead."""
    linked = db.execute(
        select(Shop).where(Shop.google_place_id == place_id, Shop.place_link_status == "verified",
                           Shop.status == "active")
    ).scalar_one_or_none()
    if places is None:
        if linked:
            return {"place": None, "milega_shop_id": linked.id, "attribution": None}
        raise _places_error(PlacesError("not_configured"))
    release_sqlite_lock(db)
    try:
        place = places.details(place_id, fields=BASIC_FIELDS if fields == "basic" else DETAIL_FIELDS,
                               language=_lang(lang, user))
    except PlacesError as exc:
        raise _places_error(exc) from exc
    if lat is not None and lng is not None and place.get("lat") is not None:
        place["distance_m"] = round(haversine_m(lat, lng, place["lat"], place["lng"]))
    return {"place": place, "milega_shop_id": linked.id if linked else None, "attribution": ATTRIBUTION}


# ---------------------------------------------------------------- product pages


@router.get("/products/nearby", tags=["search"])
def products_nearby(
    lat: float = Query(ge=-90, le=90), lng: float = Query(ge=-180, le=180),
    radius_km: float = Query(default=3.0, gt=0, le=10),
    limit: int = Query(default=12, ge=1, le=40),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    return {"items": available_nearby(db, settings, lat=lat, lng=lng, radius_km=radius_km, limit=limit)}


@router.get("/products/{variant_id}", tags=["search"])
def product_page(
    variant_id: int,
    lat: float | None = Query(default=None, ge=-90, le=90),
    lng: float | None = Query(default=None, ge=-180, le=180),
    radius_km: float | None = Query(default=None, gt=0, le=25),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    return product_detail(db, settings, variant_id, lat=lat, lng=lng, radius_km=radius_km)


@router.get("/search/suggest", tags=["search"], dependencies=[rate_limit("suggest", 240, 60)])
def search_suggest(
    q: str = Query(default="", max_length=80),
    lat: float | None = Query(default=None, ge=-90, le=90),
    lng: float | None = Query(default=None, ge=-180, le=180),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    return suggest(db, settings, q, lat=lat, lng=lng)


# ----------------------------------------------------------------------- saved


class SaveIn(BaseModel):
    shop_id: int | None = None
    place_id: str | None = Field(default=None, max_length=255)

    @model_validator(mode="after")
    def _one_target(self):
        if (self.shop_id is None) == (self.place_id is None):
            raise ValueError("Give either shop_id or place_id")
        return self


@router.get("/saved")
def saved(user: User = Depends(current_user), db: Session = Depends(get_db), settings: Settings = Depends(get_settings)):
    rows = db.execute(select(SavedPlace).where(SavedPlace.user_id == user.id).order_by(SavedPlace.created_at.desc())).scalars().all()
    shop_ids = [r.shop_id for r in rows if r.shop_id]
    shops = {s.id: s for s in db.execute(select(Shop).where(Shop.id.in_(shop_ids))).scalars()} if shop_ids else {}
    visible = [shops[i] for i in shop_ids if i in shops and shops[i].status in customer_statuses(settings)
               and (settings.show_demo_data or not shops[i].is_demo)]
    return {
        "shops": merchant_views(db, [(s, None) for s in visible]),
        # Only Google place IDs are stored; the app loads names live from /places/{id}.
        "places": [{"place_id": r.google_place_id, "saved_at": r.created_at.isoformat()} for r in rows if r.google_place_id],
    }


@router.post("/saved", status_code=201)
def save(body: SaveIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    if body.shop_id is not None and db.get(Shop, body.shop_id) is None:
        raise not_found("Shop")
    try:
        with db.begin_nested():
            db.add(SavedPlace(user_id=user.id, shop_id=body.shop_id, google_place_id=body.place_id))
    except IntegrityError:
        pass  # already saved: idempotent
    return {"saved": True}


@router.delete("/saved")
def unsave(shop_id: int | None = None, place_id: str | None = Query(default=None, max_length=255),
           user: User = Depends(current_user), db: Session = Depends(get_db)):
    if shop_id is None and place_id is None:
        raise bad_request("missing_target", "Give shop_id or place_id")
    q = delete(SavedPlace).where(SavedPlace.user_id == user.id)
    q = q.where(SavedPlace.shop_id == shop_id) if shop_id is not None else q.where(SavedPlace.google_place_id == place_id)
    db.execute(q)
    return {"saved": False}


# --------------------------------------------------------------------- reports


class ReportIn(BaseModel):
    target_type: Literal["shop", "place", "listing", "product"]
    target_id: str = Field(min_length=1, max_length=255)
    reason: Literal["wrong_location", "permanently_closed", "wrong_info", "wrong_price", "out_of_stock",
                    "fake_or_spam", "duplicate", "other"]
    note: str | None = Field(default=None, max_length=500)


@router.post("/reports", status_code=201, dependencies=[rate_limit("reports", 10, 3600)])
def report(body: ReportIn, request: Request, user: User = Depends(current_user), db: Session = Depends(get_db)):
    row = Report(reporter_user_id=user.id, target_type=body.target_type, target_id=body.target_id,
                 reason=body.reason, note=(body.note or "").strip() or None)
    db.add(row)
    db.flush()
    audit(db, actor_id=user.id, action="report.create", entity_type=body.target_type, entity_id=body.target_id,
          data={"reason": body.reason, "report_id": row.id}, ip=client_ip(request))
    return {"id": row.id, "status": row.status}


# ------------------------------------------------------- merchant claims a place


class ClaimIn(BaseModel):
    place_id: str = Field(min_length=3, max_length=255)


@router.post("/shops/mine/google-place")
def claim_place(body: ClaimIn, request: Request, ctx: ShopContext = Depends(require_shop),
                db: Session = Depends(get_db), places: PlacesClient | None = Depends(get_places)):
    """Link the merchant's Milega shop to its Google listing. An admin confirms the link."""
    shop = ctx.shop
    other = db.execute(select(Shop).where(Shop.google_place_id == body.place_id, Shop.id != shop.id)).scalar_one_or_none()
    if other is not None:
        raise conflict("place_already_claimed", "Another Milega shop has already claimed this listing")
    distance = None
    if places is not None:
        release_sqlite_lock(db)
        try:
            p = places.details(body.place_id, fields=LOCATION_FIELDS)
        except PlacesError as exc:
            raise _places_error(exc) from exc
        if p.get("lat") is not None:
            distance = haversine_m(shop.lat, shop.lng, p["lat"], p["lng"])
            if distance > 500:
                raise AppError(409, "place_too_far", "That Google listing is more than 500 m from your shop pin")
    shop.google_place_id = body.place_id
    shop.place_link_status = "pending"
    db.flush()
    audit(db, actor_id=ctx.user.id, action="shop.claim_place", entity_type="shop", entity_id=shop.id,
          data={"place_id": body.place_id, "distance_m": round(distance) if distance is not None else None},
          ip=client_ip(request))
    return {"google_place_id": shop.google_place_id, "place_link_status": shop.place_link_status}


@router.delete("/shops/mine/google-place")
def unclaim_place(ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    ctx.shop.google_place_id = None
    ctx.shop.place_link_status = "none"
    audit(db, actor_id=ctx.user.id, action="shop.unclaim_place", entity_type="shop", entity_id=ctx.shop.id)
    return {"google_place_id": None, "place_link_status": "none"}


# --------------------------------------------------------------- push devices


class DeviceIn(BaseModel):
    token: str = Field(min_length=20, max_length=512)
    platform: Literal["android", "ios", "web"] = "android"
    app_version: str | None = Field(default=None, max_length=32)


@router.post("/devices", tags=["notifications"], dependencies=[rate_limit("devices", 30, 3600)])
def register_device(body: DeviceIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    device = db.execute(select(NotificationDevice).where(NotificationDevice.token == body.token)).scalar_one_or_none()
    if device is None:
        device = NotificationDevice(user_id=user.id, token=body.token, platform=body.platform)
        db.add(device)
    device.user_id = user.id  # a reinstall or account switch moves the token
    device.app_version = body.app_version
    device.disabled_at = None
    device.failure_count = 0
    device.last_seen_at = utcnow()
    db.flush()
    return {"registered": True}


@router.delete("/devices/{token}", tags=["notifications"])
def unregister_device(token: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    db.execute(delete(NotificationDevice).where(NotificationDevice.token == token, NotificationDevice.user_id == user.id))
    return {"registered": False}


__all__ = ["router"]
