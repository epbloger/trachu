[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ArtifactDir,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedCommit,
    [switch]$VerifyOnly,
    [string]$Keystore,
    [string]$KeyAlias,
    [string]$BuildToolsDir,
    [string]$OutputDir
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ArtifactDir).Path
$commit = (Get-Content -LiteralPath (Join-Path $root 'qa/source-commit.txt') -Raw).Trim()
if ($commit -ne $ExpectedCommit) { throw "Unexpected source commit: $commit" }
$manifest = Get-Content -LiteralPath (Join-Path $root 'qa/SHA256SUMS')
$files = @{}
foreach ($line in $manifest) {
    if ($line -match '^([a-fA-F0-9]{64})\s+\*?(app/build/outputs/(apk/release/[^/\\]+\.apk|bundle/release/[^/\\]+\.aab))$') {
        $expected = $Matches[1]
        $relative = $Matches[2]
        $source = Join-Path $root $relative
        if (!(Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        $extension = [IO.Path]::GetExtension($source)
        if ($files.ContainsKey($extension)) { throw "Duplicate release artifact: $extension" }
        $actual = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        if ($actual -ne $expected) { throw "Checksum mismatch: $relative" }
        $files[$extension] = $source
        Write-Host "Verified $relative ($actual)"
    }
}
if ($files.Count -eq 0) { throw 'No release APK/AAB found in the verified artifact directory.' }
if ($VerifyOnly) { return }
if (!$Keystore -or !$KeyAlias -or !$OutputDir) { throw 'Provide Keystore, KeyAlias and OutputDir, or use VerifyOnly.' }
$key = (Resolve-Path -LiteralPath $Keystore).Path
if (!(Test-Path -LiteralPath $key -PathType Leaf)) { throw 'Keystore must be a file.' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
if ($key.StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) { throw 'Keep the permanent keystore outside the repository.' }
$destination = [IO.Path]::GetFullPath($OutputDir)
if (($destination.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar).StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) { throw 'Use an output directory outside the repository.' }
if (Test-Path -LiteralPath $destination) { throw 'Choose a new output directory; existing files will not be overwritten.' }
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $Executable" }
}
if ($files.ContainsKey('.apk')) {
    if (!$BuildToolsDir) { throw 'APK signing requires Android SDK Build Tools 35.0.0 or newer; provide BuildToolsDir.' }
    $align = (Resolve-Path -LiteralPath (Join-Path $BuildToolsDir 'zipalign.exe')).Path
    $signer = (Resolve-Path -LiteralPath (Join-Path $BuildToolsDir 'apksigner.bat')).Path
}
if ($files.ContainsKey('.aab')) {
    $jarSigner = (Get-Command jarsigner -ErrorAction Stop).Source
    $keyTool = (Get-Command keytool -ErrorAction Stop).Source
}
# The signing utilities ask for passwords interactively. Never accept passwords
# as script arguments, print them, or write them to files/environment variables.
New-Item -ItemType Directory -Path $destination | Out-Null
$aligned = Join-Path $destination 'aligned-temporary.apk'
try {
    if ($files.ContainsKey('.apk')) {
        $apk = Join-Path $destination 'trachu-release.apk'
        Invoke-Checked $align @('-P','16','4',$files['.apk'],$aligned)
        Invoke-Checked $signer @('sign','--ks',$key,'--ks-key-alias',$KeyAlias,'--out',$apk,$aligned)
        Invoke-Checked $signer @('verify','--verbose','--print-certs',$apk)
        Invoke-Checked $align @('-c','-P','16','4',$apk)
    }
    if ($files.ContainsKey('.aab')) {
        $aab = Join-Path $destination 'trachu-release.aab'
        Invoke-Checked $jarSigner @('-keystore',$key,'-signedjar',$aab,$files['.aab'],$KeyAlias)
        Invoke-Checked $jarSigner @('-verify','-verbose','-certs',$aab)
        Invoke-Checked $keyTool @('-printcert','-jarfile',$aab)
    }
    $report = @("source_commit=$commit")
    Get-ChildItem -LiteralPath $destination -File | Where-Object Name -in @('trachu-release.apk','trachu-release.aab') | ForEach-Object {
        $report += "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  $($_.Name)"
    }
    $report | Set-Content -LiteralPath (Join-Path $destination 'SIGNED-SHA256SUMS.txt') -Encoding ASCII
    Write-Host 'Signing and signature verification completed. Confirm the printed publisher certificate before uploading.'
} finally {
    if (Test-Path -LiteralPath $aligned) { Remove-Item -LiteralPath $aligned }
}
