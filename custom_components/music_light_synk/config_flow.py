"""Config flow (name, webhook, lights) and options flow (everything else)."""

from __future__ import annotations

import re
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_NAME, CONF_WEBHOOK_ID
from homeassistant.core import callback
from homeassistant.data_entry_flow import section
from homeassistant.helpers import selector

from .const import (
    CONF_ADB_ENTITY,
    CONF_BEAT_LIGHTS,
    CONF_BEAT_STRENGTH,
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


def _options_schema(opts: dict[str, Any]) -> vol.Schema:
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
    beat = vol.Schema(
        {
            vol.Optional(CONF_BEAT_LIGHTS, description={"suggested_value": opts[CONF_BEAT_LIGHTS]}): LIGHT_SELECTOR,
            vol.Required(CONF_BEAT_STRENGTH, default=opts[CONF_BEAT_STRENGTH]): _percent(),
        }
    )
    return vol.Schema(
        {
            vol.Required("lights"): section(_lights_schema(opts), {"collapsed": False}),
            vol.Required("brightness"): section(brightness, {"collapsed": True}),
            vol.Required("effects"): section(effects, {"collapsed": True}),
            vol.Required("stop"): section(stop, {"collapsed": True}),
            vol.Required("beat"): section(beat, {"collapsed": True}),
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

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            flat: dict[str, Any] = {}
            for value in user_input.values():
                if isinstance(value, dict):
                    flat.update(value)
            # A cleared ADB picker is omitted from the input: make that "no TV control".
            flat.setdefault(CONF_ADB_ENTITY, None)
            flat.setdefault(CONF_BEAT_LIGHTS, [])
            return self.async_create_entry(data={**DEFAULT_OPTIONS, **flat})
        opts = {**DEFAULT_OPTIONS, **self.config_entry.options}
        return self.async_show_form(step_id="init", data_schema=_options_schema(opts))
