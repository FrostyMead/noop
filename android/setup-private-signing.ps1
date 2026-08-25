[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$KeyAlias = 'noop-personal'
)

$ErrorActionPreference = 'Stop'

$androidRoot = $PSScriptRoot
$keystorePath = Join-Path $androidRoot 'private-release.keystore'
$propertiesPath = Join-Path $androidRoot 'keystore.properties'

if ((Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $propertiesPath)) {
    throw 'Signing files already exist. Nothing was overwritten. Back them up before making changes.'
}

$keytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
if ($null -eq $keytoolCommand) {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += (Join-Path $env:JAVA_HOME 'bin\keytool.exe')
    }
    if ($env:ProgramFiles) {
        $candidates += (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\keytool.exe')
    }
    $keytoolPath = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
} else {
    $keytoolPath = $keytoolCommand.Source
}

$opensslCommand = Get-Command openssl.exe -ErrorAction SilentlyContinue
if ($null -ne $opensslCommand) {
    $opensslPath = $opensslCommand.Source
} else {
    $gitOpenSsl = Join-Path $env:ProgramFiles 'Git\usr\bin\openssl.exe'
    if (Test-Path -LiteralPath $gitOpenSsl) {
        $opensslPath = $gitOpenSsl
    }
}

if (-not $keytoolPath -and -not $opensslPath) {
    throw 'Neither keytool.exe nor openssl.exe was found. Install JDK 17, Android Studio, or Git for Windows.'
}

$randomBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rng.GetBytes($randomBytes)
} finally {
    $rng.Dispose()
}
$password = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')

if ($keytoolPath) {
    & $keytoolPath `
        -genkeypair `
        -noprompt `
        -keystore $keystorePath `
        -storetype PKCS12 `
        -storepass $password `
        -keypass $password `
        -alias $KeyAlias `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -dname 'CN=NOOP Personal, OU=Personal, O=NOOP, L=Local, ST=Local, C=AU'

    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE."
    }
} else {
    # Git for Windows includes OpenSSL, which can create the same PKCS#12 keystore without a JDK.
    $tempSigningDir = Join-Path ([System.IO.Path]::GetTempPath()) ("noop-signing-" + [Guid]::NewGuid())
    $privatePem = Join-Path $tempSigningDir 'private.pem'
    $certificatePem = Join-Path $tempSigningDir 'certificate.pem'
    New-Item -ItemType Directory -Path $tempSigningDir | Out-Null
    [Environment]::SetEnvironmentVariable('NOOP_SIGNING_TEMP_PASSWORD', $password, 'Process')
    try {
        & $opensslPath req -x509 -newkey rsa:4096 -sha256 -days 10000 -nodes `
            -keyout $privatePem -out $certificatePem `
            -subj '/CN=NOOP Personal/OU=Personal/O=NOOP/L=Local/ST=Local/C=AU'
        if ($LASTEXITCODE -ne 0) {
            throw "openssl certificate generation failed with exit code $LASTEXITCODE."
        }

        & $opensslPath pkcs12 -export -out $keystorePath -inkey $privatePem -in $certificatePem `
            -name $KeyAlias -passout env:NOOP_SIGNING_TEMP_PASSWORD
        if ($LASTEXITCODE -ne 0) {
            throw "openssl keystore generation failed with exit code $LASTEXITCODE."
        }
    } finally {
        [Environment]::SetEnvironmentVariable('NOOP_SIGNING_TEMP_PASSWORD', $null, 'Process')
        Remove-Item -LiteralPath $privatePem, $certificatePem -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $tempSigningDir -Force -ErrorAction SilentlyContinue
    }
}

@(
    'storeFile=private-release.keystore'
    "storePassword=$password"
    "keyAlias=$KeyAlias"
    "keyPassword=$password"
) | Set-Content -LiteralPath $propertiesPath -Encoding ASCII

Write-Host ''
Write-Host 'Private Android signing is ready.' -ForegroundColor Green
Write-Host 'Back up BOTH of these files somewhere private:'
Write-Host "  $keystorePath"
Write-Host "  $propertiesPath"
Write-Host 'Losing them means future APK updates cannot be installed over the existing app.'
