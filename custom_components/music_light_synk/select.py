"""Choices: what the device does (Mode), colour source, audio source, the music effect on Home
Assistant lights, and which part of the artwork each of the app's WLED strips shows."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import MusicLightSynkConfigEntry
from .const import APP_HA_MUSIC, AUDIO_SOURCE_LABELS, AUDIO_SOURCES, CONF_MUSIC_STYLE, MODES, MUSIC_EFFECTS, SOURCE_MODES
from .entity import TvAppEntity, WledStripEntity
from .wled_strips import ZONE_LABELS, ZONES, set_zone, track_strips


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MusicLightSynkConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Create the selects; one more per WLED strip as the app reports them."""
    async_add_entities([ModeSelect(entry), MusicEffectSelect(entry), SourceModeSelect(entry), AudioSourceSelect(entry)])
    track_strips(hass, entry, async_add_entities, lambda host: [WledPartSelect(entry, host)])


class ModeSelect(TvAppEntity, SelectEntity):
    """What the device does: its own lights, lending its microphone, both, or nothing."""

    _attr_icon = "mdi:account-music"
    _attr_name = "Mode"
    _attr_translation_key = "mode"

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "mode")

    @property
    def options(self) -> list[str]:
        modes = self._entry.runtime_data.status.get("modes")
        return [m for m in MODES if isinstance(modes, list) and m in modes] or ["lights", "off"]

    @property
    def current_option(self) -> str | None:
        mode = self._entry.runtime_data.setting("mode")
        if mode is None and (enabled := self._entry.runtime_data.setting("enabled")) is not None:
            mode = "lights" if enabled else "off"  # app older than v0.9
        return mode if mode in self.options else None

    async def async_select_option(self, option: str) -> None:
        await self._send(mode=option)


class MusicEffectSelect(TvAppEntity, SelectEntity):
    """The music effect on the Home Assistant lights picked under Configure -> Music effect."""

    _attr_icon = "mdi:music-circle"
    _attr_name = "Music effect"
    _attr_translation_key = "music_effect"
    _attr_options = MUSIC_EFFECTS

    def __init__(self, entry: MusicLightSynkConfigEntry) -> None:
        super().__init__(entry, "music_effect")

    @property
    def current_option(self) -> str | None:
        data = self._entry.runtime_data
        sent = data.setting("ha_music")
        if sent is None and data.setting("ha_beats") is not None:
            sent = "beat" if data.setting("ha_beats") else "off"  # app v0.8
        if sent == "level":
            return self._entry.options.get(CONF_MUSIC_STYLE, "energy")
        return sent if sent in ("off", "beat") else None

    async def async_select_option(self, option: str) -> None:
        if option in ("energy", "bars") and self._entry.options.get(CONF_MUSIC_STYLE) != option:
            self.hass.config_entries.async_update_entry(self._entry, options={**self._entry.options, CONF_MUSIC_STYLE: option})
        await self._send(ha_music=APP_HA_MUSIC[option])


class WledPartSelect(WledStripEntity, SelectEntity):
    """Which part of the artwork one of the app's WLED strips shows (or the ambient gradient)."""

    _attr_icon = "mdi:led-strip-variant"
    _attr_options = [label for _, label in ZONES]

    def __init__(self, entry: MusicLightSynkConfigEntry, host: str) -> None:
        super().__init__(entry, host, "part", "part")

    @property
    def current_option(self) -> str | None:
        strip = self.strip
        return ZONE_LABELS.get(strip.get("zone")) if strip else None

    async def async_select_option(self, option: str) -> None:
        zone = next((k for k, label in ZONES if label == option), None)
        strip = self.strip
        if zone is None or strip is None:
            raise HomeAssistantError(f"Unknown part: {option}")
        await self._set_spec(set_zone(strip["spec"], zone))
        strip["zone"] = zone
        self.async_write_ha_state()


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
