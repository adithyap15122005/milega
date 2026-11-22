"""Interchangeable vision providers.

* ``OcrTextProvider`` (free, default without credentials): uses the text that
  Google ML Kit read *on the phone*. It identifies products by the words on the
  pack and parses invoices, but it cannot count items, so quantities are always
  left for the shopkeeper to enter.
* ``ClaudeVisionProvider``: sends the compressed photo to Claude and receives
  JSON constrained by a JSON schema (structured outputs), then validated again
  with Pydantic. It can read packs without clear text and estimate counts, but
  counts are only pre-filled when the model marks them reliable.

Both return :class:`ProviderResult`; the pipeline does catalogue matching.
Adding another provider (on-device detector, Gemini, a fine-tuned model) means
implementing ``analyze``.
"""

from __future__ import annotations

import base64
import json
import logging
from typing import Protocol

from ...config import Settings
from .ocr_parse import cluster_area, cluster_lines, cluster_text, parse_invoice
from .types import DetectedItem, OcrLine, ProviderError, ProviderResult

log = logging.getLogger("milega.recognition")


class VisionProvider(Protocol):
    name: str
    can_count: bool

    def analyze(self, *, image_jpeg: bytes, mode: str, ocr_lines: list[OcrLine]) -> ProviderResult: ...


class OcrTextProvider:
    name = "on_device_ocr"
    can_count = False

    def analyze(self, *, image_jpeg: bytes, mode: str, ocr_lines: list[OcrLine]) -> ProviderResult:
        lines = [l for l in ocr_lines if sum(ch.isalnum() for ch in l.text) >= 2]
        if mode == "invoice":
            invoice_lines, supplier = parse_invoice(lines)
            return ProviderResult(provider=self.name, invoice_lines=invoice_lines, supplier_name=supplier,
                                  note="Read from on-device OCR. Check every line.")
        clusters = cluster_lines(lines)
        clusters.sort(key=cluster_area, reverse=True)
        items = []
        for cluster in clusters[:25]:
            text = cluster_text(cluster)
            items.append(DetectedItem(
                label=text[:200],
                visible_text=text,
                # OCR reads text well but says nothing about *which* product it
                # is; identity confidence comes from catalogue matching.
                identity_confidence=0.85,
                visible_count=None,
                count_reliable=False,
            ))
        return ProviderResult(provider=self.name, items=items,
                              note="Identified from text on the packs. Counts are not estimated.")


# ---------------------------------------------------------------- Claude

_SYSTEM_PROMPT = """You help small shopkeepers in India add stock to an inventory app by photographing products.
Report only what is actually visible in the photo.

Rules:
- Each distinct product (same brand, product and pack size) appears once in "items".
- brand, product_name and size_text must be read from the package. If a field is not legible, use null. Never guess a pack size.
- Packaging may be in English, Hindi or Kannada. Write brand and product names in English letters.
- visible_count is the number of separate units of that product you can see. Set count_reliable to true only when every unit is clearly visible and you are confident in the exact number; otherwise false (stacked, overlapping, cut off or blurry units).
- identity_confidence (0 to 1) is how sure you are of brand + product + size together.
- If the photo is too blurry, dark or cropped to identify anything, set image_usable to false and list the issues.
- Loose goods without packaging (vegetables, grains in sacks) may be reported with brand null and a plain product_name.
"""

_MODE_PROMPTS = {
    "incoming": "This photo shows newly received stock. List every distinct product with its visible unit count.",
    "shelf": "This photo shows a shop shelf. List every distinct product with the number of front-facing units you can see. Hidden stock is not counted.",
    "invoice": "This photo shows a supplier invoice or bill. Extract each purchased line item: description, quantity, unit, unit_price in rupees and line_total in rupees. Skip tax, discount and total rows. Leave items empty.",
}


def _nullable(schema: dict) -> dict:
    return {"anyOf": [schema, {"type": "null"}]}


_OUTPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "image_usable": {"type": "boolean"},
        "image_issues": {"type": "array", "items": {"type": "string"}},
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "label": {"type": "string"},
                    "brand": _nullable({"type": "string"}),
                    "product_name": _nullable({"type": "string"}),
                    "size_text": _nullable({"type": "string"}),
                    "category": _nullable({"type": "string"}),
                    "visible_text": _nullable({"type": "string"}),
                    "visible_count": _nullable({"type": "integer"}),
                    "count_reliable": {"type": "boolean"},
                    "identity_confidence": {"type": "number"},
                },
                "required": ["label", "brand", "product_name", "size_text", "category", "visible_text",
                             "visible_count", "count_reliable", "identity_confidence"],
                "additionalProperties": False,
            },
        },
        "invoice_lines": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "description": {"type": "string"},
                    "quantity": _nullable({"type": "number"}),
                    "unit": _nullable({"type": "string"}),
                    "unit_price": _nullable({"type": "number"}),
                    "line_total": _nullable({"type": "number"}),
                },
                "required": ["description", "quantity", "unit", "unit_price", "line_total"],
                "additionalProperties": False,
            },
        },
        "supplier_name": _nullable({"type": "string"}),
    },
    "required": ["image_usable", "image_issues", "items", "invoice_lines", "supplier_name"],
    "additionalProperties": False,
}


