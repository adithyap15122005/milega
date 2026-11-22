"""Seed the catalogue (always safe) and demo shops (development only).

    python -m app.seed            # catalogue + demo data
    python -m app.seed --catalog  # catalogue only (use this in production)
"""

from __future__ import annotations

import argparse
import random
from datetime import timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .config import load_settings
from .db import Database, utcnow
from .models import (
    Category,
    InventoryBalance,
    InventoryEvent,
    Product,
    ProductVariant,
    Shop,
    ShopHours,
    ShopListing,
    User,
)
from .seed_data import CATALOG, CATEGORIES
from .services.text import build_search_text
from .services.units import parse_size

DEMO_ADMIN = "+919000000001"
DEMO_CUSTOMER = "+919000000100"

# Sample shops around Jayanagar, Bengaluru. Clearly flagged is_demo=True.
DEMO_SHOPS = [
    ("+919000000011", "Sri Lakshmi Provision Stores", "Jayanagar 4th Block", 12.9299, 77.5826,
     ["staples", "snacks", "beverages", "dairy", "personal_care", "household"], "kn"),
    ("+919000000012", "Annapoorna Kirana & General", "Jayanagar 9th Block", 12.9226, 77.5937,
     ["staples", "snacks", "beverages", "dairy", "personal_care", "household"], "hi"),
    ("+919000000013", "Vinayaka Stationery & Xerox", "Jayanagar 3rd Block", 12.9326, 77.5856,
     ["stationery"], "kn"),
    ("+919000000014", "Sai Electricals & Hardware", "South End Circle", 12.9365, 77.5800,
     ["electrical", "hardware"], "en"),
    ("+919000000015", "Mobile Point Accessories", "Jayanagar 4th Block", 12.9290, 77.5835,
     ["mobile_accessories", "electrical"], "en"),
    ("+919000000016", "Fresh Mart Vegetables & Groceries", "JP Nagar 2nd Phase", 12.9120, 77.5900,
     ["fruits_vegetables", "staples", "dairy"], "kn"),
]
PENDING_SHOP = ("+919000000017", "New Balaji Stores", "BTM Layout 2nd Stage", 12.9166, 77.6101,
                ["staples", "snacks"], "hi")


def seed_categories(db: Session) -> None:
    """Insert or update the category list (safe to run on every start)."""
    for slug, en, hi, kn, icon, order in CATEGORIES:
        cat = db.get(Category, slug)
        if cat is None:
            db.add(Category(slug=slug, name_en=en, name_hi=hi, name_kn=kn, icon=icon, sort_order=order))
        else:
            cat.name_en, cat.name_hi, cat.name_kn, cat.icon, cat.sort_order = en, hi, kn, icon, order
    db.flush()


def seed_reference_data(db: Session) -> None:
    from .services.discovery import seed_localities

    seed_categories(db)
    seed_localities(db)


def seed_catalog(db: Session) -> int:
    seed_reference_data(db)
    if db.scalar(select(func.count()).select_from(ProductVariant)):
        return 0
    created = 0
    for brand, name, category, sizes, sell_unit, aliases in CATALOG:
        product = Product(brand=brand, name=name, category_slug=category)
        db.add(product)
        db.flush()
        for size_label, _price in sizes:
            size_value, size_unit = parse_size(size_label)
            show_brand = brand and brand.lower().split()[0] not in name.lower()
            display = " ".join(filter(None, [brand if show_brand else None, name, size_label]))
            db.add(ProductVariant(
                product_id=product.id, display_name=display, size_label=size_label,
                size_value=size_value, size_unit=size_unit, sell_unit=sell_unit,
                is_loose=sell_unit in {"kg", "g", "l", "ml", "m"}, aliases=aliases,
                search_text=build_search_text(brand, name, size_label, category, display, aliases=aliases),
                status="verified",
            ))
            created += 1
    db.flush()
    return created


def _reference_prices() -> dict[str, int]:
    prices = {}
    for brand, name, _cat, sizes, _unit, _aliases in CATALOG:
        for size_label, price in sizes:
            show_brand = brand and brand.lower().split()[0] not in name.lower()
            display = " ".join(filter(None, [brand if show_brand else None, name, size_label]))
            prices[display] = price
    return prices


