//! First-run setup wizard.
//!
//! One window, a few pages, everything automated: install (Programs folder +
//! Start-menu/autostart shortcuts), Bluetooth check (switches the radio on),
//! phone discovery with one-click pairing, sync-service check with concrete
//! phone-side instructions, and a live two-way connection test. Shown
//! automatically when no phone is configured; also reachable from the tray
//! menu and via `--setup`.
//!
//! The UI thread owns the window and a 300 ms timer; every BT/WinRT operation
//! runs on short-lived worker threads that only mutate `Shared`. The tick
//! reads `Shared`, refreshes labels and drives the page transitions.

use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use windows::core::{w, HSTRING};
use windows::Devices::Bluetooth::BluetoothDevice;
use windows::Devices::Enumeration::{DeviceInformation, DevicePairingResultStatus};
use windows::Devices::Radios::{Radio, RadioAccessStatus, RadioKind, RadioState};
use windows::Win32::Foundation::{COLORREF, HWND, LPARAM, LRESULT, RECT, WPARAM};
use windows::Win32::Graphics::Gdi::{
    CreateFontW, CreateSolidBrush, CLEARTYPE_QUALITY, CLIP_DEFAULT_PRECIS, COLOR_WINDOW,
    DEFAULT_CHARSET, DEFAULT_PITCH, FF_DONTCARE, FW_BOLD, FW_NORMAL, HBRUSH, HFONT, HDC,
    OUT_DEFAULT_PRECIS, SetBkMode, SetTextColor, TRANSPARENT,
};
use windows::Win32::System::Com::{CoInitializeEx, COINIT_MULTITHREADED};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::HiDpi::{
    GetDpiForWindow, SetProcessDpiAwarenessContext, DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2,
};
use windows::Win32::UI::Input::KeyboardAndMouse::{EnableWindow, IsWindowEnabled};
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, GetClientRect,
    GetDlgCtrlID, GetDlgItem, GetMessageW, GetWindow, GetWindowRect, IDC_ARROW,
    KillTimer, LBN_SELCHANGE, LB_ADDSTRING, LB_GETCURSEL, LB_RESETCONTENT, LB_SETCURSEL,
    LBS_NOTIFY, LBS_NOINTEGRALHEIGHT, LoadCursorW, MSG, PostQuitMessage, RegisterClassW,
    SendMessageW, SetTimer, SetWindowLongPtrW, SetWindowPos, SetWindowTextW, ShowWindow,
    SystemParametersInfoW, TranslateMessage, BS_DEFPUSHBUTTON, BS_PUSHBUTTON, CW_USEDEFAULT,
    GW_CHILD, GW_HWNDNEXT, GWLP_USERDATA, SPI_GETWORKAREA, SWP_NOZORDER, SW_SHOW, WINDOW_EX_STYLE,
    WINDOW_STYLE, WM_CLOSE, WM_COMMAND, WM_CTLCOLORSTATIC, WM_DESTROY, WM_SETFONT, WM_TIMER,
    WNDCLASSW, WS_BORDER, WS_CAPTION, WS_CHILD, WS_EX_CLIENTEDGE, WS_MINIMIZEBOX, WS_SYSMENU,
    WS_TABSTOP, WS_VISIBLE,
};

use crate::rcomm::{self, Wait};
use crate::sysclip;

const TEST_TEXT: &str = "R-Board funktioniert! ✓ Dies ist ein Testtext der Zwischenablage-Sync.";

/// classic Bluetooth AEPs that are currently present but not paired
const NEARBY_SELECTOR: &str = "System.Devices.Aep.ProtocolId:=\"{e0cbf06c-cd8b-4647-bb8b-7b2dc9623785}\" \
     AND System.Devices.Aep.IsPaired:=System.StructuredQueryType.Boolean#False \
     AND System.Devices.Aep.IsPresent:=System.StructuredQueryType.Boolean#True";

const SERVICE_INSTRUCTIONS: &str = "Der Sync-Dienst läuft auf dem Telefon noch nicht. Auf dem Telefon:\n\n  1.  R-Board öffnen (die Tastatur-App)\n  2.  Sync-Assistent öffnen (\"R-Board einrichten\")\n  3.  \"SYNC STARTEN\" antippen — beim ersten Mal die Bluetooth-Berechtigung erlauben\n\nXiaomi/HyperOS zusätzlich: Einstellungen → Apps → R-Board → Akku → \"Keine Einschränkungen\".\n\nSobald der Dienst läuft, springt der Assistent automatisch weiter.";

// control ids
const IDC_TITLE: isize = 100;
const IDC_BODY: isize = 101;
const IDC_STATUS: isize = 102;
const IDC_LIST: isize = 103;
const IDC_BTN_PRIMARY: isize = 110;
const IDC_BTN_SECONDARY: isize = 111;
const IDC_BTN_TERTIARY: isize = 112;

#[derive(Clone, Copy, PartialEq, Default)]
enum Page {
    #[default]
    Welcome,
    Install,
    Bluetooth,
    Pair,
    Service,
    Test,
}

#[derive(Clone, Copy, PartialEq, Default)]
enum StatusKind {
    #[default]
    Neutral,
    Busy,
    Good,
    Bad,
}

#[derive(Clone)]
struct Nearby {
    name: String,
    id: String,
}

#[derive(Default)]
struct Shared {
    page: Page,
    status: String,
    status_kind: StatusKind,
    devices: Vec<Nearby>,
    devices_dirty: bool,
    bt_ok: bool,
    install_done: bool,
    pair_busy: bool,
    pair_request: Option<String>,
    scan_request: bool,
    radio_restart: bool,
    found: Option<(u64, String)>, // address, display name
    service_ok: bool,
    service_hint: bool,
    service_hint_shown: bool,
    sync_started: bool,
    connected_seen: bool,
    test_text_set: bool,
    clip_baseline: Option<u32>,
    settle_at: Option<Instant>,
    inbound: bool,
    quit: bool,
}

type Sh = Arc<Mutex<Shared>>;

pub enum Outcome {
    /// the wizard started the sync thread itself — main must not start another
    SyncStarted,
    /// wizard closed before a phone was configured
    NoPhone,
}

