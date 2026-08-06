@echo off
setlocal enabledelayedexpansion

title N-Up Print - USB Debug Update Script

echo ========================================================
echo        N-Up Print - USB Debug App Update Script
echo ========================================================
echo.

rem 1. Locate ADB
set "ADB_PATH="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB_PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
) else if exist "C:\Users\kolar\AppData\Local\Android\Sdk\platform-tools\adb.exe" (
    set "ADB_PATH=C:\Users\kolar\AppData\Local\Android\Sdk\platform-tools\adb.exe"
) else (
    where adb >nul 2>nul
    if !errorlevel! equ 0 (
        set "ADB_PATH=adb"
    )
)

if "%ADB_PATH%"=="" (
    echo [ERROR] ADB not found! Please make sure Android SDK platform-tools are installed.
    echo.
    timeout /t 5
    exit /b 1
)

echo [1/4] Found ADB: "%ADB_PATH%"

rem 2. Check for connected USB debugging devices
echo [2/4] Checking connected USB devices...
"%ADB_PATH%" devices | findstr /v "List of devices attached" | findstr "device" >nul
if !errorlevel! neq 0 (
    echo.
    echo [ERROR] No Android device detected over USB!
    echo Please ensure:
    echo  1. USB Debugging is enabled on your Android device.
    echo  2. Device is connected via USB cable.
    echo  3. You accepted the "Allow USB Debugging" prompt on your device screen.
    echo.
    timeout /t 5
    exit /b 1
)

echo       Android device connected successfully!

rem 3. Build APK with Gradle
echo.
echo [3/4] Building latest APK with Gradle...
if exist "C:\Users\kolar\.jdks\jbr-17.0.14" (
    set "JAVA_HOME=C:\Users\kolar\.jdks\jbr-17.0.14"
)

set "GRADLE_BIN="
if exist "C:\Users\kolar\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" (
    set "GRADLE_BIN=C:\Users\kolar\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat"
) else if exist "gradlew.bat" (
    set "GRADLE_BIN=gradlew.bat"
) else (
    set "GRADLE_BIN=gradle"
)

call "%GRADLE_BIN%" assembleDebug
if !errorlevel! neq 0 (
    echo.
    echo [ERROR] Gradle build failed!
    echo.
    timeout /t 5
    exit /b 1
)

rem 4. Install APK onto connected device via ADB
echo.
echo [4/4] Installing updated APK via USB ADB...
"%ADB_PATH%" install -r app\build\outputs\apk\debug\app-debug.apk
if !errorlevel! neq 0 (
    echo.
    echo [ERROR] Installation failed!
    echo.
    timeout /t 5
    exit /b 1
)

rem 5. Launch App
echo.
echo Launching N-Up Print on device...
"%ADB_PATH%" shell am start -n com.example.twoupprint/.MainActivity >nul 2>&1

echo.
echo ========================================================
echo   SUCCESS: N-Up Print successfully updated on device!
echo ========================================================
echo.
echo Auto-closing in 3 seconds...
timeout /t 3 >nul
exit /b 0
