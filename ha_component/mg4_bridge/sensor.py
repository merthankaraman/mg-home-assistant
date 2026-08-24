from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorStateClass,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import PERCENTAGE, UnitOfLength, UnitOfPower, UnitOfPressure, UnitOfTemperature
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import DOMAIN, SIGNAL_UPDATE
from .device import mg4_device

SENSORS = (
    ("battery", "Batarya", "battery", PERCENTAGE, SensorDeviceClass.BATTERY, SensorStateClass.MEASUREMENT),
    ("range", "Menzil", "range", UnitOfLength.KILOMETERS, SensorDeviceClass.DISTANCE, SensorStateClass.MEASUREMENT),
    ("mileage", "Kilometre", "mileage", UnitOfLength.KILOMETERS, SensorDeviceClass.DISTANCE, SensorStateClass.TOTAL_INCREASING),
    ("exterior_temperature", "Dış sıcaklık", "exterior_temperature", UnitOfTemperature.CELSIUS, SensorDeviceClass.TEMPERATURE, SensorStateClass.MEASUREMENT),
    ("tire_pressure_fl", "Lastik FL", "tire_pressure_fl", UnitOfPressure.BAR, SensorDeviceClass.PRESSURE, SensorStateClass.MEASUREMENT),
    ("tire_pressure_fr", "Lastik FR", "tire_pressure_fr", UnitOfPressure.BAR, SensorDeviceClass.PRESSURE, SensorStateClass.MEASUREMENT),
    ("tire_pressure_rl", "Lastik RL", "tire_pressure_rl", UnitOfPressure.BAR, SensorDeviceClass.PRESSURE, SensorStateClass.MEASUREMENT),
    ("tire_pressure_rr", "Lastik RR", "tire_pressure_rr", UnitOfPressure.BAR, SensorDeviceClass.PRESSURE, SensorStateClass.MEASUREMENT),
    ("charging_status", "Şarj durumu", "charging_status", None, None, None),
    ("charging_power", "Şarj gücü", "charging_power", UnitOfPower.KILO_WATT, SensorDeviceClass.POWER, SensorStateClass.MEASUREMENT),
)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_add_entities([Mg4Sensor(hass, entry, *item) for item in SENSORS])


class Mg4Sensor(SensorEntity):
    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, hass, entry, key, name, unique_suffix, unit, device_class, state_class):
        self.hass = hass
        self._entry = entry
        self._key = key
        self._attr_name = name
        self._attr_unique_id = f"mg4_{unique_suffix}"
        self._attr_native_unit_of_measurement = unit
        self._attr_device_class = device_class
        self._attr_state_class = state_class
        self._attr_device_info = mg4_device()

    @property
    def native_value(self):
        data = self.hass.data[DOMAIN][self._entry.entry_id]["data"]
        return data.get(self._key)

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
