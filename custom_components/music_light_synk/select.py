"""TV app choices: colour source (album art / screen capture / auto) and audio source."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import MusicLightSynkConfigEntry
from .const import AUDIO_SOURCES, SOURCE_MODES
from .entity import TvAppEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MusicLightSynkConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create the source mode and audio source selects."""
    async_add_entities([SourceModeSelect(entry), AudioSourceSelect(entry)])


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


class AudioSourceSelect(TvAppEntity, SelectEntity):
    """What Energy strips and the beat pulse listen to: device audio, the microphone, or nothing."""

    _attr_icon = "mdi:microphone-settings"
    _attr_name = "Audio source"
    _attr_translation_key = "audio_source"
    _attr_options = AUDIO_SOURCES

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "audio_source")

    @property
    def current_option(self) -> str | None:
        value = self._entry.runtime_data.setting("audio_source")
        return value if value in AUDIO_SOURCES else None

    async def async_select_option(self, option: str) -> None:
        await self._send(audio_source=option)
