"""Shared base entity."""

from __future__ import annotations

from homeassistant.core import callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity

from . import MusicLightSynkConfigEntry
from .const import DOMAIN, signal_update


class MusicLightSynkEntity(Entity):
    """Push-updated entity belonging to one TV."""

    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, entry: MusicLightSynkConfigEntry, key: str) -> None:
        self._entry = entry
        self._attr_unique_id = f"{entry.entry_id}_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            name=entry.title,
            manufacturer="Music Light Synk",
            model="Android TV app",
        )

    async def async_added_to_hass(self) -> None:
        """Subscribe to updates from the webhook."""
        await super().async_added_to_hass()
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, signal_update(self._entry.entry_id), self._handle_update
            )
        )

    @callback
    def _handle_update(self) -> None:
        if self._update_from_data():
            self.async_write_ha_state()

    def _update_from_data(self) -> bool:
        """Pull new values from runtime data. Return True if anything changed."""
        raise NotImplementedError
