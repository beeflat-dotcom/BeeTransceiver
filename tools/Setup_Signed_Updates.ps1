#requires -version 5.1
$ErrorActionPreference = 'Stop'
$repo = 'beeflat-dotcom/BeeTransceiver'
$alias = 'bee-transceiver'
$folder = Join-Path $env:LOCALAPPDATA 'BeeTransceiverSigning'
$keystore = Join-Path $folder 'beetransceiver-release.p12'

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'GitHub CLI missing. Run: winget install --id GitHub.cli --exact --source winget'
}
& gh auth status
if ($LASTEXITCODE -ne 0) { throw 'GitHub login required: gh auth login' }

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
$keytoolPath = if ($keytool) { $keytool.Source } else { $null }
if (-not $keytoolPath) {
    $sdkFolder = 'C:\Program Files\Eclipse Adoptium'
    $jdks = @(Get-ChildItem $sdkFolder -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
    foreach ($jdk in $jdks) {
        $candidate = Join-Path $jdk.FullName 'bin\keytool.exe'
        if (Test-Path $candidate) { $keytoolPath = $candidate; break }
    }
}
if (-not $keytoolPath) {
    throw 'JDK 17 not found. Install once: winget install --id EclipseAdoptium.Temurin.17.JDK --exact --source winget'
}
New-Item -ItemType Directory -Path $folder -Force | Out-Null
Write-Host "BeeTransceiver signing identity: $keystore" -ForegroundColor Cyan
if (Test-Path $keystore) {
    Write-Host 'Existing signing key will be reused (not replaced).' -ForegroundColor Green
} else {
    Write-Host 'Creating a permanent signing key. Never delete or regenerate this key.' -ForegroundColor Yellow
}
$secure = Read-Host 'Signing password (at least 8 ASCII characters)' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
if ($password.Length -lt 8) { throw 'Signing password must have at least 8 characters.' }
if (-not (Test-Path $keystore)) {
    & $keytoolPath -genkeypair -noprompt -alias $alias -keyalg RSA -keysize 3072 -validity 36500 -storetype PKCS12 -keystore $keystore -storepass $password -keypass $password -dname 'CN=Bee Transceiver,O=Bee Flat,C=JP'
    if ($LASTEXITCODE -ne 0) { throw 'Keystore generation failed.' }
}
& $keytoolPath -list -keystore $keystore -storepass $password -alias $alias *> $null
if ($LASTEXITCODE -ne 0) { throw 'Could not unlock existing keystore. Secrets have not been changed.' }

$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
$base64 | & gh secret set BEE_KEYSTORE_BASE64 -R $repo
if ($LASTEXITCODE -ne 0) { throw 'Could not upload BEE_KEYSTORE_BASE64' }
$password | & gh secret set BEE_SIGNING_STORE_PASSWORD -R $repo
if ($LASTEXITCODE -ne 0) { throw 'Could not upload BEE_SIGNING_STORE_PASSWORD' }
$password | & gh secret set BEE_SIGNING_KEY_PASSWORD -R $repo
if ($LASTEXITCODE -ne 0) { throw 'Could not upload BEE_SIGNING_KEY_PASSWORD' }
$alias | & gh secret set BEE_SIGNING_KEY_ALIAS -R $repo
if ($LASTEXITCODE -ne 0) { throw 'Could not upload BEE_SIGNING_KEY_ALIAS' }
$password = $null
$base64 = $null
Write-Host ''
Write-Host 'SIGNING SETUP SUCCESS: all 4 GitHub Actions secrets installed.' -ForegroundColor Green
Write-Host 'Back up the following file securely; keep password separately:' -ForegroundColor Yellow
Write-Host $keystore
Write-Host 'Then run GitHub Actions -> Publish signed BeeTransceiver APK -> Run workflow.'
