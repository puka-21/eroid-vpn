use std::path::PathBuf;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::thread;

use eframe::egui::{
    self, Color32, FontData, FontDefinitions, FontFamily, Frame, Layout, Margin, RichText,
    Rounding, Stroke, Vec2,
};
use crate::generator::generate_singbox_config;
use crate::parser::{fetch_and_parse_subscription, SubscriptionResult};
use crate::pinger::test_all_nodes;
use crate::runner::{is_admin, restart_as_admin, SingBoxRunner};
use crate::theme::{get_theme_colors, ThemeColors};
use crate::types::{AppSettings, AppTheme, RunMode};

#[derive(PartialEq, Eq, Clone, Copy)]
enum ViewScreen {
    Main,
    Servers,
    Protection,
    Settings,
}

pub struct VpnApp {
    settings: AppSettings,
    settings_path: PathBuf,
    runner: SingBoxRunner,

    log_lines: Vec<String>,
    log_tx: Sender<String>,
    log_rx: Receiver<String>,

    ping_tx: Sender<(usize, Option<i64>)>,
    ping_rx: Receiver<(usize, Option<i64>)>,

    sub_tx: Sender<Result<SubscriptionResult, String>>,
    sub_rx: Receiver<Result<SubscriptionResult, String>>,

    current_screen: ViewScreen,
    search_query: String,
    is_loading_sub: bool,
    is_pinging: bool,
    status_text: String,
    announce_text: Option<String>,
    error_banner: Option<String>,
    syntax_check_result: Option<(bool, String)>,
}

impl VpnApp {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        // Setup native Segoe UI typography
        let mut fonts = FontDefinitions::default();
        if let Ok(font_data) = std::fs::read("C:\\Windows\\Fonts\\segoeui.ttf") {
            fonts.font_data.insert("segoeui".to_owned(), FontData::from_owned(font_data));
            fonts
                .families
                .entry(FontFamily::Proportional)
                .or_default()
                .insert(0, "segoeui".to_owned());
        }
        cc.egui_ctx.set_fonts(fonts);

