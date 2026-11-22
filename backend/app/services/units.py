"""Quantity, unit and size handling for Indian retail.

* Sell units decide how stock is counted (pieces vs. loose goods by weight).
* Sizes printed on packs ("1kg", "500 gm", "1 Ltr", "25W", "2-inch") are parsed
  into a canonical (value, unit) pair so 1 kg and 1000 g compare equal while
  1 kg and 500 g never do.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation

from ..errors import bad_request

MILLI = 1000

# unit -> (label, allows fractional quantities)
SELL_UNITS: dict[str, tuple[str, bool]] = {
    "pc": ("pcs", False),
    "kg": ("kg", True),
    "g": ("g", False),
    "l": ("L", True),
    "ml": ("ml", False),
    "m": ("m", True),
    "dozen": ("dozen", True),
}

MAX_QUANTITY = Decimal("100000")


def to_milli(quantity: float | int | str | Decimal, sell_unit: str) -> int:
    try:
        q = Decimal(str(quantity))
    except (InvalidOperation, ValueError):
        raise bad_request("invalid_quantity", "Quantity must be a number")
    if not q.is_finite() or q < 0:
        raise bad_request("invalid_quantity", "Quantity must be zero or more")
    if q > MAX_QUANTITY:
        raise bad_request("invalid_quantity", "Quantity is too large")
    _, fractional = SELL_UNITS.get(sell_unit, ("", False))
    if not fractional and q != q.to_integral_value():
        raise bad_request(
            "whole_number_required",
            f"Use a whole number for {SELL_UNITS.get(sell_unit, ('items',))[0]}",
        )
    milli = (q * MILLI).quantize(Decimal(1), rounding=ROUND_HALF_UP)
    return int(milli)


def from_milli(milli: int) -> float:
    value = Decimal(milli) / MILLI
    return float(value.quantize(Decimal("0.001")))


def unit_label(sell_unit: str) -> str:
    return SELL_UNITS.get(sell_unit, (sell_unit, False))[0]


# ------------------------------------------------------------------ pack sizes

# token -> (canonical unit, multiplier into the canonical unit)
_SIZE_UNITS: dict[str, tuple[str, float]] = {
    "kg": ("g", 1000), "kgs": ("g", 1000), "kilo": ("g", 1000), "kilogram": ("g", 1000),
    "kilograms": ("g", 1000), "किलो": ("g", 1000), "ಕೆಜಿ": ("g", 1000),
    "g": ("g", 1), "gm": ("g", 1), "gms": ("g", 1), "gram": ("g", 1), "grams": ("g", 1),
    "gr": ("g", 1), "grm": ("g", 1), "ग्राम": ("g", 1), "ಗ್ರಾಂ": ("g", 1),
    "l": ("ml", 1000), "ltr": ("ml", 1000), "ltrs": ("ml", 1000), "litre": ("ml", 1000),
    "liter": ("ml", 1000), "litres": ("ml", 1000), "liters": ("ml", 1000), "लीटर": ("ml", 1000),
    "ಲೀಟರ್": ("ml", 1000),
    "ml": ("ml", 1), "mls": ("ml", 1),
    "w": ("w", 1), "watt": ("w", 1), "watts": ("w", 1),
    "mah": ("mah", 1),
    "m": ("cm", 100), "mtr": ("cm", 100), "mtrs": ("cm", 100), "meter": ("cm", 100),
    "metre": ("cm", 100), "meters": ("cm", 100), "metres": ("cm", 100),
    "cm": ("cm", 1), "mm": ("mm", 1),
    "inch": ("inch", 1), "inches": ("inch", 1), "in": ("inch", 1), '"': ("inch", 1),
    "pages": ("pages", 1), "page": ("pages", 1), "pg": ("pages", 1),
    "pcs": ("pc", 1), "pc": ("pc", 1), "pieces": ("pc", 1), "piece": ("pc", 1),
    "sqmm": ("sqmm", 1), "amp": ("a", 1), "a": ("a", 1),
}

_UNIT_ALTERNATION = "|".join(
    sorted((re.escape(u) for u in _SIZE_UNITS), key=len, reverse=True)
)
SIZE_RE = re.compile(
    rf"(?<![\w.])(\d+(?:\.\d+)?)\s*-?\s*({_UNIT_ALTERNATION})(?![^\W\d_])",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class Size:
    value: float
    unit: str
    span: tuple[int, int]

    def matches(self, value: float | None, unit: str | None) -> bool:
        if value is None or unit is None or unit != self.unit:
            return False
        return abs(value - self.value) <= max(0.01 * self.value, 1e-6)


def parse_sizes(text: str) -> list[Size]:
    found: list[Size] = []
    for m in SIZE_RE.finditer(text):
        number, unit_token = m.group(1), m.group(2).lower()
        canon = _SIZE_UNITS.get(unit_token)
        if not canon:
            continue
        unit, mult = canon
        found.append(Size(value=round(float(number) * mult, 4), unit=unit, span=m.span()))
    return found


def parse_size(text: str | None) -> tuple[float | None, str | None]:
    if not text:
        return None, None
    sizes = parse_sizes(text)
    if not sizes:
        return None, None
    return sizes[0].value, sizes[0].unit


def format_size(value: float | None, unit: str | None) -> str | None:
    if value is None or unit is None:
        return None
    if unit == "g" and value >= 1000:
        return f"{value / 1000:g} kg"
    if unit == "ml" and value >= 1000:
        return f"{value / 1000:g} L"
    if unit == "cm" and value >= 100:
        return f"{value / 100:g} m"
    labels = {"g": "g", "ml": "ml", "w": "W", "mah": "mAh", "cm": "cm", "mm": "mm",
              "inch": "inch", "pages": "pages", "pc": "pcs", "sqmm": "sq mm", "a": "A"}
    return f"{value:g} {labels.get(unit, unit)}"
