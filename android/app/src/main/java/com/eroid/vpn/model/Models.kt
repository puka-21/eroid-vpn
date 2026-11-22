package com.eroid.vpn.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

enum class NodeType {
    VLESS,
    WIREGUARD,
    TROJAN,
    SHADOWSOCKS,
    UNKNOWN
}

data class VlessConfig(
    val uuid: String,
    val flow: String? = null,
    val security: String = "none",
    val sni: String? = null,
    val fp: String = "chrome",
    val pbk: String? = null,
    val sid: String? = null,
    val spx: String? = null,
    val path: String? = null,
    val host: String? = null,
    val type: String = "tcp"
) : Serializable

data class WireguardConfig(
    val privateKey: String,
    val peerPublicKey: String,
    val localAddress: List<String> = listOf("10.0.0.2/32"),
    val preSharedKey: String? = null,
    val mtu: Int = 1420,
    val reserved: List<Int>? = null
) : Serializable

data class TrojanConfig(
    val password: String,
    val sni: String? = null,
    val fp: String = "chrome",
    val alpn: List<String>? = null
) : Serializable

data class Node(
    val name: String,
    val address: String,
    val port: Int,
    val type: NodeType,
    val rawUri: String,
    var pingMs: Long? = null,
    val vless: VlessConfig? = null,
    val wireguard: WireguardConfig? = null,
    val trojan: TrojanConfig? = null
) : Serializable

enum class AppTheme(val displayName: String) {
    NORDIC("Nordic Deep"),
    OLED("Void OLED"),
    CYBERPUNK("Cyberpunk Neon"),
    EMERALD("Emerald Mint"),
    TOKYO("Tokyo Night")
}

data class CoreConfig(
    var stack: String = "gvisor",
    var utlsFingerprint: String = "chrome",
    var mtu: Int = 1420,
    var enableGeoSiteRu: Boolean = true,
    var enableGeoIpRu: Boolean = true,
    var enableAdBlock: Boolean = true,
    var blockQuic: Boolean = true,
    var dnsRemote: String = "8.8.8.8",
    var dnsDirect: String = "77.88.8.8",
    var customBypassDomains: List<String> = listOf("gosuslugi.ru", "sberbank.ru", "tbank.ru", "vk.com", "yandex.ru")
) : Serializable

data class AppSettings(
    var subscriptionUrl: String = "",
    var customHwid: String = "",
    var selectedNodeIndex: Int = 0,
    var theme: AppTheme = AppTheme.NORDIC,
    var core: CoreConfig = CoreConfig(),
    var nodes: MutableList<Node> = mutableListOf()
) : Serializable

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
