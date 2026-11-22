@echo off
cd /d "%~dp0"
echo Building EROID VPN Android Debug APK...
call gradlew.bat assembleDebug
echo Build finished! APK is at app\build\outputs\apk\debug\app-debug.apk
pause
