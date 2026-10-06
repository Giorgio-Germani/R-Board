package app.reventor.dictation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads whistle.cact (~17 MB, Apache-2.0) into filesDir on first use.
 */
object ModelManager {
    private const val TAG = "ReventorDictation"
    const val MODEL_URL = "https://huggingface.co/Cactus-Compute/whistle/resolve/main/whistle.cact"
    private const val MIN_VALID_BYTES = 10L * 1024 * 1024

    @Volatile
    private var downloading = false

    fun modelFile(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "whistle.cact")
    }

    fun isDownloading(): Boolean = downloading

    /** Returns false when a download is already running. */
    fun startDownload(context: Context): Boolean {
        if (downloading) return false
        downloading = true
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                val target = modelFile(appContext)
                val part = File(target.parentFile, target.name + ".part")
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.getInputStream().use { input ->
                        part.outputStream().use { output ->
                            input.copyTo(output, 64 * 1024)
                        }
                    }
                    if (part.length() >= MIN_VALID_BYTES) {
                        if (target.exists()) target.delete()
                        part.renameTo(target)
                        main.post { toast(appContext, "Dictation model ready") }
                    } else {
                        part.delete()
                        main.post { toast(appContext, "Dictation model download failed") }
                    }
                } finally {
                    conn?.disconnect()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "model download failed", t)
                main.post { toast(appContext, "Dictation model download failed: ${t.message}") }
            } finally {
                downloading = false
            }
        }.start()
        return true
    }

    private fun toast(context: Context, message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