class ClaudeVisionProvider:
    name = "claude_vision"
    can_count = True

    def __init__(self, settings: Settings, client=None):
        self.settings = settings
        if client is None:
            import anthropic  # imported lazily so the free path has no dependency on it

            client = anthropic.Anthropic(max_retries=2, timeout=90.0)
        self.client = client

    def analyze(self, *, image_jpeg: bytes, mode: str, ocr_lines: list[OcrLine]) -> ProviderResult:
        import anthropic

        content: list[dict] = [
            {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg",
                                         "data": base64.standard_b64encode(image_jpeg).decode("ascii")}},
            {"type": "text", "text": _MODE_PROMPTS[mode]},
        ]
        if ocr_lines:
            hint = "\n".join(l.text for l in ocr_lines[:120])
            content.append({"type": "text", "text": "Text read on the phone by OCR (may contain errors):\n" + hint})
        try:
            response = self.client.beta.messages.create(
                model=self.settings.vision_model,
                max_tokens=8000,
                system=_SYSTEM_PROMPT,
                messages=[{"role": "user", "content": content}],
                output_config={
                    "effort": self.settings.vision_effort,
                    "format": {"type": "json_schema", "schema": _OUTPUT_SCHEMA},
                },
                # If the primary model declines, the API retries on a fallback model.
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
            )
        except anthropic.RateLimitError as exc:
            raise ProviderError("provider_busy", "The recognition service is busy. Try again in a minute.", True) from exc
        except anthropic.APIConnectionError as exc:
            raise ProviderError("provider_unreachable", "Could not reach the recognition service.", True) from exc
        except anthropic.APIStatusError as exc:
            log.warning("vision provider error status=%s request_id=%s", exc.status_code,
                        getattr(exc, "request_id", None))
            retryable = exc.status_code >= 500
            raise ProviderError("provider_error", "Recognition failed. Please try again.", retryable) from exc

        if response.stop_reason == "refusal":
            raise ProviderError("provider_refused", "This photo could not be processed. Add items manually.")
        if response.stop_reason == "max_tokens":
            raise ProviderError("provider_truncated", "Too many items in one photo. Photograph fewer products at a time.")
        text = next((b.text for b in response.content if getattr(b, "type", None) == "text"), None)
        if not text:
            raise ProviderError("provider_empty", "Recognition returned nothing. Please retake the photo.", True)
        try:
            data = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ProviderError("provider_invalid", "Recognition returned an invalid answer.", True) from exc
        return parse_claude_payload(data, mode=mode, provider=self.name)


def parse_claude_payload(data: dict, *, mode: str, provider: str = "claude_vision") -> ProviderResult:
    """Validate model JSON into a ProviderResult (drops malformed entries)."""
    if not isinstance(data, dict):
        raise ProviderError("provider_invalid", "Recognition returned an invalid answer.", True)
    items = []
    for raw in (data.get("items") or [])[:40]:
        if isinstance(raw, dict):
            try:
                items.append(DetectedItem.model_validate(raw))
            except ValueError:
                continue
    invoice_lines = []
    if mode == "invoice":
        from .types import InvoiceLine

        for raw in (data.get("invoice_lines") or [])[:80]:
            if isinstance(raw, dict):
                try:
                    invoice_lines.append(InvoiceLine.model_validate({**raw, "confidence": 0.8}))
                except ValueError:
                    continue
    issues = [str(i)[:40] for i in (data.get("image_issues") or []) if i][:5]
    return ProviderResult(
        provider=provider,
        image_usable=bool(data.get("image_usable", True)),
        image_issues=issues,
        items=items if mode != "invoice" else [],
        invoice_lines=invoice_lines,
        supplier_name=(data.get("supplier_name") or None),
    )


def select_provider(settings: Settings, *, has_ocr: bool) -> VisionProvider:
    choice = settings.vision_provider
    if choice == "claude" or (choice == "auto" and settings.anthropic_api_key_present):
        return ClaudeVisionProvider(settings)
    return OcrTextProvider()


def provider_status(settings: Settings) -> dict:
    claude = settings.vision_provider == "claude" or (
        settings.vision_provider == "auto" and settings.anthropic_api_key_present
    )
    return {
        "active": "claude_vision" if claude else "on_device_ocr",
        "can_count": claude,
        "model": settings.vision_model if claude else None,
        "description": (
            "Photo is analysed by Claude vision; results are suggestions you confirm."
            if claude else
            "Products are identified from text read on your phone. Enter quantities yourself."
        ),
    }
