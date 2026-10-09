# R-Board Clipboard Sync — Windows Agent

Tray application that mirrors the text clipboard between an Android phone
running **R-Board** and a Windows PC over Bluetooth Classic (RFCOMM).
Text only, no internet involved — the phone is the Bluetooth server, the PC connects.

## Giving the exe to someone

The only file needed is:

    dist/R-Board-Service.exe

It is self-contained (no DLLs, no admin rights, no installer). The recipient
just double-clicks it — the **built-in setup assistant** (German) does the rest:

1. **Installation** — copies itself to `%LOCALAPPDATA%\Programs\R-Board\`,
   creates the Start-menu entry "R-Board Clipboard Sync" and enables autostart.
2. **Bluetooth** — checked automatically; the radio is switched on if needed.
3. **Pairing** — already-paired R-Board phones are found on their own.
   Otherwise the assistant lists nearby devices ("Sichtbar machen" on the
   phone makes it appear); selecting the phone and clicking **Koppeln** runs
   the normal Windows pairing dialog. Confirm on both devices — done.
4. **Sync service check** — the assistant polls the phone until its sync
   service is visible. If it isn't running yet, the window shows exactly what
   to do on the phone (R-Board → "R-Board einrichten" → SYNC STARTEN) and
   continues automatically once it is.
5. **Live test** — a test text is pushed PC → phone, then the user is asked
   to copy anything on the phone; when it arrives the wizard confirms
   "Alles funktioniert ✓".

Everything is also written to the log (`%LOCALAPPDATA%\reventor-agent\agent.log`).

### Manual steps, should they ever be needed

- Start the assistant again later: tray icon → **Setup-Assistent …**, or run
  `R-Board-Service.exe --setup`.
- Forget the configured phone and start over: `R-Board-Service.exe --reset`.
- Only a second instance of the agent is refused (it would fight over the
  clipboard); the tray menu is the place to reconfigure a running agent.

## How it works under the hood

The agent discovers the phone by scanning paired Bluetooth devices for the
R-Board sync service UUID (`8c1f9a52-…-2b1f0a7c5d31`) and saves its address in
`%LOCALAPPDATA%\reventor-agent\address.txt`, so later starts connect directly.
Config, device id and log all live in that folder.

Autostart: a shortcut in the user's Startup folder (created by the setup
assistant). To remove it later: `Win+R` → `shell:startup`.

## Troubleshooting

- Log: `%LOCALAPPDATA%\reventor-agent\agent.log`
  ("connected" / "phone does not advertise the … service" etc.).
- If the tray tooltip never shows "verbunden": check the phone's sync service
  is running (R-Board sync assistant → SYNC STARTEN), Bluetooth is on on both
  sides, and the devices are paired. The tray menu's **Setup-Assistent …**
  walks through all of this with live checks.
- Windows caches Bluetooth services: after (re)pairing, restart the agent once
  (or use the wizard's "Bluetooth-Modul neu starten" button).
- HyperOS/MIUI: exclude R-Board from battery optimization, otherwise the
  phone-side service may be killed after days (sync assistant has guidance).

## Building from source

    cd desktop
    cargo build --release

The finished exe lands in `target/release/R-Board-Service.exe`.

Rust stable for Windows; no other dependencies.
