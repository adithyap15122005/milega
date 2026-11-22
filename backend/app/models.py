"""Relational data model.

Key ideas
---------
* Product identity (``products`` / ``product_variants``) is separate from what a
  shop sells (``shop_listings``): "Tata Salt 1 kg" is one canonical variant that
  many shops list at their own price.
* Stock is a ledger: ``inventory_events`` is the append-only history and
  ``inventory_balances`` is the derived current state, updated in the same
  transaction. Available = on_hand - reserved.
* Quantities are stored as integer *milli-units* (1 piece = 1000, 1.5 kg =
  1500) so loose goods sold by weight work without floating-point drift.
* Money is stored as integer paise.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    CheckConstraint,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base, BigIntPK, UTCDateTime, utcnow


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, onupdate=utcnow, nullable=False
    )


# ---------------------------------------------------------------- users / auth


class User(TimestampMixin, Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    phone: Mapped[str] = mapped_column(String(16), unique=True, index=True)
    name: Mapped[str | None] = mapped_column(String(120))
    # customer | shopkeeper | admin  (a shopkeeper can also shop as a customer)
    role: Mapped[str] = mapped_column(String(16), default="customer", nullable=False)
    preferred_language: Mapped[str] = mapped_column(String(8), default="en", nullable=False)
    token_version: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    shop: Mapped["Shop | None"] = relationship(back_populates="owner", uselist=False)


class OtpChallenge(Base):
    __tablename__ = "otp_challenges"

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    phone: Mapped[str] = mapped_column(String(16), index=True)
    code_hash: Mapped[str] = mapped_column(String(128))
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    attempts: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    consumed: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)


# ---------------------------------------------------------------------- shops


class Category(Base):
    __tablename__ = "categories"

    slug: Mapped[str] = mapped_column(String(40), primary_key=True)
    name_en: Mapped[str] = mapped_column(String(80))
    name_hi: Mapped[str] = mapped_column(String(80))
    name_kn: Mapped[str] = mapped_column(String(80))
    icon: Mapped[str] = mapped_column(String(40), default="store")
    sort_order: Mapped[int] = mapped_column(Integer, default=100)


class Shop(TimestampMixin, Base):
    __tablename__ = "shops"
    __table_args__ = (
        Index("ix_shops_lat_lng", "lat", "lng"),
        Index("ix_shops_status", "status"),
        CheckConstraint("lat BETWEEN -90 AND 90", name="ck_shops_lat"),
        CheckConstraint("lng BETWEEN -180 AND 180", name="ck_shops_lng"),
    )

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    owner_user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), unique=True)
    name: Mapped[str] = mapped_column(String(120))
    description: Mapped[str | None] = mapped_column(Text)
    show_phone: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    address_line: Mapped[str] = mapped_column(String(255), default="")
    locality: Mapped[str] = mapped_column(String(120), default="")
    city: Mapped[str] = mapped_column(String(80), default="")
    pincode: Mapped[str] = mapped_column(String(10), default="")
    lat: Mapped[float] = mapped_column(Float)
    lng: Mapped[float] = mapped_column(Float)
    location_source: Mapped[str] = mapped_column(String(16), default="gps")  # gps | manual
    location_verified: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    # pending (new, not yet visible to customers) | active | suspended
    status: Mapped[str] = mapped_column(String(16), default="pending", nullable=False)
    categories: Mapped[list] = mapped_column(JSON, default=list)
    preferred_language: Mapped[str] = mapped_column(String(8), default="en")
    is_demo: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    verified_at: Mapped[datetime | None] = mapped_column(UTCDateTime)

    owner: Mapped[User] = relationship(back_populates="shop")
    hours: Mapped[list["ShopHours"]] = relationship(
        back_populates="shop", cascade="all, delete-orphan", order_by="ShopHours.weekday"
    )


class ShopHours(Base):
    __tablename__ = "shop_hours"
    __table_args__ = (UniqueConstraint("shop_id", "weekday", name="uq_shop_hours_day"),)

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    shop_id: Mapped[int] = mapped_column(ForeignKey("shops.id", ondelete="CASCADE"))
    weekday: Mapped[int] = mapped_column(Integer)  # 0 = Monday
    opens: Mapped[str] = mapped_column(String(5), default="09:00")
    closes: Mapped[str] = mapped_column(String(5), default="21:00")
    is_closed: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    shop: Mapped[Shop] = relationship(back_populates="hours")


# -------------------------------------------------------------------- catalogue


class Product(TimestampMixin, Base):
    __tablename__ = "products"

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    brand: Mapped[str | None] = mapped_column(String(80), index=True)
    name: Mapped[str] = mapped_column(String(160))
    category_slug: Mapped[str] = mapped_column(ForeignKey("categories.slug"))
    description: Mapped[str | None] = mapped_column(Text)

    variants: Mapped[list["ProductVariant"]] = relationship(back_populates="product")


class ProductVariant(TimestampMixin, Base):
    __tablename__ = "product_variants"
    __table_args__ = (Index("ix_variants_status", "status"),)

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    product_id: Mapped[int] = mapped_column(ForeignKey("products.id"), index=True)
    display_name: Mapped[str] = mapped_column(String(200))
    size_label: Mapped[str | None] = mapped_column(String(40))
    # Normalised size, e.g. 1 kg -> (1000, "g"); 25W -> (25, "w").
    size_value: Mapped[float | None] = mapped_column(Float)
    size_unit: Mapped[str | None] = mapped_column(String(12))
    # How stock is counted: pc (pieces/packets) or kg / g / l / ml / m for loose goods.
    sell_unit: Mapped[str] = mapped_column(String(8), default="pc", nullable=False)
    is_loose: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    aliases: Mapped[list] = mapped_column(JSON, default=list)
    search_text: Mapped[str] = mapped_column(Text, default="")
    # verified | provisional (created by a shopkeeper, awaiting review) | merged
    status: Mapped[str] = mapped_column(String(16), default="verified", nullable=False)
    merged_into_id: Mapped[int | None] = mapped_column(ForeignKey("product_variants.id"))
    image_key: Mapped[str | None] = mapped_column(String(255))
    created_by_user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))

    product: Mapped[Product] = relationship(back_populates="variants")


class ProductPhoto(Base):
    __tablename__ = "product_photos"

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    variant_id: Mapped[int | None] = mapped_column(ForeignKey("product_variants.id"), index=True)
    shop_id: Mapped[int | None] = mapped_column(ForeignKey("shops.id"))
    storage_key: Mapped[str] = mapped_column(String(255))
    source: Mapped[str] = mapped_column(String(24), default="recognition")
    uploaded_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)


class ProductVerification(Base):
    """Catalogue quality queue: provisional products, name corrections, duplicates."""

    __tablename__ = "product_verifications"
    __table_args__ = (Index("ix_verifications_status", "status"),)

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    kind: Mapped[str] = mapped_column(String(32))  # provisional_product | name_correction | duplicate_report
    variant_id: Mapped[int | None] = mapped_column(ForeignKey("product_variants.id"))
    shop_id: Mapped[int | None] = mapped_column(ForeignKey("shops.id"))
    submitted_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    payload: Mapped[dict] = mapped_column(JSON, default=dict)
    status: Mapped[str] = mapped_column(String(16), default="open", nullable=False)
    resolved_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    resolved_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    resolution_note: Mapped[str | None] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)


# -------------------------------------------------------------------- inventory


class ShopListing(TimestampMixin, Base):
    __tablename__ = "shop_listings"
    __table_args__ = (
        UniqueConstraint("shop_id", "variant_id", name="uq_listing_shop_variant"),
        CheckConstraint("price_paise IS NULL OR price_paise >= 0", name="ck_listing_price"),
        Index("ix_listings_variant", "variant_id"),
    )

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    shop_id: Mapped[int] = mapped_column(ForeignKey("shops.id"), index=True)
    variant_id: Mapped[int] = mapped_column(ForeignKey("product_variants.id"))
    price_paise: Mapped[int | None] = mapped_column(Integer)
    custom_name: Mapped[str | None] = mapped_column(String(200))
    low_stock_threshold_milli: Mapped[int] = mapped_column(BigInteger, default=3000, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    variant: Mapped[ProductVariant] = relationship()
    shop: Mapped[Shop] = relationship()
    balance: Mapped["InventoryBalance"] = relationship(back_populates="listing", uselist=False)


class InventoryBalance(Base):
    __tablename__ = "inventory_balances"
    __table_args__ = (
        CheckConstraint("on_hand_milli >= 0", name="ck_balance_on_hand"),
        CheckConstraint("reserved_milli >= 0", name="ck_balance_reserved"),
    )

    listing_id: Mapped[int] = mapped_column(ForeignKey("shop_listings.id"), primary_key=True)
    shop_id: Mapped[int] = mapped_column(ForeignKey("shops.id"), index=True)
    variant_id: Mapped[int] = mapped_column(ForeignKey("product_variants.id"), index=True)
    on_hand_milli: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    reserved_milli: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    version: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)
    # Last time a *person at the shop* set or confirmed this stock. Drives the
    # freshness label customers see. System events (holds/releases) don't touch it.
    stock_confirmed_at: Mapped[datetime | None] = mapped_column(UTCDateTime)

    listing: Mapped[ShopListing] = relationship(back_populates="balance")


class InventoryEvent(Base):
    __tablename__ = "inventory_events"
    __table_args__ = (
        Index("ix_events_shop_created", "shop_id", "created_at"),
        Index("ix_events_listing_created", "listing_id", "created_at"),
    )

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    shop_id: Mapped[int] = mapped_column(ForeignKey("shops.id"))
    listing_id: Mapped[int] = mapped_column(ForeignKey("shop_listings.id"))
    variant_id: Mapped[int] = mapped_column(ForeignKey("product_variants.id"))
    # initial_stock | restock | sale | adjustment | out_of_stock | reconciliation |
    # invoice_import | reservation_hold | reservation_release | reservation_collected | merge
    event_type: Mapped[str] = mapped_column(String(32))
    # manual | voice | photo_scan | shelf_scan | invoice_scan | reservation | system | admin
    source: Mapped[str] = mapped_column(String(24))
    delta_on_hand_milli: Mapped[int] = mapped_column(BigInteger, default=0)
    delta_reserved_milli: Mapped[int] = mapped_column(BigInteger, default=0)
    on_hand_after_milli: Mapped[int] = mapped_column(BigInteger)
    reserved_after_milli: Mapped[int] = mapped_column(BigInteger)
    actor_user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    # shopkeeper_confirmed | system | admin
    verification: Mapped[str] = mapped_column(String(24))
    reference_type: Mapped[str | None] = mapped_column(String(32))
    reference_id: Mapped[str | None] = mapped_column(String(64))
    idempotency_key: Mapped[str | None] = mapped_column(String(120), unique=True)
    note: Mapped[str | None] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)


# ------------------------------------------------------------------ recognition


class RecognitionJob(TimestampMixin, Base):
    __tablename__ = "recognition_jobs"
    __table_args__ = (
        UniqueConstraint("user_id", "client_request_id", name="uq_recognition_client_request"),
        Index("ix_recognition_shop_created", "shop_id", "created_at"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    shop_id: Mapped[int] = mapped_column(ForeignKey("shops.id"))
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    mode: Mapped[str] = mapped_column(String(12))  # incoming | shelf | invoice
    # queued | processing | needs_review | confirmed | failed | discarded
    status: Mapped[str] = mapped_column(String(16), default="queued")
    provider: Mapped[str] = mapped_column(String(24))
    image_key: Mapped[str | None] = mapped_column(String(255))
    ocr_lines: Mapped[list | None] = mapped_column(JSON)
    image_quality: Mapped[dict | None] = mapped_column(JSON)
    raw_result: Mapped[dict | None] = mapped_column(JSON)
    error_code: Mapped[str | None] = mapped_column(String(40))
    error_message: Mapped[str | None] = mapped_column(String(255))
    client_request_id: Mapped[str | None] = mapped_column(String(64))
    confirmed_at: Mapped[datetime | None] = mapped_column(UTCDateTime)

    candidates: Mapped[list["RecognitionCandidate"]] = relationship(
        back_populates="job", cascade="all, delete-orphan", order_by="RecognitionCandidate.position"
    )


class RecognitionCandidate(Base):
    __tablename__ = "recognition_candidates"

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    job_id: Mapped[str] = mapped_column(ForeignKey("recognition_jobs.id", ondelete="CASCADE"), index=True)
    position: Mapped[int] = mapped_column(Integer, default=0)
    label: Mapped[str] = mapped_column(String(200))
    brand: Mapped[str | None] = mapped_column(String(80))
    size_text: Mapped[str | None] = mapped_column(String(40))
    detected_text: Mapped[str | None] = mapped_column(Text)
    visible_count: Mapped[int | None] = mapped_column(Integer)
    count_reliable: Mapped[bool] = mapped_column(Boolean, default=False)
    provider_confidence: Mapped[float] = mapped_column(Float, default=0.0)
    match_confidence: Mapped[float] = mapped_column(Float, default=0.0)
    confidence_level: Mapped[str] = mapped_column(String(8), default="low")  # high | medium | low
    matches: Mapped[list] = mapped_column(JSON, default=list)
    suggested_variant_id: Mapped[int | None] = mapped_column(ForeignKey("product_variants.id"))
    invoice_quantity: Mapped[float | None] = mapped_column(Float)
    invoice_unit: Mapped[str | None] = mapped_column(String(16))
    invoice_unit_price_paise: Mapped[int | None] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(12), default="pending")  # pending | applied | skipped
    applied_variant_id: Mapped[int | None] = mapped_column(ForeignKey("product_variants.id"))
    applied_quantity_milli: Mapped[int | None] = mapped_column(BigInteger)

    job: Mapped[RecognitionJob] = relationship(back_populates="candidates")


# ----------------------------------------------------------------- reservations


class Reservation(TimestampMixin, Base):
    __tablename__ = "reservations"
    __table_args__ = (
        Index("ix_reservations_shop_status", "shop_id", "status"),
        Index("ix_reservations_customer_created", "customer_user_id", "created_at"),
        Index("ix_reservations_status_expires", "status", "expires_at"),
    )

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    code: Mapped[str] = mapped_column(String(10), unique=True)
    customer_user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    shop_id: Mapped[int] = mapped_column(ForeignKey("shops.id"))
    # pending | confirmed | rejected | collected | cancelled | expired
    status: Mapped[str] = mapped_column(String(16), default="pending", nullable=False)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    customer_note: Mapped[str | None] = mapped_column(String(255))
    shop_note: Mapped[str | None] = mapped_column(String(255))
    idempotency_key: Mapped[str | None] = mapped_column(String(120), unique=True)
    confirmed_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    closed_at: Mapped[datetime | None] = mapped_column(UTCDateTime)

    items: Mapped[list["ReservationItem"]] = relationship(
        back_populates="reservation", cascade="all, delete-orphan"
    )
    shop: Mapped[Shop] = relationship()
    customer: Mapped[User] = relationship()


class ReservationItem(Base):
    __tablename__ = "reservation_items"

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    reservation_id: Mapped[int] = mapped_column(ForeignKey("reservations.id", ondelete="CASCADE"), index=True)
    listing_id: Mapped[int] = mapped_column(ForeignKey("shop_listings.id"))
    variant_id: Mapped[int] = mapped_column(ForeignKey("product_variants.id"))
    quantity_milli: Mapped[int] = mapped_column(BigInteger)
    unit_price_paise: Mapped[int | None] = mapped_column(Integer)
    name_snapshot: Mapped[str] = mapped_column(String(200))
    sell_unit: Mapped[str] = mapped_column(String(8), default="pc")

    reservation: Mapped[Reservation] = relationship(back_populates="items")


# ------------------------------------------------------------ notifications/audit


class Notification(Base):
    __tablename__ = "notifications"
    __table_args__ = (Index("ix_notifications_user_created", "user_id", "created_at"),)

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    kind: Mapped[str] = mapped_column(String(40))
    title: Mapped[str] = mapped_column(String(160))
    body: Mapped[str] = mapped_column(String(400))
    data: Mapped[dict] = mapped_column(JSON, default=dict)
    read_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)


class AuditLog(Base):
    __tablename__ = "audit_logs"
    __table_args__ = (Index("ix_audit_entity", "entity_type", "entity_id"),)

    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True)
    actor_user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    action: Mapped[str] = mapped_column(String(60))
    entity_type: Mapped[str] = mapped_column(String(40))
    entity_id: Mapped[str] = mapped_column(String(64))
    data: Mapped[dict] = mapped_column(JSON, default=dict)
    ip: Mapped[str | None] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)
