#requires -version 5.1
# Create or reuse the permanent BeeTransceiver release identity without console prompts.
# Password is random, never logged, and DPAPI-encrypted for this Windows user.
$ErrorActionPreference = 'Stop'
$repo = 'beeflat-dotcom/BeeTransceiver'
$alias = 'bee-transceiver'
$folder = Join-Path $env:LOCALAPPDATA 'BeeTransceiverSigning'
$keystore = Join-Path $folder 'beetransceiver-release.p12'
$protectedPassword = Join-Path $folder 'release-password.dpapi'
$gh = 'C:\Program Files\GitHub CLI\gh.exe'
$keytool = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
if (-not (Test-Path $gh) -or -not (Test-Path $keytool)) {
    throw 'GitHub CLI or Android Studio JDK was not found.'
}
& $gh auth status *> $null
if ($LASTEXITCODE -ne 0) { throw 'GitHub authentication is required.' }
New-Item -ItemType Directory -Force -Path $folder | Out-Null
if ((Test-Path $keystore) -xor (Test-Path $protectedPassword)) {
    throw 'Partial signing identity found; refusing to create/recreate a key.'
}
if (-not (Test-Path $keystore)) {
    $random = New-Object byte[] 48
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($random) } finally { $generator.Dispose() }
    $password = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+','-').Replace('/','_')
    [Array]::Clear($random, 0, $random.Length)
    $secure = ConvertTo-SecureString -String $password -AsPlainText -Force
    $secure | ConvertFrom-SecureString | Set-Content -LiteralPath $protectedPassword -Encoding ASCII
    & $keytool -genkeypair -noprompt -alias $alias -keyalg RSA -keysize 3072 -validity 36500 -storetype PKCS12 -keystore $keystore -storepass $password -keypass $password -dname 'CN=Bee Transceiver,O=Bee Flat,C=JP' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not generate the release signing key.' }
} else {
    $secure = Get-Content -LiteralPath $protectedPassword -Raw | ConvertTo-SecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}
try {
    & $keytool -list -keystore $keystore -storepass $password -alias $alias *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Permanent signing key validation failed.' }
    $base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
    $base64 | & $gh secret set BEE_KEYSTORE_BASE64 -R $repo
    if ($LASTEXITCODE -ne 0) { throw 'Could not configure the release keystore secret.' }
    $password | & $gh secret set BEE_SIGNING_STORE_PASSWORD -R $repo
    if ($LASTEXITCODE -ne 0) { throw 'Could not configure the release store password.' }
    $password | & $gh secret set BEE_SIGNING_KEY_PASSWORD -R $repo
    if ($LASTEXITCODE -ne 0) { throw 'Could not configure the release key password.' }
    $alias | & $gh secret set BEE_SIGNING_KEY_ALIAS -R $repo
    if ($LASTEXITCODE -ne 0) { throw 'Could not configure the release key alias.' }
    Write-Output 'RELEASE_SIGNING_CONFIGURED=true'
    Write-Output ('KEY_PATH=' + $keystore)
    Write-Output 'KEY_PASSWORD_PROTECTED_WITH_WINDOWS_DPAPI=true'
    Write-Output 'BACKUP_NOTE=Back up the key file and recovery method before replacing this Windows profile.'
}
finally {
    $password = $null
    $base64 = $null
    $secure = $null
}
