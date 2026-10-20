mod atomic_file;
mod backup;
mod clipboard;
mod db;
mod images;
mod lan;
mod lan_wire;
mod settings;
#[cfg(windows)]
mod win;
#[cfg(not(windows))]
#[path = "platform_unix.rs"]
mod win;

use backup::{BundleClip, BundleGroup};
use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use clipboard::Captured;
use db::{Group, NewClip, RawClip, Scope};
use images::ImageStore;
use rusqlite::Connection;
use serde::Serialize;
use settings::{Settings, SettingsFile};
use std::collections::HashMap;
use std::io::{self, BufWriter, Write};
#[cfg(windows)]
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
#[cfg(windows)]
use std::process::{Command, Output};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard, RwLock};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, PhysicalPosition, State, WebviewWindow, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};
use tauri_plugin_dialog::DialogExt;
#[cfg(windows)]
use windows_sys::Win32::System::Threading::CREATE_NO_WINDOW;

const AUTOSTART_ARG: &str = "--minimized";
#[cfg(windows)]
const AUTOSTART_TASK: &str = "\\clipkeep-clipboard";
const BUNDLE_VERSION: u32 = 1;
const MAX_IMPORT_FILE: u64 = 2 * 1024 * 1024 * 1024;
/// Window won't auto-hide on blur until this epoch-ms (briefly after show or while dragging).
static SUPPRESS_HIDE_UNTIL: AtomicI64 = AtomicI64::new(0);
const SHOW_BLUR_GRACE_MS: i64 = 80;
/// Only an actual title-bar drag may extend the blur grace on Moved events.
/// Showing the window also moves it near the cursor and emits Moved.
static DRAG_HIDE_UNTIL: AtomicI64 = AtomicI64::new(0);
/// Invalidates a pending blur check whenever the window is shown, hidden, or focused again.
static FOCUS_EPOCH: AtomicU64 = AtomicU64::new(0);
/// Our own record of whether the window is shown. `WebviewWindow::is_visible()` proved unreliable
/// here (stale `true` after a hide), which made the toggle hotkey need two presses; every show/hide
/// goes through show_main/hide_main, so this flag is the authoritative toggle state.
static WINDOW_VISIBLE: AtomicBool = AtomicBool::new(false);
/// Ignore a transient blur while WebView2 is being shown, then check real focus.
static TRAY_MENU_VISIBLE: AtomicBool = AtomicBool::new(false);
static TRAY_MENU_EPOCH: AtomicU64 = AtomicU64::new(0);
static TRAY_MENU_BLUR_GRACE_UNTIL: AtomicI64 = AtomicI64::new(0);

struct Core {
    db: Mutex<Connection>,
    images: ImageStore,
    lan: lan::LanService,
    settings: RwLock<Settings>,
    settings_file: SettingsFile,
    position_dirty: AtomicBool,
    data_dir: PathBuf,
}

type CmdResult<T> = Result<T, String>;

fn to_err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

impl Core {
    fn conn(&self) -> CmdResult<MutexGuard<'_, Connection>> {
        self.db.lock().map_err(to_err)
    }

    fn settings(&self) -> Settings {
        self.settings.read().map(|s| s.clone()).unwrap_or_default()
    }

    fn max_bytes(&self) -> i64 {
        self.settings().max_storage_bytes()
    }

    /// Inserts under the lock and deletes any trimmed image files while still holding it, so a
    /// concurrent save of the same image can't re-add a row pointing at a file we then remove.
    fn upsert(&self, clip: NewClip) -> CmdResult<()> {
        let conn = self.conn()?;
        let trimmed = db::upsert(&conn, &clip, self.max_bytes()).map_err(to_err)?;
        for file in &trimmed {
            if !db::is_file_referenced(&conn, file).unwrap_or(true) {
                self.images.remove(file);
            }
        }
        Ok(())
    }

    fn save_text(&self, text: &str, source: Option<&str>) -> CmdResult<()> {
        let hash = db::text_hash(text);
        self.upsert(NewClip {
            kind: db::KIND_TEXT,
            content: text,
            hash: &hash,
            width: None,
            height: None,
            size: text.len() as i64,
            source,
        })
    }

    fn save_image(&self, saved: &images::Saved, source: Option<&str>) -> CmdResult<()> {
        self.upsert(NewClip {
            kind: db::KIND_IMAGE,
            content: &saved.file,
            hash: &saved.hash,
            width: Some(saved.width),
            height: Some(saved.height),
            size: saved.bytes as i64,
            source,
        })
    }

    fn edit_text(&self, id: i64, text: &str) -> CmdResult<()> {
        let conn = self.conn()?;
        let trimmed = db::edit_text(&conn, id, text, self.max_bytes())?;
        for file in &trimmed {
            if !db::is_file_referenced(&conn, file).unwrap_or(true) {
                self.images.remove(file);
            }
        }
        Ok(())
    }

    /// Removes image files for rows just deleted, but only those no surviving row still points at.
    fn drop_files(&self, files: &[String]) {
        if files.is_empty() {
            return;
        }
        let Ok(conn) = self.conn() else { return };
        for file in files {
            if !db::is_file_referenced(&conn, file).unwrap_or(true) {
                self.images.remove(file);
            }
        }
    }

    fn persist_position(&self) {
        if !self.position_dirty.swap(false, Ordering::Relaxed) {
            return;
        }
        let result = self
            .settings
            .read()
            .map_err(to_err)
            .and_then(|settings| self.settings_file.save(&settings).map_err(to_err));
        if let Err(error) = result {
            self.position_dirty.store(true, Ordering::Relaxed);
            eprintln!("saving window position failed: {error}");
        }
    }
}

fn changed(app: &AppHandle) {
    let _ = app.emit("clips-changed", ());
}

