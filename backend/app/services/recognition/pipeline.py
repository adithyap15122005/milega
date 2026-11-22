"""Photo -> suggestions -> human confirmation -> stock.

Stage 1 (``create_job``)   validate + compress the upload, store it, record a job.
Stage 2 (``process_job``)  run the provider, match each detection to the catalogue,
                           store candidates with confidence. *No stock changes.*
Stage 3 (``confirm_job``)  the shopkeeper's reviewed list is applied to the
                           inventory ledger, exactly once.
"""

from __future__ import annotations

import logging
import uuid

from pydantic import BaseModel, Field
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from ...config import Settings
from ...db import Database, utcnow
from ...errors import AppError, bad_request, conflict, not_found
from ...models import (
    InventoryBalance,
    ProductPhoto,
    ProductVariant,
    RecognitionCandidate,
    RecognitionJob,
    ShopListing,
    User,
)
from ..activity import audit
from ..catalog import confidence_level, create_variant, match_variants, variant_view
from ..inventory import apply_change, create_listing, set_on_hand
from ..storage import assess_quality, sanitize_image
from ..units import from_milli, to_milli, unit_label
from .providers import VisionProvider
from .types import OcrLine, ProviderError, ProviderResult

log = logging.getLogger("milega.recognition")

MODES = ("incoming", "shelf", "invoice")
MAX_OCR_LINES = 400


def create_job(
    db: Session,
    settings: Settings,
    storage,
    *,
    shop_id: int,
    user: User,
    mode: str,
    image_bytes: bytes,
    ocr_lines: list[OcrLine],
    client_request_id: str | None,
    provider_name: str,
) -> tuple[RecognitionJob, bytes | None]:
    if mode not in MODES:
        raise bad_request("invalid_mode", "Unknown scan mode")
    if client_request_id:
        existing = db.execute(
            select(RecognitionJob).where(
                RecognitionJob.user_id == user.id, RecognitionJob.client_request_id == client_request_id
            )
        ).scalar_one_or_none()
        if existing:
            return existing, None  # upload retry: don't process twice
    jpeg, img = sanitize_image(image_bytes, settings.max_upload_bytes)
    quality = assess_quality(img)
    job_id = uuid.uuid4().hex
    key = f"recognition/{shop_id}/{job_id}.jpg"
    storage.put(key, jpeg)
    job = RecognitionJob(
        id=job_id, shop_id=shop_id, user_id=user.id, mode=mode, status="queued", provider=provider_name,
        image_key=key, ocr_lines=[l.model_dump() for l in ocr_lines[:MAX_OCR_LINES]], image_quality=quality,
        client_request_id=client_request_id,
    )
    db.add(job)
    db.flush()
    return job, jpeg


def process_job(database: Database, provider: VisionProvider, job_id: str, jpeg: bytes) -> None:
    """Runs after the upload request returns (FastAPI background task / worker)."""
    with database.session() as db:
        job = db.get(RecognitionJob, job_id)
        if job is None or job.status != "queued":
            return
        job.status = "processing"
        ocr_lines = [OcrLine.model_validate(l) for l in (job.ocr_lines or [])]
        mode = job.mode
    try:
        result = provider.analyze(image_jpeg=jpeg, mode=mode, ocr_lines=ocr_lines)
        error = None
    except ProviderError as exc:
        result, error = None, exc
    except Exception:  # never leave a job stuck in "processing"
        log.exception("recognition provider crashed job=%s", job_id)
        result, error = None, ProviderError("provider_error", "Recognition failed. Please try again.", True)

    with database.session() as db:
        job = db.get(RecognitionJob, job_id)
        if error is not None:
            job.status = "failed"
            job.error_code = error.code
            job.error_message = error.message
            return
        build_candidates(db, job, result)


def _fail(job: RecognitionJob, code: str, message: str) -> None:
    job.status = "failed"
    job.error_code = code
    job.error_message = message


