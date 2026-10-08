//! R-Board clipboard sync desktop agent.
//!
//! Runs as a tray application: connects to the REVENTOR keyboard app over
//! Bluetooth Classic RFCOMM and mirrors text clipboards both directions.
//! The phone address is read from the address.txt file in the agent config
//! folder (LOCALAPPDATA/reventor-agent), or passed via --address.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod history;
mod rcomm;
mod sysclip;
mod tray;

use windows::core::HSTRING;
use windows::Win32::UI::WindowsAndMessaging::{MessageBoxW, IDYES, MB_ICONINFORMATION, MB_ICONQUESTION, MB_YESNO};

use std::sync::Arc;
use std::io::Write as _;
use std::sync::atomic::Ordering;
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use history::ClipHistory;
use protocol::{Frame, CHUNK_SIZE, FLAG_PUSH_SUPPORTED, MIME_TEXT, PROTO_VERSION};

const POLL_INTERVAL_MS: u64 = 300;

/// connection state surfaced in the tray tooltip
pub static CONNECTED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

type BoxError = Box<dyn std::error::Error + Send + Sync>;

fn main() {
    ensure_autostart_prompt();
    let mut address: Option<u64> = None;
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--address" => {
                address = args.next().and_then(|a| rcomm::parse_address(&a));
            }
            other => {
                logln(&format!("unknown argument: {other}"));
                std::process::exit(2);
            }
        }
    }
    let address = address.or_else(|| {
        let path = config_dir().join("address.txt");
        std::fs::read_to_string(path).ok().and_then(|s| rcomm::parse_address(s.trim()))
    });

    let own_id = load_or_create_device_id();
    match address {
        Some(addr) => {
            logln(&format!("R-Board agent — phone {addr:012X}"));
            std::thread::Builder::new()
                .name("sync".into())
                .spawn(move || sync_loop(addr, own_id))
                .expect("spawn sync thread");
        }
        None => {
            // no address yet: search the paired devices for the R-Board phone and
            // save it, so every other computer only needs the phone to be paired
            logln("no address configured — searching paired devices for the R-Board phone…");
            std::thread::Builder::new()
                .name("discovery".into())
                .spawn(move || discovery_then_sync(own_id))
                .expect("spawn discovery thread");
        }
    }
    tray::run_tray();
}

/// on the very first start, ask whether the agent should launch with Windows;
/// if yes, create a shortcut in the user's Startup folder. Asked only once.
fn ensure_autostart_prompt() {
    let marker = config_dir().join("autostart_asked");
    if marker.exists() {
        return;
    }
    let exe = match std::env::current_exe() {
        Ok(e) => e,
        Err(_) => return,
    };
    let text = HSTRING::from("Soll R-Board Clipboard Sync automatisch gestartet werden, wenn du den Computer einschaltest?");
    let caption = HSTRING::from("R-Board Clipboard Sync");
    let answer = unsafe {
        MessageBoxW(None, &text, &caption, MB_YESNO | MB_ICONQUESTION)
    };
    if answer == IDYES {
        // create the shortcut through PowerShell (WScript.Shell) to avoid COM boilerplate
        let script = format!(
            "$s=(New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path [Environment]::GetFolderPath('Startup') 'R-Board Clipboard Sync.lnk')); $s.TargetPath='{}'; $s.Save()",
            exe.display()
        );
        use std::os::windows::process::CommandExt;
        let status = std::process::Command::new("powershell")
            .args(["-NoProfile", "-WindowStyle", "Hidden", "-Command", &script])
            .creation_flags(0x08000000) // CREATE_NO_WINDOW
            .status();
        match status {
            Ok(s) if s.success() => logln("autostart: shortcut created in the Startup folder"),
            _ => logln("autostart: failed to create the startup shortcut"),
        }
    } else {
        logln("autostart: declined by the user");
    }
    let _ = std::fs::write(&marker, b"");
}

pub fn logln(message: &str) {
    println!("{message}");
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(log_file())
    {
                let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
        let _ = writeln!(f, "[{stamp}] {message}");
    }
}

fn log_file() -> std::path::PathBuf {
    config_dir().join("agent.log")
}

