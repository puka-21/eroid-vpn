package com.eroid.vpn.core

import com.eroid.vpn.model.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File

class ConfigGenerator {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun generateJson(node: Node, core: CoreConfig, rulesDir: File? = null): String {
        val root = JsonObject()

        val geoipFile = rulesDir?.let { File(it, "geoip-ru.srs") }
        val geositeFile = rulesDir?.let { File(it, "geosite-category-ru.srs") }
        val hasGeoip = core.enableGeoIpRu && geoipFile?.exists() == true
        val hasGeosite = core.enableGeoSiteRu && geositeFile?.exists() == true

        // 1. Log
        val log = JsonObject().apply {
            addProperty("level", "info")
            addProperty("timestamp", true)
        }
        root.add("log", log)

        // 2. DNS
        val dns = JsonObject().apply {
            val servers = JsonArray().apply {
                // dns-remote — foreign domains via proxy
                add(JsonObject().apply {
                    addProperty("type", "udp")
                    addProperty("tag", "dns-remote")
                    addProperty("server", core.dnsRemote.ifBlank { "8.8.8.8" })
                    addProperty("detour", "proxy")
                })
                // dns-direct — Russian domains via physical NIC (Yandex DNS 77.88.8.8)
                add(JsonObject().apply {
                    addProperty("type", "udp")
                    addProperty("tag", "dns-direct")
                    addProperty("server", core.dnsDirect.ifBlank { "77.88.8.8" })
                })
            }
            add("servers", servers)

            val dnsRules = JsonArray()

            // 2ip.ru -> dns-remote (so user sees their proxy IP on 2ip.ru)
            dnsRules.add(JsonObject().apply {
                val domains = JsonArray().apply { add("2ip.ru") }
                add("domain_suffix", domains)
                addProperty("server", "dns-remote")
            })

            // Push notifications -> dns-direct
            dnsRules.add(JsonObject().apply {
                val domains = JsonArray().apply {
                    add("mtalk.google.com")
                    add("push.apple.com")
                    add("api.push.apple.com")
                }
                add("domain_suffix", domains)
                addProperty("server", "dns-direct")
            })

            // Rule 0: VPN server address → dns-direct to break the chicken-and-egg loop.
            // Only needed if address is a hostname (not an IP — IPs don't need DNS resolution).
            val isIpAddress = node.address.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
            if (node.address.isNotBlank() && !isIpAddress) {
                dnsRules.add(JsonObject().apply {
                    val domains = JsonArray().apply {
                        // Always add the exact server address
                        add(node.address)
                        // Also add its parent domain (e.g. rutrust.org for se.rutrust.org)
                        val parts = node.address.split(".")
                        if (parts.size >= 2) {
                            add(parts.takeLast(2).joinToString("."))
                        }
                    }
                    add("domain", domains)
                    addProperty("server", "dns-direct")
                })
            }

            if (hasGeosite) {
                dnsRules.add(JsonObject().apply {
                    addProperty("rule_set", "geosite-ru")
                    addProperty("server", "dns-direct")
                })
            }

            if (core.enableGeoSiteRu) {
                dnsRules.add(JsonObject().apply {
                    val domains = JsonArray().apply {
                        listOf(
                            ".ru", ".su", ".xn--p1ai", "vk.com", "vk.ru", "mail.ru",
                            "yandex.ru", "ya.ru", "yandex.net", "yastatic.net", "yandex.com",
                            "tbank.ru", "tinkoff.ru", "t-static.ru", "cdn-tinkoff.ru",
                            "sberbank.ru", "sber.ru", "vtb.ru", "alfabank.ru", "alfa-bank.ru",
                            "gosuslugi.ru", "nalog.gov.ru", "mos.ru", "avito.ru", "avito.st",
                            "ozon.ru", "ozonusercontent.com", "wildberries.ru", "wb.ru",
                            "wbpay.ru", "wbbasket.ru", "2gis.ru", "2gis.com", "kinopoisk.ru",
                            "rutube.ru", "rzd.ru"
                        ).forEach { add(it) }
                    }
                    add("domain_suffix", domains)
                    addProperty("server", "dns-direct")
                })
            }
            if (core.customBypassDomains.isNotEmpty()) {
                dnsRules.add(JsonObject().apply {
                    val domains = JsonArray()
                    core.customBypassDomains.forEach { domains.add(it) }
                    add("domain_suffix", domains)
                    addProperty("server", "dns-direct")
                })
            }
            add("rules", dnsRules)
            addProperty("final", "dns-remote")
            addProperty("strategy", "ipv4_only")
        }
        root.add("dns", dns)

