"""Shared base entity."""

from __future__ import annotations

from typing import Any

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


class TvAppEntity(MusicLightSynkEntity):
    """A TV app setting/command sent through ADB; its state comes from the app's status reports."""

    @property
    def available(self) -> bool:
        return self._entry.runtime_data.app.available

    def _update_from_data(self) -> bool:
        return True

    async def _send(self, **values: Any) -> None:
        data = self._entry.runtime_data
        await data.app.configure(**values)
        for key, value in values.items():
            data.set_setting(key, value)
        self.async_write_ha_state()
