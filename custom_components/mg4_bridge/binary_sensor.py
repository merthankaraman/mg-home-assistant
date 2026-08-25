from __future__ import annotations

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.restore_state import RestoreEntity

from .const import CONF_NAME, CONF_PREFIX, DOMAIN, SIGNAL_UPDATE
from .device import bridge_device


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    async_add_entities([Mg4ChargingBinary(hass, entry)])


class Mg4ChargingBinary(BinarySensorEntity, RestoreEntity):
    _attr_has_entity_name = True
    _attr_should_poll = False
    _attr_name = "Şarjda"
    _attr_device_class = BinarySensorDeviceClass.BATTERY_CHARGING

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        prefix = entry.data[CONF_PREFIX]
        self.hass = hass
        self._entry = entry
        self._attr_unique_id = f"{prefix}_charging"
        self._attr_device_info = bridge_device(prefix, entry.data[CONF_NAME])

    def _data(self) -> dict:
        return self.hass.data[DOMAIN][self._entry.entry_id]["data"]

    @property
    def is_on(self) -> bool | None:
        data = self._data()
        if "charging" not in data:
            return None
        return bool(data.get("charging"))

    @property
    def available(self) -> bool:
        data = self._data()
        return bool(data) and data.get("online") is not False

    async def async_added_to_hass(self) -> None:
        last = await self.async_get_last_state()
        if last is not None and "charging" not in self._data():
            self._data()["charging"] = last.state == "on"
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, f"{SIGNAL_UPDATE}_{self._entry.entry_id}", self._handle_update
            )
        )

    @callback
    def _handle_update(self) -> None:
        self.async_write_ha_state()
