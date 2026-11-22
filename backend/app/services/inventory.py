"""Inventory ledger engine.

Every change to stock goes through :func:`apply_change`, which in ONE
transaction:

1. updates ``inventory_balances`` with a *conditional* UPDATE ... RETURNING
   (so two concurrent writers can never drive stock negative or reserve more
   than is available), and
2. appends an ``inventory_events`` row recording who/what/why plus the
   resulting balances.

AI recognition never calls this directly; only the confirm endpoints (a person
pressed "Save") and the reservation workflow do.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..db import utcnow
from ..errors import AppError, conflict, not_found
from ..models import InventoryBalance, InventoryEvent, ShopListing, ProductVariant
from .units import from_milli, to_milli, unit_label

# Sources that represent a person at the shop confirming stock (freshness).
SHOPKEEPER_SOURCES = {"manual", "voice", "photo_scan", "shelf_scan", "invoice_scan"}

FRESH_HOURS = 24
AGING_HOURS = 72


@dataclass
class ChangeResult:
    event: InventoryEvent
    on_hand_milli: int
    reserved_milli: int
    replayed: bool = False

    @property
    def overcommitted(self) -> bool:
        return self.on_hand_milli < self.reserved_milli


def _event_for_key(db: Session, key: str) -> InventoryEvent | None:
    return db.execute(select(InventoryEvent).where(InventoryEvent.idempotency_key == key)).scalar_one_or_none()


def apply_change(
    db: Session,
    listing: ShopListing,
    *,
    event_type: str,
    source: str,
    delta_on_hand: int = 0,
    delta_reserved: int = 0,
    actor_id: int | None,
    verification: str,
    require_available: bool = False,
    reference: tuple[str, str] | None = None,
    idempotency_key: str | None = None,
    note: str | None = None,
) -> ChangeResult:
    """Apply a stock delta atomically and record it in the ledger.

    ``require_available`` (used for reservation holds) additionally demands
    ``on_hand - reserved >= delta_reserved`` at the moment of the update.
    """
    if idempotency_key:
        existing = _event_for_key(db, idempotency_key)
        if existing is not None:
            return ChangeResult(existing, existing.on_hand_after_milli, existing.reserved_after_milli, replayed=True)
        try:
            # Savepoint: a duplicate-key race undoes only this change, not the
            # caller's surrounding transaction.
            with db.begin_nested():
                return _apply(db, listing, event_type=event_type, source=source,
                              delta_on_hand=delta_on_hand, delta_reserved=delta_reserved,
                              actor_id=actor_id, verification=verification,
                              require_available=require_available, reference=reference,
                              idempotency_key=idempotency_key, note=note)
        except IntegrityError:
            existing = _event_for_key(db, idempotency_key)
            if existing is None:
                raise
            return ChangeResult(existing, existing.on_hand_after_milli, existing.reserved_after_milli, replayed=True)
    return _apply(db, listing, event_type=event_type, source=source,
                  delta_on_hand=delta_on_hand, delta_reserved=delta_reserved,
                  actor_id=actor_id, verification=verification,
                  require_available=require_available, reference=reference,
                  idempotency_key=None, note=note)


def _apply(
    db: Session,
    listing: ShopListing,
    *,
    event_type: str,
    source: str,
    delta_on_hand: int,
    delta_reserved: int,
    actor_id: int | None,
    verification: str,
    require_available: bool,
    reference: tuple[str, str] | None,
    idempotency_key: str | None,
    note: str | None,
) -> ChangeResult:
    now = utcnow()
    bal = InventoryBalance
    stmt = update(bal).where(bal.listing_id == listing.id)
    if delta_on_hand < 0:
        stmt = stmt.where(bal.on_hand_milli + delta_on_hand >= 0)
    if delta_reserved < 0:
        stmt = stmt.where(bal.reserved_milli + delta_reserved >= 0)
    if require_available:
        stmt = stmt.where(bal.on_hand_milli - bal.reserved_milli >= delta_reserved)
    values: dict = {
        "on_hand_milli": bal.on_hand_milli + delta_on_hand,
        "reserved_milli": bal.reserved_milli + delta_reserved,
        "version": bal.version + 1,
        "updated_at": now,
    }
    if source in SHOPKEEPER_SOURCES:
        values["stock_confirmed_at"] = now
    row = db.execute(
        stmt.values(**values).returning(bal.on_hand_milli, bal.reserved_milli)
    ).first()
    if row is None:
        current = db.execute(select(bal).where(bal.listing_id == listing.id)).scalar_one_or_none()
        if current is None:
            raise not_found("Stock record")
        available = max(current.on_hand_milli - current.reserved_milli, 0)
        raise conflict(
            "insufficient_stock",
            "Not enough stock for this change",
            on_hand=from_milli(current.on_hand_milli),
            reserved=from_milli(current.reserved_milli),
            available=from_milli(available),
        )
    on_hand_after, reserved_after = row
    event = InventoryEvent(
        shop_id=listing.shop_id,
        listing_id=listing.id,
        variant_id=listing.variant_id,
        event_type=event_type,
        source=source,
        delta_on_hand_milli=delta_on_hand,
        delta_reserved_milli=delta_reserved,
        on_hand_after_milli=on_hand_after,
        reserved_after_milli=reserved_after,
        actor_user_id=actor_id,
        verification=verification,
        reference_type=reference[0] if reference else None,
        reference_id=str(reference[1]) if reference else None,
        idempotency_key=idempotency_key,
        note=note,
        created_at=now,
    )
    db.add(event)
    db.flush()
    return ChangeResult(event, on_hand_after, reserved_after)


def set_on_hand(
    db: Session,
    listing: ShopListing,
    target_milli: int,
    *,
    event_type: str,
    source: str,
    actor_id: int | None,
    verification: str = "shopkeeper_confirmed",
    reference: tuple[str, str] | None = None,
    idempotency_key: str | None = None,
    note: str | None = None,
) -> ChangeResult:
    """Set an absolute stock count (e.g. after a recount) as a ledger delta."""
    for _attempt in range(3):
        bal = db.execute(
            select(InventoryBalance)
            .where(InventoryBalance.listing_id == listing.id)
            .execution_options(populate_existing=True)
        ).scalar_one()
        delta = target_milli - bal.on_hand_milli
        try:
            return apply_change(
                db, listing, event_type=event_type, source=source, delta_on_hand=delta,
                actor_id=actor_id, verification=verification, reference=reference,
                idempotency_key=idempotency_key, note=note,
            )
        except AppError as exc:
            if exc.code != "insufficient_stock":
                raise
            # Balance moved between read and write; retry with the fresh value.
    raise conflict("concurrent_update", "Stock changed while saving. Please try again.")


def create_listing(
    db: Session,
    *,
    shop_id: int,
    variant: ProductVariant,
    price_paise: int | None,
    initial_quantity_milli: int,
    actor_id: int,
    source: str,
    custom_name: str | None = None,
    reference: tuple[str, str] | None = None,
) -> tuple[ShopListing, bool]:
    """Create (or reactivate) the shop's listing for a variant. Returns (listing, created)."""
    listing = db.execute(
        select(ShopListing).where(ShopListing.shop_id == shop_id, ShopListing.variant_id == variant.id)
    ).scalar_one_or_none()
    created = False
    if listing is None:
        listing = ShopListing(
            shop_id=shop_id, variant_id=variant.id, price_paise=price_paise, custom_name=custom_name,
            low_stock_threshold_milli=3000 if variant.sell_unit == "pc" else 1000,
        )
        db.add(listing)
        db.flush()
        db.add(InventoryBalance(listing_id=listing.id, shop_id=shop_id, variant_id=variant.id,
                                on_hand_milli=0, reserved_milli=0, updated_at=utcnow()))
        db.flush()
        created = True
    else:
        listing.is_active = True
        if price_paise is not None:
            listing.price_paise = price_paise
        if custom_name:
            listing.custom_name = custom_name
    if initial_quantity_milli > 0:
        apply_change(
            db, listing, event_type="initial_stock" if created else "restock", source=source,
            delta_on_hand=initial_quantity_milli, actor_id=actor_id,
            verification="shopkeeper_confirmed", reference=reference,
        )
    return listing, created


