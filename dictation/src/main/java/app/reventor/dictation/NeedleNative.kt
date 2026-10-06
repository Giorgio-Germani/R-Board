package app.reventor.dictation

/**
 * JNI surface for the Needle engine (Cactus Compute, Apache-2.0).
 * The engine is process-global and not thread-safe — [NeedleEngine] serializes access.
 */
object NeedleNative {
    @Volatile
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("needle_jni")
            true
        } catch (t: Throwable) {
            // expected on ABIs we don't ship (e.g. x86_64 emulators) — dictation
            // falls back to the legacy voice-input shortcut
            false
        }
    }

    external fun nativeLoad(cact: ByteArray): Int
    external fun nativeTranscribe(pcm: FloatArray, language: String?, keywords: String?): String?
    external fun nativeStreamProcess(pcm: FloatArray, language: String?, keywords: String?): String?
    external fun nativeStreamStop(): String?
    external fun nativeLastError(): String?
}
