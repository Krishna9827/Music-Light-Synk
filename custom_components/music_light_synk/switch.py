"""'Light sync' switch: pause the integration's light control without touching the app."""

from __future__ import annotations

from typing import Any

from homeassistant.components.switch import SwitchEntity
from homeassistant.const import STATE_OFF, EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.restore_state import RestoreEntity

from . import MusicLightSynkConfigEntry
from .const import APP_SWITCHES, AppSetting
from .entity import MusicLightSynkEntity, TvAppEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MusicLightSynkConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create the light sync switch."""
    entities: list[SwitchEntity] = [LightSyncSwitch(entry), ScreenCaptureSwitch(entry)]
    entities += [AppSettingSwitch(entry, setting) for setting in APP_SWITCHES]
    async_add_entities(entities)


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


class ScreenCaptureSwitch(TvAppEntity, SwitchEntity):
    """Start / stop the app's screen capture (used for apps without album art)."""

    _attr_icon = "mdi:monitor-screenshot"
    _attr_name = "Screen capture"

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "screen_capture_switch")

    @property
    def is_on(self) -> bool | None:
        value = self._entry.runtime_data.status.get("screen_capture")
        return None if value is None else bool(value)

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._entry.runtime_data.app.start_screen_capture()
        self._entry.runtime_data.status["screen_capture"] = True
        self.async_write_ha_state()

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._entry.runtime_data.app.stop_screen_capture()
        self._entry.runtime_data.status["screen_capture"] = False
        self.async_write_ha_state()


class AppSettingSwitch(TvAppEntity, SwitchEntity):
    """An on/off setting of the TV app."""

    def __init__(self, entry: MusicLightSynkConfigEntry, setting: AppSetting) -> None:
        super().__init__(entry, f"app_{setting.key}")
        self._setting = setting
        self._attr_name = setting.name
        self._attr_icon = setting.icon
        if setting.config:
            self._attr_entity_category = EntityCategory.CONFIG

    @property
    def is_on(self) -> bool | None:
        value = self._entry.runtime_data.setting(self._setting.key)
        return None if value is None else bool(value)

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._send(**{self._setting.key: True})

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._send(**{self._setting.key: False})
