from __future__ import annotations

import hmac
import logging
from datetime import timedelta
from typing import Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import Settings
from ..db import utcnow
from ..errors import AppError
from ..models import OtpChallenge, Shop, User
from ..ratelimit import rate_limit
from ..security import (
    create_access_token,
    current_user,
    generate_otp,
    get_db,
    get_settings,
    hash_otp,
    normalize_phone,
)

router = APIRouter(prefix="/api/v1", tags=["auth"])
log = logging.getLogger("milega.auth")

Language = Literal["en", "hi", "kn"]


class OtpRequestIn(BaseModel):
    phone: str = Field(max_length=20)


class OtpVerifyIn(BaseModel):
    phone: str = Field(max_length=20)
    code: str = Field(min_length=4, max_length=8)
    name: str | None = Field(default=None, max_length=120)
    preferred_language: Language | None = None


class MePatchIn(BaseModel):
    name: str | None = Field(default=None, max_length=120)
    preferred_language: Language | None = None


def user_view(db: Session, user: User) -> dict:
    shop = db.execute(select(Shop).where(Shop.owner_user_id == user.id)).scalar_one_or_none()
    return {
        "id": user.id,
        "phone": user.phone,
        "name": user.name,
        "role": user.role,
        "preferred_language": user.preferred_language,
        "shop": {"id": shop.id, "name": shop.name, "status": shop.status} if shop else None,
    }


def send_otp(settings: Settings, phone: str, code: str) -> None:
    if settings.sms_provider == "console":
        # Development only (refused in production by Settings.validate).
        log.warning("DEV OTP for %s: %s", phone, code)
        return
    # Integrate an Indian SMS/WhatsApp OTP provider (MSG91, Gupshup, Twilio Verify) here.
    raise AppError(503, "sms_unavailable", "SMS service is not configured")


@router.post("/auth/otp/request", dependencies=[rate_limit("otp_request", 10, 600)])
def request_otp(body: OtpRequestIn, db: Session = Depends(get_db), settings: Settings = Depends(get_settings)):
    phone = normalize_phone(body.phone)
    since = utcnow() - timedelta(minutes=10)
    recent = db.scalar(
        select(func.count()).select_from(OtpChallenge).where(OtpChallenge.phone == phone, OtpChallenge.created_at > since)
    )
    if recent >= 3:
        raise AppError(429, "otp_rate_limited", "Too many codes requested. Try again in 10 minutes.")
    code = generate_otp()
    db.add(OtpChallenge(
        phone=phone, code_hash=hash_otp(settings, phone, code),
        expires_at=utcnow() + timedelta(seconds=settings.otp_ttl_seconds),
    ))
    send_otp(settings, phone, code)
    response = {"sent": True, "phone": phone, "expires_in": settings.otp_ttl_seconds}
    if settings.expose_dev_otp:
        response["dev_otp"] = code  # clearly-labelled development convenience
    return response


@router.post("/auth/otp/verify", dependencies=[rate_limit("otp_verify", 30, 600)])
def verify_otp(body: OtpVerifyIn, db: Session = Depends(get_db), settings: Settings = Depends(get_settings)):
    phone = normalize_phone(body.phone)
    challenge = db.execute(
        select(OtpChallenge)
        .where(OtpChallenge.phone == phone, OtpChallenge.consumed.is_(False))
        .order_by(OtpChallenge.id.desc())
        .limit(1)
    ).scalar_one_or_none()
    if challenge is None or challenge.expires_at < utcnow():
        raise AppError(400, "otp_expired", "The code has expired. Request a new one.")
    if challenge.attempts >= 5:
        raise AppError(429, "otp_attempts", "Too many wrong attempts. Request a new code.")
    challenge.attempts += 1
    if not hmac.compare_digest(challenge.code_hash, hash_otp(settings, phone, body.code.strip())):
        db.commit()  # persist the failed attempt before erroring
        raise AppError(400, "otp_invalid", "That code is not correct")
    challenge.consumed = True

    user = db.execute(select(User).where(User.phone == phone)).scalar_one_or_none()
    is_new = user is None
    if user is None:
        user = User(phone=phone, role="customer")
        db.add(user)
    if phone in settings.admin_phones:
        user.role = "admin"
    if body.name and not user.name:
        user.name = body.name.strip()
    if body.preferred_language:
        user.preferred_language = body.preferred_language
    db.flush()
    return {
        "access_token": create_access_token(settings, user),
        "token_type": "bearer",
        "is_new": is_new,
        "user": user_view(db, user),
    }


@router.get("/me")
def me(user: User = Depends(current_user), db: Session = Depends(get_db)):
    return user_view(db, user)


@router.patch("/me")
def update_me(body: MePatchIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    if body.name is not None:
        user.name = body.name.strip() or None
    if body.preferred_language is not None:
        user.preferred_language = body.preferred_language
    db.flush()
    return user_view(db, user)


@router.post("/auth/logout-all")
def logout_all(user: User = Depends(current_user)):
    user.token_version += 1
    return {"ok": True}
