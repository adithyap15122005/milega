"""FastAPI application factory (modular monolith).

Run locally:  uvicorn app.asgi:app --reload --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import FileResponse, JSONResponse

from .config import Settings, load_settings
from .db import Database
from .errors import AppError
from .ratelimit import MemoryRateLimiter
from .routers import admin, auth, commerce, inventory, media, recognition, shops
from .services.recognition import select_provider
from .services.reservations import expire_due
from .services.storage import make_storage

ADMIN_UI = Path(__file__).resolve().parent / "admin_ui" / "index.html"


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": round(record.created, 3),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        for key in ("request_id", "path", "method", "status", "duration_ms", "user_id", "kind"):
            if hasattr(record, key):
                payload[key] = getattr(record, key)
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


def configure_logging() -> None:
    root = logging.getLogger()
    if any(isinstance(h.formatter, JsonFormatter) for h in root.handlers):
        return
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root.handlers = [handler]
    root.setLevel(logging.INFO)


log = logging.getLogger("milega")


async def _sweeper(app: FastAPI) -> None:
    """Expire overdue reservations every 30s (releases held stock)."""
    while True:
        try:
            await asyncio.to_thread(_sweep_once, app)
        except Exception:  # keep the loop alive
            log.exception("reservation sweeper failed")
        await asyncio.sleep(30)


def _sweep_once(app: FastAPI) -> int:
    with app.state.db.session() as db:
        return expire_due(db)


def create_app(settings: Settings | None = None, *, provider_factory=None) -> FastAPI:
    settings = settings or load_settings()
    configure_logging()
    database = Database(settings.database_url)
    if database.is_sqlite:
        Path(settings.database_url.replace("sqlite:///", "")).parent.mkdir(parents=True, exist_ok=True)
        database.create_all()  # PostgreSQL uses Alembic migrations instead

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        task = asyncio.create_task(_sweeper(app)) if settings.run_background_jobs else None
        yield
        if task:
            task.cancel()

    app = FastAPI(
        title="Milega API",
        version="0.1.0",
        description="Hyperlocal commerce: searchable inventories for neighbourhood shops.",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.db = database
    app.state.storage = make_storage(settings)
    app.state.rate_limiter = MemoryRateLimiter()
    app.state.provider_factory = provider_factory or select_provider

    app.add_middleware(GZipMiddleware, minimum_size=1024)
    if settings.cors_origins:
        app.add_middleware(CORSMiddleware, allow_origins=list(settings.cors_origins),
                           allow_methods=["*"], allow_headers=["*"])

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request_id = request.headers.get("x-request-id") or uuid.uuid4().hex[:12]
        start = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            log.exception("unhandled error", extra={"request_id": request_id, "path": request.url.path})
            response = JSONResponse(status_code=500, content={"error": {
                "code": "server_error", "message": "Something went wrong. Please try again.", "details": {}}})
        response.headers["x-request-id"] = request_id
        if request.url.path.startswith("/api/"):
            log.info("request", extra={
                "request_id": request_id, "path": request.url.path, "method": request.method,
                "status": response.status_code, "duration_ms": round((time.perf_counter() - start) * 1000, 1),
            })
        return response

    @app.exception_handler(AppError)
    async def app_error_handler(_request: Request, exc: AppError):
        return JSONResponse(status_code=exc.status,
                            content={"error": {"code": exc.code, "message": exc.message, "details": exc.details}})

    @app.exception_handler(RequestValidationError)
    async def validation_handler(_request: Request, exc: RequestValidationError):
        problems = [
            {"field": ".".join(str(p) for p in err.get("loc", [])[1:]), "message": err.get("msg", "")}
            for err in exc.errors()
        ]
        return JSONResponse(status_code=422, content={"error": {
            "code": "validation_error",
            "message": problems[0]["message"] if problems else "Please check the details you entered",
            "details": {"problems": problems}}})

    for module in (auth, shops, inventory, commerce, recognition, admin, media):
        app.include_router(module.router)

    @app.get("/health", tags=["ops"])
    def health():
        return {"ok": True, "env": settings.app_env}

    @app.get("/admin", include_in_schema=False)
    def admin_ui():
        return FileResponse(ADMIN_UI, media_type="text/html")

    return app
