from __future__ import annotations

import re
from typing import Literal

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import Settings
from ..db import utcnow
from ..errors import AppError, conflict, not_found
from ..models import Category, InventoryBalance, Shop, ShopHours, ShopListing, User
from ..security import ShopContext, client_ip, current_user, get_db, get_settings, optional_user, require_shop
from ..services.activity import audit
from ..services.catalog import parse_query, rank_variants
from ..services.geo import nearby_shops, shop_public_view
from ..services.inventory import freshness, listing_view
from ..services.search import distance_from

router = APIRouter(prefix="/api/v1", tags=["shops"])

_HHMM = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


class HoursIn(BaseModel):
    weekday: int = Field(ge=0, le=6)
    opens: str = "09:00"
    closes: str = "21:00"
    is_closed: bool = False

    @field_validator("opens", "closes")
    @classmethod
    def _time(cls, v: str) -> str:
        if not _HHMM.match(v):
            raise ValueError("Use HH:MM (24 hour)")
        return v


class ShopIn(BaseModel):
    name: str = Field(min_length=2, max_length=120)
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    address_line: str = Field(default="", max_length=255)
    locality: str = Field(default="", max_length=120)
    city: str = Field(default="", max_length=80)
    pincode: str = Field(default="", max_length=10)
    categories: list[str] = Field(default_factory=list, max_length=12)
    preferred_language: Literal["en", "hi", "kn"] = "en"
    hours: list[HoursIn] | None = None
    location_source: Literal["gps", "manual"] = "gps"
    description: str | None = Field(default=None, max_length=500)
    show_phone: bool = False

    @field_validator("pincode")
    @classmethod
    def _pin(cls, v: str) -> str:
        v = v.strip()
        if v and not re.fullmatch(r"[1-9]\d{5}", v):
            raise ValueError("Pincode must be 6 digits")
        return v


class ShopPatchIn(BaseModel):
    name: str | None = Field(default=None, min_length=2, max_length=120)
    lat: float | None = Field(default=None, ge=-90, le=90)
    lng: float | None = Field(default=None, ge=-180, le=180)
    address_line: str | None = Field(default=None, max_length=255)
    locality: str | None = Field(default=None, max_length=120)
    city: str | None = Field(default=None, max_length=80)
    pincode: str | None = Field(default=None, max_length=10)
    categories: list[str] | None = Field(default=None, max_length=12)
    preferred_language: Literal["en", "hi", "kn"] | None = None
    hours: list[HoursIn] | None = None
    description: str | None = Field(default=None, max_length=500)
    show_phone: bool | None = None


def owner_view(shop: Shop) -> dict:
    view = shop_public_view(shop)
    view.update({
        "status": shop.status,
        "location_verified": shop.location_verified,
        "location_source": shop.location_source,
        "preferred_language": shop.preferred_language,
        "show_phone": shop.show_phone,
        "visible_to_customers": shop.status == "active",
    })
    return view


def _valid_categories(db: Session, slugs: list[str]) -> list[str]:
    known = set(db.execute(select(Category.slug)).scalars())
    return [s for s in dict.fromkeys(slugs) if s in known]


def _set_hours(shop: Shop, hours: list[HoursIn] | None) -> None:
    given = {h.weekday: h for h in hours or []}
    shop.hours.clear()
    for day in range(7):
        h = given.get(day)
        shop.hours.append(ShopHours(
            weekday=day, opens=h.opens if h else "09:00", closes=h.closes if h else "21:00",
            is_closed=h.is_closed if h else False,
        ))


