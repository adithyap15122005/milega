"""Validated structures exchanged between vision providers and the pipeline.

Providers must return these models (never free-form text). Validators clamp
anything out of range so a misbehaving model can't inject absurd counts.
"""

from __future__ import annotations

from pydantic import BaseModel, Field, field_validator

MAX_COUNT = 500


def _clean_str(value, limit: int):
    if value is None:
        return None
    value = " ".join(str(value).split())[:limit]
    return value or None


class OcrLine(BaseModel):
    """One text line from on-device OCR (ML Kit). Coordinates are image pixels."""

    text: str
    block: int | None = None
    left: float | None = None
    top: float | None = None
    right: float | None = None
    bottom: float | None = None

    @field_validator("text")
    @classmethod
    def _text(cls, v: str) -> str:
        return " ".join(v.split())[:200]

    @property
    def has_box(self) -> bool:
        return None not in (self.left, self.top, self.right, self.bottom)


class DetectedItem(BaseModel):
    label: str
    brand: str | None = None
    product_name: str | None = None
    size_text: str | None = None
    category: str | None = None
    visible_text: str | None = None
    visible_count: int | None = None
    count_reliable: bool = False
    identity_confidence: float = 0.5

    @field_validator("label", mode="before")
    @classmethod
    def _label(cls, v):
        return _clean_str(v, 200) or "Unknown product"

    @field_validator("brand", "product_name", "category", mode="before")
    @classmethod
    def _short(cls, v):
        return _clean_str(v, 120)

    @field_validator("size_text", mode="before")
    @classmethod
    def _size(cls, v):
        return _clean_str(v, 40)

    @field_validator("visible_text", mode="before")
    @classmethod
    def _vtext(cls, v):
        return _clean_str(v, 600)

    @field_validator("visible_count", mode="before")
    @classmethod
    def _count(cls, v):
        if v is None:
            return None
        try:
            v = int(v)
        except (TypeError, ValueError):
            return None
        return v if 0 < v <= MAX_COUNT else None

    @field_validator("identity_confidence", mode="before")
    @classmethod
    def _conf(cls, v):
        try:
            v = float(v)
        except (TypeError, ValueError):
            return 0.0
        return min(max(v, 0.0), 1.0)


class InvoiceLine(BaseModel):
    description: str
    quantity: float | None = None
    unit: str | None = None
    unit_price: float | None = None
    line_total: float | None = None
    confidence: float = 0.6

    @field_validator("description", mode="before")
    @classmethod
    def _desc(cls, v):
        return _clean_str(v, 200) or "Item"

    @field_validator("quantity", "unit_price", "line_total", mode="before")
    @classmethod
    def _num(cls, v):
        if v is None:
            return None
        try:
            v = float(v)
        except (TypeError, ValueError):
            return None
        return v if 0 < v < 1_000_000 else None

    @field_validator("unit", mode="before")
    @classmethod
    def _unit(cls, v):
        return _clean_str(v, 16)


class ProviderResult(BaseModel):
    provider: str
    image_usable: bool = True
    image_issues: list[str] = Field(default_factory=list)
    items: list[DetectedItem] = Field(default_factory=list)
    invoice_lines: list[InvoiceLine] = Field(default_factory=list)
    supplier_name: str | None = None
    note: str | None = None


class ProviderError(Exception):
    def __init__(self, code: str, message: str, retryable: bool = False):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
