"""The app's WLED strips, as Home Assistant controls.

The app reports its WLED list in its status (``status["wled"]``: host, zone, energy / flip /
mirror, and ``spec`` = that strip's entry in the app's ``wled_devices`` setting). Every change made
here rewrites one strip's entry and sends the whole list back, so the app stays the single source
of truth. The spec format is documented in the README ("WLED direct").
"""

from __future__ import annotations

from collections.abc import Callable
import re
from typing import Any

from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity
from homeassistant.helpers.entity_platform import AddEntitiesCallback

# (zone key in the app, label) - the "part" a strip shows.
ZONES: list[tuple[str, str]] = [
    ("top_left", "Top-left corner"),
    ("top", "Top edge"),
    ("top_right", "Top-right corner"),
    ("left", "Left edge"),
    ("right", "Right edge"),
    ("bottom_left", "Bottom-left corner"),
    ("bottom", "Bottom edge"),
    ("bottom_right", "Bottom-right corner"),
    ("dominant", "Dominant colour"),
    ("vibrant", "Vibrant colour"),
    ("average", "Average colour"),
    ("palette", "WLED's own effect in album colours"),
    ("ambient", "Ambient gradient (strip around the TV)"),
]
ZONE_LABELS = dict(ZONES)
FLAGS = ("energy", "flip", "mirror")
# Used when a strip becomes an ambient gradient: LED counts top,right,bottom,left; LED 0; direction.
DEFAULT_AMBIENT = ["40,22,40,22", "bottom_left", "cw"]
_EFFECT_TOKENS = {"energy", "flip", "mirror"}


def strips(status: dict[str, Any]) -> list[dict[str, Any]]:
    """The strips from the app's status (only well-formed ones)."""
    raw = status.get("wled")
    if not isinstance(raw, list):
        return []
    return [s for s in raw if isinstance(s, dict) and isinstance(s.get("host"), str) and isinstance(s.get("spec"), str)]


def slug(host: str) -> str:
    """Stable id part for a host, e.g. 192.168.1.60 -> 192_168_1_60."""
    return re.sub(r"[^a-z0-9]+", "_", host.lower()).strip("_")


def _split(spec: str) -> tuple[str, str, list[str]]:
    host, _, rest = spec.partition("=")
    parts = [p.strip() for p in rest.split(":") if p.strip()]
    zone = parts[0].lower() if parts else "dominant"
    return host.strip(), zone, parts[1:]


def set_zone(spec: str, zone: str) -> str:
    """Same strip showing another part. Effect options carry over (not to 'palette', which runs WLED's own effect)."""
    host, old, opts = _split(spec)
    if zone == old:
        return spec
    keep = [o for o in opts if o.lower() in _EFFECT_TOKENS or o.lower().startswith("leds=")]
    zone_opts = DEFAULT_AMBIENT if zone == "ambient" else []
    if zone == "palette":
        keep = []
    return f"{host}=" + ":".join([zone, *zone_opts, *keep])


def set_flag(spec: str, flag: str, on: bool) -> str:
    """Energy / flip / mirror on or off for one strip."""
    host, zone, opts = _split(spec)
    opts = [o for o in opts if o.lower() != flag]
    if on:
        opts.append(flag)
    return f"{host}=" + ":".join([zone, *opts])


def replace_spec(all_strips: list[dict[str, Any]], host: str, new_spec: str) -> str:
    """The app's whole device list with one strip's entry replaced."""
    return "; ".join(new_spec if s["host"] == host else s["spec"] for s in all_strips)


def wled_hosts(hass: HomeAssistant) -> dict[str, str]:
    """Hosts of the WLED devices Home Assistant knows (WLED integration) -> their names."""
    out: dict[str, str] = {}
    for entry in hass.config_entries.async_entries("wled"):
        host = entry.data.get("host")
        if isinstance(host, str) and host:
            out[host] = entry.title
    return out


def strip_name(hass: HomeAssistant, host: str) -> str:
    """'Living room strip' if HA's WLED integration knows the host, else the host itself."""
    return wled_hosts(hass).get(host, host)


def track_strips(
    hass: HomeAssistant,
    entry: Any,
    async_add_entities: AddEntitiesCallback,
    make: Callable[[str], list[Entity]],
) -> None:
    """Add entities for every strip the app reports, and remove them when the strip goes."""
    from .const import signal_update  # noqa: PLC0415 - avoid an import cycle at module load

    known: dict[str, list[Entity]] = {}

    @callback
    def _sync() -> None:
        status = entry.runtime_data.status
        if "wled" not in status:  # no report yet (or an app older than v0.9)
            return
        hosts = [s["host"] for s in strips(status)]
        added: list[Entity] = []
        for host in hosts:
            if host not in known:
                known[host] = make(host)
                added += known[host]
        if added:
            async_add_entities(added)
        registry = er.async_get(hass)
        for host in [h for h in known if h not in hosts]:
            for entity in known.pop(host):
                if entity.entity_id and registry.async_get(entity.entity_id):
                    registry.async_remove(entity.entity_id)

    entry.async_on_unload(async_dispatcher_connect(hass, signal_update(entry.entry_id), _sync))
    _sync()
