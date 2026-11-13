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
 * Settings + status screen for TV (D-pad navigable, overscan-safe) and phones (one column,
 * tighter spacing), built in code (no XML). Everything here can also be set over ADB
 * ([AdbCommandReceiver]) or from Home Assistant.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var accessChip: TextView
    private lateinit var captureChip: TextView
    private lateinit var haChip: TextView
    private lateinit var wledChip: TextView
    private lateinit var linkChip: TextView
    private lateinit var hearingStatus: TextView
    private lateinit var linkStatus: TextView
    private lateinit var controlStatus: TextView
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
    private val isTv by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

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
        // The microphone service may only start while the app is on screen (Android rule for the mic).
        if (prefs.mode.mic && !ListenerService.running && ListenerService.hasMicPermission(this)) {
            ListenerService.start(this)
        }
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
        val page = column().apply {
            // TV: overscan-safe margins. Phone: use the width.
            if (isTv) setPadding(dp(48), dp(24), dp(48), dp(32)) else setPadding(dp(12), dp(14), dp(12), dp(24))
        }
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
        cardOf(left, "Mode", modeHelp()).apply {
            addView(toggleButton({ "Mode: " + prefs.mode.label }) { cycleMode() }, fullWidth())
        }

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
            linkChip = chip().also { addView(it) }
            val r = row()
            accessButton = button("Grant media access") { openListenerSettings() }
            captureButton = button("Start screen capture") { toggleCapture() }
            r.addView(accessButton, weighted())
            r.addView(captureButton, weighted())
            addView(r)
        }

        // ---- right: settings
        cardOf(right, "WLED devices", "Each WLED shows its own part of the artwork, or bounces with the music (Energy). Talks to WLED directly.").apply {
            wledList = column()
            addView(wledList)
            addView(addWledRow())
            addView(toggleButton({ "WLED output: " + onOff(prefs.wledEnabled) }) { prefs.wledEnabled = !prefs.wledEnabled }, fullWidth())
            stepper(this, "WLED brightness", "%", 10, 10..100, { prefs.wledBrightnessPct }, { prefs.wledBrightnessPct = it })
            addView(toggleButton({ "Restore WLED when music stops: " + onOff(prefs.wledRestore) }) {
                prefs.wledRestore = !prefs.wledRestore
            }, fullWidth())
        }

        cardOf(
            right, "Beat & audio",
            "Energy bars, the beat pulse and Home Assistant beats listen to exactly the source you pick here (it never switches by itself). " +
                "Device audio: what this device plays (not with HDMI passthrough to a soundbar). Microphone: music from any speaker in the room. " +
                "Or another device's microphone over Wi-Fi, e.g. your phone's for the TV (its Backup listener must be on).",
        ).apply {
            hearingStatus = text("", 13f, muted).also { addView(it) }
            addView(toggleButton({ "Listen to: " + SyncEngine.audioLabel(this@MainActivity) }) { cycleAudioSource() }, fullWidth())
            stepper(this, "Sensitivity", "%", 10, Prefs.SENSITIVITY_RANGE, { prefs.energySensitivityPct }, { prefs.energySensitivityPct = it })
            stepper(this, "Blur", "", 1, Prefs.BLUR_RANGE, { prefs.energyBlur }, { prefs.energyBlur = it })
            stepper(this, "Background brightness", "%", 5, Prefs.ENERGY_BG_RANGE, { prefs.energyBackgroundPct }, { prefs.energyBackgroundPct = it })
            addView(toggleButton({ "Home Assistant lights: " + haMusicLabel(prefs.haMusic) }) {
                prefs.haMusic = Prefs.HA_MUSIC[(Prefs.HA_MUSIC.indexOf(prefs.haMusic) + 1) % Prefs.HA_MUSIC.size]
                if (prefs.haMusic != "off" && !prefs.isHaConfigured) toast("Set the Home Assistant URL first (Home Assistant card)")
            }, fullWidth())
            addView(text("Set the style in Home Assistant (device page → Music effect: beat pulse, energy or energy bars) and pick the lights under Configure → Music effect.", 12f, muted))
            addView(toggleButton({ "Beat pulse (gradient strips): " + onOff(prefs.pulseEnabled) }) {
                prefs.pulseEnabled = !prefs.pulseEnabled
                if (prefs.pulseEnabled) ensureAudioPermission()
            }, fullWidth())
            stepper(this, "Pulse strength", "%", 10, 10..100, { prefs.pulseStrengthPct }, { prefs.pulseStrengthPct = it })
        }

        cardOf(
            right, "Other devices",
            "Every device drives its own WLED list. Devices only take turns on a WLED they both list, and only devices in the " +
                "same room share the beat and album colours. Give the phone another room name to keep it completely separate.",
        ).apply {
            linkStatus = text("", 13f, muted).also { addView(it) }
            addView(toggleButton({ "Device link: " + onOff(prefs.linkEnabled) }) { prefs.linkEnabled = !prefs.linkEnabled }, fullWidth())
            field(this, "Room", "Home",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, { prefs.room }, { prefs.room = it })
            field(this, "This device's name", android.os.Build.MODEL ?: "",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, { prefs.deviceName }, { prefs.deviceName = it })
            if (prefs.hasMic) {
                addView(text(
                    "To lend this phone's microphone to the TV (or another device), set Mode (top) to \"Lights + microphone\" or " +
                        "\"Microphone only\", then pick this phone's microphone on the TV under Beat & audio → Listen to.",
                    12f, muted,
                ))
            }
            addView(button("Save room and name", primary = true) { saveFields(silent = false) }, fullWidth())
        }

        cardOf(right, "Home Assistant").apply {
            controlStatus = text("", 13f, muted).also { addView(it) }
            addView(toggleButton({ "Home Assistant may change settings over Wi-Fi: " + onOff(prefs.controlEnabled) }) {
                prefs.controlEnabled = !prefs.controlEnabled
            }, fullWidth())
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

        cardOf(right, "Colour source & tuning").apply {
            addView(toggleButton({ "Source: ${prefs.sourceMode.label}" }) { prefs.sourceMode = prefs.sourceMode.next() }, fullWidth())
            stepper(this, "Edge / corner size", "%", 5, Prefs.CORNER_RANGE, { prefs.cornerPct }, { prefs.cornerPct = it })
            stepper(this, "Saturation boost", "%", 10, Prefs.SATURATION_RANGE, { prefs.saturationPct }, { prefs.saturationPct = it })
            stepper(this, "Minimum brightness", "%", 5, Prefs.MIN_BRIGHTNESS_RANGE, { prefs.minBrightnessPct }, { prefs.minBrightnessPct = it })
            stepper(this, "Re-sample screen every (0 = per track)", "s", 5, Prefs.SCREEN_INTERVAL_RANGE, { prefs.screenIntervalSec }, { prefs.screenIntervalSec = it })
            stepper(this, "Send 'stopped' after a pause of", "s", 5, Prefs.STOP_DELAY_RANGE, { prefs.stopDelaySec }, { prefs.stopDelaySec = it })
            addView(toggleButton({ "Restart screen capture after reboot: " + onOff(prefs.bootCapture) }) {
                prefs.bootCapture = !prefs.bootCapture
            }, fullWidth())
        }

        cardOf(right, "MQTT (optional)", collapsible = true).apply {
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

        cardOf(right, "Set up from a computer or Home Assistant", collapsible = true).apply {
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

    /**
     * One block per device: host + remove; zone (press to cycle) + zone option; Energy / Flip /
     * Mirror. Rebuilt after every change. Focus roles: 1 zone, 2 zone option, 3 energy, 4 flip, 5 mirror.
     */
    private fun renderWledDevices(focusIndex: Int = -1, focusRole: Int = 0) {
        if (!::wledList.isInitialized) return
        wledList.removeAllViews()
        val devices = prefs.wledDevices
        if (devices.isEmpty()) {
            wledList.addView(text("No WLED devices yet. Add one below.", 13f, muted))
            return
        }
        devices.forEachIndexed { i, d ->
            val block = column().apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(Color.parseColor("#1F2228"), 12)
            }
            val top = row()
            top.addView(text(d.host, 15f, fg, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            top.addView(button("Remove") {
                prefs.setWledDevices(prefs.wledDevices.filterIndexed { j, _ -> j != i })
                renderWledDevices(focusIndex = (i - 1).coerceAtLeast(0), focusRole = 1)
                settingsTouched()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            block.addView(top)

            val zoneRow = row()
            val zoneBtn = button(d.zone.label) { updateDevice(i, 1) { it.copy(zone = WledDevices.nextZone(it.zone)) } }
            zoneRow.addView(zoneBtn, weighted(1.6f))
            val extra: Button? = when (val z = d.zone) {
                is WledZone.Ambient -> button("Layout") { editLayout(i, z) }
                is WledZone.Section -> if (d.effect.energy) null else button(if (z.keepEffect) "Keep effect" else "Solid") {
                    updateDevice(i, 2) { it.copy(zone = z.copy(keepEffect = !z.keepEffect)) }
                }
                is WledZone.Palette -> null
            }
            if (extra != null) zoneRow.addView(extra, weighted(1f))
            block.addView(zoneRow)

            val roles = HashMap<Int, Button>()
            roles[1] = zoneBtn
            if (extra != null) roles[2] = extra
            if (d.zone !is WledZone.Palette) {
                val fx = row()
                val e = d.effect
                val energyBtn = button("Energy: " + onOff(e.energy), primary = e.energy) {
                    if (!e.energy) ensureAudioPermission()
                    updateDevice(i, 3) { it.copy(effect = it.effect.copy(energy = !it.effect.energy)) }
                }
                val flipBtn = button("Flip: " + onOff(e.flip)) { updateDevice(i, 4) { it.copy(effect = it.effect.copy(flip = !it.effect.flip)) } }
                val mirrorBtn = button("Mirror: " + onOff(e.mirror)) {
                    updateDevice(i, 5) { it.copy(effect = it.effect.copy(mirror = !it.effect.mirror)) }
                }
                fx.addView(energyBtn, weighted(1.2f))
                fx.addView(flipBtn, weighted(1f))
                fx.addView(mirrorBtn, weighted(1f))
                block.addView(fx)
                roles[3] = energyBtn
                roles[4] = flipBtn
                roles[5] = mirrorBtn
                if (e.energy) block.addView(text(energyHint(d), 12f, muted))
            }
            wledList.addView(block, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
            if (i == focusIndex) {
                val target = roles[focusRole] ?: zoneBtn
                target.post { target.requestFocus() }
            }
        }
    }

    private fun energyHint(d: WledDevice): String {
        val from = when {
            d.effect.mirror && d.effect.flip -> "from both ends inward"
            d.effect.mirror -> "from the middle outward"
            d.effect.flip -> "from the last LED"
            else -> "from LED 0"
        }
        val colours = if (d.zone is WledZone.Ambient) "in the gradient's colours" else "lows / mids / highs in album colours"
        return "Bars bounce $from, $colours. Same switches as LedFx."
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

    private fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, WRAP_CONTENT, weight).apply {
        marginEnd = dp(6)
        topMargin = dp(6)
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

    /**
     * A titled card; returns the container to add content to. A [collapsible] card starts
     * folded: its title is a button that shows / hides the content.
     */
    private fun cardOf(parent: LinearLayout, title: String, subtitle: String? = null, collapsible: Boolean = false): LinearLayout {
        val c = column().apply {
            background = rounded(card, 16, cardEdge)
            if (isTv) setPadding(dp(18), dp(14), dp(18), dp(16)) else setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        parent.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(if (isTv) 16 else 12) })
        if (!collapsible) {
            c.addView(text(title.uppercase(), 13f, accent, bold = true).apply { letterSpacing = 0.08f })
            if (subtitle != null) c.addView(text(subtitle, 12f, muted))
            return c
        }
        val body = column().apply { visibility = View.GONE }
        val header = button("▸  $title") {}
        header.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            header.text = (if (open) "▾  " else "▸  ") + title
        }
        header.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        c.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (subtitle != null) body.addView(text(subtitle, 12f, muted))
        c.addView(body)
        return body
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
        setChip(linkChip, if (!prefs.linkEnabled) null else s.peers.isNotEmpty(), when {
            !prefs.linkEnabled -> "Device link: off"
            s.peers.isEmpty() -> "Other devices: none found yet"
            else -> "Other devices: " + s.peers.joinToString(", ")
        })
        hearingStatus.text = when {
            s.audio == AudioEngine.SRC_OUTPUT -> "Hearing: device audio"
            s.audio == AudioEngine.SRC_MIC -> "Hearing: microphone"
            s.audio == "peer" -> "Hearing: ${s.audioPeerName}'s microphone over Wi-Fi"
            prefs.audioPeerId != null -> "Waiting for ${prefs.audioPeerName}'s microphone: its Backup listener must be on, on the same Wi-Fi"
            prefs.audioSourceKey == AudioSource.OFF.key -> "Audio is off"
            else -> "Not listening right now: starts when music plays here, or with the backup listener"
        }
        linkStatus.text = if (!prefs.linkEnabled) {
            "Link is off: this device works on its own."
        } else {
            "Room: ${prefs.room}. Found: " + s.peers.joinToString(", ").ifEmpty { "no other devices yet" } +
                "\nThis device's WLEDs are driven by: " + (s.renderer ?: "nobody right now") +
                if (s.listening) "\nBackup listener is on" else ""
        }
        controlStatus.text = when {
            !prefs.controlEnabled -> "Wi-Fi control is off: Home Assistant can only change settings over ADB (Fire TV)."
            s.controlUrl == null -> "Wi-Fi control: waiting for a Wi-Fi address"
            else -> "Home Assistant controls this device over Wi-Fi at ${s.controlUrl} (no ADB needed). " +
                "It learns the address from the app, so set the URL and webhook below."
        }
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

    /** Ask for the mic / audio permission (and, for the listener's notification, notifications). */
    private fun ensureAudioPermission(forListener: Boolean = false) {
        val want = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) want += Manifest.permission.RECORD_AUDIO
        if (forListener && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            want += Manifest.permission.POST_NOTIFICATIONS
        }
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            toast(if (granted) "Audio access granted: Energy and the beat pulse can hear the music" else "Energy and the beat pulse need audio access")
            if (granted && prefs.mode.mic) ListenerService.start(this)
            settingsTouched()
        }
    }

    /** Next audio source: device audio, this device's mic, other devices' mics (by name), off. */
    private fun cycleAudioSource() {
        val options = SyncEngine.audioOptions(this)
        val i = options.indexOfFirst { it.first == prefs.audioSourceKey }
        val (key, label) = options[(i + 1) % options.size]
        if (key.startsWith(Prefs.PEER_PREFIX)) {
            prefs.setAudioPeer(key.removePrefix(Prefs.PEER_PREFIX), label.substringBefore("'s microphone"))
        } else {
            prefs.audioSourceRaw = key
            if (key != AudioSource.OFF.key) ensureAudioPermission()
        }
        if (key != AudioSource.MIC.key && prefs.mode.mic) {
            prefs.mode = if (prefs.mode.lights) DeviceMode.LIGHTS else DeviceMode.OFF
            ListenerService.stop(this)
            toast("Mode is now \"${prefs.mode.label}\": lending the microphone needs it as audio source")
        }
    }

    /** Next mode; switches the microphone service on/off to match (allowed now: the app is on screen). */
    private fun cycleMode() {
        val modes = prefs.modes
        val next = modes[(modes.indexOf(prefs.mode) + 1) % modes.size]
        prefs.mode = next
        if (next.mic) {
            if (!ListenerService.hasMicPermission(this)) {
                ensureAudioPermission(forListener = true) // starts in onRequestPermissionsResult
            } else {
                ensureAudioPermission(forListener = true)
                if (!ListenerService.start(this)) toast("Could not switch the microphone on")
            }
        } else {
            ListenerService.stop(this)
        }
        toast(
            when (next) {
                DeviceMode.LIGHTS_MIC -> "This device's lights follow the music, and other devices can use its microphone"
                DeviceMode.MIC -> "Only lending the microphone: this device's own lights are left alone"
                DeviceMode.LIGHTS -> "This device's lights follow the music"
                DeviceMode.OFF -> "Off: lights given back"
            },
        )
    }

    private fun modeHelp(): String = if (prefs.hasMic) {
        "Lights + microphone: this phone's lights follow the music, and the TV can use its mic. " +
            "Microphone only: just lend the mic (e.g. to the TV); this phone's lights are left alone. Lights only: no mic. Off: nothing."
    } else {
        "Lights: this device's lights (WLED, Home Assistant) follow the music. Off: lights are given back."
    }

    private fun haMusicLabel(v: String) = when (v) {
        "beat" -> "Beat pulse"
        "level" -> "Energy (music level)"
        else -> "Off"
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
                "setting here over Wi-Fi (or, on a Fire TV, through the Android\n" +
                "Debug Bridge integration).\n\n" +
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
