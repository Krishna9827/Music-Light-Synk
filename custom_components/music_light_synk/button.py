"""'Test lights' button: rainbow on the configured lights for a few seconds."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import MusicLightSynkConfigEntry
from .entity import MusicLightSynkEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MusicLightSynkConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create the test button."""
    async_add_entities([TestLightsButton(entry)])


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