static WIZARD_RUNNING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Opens the wizard and blocks until it is closed. Call on a dedicated thread;
/// a second concurrent call just reports the current state.
pub fn run_setup(own_id: [u8; 16]) -> Outcome {
    if WIZARD_RUNNING.swap(true, Ordering::SeqCst) {
        return if crate::SYNC_STARTED.load(Ordering::SeqCst) {
            Outcome::SyncStarted
        } else {
            Outcome::NoPhone
        };
    }
    let result = run_wizard(own_id);
    WIZARD_RUNNING.store(false, Ordering::SeqCst);
    result
}

fn run_wizard(own_id: [u8; 16]) -> Outcome {
    unsafe {
        let _ = SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
    let sh: Sh = Arc::new(Mutex::new(Shared {
        page: Page::Welcome,
        status: "Bereit.".into(),
        status_kind: StatusKind::Neutral,
        ..Shared::default()
    }));

    unsafe {
        let hinstance = GetModuleHandleW(None).unwrap();
        let class_name = w!("RBoardSetupWnd");
        let class = WNDCLASSW {
            lpfnWndProc: Some(wnd_proc),
            hInstance: hinstance.into(),
            hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
            hbrBackground: HBRUSH((COLOR_WINDOW.0 + 1) as *mut _),
            lpszClassName: class_name,
            ..Default::default()
        };
        RegisterClassW(&class);

        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            class_name,
            &HSTRING::from("R-Board Clipboard Sync — Einrichtung"),
            WS_CAPTION | WS_MINIMIZEBOX | WS_SYSMENU,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            None,
            None,
            Some(hinstance.into()),
            None,
        )
        .expect("create wizard window");

        let wiz = Box::new(Wizard {
            sh: sh.clone(),
            own_id,
            fonts: Fonts::new(hwnd),
            bg_brush: CreateSolidBrush(COLORREF(0x00FF_FFFF)),
        });
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, Box::into_raw(wiz) as isize);

        // target client area 560x640, centered in the work area
        let s = dpi_scale(hwnd);
        let client = (scaled(560, s), scaled(640, s));
        let mut outer = RECT::default();
        let _ = GetWindowRect(hwnd, &mut outer);
        let mut inner = RECT::default();
        let _ = GetClientRect(hwnd, &mut inner);
        let frame_w = (outer.right - outer.left) - (inner.right - inner.left);
        let frame_h = (outer.bottom - outer.top) - (inner.bottom - inner.top);
        let mut work = RECT::default();
        let _ = SystemParametersInfoW(SPI_GETWORKAREA, 0, Some(&mut work as *mut _ as _), Default::default());
        let x = work.left + (work.right - work.left - (client.0 + frame_w)) / 2;
        let y = work.top + (work.bottom - work.top - (client.1 + frame_h)) / 2;
        let _ = SetWindowPos(hwnd, None, x, y, client.0 + frame_w, client.1 + frame_h, SWP_NOZORDER);
        let _ = ShowWindow(hwnd, SW_SHOW);

        show_page(hwnd, Page::Welcome);
        SetTimer(Some(hwnd), 1, 300, None);

        let mut msg = MSG::default();
        while GetMessageW(&mut msg, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }

    let s = sh.lock().unwrap();
    if s.sync_started {
        Outcome::SyncStarted
    } else {
        Outcome::NoPhone
    }
}

struct Fonts {
    title: HFONT,
    body: HFONT,
    small: HFONT,
}

impl Fonts {
    fn new(hwnd: HWND) -> Self {
        let s = dpi_scale(hwnd);
        Self {
            title: make_font(scaled(17, s), true),
            body: make_font(scaled(12, s), false),
            small: make_font(scaled(11, s), false),
        }
    }
}

fn dpi_scale(hwnd: HWND) -> i32 {
    unsafe { GetDpiForWindow(hwnd) }.max(96) as i32 * 100 / 96
}

fn scaled(v: i32, s: i32) -> i32 {
    v * s / 100
}

fn make_font(pt: i32, bold: bool) -> HFONT {
    unsafe {
        CreateFontW(
            -pt,
            0,
            0,
            0,
            if bold { FW_BOLD.0 } else { FW_NORMAL.0 } as i32,
            0,
            0,
            0,
            DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS,
            CLIP_DEFAULT_PRECIS,
            CLEARTYPE_QUALITY,
            (DEFAULT_PITCH.0 | FF_DONTCARE.0) as u32,
            w!("Segoe UI"),
        )
    }
}

struct Wizard {
    sh: Sh,
    own_id: [u8; 16],
    fonts: Fonts,
    bg_brush: HBRUSH,
}

unsafe fn wizard_of(hwnd: HWND) -> Option<&'static mut Wizard> {
    let ptr = windows::Win32::UI::WindowsAndMessaging::GetWindowLongPtrW(hwnd, GWLP_USERDATA);
    if ptr == 0 {
        None
    } else {
        Some(&mut *(ptr as *mut Wizard))
    }
}

/// child control by id
unsafe fn dlg(hwnd: HWND, id: isize) -> Option<HWND> {
    GetDlgItem(Some(hwnd), id as i32).ok()
}

