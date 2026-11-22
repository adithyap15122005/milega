"""Authentication (phone OTP + JWT) and role-based authorisation dependencies."""

from __future__ import annotations

import hashlib
import hmac
import re
import secrets
from datetime import timedelta

import jwt
from fastapi import Depends, Header, Request
from sqlalchemy.orm import Session

from .config import Settings
from .db import utcnow
from .errors import AppError, forbidden
from .models import Shop, User

_PHONE_RE = re.compile(r"^[6-9]\d{9}$")


def normalize_phone(raw: str) -> str:
    digits = re.sub(r"\D", "", raw or "")
    if digits.startswith("91") and len(digits) == 12:
        digits = digits[2:]
    elif digits.startswith("0") and len(digits) == 11:
        digits = digits[1:]
    if not _PHONE_RE.match(digits):
        raise AppError(400, "invalid_phone", "Enter a valid 10-digit Indian mobile number")
    return "+91" + digits


def generate_otp() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


def hash_otp(settings: Settings, phone: str, code: str) -> str:
    return hmac.new(settings.jwt_secret.encode(), f"{phone}:{code}".encode(), hashlib.sha256).hexdigest()


def create_access_token(settings: Settings, user: User) -> str:
    now = utcnow()
    payload = {
        "sub": str(user.id),
        "role": user.role,
        "tv": user.token_version,
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(days=settings.access_token_days)).timestamp()),
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm="HS256")


# ------------------------------------------------------------- dependencies


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_db(request: Request):
    session: Session = request.app.state.db.SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def _user_from_token(token: str, settings: Settings, db: Session) -> User:
    try:
        payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
        user_id = int(payload["sub"])
    except (jwt.PyJWTError, KeyError, ValueError):
        raise AppError(401, "invalid_token", "Please log in again")
    user = db.get(User, user_id)
    if not user or not user.is_active or user.token_version != payload.get("tv"):
        raise AppError(401, "invalid_token", "Please log in again")
    return user


def _bearer(authorization: str | None) -> str | None:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    return None


def current_user(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
    db: Session = Depends(get_db),
) -> User:
    token = _bearer(authorization)
    if not token:
        raise AppError(401, "not_authenticated", "Please log in")
    return _user_from_token(token, settings, db)


def optional_user(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
    db: Session = Depends(get_db),
) -> User | None:
    token = _bearer(authorization)
    if not token:
        return None
    return _user_from_token(token, settings, db)


def require_admin(user: User = Depends(current_user)) -> User:
    if user.role != "admin":
        raise forbidden("Admin access required")
    return user


class ShopContext:
    def __init__(self, user: User, shop: Shop):
        self.user = user
        self.shop = shop


def require_shop(user: User = Depends(current_user), db: Session = Depends(get_db)) -> ShopContext:
    """The caller must own a shop. All shop-scoped queries filter by this shop id."""
    shop = db.query(Shop).filter(Shop.owner_user_id == user.id).one_or_none()
    if shop is None:
        raise AppError(403, "no_shop", "Register your shop first")
    if shop.status == "suspended":
        raise AppError(403, "shop_suspended", "This shop is suspended. Contact support.")
    return ShopContext(user, shop)


def client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"
