package com.eroid.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.eroid.vpn.EroidApplication
import com.eroid.vpn.MainActivity
import com.eroid.vpn.R
import com.eroid.vpn.core.BoxRunner
import com.eroid.vpn.core.ConfigGenerator
import com.eroid.vpn.model.CoreConfig
import com.eroid.vpn.model.Node
import com.eroid.vpn.model.VpnStatus
import io.nekohasekai.libbox.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

class EroidVpnService : VpnService(), PlatformInterface {

    companion object {
        const val ACTION_START = "com.eroid.vpn.START"
        const val ACTION_STOP = "com.eroid.vpn.STOP"
        const val EXTRA_NODE = "extra_node"
        const val EXTRA_CORE = "extra_core"
        private const val NOTIFICATION_ID = 1001

        private val _status = MutableStateFlow(VpnStatus.DISCONNECTED)
        val status: StateFlow<VpnStatus> = _status.asStateFlow()

        private val _currentNode = MutableStateFlow<Node?>(null)
        val currentNode: StateFlow<Node?> = _currentNode.asStateFlow()

        private val _logs = MutableSharedFlow<String>(replay = 100, extraBufferCapacity = 200)
        val logs: SharedFlow<String> = _logs.asSharedFlow()

        fun log(msg: String) {
            Log.d("EroidVPN", msg)
            _logs.tryEmit(msg)
        }

        var boxRunner: BoxRunner? = null
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        if (boxRunner == null) {
            boxRunner = BoxRunner(applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val node = intent.getSerializableExtra(EXTRA_NODE) as? Node
                val core = (intent.getSerializableExtra(EXTRA_CORE) as? CoreConfig) ?: CoreConfig()
                if (node != null) {
                    startVpn(node, core)
                } else {
                    stopVpn()
                }
            }
            ACTION_STOP -> {
                stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(node: Node, core: CoreConfig) {
        _status.value = VpnStatus.CONNECTING
        _currentNode.value = node

        val notification = createNotification("Подключение к ${node.name}...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w("EroidVPN", "startForeground exception: ${e.message}")
            startForeground(NOTIFICATION_ID, notification)
        }

        scope.launch(Dispatchers.IO) {
            try {
                // Pre-resolve VPN server hostname BEFORE VPN interface is established.
                // At this point no TUN is active yet, so system DNS works normally
                // and doesn't loop through the VPN tunnel.
                val resolvedNode = resolveNodeAddress(node)

                val rulesDir = java.io.File(filesDir, "rules")
                (application as? EroidApplication)?.extractRuleSets()

                val generator = ConfigGenerator()
                val configJson = generator.generateJson(resolvedNode, core, rulesDir)

                val started = boxRunner?.start(configJson, this@EroidVpnService) ?: false
                if (!started) {
                    _status.value = VpnStatus.ERROR
                    stopSelf()
                    return@launch
                }

                _status.value = VpnStatus.CONNECTED
                val notif = createNotification("Подключено: ${node.name}")
                val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(NOTIFICATION_ID, notif)

            } catch (e: Exception) {
                Log.e("EroidVPN", "startVpn error", e)
                _status.value = VpnStatus.ERROR
                stopVpn()
            }
        }
    }

    /**
     * Pre-resolves the VPN server hostname to an IPv4 address BEFORE the TUN
     * interface is opened. At this stage no VPN route is active, so the system
     * DNS resolver works over the physical NIC without any looping or protect() issues.
     * If system DNS fails (e.g. on Android emulator), falls back to direct UDP query to 8.8.8.8 / 77.88.8.8.
     */
    private fun resolveNodeAddress(node: Node): Node {
        val host = node.address
        // If it's already an IP address, nothing to do
        if (host.isBlank() || host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
            return node
        }

        val resolvedIp = try {
            log("[EROID] Резолвинг $host...")
            val addresses = InetAddress.getAllByName(host)
            addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
                ?: addresses.firstOrNull()?.hostAddress
        } catch (_: Exception) {
            null
        } ?: run {
            log("[EROID] Системный DNS не ответил, опрос 8.8.8.8...")
            resolveViaUdpDns(host, "8.8.8.8")
                ?: resolveViaUdpDns(host, "77.88.8.8")
                ?: resolveViaUdpDns(host, "1.1.1.1")
        }

        return if (resolvedIp != null) {
            log("[EROID] $host → $resolvedIp")
            val updatedVless = if (node.vless != null && node.vless.sni.isNullOrBlank()) {
                node.vless.copy(sni = host)
            } else node.vless
            val updatedTrojan = if (node.trojan != null && node.trojan.sni.isNullOrBlank()) {
                node.trojan.copy(sni = host)
            } else node.trojan
            node.copy(address = resolvedIp, vless = updatedVless, trojan = updatedTrojan)
        } else {
            log("[EROID] Резолвинг $host не удался, используем hostname")
            node
        }
    }

    private fun resolveViaUdpDns(domain: String, dnsServer: String): String? {
        var socket: java.net.DatagramSocket? = null
        return try {
            socket = java.net.DatagramSocket().apply { soTimeout = 2500 }
            val out = java.io.ByteArrayOutputStream()
            val dataOut = java.io.DataOutputStream(out)
            dataOut.writeShort(0x1a2b) // Transaction ID
            dataOut.writeShort(0x0100) // Flags: standard query, recursion desired
            dataOut.writeShort(1)      // 1 question
            dataOut.writeShort(0)
            dataOut.writeShort(0)
            dataOut.writeShort(0)
            for (part in domain.split(".")) {
                val bytes = part.toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
                dataOut.writeByte(bytes.size)
                dataOut.write(bytes)
            }
            dataOut.writeByte(0)
            dataOut.writeShort(1) // Type A
            dataOut.writeShort(1) // Class IN

            val query = out.toByteArray()
            val packet = java.net.DatagramPacket(query, query.size, java.net.InetAddress.getByName(dnsServer), 53)
            socket.send(packet)

            val buf = ByteArray(1024)
            val recv = java.net.DatagramPacket(buf, buf.size)
            socket.receive(recv)

            val inStream = java.io.DataInputStream(java.io.ByteArrayInputStream(recv.data, 0, recv.length))
            inStream.skipBytes(4)
            val qdCount = inStream.readUnsignedShort()
            val anCount = inStream.readUnsignedShort()
            inStream.skipBytes(4)
            for (i in 0 until qdCount) {
                while (true) {
                    val len = inStream.readUnsignedByte()
                    if (len == 0) break
                    inStream.skipBytes(len)
                }
                inStream.skipBytes(4)
            }
            for (i in 0 until anCount) {
                val marker = inStream.readUnsignedByte()
                if ((marker and 0xC0) == 0xC0) {
                    inStream.skipBytes(1)
                } else {
                    var len = marker
                    while (len > 0) {
                        inStream.skipBytes(len)
                        len = inStream.readUnsignedByte()
                    }
                }
                val type = inStream.readUnsignedShort()
                val clazz = inStream.readUnsignedShort()
                val ttl = inStream.readInt()
                val dataLen = inStream.readUnsignedShort()
                if (type == 1 && dataLen == 4) {
                    val ip = ByteArray(4)
                    inStream.readFully(ip)
                    return java.net.InetAddress.getByAddress(ip).hostAddress
                } else {
                    inStream.skipBytes(dataLen)
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun stopVpn() {
        _status.value = VpnStatus.DISCONNECTED
        _currentNode.value = null

        boxRunner?.stop()

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        stopSelf()
    }

    // ==========================================
    // PlatformInterface Implementation for libbox
    // ==========================================

    override fun openTun(options: TunOptions): Int {
        val builder = Builder().setSession("EROID VPN")
        val mtu = if (options.mtu in 1280..9000) options.mtu else 1500
        builder.setMtu(mtu)

        var hasIpv4 = false
        val inet4 = options.inet4Address
        if (inet4 != null) {
            while (inet4.hasNext()) {
                val prefix = inet4.next()
                builder.addAddress(prefix.address(), prefix.prefix())
                hasIpv4 = true
            }
        }
        if (!hasIpv4) {
            builder.addAddress("172.19.0.1", 30)
        }

        var hasDefaultRoute = false
        val routes = options.inet4RouteAddress
        if (routes != null) {
            while (routes.hasNext()) {
                val r = routes.next()
                try {
                    builder.addRoute(r.address(), r.prefix())
                    if (r.address() == "0.0.0.0" && r.prefix() == 0) {
                        hasDefaultRoute = true
                    }
                } catch (_: Exception) {}
            }
        }
        if (!hasDefaultRoute) {
            try {
                builder.addRoute("0.0.0.0", 0)
            } catch (_: Exception) {}
        }

        // Intentionally NO IPv6 routes or addresses added here.
        // Remnawave/VLESS proxy nodes are IPv4-only. Adding IPv6 routes creates
        // 15-second hangs and ERR_CONNECTION_ABORTED across all dual-stack websites.

        val dns = options.dnsServerAddress
        var hasDns = false
        if (dns != null) {
            while (dns.hasNext()) {
                try {
                    builder.addDnsServer(dns.next())
                    hasDns = true
                } catch (_: Exception) {}
            }
        }
        if (!hasDns) {
            builder.addDnsServer("77.88.8.8")
        }

        try {
            val physical = findPhysicalNetwork()
            if (physical != null) {
                builder.setUnderlyingNetworks(arrayOf(physical))
            }
        } catch (_: Exception) {}

        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {}

        val pfd = builder.establish() ?: throw IllegalStateException("Не удалось создать VPN интерфейс")
        vpnInterface = pfd
        log("[EROID] VPN интерфейс создан (fd=${pfd.fd}, MTU=$mtu)")
        return pfd.detachFd()
    }

    private fun findPhysicalNetwork(): Network? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return active
            }
        }
        return try {
            cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Exception) { null }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        val ok = protect(fd)
        if (!ok) log("[EROID] WARN: protect(fd=$fd) �� ������!")
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Filter out TUN/VPN interfaces — only report physical ones (wlan0, rmnet, eth0)
        fun isPhysicalInterface(name: String): Boolean {
            return !name.startsWith("tun") && !name.startsWith("vpn") && !name.startsWith("ppp")
        }

        fun sendUpdate(network: Network?) {
            val net = network ?: findPhysicalNetwork() ?: return
            val caps = cm.getNetworkCapabilities(net) ?: return
            // Skip VPN networks — Sing-Box must route outbound through the physical NIC
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

            val linkProps = cm.getLinkProperties(net) ?: return
            val ifaceName = linkProps.interfaceName ?: return
            if (!isPhysicalInterface(ifaceName)) return

            val isIPv4 = linkProps.linkAddresses.any { it.address is Inet4Address }
            val isIPv6 = linkProps.linkAddresses.any { it.address is Inet6Address }
            val ifaceIndex = try {
                NetworkInterface.getByName(ifaceName)?.index ?: 0
            } catch (_: Exception) { 0 }
            try {
                log("[EROID] Физический интерфейс: $ifaceName (idx=$ifaceIndex, v4=$isIPv4, v6=$isIPv6)")
                listener.updateDefaultInterface(ifaceName, ifaceIndex, isIPv4, isIPv6)
            } catch (e: Exception) {
                Log.w("EroidVPN", "updateDefaultInterface failed", e)
            }
        }

        // Send initial state immediately using the physical underlying network
        try {
            sendUpdate(findPhysicalNetwork())
        } catch (_: Exception) {}

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { sendUpdate(network) }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { sendUpdate(network) }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { sendUpdate(network) }
        }
        netCallback = callback

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            try { cm.registerDefaultNetworkCallback(callback) } catch (_: Exception) {}
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        netCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            netCallback = null
        }
    }

    override fun cancelNotification(tag: String?, id: Int) {}
    override fun checkPlatformShell() {}
    override fun clearDNSCache() {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun createBridge(options: BridgeOptions?): BridgeSession = throw Exception("Bridge not supported")
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sourceAddress != null && destinationAddress != null) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val src = InetSocketAddress(InetAddress.getByName(sourceAddress), sourcePort)
                val dst = InetSocketAddress(InetAddress.getByName(destinationAddress), destinationPort)
                val uid = cm.getConnectionOwnerUid(ipProtocol, src, dst)
                if (uid != android.os.Process.INVALID_UID) {
                    val owner = ConnectionOwner()
                    owner.userId = uid
                    val pkgs = packageManager.getPackagesForUid(uid)
                    if (!pkgs.isNullOrEmpty()) {
                        owner.processPath = pkgs[0]
                    }
                    return owner
                }
            } catch (_: Exception) {}
        }
        throw Exception("Connection owner not found")
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        try {
            val nics = NetworkInterface.getNetworkInterfaces()
            while (nics != null && nics.hasMoreElements()) {
                val nic = nics.nextElement()
                if (nic.isUp && !nic.isLoopback && !nic.name.startsWith("tun") && !nic.name.startsWith("vpn") && !nic.name.startsWith("ppp")) {
                    val iface = io.nekohasekai.libbox.NetworkInterface()
                    iface.name = nic.name
                    iface.index = nic.index
                    iface.mtu = try { nic.mtu } catch (_: Exception) { 1500 }

                    val addrs = mutableListOf<String>()
                    val ifAddrs = try { nic.interfaceAddresses } catch (_: Exception) { null }
                    if (!ifAddrs.isNullOrEmpty()) {
                        for (ifAddr in ifAddrs) {
                            val addr = ifAddr.address ?: continue
                            val host = addr.hostAddress?.substringBefore("%") ?: continue
                            val prefix = ifAddr.networkPrefixLength.toInt()
                            val validPrefix = when {
                                prefix in 1..128 -> prefix
                                addr is Inet4Address -> 32
                                else -> 128
                            }
                            addrs.add("$host/$validPrefix")
                        }
                    } else {
                        val inetAddrs = nic.inetAddresses
                        while (inetAddrs != null && inetAddrs.hasMoreElements()) {
                            val addr = inetAddrs.nextElement()
                            val host = addr.hostAddress?.substringBefore("%") ?: continue
                            val validPrefix = if (addr is Inet4Address) 32 else 128
                            addrs.add("$host/$validPrefix")
                        }
                    }

                    iface.addresses = object : StringIterator {
                        val it = addrs.iterator()
                        override fun hasNext(): Boolean = it.hasNext()
                        override fun next(): String = it.next()
                        override fun len(): Int = addrs.size
                    }
                    iface.dnsServer = object : StringIterator {
                        override fun hasNext(): Boolean = false
                        override fun next(): String = ""
                        override fun len(): Int = 0
                    }
                    iface.gateway = object : StringIterator {
                        override fun hasNext(): Boolean = false
                        override fun next(): String = ""
                        override fun len(): Int = 0
                    }
                    list.add(iface)
                }
            }
        } catch (e: Exception) {
            Log.w("EroidVPN", "getInterfaces error: ${e.message}")
        }
        val it = list.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = it.hasNext()
            override fun next(): io.nekohasekai.libbox.NetworkInterface = it.next()
        }
    }

    override fun includeAllNetworks(): Boolean = false
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun lookupSFTPServer(): String = throw Exception("SFTP not supported")
    override fun lookupUser(name: String?): PlatformUser = throw Exception("User lookup not supported")
    override fun openShellSession(user: PlatformUser?, command: String?, args: StringIterator?, workingDirectory: String?, cols: Int, rows: Int): ShellSession = throw Exception("Shell not supported")
    override fun readSystemSSHHostKey(): String = throw Exception("SSH host key not supported")
    override fun readWIFIState(): WIFIState? = null
    override fun registerMyInterface(name: String?) {}
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun tailscaleHostname(): String = ""
    override fun underNetworkExtension(): Boolean = false
    override fun usePlatformBridge(): Boolean = false
    override fun usePlatformShell(): Boolean = false

    private fun createNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pLaunch = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopIntent = Intent(this, EroidVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, EroidApplication.CHANNEL_ID)
            .setContentTitle("EROID VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pLaunch)
            .addAction(0, getString(R.string.disconnect), pStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
