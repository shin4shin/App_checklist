use std::{ffi::c_void, sync::{Mutex, atomic::{AtomicBool, Ordering}}};
use tauri::Emitter;
type Hwnd = *mut c_void;
#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Point { x: i32, y: i32 }
#[repr(C)]
#[derive(Default)]
struct Rect { left: i32, top: i32, right: i32, bottom: i32 }
#[derive(Clone, Copy)]
struct Attachment { parent: usize, pid: u32, style: isize, original_parent: usize }
static STATE: Mutex<Option<Attachment>> = Mutex::new(None);
static VISIBLE: AtomicBool = AtomicBool::new(true);
static EDITING: AtomicBool = AtomicBool::new(false);
pub fn edit_mode(window: &tauri::WebviewWindow, editing: bool) -> Result<(), String> {
    EDITING.store(editing, Ordering::Relaxed);
    if !editing {
        let result = attach(window);
        if result.is_err() { EDITING.store(true, Ordering::Relaxed); }
        return result;
    }
    let child = window.hwnd().map_err(|e| e.to_string())?.0 as Hwnd;
    let (x, y) = position(window)?;
    let previous = STATE.lock().unwrap().take();
    if let Some(state) = previous { unsafe {
        SetLastError(0);
        let result = SetParent(child, std::ptr::null_mut());
        let error = GetLastError();
        if result.is_null() && error != 0 { *STATE.lock().unwrap() = Some(state); EDITING.store(false, Ordering::Relaxed); return Err(format!("조정 모드 전환 실패: {error}")); }
        SetWindowLongPtrW(child, -16, state.style);
        SetWindowPos(child, std::ptr::null_mut(), x, y, 0, 0, 0x1 | 0x10 | 0x20 | 0x40);
    } }
    Ok(())
}
pub fn visible() -> bool { VISIBLE.load(Ordering::Relaxed) }
pub fn set_visible(window: &tauri::WebviewWindow, show: bool) {
    VISIBLE.store(show, Ordering::Relaxed);
    if show { lower(window, true); } else { let _ = window.hide(); }
}
#[link(name="user32")]
unsafe extern "system" {
    fn FindWindowW(class: *const u16, title: *const u16) -> Hwnd;
    fn FindWindowExW(parent: Hwnd, after: Hwnd, class: *const u16, title: *const u16) -> Hwnd;
    fn EnumWindows(callback: unsafe extern "system" fn(Hwnd, isize) -> i32, data: isize) -> i32;
    fn SendMessageTimeoutW(hwnd: Hwnd, msg: u32, w: usize, l: isize, flags: u32, timeout: u32, result: *mut usize) -> isize;
    fn SetParent(child: Hwnd, parent: Hwnd) -> Hwnd;
    fn GetParent(hwnd: Hwnd) -> Hwnd;
    fn GetWindowLongPtrW(hwnd: Hwnd, index: i32) -> isize;
    fn SetWindowLongPtrW(hwnd: Hwnd, index: i32, value: isize) -> isize;
    fn GetWindowThreadProcessId(hwnd: Hwnd, pid: *mut u32) -> u32;
    fn IsWindow(hwnd: Hwnd) -> i32;
    fn IsWindowVisible(hwnd: Hwnd) -> i32;
    fn GetWindowRect(hwnd: Hwnd, rect: *mut Rect) -> i32;
    fn WindowFromPoint(point: Point) -> Hwnd;
    fn IsChild(parent: Hwnd, child: Hwnd) -> i32;
    fn ScreenToClient(hwnd: Hwnd, point: *mut Point) -> i32;
    fn ShowWindow(hwnd: Hwnd, command: i32) -> i32;
    fn SetWindowPos(hwnd: Hwnd, after: Hwnd, x: i32, y: i32, width: i32, height: i32, flags: u32) -> i32;
}
#[link(name="kernel32")]
unsafe extern "system" {
    fn SetLastError(code: u32);
    fn GetLastError() -> u32;
    fn CreateMutexW(attributes: *mut c_void, owner: i32, name: *const u16) -> Hwnd;
    fn CloseHandle(handle: Hwnd) -> i32;
}
pub struct InstanceGuard(Hwnd);
impl Drop for InstanceGuard {
    fn drop(&mut self) { unsafe { CloseHandle(self.0); } }
}
pub fn single_instance() -> Option<InstanceGuard> {
    unsafe {
        let handle = CreateMutexW(std::ptr::null_mut(), 0, wide("Local\\DailyCheckWidget.Singleton.v1").as_ptr());
        if handle.is_null() { return None; }
        if GetLastError() == 183 { CloseHandle(handle); return None; }
        Some(InstanceGuard(handle))
    }
}
fn wide(s: &str) -> Vec<u16> { s.encode_utf16().chain(Some(0)).collect() }
unsafe fn pid(hwnd: Hwnd) -> u32 { let mut id = 0; GetWindowThreadProcessId(hwnd, &mut id); id }
struct Search { explorer: u32, candidates: Vec<usize> }
unsafe extern "system" fn find(hwnd: Hwnd, data: isize) -> i32 {
    let search = &mut *(data as *mut Search);
    let view = FindWindowExW(hwnd, std::ptr::null_mut(), wide("SHELLDLL_DefView").as_ptr(), std::ptr::null());
    if !view.is_null() && pid(hwnd) == search.explorer {
        if pid(view) == search.explorer && IsWindowVisible(hwnd) != 0 {
            search.candidates.push(hwnd as usize);
        }
    }
    1
}
pub fn lower(window: &tauri::WebviewWindow, show: bool) {
    if EDITING.load(Ordering::Relaxed) { return; }
    if let Ok(hwnd) = window.hwnd() { unsafe {
        if show { ShowWindow(hwnd.0 as Hwnd, 4); }
        let attached = STATE.lock().unwrap().map(|state| GetParent(hwnd.0 as Hwnd) == state.parent as Hwnd).unwrap_or(false);
        let after = if attached { std::ptr::null_mut() } else { 1_usize as Hwnd };
        SetWindowPos(hwnd.0 as Hwnd, after, 0, 0, 0, 0, 0x1 | 0x2 | 0x10);
    } }
}
pub fn position(window: &tauri::WebviewWindow) -> Result<(i32, i32), String> {
    let hwnd = window.hwnd().map_err(|e| e.to_string())?.0 as Hwnd;
    let mut rect = Rect::default();
    if unsafe { GetWindowRect(hwnd, &mut rect) } == 0 { return Err("화면 위치 조회 실패".into()); }
    Ok((rect.left, rect.top))
}
pub fn set_position(window: &tauri::WebviewWindow, x: i32, y: i32) -> Result<(), String> {
    let hwnd = window.hwnd().map_err(|e| e.to_string())?.0 as Hwnd;
    let mut point = Point { x, y };
    unsafe {
        let parent = GetParent(hwnd);
        if !parent.is_null() && ScreenToClient(parent, &mut point) == 0 { return Err("좌표 변환 실패".into()); }
        if SetWindowPos(hwnd, std::ptr::null_mut(), point.x, point.y, 0, 0, 0x1 | 0x4 | 0x10) == 0 { return Err("위치 복원 실패".into()); }
    }
    Ok(())
}
pub fn attach(window: &tauri::WebviewWindow) -> Result<(), String> {
    unsafe {
        let progman = FindWindowW(wide("Progman").as_ptr(), std::ptr::null());
        if progman.is_null() { return Err("Explorer 바탕화면을 찾을 수 없습니다".into()); }
        let mut result = 0;
        SendMessageTimeoutW(progman, 0x052c, 0, 0, 0x2, 500, &mut result);
        let mut search = Search { explorer: pid(progman), candidates: vec![] };
        EnumWindows(find, &mut search as *mut Search as isize);
        search.candidates.sort_unstable(); search.candidates.dedup();
        if search.candidates.len() != 1 { return Err("표시 가능한 바탕화면 호스트가 없거나 모호합니다".into()); }
        let parent = search.candidates[0] as Hwnd;
        let child = window.hwnd().map_err(|e| e.to_string())?.0 as Hwnd;
        let (x, y) = position(window)?;
        let mut state = STATE.lock().unwrap();
        let original = state.unwrap_or(Attachment { parent: 0, pid: 0, style: GetWindowLongPtrW(child, -16), original_parent: GetParent(child) as usize });
        SetWindowLongPtrW(child, -16, (original.style & !(0x80000000_u32 as isize)) | 0x40000000);
        SetLastError(0);
        let previous = SetParent(child, parent);
        let error = GetLastError();
        if (previous.is_null() && error != 0) || GetParent(child) != parent {
            SetWindowLongPtrW(child, -16, original.style);
            return Err(format!("바탕화면 연결 실패: {error}"));
        }
        *state = Some(Attachment { parent: parent as usize, pid: search.explorer, ..original });
        drop(state);
        set_position(window, x, y)?;
        SetWindowPos(child, std::ptr::null_mut(), 0, 0, 0, 0, 0x1 | 0x2 | 0x10 | 0x20);
        lower(window, visible());
    }
    Ok(())
}
pub fn maintain(window: &tauri::WebviewWindow) {
    if EDITING.load(Ordering::Relaxed) { return; }
    let valid = STATE.lock().unwrap().map(|s| unsafe {
        IsWindow(s.parent as Hwnd) != 0 && pid(s.parent as Hwnd) == s.pid
            && window.hwnd().map(|h| GetParent(h.0 as Hwnd) == s.parent as Hwnd).unwrap_or(false)
    }).unwrap_or(false);
    if valid {
        if let Ok(handle) = window.hwnd() { unsafe {
            let child = handle.0 as Hwnd;
            let mut rect = Rect::default();
            GetWindowRect(child, &mut rect);
            let target = WindowFromPoint(Point { x: (rect.left + rect.right) / 2, y: rect.bottom - 24 });
            let clickable = target == child || IsChild(child, target) != 0;
            let status = if clickable { "바탕화면 연결됨 · 입력 영역 정상" } else { "바탕화면 연결됨 · 다른 창이 입력 영역을 가리고 있음" };
            let _ = window.emit("desktop-status", status);
            if let Ok(exe) = std::env::current_exe() {
                let _ = std::fs::write(exe.with_file_name("desktop-status.log"), format!("{status}\nbounds={},{},{},{}", rect.left, rect.top, rect.right, rect.bottom));
            }
        } }
        lower(window, false);
        return;
    }
    match attach(window) {
        Ok(()) => { let _ = window.emit("desktop-status", "바탕화면 연결됨"); }
        Err(message) => {
            if let Ok(hwnd) = window.hwnd() { unsafe {
                let child = hwnd.0 as Hwnd;
                let previous = STATE.lock().unwrap().take();
                if let Some(state) = previous {
                    let mut parent = state.original_parent as Hwnd;
                    if IsWindow(parent) == 0 { parent = std::ptr::null_mut(); }
                    SetParent(child, parent);
                    SetWindowLongPtrW(child, -16, state.style);
                }
                lower(window, visible());
            } }
            let _ = window.emit("desktop-status", format!("일반 창으로 표시 중: {message}"));
        }
    }
}

