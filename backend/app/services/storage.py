"""Object storage for photos.

``LocalStorage`` writes under STORAGE_DIR (development). ``S3Storage`` targets
any S3-compatible store (AWS S3, Cloudflare R2, MinIO) and needs ``boto3``.

Keys are generated server-side only (never from user input) and follow:
    recognition/<shop_id>/<job_id>.jpg   private to the shop + admins
    catalog/<variant_id>/<uuid>.jpg      public product images
"""

from __future__ import annotations

import io
import re
from pathlib import Path

from PIL import Image, ImageOps, ImageStat, ImageFilter, UnidentifiedImageError

from ..config import Settings
from ..errors import AppError

_KEY_RE = re.compile(r"^(recognition|catalog)/[0-9a-zA-Z_-]+/[0-9a-zA-Z_-]+\.jpg$")
MAX_EDGE = 1600
ALLOWED_FORMATS = {"JPEG", "PNG", "WEBP"}


class LocalStorage:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, key: str) -> Path:
        if not _KEY_RE.match(key):
            raise AppError(400, "invalid_key", "Invalid file key")
        path = (self.root / key).resolve()
        if self.root not in path.parents:
            raise AppError(400, "invalid_key", "Invalid file key")
        return path

    def put(self, key: str, data: bytes) -> None:
        path = self._path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def get(self, key: str) -> bytes | None:
        path = self._path(key)
        return path.read_bytes() if path.exists() else None


class S3Storage:  # pragma: no cover - requires external service
    def __init__(self, settings: Settings):
        try:
            import boto3  # type: ignore
        except ImportError as exc:
            raise RuntimeError("STORAGE_BACKEND=s3 requires `pip install boto3`") from exc
        self.bucket = settings.s3_bucket
        self.client = boto3.client("s3", endpoint_url=settings.s3_endpoint_url or None)

    def put(self, key: str, data: bytes) -> None:
        if not _KEY_RE.match(key):
            raise AppError(400, "invalid_key", "Invalid file key")
        self.client.put_object(Bucket=self.bucket, Key=key, Body=data, ContentType="image/jpeg")

    def get(self, key: str) -> bytes | None:
        if not _KEY_RE.match(key):
            raise AppError(400, "invalid_key", "Invalid file key")
        try:
            return self.client.get_object(Bucket=self.bucket, Key=key)["Body"].read()
        except self.client.exceptions.NoSuchKey:
            return None


def make_storage(settings: Settings):
    if settings.storage_backend == "s3":
        return S3Storage(settings)
    return LocalStorage(settings.storage_dir)


def sanitize_image(data: bytes, max_bytes: int) -> tuple[bytes, Image.Image]:
    """Validate an upload is a real image, fix rotation, downscale, strip EXIF/GPS.

    Returns (jpeg_bytes, pil_image). Re-encoding drops any embedded metadata or
    payload, so we never store or serve the original bytes.
    """
    if not data:
        raise AppError(400, "empty_upload", "No photo received")
    if len(data) > max_bytes:
        raise AppError(413, "upload_too_large", "Photo is too large. Please retake it.")
    try:
        probe = Image.open(io.BytesIO(data))
        fmt = probe.format
        probe.verify()
    except (UnidentifiedImageError, OSError, SyntaxError):
        raise AppError(400, "invalid_image", "That file is not a photo we can read")
    if fmt not in ALLOWED_FORMATS:
        raise AppError(400, "invalid_image", "Use a JPEG, PNG or WEBP photo")
    img = Image.open(io.BytesIO(data))
    if img.width * img.height > 40_000_000:
        raise AppError(400, "invalid_image", "Photo resolution is too high")
    img = ImageOps.exif_transpose(img).convert("RGB")
    img.thumbnail((MAX_EDGE, MAX_EDGE))
    out = io.BytesIO()
    img.save(out, format="JPEG", quality=82, optimize=True)
    return out.getvalue(), img


def assess_quality(img: Image.Image) -> dict:
    """Cheap blur / exposure check so we can ask for a retake instead of guessing."""
    gray = img.convert("L")
    small = gray.copy()
    small.thumbnail((640, 640))
    brightness = ImageStat.Stat(small).mean[0]
    edges = small.filter(ImageFilter.FIND_EDGES)
    w, h = edges.size
    edges = edges.crop((3, 3, max(w - 3, 4), max(h - 3, 4)))  # the filter leaves artefacts at borders
    sharpness = ImageStat.Stat(edges).var[0]
    issues = []
    if brightness < 40:
        issues.append("too_dark")
    elif brightness > 235:
        issues.append("overexposed")
    if sharpness < 60:
        issues.append("blurry")
    return {
        "brightness": round(brightness, 1),
        "sharpness": round(sharpness, 1),
        "issues": issues,
        "usable": not issues,
    }