@router.post("/shops", status_code=201)
def register_shop(
    body: ShopIn,
    request: Request,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    if db.execute(select(Shop).where(Shop.owner_user_id == user.id)).scalar_one_or_none():
        raise conflict("shop_exists", "You have already registered a shop")
    if user.role == "admin":
        raise AppError(400, "admin_cannot_own_shop", "Admin accounts cannot register shops")
    shop = Shop(
        owner_user_id=user.id, name=body.name.strip(), lat=body.lat, lng=body.lng,
        address_line=body.address_line.strip(), locality=body.locality.strip(), city=body.city.strip(),
        pincode=body.pincode, categories=_valid_categories(db, body.categories),
        preferred_language=body.preferred_language, location_source=body.location_source,
        description=body.description, show_phone=body.show_phone,
        status="active" if settings.auto_approve_shops else "pending",
        location_verified=settings.auto_approve_shops,
        verified_at=utcnow() if settings.auto_approve_shops else None,
    )
    db.add(shop)
    _set_hours(shop, body.hours)
    user.role = "shopkeeper"
    user.preferred_language = body.preferred_language
    db.flush()
    audit(db, actor_id=user.id, action="shop.register", entity_type="shop", entity_id=shop.id, ip=client_ip(request))
    return owner_view(shop)


@router.get("/shops/mine")
def my_shop(ctx: ShopContext = Depends(require_shop)):
    return owner_view(ctx.shop)


@router.patch("/shops/mine")
def update_my_shop(body: ShopPatchIn, ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    shop = ctx.shop
    data = body.model_dump(exclude_unset=True)
    if "lat" in data or "lng" in data:
        if (data.get("lat") or shop.lat) != shop.lat or (data.get("lng") or shop.lng) != shop.lng:
            shop.location_verified = False  # moved pins need re-verification
    for field in ("name", "lat", "lng", "address_line", "locality", "city", "pincode",
                  "preferred_language", "description", "show_phone"):
        if field in data and data[field] is not None:
            setattr(shop, field, data[field].strip() if isinstance(data[field], str) else data[field])
    if body.categories is not None:
        shop.categories = _valid_categories(db, body.categories)
    if body.hours is not None:
        _set_hours(shop, body.hours)
    db.flush()
    audit(db, actor_id=ctx.user.id, action="shop.update", entity_type="shop", entity_id=shop.id,
          data={"fields": sorted(data)})
    return owner_view(shop)


@router.get("/shops/nearby")
def shops_nearby(
    lat: float = Query(ge=-90, le=90),
    lng: float = Query(ge=-180, le=180),
    radius_km: float = Query(default=3.0, gt=0, le=25),
    category: str | None = None,
    db: Session = Depends(get_db),
):
    found = nearby_shops(db, lat, lng, radius_km * 1000, category=category)[:60]
    counts = dict(db.execute(
        select(ShopListing.shop_id, func.count())
        .join(InventoryBalance, InventoryBalance.listing_id == ShopListing.id)
        .where(ShopListing.shop_id.in_([s.id for s, _ in found]), ShopListing.is_active.is_(True),
               InventoryBalance.on_hand_milli > InventoryBalance.reserved_milli)
        .group_by(ShopListing.shop_id)
    ).all()) if found else {}
    return {"shops": [{**shop_public_view(s, d), "products_in_stock": counts.get(s.id, 0)} for s, d in found]}


def _visible_shop(db: Session, shop_id: int, user: User | None) -> Shop:
    shop = db.get(Shop, shop_id)
    if shop is None:
        raise not_found("Shop")
    if shop.status != "active" and not (user and (user.role == "admin" or shop.owner_user_id == user.id)):
        raise not_found("Shop")
    return shop


@router.get("/shops/{shop_id}")
def shop_detail(
    shop_id: int,
    lat: float | None = None,
    lng: float | None = None,
    user: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    shop = _visible_shop(db, shop_id, user)
    return shop_public_view(shop, distance_from(lat, lng, shop))


@router.get("/shops/{shop_id}/products")
def shop_products(
    shop_id: int,
    q: str | None = Query(default=None, max_length=100),
    user: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    shop = _visible_shop(db, shop_id, user)
    rows = db.execute(
        select(ShopListing, InventoryBalance)
        .join(InventoryBalance, InventoryBalance.listing_id == ShopListing.id)
        .where(ShopListing.shop_id == shop.id, ShopListing.is_active.is_(True))
    ).all()
    if q:
        parsed = parse_query(q)
        allowed = {v.id for v, _ in rank_variants(db, parsed, limit=200, min_score=0.5,
                                                    variant_ids={l.variant_id for l, _ in rows})}
        rows = [(l, b) for l, b in rows if l.variant_id in allowed]
    items = []
    for listing, balance in rows:
        view = listing_view(listing, balance)
        items.append({
            "listing_id": listing.id, "variant_id": listing.variant_id, "name": view["name"],
            "brand": view["brand"], "category": view["category"], "size_label": view["size_label"],
            "price_paise": listing.price_paise, "unit_label": view["unit_label"], "sell_unit": view["sell_unit"],
            "availability": freshness(balance), "image_key": view["image_key"],
        })
    items.sort(key=lambda i: (i["availability"]["status"] == "out_of_stock", i["category"], i["name"]))
    return {"shop_id": shop.id, "products": items}