fn on_local_copy(app: &AppHandle, captured: Captured) {
    if clipboard::capture_paused() {
        return;
    }
    let core = app.state::<Core>();
    let result = match captured {
        Captured::Text(text) => core
            .save_text(&text, None)
            .map(|()| core.lan.queue_local(app, db::text_hash(&text))),
        Captured::Image(img) => {
            let hash = images::hash(&img);
            core.images
                .write(&img, &hash)
                .and_then(|saved| core.save_image(&saved, None))
                .map(|()| core.lan.queue_local(app, hash))
        }
        #[cfg(windows)]
        Captured::ImageFiles(paths) => {
            // A file selection can contain several large JPEGs. Keep decoding and PNG
            // thumbnail generation off the clipboard listener so later copies aren't missed.
            let app = app.clone();
            std::thread::spawn(move || {
                let core = app.state::<Core>();
                let mut saved_any = false;
                for path in paths {
                    if clipboard::capture_paused() {
                        break;
                    }
                    let _work = match images::IMAGE_WORK.lock() {
                        Ok(guard) => guard,
                        Err(error) => {
                            eprintln!("image worker lock failed: {error}");
                            break;
                        }
                    };
                    let result = images::decode_file(&path).and_then(|img| {
                        let hash = images::hash(&img);
                        let source = path.file_name().map(|name| name.to_string_lossy());
                        core.images
                            .write(&img, &hash)
                            .and_then(|saved| core.save_image(&saved, source.as_deref()))
                            .map(|()| core.lan.queue_local(&app, hash))
                    });
                    match result {
                        Ok(()) => saved_any = true,
                        Err(e) => {
                            eprintln!("save copied image file {} failed: {e}", path.display())
                        }
                    }
                }
                if saved_any {
                    changed(&app);
                }
            });
            return;
        }
    };
    match result {
        Ok(()) => changed(app),
        Err(e) => eprintln!("save clip failed: {e}"),
    }
}

fn scope_of(scope: &str, group_id: Option<i64>) -> Scope {
    match scope {
        "fav" => Scope::Favorites,
        "group" => Scope::Group(group_id.unwrap_or(-1)),
        _ => Scope::All,
    }
}

#[tauri::command]
fn list_clips(
    core: State<Core>,
    scope: String,
    group_id: Option<i64>,
    query: String,
) -> CmdResult<Vec<db::Clip>> {
    let mut clips =
        db::list(&*core.conn()?, scope_of(&scope, group_id), query.trim()).map_err(to_err)?;
    for clip in &mut clips {
        if let Some(file) = &clip.file {
            clip.thumb = core
                .images
                .thumb_path(file)
                .map(|path| path.to_string_lossy().into_owned());
        }
    }
    Ok(clips)
}

#[tauri::command]
fn get_clip_text(core: State<Core>, id: i64) -> CmdResult<String> {
    let (kind, text) = db::get(&*core.conn()?, id)
        .map_err(to_err)?
        .ok_or("条目不存在")?;
    if kind != db::KIND_TEXT {
        return Err("只能预览文本条目".into());
    }
    Ok(text)
}

#[tauri::command]
fn update_clip_text(app: AppHandle, core: State<Core>, id: i64, text: String) -> CmdResult<()> {
    if text.trim().is_empty() {
        return Err("文本不能为空".into());
    }
    if text.len() > clipboard::MAX_TEXT_BYTES {
        return Err("文本不能超过 2 MB".into());
    }
    core.edit_text(id, &text)?;
    changed(&app);
    Ok(())
}

#[tauri::command]
fn get_capture_paused() -> bool {
    clipboard::capture_paused()
}

fn set_capture_paused(app: &AppHandle, paused: bool) {
    clipboard::set_capture_paused(paused);
    let _ = app.emit("capture-paused-changed", paused);
}

#[tauri::command]
fn toggle_capture_paused(app: AppHandle) -> bool {
    let paused = !clipboard::capture_paused();
    set_capture_paused(&app, paused);
    paused
}

#[tauri::command]
fn get_lan_state(core: State<Core>) -> CmdResult<lan::LanState> {
    core.lan.state()
}

#[tauri::command]
fn set_lan_receive(app: AppHandle, core: State<Core>, enabled: bool) -> CmdResult<lan::LanState> {
    core.lan.set_receive(&app, enabled)
}

#[tauri::command]
fn set_lan_auto_send(core: State<Core>, address: Option<String>) -> CmdResult<lan::LanState> {
    core.lan.set_auto_send(address)
}

#[tauri::command]
fn set_lan_copy_received(core: State<Core>, enabled: bool) -> CmdResult<lan::LanState> {
    core.lan.set_copy_received(enabled)
}

#[tauri::command]
fn add_lan_peer(core: State<Core>, name: String, code: String) -> CmdResult<lan::LanState> {
    core.lan.add_peer(&name, &code)
}

#[tauri::command]
fn remove_lan_peer(core: State<Core>, address: String) -> CmdResult<lan::LanState> {
    core.lan.remove_peer(&address)
}

#[tauri::command]
fn rotate_lan_code(app: AppHandle, core: State<Core>) -> CmdResult<lan::LanState> {
    core.lan.rotate_code(&app)
}

#[tauri::command]
fn copy_lan_code(core: State<Core>, address: String) -> CmdResult<()> {
    let code = core
        .lan
        .state()?
        .local_codes
        .into_iter()
        .find(|c| c.address == address)
        .ok_or("本机地址不存在")?
        .code;
    clipboard::write_text(&code).map_err(to_err)
}

#[tauri::command]
async fn send_clip_to_peer(app: AppHandle, id: i64, address: String) -> CmdResult<()> {
    tauri::async_runtime::spawn_blocking(move || {
        let core = app.state::<Core>();
        send_stored_clip(&core, id, &address)
    })
    .await
    .map_err(to_err)?
}

