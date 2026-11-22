"""Bengaluru discovery, saved places, reports, push devices, search analytics, provider budgets.

PostgreSQL extras (skipped elsewhere, and skipped with a notice if the extension
can't be created by this database user):
* PostGIS: generated ``shops.geog`` geography column + GIST index for radius queries.
* pg_trgm: trigram GIN indexes for fuzzy product / shop / locality lookups.

Revision ID: 0002
Revises: 0001
Create Date: 2026-11-22
"""
from alembic import op
import sqlalchemy as sa

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None

BIG_ID = sa.BigInteger().with_variant(sa.Integer(), "sqlite")
TS = sa.DateTime(timezone=True)


def _pg() -> bool:
    return op.get_bind().dialect.name == "postgresql"


def _try_extension(name: str) -> bool:
    """CREATE EXTENSION inside a savepoint so a missing privilege doesn't abort the migration."""
    bind = op.get_bind()
    try:
        with bind.begin_nested():
            bind.exec_driver_sql(f"CREATE EXTENSION IF NOT EXISTS {name}")
        return True
    except Exception as exc:  # pragma: no cover - depends on the server
        print(f"NOTICE: extension {name} not available ({exc.__class__.__name__}); using portable fallback")
        return False


def upgrade() -> None:
    with op.batch_alter_table("shops") as batch:
        batch.add_column(sa.Column("google_place_id", sa.String(length=255), nullable=True))
        batch.add_column(sa.Column("place_link_status", sa.String(length=12), nullable=False, server_default="none"))
        batch.create_unique_constraint("uq_shops_google_place_id", ["google_place_id"])

    with op.batch_alter_table("inventory_events") as batch:
        batch.add_column(sa.Column("client_recorded_at", TS, nullable=True))

    with op.batch_alter_table("notifications") as batch:
        batch.add_column(sa.Column("push_status", sa.String(length=10), nullable=False, server_default="skipped"))
        batch.add_column(sa.Column("push_attempts", sa.Integer(), nullable=False, server_default="0"))
        batch.add_column(sa.Column("pushed_at", TS, nullable=True))
    op.create_index("ix_notifications_push_status", "notifications", ["push_status"])

    op.create_table(
        "notification_devices",
        sa.Column("id", BIG_ID, primary_key=True),
        sa.Column("user_id", BIG_ID, sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("token", sa.String(length=512), nullable=False, unique=True),
        sa.Column("platform", sa.String(length=12), nullable=False),
        sa.Column("app_version", sa.String(length=32), nullable=True),
        sa.Column("failure_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("disabled_at", TS, nullable=True),
        sa.Column("last_seen_at", TS, nullable=False),
        sa.Column("created_at", TS, nullable=False),
    )
    op.create_index("ix_notification_devices_user_id", "notification_devices", ["user_id"])

    op.create_table(
        "localities",
        sa.Column("id", BIG_ID, primary_key=True),
        sa.Column("slug", sa.String(length=60), nullable=False, unique=True),
        sa.Column("name", sa.String(length=80), nullable=False),
        sa.Column("name_kn", sa.String(length=80), nullable=True),
        sa.Column("city", sa.String(length=40), nullable=False),
        sa.Column("lat", sa.Float(), nullable=False),
        sa.Column("lng", sa.Float(), nullable=False),
        sa.Column("radius_m", sa.Integer(), nullable=False),
        sa.Column("aliases", sa.JSON(), nullable=False),
        sa.Column("search_text", sa.Text(), nullable=False),
        sa.Column("source", sa.String(length=12), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.true()),
    )
    op.create_index("ix_localities_lat_lng", "localities", ["lat", "lng"])

    op.create_table(
        "saved_places",
        sa.Column("id", BIG_ID, primary_key=True),
        sa.Column("user_id", BIG_ID, sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("shop_id", BIG_ID, sa.ForeignKey("shops.id", ondelete="CASCADE"), nullable=True),
        sa.Column("google_place_id", sa.String(length=255), nullable=True),
        sa.Column("created_at", TS, nullable=False),
        sa.UniqueConstraint("user_id", "shop_id", name="uq_saved_user_shop"),
        sa.UniqueConstraint("user_id", "google_place_id", name="uq_saved_user_place"),
        sa.CheckConstraint("(shop_id IS NOT NULL) OR (google_place_id IS NOT NULL)", name="ck_saved_target"),
    )
    op.create_index("ix_saved_places_user_id", "saved_places", ["user_id"])

    op.create_table(
        "reports",
        sa.Column("id", BIG_ID, primary_key=True),
        sa.Column("reporter_user_id", BIG_ID, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("target_type", sa.String(length=16), nullable=False),
        sa.Column("target_id", sa.String(length=255), nullable=False),
        sa.Column("reason", sa.String(length=32), nullable=False),
        sa.Column("note", sa.String(length=500), nullable=True),
        sa.Column("status", sa.String(length=12), nullable=False),
        sa.Column("resolved_by", BIG_ID, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("resolved_at", TS, nullable=True),
        sa.Column("resolution_note", sa.String(length=255), nullable=True),
        sa.Column("created_at", TS, nullable=False),
    )
    op.create_index("ix_reports_status_created", "reports", ["status", "created_at"])

    op.create_table(
        "search_logs",
        sa.Column("id", BIG_ID, primary_key=True),
        sa.Column("user_id", BIG_ID, sa.ForeignKey("users.id", ondelete="SET NULL"), nullable=True),
        sa.Column("query", sa.String(length=200), nullable=False),
        sa.Column("intent", sa.String(length=16), nullable=False),
        sa.Column("locality_slug", sa.String(length=60), nullable=True),
        sa.Column("lat_r", sa.Float(), nullable=True),
        sa.Column("lng_r", sa.Float(), nullable=True),
        sa.Column("result_count", sa.Integer(), nullable=False),
        sa.Column("created_at", TS, nullable=False),
    )
    op.create_index("ix_search_logs_created", "search_logs", ["created_at"])

    op.create_table(
        "provider_usage",
        sa.Column("id", BIG_ID, primary_key=True),
        sa.Column("day", sa.String(length=10), nullable=False),
        sa.Column("provider", sa.String(length=24), nullable=False),
        sa.Column("operation", sa.String(length=32), nullable=False),
        sa.Column("count", sa.Integer(), nullable=False),
        sa.Column("errors", sa.Integer(), nullable=False),
        sa.UniqueConstraint("day", "provider", "operation", name="uq_provider_usage"),
    )

    if _pg():
        if _try_extension("postgis"):
            op.execute(
                "ALTER TABLE shops ADD COLUMN geog geography(Point, 4326) "
                "GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography) STORED"
            )
            op.execute("CREATE INDEX ix_shops_geog ON shops USING GIST (geog)")
        if _try_extension("pg_trgm"):
            op.execute("CREATE INDEX ix_variants_search_trgm ON product_variants USING GIN (search_text gin_trgm_ops)")
            op.execute("CREATE INDEX ix_shops_name_trgm ON shops USING GIN (lower(name) gin_trgm_ops)")
            op.execute("CREATE INDEX ix_localities_search_trgm ON localities USING GIN (search_text gin_trgm_ops)")


def downgrade() -> None:
    if _pg():
        for ix in ("ix_localities_search_trgm", "ix_shops_name_trgm", "ix_variants_search_trgm", "ix_shops_geog"):
            op.execute(f"DROP INDEX IF EXISTS {ix}")
        op.execute("ALTER TABLE shops DROP COLUMN IF EXISTS geog")
    op.drop_table("provider_usage")
    op.drop_index("ix_search_logs_created", table_name="search_logs")
    op.drop_table("search_logs")
    op.drop_index("ix_reports_status_created", table_name="reports")
    op.drop_table("reports")
    op.drop_index("ix_saved_places_user_id", table_name="saved_places")
    op.drop_table("saved_places")
    op.drop_index("ix_localities_lat_lng", table_name="localities")
    op.drop_table("localities")
    op.drop_index("ix_notification_devices_user_id", table_name="notification_devices")
    op.drop_table("notification_devices")
    op.drop_index("ix_notifications_push_status", table_name="notifications")
    with op.batch_alter_table("notifications") as batch:
        batch.drop_column("pushed_at")
        batch.drop_column("push_attempts")
        batch.drop_column("push_status")
    with op.batch_alter_table("inventory_events") as batch:
        batch.drop_column("client_recorded_at")
    with op.batch_alter_table("shops") as batch:
        batch.drop_constraint("uq_shops_google_place_id", type_="unique")
        batch.drop_column("place_link_status")
        batch.drop_column("google_place_id")
