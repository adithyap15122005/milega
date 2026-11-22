from __future__ import annotations

import os
import sys
from pathlib import Path

from alembic import context
from sqlalchemy import engine_from_config, pool

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import load_settings  # noqa: E402
from app.db import Base, UTCDateTime  # noqa: E402
from app import models  # noqa: E402,F401

config = context.config
if os.environ.get("DATABASE_URL") or not config.get_main_option("sqlalchemy.url"):
    config.set_main_option("sqlalchemy.url", load_settings().database_url)
target_metadata = Base.metadata


def render_item(type_, obj, autogen_context):
    # Store our tz-aware UTC wrapper as a plain TIMESTAMP WITH TIME ZONE.
    if type_ == "type" and isinstance(obj, UTCDateTime):
        return "sa.DateTime(timezone=True)"
    return False


# PostgreSQL-only objects created by raw SQL in migrations (PostGIS / pg_trgm).
_UNMANAGED = {"geog", "ix_shops_geog", "ix_variants_search_trgm", "ix_shops_name_trgm", "ix_localities_search_trgm"}


def include_object(obj, name, type_, reflected, compare_to):
    return name not in _UNMANAGED


def run_migrations_offline() -> None:
    context.configure(url=config.get_main_option("sqlalchemy.url"), target_metadata=target_metadata,
                      literal_binds=True, render_item=render_item, render_as_batch=True,
                      include_object=include_object)
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(config.get_section(config.config_ini_section), prefix="sqlalchemy.",
                                     poolclass=pool.NullPool)
    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata, render_item=render_item,
                          render_as_batch=connection.dialect.name == "sqlite", include_object=include_object)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
