//! 路由恢复 Drop Guard
//!
//! 记录所有添加的系统路由，在进程退出（包括 panic）时自动恢复原始路由表。

use std::net::IpAddr;
use std::process::Command;

use crate::config::{BypassConfig, IntranetDnsConfig, TunConfig};
use crate::error::TunError;

/// 单条路由记录
#[derive(Debug, Clone)]
pub struct RouteEntry {
    /// 目标网段
    pub destination: String,
    /// 网关
    pub gateway: String,
}

/// 路由恢复守卫：Drop 时自动删除添加的路由并恢复原始默认网关和 DNS
pub struct RouteGuard {
    /// 原始默认网关
    original_gateway: Option<IpAddr>,
    /// TUN 设备名称
    _tun_name: String,
    /// 记录所有添加的路由
    routes_added: Vec<RouteEntry>,
    /// 原始 DNS 服务器设置（用于恢复）
    original_dns: Option<DnsSettings>,
    /// 已创建的 /etc/resolver/ 文件列表（用于恢复时删除）
    resolver_files: Vec<String>,
}

/// 保存的原始 DNS 设置
#[derive(Debug, Clone)]
struct DnsSettings {
    /// 网络服务名（如 "Wi-Fi"）
    service_name: String,
    /// 原始 DNS 服务器列表（可能是 "Empty" 表示 DHCP 自动获取）
    servers: Vec<String>,
}

impl RouteGuard {
    /// 检测当前网络环境并设置路由
    ///
    /// 设置逻辑：
    /// 1. 获取当前默认网关（物理网关）
    /// 2. 为 proxy-remote IP 和 bypass CIDR 添加排除路由（走物理网关）
    /// 3. 添加 0.0.0.0/1 和 128.0.0.0/1 指向 TUN 设备（截获所有流量）
    ///
    /// 这样确保发往 proxy-remote 的流量不会再进入 TUN 造成回环。
    #[cfg(target_os = "macos")]
    pub async fn setup(config: &TunConfig, bypass: &BypassConfig, intranet_dns: &IntranetDnsConfig, iface_name: &str) -> Result<Self, TunError> {
        tracing::info!("Setting up route guard for TUN interface: {}", iface_name);

        // 获取当前默认网关
        let original_gateway = Self::get_default_gateway()?;
        tracing::info!("Original default gateway: {:?}", original_gateway);

        let mut guard = Self {
            original_gateway,
            _tun_name: config.name.clone(),
            routes_added: Vec::new(),
            original_dns: None,
            resolver_files: Vec::new(),
        };

        // 必须有原始网关才能添加排除路由
        if let Some(orig_gw) = &guard.original_gateway {
            let gw_str = orig_gw.to_string();

            // 为 proxy-remote IP 添加排除路由（host route /32）
            for ip in &bypass.proxy_remote_ips {
                let host_route = if ip.contains('/') {
                    ip.clone()
                } else {
                    format!("{}/32", ip)
                };
                tracing::info!("Adding bypass route for proxy-remote: {} via {}", host_route, gw_str);
                guard.add_route(&host_route, &gw_str)?;
            }

            // 为额外 bypass CIDR（如内网段）添加排除路由
            for cidr in &bypass.extra_cidrs {
                tracing::info!("Adding bypass route for CIDR: {} via {}", cidr, gw_str);
                guard.add_route(cidr, &gw_str)?;
            }

            // 为 DNS bypass IP 添加排除路由（供 proxy-local 直连时做真实 DNS 解析）
            for ip in &bypass.dns_bypass_ips {
                let host_route = if ip.contains('/') {
                    ip.clone()
                } else {
                    format!("{}/32", ip)
                };
                tracing::info!("Adding bypass route for DNS server: {} via {}", host_route, gw_str);
                guard.add_route(&host_route, &gw_str)?;
            }
        } else {
            tracing::warn!("No original gateway detected; bypass routes will not be added");
        }

        // 【顺序关键】先创建 /etc/resolver/ 分流文件，再修改系统 DNS
        // 这样确保内网域名的解析规则已就位，修改系统 DNS 后内网域名不会受影响

        // Step 1: 设置内网域名 DNS 分流（/etc/resolver/）
        // 如果这步失败，绝不能执行 Step 2（否则内网域名会无法解析）
        let resolvers_ok = if !intranet_dns.servers.is_empty() && !intranet_dns.domains.is_empty() {
            match guard.setup_intranet_resolvers(intranet_dns) {
                Ok(()) => true,
                Err(e) => {
                    tracing::error!("Intranet DNS resolver setup failed: {}. Will NOT modify system DNS.", e);
                    false
                }
            }
        } else {
            // 没有配置内网 DNS，可以安全修改系统 DNS
            true
        };

        // Step 2: 修改系统 DNS 为 198.18.0.2（FakeDNS）
        // 仅在 /etc/resolver/ 全部就位后才执行
        if resolvers_ok {
            if let Err(e) = guard.setup_dns() {
                tracing::warn!("Failed to set system DNS (non-fatal): {}", e);
            }
        } else {
            tracing::warn!(
                "Skipping system DNS modification due to resolver setup failure. \
                 External domains may not be intercepted by FakeDNS."
            );
        }

        // 添加主路由规则：0.0.0.0/1 和 128.0.0.0/1 指向 TUN 接口
        // 这两条路由比 default (0.0.0.0/0) 更具体，所以会优先匹配
        // macOS 上获取 TUN 接口的 peer/destination 地址作为网关
        let tun_peer = Self::get_tun_peer_address(iface_name)?;
        tracing::info!("TUN peer address for routing: {}", tun_peer);
        guard.add_route("0.0.0.0/1", &tun_peer)?;
        guard.add_route("128.0.0.0/1", &tun_peer)?;

        tracing::info!("Routes configured successfully");

        Ok(guard)
    }

