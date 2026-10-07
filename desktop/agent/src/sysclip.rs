//! Cross-platform system clipboard access. Windows implemented via clipboard-win;
//! macOS (NSPasteboard) and Linux (X11/Wayland) backends land in M3.

use clipboard_win::Clipboard;

/// Cheap change detection token; `None` when unsupported on this platform.
pub fn seq() -> Option<u32> {
    clipboard_win::seq_num().map(|n| n.get())
}

pub fn get_text() -> Option<String> {
    let _clip = Clipboard::new_attempts(10).ok()?;
    clipboard_win::get_clipboard_string().ok().filter(|s| !s.is_empty())
}

pub fn set_text(text: &str) -> bool {
    let Ok(_clip) = Clipboard::new_attempts(10) else {
        return false;
    };
    clipboard_win::set_clipboard_string(text).is_ok()
}
