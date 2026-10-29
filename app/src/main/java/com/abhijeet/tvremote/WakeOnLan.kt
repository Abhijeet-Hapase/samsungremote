package com.abhijeet.tvremote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLan {

    private val MAC_PATTERN = Regex("^[0-9A-Fa-f]{12}$")

    fun isValidMac(mac: String): Boolean = MAC_PATTERN.matches(normalize(mac))

    /** Sends the magic packet to the network broadcast addresses and directly to the TV. */
    fun send(mac: String, tvIp: String?): Boolean {
        val hex = normalize(mac)
        if (!MAC_PATTERN.matches(hex)) return false
        val macBytes = ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 1..16) System.arraycopy(macBytes, 0, packet, i * 6, 6)

        val targets = LinkedHashSet<String>()
        targets.add("255.255.255.255")
        if (!tvIp.isNullOrBlank()) {
            targets.add(tvIp.substringBeforeLast('.') + ".255")
            targets.add(tvIp)
        }

        return try {
            DatagramSocket().use { s ->
                s.broadcast = true
                repeat(3) {
                    for (t in targets) {
                        val addr = InetAddress.getByName(t)
                        for (port in intArrayOf(9, 7)) {
                            s.send(DatagramPacket(packet, packet.size, addr, port))
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun normalize(mac: String) = mac.replace(":", "").replace("-", "").replace(".", "").trim()
}
