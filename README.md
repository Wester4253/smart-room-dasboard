# Smart Room Dashboard

Native Kotlin Android app for an e-ink-friendly Home Assistant todo dashboard.

## Features

- Compose shell with high-contrast, low-decoration UI for e-ink displays.
- Home Assistant URL, todo entity, and encrypted long-lived token settings.
- Home Assistant todo sync. Items are listed over the WebSocket `todo/item/list` command.
- Optional HACS custom integration for reliable add/update/remove WebSocket mutations.
- Real on-device handwriting conversion through ML Kit Digital Ink Recognition.
- A Home Assistant dashboard mode with external-authenticated WebView access.
- Offline-first todo list with add, edit, complete, and delete actions.
- Trello-style two-column Kanban board backed by the same Home Assistant todo entity.
- Finger and capacitive-pen handwriting canvas. The Boox touch pen is handled as a normal touch, with clear, undo, and typed fallback.
- Ordered offline mutations persisted and retried on refresh or the next mutation.
- Mutations use the UID returned by Home Assistant after a WebSocket refresh. Before an edit,
  the app resolves the current remote item and never sends its local UUID to Home Assistant.
  Failed mutations remain queued and are shown as errors instead of being silently discarded.

## Home Assistant configuration

Enter the Home Assistant origin only, such as `http://homeassistant.local:8123` or
`https://ha.example.com`. The app strips a trailing `/api` if it is entered accidentally and
constructs the REST paths itself.

Create a Home Assistant long-lived access token and paste it into the settings screen. The token
is stored using Android Keystore and sent only as a Bearer authorization header. It is never
written to logs.

The first handwriting submission downloads the English Digital Ink model (about 20 MB) while the
device is online. Later handwriting recognition works on-device. Write one task at a time, tap
**Convert handwriting**, review the recognized text, and tap **Add this task** to add it.

Create a list before configuring the app: in Home Assistant open **Settings > Devices &
services > Add integration > Local Todo**. Each list creates a `todo.*` entity. Use **Find todo
lists** in the app settings to discover the available entity IDs, then enter one of them (for
example `todo.smart_room`).

The app lists items with the WebSocket command `todo/item/list`. It never calls the broken REST
`todo.get_items` endpoint. Install the bundled `custom_components/boox_smart_room` folder through
HACS, add **Boox Smart Room** once in Home Assistant, and the app will use the integration's
authenticated `boox_smart_room/todo/add`, `update`, and `remove` WebSocket commands for
mutations. Discovery uses `GET /api/states`. The board maps incomplete items to **To do** and
completed items to **Done**.

The **Home Assistant** mode opens the configured dashboard path (default `lovelace/0`) inside
the app. It uses Home Assistant's `external_auth=1` integration rather than putting the token in
the URL, so dashboard API requests can authenticate correctly.

## Build

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The Android SDK must provide API 35 and a Java 17-compatible Gradle runtime.

## Offline synchronization

Todo add, update, and delete operations are applied to the local cache immediately and stored in
an ordered persistent queue until the Home Assistant request succeeds. Refresh drains that queue
before replacing the cache with the server list. A failed operation and later operations remain
queued so their order is preserved.
