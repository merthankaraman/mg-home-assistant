from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.dispatcher import async_dispatcher_send

from .const import (
    CONF_NAME,
    CONF_PREFIX,
    DOMAIN,
    SERVICE_PUSH,
    SIGNAL_UPDATE,
)

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [Platform.SENSOR, Platform.BINARY_SENSOR, Platform.DEVICE_TRACKER]

PUSH_SCHEMA = vol.Schema(
    {
        vol.Optional("prefix"): cv.string,
        vol.Optional("online", default=True): cv.boolean,
        vol.Optional("demo"): cv.boolean,
        vol.Optional("battery"): vol.Coerce(float),
        vol.Optional("range"): vol.Coerce(int),
        vol.Optional("mileage"): vol.Coerce(int),
        vol.Optional("exterior_temperature"): vol.Coerce(int),
        vol.Optional("tire_pressure_fl"): vol.Coerce(int),
        vol.Optional("tire_pressure_fr"): vol.Coerce(int),
        vol.Optional("tire_pressure_rl"): vol.Coerce(int),
        vol.Optional("tire_pressure_rr"): vol.Coerce(int),
        vol.Optional("charging"): cv.boolean,
        vol.Optional("charging_status"): cv.string,
        vol.Optional("battery_voltage"): vol.Coerce(float),
        vol.Optional("battery_current"): vol.Coerce(float),
        vol.Optional("battery_charging_power"): vol.Coerce(float),
        vol.Optional("station_dc_current"): vol.Coerce(float),
        vol.Optional("station_dc_power"): vol.Coerce(float),
        vol.Optional("ac_voltage"): vol.Coerce(float),
        vol.Optional("ac_current"): vol.Coerce(float),
        vol.Optional("ac_charging_power"): vol.Coerce(float),
        vol.Optional("charge_remaining"): vol.Coerce(int),
        vol.Optional("last_update"): cv.string,
        vol.Optional("latitude"): vol.Coerce(float),
        vol.Optional("longitude"): vol.Coerce(float),
        vol.Optional("gps_accuracy"): vol.Coerce(float),
    },
    extra=vol.ALLOW_EXTRA,
)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    hass.data.setdefault(DOMAIN, {})
    hass.data[DOMAIN][entry.entry_id] = {
        "data": {"online": False},
        "prefix": entry.data[CONF_PREFIX],
        "name": entry.data[CONF_NAME],
    }

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    async def _async_push(call: ServiceCall) -> None:
        payload = dict(call.data)
        prefix_filter = payload.pop("prefix", None)
        updated = False
        for entry_id, store in hass.data[DOMAIN].items():
            if entry_id == "services_registered":
                continue
            if not isinstance(store, dict) or "prefix" not in store:
                continue
            if prefix_filter and store["prefix"] != str(prefix_filter).strip().lower():
                continue
            merged = dict(store.get("data") or {})
            merged.update(payload)
            if "online" not in payload:
                merged["online"] = True
            # DC istasyon alanları yalnız DC şarjdayken
            if merged.get("charging_status") != "DC":
                merged.pop("station_dc_current", None)
                merged.pop("station_dc_power", None)
            # Kalan süre yalnız AC/DC şarjdayken; payload'da yoksa temizle
            if merged.get("charging_status") not in ("AC", "DC") or "charge_remaining" not in payload:
                merged.pop("charge_remaining", None)
            store["data"] = merged
            updated = True
            async_dispatcher_send(hass, f"{SIGNAL_UPDATE}_{entry_id}")
        if not updated:
            raise HomeAssistantError(f"No mg4_bridge entry for prefix={prefix_filter}")

    if not hass.data[DOMAIN].get("services_registered"):
        hass.services.async_register(DOMAIN, SERVICE_PUSH, _async_push, schema=PUSH_SCHEMA)
        hass.data[DOMAIN]["services_registered"] = True

    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unload_ok:
        hass.data[DOMAIN].pop(entry.entry_id, None)
        # Keep service if other entries remain
        remaining = [
            k
            for k, v in hass.data[DOMAIN].items()
            if k != "services_registered" and isinstance(v, dict) and "prefix" in v
        ]
        if not remaining and hass.data[DOMAIN].get("services_registered"):
            hass.services.async_remove(DOMAIN, SERVICE_PUSH)
            hass.data[DOMAIN]["services_registered"] = False
    return unload_ok
