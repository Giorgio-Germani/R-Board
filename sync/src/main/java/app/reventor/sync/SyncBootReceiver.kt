package app.reventor.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts the sync service after reboot when the user enabled it (BOOT_COMPLETED is an allowed FGS start exemption). */
class SyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SyncPrefs.enabled(context)) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, SyncService::class.java).setAction(SyncService.ACTION_START)
        )
    }
}
