package com.musiclightsynk

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * TV-friendly settings + status screen, built in code (no XML) and fully D-pad navigable.
 * Everything here can also be set over ADB ([AdbCommandReceiver]) or from Home Assistant.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var accessChip: TextView
    private lateinit var captureChip: TextView
    private lateinit var haChip: TextView
    private lateinit var wledChip: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowApp: TextView
    private lateinit var resultStatus: TextView
    private lateinit var noteStatus: TextView
    private lateinit var captureButton: Button
    private lateinit var accessButton: Button
    private lateinit var profileButton: Button
    private lateinit var wledList: LinearLayout
    private val fields = ArrayList<BoundField>()
    private val labelRefreshers = ArrayList<() -> Unit>()
    private val swatches = LinkedHashMap<Slot, TextView>()

    private val main = Handler(Looper.getMainLooper())
    private val refresher = Runnable { refreshStatus() }
    private val applySettings = Runnable { SyncEngine.settingsChanged(this) }

    private val bg = Color.parseColor("#0E0F12")
    private val card = Color.parseColor("#181A1F")
    private val cardEdge = Color.parseColor("#24272E")
    private val control = Color.parseColor("#262A31")
    private val controlPressed = Color.parseColor("#353A43")
    private val fg = Color.parseColor("#ECEDEF")
    private val muted = Color.parseColor("#9AA0A6")
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
        renderWledDevices()
        refreshStatus()
    }

    override fun onPause() {
        SyncEngine.removeListener(refresher)
        saveFields(silent = true)
        super.onPause()
    }

    // ---- layout --------------------------------------------------------------------------------

    private fun buildUi(): View {
        val wide = resources.configuration.screenWidthDp >= 720
        val page = column().apply { setPadding(dp(48), dp(24), dp(48), dp(32)) } // TV overscan-safe
        page.addView(headerRow())

        val columns = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val left = column()
        val right = column()
        columns.addView(left, LinearLayout.LayoutParams(if (wide) 0 else MATCH_PARENT, WRAP_CONTENT, 1f).apply {
            if (wide) marginEnd = dp(20)
        })
        columns.addView(right, LinearLayout.LayoutParams(if (wide) 0 else MATCH_PARENT, WRAP_CONTENT, 1.15f))
        page.addView(columns)

        // ---- left: what's happening
        cardOf(left, "Now playing").apply {
            nowTitle = text("Nothing playing", 20f, fg, bold = true).also { addView(it) }
            nowApp = text("", 14f, muted).also { addView(it) }
            profileButton = button("Profile for this app") { cycleProfile() }.also { addView(it, fullWidth()) }
            resultStatus = text("", 13f, muted).also { addView(it) }
            noteStatus = text("", 13f, accent).also { addView(it) }
        }

        cardOf(left, "Colours", "Laid out like your TV").apply {
            addView(previewGrid())
            addView(button("Send test colours", primary = true) {
                saveFields(silent = true)
                SyncEngine.sendTest(this@MainActivity) { msg -> toast(msg) }
            }, fullWidth())
        }

        cardOf(left, "Status & permissions").apply {
            accessChip = chip().also { addView(it) }
            captureChip = chip().also { addView(it) }
            haChip = chip().also { addView(it) }
            wledChip = chip().also { addView(it) }
            val r = row()
            accessButton = button("Grant media access") { openListenerSettings() }
            captureButton = button("Start screen capture") { toggleCapture() }
            r.addView(accessButton, weighted())
            r.addView(captureButton, weighted())
            addView(r)
        }

        // ---- right: settings
        cardOf(right, "Home Assistant").apply {
            field(this, "Home Assistant URL", "http://192.168.1.10:8123",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                { prefs.haUrl }, { prefs.haUrl = it })
            field(this, "Webhook ID(s), comma separated", Prefs.DEFAULT_WEBHOOK,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                { prefs.webhookId }, { prefs.webhookId = it.ifBlank { Prefs.DEFAULT_WEBHOOK } })
            field(this, "Long-lived token (optional)", "optional",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                { prefs.token }, { prefs.token = it })
            addView(button("Save", primary = true) { saveFields(silent = false) }, fullWidth())
        }

        cardOf(right, "WLED devices", "Each WLED shows its own part of the artwork. Talks to WLED directly, instant.").apply {
            wledList = column()
            addView(wledList)
            addView(addWledRow())
            addView(toggleButton({ "WLED output: " + onOff(prefs.wledEnabled) }) { prefs.wledEnabled = !prefs.wledEnabled }, fullWidth())
            stepper(this, "WLED brightness", "%", 10, 10..100, { prefs.wledBrightnessPct }, { prefs.wledBrightnessPct = it })
            addView(toggleButton({ "Restore WLED when music stops: " + onOff(prefs.wledRestore) }) {
                prefs.wledRestore = !prefs.wledRestore
            }, fullWidth())
            addView(toggleButton({ "Beat pulse (ambient strips): " + onOff(prefs.pulseEnabled) }) {
                prefs.pulseEnabled = !prefs.pulseEnabled
                if (prefs.pulseEnabled) ensureAudioPermission()
            }, fullWidth())
            stepper(this, "Pulse strength", "%", 10, 10..100, { prefs.pulseStrengthPct }, { prefs.pulseStrengthPct = it })
        }

        cardOf(right, "Colour source & tuning").apply {
            addView(toggleButton({ "Source: ${prefs.sourceMode.label}" }) { prefs.sourceMode = prefs.sourceMode.next() }, fullWidth())
            stepper(this, "Edge / corner size", "%", 5, Prefs.CORNER_RANGE, { prefs.cornerPct }, { prefs.cornerPct = it })
            stepper(this, "Saturation boost", "%", 10, Prefs.SATURATION_RANGE, { prefs.saturationPct }, { prefs.saturationPct = it })
            stepper(this, "Minimum brightness", "%", 5, Prefs.MIN_BRIGHTNESS_RANGE, { prefs.minBrightnessPct }, { prefs.minBrightnessPct = it })
            stepper(this, "Re-sample screen every (0 = per track)", "s", 5, Prefs.SCREEN_INTERVAL_RANGE, { prefs.screenIntervalSec }, { prefs.screenIntervalSec = it })
            stepper(this, "Send 'stopped' after a pause of", "s", 5, Prefs.STOP_DELAY_RANGE, { prefs.stopDelaySec }, { prefs.stopDelaySec = it })
            addView(toggleButton({ "Syncing: " + onOff(prefs.enabled) }) { prefs.enabled = !prefs.enabled }, fullWidth())
            addView(toggleButton({ "Restart screen capture after reboot: " + onOff(prefs.bootCapture) }) {
                prefs.bootCapture = !prefs.bootCapture
            }, fullWidth())
        }

        cardOf(right, "MQTT (optional)").apply {
            addView(toggleButton({ "MQTT: " + onOff(prefs.mqttEnabled) }) { prefs.mqttEnabled = !prefs.mqttEnabled }, fullWidth())
            field(this, "Broker host / IP", "192.168.1.10", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                { prefs.mqttHost }, { prefs.mqttHost = it })
            field(this, "Port", "1883", InputType.TYPE_CLASS_NUMBER,
                { prefs.mqttPort.toString() }, { v -> v.trim().toIntOrNull()?.let { prefs.mqttPort = it } })
            field(this, "Username", "optional", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                { prefs.mqttUser }, { prefs.mqttUser = it })
            field(this, "Password", "optional", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                { prefs.mqttPassword }, { prefs.mqttPassword = it })
            field(this, "Topic prefix (one per TV)", Prefs.DEFAULT_MQTT_TOPIC, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                { prefs.mqttTopic }, { prefs.mqttTopic = it })
            addView(toggleButton({ "Home Assistant discovery: " + onOff(prefs.mqttDiscovery) }) {
                prefs.mqttDiscovery = !prefs.mqttDiscovery
            }, fullWidth())
            addView(button("Save MQTT", primary = true) { saveFields(silent = false) }, fullWidth())
        }

        cardOf(right, "Set up from a computer or Home Assistant").apply {
            addView(text(ADB_HELP, 12f, muted).apply { typeface = Typeface.MONOSPACE })
        }

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(page)
        }
    }

    private fun headerRow(): View {
        val header = row().apply { setPadding(0, 0, 0, dp(16)) }
        val icon = ImageView(this).apply {
            try {
                setImageDrawable(packageManager.getApplicationIcon(packageName))
            } catch (_: Exception) {
            }
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        val titles = column()
        titles.addView(text("Music Light Synk", 26f, fg, bold = true))
        val version = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        titles.addView(text("Album art to your lights" + (version?.let { "  ·  v$it" } ?: ""), 14f, muted))
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return header
    }

    // ---- WLED device list ----------------------------------------------------------------------

    private fun addWledRow(): View {
        val r = row().apply { setPadding(0, dp(6), 0, dp(6)) }
        val input = EditText(this).apply {
            hint = "Add WLED: IP address, e.g. 192.168.1.60"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            styleInput(this)
        }
        r.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        r.addView(button("Add", primary = true) {
            val host = input.text.toString().trim().removePrefix("http://").trimEnd('/')
            if (host.isEmpty() || host.any { it == ' ' || it == '=' || it == ';' }) {
                toast("Type the WLED's IP address first")
                return@button
            }
            prefs.setWledDevices(prefs.wledDevices + WledDevice(host, WledZone.Section(Slot.DOMINANT)))
            input.setText("")
            renderWledDevices()
            settingsTouched()
            toast("Added $host. Press its zone button to choose what it shows.")
        })
        return r
    }

    /** One row per device: host, zone (cycles), zone options, remove. Rebuilt after every change. */
    private fun renderWledDevices(focusIndex: Int = -1, focusRole: Int = 0) {
        if (!::wledList.isInitialized) return
        wledList.removeAllViews()
        val devices = prefs.wledDevices
        if (devices.isEmpty()) {
            wledList.addView(text("No WLED devices yet. Add one below.", 13f, muted))
            return
        }
        devices.forEachIndexed { i, d ->
            val r = row().apply {
                setPadding(dp(10), dp(6), dp(6), dp(6))
                background = rounded(Color.parseColor("#1F2228"), 10)
            }
            r.addView(text(d.host, 14f, fg, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.9f))
            val zoneBtn = button(d.zone.label) { updateDevice(i, 1) { it.copy(zone = WledDevices.nextZone(it.zone)) } }
            r.addView(zoneBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.6f).apply { marginEnd = dp(6) })
            val extra: Button? = when (val z = d.zone) {
                is WledZone.Ambient -> button("Layout") { editLayout(i, z) }
                is WledZone.Section -> button(if (z.keepEffect) "Keep effect" else "Solid") {
                    updateDevice(i, 2) { it.copy(zone = z.copy(keepEffect = !z.keepEffect)) }
                }
                is WledZone.Palette -> null
            }
            if (extra != null) r.addView(extra, LinearLayout.LayoutParams(dp(130), WRAP_CONTENT).apply { marginEnd = dp(6) })
            r.addView(button("Remove") {
                prefs.setWledDevices(prefs.wledDevices.filterIndexed { j, _ -> j != i })
                renderWledDevices(focusIndex = (i - 1).coerceAtLeast(0), focusRole = 1)
                settingsTouched()
            }, LinearLayout.LayoutParams(dp(110), WRAP_CONTENT))
            wledList.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
            if (i == focusIndex) {
                val target = if (focusRole == 2 && extra != null) extra else zoneBtn
                target.post { target.requestFocus() }
            }
        }
    }

    private fun updateDevice(index: Int, focusRole: Int, change: (WledDevice) -> WledDevice) {
        val devices = prefs.wledDevices.toMutableList()
        if (index !in devices.indices) return
        devices[index] = change(devices[index])
        prefs.setWledDevices(devices)
        renderWledDevices(index, focusRole)
        settingsTouched()
    }

    private fun editLayout(index: Int, zone: WledZone.Ambient) {
        var start = zone.layout.start
        var clockwise = zone.layout.clockwise
        val box = column().apply { setPadding(dp(24), dp(12), dp(24), 0) }
        box.addView(text("LED counts: top, right, bottom, left (0 = no LEDs on that side)", 14f, fg))
        val counts = EditText(this).apply {
            setText(with(zone.layout) { "$top,$right,$bottom,$left" })
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            styleInput(this)
        }
        box.addView(counts, fullWidth())
        val startBtn = button("LED 0 is at: ${start.label}") {}
        startBtn.setOnClickListener {
            start = start.next()
            startBtn.text = "LED 0 is at: ${start.label}"
        }
        box.addView(startBtn, fullWidth())
        val dirBtn = button("Direction: " + if (clockwise) "clockwise" else "counter-clockwise") {}
        dirBtn.setOnClickListener {
            clockwise = !clockwise
            dirBtn.text = "Direction: " + if (clockwise) "clockwise" else "counter-clockwise"
        }
        box.addView(dirBtn, fullWidth())
        AlertDialog.Builder(this)
            .setTitle("Strip layout for ${prefs.wledDevices.getOrNull(index)?.host ?: "WLED"}")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val layout = WledLayout.parse(counts.text.toString(), start, clockwise)
                if (layout == null) {
                    toast("Use four numbers: top,right,bottom,left")
                } else {
                    updateDevice(index, 2) { it.copy(zone = WledZone.Ambient(layout)) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- small widget helpers -------------------------------------------------------------------

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun fullWidth() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }

    private fun weighted() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
        marginEnd = dp(6)
        topMargin = dp(6)
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

    /** A titled card; returns the container to add content to. */
    private fun cardOf(parent: LinearLayout, title: String, subtitle: String? = null): LinearLayout {
        val c = column().apply {
            background = rounded(card, 16, cardEdge)
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        c.addView(text(title.uppercase(), 13f, accent, bold = true).apply { letterSpacing = 0.08f })
        if (subtitle != null) c.addView(text(subtitle, 12f, muted))
        parent.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })
        return c
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun chip() = TextView(this).apply {
        textSize = 14f
        setTextColor(fg)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(Color.parseColor("#20232A"), 20)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }

    private fun setChip(chip: TextView, ok: Boolean?, label: String) {
        val dot = when (ok) {
            true -> "#66BB6A"
            false -> "#EF5350"
            null -> "#9AA0A6"
        }
        chip.text = Html.fromHtml("<font color='$dot'>&#9679;</font>&nbsp;&nbsp;" + TextUtils.htmlEncode(label), Html.FROM_HTML_MODE_LEGACY)
    }

    private fun buttonBackground(primary: Boolean): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(accent, 10))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(controlPressed, 10))
        addState(intArrayOf(), rounded(if (primary) Color.parseColor("#3A3220") else control, 10, if (primary) accent else null))
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        minHeight = dp(46)
        minimumHeight = dp(46)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = buttonBackground(primary)
        stateListAnimator = null
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.parseColor("#14151A"), Color.parseColor("#5F6368"), fg),
            ),
        )
        setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(120).start()
        }
        setOnClickListener { onClick() }
    }

    /** A button whose label reflects a setting; pressing it changes the setting. */
    private fun toggleButton(label: () -> String, onPress: () -> Unit): Button {
        val b = button(label()) {}
        b.setOnClickListener {
            onPress()
            b.text = label()
            settingsTouched()
        }
        labelRefreshers += { b.text = label() }
        return b
    }

    private fun styleInput(e: EditText) {
        e.setTextColor(fg)
        e.setHintTextColor(Color.parseColor("#5F6368"))
        e.textSize = 15f
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        e.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Color.parseColor("#23262C"), 10, accent, 2))
            addState(intArrayOf(), rounded(Color.parseColor("#23262C"), 10, Color.parseColor("#33373F")))
        }
    }

    /**
     * Text field bound to a preference. Only the user's own edits are written back, so values
     * pushed over ADB / from Home Assistant while this screen is open are picked up instead.
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
        parent.addView(text(label, 13f, muted).apply { setPadding(0, dp(8), 0, dp(2)) })
        val e = EditText(this).apply {
            setText(get())
            this.hint = hint
            inputType = type
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            styleInput(this)
        }
        parent.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fields += BoundField(e, get, set)
    }

    private fun stepper(parent: LinearLayout, label: String, unit: String, step: Int, range: IntRange, get: () -> Int, set: (Int) -> Unit) {
        val r = row().apply { setPadding(0, dp(4), 0, dp(4)) }
        val value = text("${get()} $unit", 15f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(76)
        }
        fun change(delta: Int) {
            set((get() + delta).coerceIn(range))
            value.text = "${get()} $unit"
            settingsTouched()
        }
        labelRefreshers += { value.text = "${get()} $unit" }
        r.addView(text(label, 14f, fg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(button("−") { change(-step) }, LinearLayout.LayoutParams(dp(58), WRAP_CONTENT))
        r.addView(value)
        r.addView(button("+") { change(step) }, LinearLayout.LayoutParams(dp(58), WRAP_CONTENT))
        parent.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun previewGrid(): View {
        val box = column().apply { setPadding(0, dp(6), 0, dp(4)) }
        fun gridRow(height: Int, vararg slots: Slot) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (s in slots) {
                val tv = TextView(this@MainActivity).apply {
                    gravity = Gravity.CENTER
                    textSize = 11f
                    text = s.label
                    setTextColor(muted)
                    background = rounded(Color.parseColor("#22252B"), 10)
                }
                swatches[s] = tv
                addView(tv, LinearLayout.LayoutParams(0, dp(height), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
        }
        box.addView(gridRow(52, Slot.TOP_LEFT, Slot.TOP, Slot.TOP_RIGHT))
        box.addView(gridRow(52, Slot.LEFT, Slot.DOMINANT, Slot.RIGHT))
        box.addView(gridRow(52, Slot.BOTTOM_LEFT, Slot.BOTTOM, Slot.BOTTOM_RIGHT))
        box.addView(gridRow(34, Slot.VIBRANT, Slot.AVERAGE))
        return box
    }

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    // ---- behaviour -------------------------------------------------------------------------------

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
        if (!::accessChip.isInitialized) return
        for (f in fields) f.pullIfClean()
        for (r in labelRefreshers) r()

        val granted = MediaListenerService.isEnabled(this)
        val connected = MediaListenerService.connected
        setChip(accessChip, if (granted && connected) true else if (granted) null else false, when {
            granted && connected -> "Media access: listening to media apps"
            granted -> "Media access: granted, waiting for Android to connect"
            else -> "Media access: NOT granted"
        })
        accessButton.visibility = if (granted && connected) View.GONE else View.VISIBLE

        val running = ScreenCaptureService.isRunning
        setChip(captureChip, if (running) true else null,
            if (running) "Screen capture: running" else "Screen capture: off (only needed for apps without album art)")
        captureButton.text = if (running) "Stop screen capture" else "Start screen capture"

        setChip(haChip, if (prefs.isHaConfigured) true else null,
            if (prefs.isHaConfigured) "Home Assistant: ${prefs.haUrl}" else "Home Assistant: not set up")
        val wledCount = prefs.wledDevices.size
        setChip(wledChip, if (prefs.isWledConfigured) true else null, when {
            wledCount == 0 -> "WLED: no devices"
            !prefs.wledEnabled -> "WLED: $wledCount device(s), output OFF"
            else -> "WLED: $wledCount device(s)"
        })

        val s = SyncEngine.status
        val parts = s.nowPlaying.split(": ", limit = 2)
        nowApp.text = if (parts.size == 2) parts[0] else ""
        nowTitle.text = if (parts.size == 2) parts[1] else s.nowPlaying
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
            tv.background = rounded(Color.rgb(c.r, c.g, c.b), 10)
            tv.text = "${slot.label}\n${c.hex}"
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
                    "Fire TV hides the notification-access screen. Press \"Grant media access\" on the " +
                        "Music Light Synk device in Home Assistant, or from a computer run:\n\n" +
                        "adb shell cmd notification allow_listener " +
                        "$packageName/${MediaListenerService::class.java.name}",
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val ADB_HELP =
            "Home Assistant: the Music Light Synk integration can change every\n" +
                "setting here through the Android Debug Bridge integration.\n\n" +
                "From a computer:\n" +
                "adb connect <fire-tv-ip>\n" +
                "adb shell cmd notification allow_listener \\\n" +
                "  com.musiclightsynk/com.musiclightsynk.MediaListenerService\n" +
                "adb shell appops set com.musiclightsynk PROJECT_MEDIA allow\n" +
                "adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver \\\n" +
                "  -a com.musiclightsynk.CONFIGURE --es ha_url http://<ha-ip>:8123 \\\n" +
                "  --es wled_devices '192.168.1.60=ambient:40,22,40,22:bottom_left:cw'"
    }
}
