"""Apply the TV app's colours to the lights picked in the integration's options.

Same behaviour as the blueprint, without needing one: every zone is updated in parallel,
running effects are switched to solid (or to a chosen effect), and when the music stops the
lights are restored to how they were before it started (or turned off).
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable, Mapping
import logging
from typing import Any

from homeassistant.components.light import (
    ATTR_BRIGHTNESS,
    ATTR_EFFECT,
    ATTR_EFFECT_LIST,
    ATTR_HS_COLOR,
    ATTR_TRANSITION,
    DOMAIN as LIGHT_DOMAIN,
)
from homeassistant.const import ATTR_ENTITY_ID, STATE_UNAVAILABLE, STATE_UNKNOWN
from homeassistant.core import HomeAssistant, State
from homeassistant.helpers.state import async_reproduce_state

from .const import (
    CONF_BRIGHTNESS_MODE,
    CONF_EFFECT_MODE,
    CONF_EFFECT_NAME,
    CONF_FIXED_BRIGHTNESS,
    CONF_MAX_BRIGHTNESS,
    CONF_ON_STOP,
    CONF_PALETTE_OPTION,
    CONF_PALETTE_SELECTS,
    CONF_TRANSITION,
    DEFAULT_OPTIONS,
    LIGHT_SLOTS,
    SOLID_EFFECT_NAMES,
    TEST_COLORS,
    lights_key,
)

_LOGGER = logging.getLogger(__name__)

TEST_SECONDS = 6


class LightController:
    """Owns the 'before music' snapshot and applies colour payloads to lights."""

    def __init__(self, hass: HomeAssistant, get_options: Callable[[], Mapping[str, Any]]) -> None:
        self.hass = hass
        self._get_options = get_options
        self.enabled = True
        self._task: asyncio.Task | None = None
        self._snapshot: list[State] | None = None

    # ---- configuration ------------------------------------------------------------------

    @property
    def options(self) -> dict[str, Any]:
        return {**DEFAULT_OPTIONS, **self._get_options()}

    def groups(self) -> dict[str, list[str]]:
        opts = self.options
        return {slot: list(opts.get(lights_key(slot)) or []) for slot in LIGHT_SLOTS}

    def all_lights(self) -> list[str]:
        seen: dict[str, None] = {}
        for ents in self.groups().values():
            for e in ents:
                seen.setdefault(e, None)
        return list(seen)

    # ---- entry points (event loop) ---------------------------------------------------------

    def handle(self, payload: Mapping[str, Any]) -> None:
        """Called for every webhook payload. Newer payloads cancel unfinished older ones."""
        if not self.enabled or not self.all_lights():
            return
        event = payload.get("event")
        if event == "stopped":
            self._start(self.async_stop())
        elif event in ("colors", "test"):
            self._start(self.async_apply(payload.get("colors"), bool(payload.get("session_start"))))

    def test(self) -> None:
        """Show the rainbow test pattern for a few seconds, then put the lights back."""
        self._start(self.async_test())

    async def async_disable(self) -> None:
        """Light sync switched off: stop and give the lights back."""
        self.enabled = False
        if self._task and not self._task.done():
            self._task.cancel()
        await self.async_stop()

    # ---- work -------------------------------------------------------------------------------

    def _start(self, coro) -> None:
        if self._task and not self._task.done():
            self._task.cancel()
        self._task = self.hass.async_create_background_task(self._guard(coro), "music_light_synk_lights")

    async def _guard(self, coro) -> None:
        try:
            await coro
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - never let one bad payload kill the integration
            _LOGGER.exception("Applying Music Light Synk colours failed")

    async def async_apply(self, colors: Any, session_start: bool) -> None:
        if not isinstance(colors, Mapping) or not colors:
            return
        opts = self.options
        lights = self.all_lights()
        if not lights:
            return
        palettes = list(opts.get(CONF_PALETTE_SELECTS) or [])

        if opts[CONF_ON_STOP] == "restore" and session_start and self._snapshot is None:
            # Taken once per music session; never overwritten by music colours.
            self._snapshot = self._capture(lights + palettes)

        if opts[CONF_EFFECT_MODE] == "effect" and palettes:
            await self._set_palettes(palettes, str(opts[CONF_PALETTE_OPTION]))
        await self._set_effects(lights, opts[CONF_EFFECT_MODE], str(opts[CONF_EFFECT_NAME] or ""))

        transition = float(opts[CONF_TRANSITION] or 0)
        calls = []
        for slot, ents in self.groups().items():
            color = colors.get(slot)
            if not ents or not isinstance(color, Mapping) or "hs" not in color:
                continue
            data: dict[str, Any] = {
                ATTR_ENTITY_ID: ents,
                ATTR_HS_COLOR: [float(color["hs"][0]), float(color["hs"][1])],
                ATTR_BRIGHTNESS: self._brightness(color, opts),
            }
            if transition > 0:
                data[ATTR_TRANSITION] = transition
            calls.append(self._call(LIGHT_DOMAIN, "turn_on", data))
        # All zones at once: with cloud bulbs, sequential calls add up to visible lag.
        await asyncio.gather(*calls)

    async def async_stop(self) -> None:
        opts = self.options
        if opts[CONF_ON_STOP] == "restore":
            if self._snapshot:
                snapshot, self._snapshot = self._snapshot, None
                await async_reproduce_state(self.hass, snapshot)
        elif opts[CONF_ON_STOP] == "off":
            self._snapshot = None
            if lights := self.all_lights():
                await self._call(LIGHT_DOMAIN, "turn_off", {ATTR_ENTITY_ID: lights})
        else:
            self._snapshot = None

    async def async_test(self) -> None:
        lights = self.all_lights()
        if not lights:
            return
        before = self._capture(lights + list(self.options.get(CONF_PALETTE_SELECTS) or []))
        await self.async_apply(TEST_COLORS, session_start=False)
        await asyncio.sleep(TEST_SECONDS)
        await async_reproduce_state(self.hass, before)

    # ---- helpers ----------------------------------------------------------------------------

    @staticmethod
    def _brightness(color: Mapping[str, Any], opts: Mapping[str, Any]) -> int:
        if opts[CONF_BRIGHTNESS_MODE] == "fixed":
            value = float(opts[CONF_FIXED_BRIGHTNESS]) * 2.55
        else:
            value = float(color.get("brightness", 255)) * float(opts[CONF_MAX_BRIGHTNESS]) / 100
        return max(1, min(255, round(value)))

    def _capture(self, entity_ids: list[str]) -> list[State]:
        states = []
        for entity_id in entity_ids:
            state = self.hass.states.get(entity_id)
            if state is not None and state.state not in (STATE_UNAVAILABLE, STATE_UNKNOWN):
                states.append(state)
        return states

    async def _set_effects(self, lights: list[str], mode: str, name: str) -> None:
        """One call per effect name, only for lights that have it and aren't already on it."""
        if mode not in ("solid", "effect"):
            return
        plan: dict[str, list[str]] = {}
        for entity_id in lights:
            state = self.hass.states.get(entity_id)
            if state is None:
                continue
            effects = [str(e) for e in state.attributes.get(ATTR_EFFECT_LIST) or []]
            lower = [e.lower() for e in effects]
            target = None
            if mode == "effect" and name and name.lower() in lower:
                target = effects[lower.index(name.lower())]
            if target is None:
                target = next((effects[lower.index(s)] for s in SOLID_EFFECT_NAMES if s in lower), None)
            if target is not None and state.attributes.get(ATTR_EFFECT) != target:
                plan.setdefault(target, []).append(entity_id)
        for effect, ents in plan.items():
            await self._call(LIGHT_DOMAIN, "turn_on", {ATTR_ENTITY_ID: ents, ATTR_EFFECT: effect})

    async def _set_palettes(self, selects: list[str], option: str) -> None:
        for entity_id in selects:
            state = self.hass.states.get(entity_id)
            if state and option in (state.attributes.get("options") or []) and state.state != option:
                await self._call("select", "select_option", {ATTR_ENTITY_ID: entity_id, "option": option})

    async def _call(self, domain: str, service: str, data: dict[str, Any]) -> None:
        try:
            await self.hass.services.async_call(domain, service, data, blocking=True)
        except Exception as err:  # noqa: BLE001 - one broken bulb must not stop the others
            _LOGGER.warning("%s.%s failed for %s: %s", domain, service, data.get(ATTR_ENTITY_ID), err)
