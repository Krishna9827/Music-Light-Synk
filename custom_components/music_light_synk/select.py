"""Colour source of the TV app: album art (media access), screen capture, or auto."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import MusicLightSynkConfigEntry
from .const import SOURCE_MODES
from .entity import TvAppEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MusicLightSynkConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create the source mode select."""
    async_add_entities([SourceModeSelect(entry)])


class SourceModeSelect(TvAppEntity, SelectEntity):
    """Switch between album art (media access) and screen capture."""

    _attr_icon = "mdi:image-filter-center-focus"
    _attr_name = "Colour source mode"
    _attr_translation_key = "source_mode"
    _attr_options = SOURCE_MODES

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "source_mode")

    @property
    def current_option(self) -> str | None:
        value = self._entry.runtime_data.status.get("source_mode")
        return value if value in SOURCE_MODES else None

    async def async_select_option(self, option: str) -> None:
        await self._entry.runtime_data.app.configure(source=option)
        self._entry.runtime_data.status["source_mode"] = option
        self.async_write_ha_state()
