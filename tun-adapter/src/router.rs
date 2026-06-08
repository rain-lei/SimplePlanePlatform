//! 路由决策引擎模块
//!
//! 基于域名后缀、域名关键词、IP CIDR 和端口进行路由决策，
//! 决定每个连接走代理、直连还是拦截。

use std::net::IpAddr;

use ipnet::IpNet;

use crate::config::RoutingConfig;
use crate::error::RouterError;

/// 路由动作
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RouteAction {
    /// 走 SOCKS5 代理 → proxy-local
    Proxy,
    /// 直连（bypass，从物理网卡出去）
    Direct,
    /// 丢弃（黑洞）
    Reject,
}

/// 连接信息，用于路由决策
#[derive(Debug, Clone)]
pub struct ConnectionInfo {
    /// 源 IP 地址
    pub src_ip: IpAddr,
    /// 目标 IP 地址
    pub dst_ip: IpAddr,
    /// 目标端口
    pub dst_port: u16,
    /// 域名（如果有，从 FakeDNS 反查得到）
    pub domain: Option<String>,
    /// 协议类型
    pub protocol: Protocol,
}

/// 协议类型
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Protocol {
    Tcp,
    Udp,
}

/// 路由规则 trait
pub trait Rule: Send + Sync {
    /// 判断是否匹配，返回匹配后的动作
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction>;
    /// 规则名称（用于日志和调试）
    fn name(&self) -> &str;
}

/// 路由引擎
pub struct Router {
    /// 有序规则列表
    rules: Vec<Box<dyn Rule>>,
    /// 默认动作（所有规则都不匹配时使用）
    default_action: RouteAction,
}

// ===== 规则实现 =====

/// 域名后缀匹配规则
struct DomainSuffixRule {
    suffix: String,
    action: RouteAction,
}

/// 域名关键词匹配规则
struct DomainKeywordRule {
    keyword: String,
    action: RouteAction,
}

/// 域名精确匹配规则
struct DomainFullRule {
    domain: String,
    action: RouteAction,
}

/// IP CIDR 匹配规则
struct IpCidrRule {
    cidr: IpNet,
    action: RouteAction,
}

/// 端口匹配规则
struct PortRule {
    port: u16,
    action: RouteAction,
}

impl Rule for DomainSuffixRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if let Some(domain) = &info.domain {
            let domain_lower = domain.to_lowercase();
            // 匹配完整后缀：.cn 匹配 www.baidu.cn，cn 匹配所有 .cn 域名
            if domain_lower.ends_with(&format!(".{}", self.suffix))
                || domain_lower == self.suffix
            {
                return Some(self.action.clone());
            }
        }
        None
    }

    fn name(&self) -> &str {
        &self.suffix
    }
}

impl Rule for DomainKeywordRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if let Some(domain) = &info.domain {
            if domain.to_lowercase().contains(&self.keyword) {
                return Some(self.action.clone());
            }
        }
        None
    }

    fn name(&self) -> &str {
        &self.keyword
    }
}

impl Rule for DomainFullRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if let Some(domain) = &info.domain {
            if domain.to_lowercase() == self.domain {
                return Some(self.action.clone());
            }
        }
        None
    }

    fn name(&self) -> &str {
        &self.domain
    }
}

impl Rule for IpCidrRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if self.cidr.contains(&info.dst_ip) {
            return Some(self.action.clone());
        }
        None
    }

    fn name(&self) -> &str {
        "ip_cidr"
    }
}

impl Rule for PortRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if info.dst_port == self.port {
            return Some(self.action.clone());
        }
        None
    }

    fn name(&self) -> &str {
        "port"
    }
}

impl Router {
    /// 从配置构建路由引擎
    pub fn from_config(routing_config: &RoutingConfig) -> Result<Self, RouterError> {
        let default_action = parse_action(&routing_config.default_action)?;
        let mut rules: Vec<Box<dyn Rule>> = Vec::new();

        for rule_cfg in &routing_config.rules {
            let action = parse_action(&rule_cfg.action)?;
            let rule: Box<dyn Rule> = match rule_cfg.rule_type.as_str() {
                "domain_suffix" => Box::new(DomainSuffixRule {
                    suffix: rule_cfg.value.to_lowercase(),
                    action,
                }),
                "domain_keyword" => Box::new(DomainKeywordRule {
                    keyword: rule_cfg.value.to_lowercase(),
                    action,
                }),
                "domain_full" => Box::new(DomainFullRule {
                    domain: rule_cfg.value.to_lowercase(),
                    action,
                }),
                "ip_cidr" => {
                    let cidr: IpNet = rule_cfg
                        .value
                        .parse()
                        .map_err(|_| RouterError::InvalidCidr(rule_cfg.value.clone()))?;
                    Box::new(IpCidrRule { cidr, action })
                }
                "port" => {
                    let port: u16 = rule_cfg
                        .value
                        .parse()
                        .map_err(|_| RouterError::InvalidRuleType(format!("invalid port: {}", rule_cfg.value)))?;
                    Box::new(PortRule { port, action })
                }
                other => {
                    return Err(RouterError::InvalidRuleType(other.to_string()));
                }
            };
            rules.push(rule);
        }

        tracing::info!("Router initialized with {} rules, default: {:?}", rules.len(), default_action);
        Ok(Self { rules, default_action })
    }