unsafe extern "system" fn wnd_proc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match msg {
        WM_TIMER => {
            if let Some(wiz) = wizard_of(hwnd) {
                tick(hwnd, wiz);
            }
            LRESULT(0)
        }
        WM_COMMAND => {
            let ctrl_id = (wparam.0 & 0xFFFF) as isize;
            let notif = (wparam.0 >> 16) as u16;
            if let Some(wiz) = wizard_of(hwnd) {
                handle_command(hwnd, wiz, ctrl_id, notif);
            }
            LRESULT(0)
        }
        WM_CTLCOLORSTATIC => {
            if let Some(wiz) = wizard_of(hwnd) {
                let hdc = HDC(wparam.0 as *mut _);
                let id = GetDlgCtrlID(HWND(lparam.0 as *mut _)) as isize;
                let color = match id {
                    IDC_STATUS => {
                        let sh = wiz.sh.lock().unwrap();
                        match sh.status_kind {
                            StatusKind::Good => COLORREF(0x00_00_9C_18),
                            StatusKind::Bad => COLORREF(0x00_20_39_D5),
                            StatusKind::Busy => COLORREF(0x00_2A_76_9A),
                            StatusKind::Neutral => COLORREF(0x00_30_30_30),
                        }
                    }
                    _ => COLORREF(0x00_20_20_20),
                };
                SetTextColor(hdc, color);
                SetBkMode(hdc, TRANSPARENT);
                return LRESULT(wiz.bg_brush.0 as isize);
            }
            DefWindowProcW(hwnd, msg, wparam, lparam)
        }
        WM_CLOSE => {
            if let Some(wiz) = wizard_of(hwnd) {
                wiz.sh.lock().unwrap().quit = true;
            }
            let _ = DestroyWindow(hwnd);
            LRESULT(0)
        }
        WM_DESTROY => {
            let _ = KillTimer(Some(hwnd), 1);
            PostQuitMessage(0);
            LRESULT(0)
        }
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

fn close_wizard(hwnd: HWND, sh: &mut Shared) {
    sh.quit = true;
    unsafe {
        let _ = DestroyWindow(hwnd);
    }
}

fn handle_command(hwnd: HWND, wiz: &mut Wizard, ctrl_id: isize, notif: u16) {
    let mut sh = wiz.sh.lock().unwrap();
    match ctrl_id {
        IDC_BTN_PRIMARY => match sh.page {
            Page::Welcome => {
                drop(sh);
                goto_page(hwnd, wiz, Page::Install);
            }
            Page::Pair => {
                if !sh.pair_busy {
                    if let Some(id) = selected_device(hwnd, &sh) {
                        sh.pair_request = Some(id);
                        sh.status = "Kopplung läuft — bestätige auf BEIDEN Geräten (Windows-Fenster und Telefon).".into();
                        sh.status_kind = StatusKind::Busy;
                    }
                }
            }
            Page::Test => close_wizard(hwnd, &mut sh),
            _ => {}
        },
        IDC_BTN_SECONDARY => match sh.page {
            Page::Bluetooth => {
                drop(sh);
                goto_page(hwnd, wiz, Page::Pair);
            }
            Page::Pair => {
                sh.scan_request = true;
                sh.status = "Suche neu …".into();
                sh.status_kind = StatusKind::Busy;
            }
            Page::Service | Page::Test => close_wizard(hwnd, &mut sh),
            _ => {}
        },
        IDC_BTN_TERTIARY => {
            if sh.page == Page::Pair {
                sh.radio_restart = true;
                sh.status = "Bluetooth-Modul startet neu … (dauert ~10 Sekunden)".into();
                sh.status_kind = StatusKind::Busy;
            }
        }
        IDC_LIST => {
            if notif as u32 == LBN_SELCHANGE {
                unsafe {
                    let has_sel = listbox_selection(hwnd) >= 0;
                    if let Some(primary) = dlg(hwnd, IDC_BTN_PRIMARY) {
                        let _ = EnableWindow(primary, (has_sel && !sh.pair_busy).into());
                    }
                }
            }
        }
        _ => {}
    }
}

fn selected_device(hwnd: HWND, sh: &Shared) -> Option<String> {
    // a pending list refresh means the index could point at another device
    if sh.devices_dirty {
        return None;
    }
    let sel = unsafe { listbox_selection(hwnd) };
    if sel < 0 {
        return None;
    }
    sh.devices.get(sel as usize).map(|d| d.id.clone())
}

unsafe fn listbox_selection(hwnd: HWND) -> i32 {
    match dlg(hwnd, IDC_LIST) {
        Some(list) => SendMessageW(list, LB_GETCURSEL, None, None).0 as i32,
        None => -1,
    }
}

fn hmenu(id: isize) -> windows::Win32::UI::WindowsAndMessaging::HMENU {
    windows::Win32::UI::WindowsAndMessaging::HMENU(id as *mut _)
}

// ---------------------------------------------------------------- pages

fn page_title(page: Page) -> &'static str {
    match page {
        Page::Welcome => "Willkommen beim R-Board Setup-Assistenten",
        Page::Install => "R-Board wird installiert …",
        Page::Bluetooth => "Bluetooth prüfen",
        Page::Pair => "Telefon finden und koppeln",
        Page::Service => "Sync-Dienst prüfen",
        Page::Test => "Verbindung testen",
    }
}

fn page_body(page: Page) -> &'static str {
    match page {
        Page::Welcome => "Dieser Assistent richtet die Zwischenablage-Sync zwischen diesem PC und deinem Android-Telefon komplett ein:\n\n  1.  Installation (Programm-Ordner, Startmenü, Autostart)\n  2.  Bluetooth prüfen — wird bei Bedarf automatisch eingeschaltet\n  3.  Telefon finden und mit einem Klick koppeln\n  4.  Sync-Dienst auf dem Telefon prüfen\n  5.  Verbindung in beide Richtungen testen\n\nHalte dein Telefon bereit und leg los!",
        Page::Install => "R-Board wird installiert:\n\n  •  Programm nach %LOCALAPPDATA%\\Programs\\R-Board kopieren\n  •  Start-Menü-Eintrag \"R-Board Clipboard Sync\" anlegen\n  •  Autostart mit Windows einschalten\n\nDas dauert nur wenige Sekunden.",
        Page::Bluetooth => "Bluetooth wird geprüft. Ist es ausgeschaltet, schaltet der Assistent es automatisch ein.",
        Page::Pair => "Auf dem Telefon: R-Board öffnen → \"R-Board einrichten\" → \"Sichtbar machen\" antippen.\n\nDein Telefon erscheint dann in der Liste — auswählen und auf \"Koppeln\" klicken. Danach auf beiden Geräten bestätigen: Windows zeigt einen Code, das Telefon fragt nach.\n\nTipp: Wer das Telefon lieber über die Windows-Bluetooth-Einstellungen koppelt, kann das auch tun — danach hier einfach \"Erneut suchen\" klicken.",
        Page::Service => "Der Assistent prüft, ob der Sync-Dienst auf dem Telefon erreichbar ist.\n\nDauert es länger als ein paar Sekunden, erscheint hier eine Anleitung für das Telefon.",
        Page::Test => "Der Assistent prüft jetzt die Verbindung in beide Richtungen:\n\n  1.  PC → Telefon: Ein Testtext landet in der Zwischenablage — prüfe auf dem Telefon, ob er ankommt (z. B. in einer Notiz einfügen).\n  2.  Telefon → PC: Kopiere irgendeinen Text auf dem Telefon (z. B. eine Nachricht markieren und kopieren). Sobald er hier ankommt, zeigt der Assistent grünes Licht.\n\nDanach läuft die Sync automatisch im Hintergrund — es ist nichts weiter zu tun.",
    }
}

