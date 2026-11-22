package com.eroid.vpn.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.eroid.vpn.model.*
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class SubscriptionFetcher(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                if (hostname.equals("key.ruguard.com", ignoreCase = true)) {
                    try {
                        val addrs = okhttp3.Dns.SYSTEM.lookup(hostname)
                        if (addrs.isNotEmpty()) return addrs
                    } catch (_: Exception) {}
                    return listOf(java.net.InetAddress.getByAddress(hostname, byteArrayOf(185.toByte(), 178.toByte(), 208.toByte(), 167.toByte())))
                }
                return okhttp3.Dns.SYSTEM.lookup(hostname)
            }
        })
        .build()

    @SuppressLint("HardwareIds")
    fun getHardwareId(customHwid: String?): String {
        if (!customHwid.isNullOrBlank()) {
            return customHwid.trim()
        }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return if (!androidId.isNullOrBlank()) {
            "android-$androidId"
        } else {
            "android-${Build.SERIAL.takeIf { it != "unknown" } ?: "default-hwid-device"}"
        }
    }

    suspend fun fetch(url: String, customHwid: String? = null): FetchResult = withContext(Dispatchers.IO) {
        val hwid = getHardwareId(customHwid)
        val request = Request.Builder()
            .url(url.trim())
            .addHeader("User-Agent", "INCY/4.0.1 (Android; ru)")
            .addHeader("x-hwid", hwid)
            .addHeader("x-device-os", "Android ${Build.VERSION.RELEASE}")
            .addHeader("x-device-model", Build.MODEL)
            .addHeader("x-device-name", Build.DEVICE)
            .addHeader("x-device-brand", Build.MANUFACTURER)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext FetchResult.Error("HTTP Error: ${response.code}")
                }

                val maxDevices = response.header("X-Hwid-Max-Devices-Reached")?.toBoolean() ?: false
                val notSupported = response.header("X-Hwid-Not-Supported")?.toBoolean() ?: false
                val announceHeader = response.header("Announce")

                val rawBody = response.body?.string().orEmpty()
                val trimmed = rawBody.trim()

                val textToParse = if (trimmed.startsWith("[") || trimmed.contains("proxies:") || trimmed.contains("://")) {
                    trimmed
                } else {
                    tryDecodeBase64(trimmed)
                }

                val hasLimitInBody = textToParse.contains("Лимит устройств") ||
                        textToParse.contains("максимального количества устройств") ||
                        rawBody.contains("X-Hwid-Max-Devices-Reached")
                val isLimit = maxDevices || hasLimitInBody

                var announce = announceHeader
                if (announce?.startsWith("base64:") == true) {
                    announce = tryDecodeBase64(announce.removePrefix("base64:"))
                }

                val nodes = parseNodes(textToParse)

                if (nodes.isEmpty()) {
                    if (isLimit) {
                        return@withContext FetchResult.Error(
                            "Достигнут лимит устройств (1/1)!\n" +
                            (announce ?: "Сервер отклонил привязку.") + "\n\n" +
                            "👉 Укажите ваш HWID в Настройках или сбросьте привязку в Telegram-боте: @ruvpn"
                        )
                    } else if (textToParse.contains("Приложение не подходит")) {
                        return@withContext FetchResult.Error("Провайдер требует эмуляцию INCY или HAPP.")
                    } else {
                        return@withContext FetchResult.Error("Сервер не вернул доступных узлов для подключения.")
                    }
                }

                FetchResult.Success(
                    nodes = nodes,
                    hwidUsed = hwid,
                    isDeviceLimitReached = isLimit,
                    isHwidNotSupported = notSupported,
                    announce = announce
                )
            }
        } catch (e: Exception) {
            FetchResult.Error("Ошибка загрузки: ${e.localizedMessage ?: e.message}")
        }
    }

    private fun tryDecodeBase64(input: String): String {
        val cleaned = input.trim().replace("\r", "").replace("\n", "")
        return try {
            val bytes = Base64.decode(cleaned, Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            input
        }
    }

    fun parseNodes(text: String): List<Node> {
        val trimmedText = text.trim()
        if (trimmedText.startsWith("[")) {
            val jsonNodes = parseRemnawaveJson(trimmedText)
            if (jsonNodes.isNotEmpty()) {
                return jsonNodes
            }
        }

        val result = mutableListOf<Node>()
        val lines = text.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            try {
                when {
                    trimmed.startsWith("vless://") -> parseVless(trimmed)?.let { result.add(it) }
                    trimmed.startsWith("trojan://") -> parseTrojan(trimmed)?.let { result.add(it) }
                    trimmed.startsWith("wireguard://") -> parseWireguard(trimmed)?.let { result.add(it) }
                }
            } catch (_: Exception) {
                // skip malformed entries
            }
        }
        return result
    }

    fun parseRemnawaveJson(jsonStr: String): List<Node> {
        val list = mutableListOf<Node>()
        try {
            val jsonArray = JsonParser.parseString(jsonStr).asJsonArray
            for (element in jsonArray) {
                val item = element.asJsonObject
                val remarks = item.get("remarks")?.asString ?: "Proxy"
                val outbounds = item.getAsJsonArray("outbounds") ?: continue

                for (obElem in outbounds) {
                    val ob = obElem.asJsonObject
                    val proto = ob.get("protocol")?.asString ?: ob.get("type")?.asString ?: ""
                    if (proto.equals("vless", ignoreCase = true)) {
                        var server = ""
                        var port = 443
                        var uuid = ""
                        var flow: String? = null

                        val settings = ob.getAsJsonObject("settings")
                        if (settings != null && settings.has("vnext")) {
                            val vnextArr = settings.getAsJsonArray("vnext")
                            if (vnextArr != null && vnextArr.size() > 0) {
                                val vnext = vnextArr[0].asJsonObject
                                server = vnext.get("address")?.asString ?: ""
                                port = vnext.get("port")?.asInt ?: 443
                                val users = vnext.getAsJsonArray("users")
                                if (users != null && users.size() > 0) {
                                    val user = users[0].asJsonObject
                                    uuid = user.get("id")?.asString ?: ""
                                    flow = user.get("flow")?.asString
                                }
                            }
                        } else if (ob.has("server")) {
                            server = ob.get("server")?.asString ?: ""
                            port = ob.get("server_port")?.asInt ?: ob.get("port")?.asInt ?: 443
                            uuid = ob.get("uuid")?.asString ?: ""
                            flow = ob.get("flow")?.asString
                        }

                        if (server.isBlank() || server == "0.0.0.0") continue

                        val stream = ob.getAsJsonObject("streamSettings")
                        val security = stream?.get("security")?.asString ?: "none"
                        var pbk: String? = null
                        var sid: String? = null
                        var sni: String? = null
                        var fp = "chrome"

                        if (security.equals("reality", ignoreCase = true)) {
                            val reality = stream?.getAsJsonObject("realitySettings")
                            sni = reality?.get("serverName")?.asString ?: server
                            pbk = reality?.get("publicKey")?.asString
                            sid = reality?.get("shortId")?.asString
                            fp = reality?.get("fingerprint")?.asString ?: "chrome"
                        } else if (security.equals("tls", ignoreCase = true)) {
                            val tls = stream?.getAsJsonObject("tlsSettings")
                            sni = tls?.get("serverName")?.asString ?: server
                            fp = tls?.get("fingerprint")?.asString ?: "chrome"
                        }

                        val vless = VlessConfig(
                            uuid = uuid,
                            flow = flow,
                            security = security,
                            sni = sni,
                            fp = fp,
                            pbk = pbk,
                            sid = sid
                        )

                        list.add(Node(
                            name = remarks,
                            address = server,
                            port = port,
                            type = NodeType.VLESS,
                            rawUri = "vless://$uuid@$server:$port",
                            vless = vless
                        ))
                        break
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun parseVless(uriString: String): Node? {
        val uri = URI(uriString)
        val userInfo = uri.userInfo ?: return null
        val host = uri.host ?: return null
        if (host == "0.0.0.0" || userInfo == "00000000-0000-0000-0000-000000000000") {
            return null
        }
        val port = if (uri.port != -1) uri.port else 443

        val queryParams = parseQuery(uri.rawQuery)
        val name = uri.fragment?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: "$host:$port"

        val vless = VlessConfig(
            uuid = userInfo,
            flow = queryParams["flow"],
            security = queryParams["security"] ?: "none",
            sni = queryParams["sni"],
            fp = queryParams["fp"] ?: "chrome",
            pbk = queryParams["pbk"],
            sid = queryParams["sid"],
            spx = queryParams["spx"],
            path = queryParams["path"],
            host = queryParams["host"],
            type = queryParams["type"] ?: "tcp"
        )

        return Node(
            name = name,
            address = host,
            port = port,
            type = NodeType.VLESS,
            rawUri = uriString,
            vless = vless
        )
    }

    private fun parseTrojan(uriString: String): Node? {
        val uri = URI(uriString)
        val password = uri.userInfo ?: return null
        val host = uri.host ?: return null
        if (host == "0.0.0.0") {
            return null
        }
        val port = if (uri.port != -1) uri.port else 443

        val queryParams = parseQuery(uri.rawQuery)
        val name = uri.fragment?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: "$host:$port"

        val trojan = TrojanConfig(
            password = password,
            sni = queryParams["sni"],
            fp = queryParams["fp"] ?: "chrome"
        )

        return Node(
            name = name,
            address = host,
            port = port,
            type = NodeType.TROJAN,
            rawUri = uriString,
            trojan = trojan
        )
    }

    private fun parseWireguard(uriString: String): Node? {
        val uri = URI(uriString)
        val privateKey = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port != -1) uri.port else 51820
        val queryParams = parseQuery(uri.rawQuery)
        val name = uri.fragment?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: "WireGuard $host"

        val peerPublicKey = queryParams["publickey"] ?: queryParams["peer_public_key"] ?: ""
        val localAddress = queryParams["ip"]?.split(",") ?: listOf("10.0.0.2/32")

        val wg = WireguardConfig(
            privateKey = privateKey,
            peerPublicKey = peerPublicKey,
            localAddress = localAddress,
            mtu = queryParams["mtu"]?.toIntOrNull() ?: 1420
        )

        return Node(
            name = name,
            address = host,
            port = port,
            type = NodeType.WIREGUARD,
            rawUri = uriString,
            wireguard = wg
        )
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (param in query.split("&")) {
            val parts = param.split("=", limit = 2)
            if (parts.isNotEmpty()) {
                val key = parts[0]
                val value = if (parts.size > 1) URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) else ""
                result[key] = value
            }
        }
        return result
    }

    sealed class FetchResult {
        data class Success(
            val nodes: List<Node>,
            val hwidUsed: String,
            val isDeviceLimitReached: Boolean,
            val isHwidNotSupported: Boolean,
            val announce: String? = null
        ) : FetchResult()

        data class Error(val message: String) : FetchResult()
    }
}
