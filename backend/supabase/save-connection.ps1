$ErrorActionPreference = 'Stop'
$connection = Read-Host 'Paste the full Transaction pooler URI with your DB password' -AsSecureString
$credential = New-Object System.Management.Automation.PSCredential('supabase', $connection)
$credential | Export-Clixml -LiteralPath (Join-Path $PSScriptRoot '.connection.xml')
Write-Output 'Connection saved encrypted for the current Windows account.'
