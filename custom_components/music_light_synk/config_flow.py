"""Config flow (name, webhook, lights) and options flow (everything else)."""

from __future__ import annotations

import re
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_NAME, CONF_WEBHOOK_ID
from homeassistant.core import callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.data_entry_flow import section
from homeassistant.helpers import selector

from homeassistant.helpers import entity_registry as er

from .const import (
    CONF_ADB_ENTITY,
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
    DEFAULT_NAME,
    DEFAULT_OPTIONS,
    DEFAULT_WEBHOOK_ID,
    DOMAIN,
    EFFECT_SUGGESTIONS,
    FALLBACK_WEBHOOK_ID,
    LIGHT_SLOTS,
    lights_key,
)
from .wled_strips import strips, wled_hosts

CONF_WLED_STRIPS = "wled_strips"
WLED_SELECTOR = selector.EntitySelector(selector.EntitySelectorConfig(integration="wled", domain="light", multiple=True))

WEBHOOK_ID_RE = re.compile(r"^[A-Za-z0-9_-]{3,64}$")

LIGHT_SELECTOR = selector.EntitySelector(selector.EntitySelectorConfig(domain="light", multiple=True))
ADB_SELECTOR = selector.EntitySelector(selector.EntitySelectorConfig(integration="androidtv", domain="media_player"))


def _tv_schema(opts: dict[str, Any]) -> vol.Schema:
    return vol.Schema(
        {vol.Optional(CONF_ADB_ENTITY, description={"suggested_value": opts.get(CONF_ADB_ENTITY)}): ADB_SELECTOR}
    )


def _lights_schema(opts: dict[str, Any]) -> vol.Schema:
    return vol.Schema(
        {
            # suggested_value (not default) so a cleared picker really clears the zone
            vol.Optional(lights_key(slot), description={"suggested_value": opts.get(lights_key(slot), [])}): LIGHT_SELECTOR
            for slot in LIGHT_SLOTS
        }
    )


def _select(options: list[str], key: str) -> selector.SelectSelector:
    return selector.SelectSelector(
        selector.SelectSelectorConfig(options=options, translation_key=key, mode=selector.SelectSelectorMode.DROPDOWN)
    )


def _percent() -> selector.NumberSelector:
    return selector.NumberSelector(
        selector.NumberSelectorConfig(min=1, max=100, step=1, unit_of_measurement="%", mode=selector.NumberSelectorMode.SLIDER)
    )


def _options_schema(opts: dict[str, Any], wled_strip_entities: list[str] | None = None) -> vol.Schema:
    wled_strip_entities = wled_strip_entities or []
    brightness = vol.Schema(
        {
            vol.Required(CONF_BRIGHTNESS_MODE, default=opts[CONF_BRIGHTNESS_MODE]): _select(["follow", "fixed"], CONF_BRIGHTNESS_MODE),
            vol.Required(CONF_FIXED_BRIGHTNESS, default=opts[CONF_FIXED_BRIGHTNESS]): _percent(),
            vol.Required(CONF_MAX_BRIGHTNESS, default=opts[CONF_MAX_BRIGHTNESS]): _percent(),
            vol.Required(CONF_TRANSITION, default=opts[CONF_TRANSITION]): selector.NumberSelector(
                selector.NumberSelectorConfig(min=0, max=10, step=0.5, unit_of_measurement="s", mode=selector.NumberSelectorMode.BOX)
            ),
        }
    )
    effects = vol.Schema(
        {
            vol.Required(CONF_EFFECT_MODE, default=opts[CONF_EFFECT_MODE]): _select(["solid", "effect", "keep"], CONF_EFFECT_MODE),
            vol.Required(CONF_EFFECT_NAME, default=opts[CONF_EFFECT_NAME]): selector.SelectSelector(
                selector.SelectSelectorConfig(options=EFFECT_SUGGESTIONS, custom_value=True, mode=selector.SelectSelectorMode.DROPDOWN)
            ),
            vol.Optional(CONF_PALETTE_SELECTS, description={"suggested_value": opts[CONF_PALETTE_SELECTS]}): selector.EntitySelector(
                selector.EntitySelectorConfig(domain="select", multiple=True)
            ),
            vol.Required(CONF_PALETTE_OPTION, default=opts[CONF_PALETTE_OPTION]): selector.TextSelector(),
        }
    )
    stop = vol.Schema(
        {vol.Required(CONF_ON_STOP, default=opts[CONF_ON_STOP]): _select(["restore", "off", "nothing"], CONF_ON_STOP)}
    )
    music_lights = opts.get(CONF_MUSIC_LIGHTS) or opts.get(CONF_BEAT_LIGHTS) or []
    music = vol.Schema(
        {
            vol.Optional(CONF_MUSIC_LIGHTS, description={"suggested_value": music_lights}): LIGHT_SELECTOR,
            vol.Required(CONF_MUSIC_STRENGTH, default=opts.get(CONF_MUSIC_STRENGTH, opts.get(CONF_BEAT_STRENGTH, 60))): _percent(),
        }
    )
    wled = vol.Schema(
        {vol.Optional(CONF_WLED_STRIPS, description={"suggested_value": wled_strip_entities}): WLED_SELECTOR}
    )
    return vol.Schema(
        {
            vol.Required("lights"): section(_lights_schema(opts), {"collapsed": False}),
            vol.Required("brightness"): section(brightness, {"collapsed": True}),
            vol.Required("effects"): section(effects, {"collapsed": True}),
            vol.Required("stop"): section(stop, {"collapsed": True}),
            vol.Required("music"): section(music, {"collapsed": True}),
            vol.Required("wled"): section(wled, {"collapsed": True}),
            vol.Required("tv"): section(_tv_schema(opts), {"collapsed": True}),
        }
    )