    #[cfg(not(target_os = "macos"))]
    pub async fn setup(config: &TunConfig, bypass: &BypassConfig, intranet_dns: &IntranetDnsConfig, iface_name: &str) -> Result<Self, TunError> {
        let _ = (bypass, intranet_dns, iface_name);
        tracing::warn!("Route guard not implemented for this platform");
        Ok(Self {
            original_gateway: None,
            _tun_name: config.name.clone(),
            routes_added: Vec::new(),
            original_dns: None,
            resolver_files: Vec::new(),
        })
    }

    /// 持久化备份文件路径（用于异常退出后恢复）
    const DNS_BACKUP_FILE: &'static str = "/tmp/tun-adapter-dns-backup.conf";

    /// 设置系统 DNS 为 FakeDNS (198.18.0.2)
    ///
    /// 将系统 DNS 改为 198.18.0.2，让所有 DNS 查询走 TUN → FakeDNS。
    /// 内网域名通过 /etc/resolver/ 分流规则（先于本方法执行）走内网 DNS，不受影响。
    ///
    /// 注意：调用本方法前必须先调用 setup_intranet_resolvers()，
    /// 确保 /etc/resolver/ 文件已就位，否则内网域名会无法解析。
    #[cfg(target_os = "macos")]
    fn setup_dns(&mut self) -> Result<(), TunError> {
        // 获取当前活跃的网络服务名
        let service_name = Self::get_active_network_service()?;
        tracing::info!("Active network service: {}", service_name);

        // 保存当前 DNS 设置（用于退出时恢复）
        let current_dns = Self::get_dns_servers(&service_name)?;
        tracing::info!("Original DNS servers for '{}': {:?}", service_name, current_dns);

        self.original_dns = Some(DnsSettings {
            service_name: service_name.clone(),
            servers: current_dns.clone(),
        });

        // 【关键】将原始 DNS 信息持久化到文件
        // 即使进程被 kill -9 或异常退出，也能通过 restore-dns.sh 恢复
        Self::persist_dns_backup(&service_name, &current_dns);

        // 设置系统 DNS 为 198.18.0.2（FakeDNS 地址，在 TUN 子网内）
        let output = Command::new("networksetup")
            .args(["-setdnsservers", &service_name, "198.18.0.2"])
            .output()
            .map_err(|e| TunError::SystemRoute(format!("failed to set DNS: {}", e)))?;

        if output.status.success() {
            tracing::info!("System DNS set to 198.18.0.2 (FakeDNS). Intranet domains use /etc/resolver/ split.");
        } else {
            let stderr = String::from_utf8_lossy(&output.stderr);
            // 失败则删除备份文件（DNS 没改成功，无需恢复）
            let _ = std::fs::remove_file(Self::DNS_BACKUP_FILE);
            return Err(TunError::SystemRoute(format!("networksetup -setdnsservers failed: {}", stderr)));
        }

        // 刷新 DNS 缓存
        let _ = Command::new("dscacheutil").arg("-flushcache").output();
        let _ = Command::new("killall")
            .args(["-HUP", "mDNSResponder"])
            .output();
        tracing::info!("DNS cache flushed");

        Ok(())
    }

