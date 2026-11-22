"""OTP delivery providers.

* ``console``: development only (refused in production). Logs the code.
* ``msg91``: India DLT-compliant SMS through MSG91's Flow API. Milega generates the
  code and stores only its HMAC, so expiry, attempt limits and abuse protection
  stay in our database.
* ``twilio_verify``: Twilio Verify generates, sends and checks the code. Milega still
  records the challenge to enforce expiry, attempt limits and per-number rate limits.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

import httpx

from ..config import Settings
from ..errors import AppError

log = logging.getLogger("milega.sms")


class SmsProvider:
    name = "base"
    #: True when the provider creates and checks the code itself.
    managed = False

    def send(self, phone: str, code: str) -> None:  # pragma: no cover - interface
        raise NotImplementedError

    def start(self, phone: str) -> None:  # pragma: no cover - interface
        raise NotImplementedError

    def check(self, phone: str, code: str) -> bool:  # pragma: no cover - interface
        raise NotImplementedError


class ConsoleSms(SmsProvider):
    name = "console"

    def send(self, phone: str, code: str) -> None:
        log.warning("DEV OTP for %s: %s", phone, code)


def _unavailable() -> AppError:
    return AppError(503, "sms_unavailable", "We couldn't send the code right now. Please try again in a minute.")


@dataclass
class Msg91Sms(SmsProvider):
    auth_key: str
    template_id: str
    transport: httpx.BaseTransport | None = None
    name = "msg91"

    def send(self, phone: str, code: str) -> None:
        mobile = phone.lstrip("+")
        body = {"template_id": self.template_id, "short_url": "0", "recipients": [{"mobiles": mobile, "otp": code}]}
        try:
            with httpx.Client(timeout=10, transport=self.transport) as http:
                resp = http.post("https://control.msg91.com/api/v5/flow", json=body,
                                 headers={"authkey": self.auth_key, "accept": "application/json"})
        except httpx.HTTPError as exc:
            log.error("msg91 network error: %s", exc.__class__.__name__)
            raise _unavailable() from exc
        ok = resp.status_code < 300
        if ok:
            try:
                ok = resp.json().get("type", "success") != "error"
            except ValueError:
                pass
        if not ok:
            log.error("msg91 send failed: http=%s", resp.status_code)
            raise _unavailable()


@dataclass
class TwilioVerifySms(SmsProvider):
    account_sid: str
    auth_token: str
    service_sid: str
    transport: httpx.BaseTransport | None = None
    name = "twilio_verify"
    managed = True

    def _client(self) -> httpx.Client:
        return httpx.Client(base_url=f"https://verify.twilio.com/v2/Services/{self.service_sid}", timeout=10,
                            auth=(self.account_sid, self.auth_token), transport=self.transport)

    def start(self, phone: str) -> None:
        try:
            with self._client() as http:
                resp = http.post("/Verifications", data={"To": phone, "Channel": "sms"})
        except httpx.HTTPError as exc:
            raise _unavailable() from exc
        if resp.status_code >= 300:
            log.error("twilio verify start failed: http=%s", resp.status_code)
            raise _unavailable()

    def check(self, phone: str, code: str) -> bool:
        try:
            with self._client() as http:
                resp = http.post("/VerificationCheck", data={"To": phone, "Code": code})
        except httpx.HTTPError as exc:
            raise _unavailable() from exc
        if resp.status_code == 404:  # expired or already used
            return False
        if resp.status_code >= 300:
            log.error("twilio verify check failed: http=%s", resp.status_code)
            raise _unavailable()
        return resp.json().get("status") == "approved"


def make_sms_provider(settings: Settings) -> SmsProvider:
    if settings.sms_provider == "msg91":
        return Msg91Sms(settings.msg91_auth_key, settings.msg91_template_id)
    if settings.sms_provider == "twilio_verify":
        return TwilioVerifySms(settings.twilio_account_sid, settings.twilio_auth_token,
                               settings.twilio_verify_service_sid)
    if settings.sms_provider == "console":
        return ConsoleSms()
    raise RuntimeError(f"Unknown SMS_PROVIDER {settings.sms_provider!r}")
