# R-Board Clipboard Sync — Windows Agent

Tray application that mirrors the text clipboard between an Android phone
running **R-Board** and a Windows PC over Bluetooth Classic (RFCOMM).
Text only, no internet involved — the phone is the Bluetooth server, the PC connects.

## Giving the exe to someone

The only file needed is:

    dist/R-Board-Service.exe

It is self-contained (no DLLs, no installer, no admin rights). Copy it to any
folder on the test PC. Config and log live in `%LOCALAPPDATA%\reventor-agent\`
(created on first start: `address.txt`, `agent.log`, device id).

## Setup on a new Windows PC

1. **Pair the phone with the PC** via Windows Bluetooth settings
   (Settings → Bluetooth & devices → Add device). Keep the phone discoverable.
2. **On the phone**: open R-Board → the sync assistant
   ("R-Board einrichten" / START SYNC) and grant Bluetooth + notification
   permissions so the sync service is running.
3. **Find the phone's MAC address**: on the phone, in the sync assistant
   status line, or via `adb shell settings get secure bluetooth_address`
   (or Bluetooth → device details). It looks like `98:12:E0:00:B8:5A`.
4. **Tell the agent the phone's address**: create
   `%LOCALAPPDATA%\reventor-agent\address.txt` containing just the MAC
   (`9812E000B85A` or `98:12:E0:00:B8:5A` — both work).
5. **Start the exe.** A tray icon appears; the tooltip shows
   "R-Board Clipboard Sync — verbunden" once connected (or "nicht verbunden"
   while waiting). Copy text on either device and it appears in the other's
   clipboard within a moment.

Alternative to step 4: start with `R-Board-Service.exe --address 98:12:E0:00:B8:5A`.

## Autostart with Windows

On the very first start the agent asks (Yes/No dialog) whether it should
launch automatically with Windows — answer once and it is set up
(a shortcut is created in the user's Startup folder). To change it later:
press `Win+R`, run `shell:startup`, and add/remove the
"R-Board Clipboard Sync" shortcut there.

## Troubleshooting

- Log: `%LOCALAPPDATA%\reventor-agent\agent.log`
  ("connected" / "phone does not advertise the … service" etc.).
- If the tooltip never shows "verbunden": check the phone's sync service is
  running (R-Board sync assistant → START SYNC), Bluetooth is on on both
  sides, and the devices are paired.
- Windows caches Bluetooth services: after (re)pairing, restart the agent once.
- HyperOS/MIUI: exclude R-Board from battery optimization, otherwise the
  phone-side service may be killed after days (sync assistant has guidance).

## Building from source

    cd desktop
    cargo build --release

The finished exe lands in `target/release/R-Board-Service.exe`.

Rust stable for Windows; no other dependencies.
