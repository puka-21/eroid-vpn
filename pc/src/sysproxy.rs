use winreg::enums::*;
use winreg::RegKey;

const INTERNET_OPTION_SETTINGS_CHANGED: u32 = 39;
const INTERNET_OPTION_REFRESH: u32 = 37;

#[link(name = "wininet")]
extern "system" {
    fn InternetSetOptionW(
        h_internet: *mut std::ffi::c_void,
        dw_option: u32,
        lp_buffer: *mut std::ffi::c_void,
        dw_buffer_length: u32,
    ) -> i32;
}

pub fn refresh_system_proxy() {
    unsafe {
        InternetSetOptionW(std::ptr::null_mut(), INTERNET_OPTION_SETTINGS_CHANGED, std::ptr::null_mut(), 0);
        InternetSetOptionW(std::ptr::null_mut(), INTERNET_OPTION_REFRESH, std::ptr::null_mut(), 0);
    }
}

pub fn enable_system_proxy(port: u16) -> Result<(), String> {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let (key, _) = hkcu
        .create_subkey(r"Software\Microsoft\Windows\CurrentVersion\Internet Settings")
        .map_err(|e| e.to_string())?;

    let proxy_str = format!("http=127.0.0.1:{port};https=127.0.0.1:{port};socks=127.0.0.1:{port}");
    let bypass_str = "localhost;127.*;10.*;172.16.*;192.168.*;*.local;<local>";

    key.set_value("ProxyEnable", &1u32).map_err(|e| e.to_string())?;
    key.set_value("ProxyServer", &proxy_str).map_err(|e| e.to_string())?;
    key.set_value("ProxyOverride", &bypass_str).map_err(|e| e.to_string())?;

    refresh_system_proxy();
    Ok(())
}

pub fn disable_system_proxy() -> Result<(), String> {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let (key, _) = hkcu
        .create_subkey(r"Software\Microsoft\Windows\CurrentVersion\Internet Settings")
        .map_err(|e| e.to_string())?;

    key.set_value("ProxyEnable", &0u32).map_err(|e| e.to_string())?;

    refresh_system_proxy();
    Ok(())
}
