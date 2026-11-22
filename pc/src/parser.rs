use std::collections::HashMap;
use base64::{engine::general_purpose::{STANDARD, URL_SAFE}, Engine as _};
use url::Url;
use crate::types::{Node, NodeType};

pub fn safe_b64decode(input: &str) -> Option<String> {
    let clean = input.trim().replace('\r', "").replace('\n', "");
    let mut padded = clean.clone();
    while padded.len() % 4 != 0 {
        padded.push('=');
    }
    if let Ok(bytes) = STANDARD.decode(&padded) {
        if let Ok(s) = String::from_utf8(bytes) {
            return Some(s);
        }
    }
    if let Ok(bytes) = URL_SAFE.decode(&padded) {
        if let Ok(s) = String::from_utf8(bytes) {
            return Some(s);
        }
    }
    None
}

pub fn parse_vless(raw: &str) -> Option<Node> {
    let parsed = Url::parse(raw).ok()?;
    if parsed.scheme() != "vless" {
        return None;
    }

    let uuid = parsed.username().to_string();
    let host = parsed.host_str()?.to_string();
    let port = parsed.port().unwrap_or(443);
    let tag = parsed.fragment().map(|f| urlencoding_decode(f)).unwrap_or_else(|| format!("VLESS-{host}:{port}"));

    // Filter out dummy/stub provider warning nodes (e.g. 0.0.0.0:1)
    if host == "0.0.0.0" || uuid == "00000000-0000-0000-0000-000000000000" {
        return None;
    }

    let params: HashMap<String, String> = parsed.query_pairs().into_owned().collect();
    let sec = params.get("security").map(|s| s.as_str()).unwrap_or("none").to_lowercase();
    let flow = params.get("flow").cloned().unwrap_or_default();
    let sni = params.get("sni").or_else(|| params.get("peer")).cloned().unwrap_or_else(|| host.clone());
    let fp = params.get("fp").cloned().unwrap_or_else(|| "chrome".to_string());
    let pbk = params.get("pbk").cloned().unwrap_or_default();
    let sid = params.get("sid").cloned().unwrap_or_default();
    let net = params.get("type").map(|s| s.as_str()).unwrap_or("tcp").to_lowercase();
    let path = params.get("path").cloned().unwrap_or_else(|| "/".to_string());

    let mut outbound = serde_json::json!({
        "type": "vless",
        "tag": tag,
        "server": host,
        "server_port": port,
        "uuid": uuid
    });

    if !flow.is_empty() {
        outbound["flow"] = serde_json::Value::String(flow);
    }

    let mut is_reality = false;
    if sec == "tls" || sec == "reality" {
        let mut tls = serde_json::json!({
            "enabled": true,
            "server_name": sni,
            "utls": {
                "enabled": true,
                "fingerprint": fp
            }
        });
        if sec == "reality" {
            is_reality = true;
            tls["reality"] = serde_json::json!({
                "enabled": true,
                "public_key": pbk,
                "short_id": sid
            });
        }
        outbound["tls"] = tls;
    }

    if net == "ws" {
        outbound["transport"] = serde_json::json!({
            "type": "ws",
            "path": path,
            "headers": {
                "Host": sni
            }
        });
    }

    Some(Node {
        name: tag.clone(),
        node_type: NodeType::Outbound,
        protocol: if is_reality { "VLESS-Reality".to_string() } else { "VLESS".to_string() },
        config: outbound,
        address: host,
        port,
        ping: None,
    })
}