# ------------------------------------------------------------------ read models


def freshness(balance: InventoryBalance, now: datetime | None = None) -> dict:
    """Customer-facing availability label. Never claims more than the data supports."""
    now = now or utcnow()
    available = max(balance.on_hand_milli - balance.reserved_milli, 0)
    confirmed = balance.stock_confirmed_at
    age_hours = (now - confirmed).total_seconds() / 3600 if confirmed else None
    if available <= 0:
        status = "out_of_stock"
    elif age_hours is None or age_hours > AGING_HOURS:
        status = "unverified"
    elif age_hours > FRESH_HOURS:
        status = "likely"
    else:
        status = "in_stock"
    return {
        "status": status,
        "available": from_milli(available),
        "stock_confirmed_at": confirmed.isoformat() if confirmed else None,
        "age_hours": round(age_hours, 1) if age_hours is not None else None,
    }


def listing_view(listing: ShopListing, balance: InventoryBalance) -> dict:
    variant = listing.variant
    available = max(balance.on_hand_milli - balance.reserved_milli, 0)
    return {
        "id": listing.id,
        "variant_id": variant.id,
        "name": listing.custom_name or variant.display_name,
        "catalog_name": variant.display_name,
        "brand": variant.product.brand,
        "category": variant.product.category_slug,
        "size_label": variant.size_label,
        "sell_unit": variant.sell_unit,
        "unit_label": unit_label(variant.sell_unit),
        "is_loose": variant.is_loose,
        "variant_status": variant.status,
        "price_paise": listing.price_paise,
        "on_hand": from_milli(balance.on_hand_milli),
        "reserved": from_milli(balance.reserved_milli),
        "available": from_milli(available),
        "low_stock": 0 < balance.on_hand_milli <= listing.low_stock_threshold_milli,
        "out_of_stock": balance.on_hand_milli <= 0,
        "overcommitted": balance.on_hand_milli < balance.reserved_milli,
        "low_stock_threshold": from_milli(listing.low_stock_threshold_milli),
        "stock_confirmed_at": balance.stock_confirmed_at.isoformat() if balance.stock_confirmed_at else None,
        "updated_at": balance.updated_at.isoformat(),
        "is_active": listing.is_active,
        "image_key": variant.image_key,
    }


def event_view(event: InventoryEvent, sell_unit: str = "pc") -> dict:
    return {
        "id": event.id,
        "listing_id": event.listing_id,
        "event_type": event.event_type,
        "source": event.source,
        "delta_on_hand": from_milli(event.delta_on_hand_milli),
        "delta_reserved": from_milli(event.delta_reserved_milli),
        "on_hand_after": from_milli(event.on_hand_after_milli),
        "reserved_after": from_milli(event.reserved_after_milli),
        "verification": event.verification,
        "reference_type": event.reference_type,
        "reference_id": event.reference_id,
        "note": event.note,
        "actor_user_id": event.actor_user_id,
        "created_at": event.created_at.isoformat(),
        "unit_label": unit_label(sell_unit),
    }


def quantity_to_milli(quantity, variant: ProductVariant) -> int:
    return to_milli(quantity, variant.sell_unit)


def recent_cutoff(hours: int = 24) -> datetime:
    return utcnow() - timedelta(hours=hours)
