from homeassistant.config_entries import ConfigFlow

from .const import DOMAIN


class Mg4ConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    async def async_step_user(self, user_input=None):
        await self.async_set_unique_id(DOMAIN)
        self._abort_if_unique_id_configured()
        if user_input is not None:
            return self.async_create_entry(title="MG4", data={})
        return self.async_show_form(step_id="user")
