"""Environment-based configuration.

Every setting comes from environment variables (optionally loaded from
``backend/.env``). Nothing secret is hard-coded; the development defaults are
refused when ``APP_ENV=production``.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field, replace
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent  # backend/

_DEV_JWT_SECRET = "dev-only-insecure-secret-change-me"


def _load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


def _env_bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(name: str, default: int) -> int:
    value = os.environ.get(name)
    return int(value) if value not in (None, "") else default


@dataclass(frozen=True)
class Settings:
    app_env: str = "development"
    database_url: str = f"sqlite:///{(BASE_DIR / 'data' / 'milega.db').as_posix()}"
    jwt_secret: str = _DEV_JWT_SECRET
    access_token_days: int = 30
    otp_ttl_seconds: int = 300
    # "console" prints OTPs to the server log (development only).
    sms_provider: str = "console"
    expose_dev_otp: bool = True
    admin_phones: tuple[str, ...] = ()
    storage_backend: str = "local"  # local | s3
    storage_dir: Path = BASE_DIR / "data" / "uploads"
    s3_bucket: str = ""
    s3_endpoint_url: str = ""
    # auto: Claude vision when an Anthropic credential is configured, otherwise
    # the on-device OCR text matcher. "ocr" forces the free path.
    vision_provider: str = "auto"
    vision_model: str = "claude-opus-5-5"
    vision_effort: str = "medium"
    anthropic_api_key_present: bool = False
    auto_approve_shops: bool = False
    pending_reservation_minutes: int = 30
    confirmed_hold_minutes: int = 180
    max_active_reservations_per_customer: int = 5
    max_upload_bytes: int = 8 * 1024 * 1024
    search_default_radius_km: float = 5.0
    cors_origins: tuple[str, ...] = field(default_factory=tuple)
    run_background_jobs: bool = True

    @property
    def is_production(self) -> bool:
        return self.app_env == "production"

    def validate(self) -> None:
        if self.is_production:
            problems = []
            if self.jwt_secret == _DEV_JWT_SECRET or len(self.jwt_secret) < 32:
                problems.append("JWT_SECRET must be set to a random value of 32+ characters")
            if self.expose_dev_otp:
                problems.append("EXPOSE_DEV_OTP must be false in production")
            if self.sms_provider == "console":
                problems.append("SMS_PROVIDER=console cannot be used in production")
            if self.database_url.startswith("sqlite"):
                problems.append("Use PostgreSQL (DATABASE_URL) in production")
            if problems:
                raise RuntimeError("Unsafe production configuration: " + "; ".join(problems))

    def with_overrides(self, **kwargs) -> "Settings":
        return replace(self, **kwargs)


def load_settings() -> Settings:
    _load_dotenv(BASE_DIR / ".env")
    storage_dir = Path(_env("STORAGE_DIR", str(BASE_DIR / "data" / "uploads")))
    settings = Settings(
        app_env=_env("APP_ENV", "development"),
        database_url=_env("DATABASE_URL", Settings.database_url),
        jwt_secret=_env("JWT_SECRET", _DEV_JWT_SECRET),
        access_token_days=_env_int("ACCESS_TOKEN_DAYS", 30),
        otp_ttl_seconds=_env_int("OTP_TTL_SECONDS", 300),
        sms_provider=_env("SMS_PROVIDER", "console"),
        expose_dev_otp=_env_bool("EXPOSE_DEV_OTP", _env("APP_ENV", "development") != "production"),
        admin_phones=tuple(p.strip() for p in _env("ADMIN_PHONES", "").split(",") if p.strip()),
        storage_backend=_env("STORAGE_BACKEND", "local"),
        storage_dir=storage_dir,
        s3_bucket=_env("S3_BUCKET", ""),
        s3_endpoint_url=_env("S3_ENDPOINT_URL", ""),
        vision_provider=_env("VISION_PROVIDER", "auto"),
        vision_model=_env("VISION_MODEL", "claude-opus-5-5"),
        vision_effort=_env("VISION_EFFORT", "medium"),
        anthropic_api_key_present=bool(
            os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN")
        ),
        auto_approve_shops=_env_bool("AUTO_APPROVE_SHOPS", False),
        pending_reservation_minutes=_env_int("PENDING_RESERVATION_MINUTES", 30),
        confirmed_hold_minutes=_env_int("CONFIRMED_HOLD_MINUTES", 180),
        max_active_reservations_per_customer=_env_int("MAX_ACTIVE_RESERVATIONS", 5),
        max_upload_bytes=_env_int("MAX_UPLOAD_BYTES", 8 * 1024 * 1024),
        search_default_radius_km=float(_env("SEARCH_DEFAULT_RADIUS_KM", "5")),
        cors_origins=tuple(o.strip() for o in _env("CORS_ORIGINS", "").split(",") if o.strip()),
        run_background_jobs=_env_bool("RUN_BACKGROUND_JOBS", True),
    )
    settings.validate()
    return settings