fn show_page(hwnd: HWND, page: Page) {
    unsafe {
        clear_children(hwnd);
        let wiz = match wizard_of(hwnd) {
            Some(w) => w,
            None => return,
        };
        let s = dpi_scale(hwnd);
        let margin = scaled(28, s);
        let client_w = scaled(560, s);
        let client_h = scaled(640, s);
        let content_w = client_w - 2 * margin;

        make_static(hwnd, page_title(page), IDC_TITLE, margin, scaled(22, s), content_w, scaled(30, s), wiz.fonts.title);

        let body_y = scaled(62, s);
        let body_h = if page == Page::Pair { scaled(180, s) } else { scaled(300, s) };
        make_static(hwnd, page_body(page), IDC_BODY, margin, body_y, content_w, body_h, wiz.fonts.body);

        let status_y = client_h - scaled(152, s);
        make_static(hwnd, "", IDC_STATUS, margin, status_y, content_w, scaled(30, s), wiz.fonts.small);

        let btn_w = scaled(170, s);
        let btn_h = scaled(36, s);
        let btn_y = client_h - scaled(62, s);
        let small_w = scaled(150, s);
        let gap = scaled(10, s);

        let (primary, secondary, tertiary): (Option<&str>, Option<&str>, Option<&str>) = match page {
            Page::Welcome => (Some("Einrichtung starten"), None, None),
            Page::Install => (None, None, None),
            Page::Bluetooth => (None, Some("Überspringen"), None),
            Page::Pair => (
                Some("Koppeln"),
                Some("Erneut suchen"),
                Some("Bluetooth-Modul neu starten"),
            ),
            Page::Service => (None, Some("Fenster schließen"), None),
            Page::Test => (Some("Fertig stellen"), Some("Überspringen"), None),
        };

        if let Some(label) = primary {
            let b = make_button(hwnd, label, IDC_BTN_PRIMARY, client_w - margin - btn_w, btn_y, btn_w, btn_h, &wiz.fonts.body, true);
            if page == Page::Pair || page == Page::Test {
                let _ = EnableWindow(b, false.into());
            }
        }
        if let Some(label) = secondary {
            make_button(hwnd, label, IDC_BTN_SECONDARY, client_w - margin - btn_w - gap - small_w, btn_y, small_w, btn_h, &wiz.fonts.body, false);
        }
        if let Some(label) = tertiary {
            // own row above the buttons — the bottom row holds secondary+primary
            make_button(hwnd, label, IDC_BTN_TERTIARY, margin, client_h - scaled(106, s), scaled(215, s), btn_h, &wiz.fonts.small, false);
        }

        if page == Page::Pair {
            let list = CreateWindowExW(
                WS_EX_CLIENTEDGE,
                w!("LISTBOX"),
                w!(""),
                WS_CHILD | WS_VISIBLE | WS_TABSTOP | WS_BORDER
                    | WINDOW_STYLE(LBS_NOTIFY as u32)
                    | WINDOW_STYLE(LBS_NOINTEGRALHEIGHT as u32),
                margin,
                body_y + body_h + scaled(12, s),
                content_w,
                client_h - (body_y + body_h + scaled(12, s)) - scaled(180, s),
                Some(hwnd),
                Some(hmenu(IDC_LIST)),
                Some(GetModuleHandleW(None).unwrap().into()),
                None,
            )
            .expect("listbox");
            SendMessageW(list, WM_SETFONT, Some(WPARAM(wiz.fonts.body.0 as usize)), Some(LPARAM(0)));
        }

        spawn_page_worker(wiz.sh.clone(), page);
    }
}

unsafe fn make_static(
    hwnd: HWND,
    text: &str,
    id: isize,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    font: HFONT,
) -> HWND {
    let ctl = CreateWindowExW(
        WINDOW_EX_STYLE::default(),
        w!("STATIC"),
        &HSTRING::from(text),
        WS_CHILD | WS_VISIBLE,
        x,
        y,
        w,
        h,
        Some(hwnd),
        Some(hmenu(id)),
        Some(GetModuleHandleW(None).unwrap().into()),
        None,
    )
    .expect("static");
    SendMessageW(ctl, WM_SETFONT, Some(WPARAM(font.0 as usize)), Some(LPARAM(0)));
    ctl
}

unsafe fn make_button(
    hwnd: HWND,
    text: &str,
    id: isize,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    font: &HFONT,
    def: bool,
) -> HWND {
        let ctl = CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            w!("BUTTON"),
            &HSTRING::from(text),
            WS_CHILD | WS_VISIBLE | WS_TABSTOP
                | if def { WINDOW_STYLE(BS_DEFPUSHBUTTON as u32) } else { WINDOW_STYLE(BS_PUSHBUTTON as u32) },
        x,
        y,
        w,
        h,
        Some(hwnd),
        Some(hmenu(id)),
        Some(GetModuleHandleW(None).unwrap().into()),
        None,
    )
    .expect("button");
    SendMessageW(ctl, WM_SETFONT, Some(WPARAM(font.0 as usize)), Some(LPARAM(0)));
    ctl
}

unsafe fn clear_children(hwnd: HWND) {
    let mut cursor = GetWindow(hwnd, GW_CHILD);
    while let Ok(child) = cursor {
        cursor = GetWindow(child, GW_HWNDNEXT);
        let _ = DestroyWindow(child);
    }
}

// ---------------------------------------------------------------- tick & transitions

