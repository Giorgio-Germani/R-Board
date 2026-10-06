package app.reventor.sync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Minimal setup screen: permissions, pairing hint, direction toggles, start/stop. */
class SyncSetupActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button
    private lateinit var batteryButton: Button
    private lateinit var batteryText: TextView
    private var guidanceText: TextView? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable: Runnable = object : Runnable {
        override fun run() {
            refresh()
            refreshHandler.postDelayed(this, 1000)
        }
    }
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val btPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        fun label(sp: Float) = TextView(this).apply {
            textSize = sp
            setPadding(0, pad / 2, 0, pad / 2)
        }

        val title = label(22f).apply {
            text = "REVENTOR Sync"
            gravity = Gravity.CENTER
        }
        statusView = label(14f)

        val notifButton = Button(this).apply {
            text = "Grant notification permission"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Toast.makeText(context, "Not needed on this Android version", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btButton = Button(this).apply {
            text = "Grant Bluetooth permission"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 31) {
                    btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    Toast.makeText(context, "Not needed on this Android version", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val pairButton = Button(this).apply {
            text = "Pair desktop (open Bluetooth settings)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        batteryButton = Button(this).apply {
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
        batteryText = TextView(this).apply {
            textSize = 12f
            setPadding(0, 4, 0, 4)
        }
        val guidance = manufacturerGuidance()
        if (guidance != null) {
            guidanceText = TextView(this).apply {
                textSize = 12f
                text = guidance
                setPadding(0, 6, 0, 6)
            }
        } else guidanceText = null
        val toDesktop = CheckBox(this).apply {
            text = "Push phone clipboard → desktop"
            isChecked = SyncPrefs.pushToDesktop(this@SyncSetupActivity)
            setOnCheckedChangeListener { _, checked ->
                SyncPrefs.setPushToDesktop(this@SyncSetupActivity, checked)
            }
        }
        val toPhone = CheckBox(this).apply {
            text = "Accept desktop clipboard → phone"
            isChecked = SyncPrefs.acceptFromDesktop(this@SyncSetupActivity)
            setOnCheckedChangeListener { _, checked ->
                SyncPrefs.setAcceptFromDesktop(this@SyncSetupActivity, checked)
            }
        }
        toggleButton = Button(this)

        val root = ScrollView(this).apply {
            addView(LinearLayout(this@SyncSetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(title)
                addView(statusView)
                addView(notifButton)
                addView(btButton)
                addView(pairButton)
                addView(batteryButton)
                addView(batteryText)
                guidanceText?.let { addView(it) }
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
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, SyncService::class.java).setAction(SyncService.ACTION_START)
                )
            }
            refresh()
        }
        refresh()
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

    private fun refresh() {
        val btGranted = Build.VERSION.SDK_INT < 31 || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        val notifGranted = Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        statusView.text = buildString {
            appendLine("Service: ${if (SyncState.serviceRunning) "running" else "stopped"}")
            appendLine("Status: ${SyncState.statusLine}")
            SyncState.connectedPeer?.let { appendLine("Peer: $it") }
            appendLine("Bluetooth permission: ${if (btGranted) "granted" else "missing"}")
            appendLine("Notification permission: ${if (notifGranted) "granted" else "missing"}")
        }
        toggleButton.text = if (SyncState.serviceRunning) "Stop sync" else "Start sync"
        // connectedDevice FGS cannot start without BLUETOOTH_CONNECT on API 34+
        toggleButton.isEnabled = btGranted
        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        batteryText.text = if (ignoring) {
            "Batterieoptimierung: ignoriert (gut)"
        } else {
            "Batterieoptimierung: AKTIV — antippen, damit der Sync dauerhaft läuft"
        }
        batteryButton.text = if (ignoring) "Batterieoptimierung OK ✓" else "Batterieoptimierung deaktivieren"
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
