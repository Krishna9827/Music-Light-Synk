"""Is music playing on the TV?"""

from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorEntity
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
    async_add_entities([PlayingSensor(entry)])


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
