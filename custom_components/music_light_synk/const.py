"""Constants for Music Light Synk."""

DOMAIN = "music_light_synk"
DEFAULT_WEBHOOK_ID = "music_light_synk_entities"
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


def signal_update(entry_id: str) -> str:
    """Dispatcher signal for one config entry."""
    return f"{DOMAIN}_{entry_id}_update"
