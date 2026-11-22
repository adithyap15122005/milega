"""Audit log and in-app notifications.

Notifications are stored rows that the apps poll. A push channel (FCM / SMS /
WhatsApp Business) can be added in ``deliver()`` without changing callers.
"""

from __future__ import annotations

import logging
from typing import Any

from sqlalchemy.orm import Session

from ..models import AuditLog, Notification

log = logging.getLogger("milega.activity")


def audit(
    db: Session,
    *,
    actor_id: int | None,
    action: str,
    entity_type: str,
    entity_id: Any,
    data: dict[str, Any] | None = None,
    ip: str | None = None,
) -> None:
    db.add(
        AuditLog(
            actor_user_id=actor_id,
            action=action,
            entity_type=entity_type,
            entity_id=str(entity_id),
            data=data or {},
            ip=ip,
        )
    )


def notify(db: Session, *, user_id: int, kind: str, title: str, body: str, data: dict | None = None) -> None:
    db.add(Notification(user_id=user_id, kind=kind, title=title, body=body, data=data or {}))
    deliver(user_id=user_id, kind=kind, title=title)


def deliver(*, user_id: int, kind: str, title: str) -> None:
    """Hook for real push delivery. Today the apps poll /notifications."""
    log.info("notification queued", extra={"user_id": user_id, "kind": kind, "title": title})
