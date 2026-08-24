from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import DOMAIN, SIGNAL_UPDATE
from .device import mg4_device


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_add_entities([Mg4ChargingSensor(hass, entry)])


class Mg4ChargingSensor(BinarySensorEntity):
    _attr_has_entity_name = True
    _attr_should_poll = False
    _attr_name = "Şarjda"
    _attr_unique_id = "mg4_charging"
    _attr_device_class = BinarySensorDeviceClass.BATTERY_CHARGING

    def __init__(self, hass, entry):
        self.hass = hass
        self._entry = entry
        self._attr_device_info = mg4_device()

    @property
    def is_on(self):
        data = self.hass.data[DOMAIN][self._entry.entry_id]["data"]
        return bool(data.get("charging"))

    @property
    def available(self) -> bool:
        data = self.hass.data[DOMAIN][self._entry.entry_id]["data"]
        return bool(data) and data.get("online", True) is not False

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(self.hass, SIGNAL_UPDATE, self._handle_update)
        )

    @callback
    def _handle_update(self) -> None:
        self.async_write_ha_state()
