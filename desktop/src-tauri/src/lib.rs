use tauri::Manager;
static RUNNING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

#[cfg(windows)]
mod desktop;

#[tauri::command]
fn desktop_position(window: tauri::WebviewWindow) -> Result<(i32, i32), String> {
    #[cfg(windows)] { desktop::position(&window) }
    #[cfg(not(windows))] { window.outer_position().map(|p| (p.x, p.y)).map_err(|e| e.to_string()) }
}
#[tauri::command]
fn desktop_set_position(window: tauri::WebviewWindow, x: i32, y: i32) -> Result<(), String> {
    #[cfg(windows)] { desktop::set_position(&window, x, y) }
    #[cfg(not(windows))] { window.set_position(tauri::PhysicalPosition::new(x, y)).map_err(|e| e.to_string()) }
}
#[tauri::command]
fn desktop_edit_mode(window: tauri::WebviewWindow, editing: bool) -> Result<(), String> {
    #[cfg(windows)] { desktop::edit_mode(&window, editing) }
    #[cfg(not(windows))] { let _ = (window, editing); Ok(()) }
}
pub fn run() {
    #[cfg(windows)]
    let _instance = match desktop::single_instance() { Some(guard) => guard, None => return };
    if std::env::args().any(|arg| arg == "--startup") {
        std::thread::sleep(std::time::Duration::from_secs(15));
    }
    use tauri::{menu::{Menu, MenuItem}, tray::TrayIconBuilder};
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![desktop_position, desktop_set_position, desktop_edit_mode])
        .on_window_event(|window, event| {
            #[cfg(windows)]
            if matches!(event, tauri::WindowEvent::Focused(true)) {
                if let Some(webview) = window.app_handle().get_webview_window(window.label()) {
                    desktop::lower(&webview, false);
                }
            }
        })
        .setup(|app| {
            #[cfg(all(windows, not(debug_assertions)))]
            {
                use std::os::windows::process::CommandExt;
                let scheduled = std::process::Command::new("schtasks.exe")
                    .args(["/Query", "/TN", "DailyCheckWidget"])
                    .creation_flags(0x08000000)
                    .output().map(|result| result.status.success()).unwrap_or(false);
                if !scheduled && !std::env::args().any(|arg| arg == "--startup") {
                let executable = std::env::current_exe()?;
                let status = std::process::Command::new("reg.exe")
                    .args(["add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "DailyCheckWidget", "/t", "REG_SZ", "/d"])
                    .arg(format!("\"{}\" --startup", executable.display()))
                    .arg("/f")
                    .creation_flags(0x08000000)
                    .status()?;
                if !status.success() {
                    eprintln!("Windows 자동 시작 등록에 실패했습니다");
                }
                }
            }
            let window = app.get_webview_window("main").unwrap();
            #[cfg(windows)]
            {
                desktop::maintain(&window);
                let app_handle = app.handle().clone();
                std::thread::spawn(move || while RUNNING.load(std::sync::atomic::Ordering::Relaxed) {
                    std::thread::sleep(std::time::Duration::from_secs(5));
                    let handle = app_handle.clone();
                    if app_handle.run_on_main_thread(move || {
                                                let window = handle.get_webview_window("main").or_else(|| {
                            let config = handle.config().app.windows.first()?;
                            tauri::WebviewWindowBuilder::from_config(&handle, config).ok()?.build().ok()
                        });
                        if let Some(window) = window {
                            desktop::maintain(&window);
                            if !desktop::visible() { desktop::set_visible(&window, false); }
                        }
                    }).is_err() { break; }
                });
            }
            let toggle = MenuItem::with_id(app, "toggle", "위젯 보이기/숨기기", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "종료", true, None::<&str>)?;
            let recover = MenuItem::with_id(app, "recover", "창 위치 복구", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&toggle, &recover, &quit])?;
            let mut tray = TrayIconBuilder::new().menu(&menu).on_menu_event(|app, event| {
                if event.id.as_ref() == "quit" { app.exit(0); }
                if event.id.as_ref() == "recover" {
                    if let Some(window) = app.get_webview_window("main") {
                        #[cfg(windows)]
                        let _ = desktop::set_position(&window, 40, 40);
                        let _ = window.show();
                        #[cfg(windows)]
                        desktop::set_visible(&window, true);
                    }
                }
                if event.id.as_ref() == "toggle" {
                    if let Some(window) = app.get_webview_window("main") {
                        #[cfg(windows)]
                        { desktop::set_visible(&window, !desktop::visible()); }
                        #[cfg(not(windows))]
                        if window.is_visible().unwrap_or(false) { let _ = window.hide(); }
                        else {
                            #[cfg(windows)]
                            desktop::set_visible(&window, true);
                            #[cfg(not(windows))]
                            let _ = window.show();
                        }
                    }
                }
            });
            if let Some(icon) = app.default_window_icon() { tray = tray.icon(icon.clone()); }
            tray.build(app)?;
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("위젯을 시작하지 못했습니다")
        .run(|_, event| {
            match event {
                tauri::RunEvent::ExitRequested { code: None, api, .. } => api.prevent_exit(),
                tauri::RunEvent::ExitRequested { code: Some(_), .. } | tauri::RunEvent::Exit => {
                    RUNNING.store(false, std::sync::atomic::Ordering::Relaxed);
                }
                _ => {}
            }
        });
}





