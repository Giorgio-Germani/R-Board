# R-Board Clipboard Sync Protocol (RCS-1)

Version 1 — text-only clipboard sync over Bluetooth Classic RFCOMM.
One persistent connection per desktop agent ↔ phone. Phone is the RFCOMM **server**
(secure listen, SDP service name `R-Board Clipboard Sync`, service UUID
`8c1f9a52-6e3b-4c7a-9d4e-2b1f0a7c5d31`); desktop agents are clients.
Link security comes from OS-level Bluetooth pairing (encrypted transport);
the protocol adds no crypto of its own.

## Framing

Every frame on the socket:

```
offset  size        field
0       2           length (uint16 big-endian, length of type+payload, max 65535)
2       1           type
3       length-1    payload
```

## Frame types

| Type  | Name       | Payload |
|-------|------------|---------|
| 0x01  | HELLO      | `protoVer:u8 (=1)` `deviceId:16` `flags:u8` `name:utf8 (rest)` |
| 0x10  | CLIP_START | `hash:16` `originId:16` `timestampMs:u64` `flags:u8` `totalLen:u64` `mimeLen:u8` `mime:utf8` |
| 0x11  | CLIP_CHUNK | `hash:16` `seq:u32` `data:≤4096` |
| 0x20  | PING       | opaque bytes (≤64) |
| 0x21  | PONG       | mirrors PING payload |

- Both sides send `HELLO` immediately after connect (symmetric, no ack).
  `flags` bit0: sender supports push-on-change (always 1 in v1).
- `hash` = first 16 bytes of SHA-256 of the full UTF-8 text.
- `originId` = deviceId of the device where the clip was originally copied
  (preserved across hops; a device never applies a clip whose originId equals
  its own deviceId).
- `flags` bit0 on CLIP_START: sensitive (Android `EXTRA_IS_SENSITIVE`,
  desktop: still synced but receiver should not show preview UI).
- `timestampMs` = wall-clock ms at copy time (used only for diagnostics in v1).
- A clip is complete when the sum of CLIP_CHUNK byte counts for its hash
  equals `totalLen` of CLIP_START. Chunks may arrive in any order but senders
  use sequential `seq` starting at 0.

## Session flow

1. Connect (bonded devices only).
2. Both send HELLO.
3. Both immediately push their **current clipboard** as a clip (if any and
   non-empty) — this converges both directions on connect/reconnect.
4. Thereafter each side pushes clipboard changes as they happen
   (push-on-change, debounced ≥150 ms).
5. PING/PONG every 30 s idle to keep the link observable; either side
   reconnects with exponential backoff (1 s → 30 s cap) on failure.

## Loop / echo suppression (both sides implement identically)

Keep a ring of the last 32 applied and last 32 sent `(hash)` entries with
timestamps. A received clip is ignored when any of:

- `originId == own deviceId`
- `hash` appears in the applied/sent ring and the ring entry is younger than
  120 s

Otherwise apply to the local clipboard and record the hash.

## Device identity

`deviceId` is a random 16-byte UUID generated at first run and persisted.
`name` is a human-readable label (`REVENTOR Keyboard` on the phone,
hostname on desktop) for logs and future multi-device UI.

## Out of scope in v1 (by decision)

Images / binary mime types, pull-on-paste semantics, encryption beyond the
Bluetooth bond, discovery/pairing protocol (OS pairing dialog is used).
