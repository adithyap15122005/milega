from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..errors import not_found
from ..models import Shop, User
from ..security import current_user, get_db

router = APIRouter(tags=["media"])


@router.get("/media/{key:path}")
def get_media(key: str, request: Request, user: User = Depends(current_user), db: Session = Depends(get_db)):
    """Recognition photos are private: only the owning shop and admins may read them."""
    parts = key.split("/")
    if parts[0] == "recognition":
        shop = db.execute(select(Shop).where(Shop.owner_user_id == user.id)).scalar_one_or_none()
        allowed = user.role == "admin" or (shop is not None and len(parts) > 1 and parts[1] == str(shop.id))
        if not allowed:
            raise not_found("File")
    data = request.app.state.storage.get(key)
    if data is None:
        raise not_found("File")
    return Response(content=data, media_type="image/jpeg", headers={"Cache-Control": "private, max-age=3600"})