fn send_stored_clip(core: &Core, id: i64, address: &str) -> CmdResult<()> {
    let _send = lan::SEND_WORK.lock().map_err(to_err)?;
    let (kind, text) = db::get(&*core.conn()?, id)
        .map_err(to_err)?
        .ok_or("条目不存在")?;
    if kind == db::KIND_TEXT {
        core.lan.send_text(address, &text)
    } else {
        let png = core
            .images
            .png_bytes_limited(&text, lan_wire::MAX_IMAGE_BYTES)
            .map_err(to_err)?;
        let (w, h) =
            image::ImageReader::with_format(std::io::Cursor::new(&png), image::ImageFormat::Png)
                .into_dimensions()
                .map_err(to_err)?;
        if u64::from(w) * u64::from(h) > lan_wire::MAX_IMAGE_PIXELS {
            return Err("传送图片最多 1600 万像素".into());
        }
        core.lan.send_image(address, &png)
    }
}

#[tauri::command]
fn toggle_favorite(app: AppHandle, core: State<Core>, id: i64) -> CmdResult<bool> {
    let on = db::toggle_favorite(&*core.conn()?, id).map_err(to_err)?;
    changed(&app);
    Ok(on)
}

#[tauri::command]
fn toggle_pinned(app: AppHandle, core: State<Core>, id: i64) -> CmdResult<bool> {
    let on = db::toggle_pinned(&*core.conn()?, id).map_err(to_err)?;
    changed(&app);
    Ok(on)
}

#[tauri::command]
fn move_clip(app: AppHandle, core: State<Core>, id: i64, group_id: Option<i64>) -> CmdResult<()> {
    db::set_group(&*core.conn()?, id, group_id).map_err(to_err)?;
    changed(&app);
    Ok(())
}

#[tauri::command]
fn delete_clip(app: AppHandle, core: State<Core>, id: i64) -> CmdResult<()> {
    let files = db::delete(&*core.conn()?, id).map_err(to_err)?;
    core.drop_files(&files);
    changed(&app);
    Ok(())
}

#[tauri::command]
fn clear_data(
    app: AppHandle,
    core: State<Core>,
    include_pinned: bool,
    include_grouped: bool,
    include_all: bool,
) -> CmdResult<()> {
    let files = db::clear_data(&*core.conn()?, include_pinned, include_grouped, include_all)
        .map_err(to_err)?;
    core.drop_files(&files);
    changed(&app);
    Ok(())
}

#[tauri::command]
fn list_groups(core: State<Core>) -> CmdResult<Vec<Group>> {
    db::list_groups(&*core.conn()?).map_err(to_err)
}

#[tauri::command]
fn create_group(app: AppHandle, core: State<Core>, name: String) -> CmdResult<Group> {
    let name = name.trim();
    if name.is_empty() {
        return Err("分组名不能为空".into());
    }
    let group = db::create_group(&*core.conn()?, name).map_err(to_err)?;
    let _ = app.emit("groups-changed", ());
    Ok(group)
}

#[tauri::command]
fn rename_group(app: AppHandle, core: State<Core>, id: i64, name: String) -> CmdResult<()> {
    let name = name.trim();
    if name.is_empty() {
        return Err("分组名不能为空".into());
    }
    db::rename_group(&*core.conn()?, id, name).map_err(to_err)?;
    let _ = app.emit("groups-changed", ());
    Ok(())
}

#[tauri::command]
fn delete_group(app: AppHandle, core: State<Core>, id: i64, delete_items: bool) -> CmdResult<()> {
    let files = db::delete_group(&*core.conn()?, id, delete_items).map_err(to_err)?;
    core.drop_files(&files);
    let _ = app.emit("groups-changed", ());
    changed(&app);
    Ok(())
}

#[tauri::command]
fn reorder_groups(app: AppHandle, core: State<Core>, ids: Vec<i64>) -> CmdResult<()> {
    db::reorder_groups(&*core.conn()?, &ids).map_err(to_err)?;
    let _ = app.emit("groups-changed", ());
    Ok(())
}

/// Reads the stored item and puts it on the clipboard. Image decoding happens before the lock is
/// taken so a large image never blocks other DB users.
fn put_on_clipboard(core: &Core, id: i64) -> CmdResult<()> {
    let (kind, content) = db::get(&*core.conn()?, id)
        .map_err(to_err)?
        .ok_or("条目不存在")?;
    if kind == db::KIND_IMAGE {
        let _work = images::IMAGE_WORK.lock().map_err(to_err)?;
        let img = core.images.load(&content)?;
        clipboard::write_image(&img).map_err(to_err)?;
    } else {
        clipboard::write_text(&content).map_err(to_err)?;
    }
    db::touch(&*core.conn()?, id).map_err(to_err)?;
    Ok(())
}

#[tauri::command]
fn paste_clip(app: AppHandle, id: i64) {
    hide_main(&app);
    // Off the UI thread: decoding/encoding a large image would otherwise freeze the window.
    std::thread::spawn(move || {
        let core = app.state::<Core>();
        match put_on_clipboard(&core, id) {
            Ok(()) => {
                changed(&app);
                win::paste_to_previous_window();
            }
            Err(e) => eprintln!("paste failed: {e}"),
        }
    });
}

#[tauri::command]
fn copy_clip(app: AppHandle, id: i64) {
    hide_main(&app);
    std::thread::spawn(move || {
        let core = app.state::<Core>();
        match put_on_clipboard(&core, id) {
            Ok(()) => changed(&app),
            Err(e) => eprintln!("copy failed: {e}"),
        }
    });
}

#[tauri::command]
fn hide_window(app: AppHandle) {
    hide_main(&app);
}

#[tauri::command]
fn start_drag(window: WebviewWindow) -> CmdResult<()> {
    // Dragging blurs the webview; hold off the blur-hide (extended by each Moved event).
    let until = db::now_ms() + 1500;
    DRAG_HIDE_UNTIL.store(until, Ordering::Relaxed);
    SUPPRESS_HIDE_UNTIL.fetch_max(until, Ordering::Relaxed);
    window.start_dragging().map_err(to_err)
}

#[tauri::command]
fn toggle_keep_on_blur(app: AppHandle, core: State<Core>) -> CmdResult<bool> {
    let mut current = core.settings.write().map_err(to_err)?;
    let mut s = current.clone();
    s.keep_on_blur = !s.keep_on_blur;
    core.settings_file.save(&s).map_err(to_err)?;
    *current = s.clone();
    drop(current);
    let _ = app.emit("settings-changed", ());
    Ok(s.keep_on_blur)
}

