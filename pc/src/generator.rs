use serde_json::{json, Value};
use crate::types::{CoreConfig, Node, NodeType, RunMode};

pub fn generate_singbox_config(
    node: &Node,
    mode: RunMode,
    mixed_port: u16,
    dns_server: &str,
    core_cfg: &CoreConfig,
) -> Value {
    let mut node_config = node.config.clone();
    let node_tag = "proxy-out";
    node_config["tag"] = json!(node_tag);

    // Override uTLS fingerprint if applicable
    if let Some(tls) = node_config.get_mut("tls") {
        if let Some(utls) = tls.get_mut("utls") {
            utls["fingerprint"] = json!(core_cfg.utls_fingerprint);
        }
    }

    let mut inbounds = vec![
        json!({
            "type": "mixed",
            "tag": "mixed-in",
            "listen": "127.0.0.1",
            "listen_port": mixed_port
        })
    ];

    if mode == RunMode::Tun {
        inbounds.push(json!({
            "type": "tun",
            "tag": "tun-in",
            "interface_name": "singbox-tun",
            "address": [
                "172.19.0.1/30"
            ],
            "mtu": core_cfg.mtu,
            "auto_route": core_cfg.auto_route,
            "strict_route": core_cfg.strict_route,
            "stack": core_cfg.stack
        }));
    }

    let mut outbounds = vec![
        json!({
            "type": "direct",
            "tag": "direct"
        }),
        json!({
            "type": "block",
            "tag": "block"
        })
    ];

    let mut endpoints = Vec::new();

    match node.node_type {
        NodeType::Outbound => {
            outbounds.insert(0, node_config);
        }
        NodeType::Endpoint => {
            endpoints.push(node_config);
        }
    }

    let target_tag = node_tag;

    let inbound_tags = if mode == RunMode::Proxy {
        vec!["mixed-in"]
    } else {
        vec!["mixed-in", "tun-in"]
    };

    // Build Route Rules & Rule Sets (GeoSite / GeoIP)
    let mut route_rules = vec![
        json!({
            "protocol": "dns",
            "action": "hijack-dns"
        }),
        json!({
            "ip_is_private": true,
            "outbound": "direct"
        })
    ];

    // GeoSite RU direct routing via domain suffixes (offline, zero-dependency, immune to GitHub blocks/404s)
    if core_cfg.enable_geosite_ru {
        route_rules.push(json!({
            "domain_suffix": [
                ".ru",
                ".su",
                ".xn--p1ai",
                "vk.com",
                "vk.ru",
                "mail.ru",
                "yandex.ru",
                "ya.ru",
                "yandex.net",
                "yastatic.net",
                "yandex.com",
                "tbank.ru",
                "tinkoff.ru",
                "t-static.ru",
                "cdn-tinkoff.ru",
                "sberbank.ru",
                "sber.ru",
                "vtb.ru",
                "alfabank.ru",
                "alfa-bank.ru",
                "gosuslugi.ru",
                "nalog.gov.ru",
                "mos.ru",
                "avito.ru",
                "avito.st",
                "ozon.ru",
                "ozonusercontent.com",
                "wildberries.ru",
                "wb.ru",
                "wbpay.ru",
                "wbbasket.ru",
                "2gis.ru",
                "2gis.com",
                "kinopoisk.ru",
                "rutube.ru",
                "rzd.ru"
            ],
            "outbound": "direct"
        }));
    }

    // Custom bypass domains
    let custom_domains: Vec<&str> = core_cfg
        .custom_bypass_domains
        .split(',')
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect();

    if !custom_domains.is_empty() {
        route_rules.push(json!({
            "domain_suffix": custom_domains,
            "outbound": "direct"
        }));
    }

    // Default route through proxy
    route_rules.push(json!({
        "inbound": inbound_tags,
        "outbound": target_tag
    }));

    let route_obj = json!({
        "rules": route_rules,
        "auto_detect_interface": true,
        "default_domain_resolver": "dns-direct"
    });

    let mut config = json!({
        "log": {
            "level": core_cfg.log_level,
            "timestamp": true
        },
        "dns": {
            "servers": [
                {
                    "type": "udp",
                    "tag": "dns-remote",
                    "server": dns_server,
                    "detour": target_tag
                },
                {
                    "type": "udp",
                    "tag": "dns-direct",
                    "server": "77.88.8.8"
                }
            ],
            "strategy": "prefer_ipv4"
        },
        "inbounds": inbounds,
        "outbounds": outbounds,
        "route": route_obj
    });

    if !endpoints.is_empty() {
        config["endpoints"] = json!(endpoints);
    }

    config
}
