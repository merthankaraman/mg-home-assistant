from homeassistant.helpers.device_registry import DeviceInfo

from .const import DOMAIN


def bridge_device(prefix: str, name: str) -> DeviceInfo:
    return DeviceInfo(
        identifiers={(DOMAIN, prefix)},
        name=name,
        manufacturer="MG",
        model="MG4 EH32",
    )
