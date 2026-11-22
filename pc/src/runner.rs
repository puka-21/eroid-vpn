use std::io::{BufRead, BufReader};
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::thread;

use crate::sysproxy::{disable_system_proxy, enable_system_proxy};
use crate::types::RunMode;

const CREATE_NO_WINDOW: u32 = 0x08000000;

#[link(name = "shell32")]
extern "system" {
    fn IsUserAnAdmin() -> i32;
    fn ShellExecuteW(
        hwnd: *mut std::ffi::c_void,
        lp_operation: *const u16,
        lp_file: *const u16,
        lp_parameters: *const u16,
        lp_directory: *const u16,
        n_show_cmd: i32,
    ) -> isize;
}

pub fn is_admin() -> bool {
    unsafe { IsUserAnAdmin() != 0 }
}

pub fn restart_as_admin() {
    if let Ok(exe) = std::env::current_exe() {
        use std::os::windows::ffi::OsStrExt;
        let op: Vec<u16> = std::ffi::OsStr::new("runas").encode_wide().chain(std::iter::once(0)).collect();
        let file: Vec<u16> = exe.as_os_str().encode_wide().chain(std::iter::once(0)).collect();
        unsafe {
            ShellExecuteW(std::ptr::null_mut(), op.as_ptr(), file.as_ptr(), std::ptr::null(), std::ptr::null(), 1);
        }
        std::process::exit(0);
    }
}

pub struct SingBoxRunner {
    base_dir: PathBuf,
    child: Arc<Mutex<Option<Child>>>,
    active_mode: Arc<Mutex<RunMode>>,
}

impl SingBoxRunner {
    pub fn new(base_dir: PathBuf) -> Self {
        Self {
            base_dir,
            child: Arc::new(Mutex::new(None)),
            active_mode: Arc::new(Mutex::new(RunMode::Tun)),
        }
    }

    pub fn is_running(&self) -> bool {
        let mut lock = self.child.lock().unwrap();
        if let Some(ref mut child) = *lock {
            match child.try_wait() {
                Ok(None) => true,
                _ => {
                    *lock = None;
                    false
                }
            }
        } else {
            false
        }
    }

    pub fn start(
        &self,
        config: &serde_json::Value,
        mode: RunMode,
        mixed_port: u16,
        log_tx: Sender<String>,
    ) -> Result<(), String> {
        self.stop();

        *self.active_mode.lock().unwrap() = mode;

        let bin_dir = crate::embed::ensure_binaries_extracted().map_err(|e| format!("Failed to extract embedded core: {e}"))?;
        let config_path = bin_dir.join("runtime_config.json");
        let bin_path = bin_dir.join("sing-box.exe");

        if !bin_path.exists() {
            return Err(format!("sing-box.exe not found at {}", bin_path.display()));
        }

        let config_str = serde_json::to_string_pretty(config).map_err(|e| e.to_string())?;
        std::fs::write(&config_path, config_str).map_err(|e| e.to_string())?;

        let mut cmd = Command::new(&bin_path);
        cmd.args(["run", "-c"])
            .arg(&config_path)
            .arg("-D")
            .arg(&bin_dir)
            .creation_flags(CREATE_NO_WINDOW)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        let mut child_proc = cmd.spawn().map_err(|e| format!("Failed to spawn sing-box: {e}"))?;

        if mode == RunMode::Proxy {
            let _ = enable_system_proxy(mixed_port);
        }

        let stdout = child_proc.stdout.take();
        let stderr = child_proc.stderr.take();

        let tx1 = log_tx.clone();
        if let Some(out) = stdout {
            thread::spawn(move || {
                let reader = BufReader::new(out);
                for line in reader.lines().flatten() {
                    let _ = tx1.send(line);
                }
            });
        }

        let tx2 = log_tx;
        if let Some(err) = stderr {
            thread::spawn(move || {
                let reader = BufReader::new(err);
                for line in reader.lines().flatten() {
                    let _ = tx2.send(line);
                }
            });
        }

        *self.child.lock().unwrap() = Some(child_proc);
        Ok(())
    }

    pub fn stop(&self) {
        let mut lock = self.child.lock().unwrap();
        if let Some(ref mut child) = *lock {
            let _ = child.kill();
            let _ = child.wait();
        }
        *lock = None;

        let mode = *self.active_mode.lock().unwrap();
        if mode == RunMode::Proxy {
            let _ = disable_system_proxy();
        }
    }
}

impl Drop for SingBoxRunner {
    fn drop(&mut self) {
        self.stop();
    }
}
