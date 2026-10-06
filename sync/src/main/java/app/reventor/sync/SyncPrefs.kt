package app.reventor.sync

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

object SyncPrefs {
    private const val FILE = "reventor_sync"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("enabled", value).apply()

    fun pushToDesktop(context: Context): Boolean = prefs(context).getBoolean("push_to_desktop", true)

    fun setPushToDesktop(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("push_to_desktop", value).apply()

    fun acceptFromDesktop(context: Context): Boolean = prefs(context).getBoolean("accept_from_desktop", true)

    fun setAcceptFromDesktop(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("accept_from_desktop", value).apply()

    /** Stable 16-byte identity generated on first use (protocol `deviceId`). */
    fun deviceId(context: Context): ByteArray {
        val p = prefs(context)
        val existing = p.getString("device_id", null)
        if (existing != null) return Base64.decode(existing, Base64.NO_WRAP)
        val id = ByteArray(16)
        SecureRandom().nextBytes(id)
        p.edit().putString("device_id", Base64.encodeToString(id, Base64.NO_WRAP)).apply()
        return id
    }
}
