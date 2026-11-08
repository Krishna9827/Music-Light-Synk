package com.musiclightsynk

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * TV-friendly settings + status screen, built in code (no XML) and fully D-pad navigable.
 * Everything here can also be set over ADB - see [AdbCommandReceiver].
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var accessStatus: TextView
    private lateinit var captureStatus: TextView
    private lateinit var nowStatus: TextView
    private lateinit var resultStatus: TextView
    private lateinit var captureButton: Button
    private lateinit var profileButton: Button
    private lateinit var noteStatus: TextView
    private val fields = ArrayList<BoundField>()
    private val labelRefreshers = ArrayList<() -> Unit>()
    private val swatches = LinkedHashMap<Slot, TextView>()

    private val main = Handler(Looper.getMainLooper())
    private val refresher = Runnable { refreshStatus() }
    private val applySettings = Runnable { SyncEngine.settingsChanged(this) }

    private val bg = Color.parseColor("#101114")
    private val fg = Color.parseColor("#ECEDEF")
    private val muted = Color.parseColor("#9AA0A6")
    private val good = Color.parseColor("#66BB6A")
    private val bad = Color.parseColor("#EF5350")
    private val accent = Color.parseColor("#FFB300")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(bg)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        SyncEngine.addListener(refresher)
        MediaListenerService.requestRebindIfNeeded(this)
        refreshStatus()
    }

    override fun onPause() {
        SyncEngine.removeListener(refresher)
        saveFields(silent = true)
        super.onPause()
    }

    // ---- UI construction ---------------------------------------------------------------------

    private fun buildUi(): View {
        val wide = resources.configuration.screenWidthDp >= 720
        val root = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(48), dp(27), dp(48), dp(27)) // TV overscan-safe margins
        }
        val left = column()
        val right = column()
        root.addView(left, LinearLayout.LayoutParams(if (wide) 0 else MATCH_PARENT, WRAP_CONTENT, 1f).apply {
            if (wide) marginEnd = dp(40)
        })
        root.addView(right, LinearLayout.LayoutParams(if (wide) 0 else MATCH_PARENT, WRAP_CONTENT, 1f))

        // Left column: status + preview
        left.addView(text("Music Light Synk", 28f, fg, bold = true))
        left.addView(text("Album art -> corner + side colours -> Home Assistant lights", 14f, muted))

        header(left, "Status")
        accessStatus = text("", 15f, fg).also { left.addView(it) }
        left.addView(button("Grant media access") { openListenerSettings() })
        captureStatus = text("", 15f, fg).also { left.addView(it) }
        captureButton = button("Start screen capture") { toggleCapture() }.also { left.addView(it) }
        nowStatus = text("", 14f, muted).also { left.addView(it) }
        profileButton = button("Profile for this app") { cycleProfile() }.also { left.addView(it) }
        resultStatus = text("", 14f, muted).also { left.addView(it) }
        noteStatus = text("", 13f, accent).also { left.addView(it) }

        header(left, "Last colours (laid out like your TV)")
        left.addView(previewGrid())
        left.addView(button("Send test colours") {
            saveFields(silent = true)
            SyncEngine.sendTest(this) { msg -> toast(msg) }
        })

        // Right column: settings
        header(right, "Home Assistant")
        field(right, "Home Assistant URL", "http://192.168.1.10:8123",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            { prefs.haUrl }, { prefs.haUrl = it })
        field(right, "Webhook ID(s), comma separated (blueprint, integration)", Prefs.DEFAULT_WEBHOOK,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            { prefs.webhookId }, { prefs.webhookId = it.ifBlank { Prefs.DEFAULT_WEBHOOK } })
        field(right, "Long-lived token (optional, adds sensor.music_light_synk)", "optional",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            { prefs.token }, { prefs.token = it })
        right.addView(button("Save") { saveFields(silent = false) })

        header(right, "Colours")
        right.addView(cycleButton({ "Source: ${prefs.sourceMode.label}" }) {
            prefs.sourceMode = prefs.sourceMode.next()
        })
        stepper(right, "Edge / corner size", "%", 5, Prefs.CORNER_RANGE, { prefs.cornerPct }, { prefs.cornerPct = it })
        stepper(right, "Saturation boost", "%", 10, Prefs.SATURATION_RANGE, { prefs.saturationPct }, { prefs.saturationPct = it })
        stepper(right, "Minimum brightness", "%", 5, Prefs.MIN_BRIGHTNESS_RANGE, { prefs.minBrightnessPct }, { prefs.minBrightnessPct = it })
        stepper(right, "Re-sample screen every (0 = per track)", "s", 5, Prefs.SCREEN_INTERVAL_RANGE, { prefs.screenIntervalSec }, { prefs.screenIntervalSec = it })
        stepper(right, "Send 'stopped' after pause of", "s", 5, Prefs.STOP_DELAY_RANGE, { prefs.stopDelaySec }, { prefs.stopDelaySec = it })
        right.addView(cycleButton({ "Syncing: " + if (prefs.enabled) "ON" else "OFF" }) {
            prefs.enabled = !prefs.enabled
        })
        right.addView(cycleButton({ "Restart screen capture after reboot: " + if (prefs.bootCapture) "ON" else "OFF" }) {
            prefs.bootCapture = !prefs.bootCapture
        })

        header(right, "MQTT (optional)")
        right.addView(cycleButton({ "MQTT: " + if (prefs.mqttEnabled) "ON" else "OFF" }) {
            prefs.mqttEnabled = !prefs.mqttEnabled
        })
        field(right, "Broker host / IP", "192.168.1.10", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            { prefs.mqttHost }, { prefs.mqttHost = it })
        field(right, "Port", "1883", InputType.TYPE_CLASS_NUMBER,
            { prefs.mqttPort.toString() }, { v -> v.trim().toIntOrNull()?.let { prefs.mqttPort = it } })
        field(right, "Username", "optional", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            { prefs.mqttUser }, { prefs.mqttUser = it })
        field(right, "Password", "optional", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            { prefs.mqttPassword }, { prefs.mqttPassword = it })
        field(right, "Topic prefix (one per TV)", Prefs.DEFAULT_MQTT_TOPIC, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            { prefs.mqttTopic }, { prefs.mqttTopic = it })
        right.addView(cycleButton({ "Home Assistant discovery: " + if (prefs.mqttDiscovery) "ON" else "OFF" }) {
            prefs.mqttDiscovery = !prefs.mqttDiscovery
        })
        right.addView(button("Save MQTT") { saveFields(silent = false) })

        header(right, "WLED direct (skips Home Assistant)")
        right.addView(cycleButton({ "WLED: ${prefs.wledMode.label}" }) { prefs.wledMode = prefs.wledMode.next() })
        field(right, "WLED IPs, comma separated", "192.168.1.60", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            { prefs.wledHostsRaw }, { prefs.wledHostsRaw = it })
        field(right, "LED counts top,right,bottom,left (ambient)", "40,22,40,22", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            { prefs.wledLayoutRaw }, { prefs.wledLayoutRaw = it })
        right.addView(cycleButton({ "Strip starts at: ${prefs.wledStart.label}" }) { prefs.wledStart = prefs.wledStart.next() })
        right.addView(cycleButton({ "Strip direction: " + if (prefs.wledClockwise) "clockwise" else "counter-clockwise" }) {
            prefs.wledClockwise = !prefs.wledClockwise
        })
        stepper(right, "WLED brightness", "%", 10, 10..100, { prefs.wledBrightnessPct }, { prefs.wledBrightnessPct = it })
        right.addView(cycleButton({ "Restore WLED when music stops: " + if (prefs.wledRestore) "ON" else "OFF" }) {
            prefs.wledRestore = !prefs.wledRestore
        })
        right.addView(cycleButton({ "Beat pulse (ambient mode): " + if (prefs.pulseEnabled) "ON" else "OFF" }) {
            prefs.pulseEnabled = !prefs.pulseEnabled
            if (prefs.pulseEnabled) ensureAudioPermission()
        })
        stepper(right, "Pulse strength", "%", 10, 10..100, { prefs.pulseStrengthPct }, { prefs.pulseStrengthPct = it })
        right.addView(button("Save WLED") { saveFields(silent = false) })

        header(right, "Set up from a computer (ADB)")
        right.addView(text(ADB_HELP, 12f, muted).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(false) })

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun header(parent: LinearLayout, s: String) {
        parent.addView(text(s.uppercase(), 13f, accent, bold = true).apply { setPadding(0, dp(18), 0, dp(4)) })
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setOnClickListener { onClick() }
    }

    /** A button whose label reflects a setting; pressing it changes the setting. */
    private fun cycleButton(label: () -> String, onPress: () -> Unit): Button {
        val b = button(label()) {}
        b.setOnClickListener {
            onPress()
            b.text = label()
            settingsTouched()
        }
        labelRefreshers += { b.text = label() }
        return b
    }

    /**
     * Text field bound to a preference. Only the user's own edits are written back, so values
     * pushed over ADB while this screen is open are picked up instead of being overwritten.
     */
    private inner class BoundField(val edit: EditText, val get: () -> String, val set: (String) -> Unit) {
        var synced: String = get()
        val dirty: Boolean get() = edit.text.toString() != synced

        fun pullIfClean() {
            val v = get()
            if (!dirty && v != synced) {
                edit.setText(v)
                synced = v
            }
        }

        fun push(): Boolean {
            if (!dirty) return false
            set(edit.text.toString())
            synced = get()
            if (edit.text.toString() != synced) edit.setText(synced)
            return true
        }
    }

    private fun field(parent: LinearLayout, label: String, hint: String, type: Int, get: () -> String, set: (String) -> Unit) {
        parent.addView(text(label, 13f, muted))
        val e = EditText(this).apply {
            setText(get())
            this.hint = hint
            inputType = type
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(fg)
            setHintTextColor(Color.parseColor("#5F6368"))
            textSize = 15f
        }
        parent.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fields += BoundField(e, get, set)
    }

    private fun stepper(
        parent: LinearLayout,
        label: String,
        unit: String,
        step: Int,
        range: IntRange,
        get: () -> Int,
        set: (Int) -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = text("${get()} $unit", 15f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(72)
        }
        fun change(delta: Int) {
            val v = (get() + delta).coerceIn(range)
            set(v)
            value.text = "${get()} $unit"
            settingsTouched()
        }
        labelRefreshers += { value.text = "${get()} $unit" }
        row.addView(text(label, 14f, fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(button("-") { change(-step) }, LinearLayout.LayoutParams(dp(64), WRAP_CONTENT))
        row.addView(value)
        row.addView(button("+") { change(step) }, LinearLayout.LayoutParams(dp(64), WRAP_CONTENT))
        parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun previewGrid(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(height: Int, vararg slots: Slot) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (s in slots) {
                val tv = TextView(this@MainActivity).apply {
                    gravity = Gravity.CENTER
                    textSize = 12f
                    text = s.label
                    setTextColor(muted)
                    background = swatchBackground(Color.parseColor("#26282C"))
                }
                swatches[s] = tv
                addView(tv, LinearLayout.LayoutParams(0, dp(height), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
        }
        // Laid out like the TV: corners and sides around the edge, dominant in the middle.
        box.addView(row(50, Slot.TOP_LEFT, Slot.TOP, Slot.TOP_RIGHT))
        box.addView(row(50, Slot.LEFT, Slot.DOMINANT, Slot.RIGHT))
        box.addView(row(50, Slot.BOTTOM_LEFT, Slot.BOTTOM, Slot.BOTTOM_RIGHT))
        box.addView(row(36, Slot.VIBRANT, Slot.AVERAGE))
        return box
    }

    private fun swatchBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
    }

    // ---- behaviour ---------------------------------------------------------------------------

    private fun settingsTouched() {
        // Debounce so tapping "+" five times sends one update, not five.
        main.removeCallbacks(applySettings)
        main.postDelayed(applySettings, 900)
    }

    private fun saveFields(silent: Boolean) {
        var changed = false
        for (f in fields) if (f.push()) changed = true
        if (changed) settingsTouched()
        if (!silent) toast(if (prefs.hasOutput) "Saved" else "Saved - no output set up yet (Home Assistant, MQTT or WLED)")
    }

    private fun refreshStatus() {
        if (!::accessStatus.isInitialized) return
        for (f in fields) f.pullIfClean()
        for (r in labelRefreshers) r()
        val granted = MediaListenerService.isEnabled(this)
        val (accessText, accessColor) = when {
            granted && MediaListenerService.connected -> "Media access: OK, listening to media apps" to good
            granted -> "Media access: granted, waiting for Android to connect..." to accent
            else -> "Media access: NOT granted - press the button below or run the ADB setup" to bad
        }
        accessStatus.text = accessText
        accessStatus.setTextColor(accessColor)

        val running = ScreenCaptureService.isRunning
        captureStatus.text = if (running) "Screen capture: running (used for Auto fallback / Screen mode)"
        else "Screen capture: off (only needed when an app has no album art)"
        captureStatus.setTextColor(if (running) good else muted)
        captureButton.text = if (running) "Stop screen capture" else "Start screen capture"

        val s = SyncEngine.status
        nowStatus.text = "Now: ${s.nowPlaying}"
        resultStatus.text = "Last: ${s.lastResult}"
        noteStatus.text = s.note ?: ""
        noteStatus.visibility = if (s.note.isNullOrEmpty()) View.GONE else View.VISIBLE
        val pkg = s.nowPackage
        profileButton.isEnabled = pkg != null
        profileButton.text = if (pkg == null) "Per-app profile: play something first"
        else "This app uses: ${prefs.profileFor(pkg).label}" + if (prefs.profileFor(pkg) == AppProfile.DEFAULT) " (${prefs.sourceMode.key})" else ""

        val palette = s.palette
        for ((slot, tv) in swatches) {
            if (palette == null) continue
            val c = palette[slot].light
            val color = Color.rgb(c.r, c.g, c.b)
            tv.background = swatchBackground(color)
            tv.text = "${slot.label}\n${c.hex}  ${palette[slot].brightnessPct}%"
            tv.setTextColor(if (c.luminance > 0.55f) Color.BLACK else Color.WHITE)
        }
    }

    /** Cycle the per-app profile of whatever is playing: default -> artwork -> screen -> auto -> ignore. */
    private fun cycleProfile() {
        val pkg = SyncEngine.status.nowPackage ?: return
        prefs.setProfile(pkg, prefs.profileFor(pkg).next())
        refreshStatus()
        settingsTouched()
    }

    private fun ensureAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            toast(if (granted) "Audio access granted - beat pulse ready" else "Beat pulse needs audio access")
            settingsTouched()
        }
    }

    private fun toggleCapture() {
        if (ScreenCaptureService.isRunning) {
            ScreenCaptureService.stop(this)
        } else {
            startActivity(Intent(this, CaptureConsentActivity::class.java))
        }
    }

    private fun openListenerSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            toast("Enable \"Music Light Synk\" in the list")
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Grant media access over ADB")
                .setMessage(
                    "Fire TV hides the notification-access screen. From a computer on the same network run:\n\n" +
                        "adb connect <fire-tv-ip>\n" +
                        "adb shell cmd notification allow_listener " +
                        "$packageName/${MediaListenerService::class.java.name}\n\n" +
                        "or just run scripts/setup-firetv.sh from the project.",
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val ADB_HELP =
            "adb connect <fire-tv-ip>\n" +
                "adb shell cmd notification allow_listener \\\n" +
                "  com.musiclightsynk/com.musiclightsynk.MediaListenerService\n" +
                "adb shell appops set com.musiclightsynk PROJECT_MEDIA allow\n" +
                "adb shell appops set com.musiclightsynk SYSTEM_ALERT_WINDOW allow\n" +
                "adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver \\\n" +
                "  -a com.musiclightsynk.CONFIGURE --es ha_url http://<ha-ip>:8123 \\\n" +
                "  --es webhook_id music_light_synk\n" +
                "adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver \\\n" +
                "  -a com.musiclightsynk.TEST\n\n" +
                "Or run scripts/setup-firetv.sh - it does all of the above."
    }
}
