# R-Board

R-Board is a privacy-conscious, fully offline keyboard for Android — a fork of
[HeliBoard](https://github.com/HeliBorg/HeliBoard) (based on AOSP / OpenBoard)
with its own feature set on top:

- **Offline voice dictation** — push-to-talk with the open Whistle speech model
  (auto-downloaded on first use, ~17 MB). No cloud, no internet permission.
  Recognition is limited to your active keyboard languages.
- **Clipboard sync with your PC** — text clipboard is mirrored over Bluetooth
  Classic (RFCOMM) between the phone and Windows. No cloud, no WLAN needed;
  the Windows companion **R-Board Clipboard Sync** comes with a built-in
  setup assistant that walks through installation, Bluetooth pairing and a
  live connection test.
- **Multilingual typing out of the box** — with two or more Latin keyboard
  languages enabled, suggestions draw from all of them at once (e.g. German +
  English, shown as "DE / EN" on the spacebar).
- **Glide typing without setup** — the swipe library is fetched and activated
  automatically in the background on first start.
- Everything HeliBoard offers: customizable themes (plus R-Board's own color
  picker with live preview), layouts, clipboard history, one-handed mode,
  split keyboard, number pad, backup/restore.

R-Board never uses the internet permission for typing; the only network access
happens on explicit user actions (model / swipe library download, both
checksum-verified files).

## Install

Grab the latest assets from the
[GitHub releases page](https://github.com/Giorgio-Germani/R-Board/releases/latest):

| File | What it is |
| --- | --- |
| `REVENTOR_Keyboard_<version>-release.apk` | The keyboard app for Android |
| `R-Board-Clipboard-Sync-Windows-<version>.zip` | Windows clipboard-sync agent (self-contained exe + README) |

After installing the APK, enable R-Board in the system settings and pick it as
your keyboard — the built-in setup assistant then guides through permissions,
languages and sync.

## Building from source

Android app (Gradle, in this repository root):

    ./gradlew assembleRelease

Windows agent (Rust, in [desktop/](desktop/)):

    cd desktop
    cargo build --release

## Repository layout

- [app/](app/) — the keyboard (HeliBoard fork)
- [sync/](sync/) — the Bluetooth clipboard-sync service on the phone side
- [dictation/](dictation/) — offline speech recognition (Whistle/Needle engine, JNI)
- [desktop/](desktop/) — Rust workspace for the Windows agent
- [docs/protocol.md](docs/protocol.md) — the clipboard-sync wire protocol (RCS-1)

## Credits & license

R-Board is built on the great work of the HeliBoard project — thanks to
[Helium314 and contributors](https://github.com/HeliBorg/HeliBoard/graphs/contributors).
HeliBoard itself is a fork of [OpenBoard](https://github.com/openboard-team/openboard)
and the AOSP Keyboard, with contributions from LineageOS, Simple Keyboard,
Indic Keyboard and FlorisBoard (see the upstream repository for the full list).

Licensing is inherited from upstream: the code is licensed under
[GNU GPL v3.0](/LICENSE); since the base is the Apache 2.0 licensed AOSP
Keyboard, an [Apache 2.0](/LICENSE-Apache-2.0) license file is provided as well.
The R-Board logo/launcher icon is a Reventor creation; the original HeliBoard
icon is licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
([license file](/LICENSE-CC-BY-SA-4.0)).
