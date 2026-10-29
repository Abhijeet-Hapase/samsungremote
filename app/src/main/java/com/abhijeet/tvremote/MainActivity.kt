package com.abhijeet.tvremote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity(), TvClient.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var tv: TvClient
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newCachedThreadPool()

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>
    private lateinit var appsGrid: GridLayout
    private lateinit var appsStatus: TextView
    private lateinit var kbStatus: TextView
    private lateinit var kbInput: EditText

    private var isActive = false
    private var currentTab = 0
    private var reconnectAttempts = 0
    private var appsLoaded = false
    private var lastToastAt = 0L

    private val reconnectRunnable = Runnable { connectIfConfigured() }
    private val appsFallback = Runnable {
        if (!appsLoaded) renderApps(DEFAULT_APPS, "Your TV did not share its app list, so common apps are shown.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("tv", Context.MODE_PRIVATE)
        tv = TvClient(this)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        appsGrid = findViewById(R.id.appsGrid)
        appsStatus = findViewById(R.id.appsStatus)
        kbStatus = findViewById(R.id.kbStatus)
        kbInput = findViewById(R.id.kbInput)

        pages = listOf<View>(
            findViewById(R.id.pageRemote),
            findViewById(R.id.pageApps),
            findViewById(R.id.pageKeyboard)
        )
        tabs = listOf<TextView>(
            findViewById(R.id.tabRemote),
            findViewById(R.id.tabApps),
            findViewById(R.id.tabKeyboard)
        )
        tabs.forEachIndexed { index, tab -> tab.setOnClickListener { selectTab(index) } }
        selectTab(0)

        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.btnPower).setOnClickListener { v -> haptic(v); powerPressed() }

        bindKey(R.id.btnSource, "KEY_SOURCE")
        bindKey(R.id.btnMenu, "KEY_MENU")
        bindKey(R.id.btnUp, "KEY_UP", repeat = true)
        bindKey(R.id.btnDown, "KEY_DOWN", repeat = true)
        bindKey(R.id.btnLeft, "KEY_LEFT", repeat = true)
        bindKey(R.id.btnRight, "KEY_RIGHT", repeat = true)
        bindKey(R.id.btnOk, "KEY_ENTER")
        bindKey(R.id.btnBack, "KEY_RETURN")
        bindKey(R.id.btnHome, "KEY_HOME")
        bindKey(R.id.btnExit, "KEY_EXIT")
        bindKey(R.id.btnVolUp, "KEY_VOLUP", repeat = true)
        bindKey(R.id.btnVolDown, "KEY_VOLDOWN", repeat = true)
        bindKey(R.id.btnMute, "KEY_MUTE")
        bindKey(R.id.btnChUp, "KEY_CHUP")
        bindKey(R.id.btnChDown, "KEY_CHDOWN")
        bindKey(R.id.btnInfo, "KEY_INFO")
        bindKey(R.id.btnRew, "KEY_REWIND")
        bindKey(R.id.btnPlay, "KEY_PLAY")
        bindKey(R.id.btnPause, "KEY_PAUSE")
        bindKey(R.id.btnFf, "KEY_FF")

        findViewById<View>(R.id.btnRefreshApps).setOnClickListener {
            if (tv.requestApps()) appsStatus.text = "Loading apps from TV..." else notConnected()
        }
        findViewById<View>(R.id.btnKbSend).setOnClickListener { v -> haptic(v); sendKeyboardText() }
        findViewById<View>(R.id.btnKbEnter).setOnClickListener { v -> haptic(v); sendKey("KEY_ENTER") }
        findViewById<View>(R.id.btnKbClear).setOnClickListener { kbInput.setText("") }
        kbInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendKeyboardText()
                true
            } else {
                false
            }
        }

        updateStatus(TvClient.State.DISCONNECTED, null)
        if (savedIp().isEmpty()) ui.post { showSettings() }
    }

    override fun onResume() {
        super.onResume()
        isActive = true
        reconnectAttempts = 0
        connectIfConfigured()
    }

    override fun onPause() {
        isActive = false
        ui.removeCallbacks(reconnectRunnable)
        ui.removeCallbacks(appsFallback)
        tv.disconnect()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    // Phone volume buttons drive the TV volume while the app is in front.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (tv.state == TvClient.State.CONNECTED) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    tv.sendKey("KEY_VOLUP")
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    tv.sendKey("KEY_VOLDOWN")
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (tv.state == TvClient.State.CONNECTED &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------- TvClient.Listener (called from background threads) ----------

    override fun onStateChanged(state: TvClient.State, message: String?) {
        ui.post {
            updateStatus(state, message)
            when (state) {
                TvClient.State.CONNECTED -> {
                    reconnectAttempts = 0
                    onConnected()
                }
                TvClient.State.DISCONNECTED -> {
                    val paired = savedToken().isNotEmpty()
                    if (isActive && paired && reconnectAttempts < MAX_RECONNECTS) {
                        reconnectAttempts++
                        ui.removeCallbacks(reconnectRunnable)
                        ui.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
                    }
                }
                else -> Unit
            }
        }
    }

    override fun onTokenReceived(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun onAppsReceived(apps: List<TvApp>) {
        ui.post {
            if (apps.isEmpty()) return@post
            appsLoaded = true
            ui.removeCallbacks(appsFallback)
            renderApps(apps.sortedBy { it.name.lowercase() }, "${apps.size} apps on your TV")
        }
    }

    override fun onImeChanged(active: Boolean) {
        ui.post {
            if (active) {
                kbStatus.text = "The TV keyboard is open. Type below and tap Send."
                selectTab(2)
                kbInput.requestFocus()
            } else {
                kbStatus.text = "Open a search or text box on the TV, then type here."
            }
        }
    }

    // ---------- Connection ----------

    private fun connectIfConfigured() {
        val ip = savedIp()
        if (ip.isEmpty() || !isActive) return
        when (tv.state) {
            TvClient.State.CONNECTING,
            TvClient.State.WAITING_FOR_APPROVAL,
            TvClient.State.CONNECTED -> return
            else -> tv.connect(ip, savedToken().ifEmpty { null })
        }
    }

    private fun onConnected() {
        appsLoaded = false
        appsStatus.text = "Loading apps from TV..."
        ui.removeCallbacks(appsFallback)
        ui.postDelayed(appsFallback, 4000)

        val ip = savedIp()
        io.execute {
            val info = Discovery.fetchInfo(ip) ?: return@execute
            ui.post {
                val editor = prefs.edit()
                if (WakeOnLan.isValidMac(info.mac)) editor.putString(KEY_MAC, info.mac)
                if (info.name.isNotBlank()) editor.putString(KEY_NAME, info.name)
                editor.apply()
                if (tv.state == TvClient.State.CONNECTED) statusText.text = tvName()
            }
        }
    }

    private fun powerPressed() {
        val ip = savedIp()
        if (ip.isEmpty()) {
            showSettings()
            return
        }
        val mac = prefs.getString(KEY_MAC, "").orEmpty()

        if (tv.state == TvClient.State.CONNECTED) {
            // A TV in network standby can still accept the socket; wake it instead of toggling.
            io.execute {
                val info = Discovery.fetchInfo(ip, 1500)
                val inStandby = info?.powerState.equals("standby", ignoreCase = true)
                if (inStandby && WakeOnLan.isValidMac(mac)) {
                    WakeOnLan.send(mac, ip)
                    ui.post { setDetail("Switching the TV on...") }
                } else {
                    ui.post { sendKey("KEY_POWER") }
                }
            }
            return
        }

        if (!WakeOnLan.isValidMac(mac)) {
            toast("The TV's MAC address is not known yet. Connect once while the TV is on, or enter it in Settings.")
            return
        }
        setDetail("Sending power-on signal...")
        io.execute {
            val ok = WakeOnLan.send(mac, ip)
            ui.post {
                if (!ok) {
                    toast("Could not send the power-on signal.")
                    setDetail(null)
                } else {
                    setDetail("Power-on signal sent. Connecting when the TV is ready...")
                    reconnectAttempts = 0
                    ui.removeCallbacks(reconnectRunnable)
                    ui.postDelayed(reconnectRunnable, 4000)
                }
            }
        }
    }

    // ---------- Buttons ----------

    private fun bindKey(id: Int, key: String, repeat: Boolean = false) {
        val view = findViewById<View>(id)
        view.setOnClickListener { v -> haptic(v); sendKey(key) }
        if (!repeat) return

        val repeater = object : Runnable {
            override fun run() {
                sendKey(key)
                ui.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    haptic(v)
                    sendKey(key)
                    ui.postDelayed(repeater, REPEAT_START_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    ui.removeCallbacks(repeater)
                }
            }
            true
        }
    }

    private fun sendKey(key: String) {
        if (!tv.sendKey(key)) notConnected()
    }

    private fun sendKeyboardText() {
        val text = kbInput.text.toString()
        if (text.isEmpty()) return
        if (tv.sendText(text)) toast("Sent to TV") else notConnected()
    }

    private fun notConnected() {
        val now = System.currentTimeMillis()
        if (now - lastToastAt > 2500) {
            lastToastAt = now
            val waiting = tv.state == TvClient.State.CONNECTING || tv.state == TvClient.State.WAITING_FOR_APPROVAL
            toast(if (waiting) "Still connecting to the TV" else "Not connected. Trying to reconnect...")
        }
        if (tv.state == TvClient.State.DISCONNECTED || tv.state == TvClient.State.DENIED) {
            reconnectAttempts = 0
            connectIfConfigured()
        }
    }

    // ---------- UI ----------

    private fun selectTab(index: Int) {
        currentTab = index
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, tab ->
            val selected = i == index
            tab.setTextColor(getColor(if (selected) R.color.accent else R.color.text_dim))
            tab.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun updateStatus(state: TvClient.State, message: String?) {
        if (savedIp().isEmpty()) {
            statusDot.background.mutate().setTint(getColor(R.color.status_bad))
            statusText.text = "No TV set up"
            setDetail("Tap Settings to find your TV.")
            return
        }
        val (colorRes, text) = when (state) {
            TvClient.State.CONNECTED -> R.color.status_ok to tvName()
            TvClient.State.CONNECTING -> R.color.status_wait to "Connecting..."
            TvClient.State.WAITING_FOR_APPROVAL -> R.color.status_wait to "Waiting for TV"
            TvClient.State.DENIED -> R.color.status_bad to "Not allowed"
            TvClient.State.DISCONNECTED -> R.color.status_bad to "Not connected"
        }
        statusDot.background.mutate().setTint(getColor(colorRes))
        statusText.text = text
        setDetail(message)
    }

    private fun setDetail(message: String?) {
        statusDetail.text = message.orEmpty()
        statusDetail.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun renderApps(apps: List<TvApp>, status: String) {
        appsStatus.text = status
        appsGrid.removeAllViews()
        for (app in apps) {
            val button = Button(this, null, 0, R.style.RemoteButton)
            button.text = app.name
            button.maxLines = 2
            button.ellipsize = TextUtils.TruncateAt.END
            button.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = dp(64)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            button.setOnClickListener { v ->
                haptic(v)
                if (!tv.launchApp(app)) notConnected()
            }
            appsGrid.addView(button)
        }
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val ipInput = view.findViewById<EditText>(R.id.settingsIp)
        val macInput = view.findViewById<EditText>(R.id.settingsMac)
        val discoverButton = view.findViewById<Button>(R.id.settingsDiscover)
        val discoverStatus = view.findViewById<TextView>(R.id.settingsDiscoverStatus)
        val found = view.findViewById<LinearLayout>(R.id.settingsFound)

        ipInput.setText(savedIp())
        macInput.setText(prefs.getString(KEY_MAC, ""))

        discoverButton.setOnClickListener {
            runDiscovery(discoverButton, discoverStatus, found, ipInput, macInput)
        }
        view.findViewById<View>(R.id.settingsForget).setOnClickListener {
            prefs.edit().remove(KEY_TOKEN).apply()
            tv.disconnect()
            toast("Pairing removed. The TV will ask to allow this phone again.")
        }

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("TV settings")
            .setView(view)
            .setPositiveButton("Save and connect") { _, _ ->
                val newIp = ipInput.text.toString().trim()
                val newMac = macInput.text.toString().trim()
                val editor = prefs.edit().putString(KEY_IP, newIp).putString(KEY_MAC, newMac)
                if (newIp != savedIp()) editor.remove(KEY_TOKEN).remove(KEY_NAME)
                editor.apply()
                tv.disconnect()
                reconnectAttempts = 0
                updateStatus(TvClient.State.DISCONNECTED, null)
                connectIfConfigured()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runDiscovery(
        button: Button,
        status: TextView,
        found: LinearLayout,
        ipInput: EditText,
        macInput: EditText
    ) {
        button.isEnabled = false
        status.text = "Searching your Wi-Fi network. This can take up to 20 seconds..."
        found.removeAllViews()
        io.execute {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            val lock = wifi?.createMulticastLock("tvremote")
            lock?.setReferenceCounted(false)
            lock?.acquire()
            val tvs = try {
                Discovery.discover(Discovery.localIpv4(this@MainActivity))
            } finally {
                lock?.release()
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                button.isEnabled = true
                if (tvs.isEmpty()) {
                    status.text = "No Samsung TV found. Check that the TV is on and on the same Wi-Fi, or type its IP address below."
                    return@post
                }
                status.text = "Tap your TV:"
                for (info in tvs) {
                    val item = Button(this, null, 0, R.style.RemoteButton)
                    item.text = "${info.name}\n${info.ip}"
                    item.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dp(8), 0, 0) }
                    item.minHeight = dp(56)
                    item.setPadding(dp(12), dp(8), dp(12), dp(8))
                    item.setOnClickListener {
                        ipInput.setText(info.ip)
                        if (WakeOnLan.isValidMac(info.mac)) macInput.setText(info.mac)
                        status.text = "Selected ${info.name}. Tap Save and connect."
                    }
                    found.addView(item)
                }
            }
        }
    }

    // ---------- Helpers ----------

    private fun savedIp(): String = prefs.getString(KEY_IP, "").orEmpty().trim()

    private fun savedToken(): String = prefs.getString(KEY_TOKEN, "").orEmpty()

    private fun tvName(): String = prefs.getString(KEY_NAME, "").orEmpty().ifBlank { "Samsung TV" }

    private fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_IP = "ip"
        private const val KEY_MAC = "mac"
        private const val KEY_TOKEN = "token"
        private const val KEY_NAME = "name"

        private const val MAX_RECONNECTS = 6
        private const val RECONNECT_DELAY_MS = 3000L
        private const val REPEAT_START_MS = 450L
        private const val REPEAT_INTERVAL_MS = 120L

        // Shown when the TV does not return its installed-app list.
        private val DEFAULT_APPS = listOf(
            TvApp("3201907018807", "Netflix", 2),
            TvApp("111299001912", "YouTube", 2),
            TvApp("3201910019365", "Prime Video", 2),
            TvApp("3201807016597", "Apple TV", 2),
            TvApp("3201606009684", "Spotify", 2)
        )
    }
}
