"""Apply the TV app's colours to the lights picked in the integration's options.

Same behaviour as the blueprint, without needing one: every zone is updated in parallel,
running effects are switched to solid (or to a chosen effect), and when the music stops the
lights are restored to how they were before it started (or turned off).

Music effect (the device's "Music effect" select): the app sends either every beat or the music's
level a few times a second, and the chosen lights
 - beat pulse: jump in brightness on each beat and fall back,
 - energy: follow the music's level with their brightness,
 - energy bars: light up one after another, like an Energy bar made of bulbs.
A beat arriving while the previous pulse runs is skipped and only the newest level is shown, so
slow lights never build up a backlog. Lights that are off stay off. When the music data stops, the
lights go back to their colour's brightness.
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
from homeassistant.const import ATTR_ENTITY_ID, STATE_ON, STATE_UNAVAILABLE, STATE_UNKNOWN
from homeassistant.core import HomeAssistant, State
from homeassistant.helpers.state import async_reproduce_state

from .const import (
    CONF_BEAT_LIGHTS,
    CONF_BEAT_STRENGTH,
    CONF_MUSIC_LIGHTS,
    CONF_MUSIC_STRENGTH,
    CONF_MUSIC_STYLE,
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
PULSE_HOLD_SECONDS = 0.12
PULSE_FALL_SECONDS = 0.25
LEVEL_TRANSITION = 0.15
SETTLE_SECONDS = 3.0


class LightController:
    """Owns the 'before music' snapshot and applies colour payloads to lights."""

    def __init__(self, hass: HomeAssistant, get_options: Callable[[], Mapping[str, Any]]) -> None:
        self.hass = hass
        self._get_options = get_options
        self.enabled = True
        self._task: asyncio.Task | None = None
        self._snapshot: list[State] | None = None
        self._beat_task: asyncio.Task | None = None
        self._level_task: asyncio.Task | None = None
        self._pending_level: float | None = None
        self._settle: asyncio.TimerHandle | None = None
        # Brightness each light has without the music effect (its colour's brightness).
        self._levels: dict[str, int] = {}
        # Brightness the music effect showed last, per light.
        self._shown: dict[str, int] = {}

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

    def music_lights(self) -> list[str]:
        """Lights for the music effect, in the order picked (energy bars go along it); empty = every mapped light."""
        opts = self.options
        return list(opts.get(CONF_MUSIC_LIGHTS) or opts.get(CONF_BEAT_LIGHTS) or []) or self.all_lights()

    def _strength(self) -> float:
        opts = self._get_options()
        value = opts.get(CONF_MUSIC_STRENGTH, opts.get(CONF_BEAT_STRENGTH, DEFAULT_OPTIONS[CONF_MUSIC_STRENGTH]))
        return max(0.05, min(1.0, float(value) / 100))

    @staticmethod
    def _level_of(payload: Mapping[str, Any]) -> float:
        try:
            return min(1.0, max(0.0, float(payload.get("level", 1.0))))
        except (TypeError, ValueError):
            return 1.0

    def beat(self, payload: Mapping[str, Any]) -> None:
        """A beat from the app. Skipped while the previous pulse still runs."""
        if not self.enabled or (self._beat_task is not None and not self._beat_task.done()):
            return
        lights = self.music_lights()
        if not lights:
            return
        self._beat_task = self.hass.async_create_background_task(
            self._guard(self.async_pulse(lights, self._level_of(payload))), "music_light_synk_beat"
        )

    def level(self, payload: Mapping[str, Any]) -> None:
        """The music's level from the app (energy / energy bars). Only the newest one is shown."""
        if not self.enabled or not self.music_lights():
            return
        self._pending_level = self._level_of(payload)
        if self._level_task is None or self._level_task.done():
            self._level_task = self.hass.async_create_background_task(self._guard(self._run_levels()), "music_light_synk_level")

    async def _run_levels(self) -> None:
        while self._pending_level is not None:
            value, self._pending_level = self._pending_level, None
            await self.async_show_level(value)
        if self._settle is not None:
            self._settle.cancel()
        # No level for a while (music paused, effect switched off): back to the colour's brightness.
        self._settle = self.hass.loop.call_later(
            SETTLE_SECONDS, lambda: self.hass.async_create_background_task(self._guard(self.async_settle()), "music_light_synk_settle")
        )

    def _base(self, entity_id: str, state: State) -> int:
        """The light's own brightness: what the colours set, else what it had when the effect first touched it."""
        if entity_id not in self._levels:
            self._levels[entity_id] = int(state.attributes.get(ATTR_BRIGHTNESS) or 200)
        return self._levels[entity_id]

    async def async_show_level(self, level: float) -> None:
        """Energy: brightness follows the level. Energy bars: the lights fill up one after another."""
        bars = self.options.get(CONF_MUSIC_STYLE) == "bars"
        strength = self._strength()
        on = [(e, s) for e in self.music_lights() if (s := self.hass.states.get(e)) is not None and s.state == STATE_ON]
        n = len(on)
        plan: dict[int, list[str]] = {}
        for i, (entity_id, state) in enumerate(on):
            base = self._base(entity_id, state)
            fill = min(1.0, max(0.0, level * n - i)) if bars else level
            target = max(1, round(base * ((1 - strength) + strength * fill)))
            if abs(target - self._shown.get(entity_id, -100)) < 6:
                continue
            self._shown[entity_id] = target
            plan.setdefault(target, []).append(entity_id)
        await asyncio.gather(
            *(self._call(LIGHT_DOMAIN, "turn_on", {ATTR_ENTITY_ID: ents, ATTR_BRIGHTNESS: b, ATTR_TRANSITION: LEVEL_TRANSITION})
              for b, ents in plan.items())
        )

    async def async_settle(self) -> None:
        """Music data stopped: lights the effect touched go back to their own brightness."""
        shown, self._shown = self._shown, {}
        plan: dict[int, list[str]] = {}
        for entity_id in shown:
            state = self.hass.states.get(entity_id)
            if state is not None and state.state == STATE_ON and entity_id in self._levels:
                plan.setdefault(self._levels[entity_id], []).append(entity_id)
        await asyncio.gather(
            *(self._call(LIGHT_DOMAIN, "turn_on", {ATTR_ENTITY_ID: ents, ATTR_BRIGHTNESS: b, ATTR_TRANSITION: 0.5})
              for b, ents in plan.items())
        )

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
            for entity_id in ents:
                self._levels[entity_id] = data[ATTR_BRIGHTNESS]
            calls.append(self._call(LIGHT_DOMAIN, "turn_on", data))
        # All zones at once: with cloud bulbs, sequential calls add up to visible lag.
        await asyncio.gather(*calls)

    async def async_pulse(self, lights: list[str], level: float) -> None:
        """One beat: lights that are on jump in brightness, then fall back to their colour's brightness."""
        strength = self._strength() * (0.6 + 0.4 * level)
        jump = max(25, round(255 * strength))
        plan: dict[tuple[int, int], list[str]] = {}
        for entity_id in lights:
            state = self.hass.states.get(entity_id)
            if state is None or state.state != STATE_ON:
                continue  # never switch a light on just for a beat
            base = self._base(entity_id, state)
            # Up as far as there's room; only lights already near full dip instead, so the beat still shows.
            peak = min(255, base + jump) if 255 - base >= max(25, jump // 2) else max(1, base - jump)
            plan.setdefault((peak, base), []).append(entity_id)
        if not plan:
            return
        await asyncio.gather(
            *(self._call(LIGHT_DOMAIN, "turn_on", {ATTR_ENTITY_ID: ents, ATTR_BRIGHTNESS: peak, ATTR_TRANSITION: 0})
              for (peak, _), ents in plan.items())
        )
        await asyncio.sleep(PULSE_HOLD_SECONDS)
        await asyncio.gather(
            *(self._call(LIGHT_DOMAIN, "turn_on", {ATTR_ENTITY_ID: ents, ATTR_BRIGHTNESS: base, ATTR_TRANSITION: PULSE_FALL_SECONDS})
              for (_, base), ents in plan.items())
        )

    async def async_stop(self) -> None:
        self._levels.clear()
        self._shown.clear()
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
