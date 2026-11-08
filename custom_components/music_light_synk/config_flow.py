"""Config flow: pick a name and a webhook ID."""

from __future__ import annotations

import re
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.const import CONF_NAME, CONF_WEBHOOK_ID

from .const import DEFAULT_NAME, DEFAULT_WEBHOOK_ID, DOMAIN

WEBHOOK_ID_RE = re.compile(r"^[A-Za-z0-9_-]{3,64}$")


class MusicLightSynkConfigFlow(ConfigFlow, domain=DOMAIN):
    """One entry per TV (per webhook ID)."""

    VERSION = 1

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            webhook_id = user_input[CONF_WEBHOOK_ID].strip()
            if not WEBHOOK_ID_RE.match(webhook_id):
                errors[CONF_WEBHOOK_ID] = "invalid_webhook_id"
            else:
                await self.async_set_unique_id(webhook_id)
                self._abort_if_unique_id_configured()
                if webhook_id in self.hass.data.get("webhook", {}):
                    # e.g. the blueprint automation already listens on this ID
                    errors[CONF_WEBHOOK_ID] = "webhook_in_use"
            if not errors:
                return self.async_create_entry(
                    title=user_input[CONF_NAME].strip() or DEFAULT_NAME,
                    data={CONF_WEBHOOK_ID: webhook_id},
                )
        schema = vol.Schema(
            {
                vol.Required(CONF_NAME, default=DEFAULT_NAME): str,
                vol.Required(CONF_WEBHOOK_ID, default=DEFAULT_WEBHOOK_ID): str,
            }
        )
        return self.async_show_form(step_id="user", data_schema=schema, errors=errors)
