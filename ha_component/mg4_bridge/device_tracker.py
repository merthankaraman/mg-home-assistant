from homeassistant.components.device_tracker import SourceType, TrackerEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import DOMAIN, SIGNAL_UPDATE
from .device import mg4_device


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_add_entities([Mg4Tracker(hass, entry)])


class Mg4Tracker(TrackerEntity):
    _attr_has_entity_name = True
    _attr_should_poll = False
    _attr_name = "Konum"
    _attr_unique_id = "mg4_tracker"

    def __init__(self, hass, entry):
        self.hass = hass
        self._entry = entry
        self._attr_device_info = mg4_device()

    @property
    def source_type(self) -> SourceType:
        return SourceType.GPS

    @property
    def latitude(self):
        return self.hass.data[DOMAIN][self._entry.entry_id]["data"].get("latitude")

    @property
    def longitude(self):
        return self.hass.data[DOMAIN][self._entry.entry_id]["data"].get("longitude")

    @property
    def location_accuracy(self):
        acc = self.hass.data[DOMAIN][self._entry.entry_id]["data"].get("gps_accuracy")
        return int(acc) if acc is not None else 0

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
