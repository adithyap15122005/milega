"""Internal administration API (role=admin only)."""

from __future__ import annotations

from datetime import timedelta
from typing import Literal

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session, joinedload, selectinload

from ..db import utcnow
from ..errors import bad_request, conflict, not_found
from ..models import (
    AuditLog,
    Category,
    InventoryBalance,
    Product,
    ProductVariant,
    ProductVerification,
    RecognitionJob,
    Reservation,
    Shop,
    ShopListing,
    User,
)
from ..security import client_ip, get_db, require_admin
from ..services.activity import audit, notify
from ..services.catalog import refresh_search_text, variant_view
from ..services.inventory import apply_change
from ..services.reservations import reservation_view
from ..services.units import SELL_UNITS, parse_size

router = APIRouter(prefix="/api/v1/admin", tags=["admin"])


class ReasonIn(BaseModel):
    reason: str | None = Field(default=None, max_length=255)


class VariantPatchIn(BaseModel):
    display_name: str | None = Field(default=None, min_length=2, max_length=200)
    brand: str | None = Field(default=None, max_length=80)
    product_name: str | None = Field(default=None, min_length=2, max_length=160)
    size_label: str | None = Field(default=None, max_length=40)
    category: str | None = Field(default=None, max_length=40)
    sell_unit: str | None = None
    aliases: list[str] | None = Field(default=None, max_length=30)


class MergeIn(BaseModel):
    source_id: int
    target_id: int


class ResolveIn(BaseModel):
    status: Literal["resolved", "rejected"]
    note: str | None = Field(default=None, max_length=255)


