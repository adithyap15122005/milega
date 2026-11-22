from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Literal

from fastapi import APIRouter, Depends, Header, Query
from pydantic import BaseModel, Field
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..db import utcnow
from ..errors import AppError, bad_request, conflict, not_found
from ..models import (
    Category,
    InventoryBalance,
    InventoryEvent,
    ProductVariant,
    ProductVerification,
    Reservation,
    ShopListing,
)
from ..security import ShopContext, current_user, get_db, require_shop
from ..services.activity import audit
from ..services.catalog import create_variant, match_variants, variant_view
from ..services.inventory import apply_change, create_listing, event_view, listing_view, set_on_hand
from ..services.recognition.pipeline import NewProductIn
from ..services.units import from_milli, to_milli

router = APIRouter(prefix="/api/v1", tags=["inventory"])


class AddListingIn(BaseModel):
    variant_id: int | None = None
    new_product: NewProductIn | None = None
    quantity: float = Field(default=0, ge=0)
    price_paise: int | None = Field(default=None, ge=0, le=10_000_000)
    custom_name: str | None = Field(default=None, max_length=200)


class ListingPatchIn(BaseModel):
    price_paise: int | None = Field(default=None, ge=0, le=10_000_000)
    clear_price: bool = False
    custom_name: str | None = Field(default=None, max_length=200)
    low_stock_threshold: float | None = Field(default=None, ge=0, le=100_000)


class AdjustIn(BaseModel):
    mode: Literal["add", "sell", "set", "out_of_stock"]
    quantity: float = Field(default=0, ge=0)
    source: Literal["manual", "voice"] = "manual"
    note: str | None = Field(default=None, max_length=200)
    # Set by the app's offline outbox: when the shopkeeper actually made the change.
    client_recorded_at: datetime | None = None


def _my_listing(db: Session, ctx: ShopContext, listing_id: int) -> ShopListing:
    listing = db.get(ShopListing, listing_id)
    # Shop-level isolation: a listing of another shop is indistinguishable from a missing one.
    if listing is None or listing.shop_id != ctx.shop.id:
        raise not_found("Product")
    return listing


def _view(db: Session, listing: ShopListing) -> dict:
    balance = db.execute(
        select(InventoryBalance).where(InventoryBalance.listing_id == listing.id)
        .execution_options(populate_existing=True)
    ).scalar_one()
    return listing_view(listing, balance)


# ----------------------------------------------------------------- catalogue


@router.get("/catalog/categories", tags=["catalog"])
def categories(db: Session = Depends(get_db)):
    rows = db.execute(select(Category).order_by(Category.sort_order)).scalars()
    return {"categories": [
        {"slug": c.slug, "name_en": c.name_en, "name_hi": c.name_hi, "name_kn": c.name_kn, "icon": c.icon}
        for c in rows
    ]}


@router.get("/catalog/search", tags=["catalog"])
def catalog_search(
    q: str = Query(min_length=1, max_length=100),
    limit: int = Query(default=15, le=40),
    _user=Depends(current_user),
    db: Session = Depends(get_db),
):
    return {"variants": [{**variant_view(v), "score": s} for v, s in match_variants(db, q, limit=limit, min_score=0.4)]}