fn config_dir() -> std::path::PathBuf {
    let dir = std::env::var("LOCALAPPDATA")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|_| std::path::PathBuf::from("."))
        .join("reventor-agent");
    let _ = std::fs::create_dir_all(&dir);
    dir
}

/// Searches for a paired phone advertising the sync service and starts the
/// sync loop once found; the address is saved so later starts connect directly.
/// While nothing is found, an info dialog points the user to the Bluetooth
/// settings (once) and the search keeps retrying every 15 seconds.
fn discovery_then_sync(own_id: [u8; 16]) {
    let mut hint_shown = false;
    loop {
        match rcomm::discover_paired_phone() {
            Ok(Some(addr)) => {
                logln(&format!("found paired R-Board phone {addr:012X} — saved to address.txt"));
                let _ = std::fs::write(config_dir().join("address.txt"), format!("{addr:012X}"));
                logln(&format!("R-Board agent — phone {addr:012X}"));
                sync_loop(addr, own_id);
                return;
            }
            Ok(None) => {
                if !hint_shown {
                    hint_shown = true;
                    let text = HSTRING::from(
                        "Kein gekoppeltes R-Board-Telefon gefunden.

Koppel dein Telefon in den Windows-Bluetooth-Einstellungen — die Suche läuft automatisch weiter.",
                    );
                    let caption = HSTRING::from("R-Board Clipboard Sync");
                    unsafe {
                        MessageBoxW(None, &text, &caption, MB_ICONINFORMATION);
                    }
                }
            }
            Err(e) => logln(&format!("pairing search failed: {e}")),
        }
        thread::sleep(Duration::from_secs(15));
    }
}

fn sync_loop(address: u64, own_id: [u8; 16]) {
    let mut backoff = Duration::from_secs(1);
    loop {
        CONNECTED.store(false, std::sync::atomic::Ordering::SeqCst);
        match rcomm::Connection::connect(address) {
            Ok(conn) => {
                logln("connected");
                CONNECTED.store(true, std::sync::atomic::Ordering::SeqCst);
                backoff = Duration::from_secs(1);
                if let Err(e) = run_session(Arc::new(conn), own_id) {
                    logln(&format!("session ended: {e}"));
                }
            }
            Err(e) => logln(&format!("connect failed: {e}")),
        }
        logln(&format!("reconnecting in {}s…", backoff.as_secs()));
        thread::sleep(backoff);
        backoff = (backoff * 2).min(Duration::from_secs(30));
    }
}

fn run_session(conn: Arc<rcomm::Connection>, own_id: [u8; 16]) -> Result<(), BoxError> {
    let dead = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let history = Arc::new(std::sync::Mutex::new(ClipHistory::new()));

    conn.write_frame(&Frame::Hello {
        proto_version: PROTO_VERSION,
        device_id: own_id,
        flags: FLAG_PUSH_SUPPORTED,
        name: rcomm::hostname(),
    })?;

    // converge on connect: push our current clipboard
    if let Some(text) = sysclip::get_text() {
        send_clip(&conn, &history, own_id, &text)?;
    }

    // reader thread: apply clips from the phone
    {
        let conn = conn.clone();
        let dead = dead.clone();
        let history = history.clone();
        thread::spawn(move || {
            loop {
                match conn.read_frame() {
                    Ok(Some(frame)) => {
                        if let Some(text) = handle_inbound(frame, own_id, &history) {
                            sysclip::set_text(&text);
                        }
                    }
                    Ok(None) => break,
                    Err(e) => {
                        logln(&format!("read error: {e}"));
                        break;
                    }
                }
            }
            dead.store(true, Ordering::SeqCst);
        });
    }

    // watcher loop: push local clipboard changes
    let result = (|| -> Result<(), BoxError> {
        let mut last_seq = sysclip::seq();
        loop {
            if dead.load(Ordering::SeqCst) {
                return Err("phone disconnected".into());
            }
            if let Some(seq) = sysclip::seq() {
                if last_seq != Some(seq) {
                    last_seq = Some(seq);
                    if let Some(text) = sysclip::get_text() {
                        send_clip(&conn, &history, own_id, &text)?;
                    }
                }
            }
            thread::sleep(Duration::from_millis(POLL_INTERVAL_MS));
        }
    })();

    conn.shutdown();
    result
}

