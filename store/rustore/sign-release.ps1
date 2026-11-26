[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][string]$Keystore,
    [Parameter(Mandatory=$true)][string]$KeyAlias,
    [Parameter(Mandatory=$true)][string]$BuildToolsDir,
    [Parameter(Mandatory=$true)][string]$OutputDir
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $ApkPath).Path
$key = (Resolve-Path -LiteralPath $Keystore).Path
if (!(Test-Path -LiteralPath $source -PathType Leaf) -or [IO.Path]::GetExtension($source) -ne '.apk') { throw 'Provide the unsigned release APK.' }
if (!(Test-Path -LiteralPath $key -PathType Leaf)) { throw 'Keystore must be a file.' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
if ($key.StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) { throw 'Keep the permanent keystore outside the repository.' }
$destination = [IO.Path]::GetFullPath($OutputDir)
if (($destination.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar).StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) { throw 'Use an output directory outside the repository.' }
if (Test-Path -LiteralPath $destination) { throw 'Choose a new output directory.' }
$align = (Resolve-Path -LiteralPath (Join-Path $BuildToolsDir 'zipalign.exe')).Path
$signer = (Resolve-Path -LiteralPath (Join-Path $BuildToolsDir 'apksigner.bat')).Path
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $Executable" }
}
# Never re-sign a debug or already signed APK by accident.
& $signer verify $source 2>$null
if ($LASTEXITCODE -eq 0) { throw 'Input APK is already signed.' }
New-Item -ItemType Directory -Path $destination | Out-Null
$aligned = Join-Path $destination 'aligned-temporary.apk'
$apk = Join-Path $destination 'trachu-release.apk'
try {
    Invoke-Checked $align @('-P','16','4',$source,$aligned)
    # The signing tool prompts for the keystore password. No password is stored here.
    Invoke-Checked $signer @('sign','--ks',$key,'--ks-key-alias',$KeyAlias,'--out',$apk,$aligned)
    Invoke-Checked $signer @('verify','--verbose','--print-certs',$apk)
    Invoke-Checked $align @('-c','-P','16','4',$apk)
    Write-Host "Verified release APK: $apk"
} catch {
    if (Test-Path -LiteralPath $apk) { Remove-Item -LiteralPath $apk }
    throw
} finally {
    if (Test-Path -LiteralPath $aligned) { Remove-Item -LiteralPath $aligned }
}
