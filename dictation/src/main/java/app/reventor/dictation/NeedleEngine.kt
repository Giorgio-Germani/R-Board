package app.reventor.dictation

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Single-threaded gate to the process-global Needle engine. All calls serialize on
 * the object monitor; run them off the main thread (engine init + decode are slow).
 */
object NeedleEngine {
    private const val TAG = "ReventorDictation"
    private val lock = Any()

    @Volatile
    private var modelLoaded = false

    fun available(): Boolean = NeedleNative.available

    /** Loads whistle.cact once; subsequent calls are no-ops. */
    fun ensureLoaded(model: File): Boolean = synchronized(lock) {
        if (modelLoaded) return true
        if (!NeedleNative.available) return false
        val result = try {
            NeedleNative.nativeLoad(model.readBytes())
        } catch (t: Throwable) {
            Log.w(TAG, "needle_load crashed", t)
            Int.MIN_VALUE
        }
        if (result >= 0) {
            modelLoaded = true
            Log.i(TAG, "whistle model loaded")
        } else {
            Log.w(TAG, "needle_load failed: ${NeedleNative.nativeLastError()}")
        }
        modelLoaded
    }

    /** Batch transcribe a clip (≤30 s). Returns "" on silence, failure, or unloaded model. */
    fun transcribe(pcm: FloatArray, language: String?, keywords: String?): String = synchronized(lock) {
        if (!modelLoaded) return ""
        try {
            NeedleNative.nativeTranscribe(pcm, language, keywords)?.let { extractText(it) } ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "transcribe failed", t)
            ""
        }
    }

    /**
     * Transcribes with auto-detect and returns (text, detectedLanguageCode).
     * detectedLanguageCode is null when the engine did not report a language.
     */
    fun transcribeAutoDetect(pcm: FloatArray): Pair<String, String?> = synchronized(lock) {
        if (!modelLoaded) return "" to null
        try {
            NeedleNative.nativeTranscribe(pcm, null, null)?.let { json ->
                val obj = JSONObject(json)
                obj.optString("text", "") to obj.optString("language", "").ifEmpty { null }
            } ?: ("" to null)
        } catch (t: Throwable) {
            Log.w(TAG, "transcribe failed", t)
            "" to null
        }
    }

    /** Streams a chunk (~1 s); returns the words newly committed by this call. */
    fun streamProcess(pcm: FloatArray, language: String?, keywords: String?): String = synchronized(lock) {
        if (!modelLoaded) return ""
        try {
            NeedleNative.nativeStreamProcess(pcm, language, keywords)?.let { extractText(it) } ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "streamProcess failed", t)
            ""
        }
    }

    /** Ends the stream; returns the uncommitted tail. */
    fun streamStop(): String = synchronized(lock) {
        if (!modelLoaded) return ""
        try {
            NeedleNative.nativeStreamStop()?.let { extractText(it) } ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "streamStop failed", t)
            ""
        }
    }

    private fun extractText(json: String): String = try {
        JSONObject(json).optString("text", "")
    } catch (_: Throwable) {
        ""
    }
}
