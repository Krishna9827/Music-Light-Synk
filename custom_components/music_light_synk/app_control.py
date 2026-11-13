"""Control the app (Fire TV or phone) from Home Assistant.

Two ways, the first one that works:
 - Over Wi-Fi: the app runs a small HTTP API (port 21331) and tells us its address and a secret
   token in its webhook posts. Works for phones and the Fire TV, no ADB needed.
 - Over ADB (Fire TV): the app accepts `am broadcast` intents from the ADB shell (protected by
   android.permission.DUMP, which only the shell has); Home Assistant's Android Debug Bridge
   integration runs them with `androidtv.adb_command`.
The app answers by pushing a `status` payload to our webhook, which updates the entities.
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable, Mapping
import ipaddress
import logging
import shlex
from typing import Any
from urllib.parse import urlsplit

import aiohttp

from homeassistant.const import STATE_UNAVAILABLE
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .const import APP_LISTENER, APP_PACKAGE, APP_RECEIVER, CONF_ADB_ENTITY, CONF_CONTROL_TOKEN, CONF_CONTROL_URL

_LOGGER = logging.getLogger(__name__)

ADB_DOMAIN = "androidtv"
ADB_SERVICE = "adb_command"


def broadcast(action: str, extras: Mapping[str, Any] | None = None) -> str:
    """Shell command for one app intent. Values are shell-quoted; types pick --es/--ei/--ez."""
    parts = ["am", "broadcast", "-f", "0x20", "-n", APP_RECEIVER, "-a", f"{APP_PACKAGE}.{action}"]
    for key, value in (extras or {}).items():
        if isinstance(value, bool):
            parts += ["--ez", key, "true" if value else "false"]
        elif isinstance(value, (int, float)):
            parts += ["--ei", key, str(round(value))]
        else:
            parts += ["--es", key, shlex.quote(str(value))]
    return " ".join(parts)


def valid_control_url(url: Any) -> bool:
    """Only plain http:// to a private LAN address on the app's port: never anywhere else."""
    if not isinstance(url, str):
        return False
    try:
        parts = urlsplit(url)
        host = ipaddress.ip_address(parts.hostname or "")
    except ValueError:
        return False
    return parts.scheme == "http" and host.is_private and parts.port == 21331 and parts.path in ("", "/")


class AppControl:
    """Sends commands to the app over Wi-Fi (preferred) or `androidtv.adb_command`."""

    def __init__(
        self,
        hass: HomeAssistant,
        get_options: Callable[[], Mapping[str, Any]],
        get_data: Callable[[], Mapping[str, Any]] = lambda: {},
    ) -> None:
        self.hass = hass
        self._get_options = get_options
        self._get_data = get_data

    @property
    def adb_entity(self) -> str | None:
        return self._get_options().get(CONF_ADB_ENTITY) or None

    @property
    def control_url(self) -> str | None:
        url = self._get_data().get(CONF_CONTROL_URL)
        return url if valid_control_url(url) and self._get_data().get(CONF_CONTROL_TOKEN) else None

    @property
    def adb_available(self) -> bool:
        entity = self.adb_entity
        if not entity:
            return False
        state = self.hass.states.get(entity)
        return state is not None and state.state != STATE_UNAVAILABLE

    @property
    def available(self) -> bool:
        """Some way to reach the app: Wi-Fi (the app told us where) or ADB."""
        return self.control_url is not None or self.adb_available

    async def _http(self, action: str, body: Mapping[str, Any] | None = None) -> bool:
        """POST to the app's Wi-Fi API. False if we don't know it or it didn't answer."""
        url = self.control_url
        if url is None:
            return False
        token = self._get_data().get(CONF_CONTROL_TOKEN)
        session = async_get_clientsession(self.hass)
        try:
            async with session.post(
                f"{url.rstrip('/')}/api/{action}",
                json=dict(body or {}),
                headers={"Authorization": f"Bearer {token}"},
                timeout=aiohttp.ClientTimeout(total=6),
            ) as resp:
                if resp.status == 401:
                    raise HomeAssistantError("The app refused the request (token changed?). Open the app once so it reports again")
                if resp.status != 200:
                    _LOGGER.debug("App answered %s to %s", resp.status, action)
                    return False
                return True
        except (aiohttp.ClientError, asyncio.TimeoutError) as err:
            _LOGGER.debug("App not reachable over Wi-Fi at %s: %s", url, err)
            return False

    async def _send(self, action: str, body: Mapping[str, Any] | None, adb_command: str | None) -> None:
        if await self._http(action, body):
            return
        if adb_command is not None and self.adb_entity:
            await self.shell(adb_command)
            return
        if self.control_url is not None:
            raise HomeAssistantError(f"The app didn't answer at {self.control_url}. Is the device on and on Wi-Fi?")
        raise HomeAssistantError(
            "The app hasn't told Home Assistant where to reach it yet: set the HA URL and webhook ID in the app "
            "(or pick the Fire TV's Android Debug Bridge entity in the options)"
        )

    async def shell(self, command: str) -> None:
        entity = self.adb_entity
        if not entity:
            raise HomeAssistantError(
                "Pick your Fire TV's Android Debug Bridge entity in the Music Light Synk options first"
            )
        if not self.hass.services.has_service(ADB_DOMAIN, ADB_SERVICE):
            raise HomeAssistantError("The Android Debug Bridge integration is not set up in Home Assistant")
        _LOGGER.debug("adb shell on %s: %s", entity, command)
        await self.hass.services.async_call(
            ADB_DOMAIN, ADB_SERVICE, {"entity_id": entity, "command": command}, blocking=True
        )

    async def configure(self, **values: Any) -> None:
        await self._send("configure", values, broadcast("CONFIGURE", values))

    async def request_status(self) -> None:
        """The app pushes a fresh status payload to our webhook."""
        await self._send("status", None, broadcast("STATUS"))

    async def start_screen_capture(self) -> None:
        await self._send("capture", None, f"am start -n {APP_PACKAGE}/.CaptureConsentActivity")

    async def stop_screen_capture(self) -> None:
        await self._send("stop_capture", None, broadcast("STOP_CAPTURE"))

    async def send_test(self) -> None:
        await self._send("test", None, broadcast("TEST"))

    async def grant_permissions(self) -> None:
        """Media access (the app can't work without it) + screen capture without a prompt. ADB only."""
        if not self.adb_entity:
            raise HomeAssistantError(
                "Granting permissions needs the Android Debug Bridge (Fire TV). On a phone, grant them in the app"
            )
        await self.shell(
            f"cmd notification allow_listener {APP_LISTENER}; "
            f"appops set {APP_PACKAGE} PROJECT_MEDIA allow; "
            f"appops set {APP_PACKAGE} SYSTEM_ALERT_WINDOW allow"
        )
        await self.request_status()
