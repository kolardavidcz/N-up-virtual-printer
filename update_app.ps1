# N-Up Print - PowerShell USB Debug Update Script
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "       N-Up Print - USB Debug App Update Script" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Locate ADB
$AdbPath = ""
$PossiblePaths = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "C:\Users\kolar\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)

foreach ($path in $PossiblePaths) {
    if (Test-Path $path) {
        $AdbPath = $path
        break
    }
}

if (-not $AdbPath) {
    $CommandAdb = Get-Command adb -ErrorAction SilentlyContinue
    if ($CommandAdb) {
        $AdbPath = "adb"
    }
}

if (-not $AdbPath) {
    Write-Host "[ERROR] ADB not found! Please check Android SDK platform-tools." -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "[1/4] Found ADB: $AdbPath" -ForegroundColor Green

# 2. Check connected devices
Write-Host "[2/4] Checking connected USB devices..." -ForegroundColor Yellow
$Devices = & $AdbPath devices
$ConnectedDevices = $Devices | Select-String -Pattern "\tdevice$"

if (-not $ConnectedDevices) {
    Write-Host ""
    Write-Host "[ERROR] No Android device detected over USB!" -ForegroundColor Red
    Write-Host "Please ensure:" -ForegroundColor Yellow
    Write-Host " 1. USB Debugging is enabled on your Android device."
    Write-Host " 2. Device is connected via USB cable."
    Write-Host " 3. You accepted the 'Allow USB Debugging' prompt on device screen."
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "      Android device connected successfully!" -ForegroundColor Green

# 3. Set Java and Build
Write-Host ""
Write-Host "[3/4] Building latest APK with Gradle..." -ForegroundColor Yellow

if (Test-Path "C:\Users\kolar\.jdks\jbr-17.0.14") {
    $env:JAVA_HOME = "C:\Users\kolar\.jdks\jbr-17.0.14"
}

$GradleBin = "gradle"
if (Test-Path "C:\Users\kolar\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat") {
    $GradleBin = "C:\Users\kolar\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat"
} elseif (Test-Path ".\gradlew.bat") {
    $GradleBin = ".\gradlew.bat"
}

$BuildResult = & $GradleBin assembleDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "[ERROR] Gradle build failed!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# 4. Install APK
Write-Host ""
Write-Host "[4/4] Installing updated APK via USB ADB..." -ForegroundColor Yellow
& $AdbPath install -r app\build\outputs\apk\debug\app-debug.apk

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "[ERROR] Installation failed!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# 5. Launch App
Write-Host ""
Write-Host "Launching N-Up Print on device..." -ForegroundColor Green
& $AdbPath shell am start -n com.example.twoupprint/.MainActivity | Out-Null

Write-Host ""
Write-Host "========================================================" -ForegroundColor Green
Write-Host "  SUCCESS: N-Up Print successfully updated on device!" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
Write-Host ""
Read-Host "Press Enter to exit"
