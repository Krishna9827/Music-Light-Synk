"""Control the TV app from Home Assistant through the Android Debug Bridge integration.

The app accepts settings and commands as `am broadcast` intents from the ADB shell (protected
by android.permission.DUMP, which only the shell has). Home Assistant's Android Debug Bridge
integration (domain `androidtv`) runs shell commands on the TV with `androidtv.adb_command`.
The app answers by pushing a `status` payload to our webhook, which updates the entities.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
import logging
import shlex
from typing import Any

from homeassistant.const import STATE_UNAVAILABLE
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError

from .const import APP_LISTENER, APP_PACKAGE, APP_RECEIVER, CONF_ADB_ENTITY

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


class AppControl:
    """Sends commands to the TV app via `androidtv.adb_command`."""

    def __init__(self, hass: HomeAssistant, get_options: Callable[[], Mapping[str, Any]]) -> None:
        self.hass = hass
        self._get_options = get_options

    @property
    def adb_entity(self) -> str | None:
        return self._get_options().get(CONF_ADB_ENTITY) or None

    @property
    def available(self) -> bool:
        entity = self.adb_entity
        if not entity:
            return False
        state = self.hass.states.get(entity)
        return state is not None and state.state != STATE_UNAVAILABLE

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
        await self.shell(broadcast("CONFIGURE", values))

    async def request_status(self) -> None:
        """The app pushes a fresh status payload to our webhook."""
        await self.shell(broadcast("STATUS"))

    async def start_screen_capture(self) -> None:
        await self.shell(f"am start -n {APP_PACKAGE}/.CaptureConsentActivity")

    async def stop_screen_capture(self) -> None:
        await self.shell(broadcast("STOP_CAPTURE"))

    async def send_test(self) -> None:
        await self.shell(broadcast("TEST"))

    async def grant_permissions(self) -> None:
        """Media access (the app can't work without it) + screen capture without a prompt."""
        await self.shell(
            f"cmd notification allow_listener {APP_LISTENER}; "
            f"appops set {APP_PACKAGE} PROJECT_MEDIA allow; "
            f"appops set {APP_PACKAGE} SYSTEM_ALERT_WINDOW allow"
        )
        await self.request_status()
