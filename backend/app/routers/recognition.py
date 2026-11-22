from __future__ import annotations

import json

from fastapi import APIRouter, BackgroundTasks, Depends, File, Form, Request, UploadFile
from pydantic import BaseModel, Field, ValidationError
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings
from ..errors import bad_request
from ..models import RecognitionJob
from ..ratelimit import rate_limit
from ..security import ShopContext, get_db, get_settings, require_shop
from ..services.recognition import OcrLine, provider_status
from ..services.recognition.pipeline import (
    ConfirmItemIn,
    confirm_job,
    create_job,
    discard_job,
    get_job_for_shop,
    job_view,
    process_job,
)

router = APIRouter(prefix="/api/v1/recognition", tags=["recognition"])


class ConfirmIn(BaseModel):
    items: list[ConfirmItemIn] = Field(max_length=60)


@router.get("/provider")
def get_provider(settings: Settings = Depends(get_settings)):
    return provider_status(settings)


@router.post("/jobs", status_code=202, dependencies=[rate_limit("recognition", 60, 3600)])
def upload_scan(
    request: Request,
    background: BackgroundTasks,
    image: UploadFile = File(...),
    mode: str = Form("incoming"),
    ocr_lines: str = Form("[]"),
    client_request_id: str | None = Form(default=None, max_length=64),
    ctx: ShopContext = Depends(require_shop),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    data = image.file.read(settings.max_upload_bytes + 1)  # sync endpoint: runs in a worker thread
    try:
        raw_lines = json.loads(ocr_lines or "[]")
        if not isinstance(raw_lines, list):
            raise ValueError
        lines = [OcrLine.model_validate(l) for l in raw_lines[:400] if isinstance(l, dict) and l.get("text")]
    except (ValueError, ValidationError):
        raise bad_request("invalid_ocr", "Could not read the text data sent with the photo")
    provider = request.app.state.provider_factory(settings, has_ocr=bool(lines))
    job, jpeg = create_job(
        db, settings, request.app.state.storage, shop_id=ctx.shop.id, user=ctx.user, mode=mode,
        image_bytes=data, ocr_lines=lines, client_request_id=client_request_id, provider_name=provider.name,
    )
    view = job_view(db, job)
    # Commit (and leave no transaction open) before the background worker starts,
    # so it never waits on this request's database lock.
    db.commit()
    if jpeg is not None:
        background.add_task(process_job, request.app.state.db, provider, job.id, jpeg)
    return view


@router.get("/jobs")
def recent_jobs(ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    jobs = db.execute(
        select(RecognitionJob).where(RecognitionJob.shop_id == ctx.shop.id)
        .order_by(RecognitionJob.created_at.desc()).limit(20)
    ).scalars().all()
    return {"jobs": [{"id": j.id, "mode": j.mode, "status": j.status, "created_at": j.created_at.isoformat(),
                      "items": len(j.candidates)} for j in jobs]}


@router.get("/jobs/{job_id}")
def get_scan(job_id: str, ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    job = get_job_for_shop(db, job_id, ctx.shop.id)
    db.refresh(job)
    return job_view(db, job)


@router.post("/jobs/{job_id}/confirm")
def confirm_scan(job_id: str, body: ConfirmIn, ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    job = get_job_for_shop(db, job_id, ctx.shop.id)
    return confirm_job(db, job, ctx.user, body.items)


@router.post("/jobs/{job_id}/discard")
def discard_scan(job_id: str, ctx: ShopContext = Depends(require_shop), db: Session = Depends(get_db)):
    discard_job(db, get_job_for_shop(db, job_id, ctx.shop.id))
    return {"ok": True}