@router.get("/stats")
def stats(_admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    def grouped(model, column):
        return dict(db.execute(select(column, func.count()).select_from(model).group_by(column)).all())

    week = utcnow() - timedelta(days=7)
    return {
        "users": dict(db.execute(select(User.role, func.count()).group_by(User.role)).all()),
        "shops": grouped(Shop, Shop.status),
        "variants": grouped(ProductVariant, ProductVariant.status),
        "active_listings": db.scalar(select(func.count()).select_from(ShopListing).where(ShopListing.is_active.is_(True))),
        "reservations_7d": dict(db.execute(
            select(Reservation.status, func.count()).where(Reservation.created_at > week).group_by(Reservation.status)
        ).all()),
        "recognition_7d": dict(db.execute(
            select(RecognitionJob.status, func.count()).where(RecognitionJob.created_at > week)
            .group_by(RecognitionJob.status)
        ).all()),
        "open_verifications": db.scalar(
            select(func.count()).select_from(ProductVerification).where(ProductVerification.status == "open")
        ),
    }


@router.get("/shops")
def list_shops(status: str | None = None, q: str | None = Query(default=None, max_length=80),
               _admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    query = select(Shop).options(joinedload(Shop.owner))
    if status:
        query = query.where(Shop.status == status)
    if q:
        like = f"%{q.strip()}%"
        query = query.where(or_(Shop.name.ilike(like), Shop.locality.ilike(like), Shop.city.ilike(like)))
    shops = db.execute(query.order_by(Shop.id.desc()).limit(200)).scalars().all()
    counts = dict(db.execute(
        select(ShopListing.shop_id, func.count()).where(ShopListing.is_active.is_(True)).group_by(ShopListing.shop_id)
    ).all())
    return {"shops": [{
        "id": s.id, "name": s.name, "status": s.status, "locality": s.locality, "city": s.city,
        "pincode": s.pincode, "address_line": s.address_line, "lat": s.lat, "lng": s.lng,
        "location_source": s.location_source, "location_verified": s.location_verified,
        "categories": s.categories, "owner_phone": s.owner.phone, "owner_name": s.owner.name,
        "is_demo": s.is_demo, "listings": counts.get(s.id, 0), "created_at": s.created_at.isoformat(),
    } for s in shops]}


def _shop(db: Session, shop_id: int) -> Shop:
    shop = db.get(Shop, shop_id)
    if shop is None:
        raise not_found("Shop")
    return shop


@router.post("/shops/{shop_id}/verify")
def verify_shop(shop_id: int, request: Request, admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    shop = _shop(db, shop_id)
    shop.status = "active"
    shop.location_verified = True
    shop.verified_at = utcnow()
    notify(db, user_id=shop.owner_user_id, kind="shop_verified", title="Your shop is live",
           body="Customers nearby can now find your products.")
    audit(db, actor_id=admin.id, action="shop.verify", entity_type="shop", entity_id=shop.id, ip=client_ip(request))
    return {"ok": True, "status": shop.status}


@router.post("/shops/{shop_id}/suspend")
def suspend_shop(shop_id: int, body: ReasonIn, request: Request, admin: User = Depends(require_admin),
                 db: Session = Depends(get_db)):
    shop = _shop(db, shop_id)
    shop.status = "suspended"
    audit(db, actor_id=admin.id, action="shop.suspend", entity_type="shop", entity_id=shop.id,
          data={"reason": body.reason}, ip=client_ip(request))
    return {"ok": True, "status": shop.status}


@router.post("/shops/{shop_id}/reactivate")
def reactivate_shop(shop_id: int, request: Request, admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    shop = _shop(db, shop_id)
    shop.status = "active"
    audit(db, actor_id=admin.id, action="shop.reactivate", entity_type="shop", entity_id=shop.id, ip=client_ip(request))
    return {"ok": True, "status": shop.status}


# ------------------------------------------------------------------ catalogue


@router.get("/variants")
def list_variants(status: str | None = None, q: str | None = Query(default=None, max_length=80),
                  _admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    query = select(ProductVariant).options(joinedload(ProductVariant.product))
    if status:
        query = query.where(ProductVariant.status == status)
    if q:
        query = query.where(ProductVariant.search_text.contains(q.lower().strip()))
    variants = db.execute(query.order_by(ProductVariant.id.desc()).limit(300)).unique().scalars().all()
    usage = dict(db.execute(
        select(ShopListing.variant_id, func.count()).where(ShopListing.is_active.is_(True)).group_by(ShopListing.variant_id)
    ).all())
    return {"variants": [{**variant_view(v), "aliases": v.aliases, "shops": usage.get(v.id, 0),
                          "merged_into_id": v.merged_into_id} for v in variants]}


def _variant(db: Session, variant_id: int) -> ProductVariant:
    v = db.get(ProductVariant, variant_id)
    if v is None:
        raise not_found("Product")
    return v


@router.patch("/variants/{variant_id}")
def update_variant(variant_id: int, body: VariantPatchIn, admin: User = Depends(require_admin),
                   db: Session = Depends(get_db)):
    v = _variant(db, variant_id)
    product: Product = v.product
    before = variant_view(v)
    if body.brand is not None:
        product.brand = body.brand.strip() or None
    if body.product_name is not None:
        product.name = body.product_name.strip()
    if body.category is not None:
        if db.get(Category, body.category) is None:
            raise bad_request("invalid_category", "Unknown category")
        product.category_slug = body.category
    if body.size_label is not None:
        v.size_label = body.size_label.strip() or None
        v.size_value, v.size_unit = parse_size(v.size_label)
    if body.sell_unit is not None:
        if body.sell_unit not in SELL_UNITS:
            raise bad_request("invalid_unit", "Unknown unit")
        v.sell_unit = body.sell_unit
        v.is_loose = body.sell_unit in {"kg", "g", "l", "ml", "m"}
    if body.aliases is not None:
        v.aliases = [a.strip() for a in body.aliases if a.strip()][:30]
    if body.display_name is not None:
        v.display_name = body.display_name.strip()
    refresh_search_text(v)
    audit(db, actor_id=admin.id, action="variant.update", entity_type="variant", entity_id=v.id,
          data={"before": before, "after": variant_view(v)})
    return variant_view(v)


@router.post("/variants/{variant_id}/verify")
def verify_variant(variant_id: int, admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    v = _variant(db, variant_id)
    if v.status == "merged":
        raise conflict("merged", "This product was merged into another")
    v.status = "verified"
    for pv in db.execute(select(ProductVerification).where(
            ProductVerification.variant_id == v.id, ProductVerification.kind == "provisional_product",
            ProductVerification.status == "open")).scalars():
        pv.status, pv.resolved_by, pv.resolved_at = "resolved", admin.id, utcnow()
    audit(db, actor_id=admin.id, action="variant.verify", entity_type="variant", entity_id=v.id)
    return variant_view(v)


@router.post("/variants/merge")
def merge_variants(body: MergeIn, request: Request, admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    """Merge a duplicate (source) into the canonical variant (target), moving every shop's stock."""
    if body.source_id == body.target_id:
        raise bad_request("same_variant", "Choose two different products")
    source, target = _variant(db, body.source_id), _variant(db, body.target_id)
    if source.status == "merged" or target.status == "merged":
        raise conflict("merged", "One of these products is already merged")
    listings = db.execute(select(ShopListing).where(ShopListing.variant_id == source.id)).scalars().all()
    for listing in listings:
        bal = db.get(InventoryBalance, listing.id)
        if bal and bal.reserved_milli > 0:
            raise conflict("has_reservations",
                           f"Shop #{listing.shop_id} has open reservations for this product; try again later")
    moved = 0
    for listing in listings:
        bal = db.get(InventoryBalance, listing.id)
        existing = db.execute(select(ShopListing).where(
            ShopListing.shop_id == listing.shop_id, ShopListing.variant_id == target.id)).scalar_one_or_none()
        if existing is None:
            listing.variant_id = target.id
            bal.variant_id = target.id
        else:
            qty = bal.on_hand_milli
            if qty:
                apply_change(db, listing, event_type="merge", source="admin", delta_on_hand=-qty, actor_id=admin.id,
                             verification="admin", reference=("variant_merge", f"{source.id}->{target.id}"))
                apply_change(db, existing, event_type="merge", source="admin", delta_on_hand=qty, actor_id=admin.id,
                             verification="admin", reference=("variant_merge", f"{source.id}->{target.id}"))
            if existing.price_paise is None:
                existing.price_paise = listing.price_paise
            existing.is_active = existing.is_active or listing.is_active
            listing.is_active = False
        moved += 1
    source.status = "merged"
    source.merged_into_id = target.id
    target.aliases = list(dict.fromkeys([*(target.aliases or []), source.display_name, *(source.aliases or [])]))[:30]
    refresh_search_text(target)
    for pv in db.execute(select(ProductVerification).where(
            ProductVerification.variant_id == source.id, ProductVerification.status == "open")).scalars():
        pv.status, pv.resolved_by, pv.resolved_at = "resolved", admin.id, utcnow()
        pv.resolution_note = f"Merged into #{target.id}"
    audit(db, actor_id=admin.id, action="variant.merge", entity_type="variant", entity_id=source.id,
          data={"target_id": target.id, "listings_moved": moved}, ip=client_ip(request))
    return {"ok": True, "listings_moved": moved, "target": variant_view(target)}


@router.get("/verifications")
def verifications(status: str = "open", _admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    rows = db.execute(select(ProductVerification).where(ProductVerification.status == status)
                      .order_by(ProductVerification.id.desc()).limit(200)).scalars().all()
    variants = {v.id: v for v in db.execute(select(ProductVariant).options(joinedload(ProductVariant.product)).where(
        ProductVariant.id.in_({r.variant_id for r in rows if r.variant_id}))).unique().scalars()}
    shops = {s.id: s.name for s in db.execute(select(Shop).where(Shop.id.in_({r.shop_id for r in rows if r.shop_id}))).scalars()}
    return {"verifications": [{
        "id": r.id, "kind": r.kind, "status": r.status, "payload": r.payload, "created_at": r.created_at.isoformat(),
        "variant": variant_view(variants[r.variant_id]) if r.variant_id in variants else None,
        "shop_name": shops.get(r.shop_id),
    } for r in rows]}


@router.post("/verifications/{verification_id}/resolve")
def resolve_verification(verification_id: int, body: ResolveIn, admin: User = Depends(require_admin),
                         db: Session = Depends(get_db)):
    pv = db.get(ProductVerification, verification_id)
    if pv is None:
        raise not_found("Verification")
    pv.status, pv.resolved_by, pv.resolved_at, pv.resolution_note = body.status, admin.id, utcnow(), body.note
    audit(db, actor_id=admin.id, action=f"verification.{body.status}", entity_type="verification", entity_id=pv.id)
    return {"ok": True}


@router.get("/reservations")
def all_reservations(status: str | None = None, _admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    q = select(Reservation).options(selectinload(Reservation.items))
    if status:
        q = q.where(Reservation.status == status)
    rows = db.execute(q.order_by(Reservation.id.desc()).limit(200)).scalars().all()
    return {"reservations": [reservation_view(r, include_customer=True) for r in rows]}


@router.get("/audit")
def audit_log(limit: int = Query(default=100, le=500), _admin: User = Depends(require_admin),
              db: Session = Depends(get_db)):
    rows = db.execute(select(AuditLog).order_by(AuditLog.id.desc()).limit(limit)).scalars().all()
    return {"entries": [{"id": a.id, "actor_user_id": a.actor_user_id, "action": a.action,
                         "entity_type": a.entity_type, "entity_id": a.entity_id, "data": a.data,
                         "created_at": a.created_at.isoformat()} for a in rows]}
