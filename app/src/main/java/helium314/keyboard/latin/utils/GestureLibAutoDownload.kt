// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.os.Build
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.content.edit

private const val LIB_URL_BASE =
    "https://raw.githubusercontent.com/erkserkserks/openboard/master/app/src/main/jniLibs"

/**
 * Silent acquisition of the closed-source glide typing library: on keyboard
 * start, the library is downloaded in the background (no dialog), verified,
 * installed and loaded — swiping simply works from the first start on.
 * Failures are logged and retried on the next keyboard start; the manual
 * download in the gesture typing settings remains as a fallback.
 */
object GestureLibAutoDownload {
    private var started = false

    @JvmStatic
    fun start(context: Context) {
        if (started) return
        if (BuildConfig.BUILD_TYPE == "nouserlib") return
        if (!context.prefs().getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)) return
        if (JniUtils.sHaveGestureLib) return
        started = true
        Thread({
            val ok = downloadAndInstallGestureLib(context)
            Log.i("GestureLibAutoDownload", "gesture library auto-download: ${if (ok) "installed" else "failed — retrying on next start"}")
        }, "gesture-lib-auto-download").start()
    }
}

/** Downloads, verifies and loads the library. Returns true when swiping is ready (no restart needed). */
fun downloadAndInstallGestureLib(context: Context): Boolean {
    val filesDir = context.filesDir
    val tmpFile = File(filesDir, "tmplib_download")
    try {
        val abi = Build.SUPPORTED_ABIS[0]
        val connection = URL("$LIB_URL_BASE/$abi/libjni_latinimegoogle.so").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
        connection.inputStream.use { input ->
            tmpFile.outputStream().use { output -> input.copyTo(output) }
        }
        val checksum = ChecksumCalculator.checksum(tmpFile) ?: ""
        if (checksum != JniUtils.expectedDefaultChecksum()) throw IOException("checksum mismatch")
        tmpFile.setReadOnly()
        val libFile = File(filesDir, JniUtils.JNI_LIB_IMPORT_FILE_NAME)
        libFile.setWritable(true)
        libFile.delete()
        context.protectedPrefs().edit(commit = true) { putString(Settings.PREF_LIBRARY_CHECKSUM, checksum) }
        tmpFile.copyTo(libFile, overwrite = true)
        tmpFile.delete()
        libFile.setReadOnly()
        // load immediately: no process restart, so the keyboard stays active
        JniUtils.loadGestureLib(context)
        if (!JniUtils.sHaveGestureLib) throw IOException("library loaded but flag not set")
        // make sure swiping is on so it works without any manual step
        context.prefs().edit { putBoolean(Settings.PREF_GESTURE_INPUT, true) }
        return true
    } catch (e: Exception) {
        Log.w("GestureLibDownload", "gesture library download failed", e)
        tmpFile.delete()
        return false
    }
}
