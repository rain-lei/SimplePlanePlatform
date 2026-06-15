use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// 代理配置（对应 proxy.yml 的结构）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxyConfig {
    pub local: LocalConfig,
    pub remote: RemoteConfig,
    #[serde(default)]
    pub route: RouteConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LocalConfig {
    #[serde(default = "default_socks_port")]
    pub port: u16,
    #[serde(default = "default_http_enabled")]
    pub http_proxy_enabled: bool,
    #[serde(default = "default_http_port")]
    pub http_proxy_port: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RemoteConfig {
    pub host: String,
    pub port: u16,
    #[serde(default = "default_cipher")]
    pub cipher: String,
    #[serde(default)]
    pub key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct RouteConfig {
    #[serde(default = "default_route_mode")]
    pub default_route: String,
    #[serde(default)]
    pub proxy_list: Vec<String>,
    #[serde(default)]
    pub direct_list: Vec<String>,
}

fn default_socks_port() -> u16 { 1080 }
fn default_http_enabled() -> bool { true }
fn default_http_port() -> u16 { 1087 }
fn default_cipher() -> String { "chacha20".to_string() }
fn default_route_mode() -> String { "proxy".to_string() }

/// TUN 配置（对应 tun.toml）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TunConfig {
    #[serde(default)]
    pub tun: TunSection,
    #[serde(default)]
    pub dns: DnsSection,
    #[serde(default)]
    pub proxy: TunProxySection,
    #[serde(default)]
    pub routing: Option<RoutingSection>,
    #[serde(default)]
    pub bypass: Option<BypassSection>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TunSection {
    #[serde(default = "default_tun_name")]
    pub name: String,
    #[serde(default = "default_tun_address")]
    pub address: String,
    #[serde(default = "default_tun_netmask")]
    pub netmask: Option<String>,
    #[serde(default = "default_tun_mtu")]
    pub mtu: u16,
    #[serde(default = "default_true")]
    pub enabled: bool,
}

impl Default for TunSection {
    fn default() -> Self {
        Self {
            name: default_tun_name(),
            address: default_tun_address(),
            netmask: Some("255.254.0.0".to_string()),
            mtu: default_tun_mtu(),
            enabled: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DnsSection {
    #[serde(default = "default_dns_listen")]
    pub listen: String,
    #[serde(default = "default_dns_upstream")]
    pub upstream: String,
}

impl Default for DnsSection {
    fn default() -> Self {
        Self {
            listen: default_dns_listen(),
            upstream: default_dns_upstream(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TunProxySection {
    #[serde(default = "default_proxy_socks")]
    pub socks5: Option<String>,
    #[serde(default)]
    pub socks5_addr: Option<String>,
}

impl Default for TunProxySection {
    fn default() -> Self {
        Self {
            socks5: default_proxy_socks(),
            socks5_addr: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RoutingSection {
    #[serde(default = "default_route_proxy")]
    pub default_action: String,
    #[serde(default)]
    pub rules: Vec<RoutingRule>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RoutingRule {
    #[serde(rename = "type")]
    pub rule_type: String,
    pub value: String,
    pub action: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BypassSection {
    #[serde(default)]
    pub proxy_remote_ips: Vec<String>,
    #[serde(default)]
    pub extra_cidrs: Vec<String>,
    #[serde(default)]
    pub dns_bypass_ips: Vec<String>,
}

fn default_tun_name() -> String { "utun9".to_string() }
fn default_tun_address() -> String { "198.18.0.1".to_string() }
fn default_tun_netmask() -> Option<String> { Some("255.254.0.0".to_string()) }
fn default_tun_mtu() -> u16 { 1500 }
fn default_true() -> bool { true }
fn default_dns_listen() -> String { "127.0.0.1:53".to_string() }
fn default_dns_upstream() -> String { "8.8.8.8:53".to_string() }
fn default_proxy_socks() -> Option<String> { Some("127.0.0.1:1080".to_string()) }
fn default_route_proxy() -> String { "proxy".to_string() }

/// 获取用户配置目录
pub fn get_config_dir() -> PathBuf {
    let base = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
    let dir = base.join("SimplePlane");
    if !dir.exists() {
        let _ = fs::create_dir_all(&dir);
    }
    dir
}

/// 读取代理配置
pub fn load_proxy_config() -> Result<ProxyConfig, String> {
    let config_path = get_config_dir().join("proxy.yml");

    if !config_path.exists() {
        // 首次运行，写入默认配置
        let default_config = get_default_proxy_config();
        save_proxy_config(&default_config)?;
        return Ok(default_config);
    }

    let content =
        fs::read_to_string(&config_path).map_err(|e| format!("Failed to read config: {}", e))?;

    serde_yaml::from_str(&content).map_err(|e| format!("Failed to parse config: {}", e))
}

/// 保存代理配置
pub fn save_proxy_config(config: &ProxyConfig) -> Result<(), String> {
    let config_path = get_config_dir().join("proxy.yml");

    let content =
        serde_yaml::to_string(config).map_err(|e| format!("Failed to serialize config: {}", e))?;

    fs::write(&config_path, content).map_err(|e| format!("Failed to write config: {}", e))
}

/// 读取 TUN 配置
pub fn load_tun_config() -> Result<TunConfig, String> {
    let config_path = get_config_dir().join("tun.toml");

    if !config_path.exists() {
        let default_config = get_default_tun_config();
        save_tun_config(&default_config)?;
        return Ok(default_config);
    }

    let content =
        fs::read_to_string(&config_path).map_err(|e| format!("Failed to read tun config: {}", e))?;

    toml::from_str(&content).map_err(|e| format!("Failed to parse tun config: {}", e))
}

/// 保存 TUN 配置
pub fn save_tun_config(config: &TunConfig) -> Result<(), String> {
    let config_path = get_config_dir().join("tun.toml");

    let content =
        toml::to_string_pretty(config).map_err(|e| format!("Failed to serialize tun config: {}", e))?;

    fs::write(&config_path, content).map_err(|e| format!("Failed to write tun config: {}", e))
}

/// 读取 TUN 配置为原始文本（供前端编辑器使用）
pub fn load_tun_config_raw() -> Result<String, String> {
    let config_path = get_config_dir().join("tun.toml");
    if !config_path.exists() {
        // 写入默认配置并返回
        let default_config = get_default_tun_config();
        save_tun_config(&default_config)?;
    }
    fs::read_to_string(get_config_dir().join("tun.toml"))
        .map_err(|e| format!("Failed to read tun.toml: {}", e))
}

/// 保存 TUN 配置原始文本
pub fn save_tun_config_raw(content: &str) -> Result<(), String> {
    // 先验证是合法 TOML
    let _: toml::Value = toml::from_str(content)
        .map_err(|e| format!("Invalid TOML: {}", e))?;

    let config_path = get_config_dir().join("tun.toml");
    fs::write(&config_path, content).map_err(|e| format!("Failed to write tun.toml: {}", e))
}

/// 默认代理配置模板（可直接连接）
fn get_default_proxy_config() -> ProxyConfig {
    ProxyConfig {
        local: LocalConfig {
            port: 1080,
            http_proxy_enabled: true,
            http_proxy_port: 1087,
        },
        remote: RemoteConfig {
            host: "54.234.196.30".to_string(),
            port: 9090,
            cipher: "chacha20".to_string(),
            key: String::new(),
        },
        route: RouteConfig {
            default_route: "proxy".to_string(),
            proxy_list: vec![
                "google.com".to_string(),
                "github.com".to_string(),
                "youtube.com".to_string(),
                "twitter.com".to_string(),
                "openai.com".to_string(),
                "anthropic.com".to_string(),
                "reddit.com".to_string(),
                "stackoverflow.com".to_string(),
                "docker.io".to_string(),
                "npmjs.com".to_string(),
                "cloudflare.com".to_string(),
            ],
            direct_list: vec![
                "meituan.com".to_string(),
                "sankuai.com".to_string(),
                "baidu.com".to_string(),
                "qq.com".to_string(),
                "weixin.qq.com".to_string(),
                "aliyun.com".to_string(),
                "taobao.com".to_string(),
                "jd.com".to_string(),
                "bilibili.com".to_string(),
                "zhihu.com".to_string(),
                "douyin.com".to_string(),
                "csdn.net".to_string(),
                "163.com".to_string(),
                "apple.com".to_string(),
                "localhost".to_string(),
            ],
        },
    }
}

/// 默认 TUN 配置模板
fn get_default_tun_config() -> TunConfig {
    TunConfig {
        tun: TunSection {
            name: "utun9".to_string(),
            address: "198.18.0.1".to_string(),
            netmask: Some("255.254.0.0".to_string()),
            mtu: 1500,
            enabled: true,
        },
        dns: DnsSection {
            listen: "127.0.0.1:53".to_string(),
            upstream: "8.8.8.8:53".to_string(),
        },
        proxy: TunProxySection {
            socks5: None,
            socks5_addr: Some("127.0.0.1:1080".to_string()),
        },
        routing: Some(RoutingSection {
            default_action: "proxy".to_string(),
            rules: vec![
                RoutingRule { rule_type: "domain_suffix".to_string(), value: "sankuai.com".to_string(), action: "direct".to_string() },
                RoutingRule { rule_type: "domain_suffix".to_string(), value: "meituan.com".to_string(), action: "direct".to_string() },
                RoutingRule { rule_type: "domain_suffix".to_string(), value: "cn".to_string(), action: "direct".to_string() },
                RoutingRule { rule_type: "ip_cidr".to_string(), value: "10.0.0.0/8".to_string(), action: "direct".to_string() },
                RoutingRule { rule_type: "ip_cidr".to_string(), value: "172.16.0.0/12".to_string(), action: "direct".to_string() },
                RoutingRule { rule_type: "ip_cidr".to_string(), value: "192.168.0.0/16".to_string(), action: "direct".to_string() },
            ],
        }),
        bypass: Some(BypassSection {
            proxy_remote_ips: vec!["54.234.196.30".to_string()],
            extra_cidrs: vec![
                "10.0.0.0/8".to_string(),
                "11.0.0.0/8".to_string(),
                "172.16.0.0/12".to_string(),
                "192.168.0.0/16".to_string(),
            ],
            dns_bypass_ips: vec!["114.114.114.114".to_string(), "223.5.5.5".to_string()],
        }),
    }
}
