$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
if (-not $env:JAVA_HOME) { $Jbr = "$env:ProgramFiles\Android\Android Studio\jbr"; if (Test-Path "$Jbr\bin\java.exe") { $env:JAVA_HOME=$Jbr; $env:Path="$Jbr\bin;$env:Path" } }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Install JDK 17 or select Android Studio JBR 17/21.' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path $env:ANDROID_HOME)) { throw 'Install Android SDK 35 in Android Studio SDK Manager, then rerun.' }
$Cache = "$env:LOCALAPPDATA\PailaBuild"
New-Item -ItemType Directory -Force $Cache | Out-Null
$Gradle = "$Cache\gradle-8.9\bin\gradle.bat"
if (-not (Test-Path $Gradle)) {
    Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile "$Cache\gradle.zip"
    $Expected = (Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256').Content.Trim().ToLower()
    $Actual = (Get-FileHash "$Cache\gradle.zip" -Algorithm SHA256).Hash.ToLower()
    if ($Expected -ne $Actual) { throw 'Gradle checksum mismatch' }
    Expand-Archive "$Cache\gradle.zip" -DestinationPath $Cache -Force
}
& $Gradle -p "$Root\android" --no-daemon ':app:testDebugUnitTest' ':app:lintDebug' ':app:assembleDebug'
if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit $LASTEXITCODE. Read and fix the log." }
Write-Host "APK: $Root\android\app\build\outputs\apk\debug\app-debug.apk"