    /// 将原始 DNS 设置持久化到文件（供异常退出后恢复）
    ///
    /// 文件格式：
    /// ```text
    /// service:Wi-Fi
    /// dns:11.11.11.11
    /// dns:11.11.11.12
    /// resolver:/etc/resolver/sankuai.com    (后续由 persist_resolver_list 追加)
    /// ```
    fn persist_dns_backup(service_name: &str, servers: &[String]) {
        let mut content = format!("service:{}\n", service_name);
        if servers.is_empty() || (servers.len() == 1 && servers[0] == "Empty") {
            content.push_str("dns:Empty\n");
        } else {
            for s in servers {
                content.push_str(&format!("dns:{}\n", s));
            }
        }

        match std::fs::write(Self::DNS_BACKUP_FILE, &content) {
            Ok(()) => tracing::info!("DNS backup saved to {}", Self::DNS_BACKUP_FILE),
            Err(e) => tracing::warn!("Failed to save DNS backup: {} (recovery may fail on crash)", e),
        }
    }

    #[cfg(not(target_os = "macos"))]
    fn setup_dns(&mut self) -> Result<(), TunError> {
        Ok(())
    }

    /// 设置内网域名 DNS 分流
    ///
    /// 在 /etc/resolver/ 下为每个内网域名创建配置文件，
    /// 让 macOS 对这些域名的 DNS 查询走内网 DNS 服务器（bypass 网段），
    /// 而不是走 FakeDNS (198.18.0.2)。
    #[cfg(target_os = "macos")]
    fn setup_intranet_resolvers(&mut self, intranet_dns: &IntranetDnsConfig) -> Result<(), TunError> {
        use std::fs;

        // 确保 /etc/resolver/ 目录存在
        let resolver_dir = "/etc/resolver";
        if !std::path::Path::new(resolver_dir).exists() {
            let output = Command::new("mkdir")
                .args(["-p", resolver_dir])
                .output()
                .map_err(|e| TunError::SystemRoute(format!("failed to spawn mkdir: {}", e)))?;
            if !output.status.success() {
                let stderr = String::from_utf8_lossy(&output.stderr);
                return Err(TunError::SystemRoute(format!(
                    "mkdir -p {} failed: {}", resolver_dir, stderr
                )));
            }
            tracing::info!("Created directory: {}", resolver_dir);
        }

        // 构建 resolver 文件内容（所有域名共享相同的 nameserver 列表）
        let mut content = String::new();
        for server in &intranet_dns.servers {
            content.push_str(&format!("nameserver {}\n", server));
        }

        let mut failed_domains = Vec::new();
        for domain in &intranet_dns.domains {
            let file_path = format!("{}/{}", resolver_dir, domain);

            // 写入文件（需要 root 权限，start-tun.sh 中 tun-adapter 以 sudo 运行）
            match fs::write(&file_path, &content) {
                Ok(()) => {
                    tracing::info!("Created resolver file: {} -> {:?}", file_path, intranet_dns.servers);
                    self.resolver_files.push(file_path);
                }
                Err(e) => {
                    tracing::error!("CRITICAL: Failed to write {}: {}", file_path, e);
                    failed_domains.push(domain.clone());
                }
            }
        }

        if !failed_domains.is_empty() {
            tracing::error!(
                "Failed to create resolver files for: {:?}. These domains will NOT resolve via intranet DNS!",
                failed_domains
            );
            // 如果有任何域名的 resolver 文件创建失败，返回错误
            // 防止后续 setup_dns() 修改系统 DNS，导致这些域名无法解析
            return Err(TunError::SystemRoute(format!(
                "Failed to create resolver files for domains: {:?}. Aborting DNS setup to prevent connectivity loss.",
                failed_domains
            )));
        }

        // 刷新 DNS 缓存使分流规则立即生效
        let _ = Command::new("dscacheutil").arg("-flushcache").output();
        let _ = Command::new("killall").args(["-HUP", "mDNSResponder"]).output();
        tracing::info!(
            "Intranet DNS resolvers configured successfully: {} domains -> {:?}",
            intranet_dns.domains.len(), intranet_dns.servers
        );

        // 将 resolver 文件列表追加到备份文件（供异常退出恢复时清理）
        Self::persist_resolver_list(&self.resolver_files);

        Ok(())
    }