fn tick(hwnd: HWND, wiz: &mut Wizard) {
    let mut sh = wiz.sh.lock().unwrap();

    // refresh the status label on every tick
    unsafe {
        if let Some(status) = dlg(hwnd, IDC_STATUS) {
            let _ = SetWindowTextW(status, &HSTRING::from(sh.status.clone()));
        }
    }
    if sh.quit {
        return;
    }
    match sh.page {
        Page::Install if sh.install_done => {
            drop(sh);
            goto_page(hwnd, wiz, Page::Bluetooth);
        }
        Page::Bluetooth if sh.bt_ok => {
            drop(sh);
            goto_page(hwnd, wiz, Page::Pair);
        }
        Page::Pair => {
            if sh.found.is_some() {
                drop(sh);
                goto_page(hwnd, wiz, Page::Service);
            } else if sh.devices_dirty {
                let devices = sh.devices.clone();
                sh.devices_dirty = false;
                drop(sh);
                unsafe { refresh_listbox(hwnd, &devices) };
            } else {
                drop(sh);
            }
        }
        Page::Service => {
            if sh.service_hint && !sh.service_hint_shown {
                sh.service_hint_shown = true;
                unsafe {
                    if let Some(body) = dlg(hwnd, IDC_BODY) {
                        let _ = SetWindowTextW(body, &HSTRING::from(SERVICE_INSTRUCTIONS));
                    }
                }
            }
            if sh.service_ok && !sh.sync_started {
                let (addr, _) = sh.found.clone().expect("service ok implies found");
                sh.sync_started = true;
                crate::SYNC_STARTED.store(true, Ordering::SeqCst);
                let own_id = wiz.own_id;
                drop(sh);
                crate::logln("wizard: handing over to the sync loop");
                crate::start_sync_thread(addr, own_id);
                goto_page(hwnd, wiz, Page::Test);
            } else {
                drop(sh);
            }
        }
        Page::Test => {
            let connected = crate::CONNECTED.load(Ordering::SeqCst);
            if connected && !sh.connected_seen {
                sh.connected_seen = true;
                crate::logln("wizard: connection established — clipboard test begins");
            }
            if connected && !sh.test_text_set {
                // let the initial converge settle first (the phone pushes its
                // clipboard right after accepting), otherwise it would fake the
                // inbound test
                if sh.settle_at.is_none() {
                    sh.settle_at = Some(Instant::now());
                } else if sh.settle_at.map(|t| t.elapsed() > Duration::from_secs(3)).unwrap_or(false) {
                    sysclip::set_text(TEST_TEXT);
                    // only run the inbound test with a readable baseline — a
                    // None here would fake an instant "text arrived"
                    if let Some(baseline) = sysclip::seq() {
                        sh.clip_baseline = Some(baseline);
                        sh.test_text_set = true;
                        crate::logln("wizard: test text placed on the clipboard");
                    }
                }
            }
            if sh.test_text_set && !sh.inbound {
                if let Some(seq) = sysclip::seq() {
                    if Some(seq) != sh.clip_baseline {
                        sh.inbound = true;
                        crate::logln("wizard: inbound clipboard change — two-way test passed");
                    }
                }
            }
            let (text, kind) = if !sh.connected_seen {
                ("Verbinde mit dem Telefon …".to_string(), StatusKind::Busy)
            } else if !sh.test_text_set {
                ("Verbunden ✓ — der Testtext wird vorbereitet …".to_string(), StatusKind::Good)
            } else if !sh.inbound {
                ("PC → Telefon gesendet ✓ — kopiere jetzt irgendeinen Text auf dem Telefon; sobald er hier ankommt, ist alles fertig.".to_string(), StatusKind::Busy)
            } else {
                ("Alles funktioniert ✓ Text ist in beide Richtungen gegangen. Die Sync läuft jetzt im Hintergrund.".to_string(), StatusKind::Good)
            };
            sh.status = text;
            sh.status_kind = kind;
            if sh.connected_seen {
                unsafe {
                    if let Some(finish) = dlg(hwnd, IDC_BTN_PRIMARY) {
                        if !IsWindowEnabled(finish).as_bool() {
                            let _ = EnableWindow(finish, true.into());
                        }
                    }
                }
            }
            drop(sh);
        }
        _ => drop(sh),
    }
}

unsafe fn refresh_listbox(hwnd: HWND, devices: &[Nearby]) {
    let list = match dlg(hwnd, IDC_LIST) {
        Some(l) => l,
        None => return,
    };
    let sel = SendMessageW(list, LB_GETCURSEL, None, None).0 as i32;
    let selected = devices.get(sel as usize).map(|d| d.name.clone());
    SendMessageW(list, LB_RESETCONTENT, None, None);
    let mut new_sel: i32 = -1;
    for (i, d) in devices.iter().enumerate() {
        let text = HSTRING::from(d.name.clone());
        SendMessageW(list, LB_ADDSTRING, None, Some(LPARAM(text.as_ptr() as isize)));
        if selected.as_deref() == Some(d.name.as_str()) {
            new_sel = i as i32;
        }
    }
    if new_sel >= 0 {
        SendMessageW(list, LB_SETCURSEL, Some(WPARAM(new_sel as usize)), None);
    }
}

fn goto_page(hwnd: HWND, wiz: &mut Wizard, page: Page) {
    {
        let mut sh = wiz.sh.lock().unwrap();
        sh.page = page;
        sh.status_kind = if page == Page::Test { StatusKind::Neutral } else { StatusKind::Busy };
        sh.status = match page {
            Page::Welcome => "Bereit.".to_string(),
            Page::Install => "Installiere …".to_string(),
            Page::Bluetooth => "Prüfe Bluetooth …".to_string(),
            Page::Pair => "Prüfe, ob dein Telefon schon gekoppelt ist …".to_string(),
            Page::Service => match sh.found.clone() {
                Some((_, name)) => format!("Prüfe den Sync-Dienst auf {name} …"),
                None => "Prüfe den Sync-Dienst …".to_string(),
            },
            Page::Test => "Verbinde mit dem Telefon …".to_string(),
        };
        // a fresh pass through the check pages resets all results (this is also
        // the repair path from the tray menu)
        if page == Page::Pair {
            sh.found = None;
            sh.service_ok = false;
            sh.service_hint = false;
            sh.service_hint_shown = false;
            sh.sync_started = false;
            crate::SYNC_STARTED.store(false, Ordering::SeqCst);
            sh.connected_seen = false;
            sh.test_text_set = false;
            sh.clip_baseline = None;
            sh.settle_at = None;
            sh.inbound = false;
        }
    }
    show_page(hwnd, page);
}

