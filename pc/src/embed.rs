use std::io::Read;
use std::path::{Path, PathBuf};

const SING_BOX_GZ: &[u8] = include_bytes!("../bin/sing-box.exe.gz");
const WINTUN_GZ: &[u8] = include_bytes!("../bin/wintun.dll.gz");
const LIB_CRONET_GZ: &[u8] = include_bytes!("../bin/libcronet.dll.gz");

pub fn get_app_dir() -> PathBuf {
    if let Ok(local_app_data) = std::env::var("LOCALAPPDATA") {
        let dir = PathBuf::from(local_app_data).join("EROID VPN");
        let _ = std::fs::create_dir_all(&dir);
        dir
    } else {
        let dir = std::env::temp_dir().join("eroid_vpn");
        let _ = std::fs::create_dir_all(&dir);
        dir
    }
}

pub fn get_app_bin_dir() -> PathBuf {
    let dir = get_app_dir().join("bin");
    let _ = std::fs::create_dir_all(&dir);
    dir
}

pub fn ensure_binaries_extracted() -> Result<PathBuf, String> {
    let bin_dir = get_app_bin_dir();

    extract_gz(SING_BOX_GZ, &bin_dir.join("sing-box.exe"))?;
    extract_gz(WINTUN_GZ, &bin_dir.join("wintun.dll"))?;
    extract_gz(LIB_CRONET_GZ, &bin_dir.join("libcronet.dll"))?;

    Ok(bin_dir)
}

fn extract_gz(data: &[u8], target: &Path) -> Result<(), String> {
    if target.exists() && target.metadata().map(|m| m.len()).unwrap_or(0) > 1000 {
        return Ok(());
    }
    let mut decoder = flate2::read::GzDecoder::new(data);
    let mut decompressed = Vec::new();
    decoder
        .read_to_end(&mut decompressed)
        .map_err(|e| format!("Decompress error: {e}"))?;
    std::fs::write(target, decompressed)
        .map_err(|e| format!("Failed to write {}: {e}", target.display()))?;
    Ok(())
}
