"""Colour and now-playing sensors."""

from __future__ import annotations

from typing import Any

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity
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
    entities.append(ColourSourceSensor(entry))
    entities.append(AudioSensor(entry))
    entities.append(WledDriverSensor(entry))
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


class ColourSourceSensor(MusicLightSynkEntity, SensorEntity, RestoreEntity):
    """Where the colours come from right now: album art (media access) or screen capture."""

    _attr_icon = "mdi:palette-swatch"
    _attr_name = "Colour source"
    _attr_translation_key = "colour_source"
    _attr_device_class = SensorDeviceClass.ENUM
    _attr_options = ["artwork", "screen", "test", "none"]

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "colour_source")
        self._attr_native_value: str | None = None
        self._attr_extra_state_attributes: dict[str, Any] = {}

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        if not self._update_from_data() and (last := await self.async_get_last_state()):
            if last.state in self._attr_options:
                self._attr_native_value = last.state

    def _update_from_data(self) -> bool:
        data = self._entry.runtime_data
        source = data.source or data.status.get("last_source")
        if source is None and not data.status:
            return False
        self._attr_native_value = source if source in self._attr_options else "none"
        st = data.status
        self._attr_extra_state_attributes = {
            k: st[k] for k in ("source_mode", "media_access", "screen_capture", "app_version") if k in st
        }
        return True


class AudioSensor(MusicLightSynkEntity, SensorEntity):
    """What the app listens to right now (for Energy strips / beat pulse / other devices)."""

    _attr_icon = "mdi:waveform"
    _attr_name = "Audio"
    _attr_translation_key = "audio"
    _attr_device_class = SensorDeviceClass.ENUM
    _attr_options = ["output", "mic", "none"]

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "audio")
        self._attr_native_value: str | None = None
        self._attr_extra_state_attributes: dict[str, Any] = {}

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self._update_from_data()

    def _update_from_data(self) -> bool:
        st = self._entry.runtime_data.status
        if not st:
            return False
        audio = st.get("audio")
        self._attr_native_value = audio if audio in ("output", "mic") else "none"
        self._attr_extra_state_attributes = {
            "audio_source": self._entry.runtime_data.setting("audio_source"),
            "backup_listener": bool(st.get("listening")),
        }
        return True


class WledDriverSensor(MusicLightSynkEntity, SensorEntity):
    """Which device drives the WLED strips (the app on several devices takes turns)."""

    _attr_icon = "mdi:led-strip-variant"
    _attr_name = "WLED driven by"

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "wled_driver")
        self._attr_native_value: str | None = None
        self._attr_extra_state_attributes: dict[str, Any] = {}

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self._update_from_data()

    def _update_from_data(self) -> bool:
        st = self._entry.runtime_data.status
        if not st:
            return False
        self._attr_native_value = st.get("wled_renderer") or "Nobody"
        peers = st.get("link_peers")
        self._attr_extra_state_attributes = {
            "linked_devices": peers if isinstance(peers, list) else [],
            "device_link": self._entry.runtime_data.setting("link_enabled"),
        }
        return True
