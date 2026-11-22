#[cfg(test)]
mod tests {
    use eroid_vpn::parser::*;
    use eroid_vpn::generator::*;
    use eroid_vpn::types::*;

    #[test]
    fn test_vless_reality() {
        let uri = "vless://96238b69-8254-47b8-bc23-c90ad7a17726@198.51.100.1:443?type=tcp&security=reality&pbk=x09l6bZ2h3eWd5jklmnopqrs_tuv-wxyz0123456789&fp=chrome&sni=yahoo.com&sid=abcdef0123456789&flow=xtls-rprx-vision#My-Reality";
        let node = parse_vless(uri).expect("Should parse vless");
        assert_eq!(node.protocol, "VLESS-Reality");
        assert_eq!(node.address, "198.51.100.1");
        assert_eq!(node.port, 443);
    }

    #[test]
    fn test_wireguard_and_amnezia() {
        let uri = "awg://aW5wdXRfcHJpdmF0ZV9rZXk=@198.51.100.3:51820?publickey=b3V0cHV0X3B1YmxpY19rZXk=&address=10.0.0.2/32&jc=4&jmin=40&jmax=70#AWG-Test";
        let node = parse_wireguard_or_awg(uri).expect("Should parse awg");
        assert_eq!(node.protocol, "AmneziaWG");
        assert_eq!(node.node_type, NodeType::Endpoint);
    }

    #[test]
    fn test_config_gen_with_geosite_and_geoip() {
        let uri = "vless://96238b69-8254-47b8-bc23-c90ad7a17726@198.51.100.1:443?type=tcp&security=reality&pbk=x09l6bZ2h3eWd5jklmnopqrs_tuv-wxyz0123456789#TestNode";
        let node = parse_vless(uri).unwrap();
        let core_cfg = CoreConfig::default();
        let cfg = generate_singbox_config(&node, RunMode::Tun, 2080, "8.8.8.8", &core_cfg);
        assert!(cfg.get("inbounds").is_some());
        assert_eq!(cfg["inbounds"][1]["type"], "tun");
        assert!(cfg["route"].get("rule_set").is_some());
    }

    #[test]
    #[ignore]
    fn test_fetch_live() {
        let res = fetch_and_parse_subscription("https://example.com/sub", "YOUR-HWID");
        println!("FETCH RESULT: {:?}", res.is_ok());
        if let Err(e) = res {
            println!("ERROR STRING: >>>{}<<<", e);
        } else if let Ok(s) = res {
            println!("SUCCESS! NODES LEN: {}, announce: {:?}, max_dev: {}", s.nodes.len(), s.announce, s.max_devices_reached);
            for n in s.nodes.iter().take(5) {
                println!("  Node: {} [{}] ({}:{})", n.name, n.protocol, n.address, n.port);
            }
        }
    }
}
