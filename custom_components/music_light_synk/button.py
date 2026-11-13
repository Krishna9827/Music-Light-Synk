"""'Test lights' button: rainbow on the configured lights for a few seconds."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import MusicLightSynkConfigEntry
from .entity import MusicLightSynkEntity, TvAppEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MusicLightSynkConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create the test button."""
    async_add_entities(
        [
            TestLightsButton(entry),
            TvCommandButton(entry, "grant_permissions", "Grant TV permissions", "mdi:shield-check", "grant_permissions"),
            TvCommandButton(entry, "refresh_status", "Refresh TV app status", "mdi:refresh", "request_status"),
            TvCommandButton(entry, "tv_test", "Send test colours from TV", "mdi:television-play", "send_test"),
        ]
    )


class TestLightsButton(MusicLightSynkEntity, ButtonEntity):
    """Shows each zone's test colour on its lights for 6 s, then restores them."""

    _attr_icon = "mdi:palette-swatch-variant"
    _attr_name = "Test lights"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "test_lights")

    async def async_press(self) -> None:
        self._entry.runtime_data.lights.test()

    def _update_from_data(self) -> bool:
        return False


class TvCommandButton(TvAppEntity, ButtonEntity):
    """Runs one command on the app (over Wi-Fi, or ADB on a Fire TV)."""

    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, entry: MusicLightSynkConfigEntry, key: str, name: str, icon: str, method: str) -> None:
        super().__init__(entry, key)
        self._attr_name = name
        self._attr_icon = icon
        self._method = method

    @property
    def available(self) -> bool:
        app = self._entry.runtime_data.app
        # Android only lets the ADB shell grant these permissions.
        return app.adb_available if self._method == "grant_permissions" else app.available

    async def async_press(self) -> None:
        await getattr(self._entry.runtime_data.app, self._method)()
