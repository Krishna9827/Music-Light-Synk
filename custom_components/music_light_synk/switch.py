"""'Light sync' switch: pause the integration's light control without touching the app."""

from __future__ import annotations

from typing import Any

from homeassistant.components.switch import SwitchEntity
from homeassistant.const import STATE_OFF
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
    """Create the light sync switch."""
    async_add_entities([LightSyncSwitch(entry)])


class LightSyncSwitch(MusicLightSynkEntity, SwitchEntity, RestoreEntity):
    """On: colours go to the lights picked in the options. Off: lights are given back."""

    _attr_icon = "mdi:lightbulb-auto"
    _attr_name = "Light sync"

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "light_sync")

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        if (last := await self.async_get_last_state()) is not None:
            self._entry.runtime_data.lights.enabled = last.state != STATE_OFF

    @property
    def is_on(self) -> bool:
        return self._entry.runtime_data.lights.enabled

    async def async_turn_on(self, **kwargs: Any) -> None:
        self._entry.runtime_data.lights.enabled = True
        self.async_write_ha_state()

    async def async_turn_off(self, **kwargs: Any) -> None:
        self.async_write_ha_state()
        await self._entry.runtime_data.lights.async_disable()
        self.async_write_ha_state()

    def _update_from_data(self) -> bool:
        return False
