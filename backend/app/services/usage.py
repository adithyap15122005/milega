"""Daily usage counters for paid third-party providers.

Each billable call first *consumes* one unit of today's budget in its own short
transaction, so the count is correct across API replicas (it lives in the shared
database) and survives restarts. When the budget is spent, callers degrade
gracefully instead of running up an unexpected bill.
"""

from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError

from ..db import Database
from ..models import ProviderUsage
from .geo import IST


def today_ist() -> str:
    return datetime.now(timezone.utc).astimezone(IST).strftime("%Y-%m-%d")


class UsageMeter:
    def __init__(self, database: Database):
        self.database = database

    def consume(self, provider: str, operation: str, *, daily_limit: int | None) -> bool:
        """Count one call. Returns False (and does not count) when the provider's daily budget is spent."""
        day = today_ist()
        with self.database.session() as db:
            if daily_limit is not None:
                used = db.scalar(
                    select(func.coalesce(func.sum(ProviderUsage.count), 0))
                    .where(ProviderUsage.day == day, ProviderUsage.provider == provider)
                )
                if used >= daily_limit:
                    return False
            self._increment(db, day, provider, operation, "count")
        return True

    def record_error(self, provider: str, operation: str) -> None:
        with self.database.session() as db:
            self._increment(db, today_ist(), provider, operation, "errors")

    @staticmethod
    def _increment(db, day: str, provider: str, operation: str, column: str) -> None:
        col = getattr(ProviderUsage, column)
        where = (ProviderUsage.day == day, ProviderUsage.provider == provider, ProviderUsage.operation == operation)
        if db.execute(update(ProviderUsage).where(*where).values({column: col + 1})).rowcount:
            return
        try:
            with db.begin_nested():
                db.add(ProviderUsage(day=day, provider=provider, operation=operation,
                                     count=1 if column == "count" else 0, errors=1 if column == "errors" else 0))
        except IntegrityError:  # another replica inserted the row first
            db.execute(update(ProviderUsage).where(*where).values({column: col + 1}))

    def today(self, db=None) -> list[dict]:
        if db is not None:
            return self._today(db)
        with self.database.session() as session:
            return self._today(session)

    @staticmethod
    def _today(db) -> list[dict]:
        rows = db.execute(select(ProviderUsage).where(ProviderUsage.day == today_ist())).scalars().all()
        return [{"provider": r.provider, "operation": r.operation, "count": r.count, "errors": r.errors}
                for r in rows]
