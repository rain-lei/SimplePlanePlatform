use crate::state::{AppState, OriginalProxyState};
use std::process::Command;

/// 设置系统代理指向本地端口
pub async fn set_system_proxy(state: &mut AppState) -> Result<(), String> {
    let socks_port = state.proxy_port;
    let http_port = state.http_port;

    // 先保存原始系统代理状态
    state.original_proxy_state = Some(get_current_proxy_state()?);

    #[cfg(target_os = "macos")]
    {
        set_system_proxy_macos(socks_port, http_port)?;
    }

    #[cfg(target_os = "windows")]
    {
        set_system_proxy_windows(socks_port, http_port)?;
    }

    log::info!(
        "System proxy set: SOCKS5={}, HTTP={}",
        socks_port,
        http_port
    );
    Ok(())
}

/// 还原系统代理到设置前的状态
pub async fn restore_system_proxy(state: &mut AppState) -> Result<(), String> {
    if let Some(ref original) = state.original_proxy_state {
        #[cfg(target_os = "macos")]
        {
            restore_proxy_macos(original)?;
        }

        #[cfg(target_os = "windows")]
        {
            restore_proxy_windows(original)?;
        }

        log::info!("System proxy restored to original state");
    }
    state.original_proxy_state = None;
    Ok(())
}

/// 获取当前系统代理状态
fn get_current_proxy_state() -> Result<OriginalProxyState, String> {
    #[cfg(target_os = "macos")]
    {
        get_proxy_state_macos()
    }
    #[cfg(target_os = "windows")]
    {
        get_proxy_state_windows()
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        Ok(OriginalProxyState {
            http_enabled: false,
            https_enabled: false,
            socks_enabled: false,
            http_host: String::new(),
            http_port: String::new(),
            https_host: String::new(),
            https_port: String::new(),
            socks_host: String::new(),
            socks_port: String::new(),
        })
    }
}

// ============ macOS 实现 ============

#[cfg(target_os = "macos")]
fn get_network_service() -> String {
    // 获取默认网络服务名称
    let output = Command::new("networksetup")
        .args(["-listallnetworkservices"])
        .output();

    if let Ok(out) = output {
        let stdout = String::from_utf8_lossy(&out.stdout);
        // 优先 Wi-Fi，否则取第一个非标记行
        for line in stdout.lines() {
            if line.contains("Wi-Fi") || line.contains("Ethernet") {
                return line.trim().replace('*', "").trim().to_string();
            }
        }
        // fallback：取第一个非标题行
        for line in stdout.lines().skip(1) {
            let trimmed = line.trim().replace('*', "");
            if !trimmed.is_empty() {
                return trimmed.trim().to_string();
            }
        }
    }
    "Wi-Fi".to_string()
}

#[cfg(target_os = "macos")]
fn get_proxy_state_macos() -> Result<OriginalProxyState, String> {
    let service = get_network_service();

    let socks_output = Command::new("networksetup")
        .args(["-getsocksfirewallproxy", &service])
        .output()
        .map_err(|e| format!("Failed to get SOCKS proxy: {}", e))?;

    let socks_str = String::from_utf8_lossy(&socks_output.stdout);
    let socks_enabled = socks_str.contains("Enabled: Yes");
    let socks_host = parse_proxy_field(&socks_str, "Server");
    let socks_port = parse_proxy_field(&socks_str, "Port");

    let http_output = Command::new("networksetup")
        .args(["-getwebproxy", &service])
        .output()
        .map_err(|e| format!("Failed to get HTTP proxy: {}", e))?;

    let http_str = String::from_utf8_lossy(&http_output.stdout);
    let http_enabled = http_str.contains("Enabled: Yes");
    let http_host = parse_proxy_field(&http_str, "Server");
    let http_port = parse_proxy_field(&http_str, "Port");

    let https_output = Command::new("networksetup")
        .args(["-getsecurewebproxy", &service])
        .output()
        .map_err(|e| format!("Failed to get HTTPS proxy: {}", e))?;

    let https_str = String::from_utf8_lossy(&https_output.stdout);
    let https_enabled = https_str.contains("Enabled: Yes");
    let https_host = parse_proxy_field(&https_str, "Server");
    let https_port = parse_proxy_field(&https_str, "Port");

    Ok(OriginalProxyState {
        http_enabled,
        https_enabled,
        socks_enabled,
        http_host,
        http_port,
        https_host,
        https_port,
        socks_host,
        socks_port,
    })
}

#[cfg(target_os = "macos")]
fn parse_proxy_field(output: &str, field: &str) -> String {
    for line in output.lines() {
        if line.starts_with(field) {
            if let Some(value) = line.split(':').nth(1) {
                return value.trim().to_string();
            }
        }
    }
    String::new()
}

