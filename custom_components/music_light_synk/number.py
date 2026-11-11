"""Number settings of the TV app (edge size, saturation, delays, WLED brightness, ...)."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import MusicLightSynkConfigEntry
from .const import APP_NUMBERS, AppSetting
from .entity import TvAppEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MusicLightSynkConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create one number per app setting."""
    async_add_entities([AppSettingNumber(entry, setting) for setting in APP_NUMBERS])


class AppSettingNumber(TvAppEntity, NumberEntity):
    """A numeric setting of the TV app."""

    _attr_entity_category = EntityCategory.CONFIG
    _attr_mode = NumberMode.BOX

    def __init__(self, entry: MusicLightSynkConfigEntry, setting: AppSetting) -> None:
        super().__init__(entry, f"app_{setting.key}")
        self._setting = setting
        self._attr_name = setting.name
        self._attr_icon = setting.icon
        self._attr_native_min_value = setting.min
        self._attr_native_max_value = setting.max
        self._attr_native_step = setting.step
        self._attr_native_unit_of_measurement = setting.unit

    @property
    def native_value(self) -> float | None:
        value = self._entry.runtime_data.setting(self._setting.key)
        return None if value is None else float(value)

    async def async_set_native_value(self, value: float) -> None:
        await self._send(**{self._setting.key: int(round(value))})
