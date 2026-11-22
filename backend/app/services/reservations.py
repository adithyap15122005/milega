"""Reservation workflow with strict state transitions and no overselling.

States: pending -> confirmed -> collected;
        pending or confirmed -> rejected / cancelled / expired

* Creating a reservation places a *hold* (reserved += q) using a conditional
  UPDATE that only succeeds while ``on_hand - reserved >= q``. Two customers
  racing for the last item cannot both succeed.
* Every transition is a conditional ``UPDATE reservations SET status=... WHERE
  status IN (...)`` so a reservation is released or collected exactly once even
  if the customer cancels while the shop marks it collected.
"""

from __future__ import annotations

import secrets
from datetime import timedelta

from sqlalchemy import func, select, update
from sqlalchemy.orm import Session, selectinload

from ..config import Settings
from ..db import utcnow
from ..errors import AppError, conflict, forbidden, not_found
from ..models import Reservation, ReservationItem, Shop, ShopListing, User
from .activity import audit, notify
from .inventory import apply_change
from .units import from_milli, to_milli, unit_label

ACTIVE = ("pending", "confirmed")
_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O/1/I confusion

MAX_PIECES_PER_RESERVATION = 50_000  # milli -> 50 pieces
MAX_LOOSE_PER_RESERVATION = 25_000  # milli -> 25 kg / L


def _new_code() -> str:
    return "".join(secrets.choice(_CODE_ALPHABET) for _ in range(6))


def create_reservation(
    db: Session,
    settings: Settings,
    *,
    customer: User,
    listing_id: int,
    quantity,
    idempotency_key: str | None,
    note: str | None = None,
) -> Reservation:
    namespaced_key = f"res:{customer.id}:{idempotency_key}" if idempotency_key else None
    if namespaced_key:
        existing = db.execute(
            select(Reservation).where(Reservation.idempotency_key == namespaced_key)
        ).scalar_one_or_none()
        if existing:
            return existing

    listing = db.get(ShopListing, listing_id)
    if listing is None or not listing.is_active:
        raise not_found("Product listing")
    shop = db.get(Shop, listing.shop_id)
    if shop is None or shop.status != "active":
        raise AppError(409, "shop_unavailable", "This shop is not accepting reservations right now")
    if shop.owner_user_id == customer.id:
        raise forbidden("You cannot reserve from your own shop")

    variant = listing.variant
    qty_milli = to_milli(quantity, variant.sell_unit)
    if qty_milli <= 0:
        raise AppError(400, "invalid_quantity", "Choose a quantity greater than zero")
    limit = MAX_PIECES_PER_RESERVATION if variant.sell_unit == "pc" else MAX_LOOSE_PER_RESERVATION
    if qty_milli > limit:
        raise AppError(400, "quantity_limit", f"You can reserve at most {from_milli(limit):g} {unit_label(variant.sell_unit)}")

    expire_due(db, shop_id=shop.id)

    active_count = db.scalar(
        select(func.count()).select_from(Reservation).where(
            Reservation.customer_user_id == customer.id, Reservation.status.in_(ACTIVE)
        )
    )
    if active_count >= settings.max_active_reservations_per_customer:
        raise AppError(409, "too_many_reservations",
                       "You have too many open reservations. Collect or cancel one first.")

    now = utcnow()
    reservation = Reservation(
        code=_new_code(),
        customer_user_id=customer.id,
        shop_id=shop.id,
        status="pending",
        expires_at=now + timedelta(minutes=settings.pending_reservation_minutes),
        customer_note=(note or None) and note[:255],
        idempotency_key=namespaced_key,
    )
    db.add(reservation)
    db.flush()
    # The hold. Raises 409 insufficient_stock if someone else got there first.
    apply_change(
        db, listing, event_type="reservation_hold", source="reservation",
        delta_reserved=qty_milli, actor_id=customer.id, verification="system",
        require_available=True, reference=("reservation", reservation.id),
    )
    db.add(ReservationItem(
        reservation_id=reservation.id, listing_id=listing.id, variant_id=variant.id,
        quantity_milli=qty_milli, unit_price_paise=listing.price_paise,
        name_snapshot=listing.custom_name or variant.display_name, sell_unit=variant.sell_unit,
    ))
    db.flush()
    notify(
        db, user_id=shop.owner_user_id, kind="reservation_new",
        title="New reservation",
        body=f"{from_milli(qty_milli):g} {unit_label(variant.sell_unit)} × {variant.display_name} — code {reservation.code}",
        data={"reservation_id": reservation.id},
    )
    db.refresh(reservation)
    return reservation


def _transition(db: Session, reservation: Reservation, new_status: str, allowed: tuple[str, ...], **extra) -> None:
    result = db.execute(
        update(Reservation)
        .where(Reservation.id == reservation.id, Reservation.status.in_(allowed))
        .values(status=new_status, updated_at=utcnow(), **extra)
        .execution_options(synchronize_session=False)
    )
    if result.rowcount != 1:
        db.refresh(reservation)
        raise conflict(
            "invalid_transition",
            f"This reservation is already {reservation.status}",
            status=reservation.status,
        )
    db.refresh(reservation)