struct Assembler {
    hash: [u8; 16],
    origin: [u8; 16],
    buf: Vec<u8>,
    total: u64,
}

static ASSEMBLER: Mutex<Option<Assembler>> = Mutex::new(None);

/// Applies echo-suppression rules; returns the text to put on the clipboard, if any.
fn handle_inbound(
    frame: Frame,
    own_id: [u8; 16],
    history: &Arc<Mutex<ClipHistory>>,
) -> Option<String> {
    match frame {
        Frame::ClipStart { hash, origin_id, total_len, .. } => {
            if total_len == 0 {
                return None;
            }
            let mut guard = ASSEMBLER.lock().ok()?;
            *guard = Some(Assembler {
                hash,
                origin: origin_id,
                buf: Vec::with_capacity(total_len.min(CHUNK_SIZE as u64 * 64) as usize),
                total: total_len,
            });
            None
        }
        Frame::ClipChunk { hash, seq: _, mut data } => {
            let mut guard = ASSEMBLER.lock().ok()?;
            let asm = guard.as_mut()?;
            if asm.hash != hash {
                return None;
            }
            asm.buf.append(&mut data);
            if (asm.buf.len() as u64) < asm.total {
                return None;
            }
            let asm = guard.take()?;
            drop(guard);
            apply_inbound(asm, own_id, history)
        }
        _ => None, // HELLO from peer carries nothing we need; PONG ignored in v1
    }
}

fn apply_inbound(
    asm: Assembler,
    own_id: [u8; 16],
    history: &Arc<Mutex<ClipHistory>>,
) -> Option<String> {
    if asm.origin == own_id {
        return None;
    }
    let text = String::from_utf8(asm.buf).ok()?;
    if protocol::sha256_first16(&text) != asm.hash {
        logln("checksum mismatch, dropping clip");
        return None;
    }
    let mut hist = history.lock().ok()?;
    if hist.seen_recently(&asm.hash) {
        return None;
    }
    hist.record(&asm.hash);
    Some(text)
}

fn send_clip(
    conn: &rcomm::Connection,
    history: &Arc<Mutex<ClipHistory>>,
    own_id: [u8; 16],
    text: &str,
) -> Result<(), BoxError> {
    if text.is_empty() {
        return Ok(());
    }
    let hash = protocol::sha256_first16(text);
    {
        let mut hist = history.lock().map_err(|_| "lock poisoned".to_string())?;
        if hist.seen_recently(&hash) {
            return Ok(());
        }
        hist.record(&hash);
    }
    let bytes = text.as_bytes();
    conn.write_frame(&Frame::ClipStart {
        hash,
        origin_id: own_id,
        timestamp_ms: SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as u64,
        sensitive: false,
        total_len: bytes.len() as u64,
        mime: MIME_TEXT.into(),
    })?;
    for (seq, chunk) in bytes.chunks(CHUNK_SIZE).enumerate() {
        conn.write_frame(&Frame::ClipChunk { hash, seq: seq as u32, data: chunk.to_vec() })?;
    }
    Ok(())
}

fn load_or_create_device_id() -> [u8; 16] {
    let dir = config_dir();
    let path = dir.join("device_id");
    if let Ok(hex) = std::fs::read_to_string(&path) {
        if let Some(id) = hex_to_id(hex.trim()) {
            return id;
        }
    }
    let id = rand_id();
    if let Ok(mut f) = std::fs::File::create(&path) {
        let _ = writeln!(f, "{}", id.iter().map(|b| format!("{b:02x}")).collect::<String>());
    }
    id
}

fn hex_to_id(hex: &str) -> Option<[u8; 16]> {
    if hex.len() != 32 {
        return None;
    }
    let bytes = (0..16)
        .map(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).ok())
        .collect::<Option<Vec<u8>>>()?;
    Some(bytes.try_into().unwrap())
}

fn rand_id() -> [u8; 16] {
    let mut buf = [0u8; 16];
    let mut seed = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos() as u64
        ^ ((std::process::id() as u64) << 32);
    for b in buf.iter_mut() {
        seed = seed
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        *b = (seed >> 33) as u8;
    }
    buf
}