#[cfg(target_os = "macos")]
fn set_system_proxy_macos(socks_port: u16, http_port: u16) -> Result<(), String> {
    let service = get_network_service();
    let host = "127.0.0.1";

    // 设置 SOCKS 代理
    run_networksetup(&[
        "-setsocksfirewallproxy",
        &service,
        host,
        &socks_port.to_string(),
    ])?;
    run_networksetup(&["-setsocksfirewallproxystate", &service, "on"])?;

    // 设置 HTTP 代理
    run_networksetup(&[
        "-setwebproxy",
        &service,
        host,
        &http_port.to_string(),
    ])?;
    run_networksetup(&["-setwebproxystate", &service, "on"])?;

    // 设置 HTTPS 代理
    run_networksetup(&[
        "-setsecurewebproxy",
        &service,
        host,
        &http_port.to_string(),
    ])?;
    run_networksetup(&["-setsecurewebproxystate", &service, "on"])?;

    Ok(())
}

#[cfg(target_os = "macos")]
fn restore_proxy_macos(original: &OriginalProxyState) -> Result<(), String> {
    let service = get_network_service();

    // 还原 SOCKS
    if original.socks_enabled && !original.socks_host.is_empty() {
        run_networksetup(&[
            "-setsocksfirewallproxy",
            &service,
            &original.socks_host,
            &original.socks_port,
        ])?;
        run_networksetup(&["-setsocksfirewallproxystate", &service, "on"])?;
    } else {
        run_networksetup(&["-setsocksfirewallproxystate", &service, "off"])?;
    }

    // 还原 HTTP
    if original.http_enabled && !original.http_host.is_empty() {
        run_networksetup(&[
            "-setwebproxy",
            &service,
            &original.http_host,
            &original.http_port,
        ])?;
        run_networksetup(&["-setwebproxystate", &service, "on"])?;
    } else {
        run_networksetup(&["-setwebproxystate", &service, "off"])?;
    }

    // 还原 HTTPS
    if original.https_enabled && !original.https_host.is_empty() {
        run_networksetup(&[
            "-setsecurewebproxy",
            &service,
            &original.https_host,
            &original.https_port,
        ])?;
        run_networksetup(&["-setsecurewebproxystate", &service, "on"])?;
    } else {
        run_networksetup(&["-setsecurewebproxystate", &service, "off"])?;
    }

    Ok(())
}

#[cfg(target_os = "macos")]
fn run_networksetup(args: &[&str]) -> Result<(), String> {
    let output = Command::new("networksetup")
        .args(args)
        .output()
        .map_err(|e| format!("networksetup failed: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("networksetup error: {}", stderr));
    }
    Ok(())
}

// ============ Windows 实现 ============

#[cfg(target_os = "windows")]
fn get_proxy_state_windows() -> Result<OriginalProxyState, String> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let internet_settings = hkcu
        .open_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings")
        .map_err(|e| format!("Failed to open registry: {}", e))?;

    let proxy_enable: u32 = internet_settings
        .get_value("ProxyEnable")
        .unwrap_or(0);
    let proxy_server: String = internet_settings
        .get_value("ProxyServer")
        .unwrap_or_default();

    Ok(OriginalProxyState {
        http_enabled: proxy_enable == 1,
        https_enabled: proxy_enable == 1,
        socks_enabled: false,
        http_host: proxy_server.clone(),
        http_port: String::new(),
        https_host: proxy_server,
        https_port: String::new(),
        socks_host: String::new(),
        socks_port: String::new(),
    })
}

#[cfg(target_os = "windows")]
fn set_system_proxy_windows(socks_port: u16, http_port: u16) -> Result<(), String> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let (internet_settings, _) = hkcu
        .create_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings")
        .map_err(|e| format!("Failed to open registry: {}", e))?;

    let proxy_server = format!(
        "http=127.0.0.1:{};https=127.0.0.1:{};socks=127.0.0.1:{}",
        http_port, http_port, socks_port
    );

    internet_settings
        .set_value("ProxyEnable", &1u32)
        .map_err(|e| format!("Failed to set ProxyEnable: {}", e))?;
    internet_settings
        .set_value("ProxyServer", &proxy_server)
        .map_err(|e| format!("Failed to set ProxyServer: {}", e))?;

    // 通知系统设置已更改
    notify_proxy_change_windows();

    Ok(())
}

#[cfg(target_os = "windows")]
fn restore_proxy_windows(original: &OriginalProxyState) -> Result<(), String> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let (internet_settings, _) = hkcu
        .create_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings")
        .map_err(|e| format!("Failed to open registry: {}", e))?;

    if original.http_enabled {
        internet_settings
            .set_value("ProxyEnable", &1u32)
            .map_err(|e| format!("Failed to restore ProxyEnable: {}", e))?;
        internet_settings
            .set_value("ProxyServer", &original.http_host)
            .map_err(|e| format!("Failed to restore ProxyServer: {}", e))?;
    } else {
        internet_settings
            .set_value("ProxyEnable", &0u32)
            .map_err(|e| format!("Failed to restore ProxyEnable: {}", e))?;
    }

    notify_proxy_change_windows();
    Ok(())
}

#[cfg(target_os = "windows")]
fn notify_proxy_change_windows() {
    // 调用 InternetSetOption 通知系统代理变更
    // 简化实现：通过命令行刷新
    let _ = Command::new("cmd")
        .args(["/c", "ipconfig /flushdns"])
        .output();
}