    /// 将 resolver 文件列表追加到备份文件
    fn persist_resolver_list(resolver_files: &[String]) {
        use std::io::Write;
        // 追加到备份文件末尾（如果备份文件已存在的话）
        if let Ok(mut file) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(Self::DNS_BACKUP_FILE)
        {
            for path in resolver_files {
                let _ = writeln!(file, "resolver:{}", path);
            }
        }
    }

    #[cfg(not(target_os = "macos"))]
    fn setup_intranet_resolvers(&mut self, _intranet_dns: &IntranetDnsConfig) -> Result<(), TunError> {
        Ok(())
    }

    /// 清理 /etc/resolver/ 文件
    #[cfg(target_os = "macos")]
    fn restore_intranet_resolvers(&self) {
        use std::fs;

        for file_path in &self.resolver_files {
            match fs::remove_file(file_path) {
                Ok(()) => {
                    tracing::info!("Removed resolver file: {}", file_path);
                }
                Err(e) => {
                    tracing::warn!("Failed to remove {}: {}", file_path, e);
                }
            }
        }

        if !self.resolver_files.is_empty() {
            // 检查 /etc/resolver/ 是否为空，为空则删除目录
            if let Ok(entries) = std::fs::read_dir("/etc/resolver") {
                if entries.count() == 0 {
                    let _ = std::fs::remove_dir("/etc/resolver");
                    tracing::info!("Removed empty /etc/resolver/ directory");
                }
            }
        }
    }

    #[cfg(not(target_os = "macos"))]
    fn restore_intranet_resolvers(&self) {}

    /// 恢复原始 DNS 设置
    #[cfg(target_os = "macos")]
    fn restore_dns(&self) {
        if let Some(ref dns_settings) = self.original_dns {
            let args: Vec<&str> = if dns_settings.servers.is_empty()
                || (dns_settings.servers.len() == 1 && dns_settings.servers[0] == "Empty")
            {
                // 原来是 DHCP 自动获取，设置为 "Empty" 清除手动设置
                vec!["-setdnsservers", &dns_settings.service_name, "Empty"]
            } else {
                // 恢复原始 DNS 列表
                let mut args = vec!["-setdnsservers", &dns_settings.service_name];
                for server in &dns_settings.servers {
                    args.push(server.as_str());
                }
                args
            };

            tracing::info!("Restoring DNS for '{}': {:?}", dns_settings.service_name, dns_settings.servers);
            let result = Command::new("networksetup")
                .args(&args)
                .output();

            match result {
                Ok(output) => {
                    if output.status.success() {
                        tracing::info!("DNS restored successfully");
                        // 恢复成功，删除备份文件（标记已恢复）
                        let _ = std::fs::remove_file(Self::DNS_BACKUP_FILE);
                        tracing::info!("DNS backup file removed (restore complete)");
                    } else {
                        let stderr = String::from_utf8_lossy(&output.stderr);
                        tracing::warn!("DNS restore may have issues: {}", stderr);
                        // 恢复失败，保留备份文件供手动恢复
                    }
                }
                Err(e) => {
                    tracing::error!("Failed to restore DNS: {} (backup file preserved at {})", e, Self::DNS_BACKUP_FILE);
                }
            }

            // 再次刷新 DNS 缓存
            let _ = Command::new("dscacheutil").arg("-flushcache").output();
            let _ = Command::new("killall")
                .args(["-HUP", "mDNSResponder"])
                .output();
        }
    }

    #[cfg(not(target_os = "macos"))]
    fn restore_dns(&self) {}

