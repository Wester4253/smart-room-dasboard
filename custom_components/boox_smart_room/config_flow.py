"""Config flow for Boox Smart Room."""

from __future__ import annotations

from typing import Any

from homeassistant import config_entries
from homeassistant.core import callback

from . import DOMAIN


class BooxSmartRoomConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Set up the stateless WebSocket command integration."""

    VERSION = 1

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: config_entries.ConfigEntry) -> Any:
        return None

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> Any:
        """Create the single integration entry."""
        if user_input is not None:
            return self.async_create_entry(title="Boox Smart Room", data={})
        return self.async_show_form(step_id="user")
