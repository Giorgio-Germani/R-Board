//! RCS-1 clipboard sync wire codec — mirror of the Kotlin implementation in
//! `android/sync/src/main/java/app/reventor/sync/Rcs1.kt`. Spec: `docs/protocol.md`.
//!
//! Frame = `[u16 BE length incl. type byte][u8 type][payload]`.

use std::io::{self, Read, Write};

pub const PROTO_VERSION: u8 = 1;
pub const CHUNK_SIZE: usize = 4096;
pub const MIME_TEXT: &str = "text/plain";
pub const FLAG_PUSH_SUPPORTED: u8 = 0x01;
pub const FLAG_SENSITIVE: u8 = 0x01;

const TYPE_HELLO: u8 = 0x01;
const TYPE_CLIP_START: u8 = 0x10;
const TYPE_CLIP_CHUNK: u8 = 0x11;
const TYPE_PING: u8 = 0x20;
const TYPE_PONG: u8 = 0x21;

/// hash = first 16 bytes of SHA-256 of the full UTF-8 text.
pub fn sha256_first16(text: &str) -> [u8; 16] {
    use sha2::Digest;
    let digest = sha2::Sha256::digest(text.as_bytes());
    let mut out = [0u8; 16];
    out.copy_from_slice(&digest[..16]);
    out
}

#[derive(Debug, Clone)]
pub enum Frame {
    Hello {
        proto_version: u8,
        device_id: [u8; 16],
        flags: u8,
        name: String,
    },
    ClipStart {
        hash: [u8; 16],
        origin_id: [u8; 16],
        timestamp_ms: u64,
        sensitive: bool,
        total_len: u64,
        mime: String,
    },
    ClipChunk {
        hash: [u8; 16],
        seq: u32,
        data: Vec<u8>,
    },
    Ping(Vec<u8>),
    Pong(Vec<u8>),
}

pub fn write_frame(out: &mut impl Write, frame: &Frame) -> io::Result<()> {
    let (t, payload) = encode_payload(frame)?;
    let len = payload.len() + 1;
    if len > 0xFFFF {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "frame too large"));
    }
    out.write_all(&[(len >> 8) as u8, len as u8, t])?;
    out.write_all(&payload)?;
    out.flush()
}

fn put_u64(buf: &mut Vec<u8>, v: u64) {
    buf.extend_from_slice(&v.to_be_bytes());
}

fn encode_payload(frame: &Frame) -> io::Result<(u8, Vec<u8>)> {
    let mut p = Vec::new();
    Ok(match frame {
        Frame::Hello { proto_version, device_id, flags, name } => {
            p.push(*proto_version);
            p.extend_from_slice(device_id);
            p.push(*flags);
            p.extend_from_slice(name.as_bytes());
            (TYPE_HELLO, p)
        }
        Frame::ClipStart { hash, origin_id, timestamp_ms, sensitive, total_len, mime } => {
            p.extend_from_slice(hash);
            p.extend_from_slice(origin_id);
            put_u64(&mut p, *timestamp_ms);
            p.push(if *sensitive { FLAG_SENSITIVE } else { 0 });
            put_u64(&mut p, *total_len);
            if mime.len() > 0xFF {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "mime too long"));
            }
            p.push(mime.len() as u8);
            p.extend_from_slice(mime.as_bytes());
            (TYPE_CLIP_START, p)
        }
        Frame::ClipChunk { hash, seq, data } => {
            if data.len() > CHUNK_SIZE {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "chunk too large"));
            }
            p.extend_from_slice(hash);
            p.extend_from_slice(&seq.to_be_bytes());
            p.extend_from_slice(data);
            (TYPE_CLIP_CHUNK, p)
        }
        Frame::Ping(data) => (TYPE_PING, data.clone()),
        Frame::Pong(data) => (TYPE_PONG, data.clone()),
    })
}

/// Returns `Ok(None)` on clean EOF.
pub fn read_frame(input: &mut impl Read) -> io::Result<Option<Frame>> {
    let mut hdr = [0u8; 3];
    match input.read_exact(&mut hdr) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let len = u16::from_be_bytes([hdr[0], hdr[1]]) as usize;
    if len < 1 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "empty frame"));
    }
    let mut payload = vec![0u8; len - 1];
    input.read_exact(&mut payload)?;
    decode_frame(hdr[2], &payload).map(Some)
}

fn take<'a>(p: &mut &'a [u8], n: usize) -> io::Result<&'a [u8]> {
    if p.len() < n {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "frame truncated"));
    }
    let (head, rest) = p.split_at(n);
    *p = rest;
    Ok(head)
}