    /// 获取当前活跃的网络服务名（如 "Wi-Fi" 或 "Ethernet"）
    #[cfg(target_os = "macos")]
    fn get_active_network_service() -> Result<String, TunError> {
        // 获取所有网络服务的顺序
        let output = Command::new("networksetup")
            .args(["-listallnetworkservices"])
            .output()
            .map_err(|e| TunError::SystemRoute(format!("failed to list network services: {}", e)))?;

        if !output.status.success() {
            return Err(TunError::SystemRoute("networksetup -listallnetworkservices failed".to_string()));
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        // 跳过第一行（"An asterisk (*) denotes..."），遍历每个服务检查其是否有 IP
        for line in stdout.lines().skip(1) {
            let service = line.trim().trim_start_matches('*');
            if service.is_empty() {
                continue;
            }

            // 检查该服务是否有活跃的 IP 地址
            let ip_output = Command::new("networksetup")
                .args(["-getinfo", service])
                .output();

            if let Ok(ip_out) = ip_output {
                let info = String::from_utf8_lossy(&ip_out.stdout);
                // 如果有 "IP address:" 且不是 "none"，则此服务是活跃的
                for info_line in info.lines() {
                    if info_line.starts_with("IP address:") {
                        let addr = info_line.trim_start_matches("IP address:").trim();
                        if !addr.is_empty() && addr != "none" {
                            return Ok(service.to_string());
                        }
                    }
                }
            }
        }

        // 回退：使用 "Wi-Fi"
        Ok("Wi-Fi".to_string())
    }

    /// 获取指定网络服务的当前 DNS 服务器列表
    #[cfg(target_os = "macos")]
    fn get_dns_servers(service_name: &str) -> Result<Vec<String>, TunError> {
        let output = Command::new("networksetup")
            .args(["-getdnsservers", service_name])
            .output()
            .map_err(|e| TunError::SystemRoute(format!("failed to get DNS servers: {}", e)))?;

        if !output.status.success() {
            return Ok(vec!["Empty".to_string()]);
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        let servers: Vec<String> = stdout
            .lines()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
            .collect();

        // 如果输出包含 "There aren't any DNS Servers" 则表示 DHCP
        if servers.len() == 1 && servers[0].contains("aren't any") {
            return Ok(vec!["Empty".to_string()]);
        }

        Ok(servers)
    }

    /// 获取 TUN 接口的 peer/destination 地址（用于路由网关）
    /// 在 macOS 上通过 ifconfig 解析 "inet X.X.X.X --> Y.Y.Y.Y" 中的 Y
    #[cfg(target_os = "macos")]
    fn get_tun_peer_address(iface_name: &str) -> Result<String, TunError> {
        let output = Command::new("ifconfig")
            .arg(iface_name)
            .output()
            .map_err(|e| {
                TunError::SystemRoute(format!("failed to run ifconfig {}: {}", iface_name, e))
            })?;

        if !output.status.success() {
            return Err(TunError::SystemRoute(format!(
                "ifconfig {} failed: {}",
                iface_name,
                String::from_utf8_lossy(&output.stderr)
            )));
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        // 解析 "inet 198.18.0.1 --> 10.0.0.255 netmask ..."
        for line in stdout.lines() {
            let line = line.trim();
            if line.starts_with("inet ") && line.contains("-->") {
                if let Some(arrow_pos) = line.find("-->") {
                    let after_arrow = &line[arrow_pos + 4..]; // skip "--> "
                    let peer = after_arrow.split_whitespace().next().unwrap_or("");
                    if peer.parse::<IpAddr>().is_ok() {
                        return Ok(peer.to_string());
                    }
                }
            }
        }

        Err(TunError::SystemRoute(format!(
            "could not find peer address for interface {}",
            iface_name
        )))
    }

    /// 获取当前系统默认网关
    #[cfg(target_os = "macos")]
    fn get_default_gateway() -> Result<Option<IpAddr>, TunError> {
        let output = Command::new("route")
            .args(["-n", "get", "default"])
            .output()
            .map_err(|e| {
                TunError::SystemRoute(format!("failed to run 'route get default': {}", e))
            })?;

        if !output.status.success() {
            tracing::warn!("Could not determine default gateway");
            return Ok(None);
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        for line in stdout.lines() {
            let line = line.trim();
            if line.starts_with("gateway:") {
                let gateway_str = line.trim_start_matches("gateway:").trim();
                if let Ok(ip) = gateway_str.parse::<IpAddr>() {
                    return Ok(Some(ip));
                }
            }
        }

        Ok(None)
    }

    /// 添加一条路由（通过网关 IP）并记录
    #[cfg(target_os = "macos")]
    fn add_route(&mut self, destination: &str, gateway: &str) -> Result<(), TunError> {
        tracing::info!("Executing: route -n add -net {} {}", destination, gateway);

        let output = Command::new("route")
            .args(["-n", "add", "-net", destination, gateway])
            .output()
            .map_err(|e| {
                TunError::SystemRoute(format!(
                    "failed to add route {} via {}: {}",
                    destination, gateway, e
                ))
            })?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        if output.status.success() {
            tracing::info!("Route added successfully: {} -> {}", destination, gateway);
        } else if !stderr.contains("File exists") {
            tracing::warn!("Route add failed (exit={}): stdout={}, stderr={}", output.status, stdout.trim(), stderr.trim());
        } else {
            tracing::info!("Route already exists: {} -> {}", destination, gateway);
        }

        self.routes_added.push(RouteEntry {
            destination: destination.to_string(),
            gateway: gateway.to_string(),
        });

        Ok(())
    }

    /// 添加一条路由（通过接口名）并记录 —— 用于 TUN point-to-point 接口
    #[cfg(target_os = "macos")]
    fn add_route_via_interface(&mut self, destination: &str, iface: &str) -> Result<(), TunError> {
        tracing::debug!("Adding route: {} via interface {}", destination, iface);

        let output = Command::new("route")
            .args(["-n", "add", "-net", destination, "-interface", iface])
            .output()
            .map_err(|e| {
                TunError::SystemRoute(format!(
                    "failed to add route {} via interface {}: {}",
                    destination, iface, e
                ))
            })?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            if !stderr.contains("File exists") {
                tracing::warn!("Route add via interface may have failed: {}", stderr);
            }
        }

        self.routes_added.push(RouteEntry {
            destination: destination.to_string(),
            gateway: format!("-interface {}", iface),
        });

        Ok(())
    }

    #[cfg(not(target_os = "macos"))]
    fn add_route(&mut self, destination: &str, gateway: &str) -> Result<(), TunError> {
        self.routes_added.push(RouteEntry {
            destination: destination.to_string(),
            gateway: gateway.to_string(),
        });
        Ok(())
    }

    #[cfg(not(target_os = "macos"))]
    fn add_route_via_interface(&mut self, destination: &str, iface: &str) -> Result<(), TunError> {
        self.routes_added.push(RouteEntry {
            destination: destination.to_string(),
            gateway: format!("-interface {}", iface),
        });
        Ok(())
    }

    /// 删除一条路由
    #[cfg(target_os = "macos")]
    fn delete_route(destination: &str) {
        tracing::debug!("Deleting route: {}", destination);
        let result = Command::new("route")
            .args(["-n", "delete", "-net", destination])
            .output();

        match result {
            Ok(output) => {
                if !output.status.success() {
                    let stderr = String::from_utf8_lossy(&output.stderr);
                    tracing::warn!("Failed to delete route {}: {}", destination, stderr);
                }
            }
            Err(e) => {
                tracing::error!("Failed to run route delete command: {}", e);
            }
        }
    }

    #[cfg(not(target_os = "macos"))]
    fn delete_route(_destination: &str) {
        // 非 macOS 平台暂不处理
    }
}

impl Drop for RouteGuard {
    fn drop(&mut self) {
        tracing::info!(
            "RouteGuard dropping: restoring {} routes + DNS + {} resolver files",
            self.routes_added.len(),
            self.resolver_files.len()
        );

        // 1. 清理 /etc/resolver/ 分流文件
        self.restore_intranet_resolvers();

        // 2. 恢复 DNS（在删除路由之前，确保网络仍可达）
        self.restore_dns();

        // 2. 删除所有添加的路由（逆序删除）
        for entry in self.routes_added.iter().rev() {
            Self::delete_route(&entry.destination);
        }

        // 3. 恢复原始默认路由
        if let Some(gateway) = &self.original_gateway {
            tracing::info!("Restoring original default gateway: {}", gateway);
            #[cfg(target_os = "macos")]
            {
                let result = Command::new("route")
                    .args(["-n", "add", "default", &gateway.to_string()])
                    .output();

                match result {
                    Ok(output) => {
                        if output.status.success() {
                            tracing::info!("Default gateway restored successfully");
                        } else {
                            let stderr = String::from_utf8_lossy(&output.stderr);
                            tracing::warn!("Gateway restore may have issues: {}", stderr);
                        }
                    }
                    Err(e) => {
                        tracing::error!("Failed to restore default gateway: {}", e);
                    }
                }
            }
        }

        tracing::info!("RouteGuard cleanup completed");
    }
}
