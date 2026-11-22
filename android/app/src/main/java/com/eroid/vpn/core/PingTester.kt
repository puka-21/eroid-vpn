package com.eroid.vpn.core

import com.eroid.vpn.model.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class PingTester {

    suspend fun testPing(node: Node, timeoutMs: Int = 1800): Long? = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(node.address, node.port), timeoutMs)
            }
            val elapsed = System.currentTimeMillis() - start
            node.pingMs = elapsed
            elapsed
        } catch (_: Exception) {
            node.pingMs = null
            null
        }
    }
}