        let app_dir = crate::embed::get_app_dir();
        let exe_dir = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.to_path_buf()));
        let settings_path = if let Some(ref d) = exe_dir {
            let local = d.join("settings.json");
            if local.exists() {
                local
            } else {
                app_dir.join("settings.json")
            }
        } else {
            app_dir.join("settings.json")
        };

        let settings = if settings_path.exists() {
            std::fs::read_to_string(&settings_path)
                .ok()
                .and_then(|s| serde_json::from_str(&s).ok())
                .unwrap_or_default()
        } else {
            AppSettings::default()
        };

        // Apply theme to Context
        apply_theme_to_ctx(&cc.egui_ctx, settings.theme);

        let (log_tx, log_rx) = channel();
        let (ping_tx, ping_rx) = channel();
        let (sub_tx, sub_rx) = channel();

        let mut app = Self {
            settings,
            settings_path,
            runner: SingBoxRunner::new(app_dir),
            log_lines: Vec::new(),
            log_tx,
            log_rx,
            ping_tx,
            ping_rx,
            sub_tx,
            sub_rx,
            current_screen: ViewScreen::Main,
            search_query: String::new(),
            is_loading_sub: false,
            is_pinging: false,
            status_text: "Отключено".to_string(),
            announce_text: None,
            error_banner: None,
            syntax_check_result: None,
        };

        if !app.settings.sub_url.is_empty() && app.settings.nodes.is_empty() {
            app.load_subscription();
        }

        app
    }

    fn colors(&self) -> ThemeColors {
        get_theme_colors(self.settings.theme)
    }

    fn save_settings(&self) {
        if let Ok(data) = serde_json::to_string_pretty(&self.settings) {
            let _ = std::fs::write(&self.settings_path, data);
        }
    }

    fn update_channels(&mut self) {
        while let Ok(line) = self.log_rx.try_recv() {
            if self.log_lines.len() > 1000 {
                self.log_lines.remove(0);
            }
            self.log_lines.push(line);
        }

        while let Ok((idx, lat)) = self.ping_rx.try_recv() {
            if idx < self.settings.nodes.len() {
                self.settings.nodes[idx].ping = lat;
            }
        }

        while let Ok(res) = self.sub_rx.try_recv() {
            self.is_loading_sub = false;
            match res {
                Ok(sub_res) => {
                    self.error_banner = None;
                    self.announce_text = sub_res.announce;
                    self.settings.nodes = sub_res.nodes;
                    if self.settings.selected_idx.is_none() && !self.settings.nodes.is_empty() {
                        self.settings.selected_idx = Some(0);
                    }
                    self.save_settings();
                    self.start_ping_all();
                }
                Err(err) => {
                    self.error_banner = Some(err);
                }
            }
        }
    }

    fn start_ping_all(&mut self) {
        if self.settings.nodes.is_empty() {
            return;
        }
        self.is_pinging = true;
        let nodes = self.settings.nodes.clone();
        let tx = self.ping_tx.clone();
        test_all_nodes(nodes, tx);
    }

    fn load_subscription(&mut self) {
        self.save_settings();
        let url = self.settings.sub_url.trim().to_string();
        if url.is_empty() {
            return;
        }
        self.is_loading_sub = true;
        self.error_banner = None;
        let hwid = self.settings.hwid.trim().to_string();
        let tx = self.sub_tx.clone();
        thread::spawn(move || {
            let res = fetch_and_parse_subscription(&url, &hwid);
            let _ = tx.send(res);
        });
    }

    fn current_generated_config(&self) -> Option<serde_json::Value> {
        let idx = self.settings.selected_idx.unwrap_or(0);
        let node = self.settings.nodes.get(idx)?;
        Some(generate_singbox_config(
            node,
            self.settings.mode,
            self.settings.port,
            &self.settings.dns,
            &self.settings.core_config,
        ))
    }

    fn check_active_config_syntax(&mut self) {
        if let Some(cfg) = self.current_generated_config() {
            let base_dir = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
            let bin = base_dir.join("bin").join("sing-box.exe");
            let temp_path = base_dir.join("check_tmp.json");

            if let Ok(s) = serde_json::to_string_pretty(&cfg) {
                let _ = std::fs::write(&temp_path, s);
                let mut cmd = std::process::Command::new(bin);
                cmd.args(["check", "-c"]).arg(&temp_path);
                
                #[cfg(windows)]
                {
                    use std::os::windows::process::CommandExt;
                    cmd.creation_flags(0x08000000);
                }

                match cmd.output() {
                    Ok(out) => {
                        let _ = std::fs::remove_file(temp_path);
                        if out.status.success() {
                            self.syntax_check_result = Some((true, "Конфиг ядра sing-box 1.14 полностью валиден!".to_string()));
                        } else {
                            let err = String::from_utf8_lossy(&out.stderr).to_string();
                            self.syntax_check_result = Some((false, format!("Ошибка проверки: {err}")));
                        }
                    }
                    Err(e) => {
                        let _ = std::fs::remove_file(temp_path);
                        self.syntax_check_result = Some((false, format!("Не удалось вызвать sing-box: {e}")));
                    }
                }
            }
        } else {
            self.syntax_check_result = Some((false, "Нет выбранного сервера для генерации конфига".to_string()));
        }
    }

    fn toggle_connection(&mut self) {
        if self.runner.is_running() {
            self.runner.stop();
            self.status_text = "Отключено".to_string();
            let _ = self.log_tx.send("[INFO] VPN отключен".to_string());
        } else {
            if self.settings.nodes.is_empty() {
                self.current_screen = ViewScreen::Settings;
                return;
            }
            let idx = self.settings.selected_idx.unwrap_or(0);
            if idx >= self.settings.nodes.len() {
                return;
            }
            let node = &self.settings.nodes[idx];
            let cfg = generate_singbox_config(
                node,
                self.settings.mode,
                self.settings.port,
                &self.settings.dns,
                &self.settings.core_config,
            );

            let node_name = node.name.clone();
            let mode = self.settings.mode;
            let port = self.settings.port;
            let tx = self.log_tx.clone();

            match self.runner.start(&cfg, mode, port, tx) {
                Ok(_) => {
                    self.status_text = format!("Подключено: {node_name}");
                }
                Err(e) => {
                    self.error_banner = Some(format!("Ошибка запуска: {e}"));
                }
            }
        }
    }

    // Monolithic Floating Navigation Bar
    fn render_unibody_header(&mut self, ui: &mut egui::Ui, c: &ThemeColors) {
        ui.horizontal(|ui| {
            // EROID VPN Brand
            ui.horizontal(|ui| {
                ui.label(RichText::new("EROID").size(17.0).strong().color(c.accent));
                ui.label(RichText::new("VPN").size(17.0).strong().color(c.text_primary));
            });

            ui.with_layout(Layout::right_to_left(egui::Align::Center), |ui| {
                // Mode Pill Toggle
                let (mode_label, mode_tooltip) = match self.settings.mode {
                    RunMode::Tun => ("TUN (VPN)", "Режим TUN: глобальный туннель для всех игр, Telegram, Discord и браузеров"),
                    RunMode::Proxy => ("PROXY", "Режим System Proxy: системный прокси для браузеров"),
                };

                let mode_btn = egui::Button::new(RichText::new(mode_label).size(11.0).strong().color(c.accent))
                    .fill(c.surface)
                    .stroke(Stroke::new(1.0_f32, c.accent))
                    .rounding(Rounding::same(12.0));

                if ui.add(mode_btn).on_hover_text(mode_tooltip).clicked() {
                    self.settings.mode = match self.settings.mode {
                        RunMode::Tun => RunMode::Proxy,
                        RunMode::Proxy => RunMode::Tun,
                    };
                    self.save_settings();
                }
            });
        });

        ui.add_space(8.0);

        // Seamless Monolithic Nav Capsule
        Frame::none()
            .fill(c.surface)
            .rounding(Rounding::same(20.0))
            .inner_margin(Margin::symmetric(4.0, 4.0))
            .show(ui, |ui| {
                ui.horizontal(|ui| {
                    let nav_items = [
                        (ViewScreen::Main, "Подключение"),
                        (ViewScreen::Servers, "Серверы"),
                        (ViewScreen::Protection, "Защита & Geo"),
                        (ViewScreen::Settings, "Опции"),
                    ];

                    for (scr, label) in nav_items {
                        let is_active = self.current_screen == scr;
                        let bg = if is_active { c.accent } else { Color32::TRANSPARENT };
                        let txt_color = if is_active { Color32::from_rgb(10, 14, 23) } else { c.text_secondary };

                        let tab_btn = egui::Button::new(RichText::new(label).size(12.0).strong().color(txt_color))
                            .fill(bg)
                            .stroke(Stroke::NONE)
                            .rounding(Rounding::same(16.0))
                            .min_size(Vec2::new(92.0, 28.0));

                        if ui.add(tab_btn).clicked() {
                            self.current_screen = scr;
                        }
                    }
                });
            });
    }

    // Hero Screen: Monolithic Dashboard
    fn render_main_screen(&mut self, ui: &mut egui::Ui, c: &ThemeColors, is_running: bool) {
        ui.add_space(16.0);

        // Status Line
        ui.vertical_centered(|ui| {
            if is_running {
                ui.label(RichText::new("● ПОДКЛЮЧЕНО И ЗАЩИЩЕНО").size(13.0).strong().color(c.success));
            } else {
                ui.label(RichText::new("○ СОЕДИНЕНИЕ ОТКЛЮЧЕНО").size(13.0).strong().color(c.text_muted));
            }

            if let Some(ref ann) = self.announce_text {
                ui.add_space(4.0);
                Frame::none()
                    .fill(c.surface)
                    .rounding(Rounding::same(12.0))
                    .inner_margin(Margin::symmetric(10.0, 4.0))
                    .show(ui, |ui| {
                        ui.label(RichText::new(ann).size(11.0).color(c.accent));
                    });
            }
        });

        ui.add_space(20.0);

        // Massive Monolithic Power Connect Button
        ui.vertical_centered(|ui| {
            let (btn_text, bg_color, stroke_color, text_color) = if is_running {
                ("ОТКЛЮЧИТЬ", c.danger, c.danger, Color32::WHITE)
            } else {
                ("ПОДКЛЮЧИТЬ", c.surface_elevated, c.accent, Color32::WHITE)
            };

            let connect_btn = egui::Button::new(
                RichText::new(btn_text).size(17.0).strong().color(text_color)
            )
            .fill(bg_color)
            .stroke(Stroke::new(2.0_f32, stroke_color))
            .min_size(Vec2::new(230.0, 68.0))
            .rounding(Rounding::same(34.0));

            if ui.add(connect_btn).clicked() {
                self.toggle_connection();
            }
        });

        ui.add_space(28.0);

        // Selected Server Monolithic Capsule
        let selected_node = self.settings.selected_idx.and_then(|i| self.settings.nodes.get(i));
        
        Frame::none()
            .fill(c.surface)
            .rounding(Rounding::same(16.0))
            .inner_margin(Margin::same(16.0))
            .show(ui, |ui| {
                ui.horizontal(|ui| {
                    ui.vertical(|ui| {
                        ui.label(RichText::new("ТЕКУЩАЯ ЛОКАЦИЯ").size(10.0).strong().color(c.text_muted));
                        ui.add_space(2.0);

                        if let Some(node) = selected_node {
                            ui.horizontal(|ui| {
                                let badge_color = match node.protocol.as_str() {
                                    "VLESS-Reality" => c.accent,
                                    "WireGuard" => Color32::from_rgb(168, 85, 247),
                                    "AmneziaWG" => Color32::from_rgb(249, 115, 22),
                                    _ => c.success,
                                };
                                ui.label(RichText::new(&node.protocol).size(11.0).strong().color(badge_color));
                                ui.label(RichText::new(&node.name).size(14.0).strong().color(c.text_primary));
                            });

                            if let Some(ping) = node.ping {
                                let ping_color = if ping < 120 { c.success } else { c.warning };
                                ui.label(RichText::new(format!("Задержка: {ping} ms")).size(11.0).color(ping_color));
                            }
                        } else {
                            ui.label(RichText::new("Локация не выбрана (добавьте подписку)").size(13.0).color(c.text_muted));
                        }
                    });

                    ui.with_layout(Layout::right_to_left(egui::Align::Center), |ui| {
                        let change_btn = egui::Button::new(RichText::new("Сменить ›").size(13.0).strong().color(c.accent))
                            .fill(c.surface_elevated)
                            .stroke(Stroke::NONE)
                            .rounding(Rounding::same(10.0))
                            .min_size(Vec2::new(80.0, 32.0));

                        if ui.add(change_btn).clicked() {
                            self.current_screen = ViewScreen::Servers;
                        }
                    });
                });
            });

        ui.add_space(14.0);

        // Monolithic Geo & Protection Summary Pill
        Frame::none()
            .fill(c.surface)
            .rounding(Rounding::same(14.0))
            .inner_margin(Margin::same(14.0))
            .show(ui, |ui| {
                ui.label(RichText::new("АКТИВНЫЕ ПРАВИЛА").size(10.0).strong().color(c.text_muted));
                ui.add_space(4.0);

                ui.horizontal_wrapped(|ui| {
                    if self.settings.core_config.enable_geosite_ru {
                        ui.label(RichText::new("✓ Сайты РФ напрямую").size(11.0).color(c.success));
                    }
                    if self.settings.core_config.enable_adblock {
                        ui.label(RichText::new("✓ AdBlock активен").size(11.0).color(c.accent));
                    }
                    if self.settings.core_config.enable_geoip_ru {
                        ui.label(RichText::new("✓ GeoIP RU").size(11.0).color(c.text_secondary));
                    }
                });
            });

        ui.add_space(10.0);

        // Admin alert if running TUN without Admin
        if !is_admin() && self.settings.mode == RunMode::Tun {
            Frame::none()
                .fill(Color32::from_rgb(32, 22, 10))
                .rounding(Rounding::same(12.0))
                .inner_margin(Margin::same(12.0))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Для TUN режима требуются права Администратора.").size(11.0).color(c.warning));
                        ui.with_layout(Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button(RichText::new("Перезапустить").size(11.0).strong()).clicked() {
                                restart_as_admin();
                            }
                        });
                    });
                });
        }
    }

    // Screen 2: Servers List (Unibody)
    fn render_servers_screen(&mut self, ui: &mut egui::Ui, c: &ThemeColors) {
        ui.horizontal(|ui| {
            ui.label(RichText::new(format!("Локации ({})", self.settings.nodes.len())).size(15.0).strong().color(c.text_primary));

            ui.with_layout(Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.button(RichText::new("⚡ Замерить пинг").size(12.0)).clicked() {
                    self.start_ping_all();
                }
                let reload_text = if self.is_loading_sub { "Загрузка..." } else { "🔄 Обновить" };
                if ui.add_enabled(!self.is_loading_sub, egui::Button::new(RichText::new(reload_text).size(12.0))).clicked() {
                    self.load_subscription();
                }
            });
        });

        ui.add_space(8.0);

        // Integrated Search Bar
        Frame::none()
            .fill(c.surface)
            .rounding(Rounding::same(12.0))
            .inner_margin(Margin::symmetric(10.0, 6.0))
            .show(ui, |ui| {
                ui.add(
                    egui::TextEdit::singleline(&mut self.search_query)
                        .hint_text("Поиск по названию, стране, протоколу...")
                        .desired_width(ui.available_width())
                        .frame(false)
                );
            });

        ui.add_space(8.0);

        egui::ScrollArea::vertical().show(ui, |ui| {
            let filter = self.search_query.to_lowercase();
            let mut to_select = None;
            let mut connect_now = false;

            if self.settings.nodes.is_empty() {
                ui.vertical_centered(|ui| {
                    ui.add_space(40.0);
                    ui.label(RichText::new("Список локаций пуст").size(14.0).color(c.text_muted));
                    ui.label(RichText::new("Добавьте ссылку на подписку во вкладке «Опции»").size(12.0).color(c.text_secondary));
                });
            }

            for (idx, node) in self.settings.nodes.iter().enumerate() {
                let match_name = node.name.to_lowercase().contains(&filter);
                let match_proto = node.protocol.to_lowercase().contains(&filter);
                let match_addr = node.address.to_lowercase().contains(&filter);

                if !filter.is_empty() && !match_name && !match_proto && !match_addr {
                    continue;
                }

                let is_selected = self.settings.selected_idx == Some(idx);
                let bg_color = if is_selected { c.surface_elevated } else { c.surface };

                let frame = Frame::none()
                    .fill(bg_color)
                    .stroke(if is_selected { Stroke::new(1.0_f32, c.accent) } else { Stroke::NONE })
                    .rounding(Rounding::same(12.0))
                    .inner_margin(Margin::same(12.0));

                let resp = frame.show(ui, |ui| {
                    ui.horizontal(|ui| {
                        let proto_color = match node.protocol.as_str() {
                            "VLESS-Reality" => c.accent,
                            "WireGuard" => Color32::from_rgb(168, 85, 247),
                            "AmneziaWG" => Color32::from_rgb(249, 115, 22),
                            _ => c.success,
                        };
                        ui.label(RichText::new(&node.protocol).size(11.0).strong().color(proto_color));
                        ui.label(RichText::new(&node.name).size(13.0).strong().color(c.text_primary));

                        ui.with_layout(Layout::right_to_left(egui::Align::Center), |ui| {
                            if let Some(p) = node.ping {
                                let ping_color = if p < 120 { c.success } else { c.warning };
                                ui.label(RichText::new(format!("{p} ms")).size(12.0).strong().color(ping_color));
                            } else {
                                ui.label(RichText::new("—").size(12.0).color(c.text_muted));
                            }
                        });
                    });
                }).response;

                if resp.interact(egui::Sense::click()).clicked() {
                    to_select = Some(idx);
                }
                if resp.interact(egui::Sense::click()).double_clicked() {
                    to_select = Some(idx);
                    connect_now = true;
                }
            }

            if let Some(idx) = to_select {
                self.settings.selected_idx = Some(idx);
                self.save_settings();
                if connect_now {
                    self.current_screen = ViewScreen::Main;
                    self.toggle_connection();
                }
            }
        });
    }

    // Screen 3: Protection, GeoSite, GeoIP, Sing-box Engine (Unibody)
    fn render_protection_screen(&mut self, ui: &mut egui::Ui, c: &ThemeColors) {
        ui.label(RichText::new("Защита, GeoSite & Параметры ядра").size(15.0).strong().color(c.text_primary));
        ui.add_space(8.0);

        egui::ScrollArea::vertical().show(ui, |ui| {
            // GeoSite & GeoIP Section
            Frame::none()
                .fill(c.surface)
                .rounding(Rounding::same(14.0))
                .inner_margin(Margin::same(14.0))
                .show(ui, |ui| {
                    ui.label(RichText::new("GEOSITE & GEOIP МАРШРУТИЗАЦИЯ").size(11.0).strong().color(c.text_muted));
                    ui.add_space(6.0);

                    if ui.checkbox(&mut self.settings.core_config.enable_geosite_ru, "Прямой доступ к сайтам РФ (geosite-ru)").on_hover_text("Госуслуги, банки, Яндекс, VK, маркетплейсы работают напрямую без задержек VPN").changed() {
                        self.save_settings();
                    }

                    if ui.checkbox(&mut self.settings.core_config.enable_geoip_ru, "Обход всех российских IP-адресов (geoip-ru)").changed() {
                        self.save_settings();
                    }

                    if ui.checkbox(&mut self.settings.core_config.enable_adblock, "Аппаратный блокировщик рекламы (geosite-category-ads-all)").on_hover_text("Блокирует рекламные сети и трекеры прямо внутри ядра sing-box").changed() {
                        self.save_settings();
                    }

                    ui.add_space(6.0);
                    ui.label(RichText::new("Белый список доменов (напрямую без VPN, через запятую):").size(11.0).color(c.text_secondary));
                    if ui.add(egui::TextEdit::singleline(&mut self.settings.core_config.custom_bypass_domains).desired_width(ui.available_width())).changed() {
                        self.save_settings();
                    }
                });

            ui.add_space(10.0);

            // Core Tuning Section
            Frame::none()
                .fill(c.surface)
                .rounding(Rounding::same(14.0))
                .inner_margin(Margin::same(14.0))
                .show(ui, |ui| {
                    ui.label(RichText::new("ТОНКАЯ НАСТРОЙКА ЯДРА SING-BOX").size(11.0).strong().color(c.text_muted));
                    ui.add_space(6.0);

                    ui.horizontal(|ui| {
                        ui.label("Стек адаптера:");
                        let old_stack = self.settings.core_config.stack.clone();
                        egui::ComboBox::from_id_source("stack_combo")
                            .selected_text(&self.settings.core_config.stack)
                            .show_ui(ui, |ui| {
                                ui.selectable_value(&mut self.settings.core_config.stack, "mixed".to_string(), "mixed (Рекомендуется)");
                                ui.selectable_value(&mut self.settings.core_config.stack, "gvisor".to_string(), "gvisor");
                                ui.selectable_value(&mut self.settings.core_config.stack, "system".to_string(), "system");
                            });
                        if old_stack != self.settings.core_config.stack {
                            self.save_settings();
                        }

                        ui.add_space(10.0);
                        ui.label("uTLS маскировка:");
                        let old_fp = self.settings.core_config.utls_fingerprint.clone();
                        egui::ComboBox::from_id_source("utls_combo")
                            .selected_text(&self.settings.core_config.utls_fingerprint)
                            .show_ui(ui, |ui| {
                                ui.selectable_value(&mut self.settings.core_config.utls_fingerprint, "chrome".to_string(), "Chrome");
                                ui.selectable_value(&mut self.settings.core_config.utls_fingerprint, "firefox".to_string(), "Firefox");
                                ui.selectable_value(&mut self.settings.core_config.utls_fingerprint, "safari".to_string(), "Safari");
                                ui.selectable_value(&mut self.settings.core_config.utls_fingerprint, "ios".to_string(), "iOS");
                                ui.selectable_value(&mut self.settings.core_config.utls_fingerprint, "random".to_string(), "Random");
                            });
                        if old_fp != self.settings.core_config.utls_fingerprint {
                            self.save_settings();
                        }
                    });

                    ui.add_space(6.0);
                    ui.horizontal(|ui| {
                        if ui.checkbox(&mut self.settings.core_config.strict_route, "Strict Route").changed() {
                            self.save_settings();
                        }
                        if ui.checkbox(&mut self.settings.core_config.auto_route, "Auto Route").changed() {
                            self.save_settings();
                        }
                    });
                });

            ui.add_space(10.0);

            // Raw Config Inspector & Syntax Validator
            Frame::none()
                .fill(c.surface)
                .rounding(Rounding::same(14.0))
                .inner_margin(Margin::same(14.0))
                .show(ui, |ui| {
                    ui.label(RichText::new("ИНСПЕКТОР СИНТАКСИСА КОНФИГА").size(11.0).strong().color(c.text_muted));
                    ui.add_space(6.0);

                    ui.horizontal(|ui| {
                        if ui.button("🔍 Проверить синтаксис конфига").clicked() {
                            self.check_active_config_syntax();
                        }

                        if ui.button("📋 Скопировать JSON").clicked() {
                            if let Some(cfg) = self.current_generated_config() {
                                if let Ok(s) = serde_json::to_string_pretty(&cfg) {
                                    ui.output_mut(|o| o.copied_text = s);
                                }
                            }
                        }
                    });

                    if let Some((valid, ref msg)) = self.syntax_check_result {
                        ui.add_space(6.0);
                        let col = if valid { c.success } else { c.danger };
                        ui.label(RichText::new(msg).size(12.0).strong().color(col));
                    }

                    ui.add_space(6.0);
                    ui.collapsing("Просмотреть генерируемый config.json", |ui| {
                        let json_str = self.current_generated_config()
                            .and_then(|c| serde_json::to_string_pretty(&c).ok())
                            .unwrap_or_else(|| "{}".to_string());
                        
                        egui::ScrollArea::vertical().max_height(180.0).show(ui, |ui| {
                            ui.label(RichText::new(json_str).monospace().size(10.0).color(Color32::from_rgb(167, 243, 208)));
                        });
                    });
                });
        });
    }

    // Screen 4: Options & Settings (Unibody)
    fn render_settings_screen(&mut self, ui: &mut egui::Ui, c: &ThemeColors, ctx: &egui::Context) {
        ui.label(RichText::new("Опции и персонализация").size(15.0).strong().color(c.text_primary));
        ui.add_space(8.0);

        egui::ScrollArea::vertical().show(ui, |ui| {
            // Unibody Theme Selector Card
            Frame::none()
                .fill(c.surface)
                .rounding(Rounding::same(14.0))
                .inner_margin(Margin::same(14.0))
                .show(ui, |ui| {
                    ui.label(RichText::new("ТЕМА ОФОРМЛЕНИЯ").size(11.0).strong().color(c.text_muted));
                    ui.add_space(6.0);

                    let themes = [
                        (AppTheme::Nordic, "Nordic (Скандинавия)"),
                        (AppTheme::Void, "Void (OLED Черный)"),
                        (AppTheme::Cyberpunk, "Cyberpunk (Неон)"),
                        (AppTheme::Emerald, "Emerald (Изумруд)"),
                        (AppTheme::TokyoNight, "Tokyo Night (Токио)"),
                    ];

                    ui.horizontal_wrapped(|ui| {
                        for (th, th_name) in themes {
                            let is_sel = self.settings.theme == th;
                            let pill = egui::Button::new(RichText::new(th_name).size(11.0).strong().color(if is_sel { Color32::from_rgb(10, 14, 23) } else { c.text_primary }))
                                .fill(if is_sel { c.accent } else { c.surface_elevated })
                                .stroke(Stroke::NONE)
                                .rounding(Rounding::same(10.0));

                            if ui.add(pill).clicked() {
                                self.settings.theme = th;
                                self.save_settings();
                                apply_theme_to_ctx(ctx, th);
                            }
                        }
                    });
                });

            ui.add_space(10.0);

            // Subscription Card
            Frame::none()
                .fill(c.surface)
                .rounding(Rounding::same(14.0))
                .inner_margin(Margin::same(14.0))
                .show(ui, |ui| {
                    ui.label(RichText::new("ССЫЛКА НА ПОДПИСКУ").size(11.0).strong().color(c.text_muted));
                    ui.add_space(4.0);
                    if ui.add(egui::TextEdit::singleline(&mut self.settings.sub_url).desired_width(ui.available_width())).changed() {
                        self.save_settings();
                    }
                    
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        if ui.button("🔄 Обновить серверы").clicked() {
                            self.load_subscription();
                        }
                    });
                });

            ui.add_space(10.0);

            // HWID Card
            Frame::none()
                .fill(c.surface)
                .rounding(Rounding::same(14.0))
                .inner_margin(Margin::same(14.0))
                .show(ui, |ui| {
                    ui.label(RichText::new("ИДЕНТИФИКАТОР УСТРОЙСТВА (HWID)").size(11.0).strong().color(c.text_muted));
                    ui.add_space(2.0);
                    ui.label(RichText::new("Передается провайдеру для авторизации (эмуляция Happ/Incy).").size(11.0).color(c.text_secondary));
                    ui.add_space(4.0);
                    
                    if ui.add(egui::TextEdit::singleline(&mut self.settings.hwid).desired_width(ui.available_width())).changed() {
                        self.save_settings();
                    }
                    
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        if ui.button("📋 Скопировать HWID").clicked() {
                            ui.output_mut(|o| o.copied_text = self.settings.hwid.clone());
                        }
                        if ui.button("🔄 Сбросить на родной").clicked() {
                            self.settings.hwid = crate::types::get_or_create_default_hwid();
                            self.save_settings();
                        }
                        if ui.button("🤖 Бот @ruvpn (сбросить лимит)").clicked() {
                            let _ = std::process::Command::new("cmd").args(["/c", "start", "https://t.me/ruvpn"]).spawn();
                        }
                    });
                });

            ui.add_space(10.0);

            // Network Settings Card
            Frame::none()
                .fill(c.surface)
                .rounding(Rounding::same(14.0))
                .inner_margin(Margin::same(14.0))
                .show(ui, |ui| {
                    ui.label(RichText::new("СЕТЕВЫЕ ПАРАМЕТРЫ").size(11.0).strong().color(c.text_muted));
                    ui.add_space(4.0);
                    
                    ui.horizontal(|ui| {
                        ui.label("DNS:");
                        ui.add(egui::TextEdit::singleline(&mut self.settings.dns).desired_width(120.0));

                        ui.label("Mixed Port:");
                        let mut p_str = self.settings.port.to_string();
                        if ui.add(egui::TextEdit::singleline(&mut p_str).desired_width(70.0)).changed() {
                            if let Ok(p) = p_str.parse() {
                                self.settings.port = p;
                            }
                        }
                    });

                    ui.add_space(8.0);
                    if ui.button("💾 Сохранить").clicked() {
                        self.save_settings();
                    }
                });

            ui.add_space(10.0);

            // Logs Collapsible
            ui.collapsing(RichText::new("Консоль логов Sing-Box").color(c.text_muted), |ui| {
                if ui.button("Очистить логи").clicked() {
                    self.log_lines.clear();
                }
                egui::ScrollArea::vertical().max_height(160.0).stick_to_bottom(true).show(ui, |ui| {
                    for line in &self.log_lines {
                        let color = if line.contains("ERROR") || line.contains("FATAL") {
                            c.danger
                        } else {
                            Color32::from_rgb(167, 243, 208)
                        };
                        ui.label(RichText::new(line).monospace().size(11.0).color(color));
                    }
                });
            });
        });
    }
}