fn take_u8(p: &mut &[u8]) -> io::Result<u8> {
    Ok(take(p, 1)?[0])
}

fn take_u64(p: &mut &[u8]) -> io::Result<u64> {
    let b = take(p, 8)?;
    Ok(u64::from_be_bytes(b.try_into().unwrap()))
}

fn decode_frame(t: u8, mut p: &[u8]) -> io::Result<Frame> {
    Ok(match t {
        TYPE_HELLO => {
            let proto_version = take_u8(&mut p)?;
            let device_id = take(&mut p, 16)?.try_into().unwrap();
            let flags = take_u8(&mut p)?;
            let name = String::from_utf8_lossy(p).into_owned();
            Frame::Hello { proto_version, device_id, flags, name }
        }
        TYPE_CLIP_START => {
            let hash = take(&mut p, 16)?.try_into().unwrap();
            let origin_id = take(&mut p, 16)?.try_into().unwrap();
            let timestamp_ms = take_u64(&mut p)?;
            let flags = take_u8(&mut p)?;
            let total_len = take_u64(&mut p)?;
            let mime_len = take_u8(&mut p)? as usize;
            let mime = String::from_utf8_lossy(take(&mut p, mime_len)?).into_owned();
            Frame::ClipStart { hash, origin_id, timestamp_ms, sensitive: flags & FLAG_SENSITIVE != 0, total_len, mime }
        }
        TYPE_CLIP_CHUNK => {
            let hash = take(&mut p, 16)?.try_into().unwrap();
            let seq = u32::from_be_bytes(take(&mut p, 4)?.try_into().unwrap());
            Frame::ClipChunk { hash, seq, data: p.to_vec() }
        }
        TYPE_PING => Frame::Ping(p.to_vec()),
        TYPE_PONG => Frame::Pong(p.to_vec()),
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, format!("unknown frame type {t:#x}"))),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame_bytes(frame: &Frame) -> Vec<u8> {
        let mut buf = Vec::new();
        write_frame(&mut buf, frame).unwrap();
        buf
    }

    #[test]
    fn hello_roundtrip() {
        let f = Frame::Hello {
            proto_version: PROTO_VERSION,
            device_id: [7u8; 16],
            flags: FLAG_PUSH_SUPPORTED,
            name: "windows-pc".into(),
        };
        match read_frame(&mut frame_bytes(&f).as_slice()).unwrap().unwrap() {
            Frame::Hello { proto_version, device_id, flags, name } => {
                assert_eq!(proto_version, PROTO_VERSION);
                assert_eq!(device_id, [7u8; 16]);
                assert_eq!(flags, FLAG_PUSH_SUPPORTED);
                assert_eq!(name, "windows-pc");
            }
            other => panic!("wrong frame: {other:?}"),
        }
    }

    #[test]
    fn multi_chunk_clip_roundtrip() {
        let text = "REVENTOR — äöü ß test 🎉 ".repeat(500);
        let hash = sha256_first16(&text);
        let bytes = text.as_bytes();
        let mut buf = Vec::new();
        write_frame(&mut buf, &Frame::ClipStart {
            hash,
            origin_id: [1u8; 16],
            timestamp_ms: 1728190000123,
            sensitive: false,
            total_len: bytes.len() as u64,
            mime: MIME_TEXT.into(),
        })
        .unwrap();
        for (seq, chunk) in bytes.chunks(CHUNK_SIZE).enumerate() {
            write_frame(&mut buf, &Frame::ClipChunk { hash, seq: seq as u32, data: chunk.to_vec() }).unwrap();
        }

        let mut input = buf.as_slice();
        let mut assembled = Vec::new();
        let mut total = 0u64;
        while let Some(frame) = read_frame(&mut input).unwrap() {
            match frame {
                Frame::ClipStart { total_len, .. } => total = total_len,
                Frame::ClipChunk { data, .. } => assembled.extend_from_slice(&data),
                _ => panic!("unexpected frame"),
            }
        }
        assert_eq!(total, bytes.len() as u64);
        assert_eq!(assembled, bytes);
    }

    #[test]
    fn read_frame_eof() {
        assert!(read_frame(&mut std::io::empty()).unwrap().is_none());
    }

    #[test]
    fn hash_stable() {
        assert_eq!(sha256_first16("deterministic"), sha256_first16("deterministic"));
        assert_ne!(sha256_first16("a"), sha256_first16("b"));
    }
}
