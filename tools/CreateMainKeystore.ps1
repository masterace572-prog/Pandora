param(
    [string]$Keytool = "keytool"
)

$Repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$SigningDirectory = Join-Path $Repository "signing"
$Keystore = Join-Path $SigningDirectory "pandora-main.jks"
$Properties = Join-Path $SigningDirectory "main-keystore.properties"

if ((Test-Path -LiteralPath $Keystore) -or (Test-Path -LiteralPath $Properties)) {
    throw "The local signing identity already exists"
}

New-Item -ItemType Directory -Force -Path $SigningDirectory | Out-Null
$PasswordBytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Fill($PasswordBytes)
$Password = [Convert]::ToHexString($PasswordBytes)

& $Keytool -genkeypair -keystore $Keystore -storetype JKS `
    -storepass $Password -keypass $Password -alias pandora_main `
    -keyalg RSA -keysize 3072 -validity 3650 `
    -dname "CN=Pandora Local, OU=Android, O=Pandora"
if ($LASTEXITCODE -ne 0) {
    throw "Key generation failed"
}

@(
    "storeFile=signing/pandora-main.jks"
    "storePassword=$Password"
    "keyAlias=pandora_main"
    "keyPassword=$Password"
) | Set-Content -LiteralPath $Properties -Encoding ascii

Write-Output "Created signing/pandora-main.jks and signing/main-keystore.properties"
