#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod embed;
mod generator;
mod parser;
mod pinger;
mod runner;
mod sysproxy;
mod theme;
mod types;

use app::VpnApp;
use eframe::egui::Vec2;

fn main() -> eframe::Result<()> {
    // Automatically elevate to Administrator so TUN mode works without manual run_admin.bat
    if !runner::is_admin() {
        runner::restart_as_admin();
        return Ok(());
    }

    // Ensure embedded sing-box and wintun driver are extracted
    let _ = embed::ensure_binaries_extracted();

    // Ensure proxy is turned off on unexpected crash
    let default_panic = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let _ = sysproxy::disable_system_proxy();
        default_panic(info);
    }));

    let options = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_title("EROID VPN")
            .with_inner_size(Vec2::new(440.0, 640.0))
            .with_min_inner_size(Vec2::new(400.0, 560.0)),
        ..Default::default()
    };

    eframe::run_native(
        "EROID VPN",
        options,
        Box::new(|cc| Ok(Box::new(VpnApp::new(cc)))),
    )
}
