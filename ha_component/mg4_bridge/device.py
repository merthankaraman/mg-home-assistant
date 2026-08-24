from homeassistant.helpers.device_registry import DeviceInfo

from .const import DEVICE_ID, DOMAIN


def mg4_device() -> DeviceInfo:
    return DeviceInfo(
        identifiers={(DOMAIN, DEVICE_ID)},
        name="MG4",
        manufacturer="MG",
        model="MG4 EH32",
    )
