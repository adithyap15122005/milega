"""Rate limiting.

* ``RedisRateLimiter``: fixed-window counters with atomic ``INCR`` + ``EXPIRE`` in
  one transaction. All API replicas share the limits. Used when ``REDIS_URL`` is set.
* ``MemoryRateLimiter``: in-process sliding window, for development or a single
  instance.

If Redis is unreachable, the Redis limiter falls back to the in-process one rather
than blocking every request. That outage is logged and reported by ``/health/ready``.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import deque

from fastapi import Depends, Request

from .errors import AppError
from .security import client_ip

log = logging.getLogger("milega.ratelimit")


class MemoryRateLimiter:
    backend = "memory"

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

    def healthy(self) -> bool:
        return True


class RedisRateLimiter:
    backend = "redis"

    def __init__(self, client, prefix: str = "milega:rl:") -> None:
        self.client = client
        self.prefix = prefix
        self.fallback = MemoryRateLimiter()
        self._degraded_since: float | None = None

    @classmethod
    def from_url(cls, url: str) -> "RedisRateLimiter":
        import redis

        return cls(redis.Redis.from_url(url, socket_timeout=0.5, socket_connect_timeout=0.5))

    def hit(self, key: str, limit: int, window_seconds: float) -> bool:
        window = max(int(window_seconds), 1)
        slot = int(time.time() // window)
        redis_key = f"{self.prefix}{key}:{slot}"
        try:
            pipe = self.client.pipeline(transaction=True)
            pipe.incr(redis_key)
            pipe.expire(redis_key, window + 1)
            count, _ = pipe.execute()
            if self._degraded_since is not None:
                log.warning("redis rate limiter recovered")
                self._degraded_since = None
            return int(count) <= limit
        except Exception as exc:  # redis down: degrade, don't take the API down
            if self._degraded_since is None:
                self._degraded_since = time.time()
                log.error("redis rate limiter unavailable (%s); using in-process limits", exc.__class__.__name__)
            return self.fallback.hit(key, limit, window_seconds)

    def reset(self) -> None:
        self.fallback.reset()
        try:
            for k in self.client.scan_iter(f"{self.prefix}*"):
                self.client.delete(k)
        except Exception:
            pass

    def healthy(self) -> bool:
        try:
            return bool(self.client.ping())
        except Exception:
            return False


def make_rate_limiter(redis_url: str):
    if not redis_url:
        return MemoryRateLimiter()
    try:
        return RedisRateLimiter.from_url(redis_url)
    except ImportError:
        log.error("REDIS_URL is set but the 'redis' package is not installed; using in-process limits")
        return MemoryRateLimiter()


def rate_limit(bucket: str, limit: int, window_seconds: float):
    """Dependency factory: limit requests per client IP for a named bucket."""

    def _dependency(request: Request) -> None:
        limiter = request.app.state.rate_limiter
        key = f"{bucket}:{client_ip(request)}"
        if not limiter.hit(key, limit, window_seconds):
            raise AppError(429, "rate_limited", "Too many requests. Please wait a moment and try again.")

    return Depends(_dependency)
