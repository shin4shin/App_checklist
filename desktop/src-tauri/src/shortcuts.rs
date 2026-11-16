use std::io::Write;
use std::process::{Command, Stdio};
use std::os::windows::process::CommandExt;

pub fn list(paths: Option<Vec<String>>) -> Result<serde_json::Value, String> {
    let mut child = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", include_str!("desktop-shortcuts.ps1")])
        .creation_flags(0x08000000)
        .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped())
        .spawn().map_err(|error| error.to_string())?;
    if let Some(mut input) = child.stdin.take() {
        if let Some(paths) = paths {
            input.write_all(serde_json::to_string(&paths).map_err(|error| error.to_string())?.as_bytes()).map_err(|error| error.to_string())?;
        }
    }
    let output = child.wait_with_output().map_err(|error| error.to_string())?;
    if !output.status.success() { return Err("바탕화면 바로가기를 읽지 못했습니다.".into()); }
    serde_json::from_slice(&output.stdout).map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    #[test]
    fn reads_unicode_steam_shortcut_without_launching_it() {
        let path = std::env::temp_dir().join(format!("바탕화면 테스트-{}.url", std::process::id()));
        std::fs::write(&path, "[InternetShortcut]\r\nURL=steam://rungameid/123456\r\n").unwrap();
        let result = super::list(Some(vec![path.to_string_lossy().into_owned()]));
        std::fs::remove_file(&path).unwrap();
        let rows = result.unwrap();
        assert_eq!(rows.as_array().unwrap().len(), 1);
        assert_eq!(rows[0]["id"], "steam://rungameid/123456");
        assert!(rows[0]["name"].as_str().unwrap().starts_with("바탕화면 테스트-"));
    }
}
