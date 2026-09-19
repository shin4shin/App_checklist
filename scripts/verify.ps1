param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
# Gradle's Windows test worker can misread non-ASCII paths in its Java argument file.
# Compile and test an exact source copy in a short ASCII path; keep the original checkout intact.
$verificationRoot = Join-Path $env:TEMP ('daily-check-verify-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path (Join-Path $verificationRoot 'app') -Force | Out-Null
foreach ($name in @('settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew.bat', 'gradle')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $name) -Destination $verificationRoot -Recurse
}
if (Test-Path -LiteralPath (Join-Path $projectRoot 'local.properties')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot 'local.properties') -Destination $verificationRoot
}
foreach ($name in @('src', 'build.gradle.kts', 'proguard-rules.pro')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot "app/$name") -Destination (Join-Path $verificationRoot 'app') -Recurse
}
$gradleArguments = @('-p', $verificationRoot, 'testDebugUnitTest', 'assembleDebug', 'lintDebug')
if ($Offline) { $gradleArguments += '--offline' }
& (Join-Path $verificationRoot 'gradlew.bat') @gradleArguments
$verificationExitCode = $LASTEXITCODE
Write-Output "Verification workspace and reports: $verificationRoot"
if ($verificationExitCode -eq 0) {
    $outputDirectory = Join-Path $projectRoot 'app/build/verified'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $verificationRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination $outputDirectory
    Copy-Item -LiteralPath (Join-Path $verificationRoot 'app/build/reports') -Destination $outputDirectory -Recurse -Force
}
exit $verificationExitCode