fn spawn_page_worker(sh: Sh, page: Page) {
    let (name, spawned): (&str, std::io::Result<thread::JoinHandle<()>>) = match page {
        Page::Install => ("setup-install", thread::Builder::new().name("setup-install".into()).spawn(move || install_worker(sh))),
        Page::Bluetooth => ("setup-bt", thread::Builder::new().name("setup-bt".into()).spawn(move || bt_worker(sh))),
        Page::Pair => ("setup-pair", thread::Builder::new().name("setup-pair".into()).spawn(move || pair_worker(sh))),
        Page::Service => ("setup-service", thread::Builder::new().name("setup-service".into()).spawn(move || service_worker(sh))),
        _ => return,
    };
    if let Err(e) = spawned {
        crate::logln(&format!("wizard: failed to spawn {name} worker: {e}"));
    }
}

// ---------------------------------------------------------------- workers

fn com_init() {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
}

/// is this worker still responsible for the current page?
fn active(sh: &Shared, page: Page) -> bool {
    !sh.quit && sh.page == page
}

fn install_worker(sh: Sh) {
    com_init();
    let exe = match std::env::current_exe() {
        Ok(e) => e,
        Err(e) => {
            crate::logln(&format!("wizard: current_exe failed: {e}"));
            finish_install(&sh, false);
            return;
        }
    };
    let target = std::env::var("LOCALAPPDATA").ok().map(|base| {
        std::path::PathBuf::from(base).join("Programs").join("R-Board").join("R-Board-Service.exe")
    });

    if let Some(target) = target {
        if exe != target {
            if let Some(dir) = target.parent() {
                let _ = std::fs::create_dir_all(dir);
            }
            match std::fs::copy(&exe, &target) {
                Ok(_) => {
                    crate::logln(&format!("wizard: installed to {}", target.display()));
                    install_shortcuts(&target);
                    mark_autostart_asked();
                    // the wizard continues in the installed copy
                    use std::os::windows::process::CommandExt;
                    let spawned = std::process::Command::new(&target)
                        .arg("--setup")
                        .creation_flags(0x0800_0000) // CREATE_NO_WINDOW
                        .spawn();
                    match spawned {
                        Ok(_) => std::process::exit(0),
                        Err(e) => {
                            crate::logln(&format!("wizard: relaunch failed: {e} — continuing from here"));
                            finish_install(&sh, true);
                            return;
                        }
                    }
                }
                Err(e) => {
                    crate::logln(&format!("wizard: install copy failed: {e} — staying where we are"));
                    finish_install(&sh, true);
                    return;
                }
            }
        }
        install_shortcuts(&target);
    }
    mark_autostart_asked();
    finish_install(&sh, true);
}

fn install_shortcuts(target: &std::path::Path) {
    let exe = target.display().to_string();
    // real paths: WScript.Shell does not expand %APPDATA% etc.
    let roaming = std::env::var("APPDATA").unwrap_or_default();
    if !roaming.is_empty() {
        let base = std::path::PathBuf::from(roaming).join("Microsoft").join("Windows").join("Start Menu").join("Programs");
        make_shortcut(&exe, &(base.join("R-Board Clipboard Sync.lnk").display().to_string()));
        make_shortcut(&exe, &(base.join("Startup").join("R-Board Clipboard Sync.lnk").display().to_string()));
    }
}

fn make_shortcut(target_exe: &str, lnk: &str) {
    let script = format!(
        "$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{lnk}'); $s.TargetPath='{target_exe}'; $s.Save()"
    );
    use std::os::windows::process::CommandExt;
    let status = std::process::Command::new("powershell")
        .args(["-NoProfile", "-WindowStyle", "Hidden", "-Command", &script])
        .creation_flags(0x0800_0000)
        .status();
    let ok = matches!(status, Ok(s) if s.success());
    crate::logln(&format!("wizard: shortcut {lnk}: {}", if ok { "ok" } else { "failed" }));
}

fn mark_autostart_asked() {
    // the wizard decides autostart itself — never show the old Yes/No prompt
    let _ = std::fs::write(crate::config_dir().join("autostart_asked"), b"");
}

fn finish_install(sh: &Sh, ok: bool) {
    let mut s = sh.lock().unwrap();
    s.install_done = true;
    if !ok {
        s.status = "Die Installation konnte nicht abgeschlossen werden — der Assistent läuft trotzdem weiter.".into();
        s.status_kind = StatusKind::Bad;
    }
}

fn bt_worker(sh: Sh) {
    com_init();
    let mut on_seen = 0u32;
    loop {
        {
            let s = sh.lock().unwrap();
            if !active(&s, Page::Bluetooth) {
                return;
            }
        }
        match bluetooth_state() {
            BtState::On => {
                on_seen += 1;
                let mut s = sh.lock().unwrap();
                if !active(&s, Page::Bluetooth) {
                    return;
                }
                s.status = "Bluetooth ist eingeschaltet ✓".into();
                s.status_kind = StatusKind::Good;
                if on_seen >= 2 {
                    s.bt_ok = true;
                }
            }
            BtState::Off | BtState::Disabled => {
                on_seen = 0;
                {
                    let mut s = sh.lock().unwrap();
                    if !active(&s, Page::Bluetooth) {
                        return;
                    }
                    s.status = "Bluetooth ist aus — wird eingeschaltet …".into();
                    s.status_kind = StatusKind::Busy;
                }
                if let Err(e) = set_bluetooth(true) {
                    crate::logln(&format!("wizard: switching bluetooth on failed: {e}"));
                    let mut s = sh.lock().unwrap();
                    if !active(&s, Page::Bluetooth) {
                        return;
                    }
                    s.status = "Bluetooth konnte nicht eingeschaltet werden. Bitte in den Windows-Einstellungen (Bluetooth & Geräte) einschalten.".into();
                    s.status_kind = StatusKind::Bad;
                }
            }
            BtState::Missing => {
                let mut s = sh.lock().unwrap();
                if !active(&s, Page::Bluetooth) {
                    return;
                }
                s.status = "Kein Bluetooth-Adapter gefunden. Dieser PC braucht einen Bluetooth-Adapter (z. B. einen USB-Dongle), um mit dem Telefon zu sprechen.".into();
                s.status_kind = StatusKind::Bad;
            }
        }
        thread::sleep(Duration::from_millis(900));
    }
}

