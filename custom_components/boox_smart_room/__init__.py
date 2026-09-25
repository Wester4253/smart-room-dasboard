"""Boox Smart Room custom Home Assistant integration.

The Android client uses these WebSocket commands instead of calling
todo.get_items over REST. Home Assistant's REST implementation of that
response-only service is unreliable, while these commands validate and call
the normal todo services with the remote UID.
"""

from __future__ import annotations

from typing import Any

import voluptuous as vol

from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant
from homeassistant.helpers import config_validation as cv

DOMAIN = "boox_smart_room"

_BASE_SCHEMA = {
    vol.Required("entity_id"): cv.entity_id,
}


async def async_setup(hass: HomeAssistant, config: dict[str, Any]) -> bool:
    """Set up the integration and register its authenticated WebSocket commands."""
    websocket_api.async_register_command(hass, websocket_handle_add)
    websocket_api.async_register_command(hass, websocket_handle_update)
    websocket_api.async_register_command(hass, websocket_handle_remove)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: Any) -> bool:
    """Set up a config entry."""
    return True


async def async_unload_entry(hass: HomeAssistant, entry: Any) -> bool:
    """Unload a config entry."""
    return True


async def _call_todo_service(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict[str, Any],
    service: str,
    service_data: dict[str, Any],
) -> None:
    """Call a todo service and return a standard WebSocket result."""
    entity_id = msg["entity_id"]
    try:
        await hass.services.async_call(
            "todo",
            service,
            service_data,
            blocking=True,
        )
    except Exception as err:  # noqa: BLE001 - expose HA's actionable service error
        connection.send_error(msg["id"], "todo_service_failed", str(err))
        return
    connection.send_result(msg["id"], {"entity_id": entity_id, "service": service})


@websocket_api.websocket_command(
    {
        vol.Required("type"): "boox_smart_room/todo/add",
        **_BASE_SCHEMA,
        vol.Required("item"): vol.All(cv.string, vol.Length(min=1), str.strip),
        vol.Optional("description"): cv.string,
        vol.Optional("due"): cv.string,
    }
)
@websocket_api.async_response
async def websocket_handle_add(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict[str, Any],
) -> None:
    """Add a todo item."""
    await _call_todo_service(
        hass,
        connection,
        msg,
        "add_item",
        {
            key: value
            for key, value in {
                "entity_id": msg["entity_id"],
                "item": msg["item"],
                "description": msg.get("description"),
                "due": msg.get("due"),
            }.items()
            if value is not None and value != ""
        },
    )


@websocket_api.websocket_command(
    {
        vol.Required("type"): "boox_smart_room/todo/update",
        **_BASE_SCHEMA,
        vol.Required("item"): vol.All(cv.string, vol.Length(min=1), str.strip),
        vol.Optional("rename"): vol.All(cv.string, vol.Length(min=1), str.strip),
        vol.Optional("status"): vol.In(["needs_action", "completed"]),
        vol.Optional("description"): cv.string,
        vol.Optional("due"): cv.string,
    }
)
@websocket_api.async_response
async def websocket_handle_update(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict[str, Any],
) -> None:
    """Update a todo item by its Home Assistant UID."""
    if "rename" not in msg and "status" not in msg and "description" not in msg and "due" not in msg:
        connection.send_error(
            msg["id"],
            "invalid_format",
            "rename, status, description, or due is required",
        )
        return
    await _call_todo_service(
        hass,
        connection,
        msg,
        "update_item",
        {
            key: value
            for key, value in {
                "entity_id": msg["entity_id"],
                "item": msg["item"],
                "rename": msg.get("rename"),
                "status": msg.get("status"),
                "description": msg.get("description"),
                "due": msg.get("due"),
            }.items()
            if value is not None
        },
    )


@websocket_api.websocket_command(
    {
        vol.Required("type"): "boox_smart_room/todo/remove",
        **_BASE_SCHEMA,
        vol.Required("item"): vol.All(cv.string, vol.Length(min=1), str.strip),
    }
)
@websocket_api.async_response
async def websocket_handle_remove(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict[str, Any],
) -> None:
    """Remove a todo item by its Home Assistant UID."""
    await _call_todo_service(
        hass,
        connection,
        msg,
        "remove_item",
        {"entity_id": msg["entity_id"], "item": [msg["item"]]},
    )
