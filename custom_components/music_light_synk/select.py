"""TV app choices: colour source (album art / screen capture / auto) and audio source."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import MusicLightSynkConfigEntry
from .const import AUDIO_SOURCE_LABELS, AUDIO_SOURCES, SOURCE_MODES
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
    """What Energy strips, the beat pulse and beat sync listen to. The app lists the choices,
    including other devices' microphones by name (e.g. "Pixel 8's microphone (Wi-Fi)")."""

    _attr_icon = "mdi:microphone-settings"
    _attr_name = "Audio source"

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "audio_source")

    def _sources(self) -> list[tuple[str, str]]:
        raw = self._entry.runtime_data.status.get("audio_sources")
        pairs: list[tuple[str, str]] = []
        if isinstance(raw, list):
            for item in raw:
                if isinstance(item, dict) and item.get("key") and item.get("label"):
                    pairs.append((str(item["key"]), str(item["label"])))
        return pairs or [(k, AUDIO_SOURCE_LABELS[k]) for k in AUDIO_SOURCES]

    @property
    def options(self) -> list[str]:
        return [label for _, label in self._sources()]

    @property
    def current_option(self) -> str | None:
        key = self._entry.runtime_data.setting("audio_source")
        return next((label for k, label in self._sources() if k == key), None)

    async def async_select_option(self, option: str) -> None:
        key = next((k for k, label in self._sources() if label == option), None)
        if key is None:
            raise HomeAssistantError(f"Unknown audio source: {option}")
        await self._send(audio_source=key)
