package app.reventor.dictation

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper

/**
 * Push-to-talk dictation for the keyboard's voice key:
 * press-and-hold starts 16 kHz mono capture, release transcribes via
 * Needle/Whistle fully offline and commits the text at the cursor.
 *
 * Called from the IME's key-press/release plumbing; all slow work runs on a
 * single executor (which also serializes engine access).
 */
class DictationController(private val ime: InputMethodService) {

    companion object {
        private const val TAG = "ReventorDictation"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SAMPLES = SAMPLE_RATE * 30
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var recorder: AudioRecord? = null
    private var readerThread: Thread? = null

    @Volatile
    private var recording = false
    private val chunks = ArrayList<FloatArray>()
    private var totalSamples = 0

    // gesture bookkeeping between press / release / tap events
    @Volatile
    private var pressHandled = false

    @Volatile
    private var pendingRelease = false

    @Volatile
    private var sessionActive = false

    /** Key-down on the voice key. */
    fun onPressStart() {
        pressHandled = false
        pendingRelease = false
        if (!NeedleEngine.available()) return // no native lib (wrong ABI) → legacy voice IME
        pressHandled = true
        if (!hasMicPermission()) {
            launchPermissionActivity()
            return
        }
        executor.execute {
            val model = ModelManager.modelFile(ime)
            if (!model.exists()) {
                if (ModelManager.startDownload(ime)) {
                    toast("Downloading dictation model (17 MB)…")
                } else {
                    toast("Dictation model is downloading…")
                }
                return@execute
            }
            if (!NeedleEngine.ensureLoaded(model)) {
                toast("Dictation failed to load")
                return@execute
            }
            startRecorder()
        }
    }

    /** Key-up on the voice key. */
    fun onPressEnd() {
        if (!pressHandled) return
        pendingRelease = true
        if (sessionActive) finalizeOnExecutor()
    }

    /**
     * Tap-complete event for KeyCode.VOICE_INPUT. Returns true when dictation handled
     * this gesture (so the legacy switch-to-external-voice-IME path must be skipped).
     */
    fun onVoiceKeyTap(): Boolean = pressHandled

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(ime, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun launchPermissionActivity() {
        val intent = Intent(ime, DictationPermissionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ime.startActivity(intent)
    }

    private fun startRecorder() {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(minBuf, SAMPLE_RATE * 4)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord unavailable", t)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        synchronized(chunks) {
            chunks.clear()
            totalSamples = 0
        }
        recording = true
        sessionActive = true
        recorder = rec
        rec.startRecording()
        readerThread = Thread {
            val tmp = FloatArray(SAMPLE_RATE / 5) // 200 ms
            while (recording && totalSamples < MAX_SAMPLES) {
                val n = rec.read(tmp, 0, tmp.size, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    synchronized(chunks) {
                        chunks.add(tmp.copyOf(n))
                        totalSamples += n
                    }
                }
            }
        }.also { it.start() }
        toast("● recording")
        if (pendingRelease) finalizeOnExecutor()
    }

    private fun finalizeOnExecutor() {
        sessionActive = false
        executor.execute {
            val pcm = stopRecorder() ?: return@execute
            val language = currentLanguage()
            val text = NeedleEngine.transcribe(pcm, language, null)
            main.post {
                if (text.isNotEmpty()) {
                    ime.currentInputConnection?.commitText(text, 1)
                } else {
                    toast("Nothing heard")
                }
            }
        }
    }

    private fun stopRecorder(): FloatArray? {
        recording = false
        readerThread?.let { t ->
            try {
                t.join(1500)
            } catch (_: InterruptedException) {
            }
        }
        readerThread = null
        val rec = recorder
        recorder = null
        if (rec == null) return null
        try {
            rec.stop()
        } catch (_: Throwable) {
        }
        rec.release()
        val pcm = synchronized(chunks) {
            val all = FloatArray(totalSamples)
            var off = 0
            for (c in chunks) {
                System.arraycopy(c, 0, all, off, c.size)
                off += c.size
            }
            chunks.clear()
            all
        }
        return if (pcm.isEmpty()) null else pcm
    }

    /** Keyboard language, or null to let Whistle auto-detect. */
    private fun currentLanguage(): String? {
        return try {
            val imm = ime.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            val locale = imm.currentInputMethodSubtype?.locale
            when (locale?.lowercase()?.substringBefore('_')) {
                "de" -> "de"
                "en" -> "en"
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun toast(message: String) {
        main.post {
            try {
                Toast.makeText(ime, message, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }
}
