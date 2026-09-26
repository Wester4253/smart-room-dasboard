"""Boox Smart Room custom Home Assistant integration.

The Android client uses these WebSocket commands instead of calling
todo.get_items over REST. Home Assistant's REST implementation of that
response-only service is unreliable, while these commands validate and call
the normal todo services with the remote UID.
"""

from __future__ import annotations

import importlib
import json
import socket
from typing import Any

import voluptuous as vol

from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import config_validation as cv

DOMAIN = "boox_smart_room"

SERVICE_PAIR_TABLET = "pair_tablet"

_PAIR_SCHEMA = vol.Schema(
    {
        vol.Required("host"): cv.string,
        vol.Optional("port", default=8123): vol.All(
            vol.Coerce(int), vol.Range(min=1, max=65535)
        ),
        vol.Required("code"): vol.All(cv.string, vol.Length(min=6, max=6)),
    },
    extra=vol.PREVENT_EXTRA,
)

_BASE_SCHEMA = {
    vol.Required("entity_id"): cv.entity_id,
}


async def async_setup(hass: HomeAssistant, config: dict[str, Any]) -> bool:
    """Set up the integration and register its authenticated WebSocket commands."""
    websocket_api.async_register_command(hass, websocket_handle_add)
    websocket_api.async_register_command(hass, websocket_handle_update)
    websocket_api.async_register_command(hass, websocket_handle_remove)
    websocket_api.async_register_command(hass, websocket_handle_pair)
    _async_register_pair_service(hass)
    return True


def _async_register_pair_service(hass: HomeAssistant) -> None:
    """Register `boox_smart_room.pair_tablet`.

    This is the "press one button in Home Assistant" path: wire a `button` card on
    a dashboard to this action and the tablet provisions itself. `code` is the only
    thing the user has to carry across, and it is the six digits on the tablet.
    """

    async def _async_pair_tablet(call: Any) -> None:
        data = _PAIR_SCHEMA(dict(call.data))
        errors: list[str] = []

        class _Sink:
            """Adapter so the shared push helper can report failures as service errors."""

            def send_error(self, msg_id: int, code: str, message: str) -> None:
                errors.append(message)

            def send_result(self, msg_id: int, result: Any) -> None:
                return None

        result = await _async_push_pairing(
            hass, data["host"], data["port"], data["code"], _Sink(), 0
        )
        if result is None:
            raise ServiceValidationError(errors[0] if errors else "Pairing failed")

    hass.services.async_register(
        DOMAIN, SERVICE_PAIR_TABLET, _async_pair_tablet, schema=_PAIR_SCHEMA
    )


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


def _remote_ui_url(hass: HomeAssistant) -> str | None:
    """Return this instance's Home Assistant Cloud remote URL, if it has one.

    Prefers the cloud integration's own accessor so the canonical scheme and host
    are used. Falls back to the zeroconf-advertised external URL, which is what
    the instance believes its public address to be.
    """
    cloud = _load_component(hass, "cloud")
    if cloud is not None:
        try:
            return cloud.async_remote_ui_url(hass)
        except Exception:  # noqa: BLE001 - not logged in, remote disabled, no domain
            pass

    http = _load_component(hass, "homeassistant") or _load_component(hass, "http")
    if http is not None:
        getter = getattr(http, "async_get_url", None)
        if getter is not None:
            try:
                external = getter(hass, allow_internal=False)
            except Exception:  # noqa: BLE001 - no usable external URL configured
                external = None
            if external:
                return str(external).rstrip("/")
    return None


def _load_component(hass: HomeAssistant, domain: str) -> Any | None:
    """Import a built-in integration module without requiring it to be set up."""
    try:
        return importlib.import_module(f"homeassistant.components.{domain}")
    except ImportError:
        return None


async def _todo_entities(hass: HomeAssistant) -> list[list[str]]:
    """Collect every `todo.*` entity as `[entity_id, friendly_name]` pairs."""
    pairs: list[list[str]] = []
    for state in hass.states.async_all("todo"):
        name = state.attributes.get("friendly_name") or state.entity_id
        pairs.append([state.entity_id, str(name)])
    return pairs


