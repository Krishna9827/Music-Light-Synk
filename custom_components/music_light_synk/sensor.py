"""Colour and now-playing sensors."""

from __future__ import annotations

from typing import Any

from homeassistant.components.sensor import SensorEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.restore_state import RestoreEntity

from . import MusicLightSynkConfigEntry
from .const import SLOTS
from .entity import MusicLightSynkEntity

COLOR_ATTRS = ("rgb", "hs", "brightness", "brightness_pct", "raw_hex", "raw_rgb")
TRACK_ATTRS = ("app", "package", "artist", "album", "source", "device", "event")


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MusicLightSynkConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create one sensor per colour slot plus a now-playing sensor."""
    entities: list[SensorEntity] = [ColorSensor(entry, key, name) for key, name in SLOTS]
    entities.append(NowPlayingSensor(entry))
    async_add_entities(entities)


class ColorSensor(MusicLightSynkEntity, SensorEntity, RestoreEntity):
    """State = hex colour for bulbs; attributes = rgb / hs / brightness for automations."""

    _attr_icon = "mdi:palette"

    def __init__(self, entry: MusicLightSynkConfigEntry, key: str, name: str) -> None:
        super().__init__(entry, key)
        self._key = key
        self._attr_name = f"{name} colour"
        self._attr_native_value: str | None = None
        self._attr_extra_state_attributes: dict[str, Any] = {}

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        if not self._update_from_data() and (last := await self.async_get_last_state()):
            if last.state not in ("unknown", "unavailable"):
                self._attr_native_value = last.state
                self._attr_extra_state_attributes = {
                    k: v for k, v in last.attributes.items() if k in COLOR_ATTRS
                }

    def _update_from_data(self) -> bool:
        color = self._entry.runtime_data.colors.get(self._key)
        if not isinstance(color, dict) or "hex" not in color:
            return False
        self._attr_native_value = color["hex"]
        self._attr_extra_state_attributes = {k: color[k] for k in COLOR_ATTRS if k in color}
        return True


class NowPlayingSensor(MusicLightSynkEntity, SensorEntity, RestoreEntity):
    """Title of what's playing, or 'idle'."""

    _attr_icon = "mdi:music"
    _attr_name = "Now playing"

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "now_playing")
        self._attr_native_value: str | None = None
        self._attr_extra_state_attributes: dict[str, Any] = {}

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        if not self._update_from_data() and (last := await self.async_get_last_state()):
            if last.state not in ("unknown", "unavailable"):
                self._attr_native_value = last.state
                self._attr_extra_state_attributes = {
                    k: v for k, v in last.attributes.items() if k in TRACK_ATTRS
                }

    def _update_from_data(self) -> bool:
        data = self._entry.runtime_data
        payload = data.payload
        if not payload or payload.get("event") == "test":
            return False
        if data.playing is False:
            value = "idle"
        else:
            value = str(payload.get("title") or "playing")[:255]
        self._attr_native_value = value
        self._attr_extra_state_attributes = {
            k: payload[k] for k in TRACK_ATTRS if payload.get(k) is not None
        }
        return True
