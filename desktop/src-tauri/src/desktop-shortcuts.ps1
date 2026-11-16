$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.Drawing
$inputPaths = [Console]::In.ReadToEnd()
$shell = New-Object -ComObject WScript.Shell
$paths = if ($inputPaths.Trim()) {
    @($inputPaths | ConvertFrom-Json)
} else {
    @([Environment]::GetFolderPath('DesktopDirectory'), [Environment]::GetFolderPath('CommonDesktopDirectory')) |
        Select-Object -Unique | ForEach-Object {
            if (Test-Path -LiteralPath $_) { Get-ChildItem -LiteralPath $_ -File | Select-Object -ExpandProperty FullName }
        }
}
$seen = @{}
$items = @(foreach ($path in $paths) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $file = Get-Item -LiteralPath $path
    $extension = $file.Extension.ToLowerInvariant()
    if ($extension -notin @('.lnk', '.url', '.exe')) { continue }
    $target = $file.FullName
    $iconPath = $file.FullName
    try {
        if ($extension -eq '.lnk') {
            $shortcut = $shell.CreateShortcut($file.FullName)
            $target = $shortcut.TargetPath + ' ' + $shortcut.Arguments
            $iconPath = $shortcut.IconLocation -replace ',\s*-?\d+$', ''
            if (!$iconPath) { $iconPath = $shortcut.TargetPath }
        } elseif ($extension -eq '.url') {
            $lines = Get-Content -LiteralPath $file.FullName
            $url = $lines | Where-Object { $_ -match '^URL=' } | Select-Object -First 1
            if (!$url) { continue }
            $target = $url.Substring(4).Trim()
            $iconLine = $lines | Where-Object { $_ -match '^IconFile=' } | Select-Object -First 1
            if ($iconLine) { $iconPath = $iconLine.Substring(9).Trim() }
        }
        if (!$target.Trim()) { continue }
        $id = $target.Trim().ToLowerInvariant()
        if ($seen.ContainsKey($id)) { continue }
        $seen[$id] = $true
        $iconData = ''
        $iconPath = [Environment]::ExpandEnvironmentVariables($iconPath.Trim('"'))
        if (Test-Path -LiteralPath $iconPath -PathType Leaf) {
            $icon = $null; $bitmap = $null; $resized = $null; $graphics = $null; $stream = $null
            try {
                $icon = [System.Drawing.Icon]::ExtractAssociatedIcon($iconPath)
                if ($icon) {
                    $bitmap = $icon.ToBitmap()
                    $resized = New-Object System.Drawing.Bitmap(64, 64)
                    $graphics = [System.Drawing.Graphics]::FromImage($resized)
                    $graphics.DrawImage($bitmap, 0, 0, 64, 64)
                    $stream = New-Object System.IO.MemoryStream
                    $resized.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
                    $iconData = 'data:image/png;base64,' + [Convert]::ToBase64String($stream.ToArray())
                }
            } catch {} finally {
                foreach ($resource in @($graphics, $bitmap, $resized, $icon, $stream)) { if ($resource) { $resource.Dispose() } }
            }
        }
        [PSCustomObject]@{ id = $id; name = $file.BaseName; icon = $iconData }
    } catch {}
})
ConvertTo-Json -InputObject $items -Compress