@router.post("/catalog/variants", tags=["catalog"], status_code=201)
def create_catalog_variant(body: NewProductIn, ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    variant = create_variant(db, name=body.name, brand=body.brand, category=body.category,
                             size_label=body.size_label, sell_unit=body.sell_unit,
                             created_by=ctx.user.id, shop_id=ctx.shop.id)
    return variant_view(variant)


# ----------------------------------------------------------------- inventory


@router.get("/inventory")
def list_inventory(
    q: str | None = Query(default=None, max_length=100),
    filter: Literal["all", "low", "out", "in_stock"] = "all",
    ctx: ShopContext = Depends(require_shop),
    db: Session = Depends(get_db),
):
    rows = db.execute(
        select(ShopListing, InventoryBalance)
        .join(InventoryBalance, InventoryBalance.listing_id == ShopListing.id)
        .where(ShopListing.shop_id == ctx.shop.id, ShopListing.is_active.is_(True))
    ).all()
    if q:
        ranked = match_variants(db, q, limit=200, min_score=0.45, variant_ids={l.variant_id for l, _ in rows})
        order = {v.id: i for i, (v, _) in enumerate(ranked)}
        needle = q.lower().strip()
        rows = [(l, b) for l, b in rows
                if l.variant_id in order or (l.custom_name and needle in l.custom_name.lower())]
        rows.sort(key=lambda r: order.get(r[0].variant_id, 999))
    items = [listing_view(l, b) for l, b in rows]
    if filter == "low":
        items = [i for i in items if i["low_stock"]]
    elif filter == "out":
        items = [i for i in items if i["out_of_stock"]]
    elif filter == "in_stock":
        items = [i for i in items if not i["out_of_stock"]]
    if not q:
        items.sort(key=lambda i: (not i["overcommitted"], not i["out_of_stock"], not i["low_stock"], i["name"].lower()))
    return {"items": items, "count": len(items)}


@router.get("/inventory/summary")
def inventory_summary(ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    rows = db.execute(
        select(ShopListing, InventoryBalance)
        .join(InventoryBalance, InventoryBalance.listing_id == ShopListing.id)
        .where(ShopListing.shop_id == ctx.shop.id, ShopListing.is_active.is_(True))
    ).all()
    views = [listing_view(l, b) for l, b in rows]
    low = [v for v in views if v["low_stock"] or v["out_of_stock"] or v["overcommitted"]]
    low.sort(key=lambda v: (not v["overcommitted"], not v["out_of_stock"], v["on_hand"]))
    pending = db.scalar(select(func.count()).select_from(Reservation).where(
        Reservation.shop_id == ctx.shop.id, Reservation.status == "pending"))
    confirmed = db.scalar(select(func.count()).select_from(Reservation).where(
        Reservation.shop_id == ctx.shop.id, Reservation.status == "confirmed"))
    names = {l.id: (l.custom_name or l.variant.display_name, l.variant.sell_unit) for l, _ in rows}
    events = db.execute(
        select(InventoryEvent).where(InventoryEvent.shop_id == ctx.shop.id)
        .order_by(InventoryEvent.id.desc()).limit(8)
    ).scalars().all()
    recent = []
    for e in events:
        name, unit = names.get(e.listing_id, ("Removed product", "pc"))
        recent.append({**event_view(e, unit), "name": name})
    return {
        "shop": {"id": ctx.shop.id, "name": ctx.shop.name, "status": ctx.shop.status},
        "total_products": len(views),
        "out_of_stock": sum(1 for v in views if v["out_of_stock"]),
        "low_stock": sum(1 for v in views if v["low_stock"]),
        "attention": low[:6],
        "reservations": {"pending": pending, "confirmed": confirmed},
        "recent_activity": recent,
    }


@router.post("/inventory/listings", status_code=201)
def add_listing(body: AddListingIn, ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    if body.variant_id is not None:
        variant = db.get(ProductVariant, body.variant_id)
        if variant is None or variant.status == "merged":
            raise bad_request("invalid_variant", "Choose a product from the list")
    elif body.new_product is not None:
        np = body.new_product
        variant = create_variant(db, name=np.name, brand=np.brand, category=np.category, size_label=np.size_label,
                                 sell_unit=np.sell_unit, created_by=ctx.user.id, shop_id=ctx.shop.id)
    else:
        raise bad_request("product_required", "Choose or name a product")
    qty_milli = to_milli(body.quantity, variant.sell_unit)
    listing, created = create_listing(
        db, shop_id=ctx.shop.id, variant=variant, price_paise=body.price_paise, initial_quantity_milli=qty_milli,
        actor_id=ctx.user.id, source="manual", custom_name=body.custom_name,
    )
    db.flush()
    return {**_view(db, listing), "created": created}


@router.get("/inventory/listings/{listing_id}")
def get_listing(listing_id: int, ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    return _view(db, _my_listing(db, ctx, listing_id))


@router.patch("/inventory/listings/{listing_id}")
def update_listing(listing_id: int, body: ListingPatchIn, ctx: ShopContext = Depends(require_shop),
                   db: Session = Depends(get_db)):
    listing = _my_listing(db, ctx, listing_id)
    changes = {}
    if body.clear_price:
        listing.price_paise = None
        changes["price_paise"] = None
    elif body.price_paise is not None and body.price_paise != listing.price_paise:
        changes["price_paise"] = [listing.price_paise, body.price_paise]
        listing.price_paise = body.price_paise
    if body.custom_name is not None:
        new_name = body.custom_name.strip() or None
        if new_name != listing.custom_name:
            changes["custom_name"] = [listing.custom_name, new_name]
            listing.custom_name = new_name
            if new_name:
                # Let the catalogue team see shopkeepers' corrections.
                db.add(ProductVerification(
                    kind="name_correction", variant_id=listing.variant_id, shop_id=ctx.shop.id,
                    submitted_by=ctx.user.id,
                    payload={"catalog_name": listing.variant.display_name, "suggested_name": new_name},
                ))
    if body.low_stock_threshold is not None:
        listing.low_stock_threshold_milli = to_milli(body.low_stock_threshold, "kg")
    if changes:
        audit(db, actor_id=ctx.user.id, action="listing.update", entity_type="listing", entity_id=listing.id,
              data=changes)
    db.flush()
    return _view(db, listing)


@router.delete("/inventory/listings/{listing_id}")
def remove_listing(listing_id: int, ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    listing = _my_listing(db, ctx, listing_id)
    balance = db.get(InventoryBalance, listing.id)
    if balance and balance.reserved_milli > 0:
        raise conflict("has_reservations",
                       "Customers have reserved this product. Finish or reject those reservations first.",
                       reserved=from_milli(balance.reserved_milli))
    listing.is_active = False
    audit(db, actor_id=ctx.user.id, action="listing.remove", entity_type="listing", entity_id=listing.id)
    return {"ok": True}


@router.post("/inventory/listings/{listing_id}/adjust")
def adjust_stock(
    listing_id: int,
    body: AdjustIn,
    idempotency_key: str | None = Header(default=None, max_length=80),
    ctx: ShopContext = Depends(require_shop),
    db: Session = Depends(get_db),
):
    listing = _my_listing(db, ctx, listing_id)
    if not listing.is_active:
        raise AppError(409, "listing_removed", "This product was removed from your shop")
    variant = listing.variant
    qty = to_milli(body.quantity, variant.sell_unit)
    key = f"adj:{ctx.user.id}:{idempotency_key}" if idempotency_key else None
    common = dict(source=body.source, actor_id=ctx.user.id, idempotency_key=key, note=body.note)
    if body.mode in ("add", "sell") and qty <= 0:
        raise bad_request("quantity_required", "Enter a quantity")
    if body.mode == "add":
        result = apply_change(db, listing, event_type="restock", delta_on_hand=qty,
                              verification="shopkeeper_confirmed", **common)
    elif body.mode == "sell":
        try:
            result = apply_change(db, listing, event_type="sale", delta_on_hand=-qty,
                                  verification="shopkeeper_confirmed", **common)
        except AppError as exc:
            if exc.code == "insufficient_stock":
                raise conflict("insufficient_stock",
                               f"Only {exc.details.get('on_hand', 0):g} in stock. Use 'Set count' to correct it.",
                               **exc.details)
            raise
    elif body.mode == "set":
        result = set_on_hand(db, listing, qty, event_type="adjustment", **common)
    else:
        result = set_on_hand(db, listing, 0, event_type="out_of_stock", **common)
    if body.client_recorded_at is not None and not result.replayed:
        recorded = body.client_recorded_at
        if recorded.tzinfo is None:
            recorded = recorded.replace(tzinfo=timezone.utc)
        now = utcnow()
        # Ignore clocks that are clearly wrong (future, or older than a week).
        if now - timedelta(days=7) <= recorded <= now + timedelta(minutes=5):
            result.event.client_recorded_at = recorded
    view = _view(db, listing)
    return {
        "listing": view,
        "event": event_view(result.event, variant.sell_unit),
        "replayed": result.replayed,
        "warning": (
            "Customers have reserved more than you have in stock. Please review reservations."
            if view["overcommitted"] else None
        ),
    }


@router.get("/inventory/listings/{listing_id}/events")
def listing_events(listing_id: int, limit: int = Query(default=50, le=200),
                   ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    listing = _my_listing(db, ctx, listing_id)
    events = db.execute(
        select(InventoryEvent).where(InventoryEvent.listing_id == listing.id)
        .order_by(InventoryEvent.id.desc()).limit(limit)
    ).scalars().all()
    return {"events": [event_view(e, listing.variant.sell_unit) for e in events]}


@router.get("/inventory/events")
def shop_events(limit: int = Query(default=30, le=200), ctx: ShopContext = Depends(require_shop),
                db: Session = Depends(get_db)):
    events = db.execute(
        select(InventoryEvent).where(InventoryEvent.shop_id == ctx.shop.id)
        .order_by(InventoryEvent.id.desc()).limit(limit)
    ).scalars().all()
    listings = {l.id: l for l in db.execute(
        select(ShopListing).where(ShopListing.id.in_({e.listing_id for e in events}))).scalars()}
    out = []
    for e in events:
        listing = listings.get(e.listing_id)
        unit = listing.variant.sell_unit if listing else "pc"
        out.append({**event_view(e, unit),
                    "name": (listing.custom_name or listing.variant.display_name) if listing else "Product"})
    return {"events": out}