pub fn parse_trojan(raw: &str) -> Option<Node> {
    let parsed = Url::parse(raw).ok()?;
    if parsed.scheme() != "trojan" {
        return None;
    }

    let password = parsed.username().to_string();
    let host = parsed.host_str()?.to_string();
    let port = parsed.port().unwrap_or(443);
    let tag = parsed.fragment().map(|f| urlencoding_decode(f)).unwrap_or_else(|| format!("Trojan-{host}:{port}"));

    if host == "0.0.0.0" {
        return None;
    }

    let params: HashMap<String, String> = parsed.query_pairs().into_owned().collect();
    let sni = params.get("sni").or_else(|| params.get("peer")).cloned().unwrap_or_else(|| host.clone());

    let outbound = serde_json::json!({
        "type": "trojan",
        "tag": tag,
        "server": host,
        "server_port": port,
        "password": password,
        "tls": {
            "enabled": true,
            "server_name": sni
        }
    });

    Some(Node {
        name: tag,
        node_type: NodeType::Outbound,
        protocol: "Trojan".to_string(),
        config: outbound,
        address: host,
        port,
        ping: None,
    })
}

pub fn parse_shadowsocks(raw: &str) -> Option<Node> {
    let clean = raw.strip_prefix("ss://")?;
    let mut parts = clean.splitn(2, '#');
    let main_part = parts.next()?;
    let tag = parts.next().map(urlencoding_decode).unwrap_or_default();

    let (user_info, host, port) = if let Some((user_info, host_port)) = main_part.split_once('@') {
        let (h, p_str) = host_port.split_once(':')?;
        let p: u16 = p_str.parse().ok()?;
        (user_info.to_string(), h.to_string(), p)
    } else {
        let decoded = safe_b64decode(main_part)?;
        let (user_info, host_port) = decoded.split_once('@')?;
        let (h, p_str) = host_port.split_once(':')?;
        let p: u16 = p_str.parse().ok()?;
        (user_info.to_string(), h.to_string(), p)
    };

    if host == "0.0.0.0" {
        return None;
    }

    let (method, password) = if let Some(decoded_user) = safe_b64decode(&user_info) {
        if let Some((m, pw)) = decoded_user.split_once(':') {
            (m.to_string(), pw.to_string())
        } else {
            return None;
        }
    } else if let Some((m, pw)) = user_info.split_once(':') {
        (m.to_string(), pw.to_string())
    } else {
        return None;
    };

    let final_tag = if tag.is_empty() { format!("SS-{host}:{port}") } else { tag };

    let outbound = serde_json::json!({
        "type": "shadowsocks",
        "tag": final_tag,
        "server": host,
        "server_port": port,
        "method": method,
        "password": password
    });

    Some(Node {
        name: final_tag,
        node_type: NodeType::Outbound,
        protocol: "Shadowsocks".to_string(),
        config: outbound,
        address: host,
        port,
        ping: None,
    })
}

pub fn parse_wireguard_or_awg(raw: &str) -> Option<Node> {
    let parsed = Url::parse(raw).ok()?;
    let scheme = parsed.scheme().to_lowercase();
    if scheme != "wireguard" && scheme != "awg" && scheme != "wg" {
        return None;
    }

    let private_key = parsed.username().to_string();
    let host = parsed.host_str()?.to_string();
    let port = parsed.port().unwrap_or(51820);
    let tag = parsed.fragment().map(|f| urlencoding_decode(f)).unwrap_or_else(|| format!("WG-{host}:{port}"));

    if host == "0.0.0.0" {
        return None;
    }

    let params: HashMap<String, String> = parsed.query_pairs().into_owned().collect();
    let public_key = params.get("publickey").or_else(|| params.get("peer_public_key")).or_else(|| params.get("pubkey")).cloned().unwrap_or_default();
    let preshared_key = params.get("presharedkey").or_else(|| params.get("psk")).cloned();
    let address = params.get("address").or_else(|| params.get("ip")).cloned().unwrap_or_else(|| "10.0.0.2/32".to_string());
    let mtu: u32 = params.get("mtu").and_then(|m| m.parse().ok()).unwrap_or(1420);

    let is_amnezia = scheme == "awg" || params.contains_key("jc") || params.contains_key("jmin");

    let mut peer = serde_json::json!({
        "address": host,
        "port": port,
        "public_key": public_key,
        "allowed_ips": ["0.0.0.0/0", "::/0"]
    });

    if let Some(psk) = preshared_key {
        peer["pre_shared_key"] = serde_json::Value::String(psk);
    }

    let mut endpoint = serde_json::json!({
        "type": "wireguard",
        "tag": tag,
        "system": false,
        "address": [address],
        "private_key": private_key,
        "mtu": mtu,
        "peers": [peer]
    });

    if let Some(jc) = params.get("jc").and_then(|v| v.parse::<i64>().ok()) {
        endpoint["junk_packet_count"] = serde_json::Value::Number(jc.into());
    }
    if let Some(jmin) = params.get("jmin").and_then(|v| v.parse::<i64>().ok()) {
        endpoint["junk_packet_min_size"] = serde_json::Value::Number(jmin.into());
    }
    if let Some(jmax) = params.get("jmax").and_then(|v| v.parse::<i64>().ok()) {
        endpoint["junk_packet_max_size"] = serde_json::Value::Number(jmax.into());
    }

    Some(Node {
        name: tag,
        node_type: NodeType::Endpoint,
        protocol: if is_amnezia { "AmneziaWG".to_string() } else { "WireGuard".to_string() },
        config: endpoint,
        address: host,
        port,
        ping: None,
    })
}