enum BtState {
    On,
    Off,
    Disabled,
    Missing,
}

fn bluetooth_state() -> BtState {
    let radios = match Radio::GetRadiosAsync().and_then(|r| r.rwait()) {
        Ok(r) => r,
        Err(_) => return BtState::Missing,
    };
    let mut state = BtState::Missing;
    if let Ok(size) = radios.Size() {
        for i in 0..size {
            let radio = match radios.GetAt(i) {
                Ok(r) => r,
                Err(_) => continue,
            };
            if radio.Kind() != Ok(RadioKind::Bluetooth) {
                continue;
            }
            match radio.State() {
                Ok(RadioState::On) => return BtState::On,
                Ok(RadioState::Off) => state = BtState::Off,
                Ok(RadioState::Disabled) => state = BtState::Disabled,
                _ => {}
            }
        }
    }
    state
}

fn set_bluetooth(on: bool) -> Result<(), String> {
    let radios = Radio::GetRadiosAsync()
        .and_then(|r| r.rwait())
        .map_err(|e| e.to_string())?;
    let size = radios.Size().map_err(|e| e.to_string())?;
    for i in 0..size {
        let radio = radios.GetAt(i).map_err(|e| e.to_string())?;
        if radio.Kind() != Ok(RadioKind::Bluetooth) {
            continue;
        }
        let target = if on { RadioState::On } else { RadioState::Off };
        let status = radio
            .SetStateAsync(target)
            .and_then(|r| r.rwait())
            .map_err(|e| e.to_string())?;
        if status != RadioAccessStatus::Allowed {
            return Err(format!("access denied: {status:?}"));
        }
    }
    Ok(())
}

fn pair_worker(sh: Sh) {
    com_init();
    // 1) is an R-Board phone already paired?
    match rcomm::discover_paired_phone() {
        Ok(Some(addr)) => {
            let name = rcomm::device_name(addr).unwrap_or_else(|| "Telefon".into());
            let mut s = sh.lock().unwrap();
            if !active(&s, Page::Pair) {
                return;
            }
            crate::logln("wizard: already-paired R-Board phone found");
            s.found = Some((addr, name.clone()));
            s.status = format!("{name} ist bereits gekoppelt ✓");
            s.status_kind = StatusKind::Good;
            return;
        }
        Ok(None) => {}
        Err(e) => crate::logln(&format!("wizard: paired-device check failed: {e}")),
    }
    {
        let mut s = sh.lock().unwrap();
        if !active(&s, Page::Pair) {
            return;
        }
        s.status = "Suche Geräte in der Nähe …".into();
        s.status_kind = StatusKind::Busy;
    }

    let mut last_scan = Instant::now() - Duration::from_secs(60);
    loop {
        {
            let s = sh.lock().unwrap();
            if !active(&s, Page::Pair) {
                return;
            }
        }

        // flaky-discovery recovery: power-cycle the radio once on request
        if sh.lock().unwrap().radio_restart {
            sh.lock().unwrap().radio_restart = false;
            crate::logln("wizard: restarting the bluetooth radio");
            let _ = set_bluetooth(false);
            thread::sleep(Duration::from_millis(2500));
            let _ = set_bluetooth(true);
            thread::sleep(Duration::from_millis(2000));
            {
                let mut s = sh.lock().unwrap();
                if !active(&s, Page::Pair) {
                    return;
                }
                s.status = "Bluetooth-Modul neu gestartet — suche neu …".into();
                s.status_kind = StatusKind::Busy;
            }
            last_scan = Instant::now() - Duration::from_secs(60);
        }

        // "Erneut suchen": also re-check paired devices (covers pairing via Windows settings)
        if sh.lock().unwrap().scan_request {
            sh.lock().unwrap().scan_request = false;
            if let Ok(Some(addr)) = rcomm::discover_paired_phone() {
                let name = rcomm::device_name(addr).unwrap_or_else(|| "Telefon".into());
                let mut s = sh.lock().unwrap();
                if !active(&s, Page::Pair) {
                    return;
                }
                s.found = Some((addr, name));
                return;
            }
            last_scan = Instant::now() - Duration::from_secs(60);
        }

        // pairing request from the UI
        let request = sh.lock().unwrap().pair_request.take();
        if let Some(id) = request {
            do_pair(&sh, &id);
            if sh.lock().unwrap().found.is_some() {
                return;
            }
            continue;
        }

        // periodic nearby scan
        if last_scan.elapsed() > Duration::from_secs(4) {
            last_scan = Instant::now();
            match nearby_devices() {
                Ok(devices) => {
                    let mut s = sh.lock().unwrap();
                    if !active(&s, Page::Pair) {
                        return;
                    }
                    let changed = s.devices.len() != devices.len()
                        || s.devices.iter().map(|d| &d.name).ne(devices.iter().map(|d| &d.name));
                    if changed {
                        s.devices = devices;
                        s.devices_dirty = true;
                    }
                    if s.devices.is_empty() {
                        s.status = "Keine Geräte gefunden. Halte dein Telefon bereit: R-Board → \"R-Board einrichten\" → \"Sichtbar machen\".".into();
                        s.status_kind = StatusKind::Busy;
                    } else if !s.pair_busy && s.status_kind != StatusKind::Bad {
                        s.status = "Dein Telefon auswählen und auf \"Koppeln\" klicken.".into();
                        s.status_kind = StatusKind::Neutral;
                    }
                }
                Err(e) => crate::logln(&format!("wizard: nearby scan failed: {e}")),
            }
        }
        thread::sleep(Duration::from_millis(400));
    }
}

