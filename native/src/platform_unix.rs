//! Desktop integration for macOS and Linux/X11. Windows keeps its existing native hook.
use enigo::{Direction, Enigo, Key, Keyboard};
use std::sync::{Mutex, OnceLock};
use tauri::AppHandle;
use tauri_plugin_global_shortcut::{GlobalShortcutExt, Shortcut, ShortcutState};

pub const HOTKEYS: &[(&str, u8, u16, &str)] = &[
    ("ctrl+shift+v", 0, 0, "Ctrl+Shift+V"),
    ("alt+v", 0, 0, "Alt+V"),
    ("ctrl+alt+v", 0, 0, "Ctrl+Alt+V"),
];
static APP: OnceLock<AppHandle> = OnceLock::new();
static CURRENT: Mutex<Option<Shortcut>> = Mutex::new(None);

pub fn initialize(app: &AppHandle) {
    let _ = APP.set(app.clone());
}
pub fn hotkey_label(id: &str) -> Option<&'static str> {
    HOTKEYS.iter().find(|h| h.0 == id).map(|h| h.3)
}
pub fn set_hotkey(enabled: bool, id: &str) -> bool {
    if hotkey_label(id).is_none() {
        return false;
    }
    let Some(app) = APP.get() else {
        return false;
    };
    let Ok(mut current) = CURRENT.lock() else {
        return false;
    };
    let Ok(shortcut) = id.parse::<Shortcut>() else {
        return false;
    };
    let next = enabled.then_some(shortcut);
    if *current == next {
        return true;
    }
    // Register the replacement first: failure preserves the working old shortcut.
    if enabled
        && let Err(error) = app
            .global_shortcut()
            .on_shortcut(shortcut, |app, _, event| {
                if event.state == ShortcutState::Pressed {
                    let target = app.clone();
                    let _ = app.run_on_main_thread(move || crate::toggle_main(&target));
                }
            })
    {
        eprintln!("global shortcut registration failed: {error}");
        return false;
    }
    if let Some(previous) = *current
        && let Err(error) = app.global_shortcut().unregister(previous)
    {
        if enabled {
            let _ = app.global_shortcut().unregister(shortcut);
        }
        eprintln!("global shortcut removal failed: {error}");
        return false;
    }
    *current = next;
    true
}

pub fn log_diagnostic(_message: &str) {}

pub fn paste_to_previous_window() {
    // Hiding the window restores the previous desktop application before injecting paste.
    std::thread::sleep(std::time::Duration::from_millis(150));
    let result = (|| -> Result<(), String> {
        let mut input = Enigo::new(&enigo::Settings::default()).map_err(|e| e.to_string())?;
        let modifier = if cfg!(target_os = "macos") {
            Key::Meta
        } else {
            Key::Control
        };
        input
            .key(modifier, Direction::Press)
            .map_err(|e| e.to_string())?;
        let paste = input.key(Key::Unicode('v'), Direction::Click);
        let release = input.key(modifier, Direction::Release);
        paste.and(release).map_err(|e| e.to_string())
    })();
    if let Err(error) = result {
        eprintln!("automatic paste unavailable; clipboard is ready for manual paste: {error}");
    }
}