async def _async_handle_pair(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict[str, Any],
) -> None:
    """Push this instance's address and todo lists to a waiting tablet.

    No credential is included. The tablet cannot read a Nabu Casa address off a
    screen and has no camera, so the address comes from here; the tablet then mints
    its own long-lived token over the WebSocket API in a browser-origin sign-in
    flow. Sending a token here instead would put a permanent admin credential on
    the local network in cleartext.
    """
    try:
        port = int(msg.get("port", 8123))
    except (TypeError, ValueError):
        connection.send_error(msg["id"], "invalid_format", "port must be a number")
        return
    if not 1 <= port <= 65535:
        connection.send_error(msg["id"], "invalid_format", "port is out of range")
        return

    code = str(msg.get("code", "")).strip()
    if len(code) != 6 or not code.isdigit():
        connection.send_error(
            msg["id"], "invalid_format", "code must be the six digits shown on the tablet"
        )
        return

    result = await _async_push_pairing(
        hass,
        msg["host"],
        port,
        code,
        connection,
        msg["id"],
    )
    if result is not None:
        connection.send_result(msg["id"], result)


async def _async_push_pairing(
    hass: HomeAssistant,
    host: str,
    port: int,
    code: str,
    connection: websocket_api.ActiveConnection,
    msg_id: int,
) -> dict[str, Any] | None:
    """Shared body of the WebSocket command and the service.

    Returns the result payload on success, or None after having sent an error.
    """
    base_url = _remote_ui_url(hass) or _local_url(hass)
    if not base_url:
        connection.send_error(
            msg_id,
            "no_url",
            "Could not determine a URL for this instance",
        )
        return None

    entities = await _todo_entities(hass)
    body = _pairing_body(code, base_url, entities, hass.config.location_name)

    try:
        ok = await hass.async_add_executor_job(_post_pairing, host, port, body)
    except OSError as err:
        connection.send_error(
            msg_id, "unreachable", f"Could not reach the tablet at {host}:{port}: {err}"
        )
        return None

    if not ok:
        connection.send_error(
            msg_id,
            "rejected",
            "The tablet did not accept the pairing. Check that the code on the "
            "tablet still matches and that setup is still open.",
        )
        return None

    return {
        "base_url": base_url,
        "todo_count": len(entities),
        "is_cloud": base_url.startswith("https://") and "nabu.casa" in base_url,
    }


def _local_url(hass: HomeAssistant) -> str | None:
    """Best-effort local address, used when there is no cloud remote URL."""
    http = _load_component(hass, "homeassistant")
    if http is None:
        return None
    getter = getattr(http, "async_get_url", None)
    if getter is None:
        return None
    try:
        return str(getter(hass, allow_external=False)).rstrip("/")
    except Exception:  # noqa: BLE001 - no internal URL available
        return None


def _pairing_body(
    code: str,
    base_url: str,
    entities: list[list[str]],
    location_name: str,
) -> bytes:
    """Serialize the push payload.

    Kept in step with `parsePairingPayload` on the Kotlin side; the two have no
    shared schema, so changing one means changing the other in the same commit.
    Built with `json.dumps` rather than string concatenation so escaping a
    location name containing a quote cannot produce invalid JSON.
    """
    document = {
        "code": code,
        "baseUrl": base_url,
        "locationName": location_name or "Home Assistant",
        "todoEntities": entities,
    }
    return json.dumps(document).encode("utf-8")


def _post_pairing(host: str, port: int, body: bytes) -> bool:
    """POST the payload to the tablet. Blocking; runs in the executor."""
    with socket.create_connection((host, port), timeout=5) as client:
        client.settimeout(5)
        request = (
            f"POST / HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Content-Type: application/json\r\n"
            f"Content-Length: {len(body)}\r\n"
            "Connection: close\r\n"
            "\r\n"
        ).encode() + body
        client.sendall(request)
        status_line = client.makefile("rb").readline().decode(errors="replace")
    return " 200 " in status_line


@websocket_api.websocket_command(
    {
        vol.Required("type"): "boox_smart_room/pair",
        vol.Required("host"): cv.string,
        vol.Optional("port", default=8123): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535)),
        vol.Required("code"): vol.All(cv.string, vol.Length(min=6, max=6)),
    }
)
@websocket_api.async_response
async def websocket_handle_pair(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict[str, Any],
) -> None:
    """Pair a tablet by pushing this instance's details to it."""
    await _async_handle_pair(hass, connection, msg)


async def _todo_entities(hass: HomeAssistant) -> list[list[str]]:
    """Collect every `todo.*` entity as `[entity_id, friendly_name]` pairs."""
    pairs: list[list[str]] = []
    for state in hass.states.async_all("todo"):
        name = state.attributes.get("friendly_name") or state.entity_id
        pairs.append([state.entity_id, str(name)])
    return pairs
