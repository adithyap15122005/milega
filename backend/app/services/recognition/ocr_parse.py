"""Turn on-device OCR lines into product hints and invoice rows.

The phone runs Google ML Kit text recognition (Latin + Devanagari) offline and
uploads the recognised lines with their bounding boxes. This module:

* clusters lines that sit close together (≈ one package's label), and
* rebuilds invoice table rows from line positions (ML Kit often returns table
  columns as separate blocks).
"""

from __future__ import annotations

import re
from statistics import median

from .types import InvoiceLine, OcrLine

_NUM_TOKEN = re.compile(r"^(?:rs\.?|₹)?\(?(\d{1,3}(?:,\d{2,3})+|\d+)(?:\.(\d{1,3}))?\)?/?-?$", re.IGNORECASE)
_SERIAL = re.compile(r"^\(?\d{1,3}[.)]\)?$")
_SKIP_ROW = re.compile(
    r"\b(total|sub\s*total|grand|gst|cgst|sgst|igst|tax|round|discount|balance|amount in words|paid)\b",
    re.IGNORECASE,
)


def _height(line: OcrLine) -> float:
    return max((line.bottom or 0) - (line.top or 0), 1.0)


def cluster_lines(lines: list[OcrLine]) -> list[list[OcrLine]]:
    """Single-link spatial clustering; falls back to ML Kit block ids without boxes."""
    if not lines:
        return []
    if not all(l.has_box for l in lines):
        groups: dict[int, list[OcrLine]] = {}
        for i, l in enumerate(lines):
            groups.setdefault(l.block if l.block is not None else i, []).append(l)
        return list(groups.values())

    n = len(lines)
    parent = list(range(n))

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def near(a: OcrLine, b: OcrLine) -> bool:
        pad = 0.8 * max(_height(a), _height(b))
        return not (
            a.right + pad < b.left or b.right + pad < a.left or
            a.bottom + pad < b.top or b.bottom + pad < a.top
        )

    for i in range(n):
        for j in range(i + 1, n):
            if near(lines[i], lines[j]):
                parent[find(i)] = find(j)
    clusters: dict[int, list[OcrLine]] = {}
    for i in range(n):
        clusters.setdefault(find(i), []).append(lines[i])
    ordered = sorted(clusters.values(), key=lambda c: (min(l.top for l in c), min(l.left for l in c)))
    return [sorted(c, key=lambda l: (l.top, l.left)) for c in ordered]


def cluster_text(cluster: list[OcrLine]) -> str:
    return " ".join(l.text for l in cluster)


def cluster_area(cluster: list[OcrLine]) -> float:
    if not all(l.has_box for l in cluster):
        return float(sum(len(l.text) for l in cluster))
    return sum((l.right - l.left) * (l.bottom - l.top) for l in cluster)


# ---------------------------------------------------------------- invoices


def rebuild_rows(lines: list[OcrLine]) -> list[str]:
    if not lines:
        return []
    if not all(l.has_box for l in lines):
        return [l.text for l in lines]
    tol = 0.55 * median(_height(l) for l in lines)
    rows: list[list[OcrLine]] = []
    for line in sorted(lines, key=lambda l: (l.top + l.bottom) / 2):
        centre = (line.top + line.bottom) / 2
        if rows:
            last = rows[-1]
            last_centre = median((l.top + l.bottom) / 2 for l in last)
            if abs(centre - last_centre) <= tol:
                last.append(line)
                continue
        rows.append([line])
    return [" ".join(l.text for l in sorted(r, key=lambda l: l.left)) for r in rows]


def _number(token: str) -> float | None:
    m = _NUM_TOKEN.match(token.strip())
    if not m:
        return None
    whole = m.group(1).replace(",", "")
    frac = m.group(2)
    return float(f"{whole}.{frac}" if frac else whole)


def parse_invoice_row(row: str) -> InvoiceLine | None:
    if _SKIP_ROW.search(row):
        return None
    tokens = row.split()
    numbers: list[float] = []
    cut = len(tokens)
    for idx in range(len(tokens) - 1, -1, -1):
        value = _number(tokens[idx])
        if value is None:
            break
        numbers.insert(0, value)
        cut = idx
    desc_tokens = tokens[:cut]
    if desc_tokens and _SERIAL.match(desc_tokens[0]):
        desc_tokens = desc_tokens[1:]
    description = " ".join(desc_tokens).strip(" -:|")
    if sum(ch.isalpha() for ch in description) < 3 or not numbers:
        return None

    qty = rate = total = None
    confidence = 0.4
    # Try (qty, rate, amount) using the last 3 or 4 numbers (an HSN code may precede them).
    for window in (numbers[-3:], numbers[-4:-1]):
        if len(window) == 3:
            q, r, t = window
            if 0 < q < 10_000 and t > 0 and abs(q * r - t) <= max(0.02 * t, 1.0):
                qty, rate, total, confidence = q, r, t, 0.85
                break
    if qty is None and len(numbers) >= 2:
        q, t = numbers[-2], numbers[-1]
        if 0 < q < 10_000 and q == int(q) and t >= q:
            qty, total, rate, confidence = q, t, round(t / q, 2), 0.5
    if qty is None and len(numbers) == 1 and 0 < numbers[0] < 1000 and numbers[0] == int(numbers[0]):
        qty, confidence = numbers[0], 0.35
    if qty is None:
        return None
    return InvoiceLine(description=description, quantity=qty, unit_price=rate, line_total=total, confidence=confidence)


def parse_invoice(lines: list[OcrLine]) -> tuple[list[InvoiceLine], str | None]:
    rows = rebuild_rows(lines)
    supplier = None
    for row in rows[:4]:
        letters = sum(ch.isalpha() for ch in row)
        if letters >= 6 and not any(ch.isdigit() for ch in row[:6]):
            supplier = row[:120]
            break
    parsed = [p for p in (parse_invoice_row(r) for r in rows) if p is not None]
    return parsed[:80], supplier