class MusicLightSynkConfigFlow(ConfigFlow, domain=DOMAIN):
    """One entry per TV (per webhook ID): name + webhook, then pick lights."""

    VERSION = 1

    def __init__(self) -> None:
        self._name = DEFAULT_NAME
        self._webhook_id = DEFAULT_WEBHOOK_ID
        self._lights: dict[str, Any] = {}

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return MusicLightSynkOptionsFlow()

    def _webhook_in_use(self, webhook_id: str) -> bool:
        return webhook_id in self.hass.data.get("webhook", {})

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            webhook_id = user_input[CONF_WEBHOOK_ID].strip()
            if not WEBHOOK_ID_RE.match(webhook_id):
                errors[CONF_WEBHOOK_ID] = "invalid_webhook_id"
            else:
                await self.async_set_unique_id(webhook_id)
                self._abort_if_unique_id_configured()
                if self._webhook_in_use(webhook_id):
                    # e.g. the blueprint automation already listens on this ID
                    errors[CONF_WEBHOOK_ID] = "webhook_in_use"
            if not errors:
                self._name = user_input[CONF_NAME].strip() or DEFAULT_NAME
                self._webhook_id = webhook_id
                return await self.async_step_lights()

        # The TV app sends to "music_light_synk" out of the box. Suggest it unless the
        # blueprint is already using it.
        suggested = FALLBACK_WEBHOOK_ID if self._webhook_in_use(DEFAULT_WEBHOOK_ID) else DEFAULT_WEBHOOK_ID
        schema = vol.Schema(
            {
                vol.Required(CONF_NAME, default=DEFAULT_NAME): str,
                vol.Required(CONF_WEBHOOK_ID, default=suggested): str,
            }
        )
        return self.async_show_form(step_id="user", data_schema=schema, errors=errors)

    async def async_step_lights(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            self._lights = user_input
            return await self.async_step_tv()
        return self.async_show_form(step_id="lights", data_schema=_lights_schema(DEFAULT_OPTIONS))

    async def async_step_tv(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Optional: the Fire TV's Android Debug Bridge entity, to control the app from HA."""
        if user_input is not None:
            return self.async_create_entry(
                title=self._name,
                data={CONF_WEBHOOK_ID: self._webhook_id},
                options={**DEFAULT_OPTIONS, **self._lights, **user_input},
            )
        return self.async_show_form(step_id="tv", data_schema=_tv_schema({}))


class MusicLightSynkOptionsFlow(OptionsFlow):
    """Lights per zone, brightness, effects and what happens when the music stops."""

    def _wled_entity_hosts(self) -> dict[str, str]:
        """Light entities of HA's WLED integration -> the WLED's host."""
        hosts: dict[str, str] = {}
        registry = er.async_get(self.hass)
        for entry in self.hass.config_entries.async_entries("wled"):
            host = entry.data.get("host")
            if not isinstance(host, str):
                continue
            for ent in er.async_entries_for_config_entry(registry, entry.entry_id):
                if ent.domain == "light":
                    hosts[ent.entity_id] = host
        return hosts

    def _app_strip_entities(self) -> list[str]:
        """HA's WLED light entities whose WLED is already in the app's list (one per WLED)."""
        app_hosts = {s["host"] for s in strips(self.config_entry.runtime_data.status)}
        picked: dict[str, str] = {}
        for entity_id, host in self._wled_entity_hosts().items():
            if host in app_hosts:
                picked.setdefault(host, entity_id)
        return list(picked.values())

    async def _apply_wled_strips(self, chosen: list[str]) -> None:
        """Add / remove WLEDs that Home Assistant knows in the app's list; others stay as they are."""
        data = self.config_entry.runtime_data
        current = strips(data.status)
        ha_hosts = set(wled_hosts(self.hass))
        by_entity = self._wled_entity_hosts()
        wanted = {by_entity[e] for e in chosen if e in by_entity}
        keep = [s["spec"] for s in current if s["host"] in wanted or s["host"] not in ha_hosts]
        have = {s["host"] for s in current}
        added = [f"{host}=dominant" for host in sorted(wanted - have)]
        devices = "; ".join(keep + added)
        if devices == "; ".join(s["spec"] for s in current):
            return
        await data.app.configure(wled_devices=devices)
        data.set_setting("wled_devices", devices)

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        opts = {**DEFAULT_OPTIONS, **self.config_entry.options}
        before = self._app_strip_entities()
        if user_input is not None:
            flat: dict[str, Any] = {}
            for value in user_input.values():
                if isinstance(value, dict):
                    flat.update(value)
            # A cleared picker is omitted from the input: make that "none".
            flat.setdefault(CONF_ADB_ENTITY, None)
            flat.setdefault(CONF_MUSIC_LIGHTS, [])
            chosen = flat.pop(CONF_WLED_STRIPS, [])
            if sorted(chosen) != sorted(before):
                try:
                    await self._apply_wled_strips(chosen)
                except HomeAssistantError:
                    errors["base"] = "app_unreachable"
            if not errors:
                data = {**DEFAULT_OPTIONS, **flat, CONF_MUSIC_STYLE: opts.get(CONF_MUSIC_STYLE, "energy")}
                data.pop(CONF_BEAT_LIGHTS, None)
                data.pop(CONF_BEAT_STRENGTH, None)
                return self.async_create_entry(data=data)
        return self.async_show_form(step_id="init", data_schema=_options_schema(opts, before), errors=errors)
