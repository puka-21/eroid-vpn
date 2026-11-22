@echo off
cd /d "%~dp0"
echo Building EROID VPN for Windows (Release)...
cargo build --release
echo Build finished! Binary is at target\release\eroid-vpn.exe
pause