#[tauri::command]
fn get_autostart(app: AppHandle) -> CmdResult<bool> {
    Ok(autostart_task_exists() || app.autolaunch().is_enabled().unwrap_or(false))
}

#[tauri::command]
fn set_autostart(app: AppHandle, enabled: bool) -> CmdResult<bool> {
    apply_autostart(&app, enabled)
}

#[cfg(windows)]
fn run_schtasks(args: &[&str]) -> CmdResult<Output> {
    Command::new("schtasks.exe")
        .args(args)
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(to_err)
}

#[cfg(windows)]
fn autostart_task_exists() -> bool {
    run_schtasks(&["/Query", "/TN", AUTOSTART_TASK]).is_ok_and(|output| output.status.success())
}

#[cfg(windows)]
fn create_autostart_task() -> CmdResult<()> {
    if cfg!(debug_assertions) {
        return Err("请使用发布版设置开机自启".into());
    }
    let executable = std::env::current_exe().map_err(to_err)?;
    let task_run = format!("\"{}\" {AUTOSTART_ARG}", executable.display());
    let output = run_schtasks(&[
        "/Create",
        "/SC",
        "ONLOGON",
        "/TN",
        AUTOSTART_TASK,
        "/TR",
        &task_run,
        "/RL",
        "HIGHEST",
        "/IT",
        "/F",
    ])?;
    if !output.status.success() {
        return Err(format!(
            "创建开机自启任务失败：{}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    Ok(())
}

#[cfg(windows)]
fn delete_autostart_task() -> CmdResult<()> {
    if autostart_task_exists() {
        let output = run_schtasks(&["/Delete", "/TN", AUTOSTART_TASK, "/F"])?;
        if !output.status.success() {
            return Err(format!(
                "删除开机自启任务失败：{}",
                String::from_utf8_lossy(&output.stderr).trim()
            ));
        }
    }
    Ok(())
}

#[cfg(windows)]
fn apply_autostart(app: &AppHandle, enabled: bool) -> CmdResult<bool> {
    let launcher = app.autolaunch();
    if enabled {
        create_autostart_task()?;
    } else {
        delete_autostart_task()?;
    }
    // Old versions used HKCU Run. Remove it so only the elevated task starts at logon.
    if launcher.is_enabled().map_err(to_err)? {
        launcher.disable().map_err(to_err)?;
    }
    let now = autostart_task_exists();
    let _ = app.emit("autostart-changed", now);
    Ok(now)
}

#[cfg(not(windows))]
fn autostart_task_exists() -> bool {
    false
}

#[cfg(not(windows))]
fn apply_autostart(app: &AppHandle, enabled: bool) -> CmdResult<bool> {
    if enabled {
        app.autolaunch().enable().map_err(to_err)?;
    } else {
        app.autolaunch().disable().map_err(to_err)?;
    }
    let now = app.autolaunch().is_enabled().map_err(to_err)?;
    let _ = app.emit("autostart-changed", now);
    Ok(now)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct HotkeyOption {
    id: &'static str,
    label: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SettingsView {
    settings: Settings,
    hotkeys: Vec<HotkeyOption>,
    data_dir: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StorageUsageView {
    ordinary_bytes: i64,
    protected_bytes: i64,
    database_bytes: u64,
}

#[tauri::command]
fn get_settings(core: State<Core>) -> SettingsView {
    SettingsView {
        settings: core.settings(),
        hotkeys: win::HOTKEYS
            .iter()
            .map(|&(id, _, _, label)| HotkeyOption { id, label })
            .collect(),
        data_dir: core.data_dir.to_string_lossy().into_owned(),
    }
}

#[tauri::command]
fn get_storage_usage(core: State<Core>) -> CmdResult<StorageUsageView> {
    let usage = {
        let conn = core.conn()?;
        db::storage_usage(&conn).map_err(to_err)?
    };
    let file_len = |name: &str| -> CmdResult<u64> {
        match std::fs::metadata(core.data_dir.join(name)) {
            Ok(meta) => Ok(meta.len()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(0),
            Err(error) => Err(format!("读取数据库大小失败：{error}")),
        }
    };
    // WAL contains writes not yet checkpointed into the main SQLite file.
    let database_bytes = file_len("clipkeep.db")?.saturating_add(file_len("clipkeep.db-wal")?);
    Ok(StorageUsageView {
        ordinary_bytes: usage.ordinary_bytes,
        protected_bytes: usage.protected_bytes,
        database_bytes,
    })
}

#[tauri::command]
fn update_settings(app: AppHandle, core: State<Core>, settings: Settings) -> CmdResult<Settings> {
    let mut current = core.settings.write().map_err(to_err)?;
    let old = current.clone();
    let mut new = settings;
    new.window_x = old.window_x;
    new.window_y = old.window_y;
    if win::hotkey_label(&new.hotkey).is_none() {
        return Err("未知的快捷键".into());
    }
    if !win::set_hotkey(new.hotkey_enabled, &new.hotkey) {
        return Err("无法注册快捷键，可能已被其他应用占用".into());
    }
    if let Err(error) = core.settings_file.save(&new) {
        win::set_hotkey(old.hotkey_enabled, &old.hotkey);
        return Err(to_err(error));
    }
    *current = new.clone();
    drop(current);
    clipboard::set_capture_images(new.capture_images);

    if new.max_storage_mb != old.max_storage_mb {
        let files = db::enforce_storage(&*core.conn()?, new.max_storage_bytes()).map_err(to_err)?;
        core.drop_files(&files);
        changed(&app);
    }
    let _ = app.emit("settings-changed", ());
    Ok(new)
}

#[tauri::command]
async fn export_data(app: AppHandle) -> CmdResult<Option<String>> {
    let default_name = format!("clipkeep-backup-{}.ckbak", chrono_stamp());
    let Some(path) = app
        .dialog()
        .file()
        .add_filter("clipkeep 备份", &["ckbak"])
        .set_file_name(&default_name)
        .blocking_save_file()
    else {
        return Ok(None);
    };
    let path = path.into_path().map_err(to_err)?;

    // Snapshot the upper ID bound and groups, then export in bounded batches.
    let (last_id, groups) = {
        let core = app.state::<Core>();
        let conn = core.conn()?;
        (
            db::last_clip_id(&conn).map_err(to_err)?,
            db::list_groups(&conn).map_err(to_err)?,
        )
    };

    let groups: Vec<BundleGroup> = groups
        .into_iter()
        .map(|g| BundleGroup {
            id: g.id,
            name: g.name,
            sort_order: g.sort_order,
        })
        .collect();
    let mut count = 0usize;
    atomic_file::save_stream(&path, |file| {
        let mut w = BufWriter::new(file);
        let header = serde_json::json!({ "app": "clipkeep", "version": BUNDLE_VERSION, "exportedAt": db::now_ms() });
        write!(w, "{}", header.to_string().trim_end_matches('}'))?;
        write!(w, ",\"groups\":")?;
        serde_json::to_writer(&mut w, &groups).map_err(io::Error::other)?;
        write!(w, ",\"clips\":[")?;

        let mut after = 0;
        loop {
            let ids = {
                let core = app.state::<Core>();
                db::clip_id_batch(&*core.conn().map_err(io::Error::other)?, after, last_id).map_err(io::Error::other)?
            };
            if ids.is_empty() { break; }
            for id in ids {
                after = id;
                let (raw, png) = {
                    let core = app.state::<Core>();
                    let conn = core.conn().map_err(io::Error::other)?;
                    let Some(raw) = db::export_one(&conn, id).map_err(io::Error::other)? else {
                        continue;
                    };
                    let png = if raw.kind == db::KIND_IMAGE {
                        Some(core.images.png_bytes(&raw.content).map_err(|error| {
                            io::Error::other(format!("图片条目 {id} 无法导出：{error}"))
                        })?)
                    } else {
                        None
                    };
                    (raw, png)
                };
                let clip = BundleClip {
                    image_b64: png.map(|bytes| STANDARD.encode(bytes)),
                    text: (raw.kind == db::KIND_TEXT).then_some(raw.content),
                    kind: raw.kind,
                    favorite: raw.favorite,
                    pinned: raw.pinned,
                    group_id: raw.group_id,
                    created_at: raw.created_at,
                    updated_at: raw.updated_at,
                };
                if count > 0 {
                    write!(w, ",")?;
                }
                serde_json::to_writer(&mut w, &clip).map_err(io::Error::other)?;
                count += 1;
            }
        }
        write!(w, "]}}")?;
        w.flush()
    })
    .map_err(to_err)?;
    Ok(Some(format!("已导出 {count} 条到 {}", path.display())))
}

#[tauri::command]
async fn import_data(app: AppHandle) -> CmdResult<Option<String>> {
    let Some(path) = app
        .dialog()
        .file()
        .add_filter("clipkeep 备份", &["ckbak"])
        .blocking_pick_file()
    else {
        return Ok(None);
    };
    let path = path.into_path().map_err(to_err)?;
    if std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0) > MAX_IMPORT_FILE {
        return Err("备份文件过大".into());
    }
    let core = app.state::<Core>();
    let staged = backup::stage(std::fs::File::open(&path).map_err(to_err)?, &core.data_dir)?;

    let imported = {
        let core = app.state::<Core>();
        let mut conn = core.conn()?;
        let tx = conn.transaction().map_err(to_err)?;
        let existing: HashMap<String, i64> = db::list_groups(&tx)
            .map_err(to_err)?
            .into_iter()
            .map(|g| (g.name, g.id))
            .collect();
        let mut id_map: HashMap<i64, i64> = HashMap::new();
        for g in &staged.groups {
            let new_id = match existing.get(&g.name) {
                Some(&id) => id,
                None => db::create_group(&tx, &g.name).map_err(to_err)?.id,
            };
            id_map.insert(g.id, new_id);
        }
        let mut count = 0usize;
        for row in serde_json::Deserializer::from_reader(std::io::BufReader::new(&staged.rows))
            .into_iter::<RawClip>()
        {
            let mut raw = row.map_err(to_err)?;
            raw.group_id = raw.group_id.and_then(|old| id_map.get(&old).copied());
            if raw.kind == db::KIND_IMAGE {
                core.images.copy_from(&staged.images, &raw.content)?;
            }
            db::import_clip(&tx, &raw).map_err(to_err)?;
            count += 1;
        }
        debug_assert_eq!(count, staged.count);
        tx.commit().map_err(to_err)?;
        let files = db::enforce_storage(&conn, core.max_bytes()).map_err(to_err)?;
        drop(conn);
        core.drop_files(&files);
        count
    };

    let _ = app.emit("groups-changed", ());
    changed(&app);
    Ok(Some(format!("已导入 {imported} 条")))
}

/// Local time as YYYYMMDD-HHMMSS, for default export file names.
fn chrono_stamp() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let (y, m, d) = civil_from_days((secs / 86400) as i64);
    let (hh, mm, ss) = ((secs / 3600) % 24, (secs / 60) % 60, secs % 60);
    format!("{y:04}{m:02}{d:02}-{hh:02}{mm:02}{ss:02}")
}

/// Howard Hinnant's days-to-civil-date algorithm (UTC); good enough for a file name.
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

fn main_window(app: &AppHandle) -> Option<WebviewWindow> {
    app.get_webview_window("main")
}

fn tray_menu_window(app: &AppHandle) -> Option<WebviewWindow> {
    app.get_webview_window("tray_menu")
}

fn hide_tray_menu_window(app: &AppHandle) {
    if !TRAY_MENU_VISIBLE.swap(false, Ordering::Relaxed) {
        return;
    }
    TRAY_MENU_EPOCH.fetch_add(1, Ordering::Relaxed);
    if let Some(window) = tray_menu_window(app) {
        let _ = window.destroy();
    }
}

fn popup_axis(pointer: i32, extent: i32, start: i32, end: i32) -> i32 {
    let gap = 6;
    let after = pointer + gap;
    let before = pointer - extent - gap;
    let room_after = end - after;
    let room_before = pointer - start - gap;
    let desired = if room_after >= extent || room_after >= room_before {
        after
    } else {
        before
    };
    desired.clamp(start, (end - extent).max(start))
}

fn show_tray_menu(app: &AppHandle, cursor: PhysicalPosition<f64>) {
    let window = match tray_menu_window(app) {
        Some(window) => window,
        None => {
            let Some(config) = app
                .config()
                .app
                .windows
                .iter()
                .find(|w| w.label == "tray_menu")
            else {
                return;
            };
            match tauri::WebviewWindowBuilder::from_config(app, config)
                .and_then(|builder| builder.build())
            {
                Ok(window) => window,
                Err(error) => {
                    eprintln!("create tray menu failed: {error}");
                    return;
                }
            }
        }
    };
    TRAY_MENU_EPOCH.fetch_add(1, Ordering::Relaxed);
    TRAY_MENU_BLUR_GRACE_UNTIL.store(db::now_ms() + 120, Ordering::Relaxed);
    let Ok(size) = window.outer_size() else {
        return;
    };
    let pointer = app.cursor_position().unwrap_or(cursor);
    let mut x = pointer.x.round() as i32 + 6;
    let mut y = pointer.y.round() as i32 + 6;
    if let Ok(Some(monitor)) = app.monitor_from_point(pointer.x, pointer.y) {
        let area = monitor.work_area();
        let left = area.position.x;
        let top = area.position.y;
        let right = left + area.size.width as i32;
        let bottom = top + area.size.height as i32;
        x = popup_axis(pointer.x.round() as i32, size.width as i32, left, right);
        y = popup_axis(pointer.y.round() as i32, size.height as i32, top, bottom);
    }
    let _ = window.set_position(PhysicalPosition::new(x, y));
    // Windows can restore the native caption while this secondary window is
    // hidden. Enforce the borderless style immediately before every show.
    if let Err(e) = window.set_decorations(false) {
        win::log_diagnostic(&format!("style-tray-menu-failed {e}"));
    }
    TRAY_MENU_VISIBLE.store(true, Ordering::Relaxed);
    if let Err(e) = window.show() {
        TRAY_MENU_VISIBLE.store(false, Ordering::Relaxed);
        win::log_diagnostic(&format!("show-tray-menu-failed {e}"));
        return;
    }
    let _ = window.set_focus();
    let _ = window.emit("tray-menu-shown", ());
}

#[cfg(test)]
mod popup_position_tests {
    use super::popup_axis;

    #[test]
    fn menu_stays_near_pointer_on_both_screen_edges() {
        assert_eq!(popup_axis(5, 30, 0, 100), 11);
        assert_eq!(popup_axis(95, 30, 0, 100), 59);
        assert_eq!(popup_axis(50, 30, 0, 100), 56);
        assert_eq!(popup_axis(10, 120, 0, 100), 0);
    }
}

fn on_tray_menu_focus_changed(app: &AppHandle, focused: bool) {
    if focused || !TRAY_MENU_VISIBLE.load(Ordering::Relaxed) {
        return;
    }
    let epoch = TRAY_MENU_EPOCH.load(Ordering::Relaxed);
    let remaining = TRAY_MENU_BLUR_GRACE_UNTIL.load(Ordering::Relaxed) - db::now_ms();
    if remaining <= 0 {
        hide_tray_menu_window(app);
        return;
    }
    let handle = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(remaining as u64));
        let target = handle.clone();
        let _ = handle.run_on_main_thread(move || {
            if TRAY_MENU_EPOCH.load(Ordering::Relaxed) == epoch
                && tray_menu_window(&target).is_some_and(|w| !w.is_focused().unwrap_or(false))
            {
                hide_tray_menu_window(&target);
            }
        });
    });
}

#[tauri::command]
fn hide_tray_menu(app: AppHandle) {
    hide_tray_menu_window(&app);
}

#[tauri::command]
fn tray_menu_action(app: AppHandle, action: String) -> CmdResult<()> {
    match action.as_str() {
        "show" => {
            hide_tray_menu_window(&app);
            show_main(&app);
        }
        "pause" => {
            set_capture_paused(&app, !clipboard::capture_paused());
        }
        "autostart" => {
            let enabled = autostart_task_exists() || app.autolaunch().is_enabled().unwrap_or(false);
            apply_autostart(&app, !enabled)?;
        }
        "quit" => {
            if let Some(core) = app.try_state::<Core>() {
                core.persist_position();
            }
            app.exit(0);
        }
        _ => return Err("未知的托盘操作".into()),
    }
    Ok(())
}

fn hide_main(app: &AppHandle) {
    win::log_diagnostic("hide-main");
    FOCUS_EPOCH.fetch_add(1, Ordering::Relaxed);
    DRAG_HIDE_UNTIL.store(0, Ordering::Relaxed);
    WINDOW_VISIBLE.store(false, Ordering::Relaxed);
    if let Some(w) = main_window(app) {
        let _ = w.emit("window-hidden", ());
        if let Err(e) = w.hide() {
            win::log_diagnostic(&format!("hide-main-failed {e}"));
        }
    }
    #[cfg(target_os = "macos")]
    let _ = app.hide();
    if let Some(core) = app.try_state::<Core>() {
        core.persist_position();
    }
}

fn show_main(app: &AppHandle) {
    win::log_diagnostic("show-main-begin");
    hide_tray_menu_window(app);
    FOCUS_EPOCH.fetch_add(1, Ordering::Relaxed);
    DRAG_HIDE_UNTIL.store(0, Ordering::Relaxed);
    let Some(w) = main_window(app) else {
        win::log_diagnostic("show-main-no-window");
        return;
    };
    #[cfg(windows)]
    win::remember_foreground();
    #[cfg(target_os = "macos")]
    let _ = app.show();
    place_window(app, &w);
    let _ = w.unminimize();
    // Showing and focusing the WebView can emit a transient blur event.
    SUPPRESS_HIDE_UNTIL.store(db::now_ms() + SHOW_BLUR_GRACE_MS, Ordering::Relaxed);
    if let Err(e) = w.show() {
        win::log_diagnostic(&format!("show-main-failed {e}"));
        eprintln!("show main window failed: {e}");
        return;
    }
    // Call force_foreground directly — show_main already runs on the main thread
    // (dispatched by the hook dispatcher thread via run_on_main_thread).
    // The previous nested run_on_main_thread here was redundant and caused
    // re-entrance / blocking that made run_on_main_thread take >300 ms,
    // causing Windows to silently remove the low-level keyboard hook.
    #[cfg(windows)]
    if let Ok(hwnd) = w.hwnd() {
        win::force_foreground(hwnd.0 as _);
    }
    #[cfg(not(windows))]
    let _ = w.set_focus();
    WINDOW_VISIBLE.store(true, Ordering::Relaxed);
    win::log_diagnostic("show-main-complete");
    let _ = w.emit("window-shown", ());
}

fn toggle_main(app: &AppHandle) {
    let visible = WINDOW_VISIBLE.load(Ordering::Relaxed);
    win::log_diagnostic(&format!("toggle-main visible={}", visible));
    if visible {
        hide_main(app);
    } else {
        show_main(app);
    }
}

fn place_window(app: &AppHandle, w: &WebviewWindow) {
    if let Some(core) = app.try_state::<Core>() {
        let s = core.settings();
        if let (true, Some(x), Some(y)) = (s.remember_position, s.window_x, s.window_y)
            && let Ok(Some(_)) = app.monitor_from_point(x as f64 + 40.0, y as f64 + 20.0)
        {
            let _ = w.set_position(PhysicalPosition::new(x, y));
            return;
        }
    }
    place_near_cursor(app, w);
}

fn place_near_cursor(app: &AppHandle, w: &WebviewWindow) {
    let (Ok(cursor), Ok(size)) = (app.cursor_position(), w.outer_size()) else {
        return;
    };
    let mut x = cursor.x as i32 + 4;
    let mut y = cursor.y as i32 + 4;
    if let Ok(Some(monitor)) = app.monitor_from_point(cursor.x, cursor.y) {
        let area = monitor.work_area();
        let (left, top) = (area.position.x, area.position.y);
        let right = left + area.size.width as i32;
        let bottom = top + area.size.height as i32;
        x = x.min(right - size.width as i32).max(left);
        y = y.min(bottom - size.height as i32).max(top);
    }
    let _ = w.set_position(PhysicalPosition::new(x, y));
}

fn on_window_moved(app: &AppHandle, pos: PhysicalPosition<i32>) {
    let now = db::now_ms();
    if now < DRAG_HIDE_UNTIL.load(Ordering::Relaxed) {
        DRAG_HIDE_UNTIL.store(now + 1500, Ordering::Relaxed);
        SUPPRESS_HIDE_UNTIL.fetch_max(now + 800, Ordering::Relaxed);
    }
    let Some(core) = app.try_state::<Core>() else {
        return;
    };
    let Ok(mut s) = core.settings.write() else {
        return;
    };
    if s.remember_position && (s.window_x, s.window_y) != (Some(pos.x), Some(pos.y)) {
        s.window_x = Some(pos.x);
        s.window_y = Some(pos.y);
        core.position_dirty.store(true, Ordering::Relaxed);
    }
}

/// Pinned (keep_on_blur) or mid-drag: don't auto-hide.
fn should_keep_open(app: &AppHandle) -> bool {
    if db::now_ms() < SUPPRESS_HIDE_UNTIL.load(Ordering::Relaxed) {
        return true;
    }
    app.try_state::<Core>()
        .map(|c| c.settings().keep_on_blur)
        .unwrap_or(false)
}

fn on_main_focus_changed(app: &AppHandle, focused: bool) {
    let epoch = FOCUS_EPOCH.fetch_add(1, Ordering::Relaxed) + 1;
    let remaining = SUPPRESS_HIDE_UNTIL.load(Ordering::Relaxed) - db::now_ms();
    win::log_diagnostic(&format!(
        "main-focus focused={focused} visible={} hide-delay-ms={remaining}",
        WINDOW_VISIBLE.load(Ordering::Relaxed)
    ));
    if focused || !WINDOW_VISIBLE.load(Ordering::Relaxed) {
        return;
    }
    if !should_keep_open(app) {
        hide_main(app);
        return;
    }
    // A real blur during the short show/drag grace period used to be discarded forever.
    // Check again when the grace expires, unless focus or visibility has changed meanwhile.
    let app = app.clone();
    std::thread::spawn(move || {
        loop {
            if FOCUS_EPOCH.load(Ordering::Relaxed) != epoch {
                return;
            }
            let remaining = SUPPRESS_HIDE_UNTIL.load(Ordering::Relaxed) - db::now_ms();
            if remaining <= 0 {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(remaining.min(500) as u64));
        }
        let target = app.clone();
        let _ = app.run_on_main_thread(move || {
            if FOCUS_EPOCH.load(Ordering::Relaxed) == epoch
                && WINDOW_VISIBLE.load(Ordering::Relaxed)
                && !should_keep_open(&target)
                && main_window(&target).is_some_and(|window| !window.is_focused().unwrap_or(false))
            {
                win::log_diagnostic("deferred-blur-hide");
                hide_main(&target);
            }
        });
    });
}

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let mut tray = TrayIconBuilder::with_id("main")
        .tooltip("clipkeep 剪贴板")
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button,
                button_state: MouseButtonState::Up,
                position,
                ..
            } = event
            {
                match button {
                    MouseButton::Left => toggle_main(tray.app_handle()),
                    MouseButton::Right => show_tray_menu(tray.app_handle(), position),
                    _ => {}
                }
            }
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
}

