"""Customer search, reservations (both sides), voice and notifications."""

from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, Header, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy import select, update
from sqlalchemy.orm import Session, selectinload

from ..config import Settings
from ..db import utcnow
from ..errors import not_found
from ..models import Notification, Reservation, User
from ..ratelimit import rate_limit
from ..security import ShopContext, current_user, get_db, get_settings, optional_user, require_shop
from ..services import reservations as rsv
from ..services.search import search_products
from ..services.voice import interpret

router = APIRouter(prefix="/api/v1")


# --------------------------------------------------------------------- search


@router.get("/search", tags=["search"], dependencies=[rate_limit("search", 120, 60)])
def search(
    request: Request,
    q: str = Query(default="", max_length=200),
    lat: float | None = Query(default=None, ge=-90, le=90),
    lng: float | None = Query(default=None, ge=-180, le=180),
    radius_km: float | None = Query(default=None, gt=0, le=25),
    sort: Literal["relevance", "nearest", "price", "availability"] = "relevance",
    category: str | None = Query(default=None, max_length=40),
    limit: int = Query(default=20, ge=1, le=50),
    offset: int = Query(default=0, ge=0, le=500),
    fresh_only: bool = False,
    lang: Literal["en", "hi", "kn"] | None = None,
    user: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    return search_products(
        db, settings, q=q, lat=lat, lng=lng, radius_km=radius_km, sort=sort, category=category, limit=limit,
        offset=offset, fresh_only=fresh_only, places=getattr(request.app.state, "places", None),
        user_id=user.id if user else None, language=lang or (user.preferred_language if user else "en"),
    )


# --------------------------------------------------------------- reservations


class ReservationIn(BaseModel):
    listing_id: int
    quantity: float = Field(gt=0)
    note: str | None = Field(default=None, max_length=255)


class ShopActionIn(BaseModel):
    note: str | None = Field(default=None, max_length=255)


def _load(db: Session, reservation_id: int) -> Reservation:
    reservation = db.execute(
        select(Reservation).options(selectinload(Reservation.items)).where(Reservation.id == reservation_id)
    ).scalar_one_or_none()
    if reservation is None:
        raise not_found("Reservation")
    return reservation


@router.post("/reservations", tags=["reservations"], status_code=201,
             dependencies=[rate_limit("reserve", 30, 600)])
def create_reservation(
    body: ReservationIn,
    idempotency_key: str | None = Header(default=None, max_length=80),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    reservation = rsv.create_reservation(db, settings, customer=user, listing_id=body.listing_id,
                                         quantity=body.quantity, idempotency_key=idempotency_key, note=body.note)
    return rsv.reservation_view(reservation)


@router.get("/reservations/mine", tags=["reservations"])
def my_reservations(user: User = Depends(current_user), db: Session = Depends(get_db)):
    rsv.expire_due(db)
    rows = db.execute(
        select(Reservation).options(selectinload(Reservation.items))
        .where(Reservation.customer_user_id == user.id).order_by(Reservation.id.desc()).limit(50)
    ).scalars().all()
    return {"reservations": [rsv.reservation_view(r) for r in rows]}


@router.get("/reservations/{reservation_id}", tags=["reservations"])
def get_reservation(reservation_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)):
    reservation = _load(db, reservation_id)
    is_shop = reservation.shop.owner_user_id == user.id
    if reservation.customer_user_id != user.id and not is_shop and user.role != "admin":
        raise not_found("Reservation")
    return rsv.reservation_view(reservation, include_customer=is_shop or user.role == "admin")


@router.post("/reservations/{reservation_id}/cancel", tags=["reservations"])
def cancel_reservation(reservation_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)):
    reservation = _load(db, reservation_id)
    if reservation.customer_user_id != user.id:
        raise not_found("Reservation")
    return rsv.reservation_view(rsv.cancel(db, reservation, user))


@router.get("/shop/reservations", tags=["reservations"])
def shop_reservations(
    status: Literal["active", "pending", "confirmed", "closed", "all"] = "active",
    ctx: ShopContext = Depends(require_shop),
    db: Session = Depends(get_db),
):
    rsv.expire_due(db, shop_id=ctx.shop.id)
    q = select(Reservation).options(selectinload(Reservation.items)).where(Reservation.shop_id == ctx.shop.id)
    if status == "active":
        q = q.where(Reservation.status.in_(rsv.ACTIVE))
    elif status == "closed":
        q = q.where(Reservation.status.not_in(rsv.ACTIVE))
    elif status in ("pending", "confirmed"):
        q = q.where(Reservation.status == status)
    rows = db.execute(q.order_by(Reservation.id.desc()).limit(100)).scalars().all()
    return {"reservations": [rsv.reservation_view(r, include_customer=True) for r in rows]}


def _shop_reservation(db: Session, ctx: ShopContext, reservation_id: int) -> Reservation:
    reservation = _load(db, reservation_id)
    if reservation.shop_id != ctx.shop.id:
        raise not_found("Reservation")
    return reservation


@router.post("/shop/reservations/{reservation_id}/confirm", tags=["reservations"])
def confirm_reservation(reservation_id: int, body: ShopActionIn | None = None, ctx: ShopContext = Depends(require_shop),
                        db: Session = Depends(get_db), settings: Settings = Depends(get_settings)):
    r = rsv.confirm(db, settings, _shop_reservation(db, ctx, reservation_id), ctx.user, body.note if body else None)
    return rsv.reservation_view(r, include_customer=True)


@router.post("/shop/reservations/{reservation_id}/reject", tags=["reservations"])
def reject_reservation(reservation_id: int, body: ShopActionIn | None = None, ctx: ShopContext = Depends(require_shop),
                       db: Session = Depends(get_db)):
    r = rsv.reject(db, _shop_reservation(db, ctx, reservation_id), ctx.user, body.note if body else None)
    return rsv.reservation_view(r, include_customer=True)


@router.post("/shop/reservations/{reservation_id}/collected", tags=["reservations"])
def collected_reservation(reservation_id: int, ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    r = rsv.mark_collected(db, _shop_reservation(db, ctx, reservation_id), ctx.user)
    return rsv.reservation_view(r, include_customer=True)


# ---------------------------------------------------------------------- voice


class VoiceIn(BaseModel):
    text: str = Field(min_length=1, max_length=300)
    language: Literal["en", "hi", "kn"] | None = None


@router.post("/voice/interpret", tags=["voice"], dependencies=[rate_limit("voice", 60, 60)])
def voice_interpret(body: VoiceIn, ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    return interpret(db, ctx.shop.id, body.text, body.language)


# -------------------------------------------------------------- notifications


@router.get("/notifications", tags=["notifications"])
def notifications(unread_only: bool = False, user: User = Depends(current_user), db: Session = Depends(get_db)):
    q = select(Notification).where(Notification.user_id == user.id)
    if unread_only:
        q = q.where(Notification.read_at.is_(None))
    rows = db.execute(q.order_by(Notification.id.desc()).limit(50)).scalars().all()
    unread = sum(1 for n in rows if n.read_at is None)
    return {
        "unread": unread,
        "notifications": [
            {"id": n.id, "kind": n.kind, "title": n.title, "body": n.body, "data": n.data,
             "read": n.read_at is not None, "created_at": n.created_at.isoformat()}
            for n in rows
        ],
    }


@router.post("/notifications/read", tags=["notifications"])
def mark_read(user: User = Depends(current_user), db: Session = Depends(get_db)):
    db.execute(update(Notification).where(Notification.user_id == user.id, Notification.read_at.is_(None))
               .values(read_at=utcnow()).execution_options(synchronize_session=False))
    return {"ok": True}
