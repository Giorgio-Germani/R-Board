// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

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
import helium314.keyboard.latin.utils.downloadAndInstallGestureLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manual one-tap acquisition of the closed-source glide typing library
 * (gesture typing settings). The silent automatic variant lives in
 * [helium314.keyboard.latin.utils.GestureLibAutoDownload] and runs on every
 * keyboard start until the library is present.
 */
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

