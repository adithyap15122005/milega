from __future__ import annotations

from typing import Any


class AppError(Exception):
    """An error that is safe to show to the user.

    ``code`` is stable and machine readable (the Android app maps it to a
    translated message); ``message`` is a plain-English fallback.
    """

    def __init__(self, status: int, code: str, message: str, details: dict[str, Any] | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.details = details or {}


def not_found(what: str) -> AppError:
    return AppError(404, "not_found", f"{what} not found")


def forbidden(message: str = "You are not allowed to do this") -> AppError:
    return AppError(403, "forbidden", message)


def conflict(code: str, message: str, **details: Any) -> AppError:
    return AppError(409, code, message, details)


def bad_request(code: str, message: str, **details: Any) -> AppError:
    return AppError(400, code, message, details)