pub fn parse_link(line: &str) -> Option<Node> {
    let t = line.trim();
    if t.starts_with("vless://") {
        parse_vless(t)
    } else if t.starts_with("trojan://") {
        parse_trojan(t)
    } else if t.starts_with("ss://") {
        parse_shadowsocks(t)
    } else if t.starts_with("wireguard://") || t.starts_with("awg://") || t.starts_with("wg://") {
        parse_wireguard_or_awg(t)
    } else {
        None
    }
}

pub struct SubscriptionResult {
    pub nodes: Vec<Node>,
    pub announce: Option<String>,
    pub max_devices_reached: bool,
}

pub fn fetch_and_parse_subscription(input: &str, hwid: &str) -> Result<SubscriptionResult, String> {
    let mut announce = None;
    let mut max_devices_reached = false;

    let content = if input.starts_with("http://") || input.starts_with("https://") {
        let resp = ureq::get(input)
            .set("User-Agent", "INCY/4.0.1 (Windows NT 10.0; Win64; x64)")
            .set("x-hwid", hwid)
            .set("x-device-os", "Windows")
            .set("x-device-model", "PC")
            .set("x-ver-os", "10.0.19045")
            .timeout(std::time::Duration::from_secs(15))
            .call()
            .map_err(|e| format!("HTTP request failed: {e}"))?;

        if let Some(ann) = resp.header("announce") {
            if let Some(stripped) = ann.strip_prefix("base64:") {
                if let Some(decoded) = safe_b64decode(stripped) {
                    announce = Some(decoded);
                }
            } else {
                announce = Some(ann.to_string());
            }
        }

        if let Some(val) = resp.header("x-hwid-max-devices-reached") {
            if val.eq_ignore_ascii_case("true") {
                max_devices_reached = true;
            }
        }

        resp.into_string().map_err(|e| format!("Failed to read response body: {e}"))?
    } else {
        input.to_string()
    };

    let mut nodes = Vec::new();

    // 0. Try Remnawave / INCY / Happ JSON config array
    if content.trim().starts_with('[') {
        let json_nodes = parse_remnawave_json(&content);
        if !json_nodes.is_empty() {
            return Ok(SubscriptionResult {
                nodes: json_nodes,
                announce,
                max_devices_reached: false,
            });
        }
    }

    // 1. Try Clash YAML
    if content.contains("proxies:") {
        if let Ok(yaml_val) = serde_yaml::from_str::<serde_yaml::Value>(&content) {
            if let Some(proxies) = yaml_val.get("proxies").and_then(|p| p.as_sequence()) {
                for p in proxies {
                    let p_type = p.get("type").and_then(|t| t.as_str()).unwrap_or("").to_lowercase();
                    let name = p.get("name").and_then(|n| n.as_str()).unwrap_or("Proxy").to_string();
                    let server = p.get("server").and_then(|s| s.as_str()).unwrap_or("").to_string();
                    let port = p.get("port").and_then(|pt| pt.as_u64()).unwrap_or(443) as u16;

                    if server == "0.0.0.0" {
                        continue;
                    }

                    if p_type == "vless" {
                        let uuid = p.get("uuid").and_then(|u| u.as_str()).unwrap_or("").to_string();
                        let reality = p.get("reality-opts");
                        let mut outbound = serde_json::json!({
                            "type": "vless",
                            "tag": name,
                            "server": server,
                            "server_port": port,
                            "uuid": uuid
                        });
                        let mut is_reality = false;
                        if let Some(ropts) = reality {
                            is_reality = true;
                            let pbk = ropts.get("public-key").and_then(|k| k.as_str()).unwrap_or("");
                            let sid = ropts.get("short-id").and_then(|k| k.as_str()).unwrap_or("");
                            outbound["tls"] = serde_json::json!({
                                "enabled": true,
                                "server_name": p.get("servername").and_then(|s| s.as_str()).unwrap_or(&server),
                                "utls": {
                                    "enabled": true,
                                    "fingerprint": "chrome"
                                },
                                "reality": {
                                    "enabled": true,
                                    "public_key": pbk,
                                    "short_id": sid
                                }
                            });
                        }
                        nodes.push(Node {
                            name,
                            node_type: NodeType::Outbound,
                            protocol: if is_reality { "VLESS-Reality".to_string() } else { "VLESS".to_string() },
                            config: outbound,
                            address: server,
                            port,
                            ping: None,
                        });
                    }
                }
                if !nodes.is_empty() {
                    return Ok(SubscriptionResult { nodes, announce, max_devices_reached });
                }
            }
        }
    }

    // 2. Try Base64 or plain lines
    let text_to_parse = if let Some(decoded) = safe_b64decode(&content) {
        if decoded.contains("://") {
            decoded
        } else {
            content.clone()
        }
    } else {
        content
    };

    if text_to_parse.contains("Лимит устройств")
        || text_to_parse.contains("максимального количества устройств")
        || text_to_parse.contains("X-Hwid-Max-Devices-Reached")
    {
        max_devices_reached = true;
    }

    for line in text_to_parse.lines() {
        if let Some(node) = parse_link(line) {
            nodes.push(node);
        }
    }

    if nodes.is_empty() {
        if max_devices_reached {
            let details = announce.unwrap_or_else(|| {
                "⚠️ Вы достигли максимального количества устройств на этой подписке (лимит 1/1)!".to_string()
            });
            Err(format!(
                "Достигнут лимит устройств (1/1) у вашего провайдера!\n\
                 {details}\n\n\
                 Как исправить:\n\
                 1. Перейдите в Telegram-бота провайдера: @ruvpn (или @ruvpnhelpbot)\n\
                 2. Нажмите кнопку «Сбросить устройства» (или «Отвязать устройства»)\n\
                 3. Вернитесь сюда и нажмите «Обновить серверы» — подписка привяжется к этому ПК!"
            ))
        } else if text_to_parse.contains("Приложение не подходит") {
            Err("Провайдер требует эмуляцию клиента Happ или Incy. Проверьте заголовки User-Agent.".to_string())
        } else {
            Err("Сервер не вернул доступных узлов для подключения. Проверьте правильность ссылки на подписку.".to_string())
        }
    } else {
        Ok(SubscriptionResult { nodes, announce, max_devices_reached })
    }
}

