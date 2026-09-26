# Pairing the Boox tablet

The tablet has no camera, and a Nabu Casa remote address is per-account and awkward
to read off a screen, so neither a QR code nor typing an address is a good
experience. There are three ways in; the first is the intended one.

## 1. Push from Home Assistant (needs both devices on the same network)

1. Copy `custom_components/boox_smart_room` into your Home Assistant `config/custom_components/`
   and restart Home Assistant.
2. On the tablet: **Settings → Set up with Home Assistant → Send from Home Assistant instead**.
   The screen shows a six-digit code and the tablet's address on the network.
3. In Home Assistant, add a button, and set its action to:

   ```yaml
   action: boox_smart_room.pair_tablet
   data:
     host: 192.168.1.50    # the address shown on the tablet
     port: 41234           # the port shown on the tablet
     code: "123456"        # the code shown on the tablet
   ```

4. Press the button. The tablet fills in the address and todo lists, then opens the
   sign-in page so it can mint its own token.

**No token crosses the network.** The push carries only the address and the list of
`todo.*` entities, gated on the six-digit code. The tablet then creates its
long-lived token itself, over the WebSocket, from inside its own origin. Putting a
token in the push would mean sending a permanent admin credential over the LAN in
cleartext, so don't add one.

## 2. Cloud, which is what you want at school

Do option 1 **once, at home**, and you never have to do it again.

After the tablet connects, it asks Home Assistant for its own cloud address
(`cloud/status`, which reports `remote_domain`) and saves it. From then on the app
talks to your Nabu Casa URL and works from anywhere, including school. The local
address is kept as a fallback, and **Settings** shows a button to switch back.

Check it worked: **Settings** should say it is using a `*.ui.nabu.casa` address.

### Why pairing itself cannot work from school

The tablet listens on the LAN and Home Assistant dials it. From school your tablet
is behind NAT with no inbound path, so a push from Home Assistant cannot reach it,
and mDNS cannot see your home network either. This is a property of the network, not
a limitation of the app.

That is exactly why the cloud upgrade matters: it is the step that happens at home
and makes the tablet work everywhere else. If you need to re-pair while away, you
will have to connect the tablet to your home network (or a VPN into it) first.

## 3. Type an address

Still in **Settings**, for the case where discovery cannot see Home Assistant. The
URL field accepts either a local address or a Nabu Casa remote UI address.

## Troubleshooting

- **"This tablet is offline"** — the tablet has no network. Check Wi-Fi.
- **Nothing found when both are on Wi-Fi** — some networks block mDNS between
  clients (guest networks, some APs). Use option 1 or 3.
- **"The tablet did not accept the pairing"** — the code or address is stale. The
  code changes each time you open the screen, so copy it fresh and make sure the
  setup screen is still open on the tablet.
- **"Could not reach the tablet"** — Home Assistant cannot open a connection to the
  tablet. Check the address is the tablet's current Wi-Fi address (it changes with
  the network) and that both are on the same subnet.
- **A device on your network could push to the tablet** — the six-digit code is the
  only gate, and it is regenerated each time setup opens. Close the setup screen
  when you are done.
