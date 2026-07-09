use std::net::IpAddr;

use ipnet::IpNet;

use crate::error::{CoreError, Result};
use crate::mobile_config::RoutingConfig;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RouteAction {
    Proxy,
    Direct,
    Reject,
}

#[derive(Debug, Clone)]
pub struct ConnectionInfo {
    pub src_ip: IpAddr,
    pub dst_ip: IpAddr,
    pub dst_port: u16,
    pub domain: Option<String>,
    pub protocol: Protocol,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Protocol {
    Tcp,
    Udp,
}

trait Rule: Send + Sync {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction>;
    fn name(&self) -> &str;
}

pub struct Router {
    rules: Vec<Box<dyn Rule>>,
    default_action: RouteAction,
}

struct DomainPatternRule {
    pattern: String,
    action: RouteAction,
}

struct DomainSuffixRule {
    suffix: String,
    action: RouteAction,
}

struct DomainKeywordRule {
    keyword: String,
    action: RouteAction,
}

struct DomainFullRule {
    domain: String,
    action: RouteAction,
}

struct IpCidrRule {
    cidr: IpNet,
    action: RouteAction,
}

struct PortRule {
    port: u16,
    action: RouteAction,
}

impl Rule for DomainPatternRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        let domain = info.domain.as_deref()?.to_ascii_lowercase();
        if match_domain_pattern(&domain, &self.pattern) {
            Some(self.action.clone())
        } else {
            None
        }
    }

    fn name(&self) -> &str {
        &self.pattern
    }
}

impl Rule for DomainSuffixRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        let domain = info.domain.as_deref()?.to_ascii_lowercase();
        if domain == self.suffix || domain.ends_with(&format!(".{}", self.suffix)) {
            Some(self.action.clone())
        } else {
            None
        }
    }

    fn name(&self) -> &str {
        &self.suffix
    }
}

impl Rule for DomainKeywordRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        let domain = info.domain.as_deref()?.to_ascii_lowercase();
        if domain.contains(&self.keyword) {
            Some(self.action.clone())
        } else {
            None
        }
    }

    fn name(&self) -> &str {
        &self.keyword
    }
}

impl Rule for DomainFullRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        let domain = info.domain.as_deref()?.to_ascii_lowercase();
        if domain == self.domain {
            Some(self.action.clone())
        } else {
            None
        }
    }

    fn name(&self) -> &str {
        &self.domain
    }
}

impl Rule for IpCidrRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if self.cidr.contains(&info.dst_ip) {
            Some(self.action.clone())
        } else {
            None
        }
    }

    fn name(&self) -> &str {
        "ip_cidr"
    }
}

impl Rule for PortRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if info.dst_port == self.port {
            Some(self.action.clone())
        } else {
            None
        }
    }

    fn name(&self) -> &str {
        "port"
    }
}

impl Router {
    pub fn from_config(config: &RoutingConfig) -> Result<Self> {
        let default_action = parse_action(&config.default_action)?;
        let mut rules: Vec<Box<dyn Rule>> = Vec::new();

        for rule_cfg in &config.rules {
            let action = parse_action(&rule_cfg.action)?;
            let rule: Box<dyn Rule> = match rule_cfg.rule_type.as_str() {
                "domain_pattern" => Box::new(DomainPatternRule {
                    pattern: rule_cfg.value.to_ascii_lowercase(),
                    action,
                }),
                "domain_suffix" => Box::new(DomainSuffixRule {
                    suffix: normalize_domain_value(&rule_cfg.value),
                    action,
                }),
                "domain_keyword" => Box::new(DomainKeywordRule {
                    keyword: rule_cfg.value.to_ascii_lowercase(),
                    action,
                }),
                "domain_full" => Box::new(DomainFullRule {
                    domain: normalize_domain_value(&rule_cfg.value),
                    action,
                }),
                "ip_cidr" => {
                    let cidr = rule_cfg.value.parse::<IpNet>().map_err(|_| {
                        CoreError::InvalidArgument(format!(
                            "invalid ip_cidr route value: {}",
                            rule_cfg.value
                        ))
                    })?;
                    Box::new(IpCidrRule { cidr, action })
                }
                "port" => {
                    let port = rule_cfg.value.parse::<u16>().map_err(|_| {
                        CoreError::InvalidArgument(format!(
                            "invalid port route value: {}",
                            rule_cfg.value
                        ))
                    })?;
                    Box::new(PortRule { port, action })
                }
                other => {
                    return Err(CoreError::InvalidArgument(format!(
                        "unsupported route rule type: {other}"
                    )));
                }
            };
            rules.push(rule);
        }

        tracing::info!(
            "router initialized with {} rules, default={:?}",
            rules.len(),
            default_action
        );
        Ok(Self {
            rules,
            default_action,
        })
    }

    pub fn route(&self, info: &ConnectionInfo) -> RouteAction {
        for rule in &self.rules {
            if let Some(action) = rule.matches(info) {
                tracing::debug!(
                    "route match dst={:?}:{} domain={:?} -> {:?} ({})",
                    info.dst_ip,
                    info.dst_port,
                    info.domain,
                    action,
                    rule.name()
                );
                return action;
            }
        }
        self.default_action.clone()
    }
}

fn normalize_domain_value(value: &str) -> String {
    value
        .trim()
        .trim_start_matches("*.")
        .trim_start_matches('.')
        .to_ascii_lowercase()
}

fn match_domain_pattern(domain: &str, pattern: &str) -> bool {
    let pattern = normalize_domain_value(pattern);
    if pattern.is_empty() {
        return false;
    }
    domain == pattern || domain.ends_with(&format!(".{pattern}"))
}

fn parse_action(action: &str) -> Result<RouteAction> {
    match action.to_ascii_lowercase().as_str() {
        "proxy" => Ok(RouteAction::Proxy),
        "direct" => Ok(RouteAction::Direct),
        "reject" => Ok(RouteAction::Reject),
        other => Err(CoreError::InvalidArgument(format!(
            "unsupported route action: {other}"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use std::net::Ipv4Addr;

    use crate::mobile_config::RuleConfig;

    use super::*;

    fn info(domain: Option<&str>, ip: [u8; 4], port: u16) -> ConnectionInfo {
        ConnectionInfo {
            src_ip: IpAddr::V4(Ipv4Addr::new(198, 19, 255, 254)),
            dst_ip: IpAddr::V4(Ipv4Addr::from(ip)),
            dst_port: port,
            domain: domain.map(str::to_string),
            protocol: Protocol::Tcp,
        }
    }

    #[test]
    fn direct_rule_has_priority_by_order() {
        let router = Router::from_config(&RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![
                RuleConfig {
                    rule_type: "domain_pattern".to_string(),
                    value: "*.example.com".to_string(),
                    action: "direct".to_string(),
                },
                RuleConfig {
                    rule_type: "domain_keyword".to_string(),
                    value: "example".to_string(),
                    action: "proxy".to_string(),
                },
            ],
        })
        .unwrap();
        assert_eq!(
            router.route(&info(Some("api.example.com"), [198, 18, 0, 2], 443)),
            RouteAction::Direct
        );
    }

    #[test]
    fn cidr_rule_matches_without_domain() {
        let router = Router::from_config(&RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![RuleConfig {
                rule_type: "ip_cidr".to_string(),
                value: "10.0.0.0/8".to_string(),
                action: "direct".to_string(),
            }],
        })
        .unwrap();
        assert_eq!(router.route(&info(None, [10, 1, 2, 3], 80)), RouteAction::Direct);
        assert_eq!(router.route(&info(None, [8, 8, 8, 8], 53)), RouteAction::Proxy);
    }
}
