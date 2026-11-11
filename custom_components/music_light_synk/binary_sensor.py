"""Is music playing on the TV?"""

from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.const import STATE_ON
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.restore_state import RestoreEntity

from . import MusicLightSynkConfigEntry
from .entity import MusicLightSynkEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MusicLightSynkConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create the playing binary sensor."""
    async_add_entities(
        [
            PlayingSensor(entry),
            StatusFlagSensor(entry, "media_access", "Media access", "mdi:music-box-multiple", None),
            StatusFlagSensor(entry, "screen_capture", "Screen capture", "mdi:monitor-screenshot", BinarySensorDeviceClass.RUNNING),
        ]
    )


class PlayingSensor(MusicLightSynkEntity, BinarySensorEntity, RestoreEntity):
    """On while the app is sending colours, off after it reports 'stopped'."""

    _attr_icon = "mdi:play-circle"
    _attr_name = "Playing"

    def __init__(self, entry) -> None:
        super().__init__(entry, "playing")
        self._attr_is_on: bool | None = None

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        if not self._update_from_data() and (last := await self.async_get_last_state()):
            self._attr_is_on = last.state == STATE_ON

    def _update_from_data(self) -> bool:
        playing = self._entry.runtime_data.playing
        if playing is None:
            return False
        self._attr_is_on = playing
        return True


class StatusFlagSensor(MusicLightSynkEntity, BinarySensorEntity, RestoreEntity):
    """A yes/no from the TV app's status report (media access granted, screen capture running)."""

    def __init__(self, entry, key: str, name: str, icon: str, device_class) -> None:
        super().__init__(entry, key)
        self._key = key
        self._attr_name = name
        self._attr_icon = icon
        self._attr_device_class = device_class
        self._attr_is_on: bool | None = None

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        if not self._update_from_data() and (last := await self.async_get_last_state()):
            if last.state in ("on", "off"):
                self._attr_is_on = last.state == STATE_ON

    def _update_from_data(self) -> bool:
        value = self._entry.runtime_data.status.get(self._key)
        if value is None:
            return False
        self._attr_is_on = bool(value)
        if self._key == "media_access":
            self._attr_extra_state_attributes = {
                "connected": self._entry.runtime_data.status.get("media_connected")
            }
        return True