fn urlencoding_decode(s: &str) -> String {
    url::form_urlencoded::parse(s.as_bytes())
        .map(|(k, _)| k.into_owned())
        .collect::<Vec<_>>()
        .join("")
}

pub fn parse_remnawave_json(content: &str) -> Vec<Node> {
    let mut nodes = Vec::new();
    let val: serde_json::Value = match serde_json::from_str(content) {
        Ok(v) => v,
        Err(_) => return nodes,
    };

    let items = match val.as_array() {
        Some(arr) => arr,
        None => return nodes,
    };

    for item in items {
        let remarks = item.get("remarks").and_then(|r| r.as_str()).unwrap_or("Proxy").to_string();
        let outbounds = match item.get("outbounds").and_then(|o| o.as_array()) {
            Some(obs) => obs,
            None => continue,
        };

        for ob in outbounds {
            let proto = ob.get("protocol").or_else(|| ob.get("type")).and_then(|p| p.as_str()).unwrap_or("").to_lowercase();
            if proto == "vless" {
                let mut server = String::new();
                let mut port = 443u16;
                let mut uuid = String::new();
                let mut flow = String::new();

                if let Some(vnext) = ob.get("settings").and_then(|s| s.get("vnext")).and_then(|v| v.as_array()).and_then(|a| a.first()) {
                    server = vnext.get("address").and_then(|a| a.as_str()).unwrap_or("").to_string();
                    port = vnext.get("port").and_then(|p| p.as_u64()).unwrap_or(443) as u16;
                    if let Some(user) = vnext.get("users").and_then(|u| u.as_array()).and_then(|a| a.first()) {
                        uuid = user.get("id").and_then(|i| i.as_str()).unwrap_or("").to_string();
                        flow = user.get("flow").and_then(|f| f.as_str()).unwrap_or("").to_string();
                    }
                } else if let Some(srv) = ob.get("server").and_then(|s| s.as_str()) {
                    server = srv.to_string();
                    port = ob.get("server_port").or_else(|| ob.get("port")).and_then(|p| p.as_u64()).unwrap_or(443) as u16;
                    uuid = ob.get("uuid").and_then(|u| u.as_str()).unwrap_or("").to_string();
                    flow = ob.get("flow").and_then(|f| f.as_str()).unwrap_or("").to_string();
                }

                if server.is_empty() || server == "0.0.0.0" {
                    continue;
                }

                let mut outbound = serde_json::json!({
                    "type": "vless",
                    "tag": remarks,
                    "server": server,
                    "server_port": port,
                    "uuid": uuid
                });

                if !flow.is_empty() {
                    outbound["flow"] = serde_json::Value::String(flow);
                }

                let stream = ob.get("streamSettings");
                let security = stream.and_then(|s| s.get("security")).and_then(|sec| sec.as_str()).unwrap_or("none").to_lowercase();
                let mut is_reality = false;

                if security == "reality" {
                    is_reality = true;
                    let reality = stream.and_then(|s| s.get("realitySettings"));
                    let server_name = reality.and_then(|r| r.get("serverName")).and_then(|s| s.as_str()).unwrap_or(&server);
                    let public_key = reality.and_then(|r| r.get("publicKey")).and_then(|k| k.as_str()).unwrap_or("");
                    let short_id = reality.and_then(|r| r.get("shortId")).and_then(|s| s.as_str()).unwrap_or("");
                    let fingerprint = reality.and_then(|r| r.get("fingerprint")).and_then(|f| f.as_str()).unwrap_or("chrome");

                    outbound["tls"] = serde_json::json!({
                        "enabled": true,
                        "server_name": server_name,
                        "utls": {
                            "enabled": true,
                            "fingerprint": fingerprint
                        },
                        "reality": {
                            "enabled": true,
                            "public_key": public_key,
                            "short_id": short_id
                        }
                    });
                } else if security == "tls" {
                    let tls_settings = stream.and_then(|s| s.get("tlsSettings"));
                    let server_name = tls_settings.and_then(|t| t.get("serverName")).and_then(|s| s.as_str()).unwrap_or(&server);
                    let fingerprint = tls_settings.and_then(|t| t.get("fingerprint")).and_then(|f| f.as_str()).unwrap_or("chrome");
                    outbound["tls"] = serde_json::json!({
                        "enabled": true,
                        "server_name": server_name,
                        "utls": {
                            "enabled": true,
                            "fingerprint": fingerprint
                        }
                    });
                }

                nodes.push(Node {
                    name: remarks.clone(),
                    node_type: NodeType::Outbound,
                    protocol: if is_reality { "VLESS-Reality".to_string() } else { "VLESS".to_string() },
                    config: outbound,
                    address: server,
                    port,
                    ping: None,
                });
                break;
            }
        }
    }
    nodes
}
