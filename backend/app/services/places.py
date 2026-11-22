"""Google Places API (New) client for business discovery.

Rules this module enforces (Google Maps Platform terms + cost control):

* Only the fields we display are requested (``X-Goog-FieldMask``). List calls stay
  on the cheaper SKUs; ratings and opening hours are fetched only for one place's
  detail page.
* Nothing from Google is written to our database except place IDs (which may be
  stored indefinitely). Responses are not cached.
* Every call consumes the daily budget first (``PLACES_DAILY_REQUEST_LIMIT``);
  when it is spent, discovery degrades to Milega merchants only.
* Google results are always labelled with provenance ``google`` and never offer
  Milega inventory or reservations.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

import httpx

from ..reference_data import BENGALURU_BOUNDS, GOOGLE_TYPE_TO_CATEGORY
from .usage import UsageMeter

log = logging.getLogger("milega.places")

BASE_URL = "https://places.googleapis.com/v1"

LIST_FIELDS = ",".join(
    f"places.{f}" for f in (
        "id", "displayName", "formattedAddress", "shortFormattedAddress", "location",
        "primaryType", "primaryTypeDisplayName", "googleMapsUri", "businessStatus",
    )
)
# Text Search pages; Nearby Search does not (asking for nextPageToken there is a 400).
TEXT_SEARCH_FIELDS = LIST_FIELDS + ",nextPageToken"
BASIC_FIELDS = "id,displayName,formattedAddress,shortFormattedAddress,location,primaryType,primaryTypeDisplayName,googleMapsUri,businessStatus"
DETAIL_FIELDS = BASIC_FIELDS + (
    ",nationalPhoneNumber,internationalPhoneNumber,websiteUri,regularOpeningHours,currentOpeningHours,rating,userRatingCount"
)
LOCATION_FIELDS = "id,displayName,formattedAddress,location"


class PlacesError(Exception):
    """code: not_configured | quota_exceeded | denied | unavailable | invalid | not_found"""

    def __init__(self, code: str, message: str = ""):
        super().__init__(message or code)
        self.code = code


@dataclass
class PlacesClient:
    api_key: str
    meter: UsageMeter | None = None
    daily_limit: int | None = 2000
    timeout: float = 8.0
    transport: httpx.BaseTransport | None = None

    def __post_init__(self) -> None:
        self._http = httpx.Client(base_url=BASE_URL, timeout=self.timeout, transport=self.transport)

    # ---------------------------------------------------------------- calls

    def search_nearby(self, lat: float, lng: float, radius_m: float, *, types: list[str],
                      max_results: int = 20, language: str = "en") -> dict:
        body = {
            "includedTypes": types,
            "maxResultCount": max(1, min(max_results, 20)),
            "rankPreference": "DISTANCE",
            "languageCode": language,
            "regionCode": "IN",
            "locationRestriction": {"circle": {"center": {"latitude": lat, "longitude": lng},
                                               "radius": float(min(max(radius_m, 100), 50_000))}},
        }
        data = self._post("/places:searchNearby", body, LIST_FIELDS, "search_nearby")
        return {"places": [normalize_place(p) for p in data.get("places", [])], "next_page_token": None}

    def search_text(self, query: str, *, lat: float | None = None, lng: float | None = None,
                    radius_m: float | None = None, max_results: int = 20, page_token: str | None = None,
                    language: str = "en", restrict_to_city: bool = False, fields: str = TEXT_SEARCH_FIELDS) -> dict:
        body: dict = {"textQuery": query[:200], "pageSize": max(1, min(max_results, 20)),
                      "languageCode": language, "regionCode": "IN"}
        if page_token:
            body["pageToken"] = page_token
        if restrict_to_city:
            body["locationRestriction"] = {"rectangle": _city_rectangle()}
        elif lat is not None and lng is not None:
            body["locationBias"] = {"circle": {"center": {"latitude": lat, "longitude": lng},
                                               "radius": float(min(max(radius_m or 3000, 100), 50_000))}}
        data = self._post("/places:searchText", body, fields, "search_text")
        return {"places": [normalize_place(p) for p in data.get("places", [])],
                "next_page_token": data.get("nextPageToken")}

    def details(self, place_id: str, *, fields: str = DETAIL_FIELDS, language: str = "en",
                session_token: str | None = None) -> dict:
        if not place_id or len(place_id) > 255 or "/" in place_id:
            raise PlacesError("invalid", "Invalid place id")
        params = {"languageCode": language, "regionCode": "IN"}
        if session_token:
            params["sessionToken"] = session_token
        data = self._request("GET", f"/places/{place_id}", None, fields, "details", params=params)
        return normalize_place(data, detailed=True)

    def autocomplete(self, text: str, *, session_token: str | None = None, lat: float | None = None,
                     lng: float | None = None, language: str = "en") -> list[dict]:
        body: dict = {"input": text[:120], "includedRegionCodes": ["in"], "languageCode": language,
                      "locationRestriction": {"rectangle": _city_rectangle()}}
        if session_token:
            body["sessionToken"] = session_token
        if lat is not None and lng is not None:
            body["origin"] = {"latitude": lat, "longitude": lng}
        data = self._post("/places:autocomplete", body, None, "autocomplete")
        out = []
        for s in data.get("suggestions", []):
            p = s.get("placePrediction")
            if not p:
                continue
            fmt = p.get("structuredFormat", {})
            out.append({
                "place_id": p.get("placeId"),
                "text": p.get("text", {}).get("text", ""),
                "main_text": fmt.get("mainText", {}).get("text", ""),
                "secondary_text": fmt.get("secondaryText", {}).get("text", ""),
                "types": p.get("types", []),
            })
        return out

    # ------------------------------------------------------------- plumbing

    def _post(self, path: str, body: dict, fields: str | None, op: str) -> dict:
        return self._request("POST", path, body, fields, op)

    def _request(self, method: str, path: str, body: dict | None, fields: str | None, op: str,
                 params: dict | None = None) -> dict:
        if not self.api_key:
            raise PlacesError("not_configured", "Google Places is not configured")
        if self.meter is not None and not self.meter.consume("google_places", op, daily_limit=self.daily_limit):
            raise PlacesError("quota_exceeded", "Today's Google Places budget is used up")
        headers = {"X-Goog-Api-Key": self.api_key, "Content-Type": "application/json"}
        if fields:
            headers["X-Goog-FieldMask"] = fields
        try:
            resp = self._http.request(method, path, json=body, params=params, headers=headers)
        except httpx.HTTPError as exc:
            self._error(op)
            log.warning("places %s network error: %s", op, exc.__class__.__name__)
            raise PlacesError("unavailable", "Google Places could not be reached") from exc
        if resp.status_code >= 400:
            self._error(op)
            status = ""
            try:
                status = resp.json().get("error", {}).get("status", "")
            except ValueError:
                pass
            log.warning("places %s failed: http=%s status=%s", op, resp.status_code, status)
            if resp.status_code == 429 or status == "RESOURCE_EXHAUSTED":
                raise PlacesError("quota_exceeded", "Google Places quota reached")
            if resp.status_code in (401, 403) or status == "PERMISSION_DENIED":
                raise PlacesError("denied", "The Google Places key was rejected")
            if resp.status_code == 404 or status == "NOT_FOUND":
                raise PlacesError("not_found", "Place not found")
            if resp.status_code == 400:
                raise PlacesError("invalid", "Google Places rejected the request")
            raise PlacesError("unavailable", "Google Places is temporarily unavailable")
        return resp.json() if resp.content else {}

    def _error(self, op: str) -> None:
        if self.meter is not None:
            try:
                self.meter.record_error("google_places", op)
            except Exception:  # never let bookkeeping mask the real error
                log.exception("could not record places error")


def _city_rectangle() -> dict:
    (lo_lat, lo_lng), (hi_lat, hi_lng) = BENGALURU_BOUNDS["low"], BENGALURU_BOUNDS["high"]
    return {"low": {"latitude": lo_lat, "longitude": lo_lng}, "high": {"latitude": hi_lat, "longitude": hi_lng}}


def normalize_place(p: dict, *, detailed: bool = False) -> dict:
    loc = p.get("location") or {}
    primary = p.get("primaryType")
    view = {
        "place_id": p.get("id"),
        "name": (p.get("displayName") or {}).get("text", ""),
        "address": p.get("shortFormattedAddress") or p.get("formattedAddress") or "",
        "full_address": p.get("formattedAddress") or "",
        "lat": loc.get("latitude"),
        "lng": loc.get("longitude"),
        "primary_type": primary,
        "type_label": (p.get("primaryTypeDisplayName") or {}).get("text"),
        "category": GOOGLE_TYPE_TO_CATEGORY.get(primary or "", None),
        "maps_uri": p.get("googleMapsUri"),
        "business_status": p.get("businessStatus"),
        "provenance": "google",
        "merchant_state": "google_discovered",
        "reservable": False,
    }
    if detailed:
        current = p.get("currentOpeningHours") or {}
        regular = p.get("regularOpeningHours") or {}
        view.update({
            "phone": p.get("nationalPhoneNumber") or p.get("internationalPhoneNumber"),
            "website": p.get("websiteUri"),
            "rating": p.get("rating"),
            "user_rating_count": p.get("userRatingCount"),
            "open_now": current.get("openNow", regular.get("openNow")),
            "weekday_hours": regular.get("weekdayDescriptions") or [],
        })
    return view


ATTRIBUTION = {
    "provider": "Google Maps",
    "text": "Business information from Google Maps",
    "note": "Google-listed businesses are not Milega partners unless marked as Milega merchants.",
}
