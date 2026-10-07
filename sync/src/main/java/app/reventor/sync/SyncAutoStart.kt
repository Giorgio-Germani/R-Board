package app.reventor.sync

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import android.inputmethodservice.InputMethodService

/**
 * Keeps the Bluetooth clipboard sync alive without manual steps:
 * called every time the keyboard starts. First use launches the setup
 * assistant once; afterwards the sync foreground service is (re)started
 * automatically whenever the keyboard comes up, and keeps running after
 * the keyboard is closed.
 */
object SyncAutoStart {
    private const val TAG = "ReventorSync"

    /** keyboard service created — start the sync service when possible (never a UI launch) */
    @JvmStatic
    fun onKeyboardStart(ime: InputMethodService) {
        if (!SyncPrefs.onboardingDone(ime)) return // onboarding runs from onInputViewShown
        ensureService(ime)
    }

    /** keyboard view is visible — safe place to launch the onboarding activity (BAL rules) */
    @JvmStatic
    fun onInputViewShown(ime: InputMethodService) {
        if (!SyncPrefs.onboardingDone(ime)) {
            try {
                ime.startActivity(
                    Intent(ime, SyncSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "onboarding launch failed", t)
            }
            return
        }
        ensureService(ime)
    }

    private fun ensureService(ime: InputMethodService) {
        val hasBt = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ime, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasBt || !SyncPrefs.enabled(ime)) return
        try {
            ContextCompat.startForegroundService(
                ime, Intent(ime, SyncService::class.java).setAction(SyncService.ACTION_START)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "auto start denied", t)
        }
    }
}