def build_candidates(db: Session, job: RecognitionJob, result: ProviderResult) -> None:
    job.raw_result = result.model_dump()
    quality = job.image_quality or {}
    quality_issues = list(dict.fromkeys([*quality.get("issues", []), *result.image_issues]))
    shop_listings = {
        l.variant_id: l for l in db.execute(select(ShopListing).where(ShopListing.shop_id == job.shop_id)).scalars()
    }

    rows: list[dict] = []
    if job.mode == "invoice":
        for line in result.invoice_lines:
            matches = match_variants(db, line.description, limit=4, min_score=0.45)
            rows.append({
                "label": line.description, "brand": None, "size_text": None, "detected_text": line.description,
                "visible_count": None, "count_reliable": False, "provider_confidence": line.confidence,
                "matches": matches, "invoice_quantity": line.quantity, "invoice_unit": line.unit,
                "invoice_unit_price_paise": int(round(line.unit_price * 100)) if line.unit_price else None,
            })
    else:
        for item in result.items:
            text = " ".join(filter(None, [item.product_name, item.visible_text, item.label]))
            matches = match_variants(db, text, brand=item.brand, size_text=item.size_text, limit=4, min_score=0.45)
            rows.append({
                "label": " ".join(filter(None, [item.brand, item.product_name, item.size_text])) or item.label,
                "brand": item.brand, "size_text": item.size_text, "detected_text": item.visible_text,
                "visible_count": item.visible_count, "count_reliable": item.count_reliable,
                "provider_confidence": item.identity_confidence, "matches": matches,
                "invoice_quantity": None, "invoice_unit": None, "invoice_unit_price_paise": None,
            })
        if result.provider == "on_device_ocr":
            # Text clusters that match nothing are usually "Best before", MRP lines etc.
            matched = [r for r in rows if r["matches"]]
            if not matched and rows:
                biggest = rows[0]
                biggest["label"] = biggest["label"][:80]
                matched = [biggest]
            rows = matched

    # Merge duplicate detections of the same product: keep one row, and drop the
    # count (we can't tell whether they were the same units seen twice).
    merged: list[dict] = []
    by_variant: dict[int, dict] = {}
    for row in rows:
        top = row["matches"][0][0].id if row["matches"] else None
        if top is not None and top in by_variant and job.mode != "invoice":
            keep = by_variant[top]
            keep["visible_count"] = None
            keep["count_reliable"] = False
            keep["duplicate"] = True
            continue
        if top is not None:
            by_variant[top] = row
        merged.append(row)

    if not merged:
        if quality_issues:
            _fail(job, "photo_unclear", "The photo is not clear enough. Hold the phone steady in good light and retake.")
        elif job.mode == "invoice":
            _fail(job, "no_invoice_lines", "No item lines could be read from this bill. Retake it flat and well lit, or add items manually.")
        else:
            _fail(job, "nothing_recognised", "No products could be identified. Move closer to the labels or add the items manually.")
        return

    for position, row in enumerate(merged):
        matches = row["matches"]
        top_score = matches[0][1] if matches else 0.0
        second = matches[1][1] if len(matches) > 1 else None
        level = confidence_level(top_score, second, provider_confidence=row["provider_confidence"]) if matches else "low"
        db.add(RecognitionCandidate(
            job_id=job.id,
            position=position,
            label=row["label"][:200],
            brand=row["brand"],
            size_text=row["size_text"],
            detected_text=row["detected_text"],
            visible_count=row["visible_count"],
            count_reliable=bool(row["count_reliable"] and row["visible_count"]),
            provider_confidence=row["provider_confidence"],
            match_confidence=top_score,
            confidence_level=level,
            matches=[
                {
                    **variant_view(v),
                    "score": s,
                    "listing_id": shop_listings[v.id].id if v.id in shop_listings else None,
                }
                for v, s in matches
            ],
            # Only pre-select when we are genuinely confident.
            suggested_variant_id=matches[0][0].id if matches and level == "high" else None,
            invoice_quantity=row["invoice_quantity"],
            invoice_unit=row["invoice_unit"],
            invoice_unit_price_paise=row["invoice_unit_price_paise"],
        ))
    job.status = "needs_review"
    job.error_code = "photo_quality_warning" if quality_issues else None
    job.error_message = "Photo looks unclear — please check each item carefully." if quality_issues else None


