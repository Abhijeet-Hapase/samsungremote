package com.abhijeet.tvremote

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class TvApp(val id: String, val name: String, val type: Int)

/**
 * Talks to a 2018+ Samsung Tizen TV over its local secure WebSocket API (port 8002).
 * First connection shows an Allow prompt on the TV; the TV then issues a token that
 * is reused so later connections are silent.
 */
class TvClient(private val listener: Listener) {

    interface Listener {
        fun onStateChanged(state: State, message: String?)
        fun onTokenReceived(token: String)
        fun onAppsReceived(apps: List<TvApp>)
        fun onImeChanged(active: Boolean)
    }

    enum class State { DISCONNECTED, CONNECTING, WAITING_FOR_APPROVAL, CONNECTED, DENIED }

    @Volatile
    var state: State = State.DISCONNECTED
        private set

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var generation = 0

    private val client: OkHttpClient by lazy { buildClient() }

    fun connect(ip: String, token: String?) {
        disconnect()
        val gen = ++generation
        val name = Base64.encodeToString(CLIENT_NAME.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val url = StringBuilder("wss://")
            .append(ip)
            .append(":8002/api/v2/channels/samsung.remote.control?name=")
            .append(URLEncoder.encode(name, "UTF-8"))
        if (!token.isNullOrBlank()) {
            url.append("&token=").append(URLEncoder.encode(token, "UTF-8"))
        }

        setState(State.CONNECTING, null)
        val request = try {
            Request.Builder().url(url.toString()).build()
        } catch (e: IllegalArgumentException) {
            setState(State.DISCONNECTED, "Invalid TV address")
            return
        }

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                setState(State.WAITING_FOR_APPROVAL, "If a prompt appears on the TV, choose Allow.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                socket = null
                setState(State.DISCONNECTED, "Connection closed by TV")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                socket = null
                setState(State.DISCONNECTED, "Could not reach the TV. Is it on and on the same Wi-Fi?")
            }
        })
    }

    fun disconnect() {
        generation++
        socket?.close(1000, null)
        socket = null
        if (state != State.DISCONNECTED) setState(State.DISCONNECTED, null)
    }

    fun sendKey(key: String, cmd: String = "Click"): Boolean {
        val params = JSONObject()
            .put("Cmd", cmd)
            .put("DataOfCmd", key)
            .put("Option", "false")
            .put("TypeOfRemote", "SendRemoteKey")
        return send(JSONObject().put("method", "ms.remote.control").put("params", params))
    }

    fun requestApps(): Boolean {
        val params = JSONObject()
            .put("event", "ed.installedApp.get")
            .put("to", "host")
        return send(JSONObject().put("method", "ms.channel.emit").put("params", params))
    }

    fun launchApp(app: TvApp): Boolean {
        val data = JSONObject()
            .put("appId", app.id)
            .put("action_type", if (app.type == 4) "NATIVE_LAUNCH" else "DEEP_LINK")
        val params = JSONObject()
            .put("event", "ed.apps.launch")
            .put("to", "host")
            .put("data", data)
        return send(JSONObject().put("method", "ms.channel.emit").put("params", params))
    }

    /** Fills the text box currently open on the TV. Only works while the TV keyboard is showing. */
    fun sendText(text: String): Boolean {
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val input = JSONObject()
            .put("Cmd", encoded)
            .put("DataOfCmd", "base64")
            .put("TypeOfRemote", "SendInputString")
        if (!send(JSONObject().put("method", "ms.remote.control").put("params", input))) return false
        val end = JSONObject().put("TypeOfRemote", "SendInputEnd")
        send(JSONObject().put("method", "ms.remote.control").put("params", end))
        return true
    }

    private fun send(json: JSONObject): Boolean {
        if (state != State.CONNECTED) return false
        return socket?.send(json.toString()) ?: false
    }

    private fun handleMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        when (json.optString("event")) {
            "ms.channel.connect" -> {
                val token = json.optJSONObject("data")?.optString("token").orEmpty()
                if (token.isNotEmpty()) listener.onTokenReceived(token)
                setState(State.CONNECTED, null)
                requestApps()
            }
            "ms.channel.unauthorized" -> fail(
                State.DENIED,
                "The TV refused access. Allow this phone under TV Settings, General, External Device Manager, Device Connection Manager."
            )
            "ms.channel.timeOut" -> fail(State.DENIED, "Nobody answered the prompt on the TV. Tap any button to try again.")
            "ed.installedApp.get" -> listener.onAppsReceived(parseApps(json))
            "ms.remote.imeStart" -> listener.onImeChanged(true)
            "ms.remote.imeEnd" -> listener.onImeChanged(false)
        }
    }

    private fun parseApps(json: JSONObject): List<TvApp> {
        val arr = json.optJSONObject("data")?.optJSONArray("data")
            ?: json.optJSONArray("data")
            ?: return emptyList()
        val apps = ArrayList<TvApp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("appId")
            val name = o.optString("name")
            if (id.isNotEmpty() && name.isNotEmpty()) apps.add(TvApp(id, name, o.optInt("app_type", 2)))
        }
        return apps
    }

    private fun fail(newState: State, message: String) {
        generation++
        socket?.cancel()
        socket = null
        setState(newState, message)
    }

    private fun setState(newState: State, message: String?) {
        state = newState
        listener.onStateChanged(newState, message)
    }

    private fun buildClient(): OkHttpClient {
        // The TV uses a self-signed certificate, so this client (used only for the TV) accepts it.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    companion object {
        const val CLIENT_NAME = "Phone Remote"
    }
}
