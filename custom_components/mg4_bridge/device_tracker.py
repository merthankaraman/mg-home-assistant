from __future__ import annotations

from homeassistant.components.device_tracker import SourceType, TrackerEntity
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
    async_add_entities([Mg4Tracker(hass, entry)])


class Mg4Tracker(TrackerEntity, RestoreEntity):
    _attr_has_entity_name = True
    _attr_should_poll = False
    _attr_name = "Konum"

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        prefix = entry.data[CONF_PREFIX]
        self.hass = hass
        self._entry = entry
        self._attr_unique_id = f"{prefix}_location"
        self._attr_device_info = bridge_device(prefix, entry.data[CONF_NAME])

    def _data(self) -> dict:
        return self.hass.data[DOMAIN][self._entry.entry_id]["data"]

    @property
    def source_type(self) -> SourceType:
        return SourceType.GPS

    @property
    def latitude(self):
        return self._data().get("latitude")

    @property
    def longitude(self):
        return self._data().get("longitude")

    @property
    def location_accuracy(self):
        acc = self._data().get("gps_accuracy")
        try:
            return int(acc) if acc is not None else 0
        except (TypeError, ValueError):
            return 0

    @property
    def available(self) -> bool:
        data = self._data()
        return (
            bool(data)
            and data.get("online") is not False
            and data.get("latitude") is not None
            and data.get("longitude") is not None
        )

    async def async_added_to_hass(self) -> None:
        last = await self.async_get_last_state()
        if last is not None:
            data = self._data()
            attrs = last.attributes or {}
            if "latitude" not in data and "latitude" in attrs:
                data["latitude"] = attrs.get("latitude")
                data["longitude"] = attrs.get("longitude")
                if "gps_accuracy" in attrs:
                    data["gps_accuracy"] = attrs.get("gps_accuracy")
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, f"{SIGNAL_UPDATE}_{self._entry.entry_id}", self._handle_update
            )
        )

    @callback
    def _handle_update(self) -> None:
        self.async_write_ha_state()
