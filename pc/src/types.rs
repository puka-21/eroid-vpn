use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum NodeType {
    Outbound,
    Endpoint,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Node {
    pub name: String,
    pub node_type: NodeType,
    pub protocol: String,
    pub config: serde_json::Value,
    pub address: String,
    pub port: u16,
    pub ping: Option<i64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum RunMode {
    Tun,
    Proxy,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum AppTheme {
    Nordic,
    Void,
    Cyberpunk,
    Emerald,
    TokyoNight,
}

impl Default for AppTheme {
    fn default() -> Self {
        AppTheme::Nordic
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CoreConfig {
    pub log_level: String,
    pub stack: String,
    pub mtu: u32,
    pub strict_route: bool,
    pub auto_route: bool,
    pub utls_fingerprint: String,
    pub enable_geosite_ru: bool,
    pub enable_geoip_ru: bool,
    pub enable_adblock: bool,
    pub custom_bypass_domains: String,
}

impl Default for CoreConfig {
    fn default() -> Self {
        Self {
            log_level: "info".to_string(),
            stack: "mixed".to_string(),
            mtu: 9000,
            strict_route: true,
            auto_route: true,
            utls_fingerprint: "chrome".to_string(),
            enable_geosite_ru: true,
            enable_geoip_ru: true,
            enable_adblock: true,
            custom_bypass_domains: "gosuslugi.ru, sberbank.ru, tbank.ru, yandex.ru, vk.com, avito.ru, ozon.ru".to_string(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppSettings {
    pub sub_url: String,
    pub mode: RunMode,
    pub dns: String,
    pub port: u16,
    pub selected_idx: Option<usize>,
    pub hwid: String,
    pub theme: AppTheme,
    pub core_config: CoreConfig,
    pub nodes: Vec<Node>,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            sub_url: String::new(),
            mode: RunMode::Tun,
            dns: "8.8.8.8".to_string(),
            port: 2080,
            selected_idx: None,
            hwid: get_or_create_default_hwid(),
            theme: AppTheme::Nordic,
            core_config: CoreConfig::default(),
            nodes: Vec::new(),
        }
    }
}

pub fn get_or_create_default_hwid() -> String {
    use winreg::enums::HKEY_LOCAL_MACHINE;
    use winreg::RegKey;

    let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
    if let Ok(crypto) = hklm.open_subkey(r"SOFTWARE\Microsoft\Cryptography") {
        if let Ok(guid) = crypto.get_value::<String, _>("MachineGuid") {
            let clean = guid.trim().to_string();
            if !clean.is_empty() {
                return clean;
            }
        }
    }
    "a1b2c3d4-e5f6-47b8-bc23-c90ad7a17726".to_string()
}
