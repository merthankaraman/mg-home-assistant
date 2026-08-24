from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, Event
from homeassistant.helpers.dispatcher import async_dispatcher_send

from .const import DOMAIN, EVENT_TELEMETRY, SIGNAL_UPDATE

PLATFORMS = [Platform.SENSOR, Platform.BINARY_SENSOR, Platform.DEVICE_TRACKER]


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    hass.data.setdefault(DOMAIN, {})
    hass.data[DOMAIN][entry.entry_id] = {"data": {}}

    async def _on_telemetry(event: Event) -> None:
        payload = dict(event.data or {})
        hass.data[DOMAIN][entry.entry_id]["data"] = payload
        async_dispatcher_send(hass, SIGNAL_UPDATE)

    entry.async_on_unload(hass.bus.async_listen(EVENT_TELEMETRY, _on_telemetry))
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unload_ok:
        hass.data[DOMAIN].pop(entry.entry_id, None)
    return unload_ok