fn nearby_devices() -> Result<Vec<Nearby>, windows::core::Error> {
    let selector = HSTRING::from(NEARBY_SELECTOR);
    let devices = DeviceInformation::FindAllAsyncAqsFilter(&selector)?.rwait()?;
    let count = devices.Size()?;
    let mut map: std::collections::BTreeMap<String, String> = std::collections::BTreeMap::new();
    for i in 0..count {
        let info = devices.GetAt(i)?;
        let name = info.Name().unwrap_or_default().to_string();
        let name = name.trim().to_string();
        if name.is_empty() {
            continue;
        }
        map.insert(name, info.Id()?.to_string());
    }
    Ok(map.into_iter().map(|(name, id)| Nearby { name, id }).collect())
}

fn do_pair(sh: &Sh, id: &str) {
    {
        let mut s = sh.lock().unwrap();
        s.pair_busy = true;
        s.status = "Kopplung läuft — bestätige auf BEIDEN Geräten (Windows-Fenster und Telefon).".into();
        s.status_kind = StatusKind::Busy;
    }
    let outcome = pair_device(id);
    let mut s = sh.lock().unwrap();
    s.pair_busy = false;
    if !active(&s, Page::Pair) {
        return;
    }
    match outcome {
        Ok(addr) => {
            let name = rcomm::device_name(addr).unwrap_or_else(|| "Telefon".into());
            s.status = format!("{name} gekoppelt ✓");
            s.status_kind = StatusKind::Good;
            s.found = Some((addr, name));
        }
        Err(text) => {
            s.status = text;
            s.status_kind = StatusKind::Bad;
        }
    }
}

/// Runs the Windows pairing ceremony for one nearby device and resolves its
/// Bluetooth address afterwards.
fn pair_device(id: &str) -> Result<u64, String> {
    let info = DeviceInformation::CreateFromIdAsync(&HSTRING::from(id))
        .and_then(|r| r.rwait())
        .map_err(|e| format!("Gerät nicht mehr gefunden — bitte \"Erneut suchen\" klicken. ({e})"))?;
    let pairing = info.Pairing().map_err(|e| format!("Kopplung nicht möglich: {e}"))?;
    crate::logln("wizard: PairAsync — the Windows pairing dialog should appear now");
    let result = pairing
        .PairAsync()
        .and_then(|r| r.rwait())
        .map_err(|e| format!("Kopplung fehlgeschlagen: {e}"))?;
    match result.Status() {
        Ok(st) if st == DevicePairingResultStatus::Paired
            || st == DevicePairingResultStatus::AlreadyPaired =>
        {
            crate::logln("wizard: pairing succeeded");
            resolve_paired_address(id).ok_or_else(|| {
                "Gekoppelt, aber die Geräteadresse konnte nicht gelesen werden — bitte \"Erneut suchen\" klicken.".to_string()
            })
        }
        Ok(st) => Err(format_pairing_error(st)),
        Err(e) => Err(format!("Kopplung fehlgeschlagen: {e}")),
    }
}

fn format_pairing_error(st: DevicePairingResultStatus) -> String {
    let reason = if st == DevicePairingResultStatus::PairingCanceled {
        "wurde abgebrochen — die Bestätigung wurde auf einem der Geräte abgelehnt oder geschlossen"
    } else if st == DevicePairingResultStatus::AuthenticationTimeout {
        "ist in der Zeitüberschreitung gelaufen — bestätige die Kopplung zügig auf beiden Geräten"
    } else if st == DevicePairingResultStatus::ConnectionRejected {
        "wurde vom Telefon abgelehnt — am Telefon die Kopplungs-Anfrage bestätigen"
    } else if st == DevicePairingResultStatus::OperationAlreadyInProgress {
        "läuft bereits — bitte kurz warten"
    } else if st == DevicePairingResultStatus::RejectedByHandler {
        "wurde abgelehnt — am Telefon die Kopplungs-Anfrage bestätigen"
    } else {
        "ist fehlgeschlagen"
    };
    crate::logln(&format!("wizard: pairing failed with status {}", st.0));
    format!("Die Kopplung {reason}. Bitte erneut versuchen.")
}

/// address of the freshly paired device, matched by its enumeration id
fn resolve_paired_address(id: &str) -> Option<u64> {
    let selector = BluetoothDevice::GetDeviceSelectorFromPairingState(true).ok()?;
    let devices = DeviceInformation::FindAllAsyncAqsFilter(&selector).ok()?.rwait().ok()?;
    let count = devices.Size().ok()?;
    for i in 0..count {
        let info = devices.GetAt(i).ok()?;
        if info.Id().map(|x| x == id).unwrap_or(false) {
            let device = BluetoothDevice::FromIdAsync(&info.Id().ok()?).ok()?.rwait().ok()?;
            return device.BluetoothAddress().ok();
        }
    }
    None
}

fn service_worker(sh: Sh) {
    com_init();
    let addr = sh
        .lock()
        .unwrap()
        .found
        .clone()
        .map(|(a, _)| a)
        .expect("service worker needs an address");
    let started = Instant::now();
    loop {
        {
            let s = sh.lock().unwrap();
            if !active(&s, Page::Service) || s.sync_started {
                return;
            }
        }
        match rcomm::service_available(addr) {
            Ok(true) => {
                let mut s = sh.lock().unwrap();
                if !active(&s, Page::Service) {
                    return;
                }
                crate::logln("wizard: sync service visible");
                s.service_ok = true;
                s.status = "Sync-Dienst erreicht ✓ — Verbindung wird aufgebaut …".into();
                s.status_kind = StatusKind::Good;
                return;
            }
            Ok(false) => {
                let mut s = sh.lock().unwrap();
                if !active(&s, Page::Service) {
                    return;
                }
                if started.elapsed() > Duration::from_secs(8) && !s.service_hint {
                    s.service_hint = true;
                    crate::logln("wizard: sync service not visible — showing phone-side instructions");
                }
                s.status = if started.elapsed() > Duration::from_secs(8) {
                    "Der Sync-Dienst ist noch nicht sichtbar — siehe Anleitung. Der Assistent prüft automatisch weiter …".to_string()
                } else {
                    "Prüfe den Sync-Dienst auf dem Telefon …".to_string()
                };
                s.status_kind = StatusKind::Busy;
            }
            Err(e) => crate::logln(&format!("wizard: service query failed: {e}")),
        }
        thread::sleep(Duration::from_secs(4));
    }
}