fn apply_theme_to_ctx(ctx: &egui::Context, theme: AppTheme) {
    let c = get_theme_colors(theme);
    let mut visuals = egui::Visuals::dark();
    visuals.panel_fill = c.bg;
    visuals.window_fill = c.surface;
    visuals.faint_bg_color = c.surface;
    visuals.extreme_bg_color = c.bg;
    visuals.widgets.noninteractive.bg_stroke = Stroke::NONE;
    visuals.widgets.inactive.bg_stroke = Stroke::NONE;
    visuals.widgets.hovered.bg_stroke = Stroke::new(1.0_f32, c.accent);
    ctx.set_visuals(visuals);
}

impl eframe::App for VpnApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.update_channels();
        ctx.request_repaint_after(std::time::Duration::from_millis(150));

        let is_running = self.runner.is_running();
        let c = self.colors();

        egui::CentralPanel::default().show(ctx, |ui| {
            self.render_unibody_header(ui, &c);

            // Error banner if any
            let mut dismiss_err = false;
            let mut open_bot = false;
            let mut retry_sub = false;
            if let Some(ref err) = self.error_banner {
                ui.add_space(4.0);
                Frame::none()
                    .fill(Color32::from_rgb(45, 15, 15))
                    .stroke(Stroke::new(1.0, Color32::from_rgb(140, 40, 40)))
                    .rounding(Rounding::same(12.0))
                    .inner_margin(Margin::same(12.0))
                    .show(ui, |ui| {
                        ui.horizontal(|ui| {
                            ui.label(RichText::new("⚠️").size(16.0));
                            ui.label(RichText::new(err).size(12.0).color(Color32::from_rgb(254, 202, 202)));
                            ui.with_layout(Layout::right_to_left(egui::Align::TOP), |ui| {
                                if ui.button("✕").clicked() {
                                    dismiss_err = true;
                                }
                            });
                        });
                        if err.contains("@ruvpn") || err.contains("лимит") || err.contains("Лимит") {
                            ui.add_space(6.0);
                            ui.horizontal(|ui| {
                                if ui.button(RichText::new("🤖 Открыть бота @ruvpn (сбросить лимит)").size(11.0).strong()).clicked() {
                                    open_bot = true;
                                }
                                if ui.button(RichText::new("🔄 Обновить серверы").size(11.0)).clicked() {
                                    retry_sub = true;
                                }
                            });
                        }
                    });
            }
            if dismiss_err {
                self.error_banner = None;
            }
            if open_bot {
                let _ = std::process::Command::new("cmd").args(["/c", "start", "https://t.me/ruvpn"]).spawn();
            }
            if retry_sub {
                self.load_subscription();
            }

            ui.add_space(4.0);

            match self.current_screen {
                ViewScreen::Main => self.render_main_screen(ui, &c, is_running),
                ViewScreen::Servers => self.render_servers_screen(ui, &c),
                ViewScreen::Protection => self.render_protection_screen(ui, &c),
                ViewScreen::Settings => self.render_settings_screen(ui, &c, ctx),
            }
        });
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        self.runner.stop();
    }
}
