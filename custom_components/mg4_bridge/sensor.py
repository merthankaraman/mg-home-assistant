from __future__ import annotations

from homeassistant.components.sensor import (
    RestoreSensor,
    SensorDeviceClass,
    SensorStateClass,
)
from datetime import datetime

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import (
    PERCENTAGE,
    UnitOfElectricCurrent,
    UnitOfElectricPotential,
    UnitOfLength,
    UnitOfPower,
    UnitOfPressure,
    UnitOfTemperature,
    UnitOfTime,
)
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from .const import CONF_NAME, CONF_PREFIX, DOMAIN, SIGNAL_UPDATE
from .device import bridge_device

# key, name, unit, device_class, state_class, suggested_display_precision
SENSORS: tuple[
    tuple[str, str, str | None, SensorDeviceClass | None, SensorStateClass | None, int | None],
    ...,
] = (
    ("last_update", "Son güncelleme", None, SensorDeviceClass.TIMESTAMP, None, None),
    ("mileage", "Kilometre", UnitOfLength.KILOMETERS, SensorDeviceClass.DISTANCE, SensorStateClass.TOTAL_INCREASING, 0),
    ("battery", "Şarj yüzdesi", PERCENTAGE, SensorDeviceClass.BATTERY, SensorStateClass.MEASUREMENT, 1),
    ("range", "Menzil", UnitOfLength.KILOMETERS, SensorDeviceClass.DISTANCE, SensorStateClass.MEASUREMENT, 0),
    ("exterior_temperature", "Dış sıcaklık", UnitOfTemperature.CELSIUS, SensorDeviceClass.TEMPERATURE, SensorStateClass.MEASUREMENT, 0),
    ("tire_pressure_fl", "Lastik sol ön", UnitOfPressure.KPA, SensorDeviceClass.PRESSURE, SensorStateClass.MEASUREMENT, 0),
    ("tire_pressure_fr", "Lastik sağ ön", UnitOfPressure.KPA, SensorDeviceClass.PRESSURE, SensorStateClass.MEASUREMENT, 0),
    ("tire_pressure_rl", "Lastik sol arka", UnitOfPressure.KPA, SensorDeviceClass.PRESSURE, SensorStateClass.MEASUREMENT, 0),
    ("tire_pressure_rr", "Lastik sağ arka", UnitOfPressure.KPA, SensorDeviceClass.PRESSURE, SensorStateClass.MEASUREMENT, 0),
    ("charging_status", "Şarj durumu", None, None, None, None),
    ("charge_remaining", "Kalan şarj süresi", UnitOfTime.MINUTES, SensorDeviceClass.DURATION, SensorStateClass.MEASUREMENT, 0),
    ("battery_voltage", "Batarya voltaj", UnitOfElectricPotential.VOLT, SensorDeviceClass.VOLTAGE, SensorStateClass.MEASUREMENT, None),
    ("battery_current", "Batarya akım", UnitOfElectricCurrent.AMPERE, SensorDeviceClass.CURRENT, SensorStateClass.MEASUREMENT, None),
    ("battery_charging_power", "Batarya gücü", UnitOfPower.KILO_WATT, SensorDeviceClass.POWER, SensorStateClass.MEASUREMENT, None),
    ("station_dc_current", "İstasyon akımı", UnitOfElectricCurrent.AMPERE, SensorDeviceClass.CURRENT, SensorStateClass.MEASUREMENT, None),
    ("station_dc_power", "İstasyon gücü", UnitOfPower.KILO_WATT, SensorDeviceClass.POWER, SensorStateClass.MEASUREMENT, None),
    ("ac_voltage", "AC voltaj", UnitOfElectricPotential.VOLT, SensorDeviceClass.VOLTAGE, SensorStateClass.MEASUREMENT, None),
    ("ac_current", "AC akım", UnitOfElectricCurrent.AMPERE, SensorDeviceClass.CURRENT, SensorStateClass.MEASUREMENT, None),
    ("ac_charging_power", "AC şarj gücü", UnitOfPower.KILO_WATT, SensorDeviceClass.POWER, SensorStateClass.MEASUREMENT, None),
)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    prefix = entry.data[CONF_PREFIX]
    name = entry.data[CONF_NAME]
    async_add_entities(
        [Mg4Sensor(hass, entry, prefix, name, *item) for item in SENSORS]
    )


class Mg4Sensor(RestoreSensor):
    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(
        self,
        hass: HomeAssistant,
        entry: ConfigEntry,
        prefix: str,
        device_name: str,
        key: str,
        name: str,
        unit,
        device_class,
        state_class,
        precision: int | None,
    ) -> None:
        self.hass = hass
        self._entry = entry
        self._key = key
        self._attr_name = name
        self._attr_unique_id = f"{prefix}_{key}"
        self._attr_native_unit_of_measurement = unit
        self._attr_device_class = device_class
        self._attr_state_class = state_class
        self._attr_suggested_display_precision = precision
        self._attr_device_info = bridge_device(prefix, device_name)
        self._attr_translation_key = key

    def _data(self) -> dict:
        return self.hass.data[DOMAIN][self._entry.entry_id]["data"]

    @property
    def native_value(self):
        val = self._data().get(self._key)
        if self._key == "last_update" and isinstance(val, str):
            parsed = dt_util.parse_datetime(val)
            return parsed if parsed is not None else val
        if isinstance(val, datetime):
            return val
        return val

    @property
    def available(self) -> bool:
        data = self._data()
        if not data or data.get("online") is False:
            return False
        if self._key in ("station_dc_current", "station_dc_power"):
            return data.get("charging_status") == "DC" and self._key in data
        if self._key == "charge_remaining":
            return data.get("charging_status") in ("AC", "DC") and self._key in data
        return self._key in data

    async def async_added_to_hass(self) -> None:
        last = await self.async_get_last_sensor_data()
        if last is not None and last.native_value is not None:
            data = self._data()
            if self._key not in data:
                data[self._key] = last.native_value
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, f"{SIGNAL_UPDATE}_{self._entry.entry_id}", self._handle_update
            )
        )

    @callback
    def _handle_update(self) -> None:
        self.async_write_ha_state()
