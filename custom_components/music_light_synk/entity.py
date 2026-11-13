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
    """An app setting/command (sent over Wi-Fi or ADB); its state comes from the app's status reports."""

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


class WledStripEntity(TvAppEntity):
    """A control for one of the app's WLED strips (identified by its host)."""

    def __init__(self, entry: MusicLightSynkConfigEntry, host: str, kind: str, label: str) -> None:
        from .wled_strips import slug, strip_name  # noqa: PLC0415

        super().__init__(entry, f"wled_{slug(host)}_{kind}")
        self._host = host
        self._attr_name = f"WLED {strip_name(entry.runtime_data.lights.hass, host)}: {label}"

    @property
    def strip(self) -> dict[str, Any] | None:
        from .wled_strips import strips  # noqa: PLC0415

        return next((s for s in strips(self._entry.runtime_data.status) if s["host"] == self._host), None)

    @property
    def available(self) -> bool:
        return super().available and self.strip is not None

    async def _set_spec(self, new_spec: str) -> None:
        from .wled_strips import replace_spec, strips  # noqa: PLC0415

        data = self._entry.runtime_data
        all_strips = strips(data.status)
        devices = replace_spec(all_strips, self._host, new_spec)
        await data.app.configure(wled_devices=devices)
        # Optimistic: show the change until the app's next report confirms it.
        for s in all_strips:
            if s["host"] == self._host:
                s["spec"] = new_spec
        data.set_setting("wled_devices", devices)
