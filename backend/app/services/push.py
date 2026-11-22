"""Push notifications through Firebase Cloud Messaging (HTTP v1).

Notifications are written to the ``notifications`` table first (the in-app list and
the app's polling fallback read from it). A dispatcher then delivers rows with
``push_status='pending'`` to every active device of the user:

* exactly one attempt sequence per notification (the row is claimed with a
  conditional UPDATE, so several API replicas never double-send);
* tokens that FCM reports as unregistered are disabled;
* the app de-duplicates by ``notification_id`` in the data payload, so a push and a
  poll of the same notification show only once.
"""

from __future__ import annotations

import json
import logging
import threading
import time
from dataclasses import dataclass, field

import httpx
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from ..config import Settings
from ..db import Database, utcnow
from ..models import Notification, NotificationDevice

log = logging.getLogger("milega.push")

MAX_ATTEMPTS = 3


class PushSender:
    configured = False

    def send(self, token: str, *, title: str, body: str, data: dict[str, str]) -> str:
        """Returns 'sent', 'unregistered' or 'failed'."""
        raise NotImplementedError


class NullSender(PushSender):
    configured = False

    def send(self, token: str, *, title: str, body: str, data: dict[str, str]) -> str:  # pragma: no cover
        return "failed"


@dataclass
class FcmSender(PushSender):
    project_id: str
    credentials_file: str
    transport: httpx.BaseTransport | None = None
    configured = True
    _token: str | None = field(default=None, init=False)
    _token_expiry: float = field(default=0.0, init=False)
    _lock: threading.Lock = field(default_factory=threading.Lock, init=False)

    def _access_token(self) -> str:
        with self._lock:
            if self._token and time.time() < self._token_expiry - 60:
                return self._token
            from google.auth.transport.requests import Request as GoogleRequest
            from google.oauth2 import service_account

            creds = service_account.Credentials.from_service_account_file(
                self.credentials_file, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
            creds.refresh(GoogleRequest())
            self._token = creds.token
            self._token_expiry = creds.expiry.timestamp() if creds.expiry else time.time() + 3000
            return self._token

    def send(self, token: str, *, title: str, body: str, data: dict[str, str]) -> str:
        message = {"message": {
            "token": token,
            "notification": {"title": title, "body": body},
            "data": data,
            "android": {"priority": "HIGH", "collapse_key": data.get("notification_id", ""),
                        "notification": {"channel_id": "reservations"}},
        }}
        try:
            with httpx.Client(timeout=10, transport=self.transport) as http:
                resp = http.post(f"https://fcm.googleapis.com/v1/projects/{self.project_id}/messages:send",
                                 json=message, headers={"Authorization": f"Bearer {self._access_token()}"})
        except httpx.HTTPError:
            return "failed"
        if resp.status_code < 300:
            return "sent"
        try:
            details = json.dumps(resp.json())
        except ValueError:
            details = ""
        if resp.status_code == 404 or "UNREGISTERED" in details or (resp.status_code == 400 and "INVALID_ARGUMENT" in details and "token" in details.lower()):
            return "unregistered"
        log.warning("fcm send failed: http=%s", resp.status_code)
        return "failed"


def make_sender(settings: Settings) -> PushSender:
    if settings.push_configured:
        return FcmSender(settings.fcm_project_id, settings.fcm_credentials_file)
    return NullSender()


def dispatch_pending(database: Database, sender: PushSender, *, batch: int = 50) -> dict:
    """Deliver pending notifications. Safe to run concurrently on several replicas."""
    stats = {"sent": 0, "skipped": 0, "failed": 0}
    with database.session() as db:
        ids = list(db.execute(
            select(Notification.id).where(Notification.push_status == "pending").order_by(Notification.id).limit(batch)
        ).scalars())
    for notification_id in ids:
        with database.session() as db:
            outcome = _deliver_one(db, sender, notification_id)
        if outcome:
            stats[outcome] += 1
    return stats


def _deliver_one(db: Session, sender: PushSender, notification_id: int) -> str | None:
    # Claim the row: only one dispatcher moves it out of 'pending'.
    claimed = db.execute(
        update(Notification).where(Notification.id == notification_id, Notification.push_status == "pending")
        .values(push_status="sending", push_attempts=Notification.push_attempts + 1)
    ).rowcount
    if not claimed:
        return None
    n = db.get(Notification, notification_id)
    if not sender.configured:
        n.push_status = "skipped"
        return "skipped"
    devices = db.execute(select(NotificationDevice).where(
        NotificationDevice.user_id == n.user_id, NotificationDevice.disabled_at.is_(None))).scalars().all()
    if not devices:
        n.push_status = "skipped"
        return "skipped"
    data = {"notification_id": str(n.id), "kind": n.kind,
            **{k: str(v) for k, v in (n.data or {}).items() if v is not None}}
    delivered = False
    for device in devices:
        result = sender.send(device.token, title=n.title, body=n.body, data=data)
        if result == "sent":
            delivered = True
            device.failure_count = 0
        elif result == "unregistered":
            device.disabled_at = utcnow()
        else:
            device.failure_count += 1
            if device.failure_count >= 10:
                device.disabled_at = utcnow()
    if delivered:
        n.push_status, n.pushed_at = "sent", utcnow()
        return "sent"
    n.push_status = "pending" if n.push_attempts < MAX_ATTEMPTS else "failed"
    return "failed"
