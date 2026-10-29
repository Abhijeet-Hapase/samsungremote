package com.abhijeet.tvremote

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TvInfo(
    val ip: String,
    val name: String,
    val model: String,
    val mac: String,
    val powerState: String
)

object Discovery {

    /** Asks the TV's local REST endpoint who it is. Returns null if it is not a Samsung TV. */
    fun fetchInfo(ip: String, timeoutMs: Int = 2000): TvInfo? {
        return try {
            val conn = URL("http://$ip:8001/api/v2/").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            try {
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val device = json.optJSONObject("device") ?: return null
                TvInfo(
                    ip = ip,
                    name = device.optString("name").ifBlank { json.optString("name", "Samsung TV") },
                    model = device.optString("modelName"),
                    mac = device.optString("wifiMac"),
                    powerState = device.optString("PowerState")
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Finds Samsung TVs: SSDP first, then a quick scan of the local /24 network as a fallback. */
    fun discover(localIp: String?): List<TvInfo> {
        var tvs = ssdp().mapNotNull { fetchInfo(it) }
        if (tvs.isEmpty() && localIp != null) {
            tvs = scanSubnet(localIp).mapNotNull { fetchInfo(it) }
        }
        return tvs.distinctBy { it.ip }
    }

    fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun ssdp(timeoutMs: Long = 2500): Set<String> {
        val found = LinkedHashSet<String>()
        val targets = listOf(
            "urn:samsung.com:device:RemoteControlReceiver:1",
            "urn:dial-multiscreen-org:service:dial:1"
        )
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                for (st in targets) {
                    val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n\r\n").toByteArray()
                    s.send(DatagramPacket(msg, msg.size, group, 1900))
                }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    packet.address?.hostAddress?.let { found.add(it) }
                }
            }
        } catch (e: Exception) {
            // Multicast can be blocked by some routers; the subnet scan covers that case.
        }
        return found
    }

    private fun scanSubnet(localIp: String): List<String> {
        val prefix = localIp.substringBeforeLast('.')
        val hits = Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) {
            val ip = "$prefix.$i"
            if (ip == localIp) continue
            pool.execute {
                try {
                    Socket().use { it.connect(InetSocketAddress(ip, 8001), 400) }
                    hits.add(ip)
                } catch (e: Exception) {
                    // not a TV
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
        return ArrayList(hits)
    }
}
