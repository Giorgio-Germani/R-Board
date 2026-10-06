package app.reventor.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts the sync service after reboot when the user enabled it.
 * Must never throw — a crashing boot receiver shows "keeps stopping" dialogs
 * on every boot. Android may still deny the background FGS start (it is an
 * exemption, not a guarantee) — in that case sync simply starts on next
 * keyboard use instead.
 */
class SyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SyncPrefs.enabled(context)) return
        val hasConnectPermission =
            Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasConnectPermission) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SyncService::class.java).setAction(SyncService.ACTION_START)
            )
        } catch (t: Throwable) {
            Log.w("ReventorSync", "boot start of sync service denied", t)
        }
    }
}
