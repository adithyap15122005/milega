"""In-process sliding-window rate limiter.

Good enough for a single API instance. When running several replicas, swap
``MemoryRateLimiter`` for a Redis-backed implementation with the same
``hit()`` signature.
"""

from __future__ import annotations

import threading
import time
from collections import deque

from fastapi import Depends, Request

from .errors import AppError
from .security import client_ip


class MemoryRateLimiter:
    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = {}
        self._lock = threading.Lock()

    def hit(self, key: str, limit: int, window_seconds: float) -> bool:
        now = time.monotonic()
        with self._lock:
            bucket = self._hits.setdefault(key, deque())
            while bucket and now - bucket[0] > window_seconds:
                bucket.popleft()
            if len(bucket) >= limit:
                return False
            bucket.append(now)
            if len(self._hits) > 50_000:  # crude memory guard
                self._hits = {k: v for k, v in self._hits.items() if v}
            return True

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()


def rate_limit(bucket: str, limit: int, window_seconds: float):
    """Dependency factory: limit requests per client IP for a named bucket."""

    def _dependency(request: Request) -> None:
        limiter: MemoryRateLimiter = request.app.state.rate_limiter
        key = f"{bucket}:{client_ip(request)}"
        if not limiter.hit(key, limit, window_seconds):
            raise AppError(429, "rate_limited", "Too many requests. Please wait a moment and try again.")

    return Depends(_dependency)
