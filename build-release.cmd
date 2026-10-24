@echo off
rem Builds the signed release APK. Passwords are typed here (hidden) and only live in this window.
cd /d "%~dp0"
set "JAVA_HOME=%USERPROFILE%\.jdks\jdk-17.0.20.1+1"
powershell -NoProfile -Command "$a = Read-Host 'Keystore password' -AsSecureString; $b = Read-Host 'Key password (Enter if the same)' -AsSecureString; $m = [Runtime.InteropServices.Marshal]; $p = $m::PtrToStringAuto($m::SecureStringToBSTR($a)); $q = $m::PtrToStringAuto($m::SecureStringToBSTR($b)); if (-not $q) { $q = $p }; $env:SC_KS_PASS = $p; $env:SC_KEY_PASS = $q; & .\gradlew.bat assembleRelease --no-daemon --console=plain; exit $LASTEXITCODE"
if errorlevel 1 (echo BUILD FAILED & exit /b 1)
if not exist "app\build\outputs\apk\release\app-release.apk" (echo BUILD FAILED: no APK was produced & exit /b 1)
copy /y "app\build\outputs\apk\release\app-release.apk" "SportClock-native.apk" >nul
echo Built SportClock-native.apk