/// Only confirmed corruption starts a fresh database. I/O, permission, and schema-version
/// errors must preserve the existing history for diagnosis or a newer app version.
fn open_db(data_dir: &std::path::Path) -> CmdResult<Connection> {
    let path = data_dir.join("clipkeep.db");
    match db::open(&path) {
        Ok(conn) => Ok(conn),
        Err(e)
            if matches!(
                &e,
                rusqlite::Error::SqliteFailure(error, _)
                    if matches!(error.code, rusqlite::ErrorCode::DatabaseCorrupt | rusqlite::ErrorCode::NotADatabase)
            ) =>
        {
            eprintln!("history DB unusable ({e}); starting a fresh one");
            let stamp = chrono_stamp();
            for suffix in ["", "-wal", "-shm"] {
                let from = data_dir.join(format!("clipkeep.db{suffix}"));
                if from.exists() {
                    std::fs::rename(
                        &from,
                        data_dir.join(format!("clipkeep-corrupt-{stamp}.db{suffix}")),
                    )
                    .map_err(|error| format!("无法保留损坏的历史数据库：{error}"))?;
                }
            }
            db::open(&path).map_err(to_err)
        }
        Err(error) => Err(format!("打开历史数据库失败，原文件已保留：{error}")),
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default();
    #[cfg(not(windows))]
    let builder = builder.plugin(tauri_plugin_global_shortcut::Builder::new().build());
    builder
        // With WebView2 focused, Tauri's default device filter can starve the host process's
        // WH_KEYBOARD_LL hook. Always keeps Win+V observable inside our own window too.
        .device_event_filter(tauri::DeviceEventFilter::Always)
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main(app)
        }))
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            Some(vec![AUTOSTART_ARG]),
        ))
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let handle = app.handle().clone();

            let data_dir = app.path().app_data_dir()?;
            std::fs::create_dir_all(&data_dir)?;
            let settings_file = SettingsFile::new(&data_dir);
            #[cfg(windows)]
            win::start_hotkey_diagnostics(&data_dir);
            #[cfg(not(windows))]
            win::initialize(&handle);
            let mut settings = settings_file.load();
            if !win::set_hotkey(settings.hotkey_enabled, &settings.hotkey) {
                settings.hotkey = Settings::default().hotkey;
                if !win::set_hotkey(settings.hotkey_enabled, &settings.hotkey) {
                    settings.hotkey_enabled = false;
                    eprintln!("No global shortcut available; open clipkeep from the tray");
                }
            }
            clipboard::set_capture_images(settings.capture_images);

            #[cfg(windows)]
            if !cfg!(debug_assertions) && handle.autolaunch().is_enabled().unwrap_or(false) {
                if let Err(e) = create_autostart_task() {
                    eprintln!("migrating autostart to elevated task failed: {e}");
                } else if let Err(e) = handle.autolaunch().disable() {
                    eprintln!("removing legacy autostart entry failed: {e}");
                }
            }
            build_tray(&handle)?;

            let db = open_db(&data_dir).map_err(std::io::Error::other)?;
            let images = ImageStore::new(&data_dir)?;
            // One-time cleanup: drop files left behind by a crash between writing and saving a row.
            if let Ok(referenced) = db::referenced_files(&db) {
                images.collect_garbage(&referenced);
            }

            let core = Core {
                db: Mutex::new(db),
                images,
                lan: lan::LanService::new(&data_dir),
                settings: RwLock::new(settings.clone()),
                settings_file,
                position_dirty: AtomicBool::new(false),
                data_dir,
            };
            app.manage(core);
            handle.state::<Core>().lan.restore(&handle);

            let monitor_handle = handle.clone();
            clipboard::start_monitor(move |captured| on_local_copy(&monitor_handle, captured));

            #[cfg(windows)]
            let hook_handle = handle.clone();
            #[cfg(windows)]
            win::install_hotkey_hook(move || {
                let h = hook_handle.clone();
                if let Err(e) = hook_handle.run_on_main_thread(move || toggle_main(&h)) {
                    win::log_diagnostic(&format!("dispatch-to-main-failed {e}"));
                }
            });

            if !std::env::args().any(|a| a == AUTOSTART_ARG) {
                show_main(&handle);
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if window.label() == "tray_menu" {
                match event {
                    WindowEvent::CloseRequested { api, .. } => {
                        api.prevent_close();
                        hide_tray_menu_window(window.app_handle());
                    }
                    WindowEvent::Focused(focused) => {
                        on_tray_menu_focus_changed(window.app_handle(), *focused)
                    }
                    _ => {}
                }
                return;
            }
            match event {
                WindowEvent::CloseRequested { api, .. } => {
                    api.prevent_close();
                    hide_main(window.app_handle());
                }
                WindowEvent::Focused(focused) => {
                    on_main_focus_changed(window.app_handle(), *focused)
                }
                WindowEvent::Moved(pos) => on_window_moved(window.app_handle(), *pos),
                _ => {}
            }
        })
        .invoke_handler(tauri::generate_handler![
            list_clips,
            get_clip_text,
            update_clip_text,
            get_capture_paused,
            toggle_capture_paused,
            get_lan_state,
            set_lan_receive,
            set_lan_copy_received,
            set_lan_auto_send,
            add_lan_peer,
            remove_lan_peer,
            rotate_lan_code,
            copy_lan_code,
            send_clip_to_peer,
            toggle_favorite,
            toggle_pinned,
            move_clip,
            delete_clip,
            clear_data,
            list_groups,
            create_group,
            rename_group,
            delete_group,
            reorder_groups,
            paste_clip,
            copy_clip,
            hide_window,
            hide_tray_menu,
            tray_menu_action,
            start_drag,
            toggle_keep_on_blur,
            get_autostart,
            set_autostart,
            get_settings,
            get_storage_usage,
            update_settings,
            export_data,
            import_data
        ])
        .run(tauri::generate_context!())
        .expect("error while running clipkeep");
}