    /// 对连接信息进行路由决策（first-match-wins）
    pub fn route(&self, info: &ConnectionInfo) -> RouteAction {
        for rule in &self.rules {
            if let Some(action) = rule.matches(info) {
                tracing::debug!(
                    "Route match: {:?}:{} (domain={:?}) -> {:?} (rule: {})",
                    info.dst_ip, info.dst_port, info.domain, action, rule.name()
                );
                return action;
            }
        }

        tracing::debug!(
            "Route no match: {:?}:{} (domain={:?}) -> {:?} (default)",
            info.dst_ip, info.dst_port, info.domain, self.default_action
        );
        self.default_action.clone()
    }
}

/// 解析路由动作字符串
fn parse_action(action: &str) -> Result<RouteAction, RouterError> {
    match action.to_lowercase().as_str() {
        "proxy" => Ok(RouteAction::Proxy),
        "direct" => Ok(RouteAction::Direct),
        "reject" => Ok(RouteAction::Reject),
        other => Err(RouterError::InvalidAction(other.to_string())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{RoutingConfig, RuleConfig};
    use std::net::Ipv4Addr;

    fn make_info(dst_ip: IpAddr, dst_port: u16, domain: Option<&str>) -> ConnectionInfo {
        ConnectionInfo {
            src_ip: IpAddr::V4(Ipv4Addr::new(192, 168, 1, 100)),
            dst_ip,
            dst_port,
            domain: domain.map(|s| s.to_string()),
            protocol: Protocol::Tcp,
        }
    }

    #[test]
    fn test_domain_suffix_rule() {
        let config = RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![RuleConfig {
                rule_type: "domain_suffix".to_string(),
                value: "cn".to_string(),
                action: "direct".to_string(),
            }],
        };

        let router = Router::from_config(&config).unwrap();

        // *.cn 域名走直连
        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(198, 18, 0, 5)),
            80,
            Some("www.baidu.cn"),
        );
        assert_eq!(router.route(&info), RouteAction::Direct);

        // 非 .cn 域名走代理
        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(198, 18, 0, 6)),
            443,
            Some("www.google.com"),
        );
        assert_eq!(router.route(&info), RouteAction::Proxy);
    }

    #[test]
    fn test_domain_keyword_rule() {
        let config = RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![RuleConfig {
                rule_type: "domain_keyword".to_string(),
                value: "google".to_string(),
                action: "proxy".to_string(),
            }],
        };

        let router = Router::from_config(&config).unwrap();

        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(198, 18, 0, 5)),
            443,
            Some("www.google.com"),
        );
        assert_eq!(router.route(&info), RouteAction::Proxy);
    }

    #[test]
    fn test_ip_cidr_rule() {
        let config = RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![RuleConfig {
                rule_type: "ip_cidr".to_string(),
                value: "192.168.0.0/16".to_string(),
                action: "direct".to_string(),
            }],
        };

        let router = Router::from_config(&config).unwrap();

        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(192, 168, 1, 100)),
            80,
            None,
        );
        assert_eq!(router.route(&info), RouteAction::Direct);

        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)),
            53,
            None,
        );
        assert_eq!(router.route(&info), RouteAction::Proxy);
    }

    #[test]
    fn test_rule_priority_first_match_wins() {
        let config = RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![
                RuleConfig {
                    rule_type: "domain_full".to_string(),
                    value: "special.google.com".to_string(),
                    action: "direct".to_string(),
                },
                RuleConfig {
                    rule_type: "domain_keyword".to_string(),
                    value: "google".to_string(),
                    action: "proxy".to_string(),
                },
            ],
        };

        let router = Router::from_config(&config).unwrap();

        // 精确匹配优先
        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(198, 18, 0, 5)),
            443,
            Some("special.google.com"),
        );
        assert_eq!(router.route(&info), RouteAction::Direct);

        // 非精确匹配走第二条规则
        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(198, 18, 0, 6)),
            443,
            Some("www.google.com"),
        );
        assert_eq!(router.route(&info), RouteAction::Proxy);
    }

    #[test]
    fn test_no_domain_fallback_to_ip() {
        let config = RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![
                RuleConfig {
                    rule_type: "domain_suffix".to_string(),
                    value: "cn".to_string(),
                    action: "direct".to_string(),
                },
                RuleConfig {
                    rule_type: "ip_cidr".to_string(),
                    value: "10.0.0.0/8".to_string(),
                    action: "direct".to_string(),
                },
            ],
        };

        let router = Router::from_config(&config).unwrap();

        // 无域名但 IP 匹配
        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(10, 1, 1, 1)),
            80,
            None,
        );
        assert_eq!(router.route(&info), RouteAction::Direct);

        // 无域名且 IP 不匹配，走默认
        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)),
            80,
            None,
        );
        assert_eq!(router.route(&info), RouteAction::Proxy);
    }

    #[test]
    fn test_default_action() {
        let config = RoutingConfig {
            default_action: "reject".to_string(),
            rules: vec![],
        };

        let router = Router::from_config(&config).unwrap();

        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)),
            80,
            Some("unknown.example.com"),
        );
        assert_eq!(router.route(&info), RouteAction::Reject);
    }
}