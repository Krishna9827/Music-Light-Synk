"""Constants for Music Light Synk."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

DOMAIN = "music_light_synk"
DEFAULT_WEBHOOK_ID = "music_light_synk"
FALLBACK_WEBHOOK_ID = "music_light_synk_entities"
DEFAULT_NAME = "Music Light Synk"

# Fired on the event bus for every payload the TV app sends (colors / stopped / test).
EVENT_NAME = "music_light_synk_event"

# (payload key, entity name) - same order as the TV app's preview grid.
SLOTS: list[tuple[str, str]] = [
    ("top_left", "Top left"),
    ("top", "Top"),
    ("top_right", "Top right"),
    ("left", "Left"),
    ("right", "Right"),
    ("bottom_left", "Bottom left"),
    ("bottom", "Bottom"),
    ("bottom_right", "Bottom right"),
    ("dominant", "Dominant"),
    ("vibrant", "Vibrant"),
    ("average", "Average"),
]

# Zones that can drive lights (average is informational only).
LIGHT_SLOTS: list[str] = [key for key, _ in SLOTS if key != "average"]


def lights_key(slot: str) -> str:
    """Option key holding the light entity ids for a zone."""
    return f"{slot}_lights"


CONF_BRIGHTNESS_MODE = "brightness_mode"
CONF_FIXED_BRIGHTNESS = "fixed_brightness"
CONF_MAX_BRIGHTNESS = "max_brightness"
CONF_TRANSITION = "transition"
CONF_EFFECT_MODE = "effect_mode"
CONF_EFFECT_NAME = "effect_name"
CONF_PALETTE_SELECTS = "palette_selects"
CONF_PALETTE_OPTION = "palette_option"
CONF_ON_STOP = "on_stop"

DEFAULT_OPTIONS: dict[str, Any] = {
    **{lights_key(slot): [] for slot in LIGHT_SLOTS},
    CONF_BRIGHTNESS_MODE: "follow",
    CONF_FIXED_BRIGHTNESS: 80,
    CONF_MAX_BRIGHTNESS: 100,
    CONF_TRANSITION: 1,
    CONF_EFFECT_MODE: "solid",
    CONF_EFFECT_NAME: "Running",
    CONF_PALETTE_SELECTS: [],
    CONF_PALETTE_OPTION: "* Color 1",
    CONF_ON_STOP: "restore",
}

EFFECT_SUGGESTIONS = [
    "Running", "Breathe", "Fade", "Scan", "Theater", "Twinkle", "Sparkle",
    "Chase", "Meteor", "Candle", "Heartbeat", "Ripple", "Glitter",
]

# Effect names that mean "no effect" on common integrations (WLED, Hue, demo, ...).
SOLID_EFFECT_NAMES = ("solid", "none", "off", "static", "normal")

# Rainbow test pattern (same as the TV app's TEST): red, orange, yellow, pink, green,
# indigo, blue, cyan around the edge and purple in the middle.
TEST_COLORS: dict[str, dict[str, Any]] = {
    "top_left": {"hs": [1.4, 99.9], "brightness": 233},
    "top": {"hs": [33.5, 100.0], "brightness": 252},
    "top_right": {"hs": [48.9, 100.0], "brightness": 253},
    "left": {"hs": [338.1, 100.0], "brightness": 222},
    "right": {"hs": [122.6, 75.6], "brightness": 174},
    "bottom_left": {"hs": [231.6, 86.7], "brightness": 184},
    "bottom": {"hs": [208.0, 100.0], "brightness": 233},
    "bottom_right": {"hs": [186.5, 100.0], "brightness": 202},
    "dominant": {"hs": [287.5, 100.0], "brightness": 183},
    "vibrant": {"hs": [33.5, 100.0], "brightness": 252},
}


def signal_update(entry_id: str) -> str:
    """Dispatcher signal for one config entry."""
    return f"{DOMAIN}_{entry_id}_update"


# ---- TV app control through Home Assistant's Android Debug Bridge integration ---------------

CONF_ADB_ENTITY = "adb_entity"

APP_PACKAGE = "com.musiclightsynk"
APP_RECEIVER = f"{APP_PACKAGE}/.AdbCommandReceiver"
APP_LISTENER = f"{APP_PACKAGE}/{APP_PACKAGE}.MediaListenerService"

SOURCE_MODES = ["auto", "artwork", "screen"]


@dataclass(frozen=True)
class AppSetting:
    """One TV app setting: its key in the app (= ADB extra = status.settings key)."""

    key: str
    name: str
    icon: str
    min: float = 0
    max: float = 100
    step: float = 1
    unit: str | None = None
    config: bool = True  # shown under the device's "Configuration" section


APP_SWITCHES: list[AppSetting] = [
    AppSetting("enabled", "TV syncing", "mdi:sync", config=False),
    AppSetting("pulse", "Beat pulse", "mdi:pulse", config=False),
    AppSetting("wled_enabled", "WLED output", "mdi:led-strip-variant"),
    AppSetting("wled_restore", "Restore WLED when music stops", "mdi:restore"),
    AppSetting("boot_capture", "Restart screen capture after reboot", "mdi:restart"),
    AppSetting("mqtt_enabled", "MQTT output", "mdi:lan-connect"),
]

APP_NUMBERS: list[AppSetting] = [
    AppSetting("corner_pct", "Edge / corner size", "mdi:border-outside", 10, 50, 5, "%"),
    AppSetting("saturation_pct", "Saturation boost", "mdi:palette", 50, 250, 10, "%"),
    AppSetting("min_brightness_pct", "Minimum brightness", "mdi:brightness-4", 1, 60, 1, "%"),
    AppSetting("screen_interval", "Re-sample screen every", "mdi:timer-refresh-outline", 0, 120, 5, "s"),
    AppSetting("stop_delay", "Send 'stopped' after a pause of", "mdi:timer-pause-outline", 0, 300, 5, "s"),
    AppSetting("wled_brightness_pct", "WLED brightness", "mdi:brightness-6", 10, 100, 5, "%"),
    AppSetting("pulse_strength", "Pulse strength", "mdi:waveform", 10, 100, 5, "%"),
]

APP_TEXTS: list[AppSetting] = [
    AppSetting("wled_devices", "WLED devices", "mdi:led-strip"),
    AppSetting("profiles", "Per-app profiles", "mdi:application-cog"),
]