        // 3. Inbounds
        val inbounds = JsonArray()
        val tun = JsonObject().apply {
            addProperty("type", "tun")
            addProperty("tag", "tun-in")
            val addr = JsonArray().apply {
                add("172.19.0.1/30")
            }
            add("address", addr)
            addProperty("mtu", core.mtu)
            val stackName = if (core.stack.equals("mixed", ignoreCase = true) || core.stack.isBlank()) "gvisor" else core.stack
            addProperty("stack", stackName)
            addProperty("auto_route", true)
            addProperty("strict_route", false)
        }
        inbounds.add(tun)
        root.add("inbounds", inbounds)

        // 4. Outbounds
        val outbounds = JsonArray()
        outbounds.add(buildNodeOutbound(node, core))

        // Direct outbound
        outbounds.add(JsonObject().apply {
            addProperty("type", "direct")
            addProperty("tag", "direct")
        })

        // Block outbound
        outbounds.add(JsonObject().apply {
            addProperty("type", "block")
            addProperty("tag", "block")
        })
        root.add("outbounds", outbounds)

        // 5. Route
        val route = JsonObject().apply {
            addProperty("auto_detect_interface", false)
            val defaultResolver = JsonObject().apply {
                addProperty("server", "dns-direct")
                addProperty("strategy", "ipv4_only")
            }
            add("default_domain_resolver", defaultResolver)

            if (hasGeoip || hasGeosite) {
                val ruleSets = JsonArray()
                if (hasGeoip) {
                    ruleSets.add(JsonObject().apply {
                        addProperty("tag", "geoip-ru")
                        addProperty("type", "local")
                        addProperty("format", "binary")
                        addProperty("path", geoipFile!!.absolutePath)
                    })
                }
                if (hasGeosite) {
                    ruleSets.add(JsonObject().apply {
                        addProperty("tag", "geosite-ru")
                        addProperty("type", "local")
                        addProperty("format", "binary")
                        addProperty("path", geositeFile!!.absolutePath)
                    })
                }
                add("rule_set", ruleSets)
            }

            val rules = JsonArray()

            // Sniff with 300ms timeout
            rules.add(JsonObject().apply {
                addProperty("action", "sniff")
                addProperty("timeout", "300ms")
            })

            // Hijack DNS
            rules.add(JsonObject().apply {
                addProperty("protocol", "dns")
                addProperty("action", "hijack-dns")
            })
            rules.add(JsonObject().apply {
                addProperty("port", 53)
                addProperty("action", "hijack-dns")
            })

            // Reject all IPv6 traffic immediately — nodes are IPv4-only
            rules.add(JsonObject().apply {
                addProperty("ip_version", 6)
                addProperty("action", "reject")
                addProperty("method", "default")
            })

            // Block QUIC (HTTP/3 via UDP 443) to force browsers to use TCP/TLS HTTP/2
            // which prevents ISP UDP throttling and ensures maximum speed and instant loading
            if (core.blockQuic) {
                rules.add(JsonObject().apply {
                    addProperty("action", "reject")
                    addProperty("method", "default")
                    addProperty("network", "udp")
                    addProperty("port", 443)
                })
                rules.add(JsonObject().apply {
                    addProperty("action", "reject")
                    addProperty("method", "default")
                    addProperty("protocol", "quic")
                })
            }

            // BitTorrent direct (per provider config)
            rules.add(JsonObject().apply {
                addProperty("protocol", "bittorrent")
                addProperty("outbound", "direct")
            })

            // 2ip.ru -> proxy (per provider config, so IP check shows VPN)
            rules.add(JsonObject().apply {
                val domains = JsonArray().apply { add("2ip.ru") }
                add("domain_suffix", domains)
                addProperty("outbound", "proxy")
            })

            // Push notifications direct (per provider config: mtalk, push.apple)
            rules.add(JsonObject().apply {
                val domains = JsonArray().apply {
                    add("mtalk.google.com")
                    add("push.apple.com")
                    add("api.push.apple.com")
                }
                add("domain_suffix", domains)
                addProperty("outbound", "direct")
            })

            // Bypass private IPs
            rules.add(JsonObject().apply {
                addProperty("ip_is_private", true)
                addProperty("outbound", "direct")
            })

            // Direct Russian IPs via offline GeoIP rule-set (e.g. Rostelecom, Yandex, etc.)
            if (hasGeoip) {
                rules.add(JsonObject().apply {
                    addProperty("rule_set", "geoip-ru")
                    addProperty("outbound", "direct")
                })
            }

            // Direct Russian sites via offline GeoSite rule-set
            if (hasGeosite) {
                rules.add(JsonObject().apply {
                    addProperty("rule_set", "geosite-ru")
                    addProperty("outbound", "direct")
                })
            }

            // Direct Russian services by domain list fallback
            if (core.enableGeoSiteRu) {
                rules.add(JsonObject().apply {
                    val domains = JsonArray().apply {
                        listOf(
                            ".ru", ".su", ".xn--p1ai", "vk.com", "vk.ru", "mail.ru",
                            "yandex.ru", "ya.ru", "yandex.net", "yastatic.net", "yandex.com",
                            "tbank.ru", "tinkoff.ru", "t-static.ru", "cdn-tinkoff.ru",
                            "sberbank.ru", "sber.ru", "vtb.ru", "alfabank.ru", "alfa-bank.ru",
                            "gosuslugi.ru", "nalog.gov.ru", "mos.ru", "avito.ru", "avito.st",
                            "ozon.ru", "ozonusercontent.com", "wildberries.ru", "wb.ru",
                            "wbpay.ru", "wbbasket.ru", "2gis.ru", "2gis.com", "kinopoisk.ru",
                            "rutube.ru", "rzd.ru"
                        ).forEach { add(it) }
                    }
                    add("domain_suffix", domains)
                    addProperty("outbound", "direct")
                })
            }

            // Custom bypass domains
            if (core.customBypassDomains.isNotEmpty()) {
                rules.add(JsonObject().apply {
                    val domains = JsonArray()
                    core.customBypassDomains.forEach { domains.add(it) }
                    add("domain_suffix", domains)
                    addProperty("outbound", "direct")
                })
            }

            add("rules", rules)
            addProperty("final", "proxy")
        }
        root.add("route", route)

