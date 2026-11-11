"""Text settings of the TV app: the WLED device list and per-app profiles."""

from __future__ import annotations

from homeassistant.components.text import TextEntity
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import MusicLightSynkConfigEntry
from .const import APP_TEXTS, AppSetting
from .entity import TvAppEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MusicLightSynkConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create one text entity per app setting."""
    async_add_entities([AppSettingText(entry, setting) for setting in APP_TEXTS])


class AppSettingText(TvAppEntity, TextEntity):
    """A free-text setting of the TV app (see the README for the format)."""

    _attr_entity_category = EntityCategory.CONFIG
    _attr_native_max = 255

    def __init__(self, entry: MusicLightSynkConfigEntry, setting: AppSetting) -> None:
        super().__init__(entry, f"app_{setting.key}")
        self._setting = setting
        self._attr_name = setting.name
        self._attr_icon = setting.icon

    @property
    def native_value(self) -> str | None:
        value = self._entry.runtime_data.setting(self._setting.key)
        return None if value is None else str(value)

    async def async_set_value(self, value: str) -> None:
        await self._send(**{self._setting.key: value})
