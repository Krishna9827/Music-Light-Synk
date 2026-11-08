"""Music Light Synk: colour entities fed by the Music Light Synk Android TV app.

The app POSTs a JSON payload to a Home Assistant webhook on every track change. This
integration owns one webhook ID, keeps the latest payload, exposes it as entities and
re-fires it on the event bus as ``music_light_synk_event``.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import logging
from typing import Any

from aiohttp import web

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_WEBHOOK_ID, Platform
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryError
from homeassistant.helpers.dispatcher import async_dispatcher_send

from .const import DOMAIN, EVENT_NAME, signal_update

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [Platform.SENSOR, Platform.BINARY_SENSOR]


@dataclass
class MusicLightSynkData:
    """Latest data received from the TV."""

    payload: dict[str, Any] = field(default_factory=dict)
    colors: dict[str, Any] = field(default_factory=dict)
    playing: bool | None = None

    def apply(self, data: dict[str, Any]) -> None:
        """Merge one payload from the app."""
        self.payload = data
        if isinstance(data.get("colors"), dict):
            self.colors = data["colors"]
        event = data.get("event")
        if event == "stopped":
            self.playing = False
        elif event == "colors":
            self.playing = bool(data.get("playing", True))


type MusicLightSynkConfigEntry = ConfigEntry[MusicLightSynkData]


async def async_setup_entry(hass: HomeAssistant, entry: MusicLightSynkConfigEntry) -> bool:
    """Register the webhook and set up entities."""
    entry.runtime_data = MusicLightSynkData()
    webhook_id: str = entry.data[CONF_WEBHOOK_ID]

    async def handle_webhook(
        hass: HomeAssistant, webhook_id: str, request: web.Request
    ) -> web.Response:
        try:
            data = await request.json()
        except ValueError:
            return web.Response(status=400, text="body must be JSON")
        if not isinstance(data, dict):
            return web.Response(status=400, text="body must be a JSON object")
        entry.runtime_data.apply(data)
        async_dispatcher_send(hass, signal_update(entry.entry_id))
        hass.bus.async_fire(EVENT_NAME, {**data, "entry_id": entry.entry_id})
        return web.Response(status=200)

    try:
        webhook.async_register(
            hass,
            DOMAIN,
            entry.title,
            webhook_id,
            handle_webhook,
            local_only=True,
            allowed_methods=["POST"],
        )
    except ValueError as err:
        raise ConfigEntryError(
            f"Webhook ID '{webhook_id}' is already used by something else (probably the "
            "blueprint automation). Remove this entry and add it again with another ID."
        ) from err
    entry.async_on_unload(lambda: webhook.async_unregister(hass, webhook_id))

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    _LOGGER.debug("Music Light Synk listening on webhook %s", webhook_id)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: MusicLightSynkConfigEntry) -> bool:
    """Unload a config entry."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
