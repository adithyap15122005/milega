"""ASGI entrypoint: ``uvicorn app.asgi:app``."""

from .main import create_app

try:
    app = create_app()
except RuntimeError as exc:  # unsafe production configuration -> refuse to start
    raise SystemExit(str(exc))
