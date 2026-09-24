# Boox Smart Room

HACS custom integration for the Smart Room Android app.

Install this folder through HACS as a custom repository, or copy it as:

```text
< Home Assistant config >/custom_components/boox_smart_room/
```

Restart Home Assistant after installation. The integration registers authenticated WebSocket
commands:

- `boox_smart_room/todo/add`
- `boox_smart_room/todo/update`
- `boox_smart_room/todo/remove`

The Android app still uses Home Assistant's built-in WebSocket `todo/item/list` command for
reading. This integration only handles mutations and always passes Home Assistant's remote UID
to the built-in todo service, avoiding the REST `todo.get_items` HTTP 500 path.

After restart, open **Settings → Devices & services → Add integration → Boox Smart Room** once.
The integration has no URL or token settings; the Android app authenticates each WebSocket
connection with the long-lived token already configured in the app.
