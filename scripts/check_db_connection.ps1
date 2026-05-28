param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ForwardArgs
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir

& powershell -ExecutionPolicy Bypass -File (Join-Path $ProjectRoot "build_for_win.ps1") check-db @ForwardArgs