def _release_items(db: Session, reservation: Reservation, *, event_type: str, actor_id: int | None, collected: bool) -> None:
    for item in reservation.items:
        listing = db.get(ShopListing, item.listing_id)
        apply_change(
            db, listing, event_type=event_type, source="reservation",
            delta_reserved=-item.quantity_milli,
            delta_on_hand=-item.quantity_milli if collected else 0,
            actor_id=actor_id, verification="shopkeeper_confirmed" if collected else "system",
            reference=("reservation", reservation.id),
        )


def confirm(db: Session, settings: Settings, reservation: Reservation, actor: User, note: str | None = None) -> Reservation:
    now = utcnow()
    _transition(db, reservation, "confirmed", ("pending",), confirmed_at=now,
                expires_at=now + timedelta(minutes=settings.confirmed_hold_minutes),
                shop_note=(note or None) and note[:255])
    notify(db, user_id=reservation.customer_user_id, kind="reservation_confirmed",
           title="Reservation confirmed",
           body=f"{reservation.shop.name} has kept your items aside. Show code {reservation.code}.",
           data={"reservation_id": reservation.id})
    audit(db, actor_id=actor.id, action="reservation.confirm", entity_type="reservation", entity_id=reservation.id)
    return reservation


def reject(db: Session, reservation: Reservation, actor: User, reason: str | None = None) -> Reservation:
    _transition(db, reservation, "rejected", ACTIVE, closed_at=utcnow(), shop_note=(reason or None) and reason[:255])
    _release_items(db, reservation, event_type="reservation_release", actor_id=actor.id, collected=False)
    notify(db, user_id=reservation.customer_user_id, kind="reservation_rejected",
           title="Reservation not available",
           body=f"{reservation.shop.name} could not keep your items." + (f" Reason: {reason}" if reason else ""),
           data={"reservation_id": reservation.id})
    audit(db, actor_id=actor.id, action="reservation.reject", entity_type="reservation", entity_id=reservation.id,
          data={"reason": reason})
    return reservation


def mark_collected(db: Session, reservation: Reservation, actor: User) -> Reservation:
    _transition(db, reservation, "collected", ACTIVE, closed_at=utcnow())
    _release_items(db, reservation, event_type="reservation_collected", actor_id=actor.id, collected=True)
    notify(db, user_id=reservation.customer_user_id, kind="reservation_collected",
           title="Collected", body=f"Thanks for shopping at {reservation.shop.name}!",
           data={"reservation_id": reservation.id})
    audit(db, actor_id=actor.id, action="reservation.collected", entity_type="reservation", entity_id=reservation.id)
    return reservation


def cancel(db: Session, reservation: Reservation, actor: User) -> Reservation:
    _transition(db, reservation, "cancelled", ACTIVE, closed_at=utcnow())
    _release_items(db, reservation, event_type="reservation_release", actor_id=actor.id, collected=False)
    notify(db, user_id=reservation.shop.owner_user_id, kind="reservation_cancelled",
           title="Reservation cancelled", body=f"Customer cancelled reservation {reservation.code}.",
           data={"reservation_id": reservation.id})
    return reservation


def expire_due(db: Session, *, shop_id: int | None = None, limit: int = 200) -> int:
    """Expire overdue reservations and release their holds. Safe to run concurrently."""
    now = utcnow()
    q = select(Reservation).where(Reservation.status.in_(ACTIVE), Reservation.expires_at < now)
    if shop_id is not None:
        q = q.where(Reservation.shop_id == shop_id)
    due = db.execute(q.options(selectinload(Reservation.items)).limit(limit)).scalars().all()
    expired = 0
    for reservation in due:
        result = db.execute(
            update(Reservation)
            .where(Reservation.id == reservation.id, Reservation.status.in_(ACTIVE), Reservation.expires_at < now)
            .values(status="expired", closed_at=now, updated_at=now)
            .execution_options(synchronize_session=False)
        )
        if result.rowcount != 1:
            continue  # someone else moved it on
        _release_items(db, reservation, event_type="reservation_release", actor_id=None, collected=False)
        db.refresh(reservation)
        notify(db, user_id=reservation.customer_user_id, kind="reservation_expired",
               title="Reservation expired", body=f"Reservation {reservation.code} has expired.",
               data={"reservation_id": reservation.id})
        expired += 1
    return expired


def reservation_view(reservation: Reservation, *, include_customer: bool = False) -> dict:
    shop = reservation.shop
    view = {
        "id": reservation.id,
        "code": reservation.code,
        "status": reservation.status,
        "expires_at": reservation.expires_at.isoformat(),
        "created_at": reservation.created_at.isoformat(),
        "confirmed_at": reservation.confirmed_at.isoformat() if reservation.confirmed_at else None,
        "closed_at": reservation.closed_at.isoformat() if reservation.closed_at else None,
        "customer_note": reservation.customer_note,
        "shop_note": reservation.shop_note,
        "shop": {
            "id": shop.id, "name": shop.name, "locality": shop.locality, "city": shop.city,
            "address_line": shop.address_line, "lat": shop.lat, "lng": shop.lng,
        },
        "items": [
            {
                "listing_id": i.listing_id, "variant_id": i.variant_id, "name": i.name_snapshot,
                "quantity": from_milli(i.quantity_milli), "unit_label": unit_label(i.sell_unit),
                "unit_price_paise": i.unit_price_paise,
            }
            for i in reservation.items
        ],
    }
    if include_customer:
        customer = reservation.customer
        view["customer"] = {"name": customer.name or "Customer", "phone_last4": customer.phone[-4:]}
    return view