        return gson.toJson(root)
    }

    private fun buildNodeOutbound(node: Node, core: CoreConfig): JsonObject {
        val obj = JsonObject()
        obj.addProperty("tag", "proxy")

        when (node.type) {
            NodeType.VLESS -> {
                val v = node.vless ?: throw IllegalArgumentException("Missing VLESS config")
                obj.addProperty("type", "vless")
                obj.addProperty("server", node.address)
                obj.addProperty("server_port", node.port)
                obj.addProperty("uuid", v.uuid)
                obj.addProperty("packet_encoding", "xudp")
                if (!v.flow.isNullOrBlank()) {
                    obj.addProperty("flow", v.flow)
                }

                val chosenFp = if (!v.fp.isNullOrBlank()) v.fp else core.utlsFingerprint.ifBlank { "firefox" }

                if (v.security == "reality" || !v.pbk.isNullOrBlank()) {
                    val tls = JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("server_name", v.sni ?: node.address)
                        val utls = JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", chosenFp)
                        }
                        add("utls", utls)

                        val reality = JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("public_key", v.pbk.orEmpty())
                            if (!v.sid.isNullOrBlank()) {
                                addProperty("short_id", v.sid)
                            }
                        }
                        add("reality", reality)
                    }
                    obj.add("tls", tls)
                } else if (v.security == "tls") {
                    val tls = JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("server_name", v.sni ?: node.address)
                        val utls = JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", chosenFp)
                        }
                        add("utls", utls)
                    }
                    obj.add("tls", tls)
                }
            }
            NodeType.TROJAN -> {
                val t = node.trojan ?: throw IllegalArgumentException("Missing Trojan config")
                obj.addProperty("type", "trojan")
                obj.addProperty("server", node.address)
                obj.addProperty("server_port", node.port)
                obj.addProperty("password", t.password)
                val tls = JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", t.sni ?: node.address)
                }
                obj.add("tls", tls)
            }
            NodeType.WIREGUARD -> {
                val wg = node.wireguard ?: throw IllegalArgumentException("Missing Wireguard config")
                obj.addProperty("type", "wireguard")
                obj.addProperty("server", node.address)
                obj.addProperty("server_port", node.port)
                val localAddr = JsonArray()
                wg.localAddress.forEach { localAddr.add(it) }
                obj.add("local_address", localAddr)
                obj.addProperty("private_key", wg.privateKey)
                obj.addProperty("peer_public_key", wg.peerPublicKey)
                if (!wg.preSharedKey.isNullOrBlank()) {
                    obj.addProperty("pre_shared_key", wg.preSharedKey)
                }
                obj.addProperty("mtu", wg.mtu)
            }
            else -> {
                obj.addProperty("type", "direct")
            }
        }
        return obj
    }
}
