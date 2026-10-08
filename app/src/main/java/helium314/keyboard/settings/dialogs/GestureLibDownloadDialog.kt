// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import android.content.Context
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ChecksumCalculator
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.content.edit

/**
 * One-tap acquisition of the closed-source glide typing library.
 * Downloads from the same source the bundled checksums in [JniUtils] were created from,
 * verifies the SHA-256, installs it and restarts the app.
 */
private const val LIB_URL_BASE =
    "https://raw.githubusercontent.com/erkserkserks/openboard/master/app/src/main/jniLibs"

@Composable
fun GestureLibDownloadDialog(
    onDeclined: () -> Unit,
    onInstalled: () -> Unit,
) {
    var state by rememberSaveable { mutableStateOf(DownloadState.ASK) }
    when (state) {
        DownloadState.ASK -> ThreeButtonAlertDialog(
            // no dismiss on outside tap / back: only the explicit "No" button declines,
            // otherwise an accidental tap would silently disable swiping
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            // confirm runs onConfirmed() and then onDismissRequest(); only treat the
            // dismiss as a decline if confirmation did not already move us on
            onDismissRequest = { if (state == DownloadState.ASK) onDeclined() },
            onConfirmed = { state = DownloadState.DOWNLOADING },
            title = { Text(stringResource(R.string.gesture_lib_auto_prompt_title)) },
            content = { Text(stringResource(R.string.gesture_lib_auto_prompt_message)) },
            confirmButtonText = stringResource(R.string.gesture_lib_auto_confirm),
            cancelButtonText = stringResource(R.string.gesture_lib_auto_decline),
        )
        DownloadState.DOWNLOADING -> ThreeButtonAlertDialog(
            onDismissRequest = { },
            onConfirmed = { },
            confirmButtonText = null,
            title = { Text(stringResource(R.string.gesture_lib_auto_prompt_title)) },
            content = { Text(stringResource(R.string.gesture_lib_auto_downloading)) },
        )
        DownloadState.ERROR -> ConfirmationDialog(
            onDismissRequest = onDeclined,
            onConfirmed = { state = DownloadState.DOWNLOADING },
            title = { Text(stringResource(R.string.gesture_lib_auto_prompt_title)) },
            content = { Text(stringResource(R.string.gesture_lib_auto_download_failed)) },
            confirmButtonText = stringResource(R.string.gesture_lib_auto_retry),
            cancelButtonText = stringResource(android.R.string.cancel),
        )
    }
    if (state == DownloadState.DOWNLOADING) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) {
            val success = withContext(Dispatchers.IO) { downloadAndInstallGestureLib(ctx) }
            if (!success) state = DownloadState.ERROR else onInstalled()
        }
    }
}

private enum class DownloadState { ASK, DOWNLOADING, ERROR }

/** Downloads, verifies and loads the library. Returns true when swiping is ready (no restart needed). */
private fun downloadAndInstallGestureLib(context: Context): Boolean {
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