def _make_shop(db: Session, phone: str, name: str, locality: str, lat: float, lng: float,
               categories: list[str], lang: str, status: str) -> Shop:
    owner = User(phone=phone, name=f"{name.split()[0]} (owner)", role="shopkeeper", preferred_language=lang)
    db.add(owner)
    db.flush()
    shop = Shop(
        owner_user_id=owner.id, name=name, locality=locality, city="Bengaluru", pincode="560041",
        address_line=f"{locality}, Bengaluru", lat=lat, lng=lng, categories=categories,
        preferred_language=lang, status=status, location_verified=status == "active",
        verified_at=utcnow() if status == "active" else None, is_demo=True,
        description="Sample shop for local development. Not a real business.",
    )
    db.add(shop)
    for day in range(7):
        shop.hours.append(ShopHours(weekday=day, opens="08:00", closes="21:30", is_closed=False))
    db.flush()
    return shop


def seed_demo(db: Session) -> None:
    if db.scalar(select(func.count()).select_from(Shop).where(Shop.is_demo.is_(True))):
        return
    if not db.execute(select(User).where(User.phone == DEMO_ADMIN)).scalar_one_or_none():
        db.add(User(phone=DEMO_ADMIN, name="Platform Admin", role="admin"))
    if not db.execute(select(User).where(User.phone == DEMO_CUSTOMER)).scalar_one_or_none():
        db.add(User(phone=DEMO_CUSTOMER, name="Demo Customer", role="customer"))
    prices = _reference_prices()
    variants = db.execute(select(ProductVariant).join(Product)).scalars().all()
    now = utcnow()
    for spec in DEMO_SHOPS + [PENDING_SHOP]:
        status = "pending" if spec is PENDING_SHOP else "active"
        shop = _make_shop(db, *spec, status=status)
        rng = random.Random(shop.id * 7919)
        for v in variants:
            if v.product.category_slug not in shop.categories or rng.random() < 0.2:
                continue
            ref = prices.get(v.display_name, 50)
            price = int(round(ref * rng.uniform(0.95, 1.04))) * 100
            listing = ShopListing(shop_id=shop.id, variant_id=v.id, price_paise=price,
                                  low_stock_threshold_milli=3000 if v.sell_unit == "pc" else 2000)
            db.add(listing)
            db.flush()
            roll = rng.random()
            qty = 0 if roll < 0.1 else rng.randint(2, 40)
            if v.sell_unit != "pc":
                qty = 0 if roll < 0.1 else rng.randint(5, 60)
            age = rng.choice([1, 2, 3, 5, 8, 12, 20, 30, 50, 100])  # hours since last confirmed
            confirmed = now - timedelta(hours=age)
            db.add(InventoryBalance(listing_id=listing.id, shop_id=shop.id, variant_id=v.id,
                                    on_hand_milli=qty * 1000, reserved_milli=0, version=1,
                                    updated_at=confirmed, stock_confirmed_at=confirmed))
            db.add(InventoryEvent(shop_id=shop.id, listing_id=listing.id, variant_id=v.id,
                                  event_type="initial_stock", source="manual", delta_on_hand_milli=qty * 1000,
                                  delta_reserved_milli=0, on_hand_after_milli=qty * 1000, reserved_after_milli=0,
                                  actor_user_id=shop.owner_user_id, verification="shopkeeper_confirmed",
                                  note="Demo seed", created_at=confirmed))
    db.flush()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", action="store_true", help="seed the catalogue only")
    args = parser.parse_args()
    settings = load_settings()
    database = Database(settings.database_url)
    if database.is_sqlite:
        from pathlib import Path

        Path(settings.database_url.replace("sqlite:///", "")).parent.mkdir(parents=True, exist_ok=True)
        database.create_all()
    with database.session() as db:
        n = seed_catalog(db)
        print(f"catalogue: {n} variants created")
        if not args.catalog:
            if settings.is_production:
                raise SystemExit("Refusing to create demo shops in production")
            seed_demo(db)
            print("demo shops, admin (+91 90000 00001) and customer (+91 90000 00100) ready")


if __name__ == "__main__":
    main()
