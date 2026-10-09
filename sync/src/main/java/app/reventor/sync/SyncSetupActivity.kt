package app.reventor.sync

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Setup assistant + status screen.
 *
 * Every requirement gets a check mark (green when fulfilled):
 * keyboard active, microphone, Bluetooth, notifications, battery exemption.
 * When the core requirements are met the sync service starts automatically —
 * and keeps running (auto-restarted by [SyncAutoStart] whenever the keyboard
 * is used, plus on reboot via SyncBootReceiver).
 */
class SyncSetupActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button
    private lateinit var batteryButton: Button
    private lateinit var batteryCheck: TextView
    private lateinit var keyboardCheck: TextView
    private lateinit var keyboardButton: Button
    private lateinit var micCheck: TextView
    private lateinit var micButton: Button
    private lateinit var btCheck: TextView
    private lateinit var btButton: Button
    private lateinit var pcCheck: TextView
    private lateinit var pcLabel: TextView
    private lateinit var pcButton: Button
    private lateinit var notifCheck: TextView
    private lateinit var notifButton: Button

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable: Runnable = object : Runnable {
        override fun run() {
            refresh()
            refreshHandler.postDelayed(this, 1000)
        }
    }
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val btPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        fun label(sp: Float) = TextView(this).apply {
            textSize = sp
            setPadding(0, pad / 2, 0, pad / 2)
        }

        val title = label(22f).apply {
            text = "R-Board einrichten"
            gravity = Gravity.CENTER
        }
        statusView = label(14f)

        fun addRow(root: LinearLayout, label: String): Triple<TextView, TextView, Button> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 3, 0, pad / 3)
            }
            val check = TextView(this).apply { textSize = 18f; setPadding(0, 0, pad / 2, 0) }
            val lbl = TextView(this).apply {
                textSize = 14f
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btn = Button(this).apply { textSize = 11f }
            row.addView(check)
            row.addView(lbl)
            row.addView(btn)
            root.addView(row)
            return Triple(check, lbl, btn)
        }

        val root = ScrollView(this).apply {
            addView(LinearLayout(this@SyncSetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(title)
                addView(statusView)

                addView(sectionLabel("Zwischenablage-Sync (ohne WLAN, über Bluetooth)"))
                addRow(this, "R-Board ist die aktive Tastatur").let {
                    keyboardCheck = it.first
                    keyboardButton = it.third.apply {
                        text = "Auswählen"
                        setOnClickListener {
                            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .showInputMethodPicker()
                        }
                    }
                }
                addRow(this, "Bluetooth (PC ist gekoppelt)").let {
                    btCheck = it.first
                    btButton = it.third.apply {
                        text = "Erlauben"
                        setOnClickListener {
                            if (Build.VERSION.SDK_INT >= 31) {
                                btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            } else markDone(btButton, btCheck)
                        }
                    }
                }
                addRow(this, "Computer koppeln").let {
                    pcCheck = it.first
                    pcLabel = it.second
                    pcButton = it.third.apply {
                        text = "Sichtbar machen"
                        setOnClickListener {
                            android.widget.Toast.makeText(
                                this@SyncSetupActivity,
                                "Sichtbar für 5 Minuten — starte am PC den R-Board-Setup-Assistenten; er findet das Telefon automatisch.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            discoverableLauncher.launch(
                                Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                    .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                            )
                        }
                    }
                }
                addRow(this, "Mitteilungen (zeigt den Sync-Status)").let {
                    notifCheck = it.first
                    notifButton = it.third.apply {
                        text = "Erlauben"
                        setOnClickListener {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else markDone(notifButton, notifCheck)
                        }
                    }
                }
                addRow(this, "Batterieoptimierung aus (Sync läuft dauerhaft)").let {
                    batteryCheck = it.first
                    batteryButton = it.third.apply {
                        text = "Deaktivieren"
                        setOnClickListener {
                            startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                    }
                }

                addView(sectionLabel("Diktat (offline)"))
                addRow(this, "Mikrofon für die Spracheingabe").let {
                    micCheck = it.first
                    micButton = it.third.apply {
                        text = "Erlauben"
                        setOnClickListener { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                    }
                }

                val guidance = manufacturerGuidance()
                if (guidance != null) {
                    addView(TextView(this@SyncSetupActivity).apply {
                        textSize = 12f
                        text = guidance
                        setPadding(0, pad / 2, 0, pad / 2)
                    })
                }

                val toDesktop = CheckBox(this@SyncSetupActivity).apply {
                    text = "Push phone clipboard → desktop"
                    isChecked = SyncPrefs.pushToDesktop(this@SyncSetupActivity)
                    setOnCheckedChangeListener { _, checked ->
                        SyncPrefs.setPushToDesktop(this@SyncSetupActivity, checked)
                    }
                }
                val toPhone = CheckBox(this@SyncSetupActivity).apply {
                    text = "Accept desktop clipboard → phone"
                    isChecked = SyncPrefs.acceptFromDesktop(this@SyncSetupActivity)
                    setOnCheckedChangeListener { _, checked ->
                        SyncPrefs.setAcceptFromDesktop(this@SyncSetupActivity, checked)
                    }
                }
                toggleButton = Button(this@SyncSetupActivity)
                addView(toDesktop)
                addView(toPhone)
                addView(toggleButton)
            })
        }
        setContentView(root)
        // edge-to-edge (enforced on Android 15+): keep content below the status bar
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        toggleButton.setOnClickListener {
            if (SyncState.serviceRunning) {
                SyncPrefs.setEnabled(this, false)
                startService(Intent(this, SyncService::class.java).setAction(SyncService.ACTION_STOP))
            } else {
                SyncPrefs.setEnabled(this, true)
                startSync()
            }
            refresh()
        }
        refresh()
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        textSize = 13f
        setTextColor(Color.GRAY)
        this.text = text
        setPadding(0, paddingOffset(), 0, paddingOffset())
    }

    private fun paddingOffset(): Int = (8 * resources.displayMetrics.density).toInt()

    private fun markDone(button: Button, check: TextView) {
        button.visibility = View.GONE
        check.text = "✓"
        check.setTextColor(Color.rgb(76, 175, 80))
    }

    private fun startSync() {
        SyncPrefs.setEnabled(this, true)
        ContextCompat.startForegroundService(
            this, Intent(this, SyncService::class.java).setAction(SyncService.ACTION_START)
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshHandler.postDelayed(refreshRunnable, 1000)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun keyboardActive(): Boolean {
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return current?.startsWith("$packageName/") == true
    }

    private fun refresh() {
        val btGranted = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        val notifGranted = hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        val micGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        val kbActive = keyboardActive()
        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)

        fun set(check: TextView, button: Button, ok: Boolean) {
            if (ok) {
                check.text = "✓"
                check.setTextColor(Color.rgb(76, 175, 80))
                button.visibility = View.GONE
            } else {
                check.text = "✗"
                check.setTextColor(Color.rgb(229, 57, 53))
                button.visibility = View.VISIBLE
            }
        }
        set(keyboardCheck, keyboardButton, kbActive)
        set(btCheck, btButton, btGranted)
        // pairing assist: list paired computers, offer to make the phone discoverable
        val adapter = if (btGranted) getSystemService(BluetoothManager::class.java)?.adapter else null
        if (adapter?.state == android.bluetooth.BluetoothAdapter.STATE_ON && btGranted) {
            @Suppress("DEPRECATION")
            val computers = adapter.bondedDevices.orEmpty().filter {
                it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
            }
            if (computers.isNotEmpty()) {
                pcCheck.text = "✓"
                pcCheck.setTextColor(Color.rgb(76, 175, 80))
                pcLabel.text = "Computer koppeln: " + computers.joinToString(", ") { it.name ?: "PC" }
            } else {
                pcCheck.text = "·"
                pcCheck.setTextColor(Color.GRAY)
                pcLabel.text = "Computer koppeln (am PC \"Gerät hinzufügen\" wählen)"
            }
        } else {
            pcCheck.text = "·"
            pcCheck.setTextColor(Color.GRAY)
            pcLabel.text = "Computer koppeln (Bluetooth aus oder keine Berechtigung)"
        }
        pcButton.visibility = View.VISIBLE
        set(notifCheck, notifButton, notifGranted)
        set(micCheck, micButton, micGranted)
        // battery: MIUI's "Keine Beschränkungen" selection is sufficient and not
        // visible to the standard API — show it as a neutral recommendation, not an error
        if (batteryOk) {
            batteryCheck.text = "✓"
            batteryCheck.setTextColor(Color.rgb(76, 175, 80))
            batteryButton.visibility = View.GONE
        } else {
            batteryCheck.text = "·"
            batteryCheck.setTextColor(Color.GRAY)
            batteryButton.visibility = View.VISIBLE
        }

        statusView.text = buildString {
            appendLine("Service: ${if (SyncState.serviceRunning) "running" else "stopped"}")
            appendLine("Status: ${SyncState.statusLine}")
            SyncState.connectedPeer?.let { appendLine("Peer: $it") }
        }
        toggleButton.text = if (SyncState.serviceRunning) "Stop sync" else "Start sync"
        toggleButton.isEnabled = btGranted

        if (!SyncPrefs.onboardingDone(this) && kbActive && btGranted && notifGranted) {
            // setup complete — from now on sync starts automatically with the keyboard
            SyncPrefs.setOnboardingDone(this, true)
            if (!SyncState.serviceRunning) startSync()
        } else if (SyncPrefs.onboardingDone(this) && btGranted && !SyncState.serviceRunning && SyncPrefs.enabled(this)) {
            startSync()
        }
    }

    /** Per-OEM battery management hints (the sync service runs 24/7 and is a prime target). */
    private fun manufacturerGuidance(): String? {
        val m = android.os.Build.MANUFACTURER.lowercase()
        return when {
            m == "xiaomi" || m == "poco" || m == "redmi" -> (
                "Xiaomi/HyperOS zusätzlich: Einstellungen → Apps → R-Board → " +
                "Autostart erlauben, Akku → Keine Einschränkungen, und in den " +
                "recent Apps R-Board festpinen."
                )
            m == "samsung" -> (
                "Samsung zusätzlich: Einstellungen → Akku → Hintergrundnutzungslimits → " +
                "R-Board aus 'Ruhende Apps' entfernen."
                )
            else -> null
        }
    }
}
