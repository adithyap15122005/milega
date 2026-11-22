"""Database engine / session management.

PostgreSQL is the production database. SQLite is supported for zero-setup local
development; on SQLite every transaction starts with ``BEGIN IMMEDIATE`` so
concurrent writers are serialised instead of failing with "database is locked"
mid-transaction. Inventory correctness never relies on that serialisation: all
stock changes use conditional ``UPDATE ... WHERE`` statements that are also
safe under PostgreSQL READ COMMITTED.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from datetime import datetime, timezone

from sqlalchemy import BigInteger, DateTime, Integer, create_engine, event
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from sqlalchemy.types import TypeDecorator

# Integer primary keys: BIGINT on PostgreSQL, INTEGER (rowid alias) on SQLite.
BigIntPK = BigInteger().with_variant(Integer(), "sqlite")


class UTCDateTime(TypeDecorator):
    """Timezone-aware UTC datetimes on every backend (SQLite drops tzinfo)."""

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("Naive datetimes are not allowed; use timezone-aware UTC")
        value = value.astimezone(timezone.utc)
        if dialect.name == "sqlite":
            return value.replace(tzinfo=None)
        return value

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)


def release_sqlite_lock(db: Session) -> None:
    """End the current transaction before a slow external call, on SQLite only.

    SQLite (with BEGIN IMMEDIATE) holds a database-wide write lock for the whole
    transaction, which would block other writers such as the provider usage meter
    while we wait on Google. PostgreSQL locks rows, so this is a no-op there.
    """
    if db.get_bind().dialect.name == "sqlite" and db.in_transaction():
        db.commit()


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    pass


class Database:
    def __init__(self, url: str):
        self.url = url
        self.is_sqlite = url.startswith("sqlite")
        if self.is_sqlite:
            self.engine = create_engine(
                url, connect_args={"check_same_thread": False, "timeout": 30}
            )

            @event.listens_for(self.engine, "connect")
            def _sqlite_connect(dbapi_conn, _record):  # pragma: no cover - driver hook
                # Let SQLAlchemy (not pysqlite) control transactions.
                dbapi_conn.isolation_level = None
                cur = dbapi_conn.cursor()
                cur.execute("PRAGMA journal_mode=WAL")
                cur.execute("PRAGMA foreign_keys=ON")
                cur.execute("PRAGMA busy_timeout=30000")
                cur.close()

            @event.listens_for(self.engine, "begin")
            def _sqlite_begin(conn):  # pragma: no cover - driver hook
                conn.exec_driver_sql("BEGIN IMMEDIATE")

        else:
            self.engine = create_engine(url, pool_pre_ping=True, pool_size=10, max_overflow=20)
        self.SessionLocal = sessionmaker(bind=self.engine, expire_on_commit=False)

    def create_all(self) -> None:
        from . import models  # noqa: F401  (register tables)

        Base.metadata.create_all(self.engine)

    @contextmanager
    def session(self) -> Iterator[Session]:
        session = self.SessionLocal()
        try:
            yield session
            session.commit()
        except Exception:
            session.rollback()
            raise
        finally:
            session.close()
