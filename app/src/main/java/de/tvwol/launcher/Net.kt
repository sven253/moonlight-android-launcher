package de.tvwol.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object Net {

    /**
     * Checks whether the streaming host answers on its GameStream/Sunshine HTTP port.
     *
     * We deliberately do not use ICMP or InetAddress.isReachable(): ICMP needs root on
     * Android, and isReachable() silently falls back to a port-7 probe that most hosts
     * drop. A TCP connect also tells us something more useful than "the machine is on" —
     * namely that the streaming service is actually accepting connections, which is the
     * condition Moonlight really needs.
     */
    suspend fun isHostUp(host: String, port: Int, timeoutMs: Int = 1200): Boolean =
        withContext(Dispatchers.IO) {
            if (host.isBlank()) return@withContext false
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Sends a Wake-on-LAN magic packet as a UDP broadcast. Only useful when the TV and
     * the PC share a broadcast domain — otherwise the relay is the way to go.
     */
    suspend fun sendMagicPacket(mac: String, port: Int = 9) = withContext(Dispatchers.IO) {
        val bytes = parseMac(mac)
        val payload = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) payload[i] = 0xFF.toByte()
        for (block in 1..16) {
            System.arraycopy(bytes, 0, payload, block * 6, 6)
        }
        val address = InetAddress.getByName("255.255.255.255")
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(payload, payload.size, address, port))
        }
    }

    /** Accepts aa:bb:cc:dd:ee:ff, aa-bb-..., or aabbccddeeff. */
    fun parseMac(mac: String): ByteArray {
        val hex = mac.replace(Regex("[^0-9a-fA-F]"), "")
        require(hex.length == 12) { "Ungültige MAC-Adresse: $mac" }
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Returns the MAC in canonical aa:bb:cc:dd:ee:ff form, or null if the input does not
     * contain exactly twelve hex digits. Used to keep a typo in the settings from being
     * passed on to the relay: etherwake happily accepts a malformed address, sends
     * something useless and still exits with 0.
     */
    fun normalizeMac(mac: String): String? {
        val hex = mac.replace(Regex("[^0-9a-fA-F]"), "").lowercase()
        if (hex.length != 12) return null
        return hex.chunked(2).joinToString(":")
    }
}