# ------------------------------------------------------------------- views


def job_view(db: Session, job: RecognitionJob) -> dict:
    listings = {
        l.variant_id: l for l in db.execute(select(ShopListing).where(ShopListing.shop_id == job.shop_id)).scalars()
    }
    candidates = []
    for c in job.candidates:
        matches = []
        for m in c.matches or []:
            listing = listings.get(m["id"])
            bal = db.get(InventoryBalance, listing.id) if listing else None
            matches.append({
                **m,
                "listing_id": listing.id if listing else None,
                "current_on_hand": from_milli(bal.on_hand_milli) if bal else None,
                "current_price_paise": listing.price_paise if listing else None,
                "unit_label": unit_label(m.get("sell_unit", "pc")),
            })
        if job.mode == "incoming":
            suggested_qty = c.visible_count if c.count_reliable else None
        elif job.mode == "invoice":
            suggested_qty = c.invoice_quantity
        else:
            suggested_qty = c.visible_count
        candidates.append({
            "id": c.id,
            "label": c.label,
            "brand": c.brand,
            "size_text": c.size_text,
            "detected_text": c.detected_text,
            "confidence": c.confidence_level,
            "match_confidence": round(c.match_confidence, 2),
            "provider_confidence": round(c.provider_confidence, 2),
            "visible_count": c.visible_count,
            "count_reliable": c.count_reliable,
            "suggested_quantity": suggested_qty,
            "quantity_source": (
                "ai_count" if job.mode == "incoming" and suggested_qty is not None else
                "invoice" if job.mode == "invoice" and suggested_qty is not None else
                "shelf_observation" if job.mode == "shelf" and suggested_qty is not None else None
            ),
            "invoice_unit_price_paise": c.invoice_unit_price_paise,
            "suggested_variant_id": c.suggested_variant_id,
            "matches": matches,
            "status": c.status,
        })
    return {
        "id": job.id,
        "mode": job.mode,
        "status": job.status,
        "provider": job.provider,
        "error_code": job.error_code,
        "error_message": job.error_message,
        "image_quality": job.image_quality,
        "supplier_name": (job.raw_result or {}).get("supplier_name"),
        "created_at": job.created_at.isoformat(),
        "candidates": candidates,
    }


# ------------------------------------------------------------------ confirm


class NewProductIn(BaseModel):
    name: str = Field(min_length=2, max_length=160)
    brand: str | None = Field(default=None, max_length=80)
    size_label: str | None = Field(default=None, max_length=40)
    category: str = "other"
    sell_unit: str = "pc"


class ConfirmItemIn(BaseModel):
    candidate_id: int | None = None
    variant_id: int | None = None
    new_product: NewProductIn | None = None
    quantity: float = Field(ge=0)
    price_paise: int | None = Field(default=None, ge=0, le=10_000_000)


def _resolve_variant(db: Session, item: ConfirmItemIn, user: User, shop_id: int) -> ProductVariant:
    if item.variant_id is not None:
        variant = db.get(ProductVariant, item.variant_id)
        hops = 0
        while variant is not None and variant.status == "merged" and variant.merged_into_id and hops < 5:
            variant = db.get(ProductVariant, variant.merged_into_id)
            hops += 1
        if variant is None or variant.status == "merged":
            raise bad_request("invalid_variant", "Choose a product from the list")
        return variant
    if item.new_product is not None:
        np = item.new_product
        return create_variant(
            db, name=np.name, brand=np.brand, category=np.category, size_label=np.size_label,
            sell_unit=np.sell_unit, created_by=user.id, shop_id=shop_id,
        )
    raise bad_request("product_required", "Choose which product this is")


