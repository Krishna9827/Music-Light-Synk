"""Music Light Synk: album-art colours from the Android TV app, straight onto your lights.

The app POSTs a JSON payload to a Home Assistant webhook on every track change. This
integration owns that webhook and
 - drives the lights you picked for each zone of the artwork (no blueprint needed),
 - exposes the colours / now playing / playing / app status as entities,
 - controls the app's settings over Wi-Fi (phones and TVs), or through Home Assistant's Android
   Debug Bridge integration on a Fire TV,
 - re-fires every payload on the event bus as ``music_light_synk_event``.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import logging
from typing import Any

from aiohttp import web

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_WEBHOOK_ID, Platform
from homeassistant.core import CoreState, HomeAssistant
from homeassistant.exceptions import ConfigEntryError
from homeassistant.helpers.dispatcher import async_dispatcher_send

from .app_control import AppControl, valid_control_url
from .const import CONF_ADB_ENTITY, CONF_CONTROL_TOKEN, CONF_CONTROL_URL, DOMAIN, EVENT_NAME, signal_update
from .light_control import LightController

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [
    Platform.SENSOR,
    Platform.BINARY_SENSOR,
    Platform.SWITCH,
    Platform.BUTTON,
    Platform.SELECT,
    Platform.NUMBER,
    Platform.TEXT,
]


@dataclass
class MusicLightSynkData:
    """Latest data received from the TV."""

    lights: LightController
    app: AppControl
    payload: dict[str, Any] = field(default_factory=dict)
    colors: dict[str, Any] = field(default_factory=dict)
    playing: bool | None = None
    # The app's own report: media_access, screen_capture, source_mode, last_source, settings...
    status: dict[str, Any] = field(default_factory=dict)
    # Where the last colours came from: "artwork" (media access), "screen", "test".
    source: str | None = None
    adb_entity: str | None = None

    def setting(self, key: str) -> Any:
        """A TV app setting from the latest status (top level or under 'settings')."""
        settings = self.status.get("settings")
        if isinstance(settings, dict) and key in settings:
            return settings[key]
        return self.status.get(key)

    def set_setting(self, key: str, value: Any) -> None:
        """Optimistic local update after sending a command (the app confirms with a status push)."""
        if key in self.status and key != "settings":
            self.status[key] = value
        else:
            self.status.setdefault("settings", {})[key] = value

    def apply(self, data: dict[str, Any]) -> None:
        """Merge one payload from the app."""
        self.payload = data
        if isinstance(data.get("colors"), dict):
            self.colors = data["colors"]
        if isinstance(data.get("status"), dict):
            self.status = data["status"]
        event = data.get("event")
        if event in ("colors", "test") and isinstance(data.get("source"), str):
            self.source = data["source"]
        if event == "stopped":
            self.playing = False
        elif event == "colors":
            self.playing = bool(data.get("playing", True))


type MusicLightSynkConfigEntry = ConfigEntry[MusicLightSynkData]


async def async_setup_entry(hass: HomeAssistant, entry: MusicLightSynkConfigEntry) -> bool:
    """Register the webhook and set up entities."""
    entry.runtime_data = MusicLightSynkData(
        lights=LightController(hass, lambda: entry.options),
        app=AppControl(hass, lambda: entry.options, lambda: entry.data),
        adb_entity=entry.options.get(CONF_ADB_ENTITY),
    )
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
        _remember_control(hass, entry, data.pop("control", None))
        entry.runtime_data.apply(data)
        entry.runtime_data.lights.handle(data)  # ignores "status" payloads
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
    entry.async_on_unload(entry.add_update_listener(_async_options_updated))

    if entry.runtime_data.app.adb_entity or entry.runtime_data.app.control_url:
        # Ask the app for its current settings so the controls show real values.
        async def _ask_status(*_: Any) -> None:
            try:
                await entry.runtime_data.app.request_status()
            except Exception as err:  # noqa: BLE001 - the TV may simply be asleep
                _LOGGER.debug("Could not ask the TV app for its status: %s", err)

        if hass.state is CoreState.running:
            hass.async_create_background_task(_ask_status(), "music_light_synk_status")
        else:
            entry.async_on_unload(hass.bus.async_listen_once("homeassistant_started", _ask_status))

    _LOGGER.debug("Music Light Synk listening on webhook %s", webhook_id)
    return True


def _remember_control(hass: HomeAssistant, entry: MusicLightSynkConfigEntry, control: Any) -> None:
    """The app says where it can be controlled over Wi-Fi: keep it (survives restarts)."""
    if not isinstance(control, dict):
        return
    url, token = control.get("url"), control.get("token")
    if not valid_control_url(url) or not isinstance(token, str) or not token:
        return
    if entry.data.get(CONF_CONTROL_URL) == url and entry.data.get(CONF_CONTROL_TOKEN) == token:
        return
    _LOGGER.debug("App reachable over Wi-Fi at %s", url)
    hass.config_entries.async_update_entry(entry, data={**entry.data, CONF_CONTROL_URL: url, CONF_CONTROL_TOKEN: token})


async def _async_options_updated(hass: HomeAssistant, entry: MusicLightSynkConfigEntry) -> None:
    """Light options apply live; a new TV (ADB entity) needs a reload to re-check the controls."""
    if entry.options.get(CONF_ADB_ENTITY) != entry.runtime_data.adb_entity:
        await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: MusicLightSynkConfigEntry) -> bool:
    """Unload a config entry."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
