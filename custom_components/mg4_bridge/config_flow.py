from __future__ import annotations

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.helpers import config_validation as cv

from .const import CONF_NAME, CONF_PREFIX, DEFAULT_NAME, DEFAULT_PREFIX, DOMAIN


class Mg4ConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 2

    async def async_step_user(self, user_input=None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            prefix = user_input[CONF_PREFIX].strip().lower()
            prefix = "".join(c if c.isalnum() or c == "_" else "_" for c in prefix)
            if not prefix:
                errors["base"] = "invalid_prefix"
            else:
                await self.async_set_unique_id(f"{DOMAIN}_{prefix}")
                self._abort_if_unique_id_configured()
                return self.async_create_entry(
                    title=user_input[CONF_NAME].strip() or DEFAULT_NAME,
                    data={
                        CONF_PREFIX: prefix,
                        CONF_NAME: user_input[CONF_NAME].strip() or DEFAULT_NAME,
                    },
                )

        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_NAME, default=DEFAULT_NAME): cv.string,
                    vol.Required(CONF_PREFIX, default=DEFAULT_PREFIX): cv.string,
                }
            ),
            errors=errors,
        )