def confirm_job(db: Session, job: RecognitionJob, user: User, items: list[ConfirmItemIn]) -> dict:
    if not items:
        raise bad_request("nothing_to_save", "Select at least one item to save")
    if len(items) > 60:
        raise bad_request("too_many_items", "Save at most 60 items at once")
    # Claim the job exactly once (double taps / retries can't double-add stock).
    claimed = db.execute(
        update(RecognitionJob)
        .where(RecognitionJob.id == job.id, RecognitionJob.status == "needs_review")
        .values(status="confirmed", confirmed_at=utcnow(), updated_at=utcnow())
        .execution_options(synchronize_session=False)
    )
    if claimed.rowcount != 1:
        db.refresh(job)
        raise conflict("job_not_reviewable", f"This scan is already {job.status}", status=job.status)

    candidates = {c.id: c for c in job.candidates}
    source = {"incoming": "photo_scan", "shelf": "shelf_scan", "invoice": "invoice_scan"}[job.mode]
    applied = []
    for item in items:
        candidate = candidates.get(item.candidate_id) if item.candidate_id is not None else None
        if item.candidate_id is not None and candidate is None:
            raise bad_request("invalid_candidate", "Item does not belong to this scan")
        variant = _resolve_variant(db, item, user, job.shop_id)
        qty_milli = to_milli(item.quantity, variant.sell_unit)
        if job.mode != "shelf" and qty_milli <= 0:
            raise bad_request("quantity_required", f"Enter how many {variant.display_name} you received")
        listing, _created = create_listing(
            db, shop_id=job.shop_id, variant=variant, price_paise=item.price_paise, initial_quantity_milli=0,
            actor_id=user.id, source=source, reference=("recognition_job", job.id),
        )
        if item.price_paise is not None:
            listing.price_paise = item.price_paise
        if job.mode == "shelf":
            result = set_on_hand(
                db, listing, qty_milli, event_type="reconciliation", source=source, actor_id=user.id,
                reference=("recognition_job", job.id), note="Shelf scan recount",
            )
        else:
            result = apply_change(
                db, listing, event_type="invoice_import" if job.mode == "invoice" else "restock",
                source=source, delta_on_hand=qty_milli, actor_id=user.id,
                verification="shopkeeper_confirmed", reference=("recognition_job", job.id),
            )
        if candidate is not None:
            candidate.status = "applied"
            candidate.applied_variant_id = variant.id
            candidate.applied_quantity_milli = qty_milli
        if job.mode == "incoming" and job.image_key:
            db.add(ProductPhoto(variant_id=variant.id, shop_id=job.shop_id, storage_key=job.image_key,
                                source="recognition", uploaded_by=user.id))
        applied.append({
            "listing_id": listing.id,
            "name": listing.custom_name or variant.display_name,
            "quantity": from_milli(qty_milli),
            "on_hand": from_milli(result.on_hand_milli),
            "unit_label": unit_label(variant.sell_unit),
            "provisional": variant.status == "provisional",
        })
    for c in job.candidates:
        if c.status == "pending":
            c.status = "skipped"
    audit(db, actor_id=user.id, action="recognition.confirm", entity_type="recognition_job", entity_id=job.id,
          data={"items": len(applied), "mode": job.mode})
    return {"job_id": job.id, "applied": applied}


def get_job_for_shop(db: Session, job_id: str, shop_id: int) -> RecognitionJob:
    job = db.get(RecognitionJob, job_id)
    if job is None or job.shop_id != shop_id:
        raise not_found("Scan")
    return job


def discard_job(db: Session, job: RecognitionJob) -> None:
    result = db.execute(
        update(RecognitionJob)
        .where(RecognitionJob.id == job.id, RecognitionJob.status.in_(("needs_review", "failed", "queued")))
        .values(status="discarded", updated_at=utcnow())
        .execution_options(synchronize_session=False)
    )
    if result.rowcount != 1:
        raise AppError(409, "job_not_reviewable", "This scan can no longer be discarded")
