package com.pragon.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity(), RemoteBridge.Listener {

    private lateinit var statusTv: TextView
    private lateinit var permsTv: TextView
    private lateinit var hostEt: EditText
    private lateinit var keyEt: EditText

    // Remote screen (PhoneView-style two-way control)
    private lateinit var remoteSection: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatBox: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var micBtn: Button
    private lateinit var modelRow: LinearLayout
    private var currentModel = ""

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) toggleMic() else toast("Microphone permission is needed to talk to Pragon from here.")
        }

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val uri = Uri.parse(text.trim())
        val host = uri.host
        val key = uri.getQueryParameter("key")
        if (host.isNullOrBlank() || key.isNullOrBlank()) {
            toast("That QR code isn't from Pragon.")
            return@registerForActivityResult
        }
        startPairing(host, if (uri.port > 0) uri.port else 8000, key)
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(t: String, size: Float = 14f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, pad / 2, 0, pad / 4)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }

        root.addView(label("PragonMobile", 26f))
        statusTv = label(Bridge.status, 16f)
        root.addView(statusTv)

        root.addView(button("Scan QR from Pragon (Connect Phone)") {
            scan.launch(
                ScanOptions()
                    .setPrompt("Scan the QR shown in Pragon > Remote - PhoneView")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        })

        root.addView(label("Or enter it manually:"))
        hostEt = EditText(this).apply {
            hint = "PC address, e.g. 192.168.1.2:8000"
            if (Prefs.host(this@MainActivity).isNotBlank())
                setText("${Prefs.host(this@MainActivity)}:${Prefs.port(this@MainActivity)}")
        }
        keyEt = EditText(this).apply { hint = "Pairing key shown under the QR" }
        root.addView(hostEt)
        root.addView(keyEt)
        root.addView(button("Connect") {
            val parts = hostEt.text.toString().trim().split(":")
            val host = parts.getOrNull(0).orEmpty()
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 8000
            val key = keyEt.text.toString().trim()
            if (host.isBlank() || key.isBlank()) toast("Enter the PC address and the key.")
            else startPairing(host, port, key)
        })

        root.addView(label("Permissions (needed once):", 16f))
        permsTv = label("")
        root.addView(permsTv)
        root.addView(button("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Find PragonMobile in the list and turn it on.")
        })
        root.addView(button("2. Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("3. Battery: don't restrict this app") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        })
        root.addView(label(" "))
        root.addView(button("Disconnect and forget this PC") {
            val i = Intent(this, PragonService::class.java).setAction(PragonService.ACTION_STOP)
            ContextCompat.startForegroundService(this, i)
            Prefs.clear(this)
            statusTv.text = "Not paired yet - scan the QR from Pragon"
            remoteSection.visibility = View.GONE
        })

        root.addView(label(" "))
        root.addView(View(this).apply { setBackgroundColor(Color.DKGRAY); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2) })
        root.addView(buildRemoteSection(pad))

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Prefs.token(this).isNotBlank()) {
            ContextCompat.startForegroundService(this, Intent(this, PragonService::class.java))
        }
        remoteSection.visibility = if (Prefs.token(this).isNotBlank()) View.VISIBLE else View.GONE
    }

    /** Remote screen: chat with Pragon, model pills, mic, and PC-control buttons
     * (open app / lock / volume / media on the PC) - mirrors PhoneView's web UI,
     * but two-way: this device can also be told what to do (see CommandExecutor). */
    private fun buildRemoteSection(pad: Int): LinearLayout {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        remoteSection = section
        section.addView(TextView(this).apply { text = "Remote"; textSize = 20f; setPadding(0, pad / 2, 0, pad / 4) })
        section.addView(TextView(this).apply {
            text = "Control your PC from here, or let Pragon on the PC control this phone."
            textSize = 12f
        })

        // model pills
        modelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        section.addView(HorizontalScrollView(this).apply { addView(modelRow) })

        // PC-control quick actions
        val pcRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pcRow1.addView(smallButton("Wake") { RemoteBridge.wakePc() })
        pcRow1.addView(smallButton("Lock PC") { RemoteBridge.pcControl("lock") })
        pcRow1.addView(smallButton("Play/Pause") { RemoteBridge.pcControl("play_pause") })
        section.addView(pcRow1)
        val pcRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pcRow2.addView(smallButton("Vol -") { RemoteBridge.pcControl("volume_down") })
        pcRow2.addView(smallButton("Vol +") { RemoteBridge.pcControl("volume_up") })
        pcRow2.addView(smallButton("Next") { RemoteBridge.pcControl("next") })
        section.addView(pcRow2)
        val openRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val openEt = EditText(this).apply { hint = "App/site to open on the PC"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        openRow.addView(openEt)
        openRow.addView(smallButton("Open on PC") {
            val v = openEt.text.toString().trim()
            if (v.isNotEmpty()) { RemoteBridge.pcControl("open_app", v); openEt.setText("") }
        })
        section.addView(openRow)

        // chat
        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll = ScrollView(this).apply {
            addView(chatBox)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (220 * resources.displayMetrics.density).toInt())
        }
        section.addView(chatScroll)

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chatInput = EditText(this).apply {
            hint = "Message Pragon..."
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(chatInput)
        inputRow.addView(smallButton("Send") {
            val t = chatInput.text.toString().trim()
            if (t.isNotEmpty()) { RemoteBridge.sendChat(t); chatInput.setText("") }
        })
        micBtn = smallButton("Mic: off") { onMicButton() }
        inputRow.addView(micBtn)
        section.addView(inputRow)
        return section
    }

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 12f; setOnClickListener { onClick() }
    }

    private fun onMicButton() {
        if (!MicStreamer.hasPermission(this)) { micPermission.launch(Manifest.permission.RECORD_AUDIO); return }
        toggleMic()
    }

    private fun toggleMic() {
        if (MicStreamer.isRunning()) { MicStreamer.stop(); micBtn.text = "Mic: off" }
        else { MicStreamer.start(this); micBtn.text = "Mic: ON" }
    }

    private fun addChatLine(fromPhone: Boolean, text: String) {
        chatBox.addView(TextView(this).apply {
            this.text = (if (fromPhone) "You: " else "Pragon: ") + text
            setPadding(0, 4, 0, 4)
        })
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // RemoteBridge.Listener -------------------------------------------------
    override fun onChatLine(fromPhone: Boolean, text: String) = addChatLine(fromPhone, text)

    override fun onModel(current: String, choices: List<String>) {
        currentModel = current
        modelRow.removeAllViews()
        val names = if (choices.isEmpty()) listOf("pragon", "jarvis", "friday", "ghost") else choices
        for (m0 in names) {
            val label = if (m0 == "ollama") "ghost" else m0
            modelRow.addView(smallButton(label.replaceFirstChar { it.uppercase() } + if (label == current || (label == "ghost" && current == "ollama")) " *" else "") {
                RemoteBridge.setModel(if (label == "ghost") "ollama" else label)
            })
        }
    }

    override fun onConnection(connected: Boolean) {
        remoteSection.visibility = if (connected) View.VISIBLE else View.GONE
        if (!connected) MicStreamer.stop()
    }

    override fun onResume() {
        super.onResume()
        Bridge.listener = { statusTv.text = it; refreshPerms() }
        statusTv.text = Bridge.status
        refreshPerms()
        RemoteBridge.listener = this
        remoteSection.visibility = if (RemoteBridge.socket != null) View.VISIBLE else View.GONE
        if (RemoteBridge.socket != null) RemoteBridge.requestModel()
    }

    override fun onPause() {
        Bridge.listener = null
        RemoteBridge.listener = null
        super.onPause()
    }

    private fun refreshPerms() {
        val a11y = PragonAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(PowerManager::class.java)
        val batt = pm.isIgnoringBatteryOptimizations(packageName)
        fun s(b: Boolean) = if (b) "ON" else "OFF"
        permsTv.text = "Accessibility: ${s(a11y)}\nDisplay over other apps: ${s(overlay)}\nBattery unrestricted: ${s(batt)}"
    }

    private fun startPairing(host: String, port: Int, key: String) {
        Prefs.save(this, host, port, "")
        hostEt.setText("$host:$port")
        val i = Intent(this, PragonService::class.java)
            .putExtra(PragonService.EXTRA_KEY, key.trim().uppercase())
        ContextCompat.startForegroundService(this, i)
        Bridge.set("Pairing with $host:$port ...")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
