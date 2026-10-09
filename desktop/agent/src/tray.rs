//! Tray icon: R logo (embedded as win32 resource), tooltip, "Beenden" menu.

use std::thread;
use std::time::Duration;

use tao::event_loop::{ControlFlow, EventLoop};
use tray_icon::menu::{Menu, MenuEvent, MenuItem};
use tray_icon::TrayIconBuilder;

const EXIT_MENU_ID: &str = "rboard-exit";
const SETUP_MENU_ID: &str = "rboard-setup";

pub fn run_tray() -> ! {
    let event_loop = EventLoop::new();
    let menu = Menu::new();
    let exit_item = MenuItem::with_id(EXIT_MENU_ID, "Beenden", true, None);
    let _ = menu.append_items(&[
        &MenuItem::with_id("rboard-title", "R-Board Clipboard Sync", false, None),
        &MenuItem::with_id(SETUP_MENU_ID, "Setup-Assistent …", true, None),
        &exit_item,
    ]);
    let tray = TrayIconBuilder::new()
        .with_tooltip("R-Board Clipboard Sync")
        .with_icon(load_icon())
        .with_menu(Box::new(menu))
        .build()
        .expect("failed to create tray icon");
    let _ = tray.set_visible(true);

    let menu_channel = MenuEvent::receiver();
    let mut last_connected: Option<bool> = None;
    event_loop.run(move |_event, _, control_flow| {
        *control_flow = ControlFlow::Poll;
        thread::sleep(Duration::from_millis(500));
        for ev in menu_channel.try_recv() {
            if ev.id() == exit_item.id() {
                std::process::exit(0);
            }
            if ev.id() == SETUP_MENU_ID {
                // the wizard runs on its own thread with its own window; a
                // second invocation while one is open is a no-op
                thread::Builder::new()
                    .name("setup-wizard".into())
                    .spawn(|| {
                        crate::setup::run_setup(crate::own_id());
                    })
                    .ok();
            }
        }
        let connected = crate::CONNECTED.load(std::sync::atomic::Ordering::SeqCst);
        if last_connected != Some(connected) {
            last_connected = Some(connected);
            let tooltip = if connected {
                "R-Board Clipboard Sync — verbunden"
            } else {
                "R-Board Clipboard Sync — nicht verbunden"
            };
            let _ = tray.set_tooltip(Some(tooltip));
        }
    })
}

/// R logo from the embedded win32 resource (see build.rs); black square fallback.
fn load_icon() -> tray_icon::Icon {
    tray_icon::Icon::from_resource(1, None).unwrap_or_else(|_| {
        let mut rgba = vec![0u8; 32 * 32 * 4];
        for px in rgba.chunks_exact_mut(4) {
            px.copy_from_slice(&[0, 0, 0, 255]);
        }
        tray_icon::Icon::from_rgba(rgba, 32, 32).expect("fallback icon")
    })
}
