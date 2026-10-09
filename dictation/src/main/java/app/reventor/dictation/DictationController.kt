package app.reventor.dictation

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper

/**
 * Offline dictation for the keyboard's voice key, two interaction styles:
 *
 *  - Layout shortcut key (sends press/release): push-to-talk — press-and-hold
 *    records, release transcribes and commits.
 *  - Toolbar mic (fires only a tap code event): tap-to-toggle — first tap starts
 *    recording, second tap stops and commits.
 *
 * The tap event arrives for both styles, so [onVoiceKeyTap] must consume the
 * gesture a press/release already handled instead of starting a second recording.
 * All slow work runs on a single executor (which also serializes engine access).
 */
class DictationController(private val ime: InputMethodService) {

    companion object {
        private const val TAG = "ReventorDictation"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SAMPLES = SAMPLE_RATE * 30

        /** Language codes the dictation engine can recognize (whistle model). */
        @JvmField
        val SUPPORTED_LANGUAGES = setOf("en", "de", "fr", "es", "it", "nl")
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var recorder: AudioRecord? = null
    private var readerThread: Thread? = null

    @Volatile
    private var recording = false
    private val chunks = ArrayList<FloatArray>()
    private var totalSamples = 0

    // recording waveform inside the suggestion strip (covers the suggestions area)
    private var waveform: WaveformBarView? = null
    @Volatile private var themeBackground = 0xFF16191E.toInt()
    @Volatile private var themeLine = 0xFF3D5AFE.toInt()

    // gesture bookkeeping between press / release / tap events
    @Volatile
    private var gestureHandled = false // press started a dictation gesture (PTT)

    // timestamp of the last press/release belonging to a PTT gesture — the tap
    // event for that gesture may arrive BEFORE or AFTER the release (device-dependent)
    @Volatile
    private var lastGestureAt = 0L

    @Volatile
    private var pendingRelease = false

    @Volatile
    private var sessionActive = false

    /** Theme colors for the recording waveform (line color over the strip background). */
    fun setColors(line: Int, background: Int) {
        themeLine = line
        themeBackground = background
    }

    /**
     * Restricts recognition to the given language codes (the keyboard's active languages).
     * The engine auto-detects; an utterance it hears as an unsupported language gets one
     * re-decode with the keyboard language. Empty set = unrestricted auto-detect.
     */
    fun setAllowedLanguages(codes: Set<String>) {
        allowedLanguages = codes
    }

    @Volatile
    private var allowedLanguages: Set<String> = emptySet()

    private fun showWaveform() {
        main.post {
            val content = ime.window?.findViewById(android.R.id.content) as? android.view.ViewGroup
            val stripId = ime.resources.getIdentifier("suggestions_strip", "id", ime.packageName)
            val strip = if (content != null && stripId != 0) content.findViewById<View>(stripId) as? android.view.ViewGroup else null
            val existing = waveform
            if (existing != null) {
                if (existing.parent === strip && strip != null) {
                    Log.d(TAG, "waveform: reusing existing view")
                    return@post
                }
                // strip was recreated — drop the stale view and anchor fresh
                Log.d(TAG, "waveform: stale anchor — re-anchoring")
                (existing.parent as? android.view.ViewGroup)?.removeView(existing)
                waveform = null
            }
            if (strip == null || strip.width == 0) {
                Log.w(TAG, "waveform: suggestion strip not ready (id=$stripId, strip=${strip != null}, width=${strip?.width}) — retrying")
                main.postDelayed({ showWaveform() }, 100)
                return@post
            }
            Log.d(TAG, "waveform anchored to suggestion strip ${strip.width}x${strip.height}")
            val bar = WaveformBarView(ime, themeBackground, themeLine)
            // explicit size: the strip is wrap_content, MATCH_PARENT would measure 0
            strip.addView(bar, android.widget.LinearLayout.LayoutParams(strip.width, strip.height))
            // the waveform takes over the strip: hide suggestions/pills while recording
            for (i in 0 until strip.childCount - 1) strip.getChildAt(i).visibility = View.GONE
            waveform = bar
        }
    }

    private fun hideWaveform() {
        main.post {
            waveform?.let { bar ->
                (bar.parent as? android.view.ViewGroup)?.let { strip ->
                    strip.removeView(bar)
                    for (i in 0 until strip.childCount) strip.getChildAt(i).visibility = View.VISIBLE
                }
            }
            waveform = null
        }
    }

    /** Key-down on the layout voice key: start push-to-talk. */
    fun onPressStart() {
        Log.d(TAG, "onPressStart: engine available=${NeedleEngine.available()}")
        gestureHandled = false
        pendingRelease = false
        if (!NeedleEngine.available()) return // no native lib (wrong ABI) → legacy voice IME
        gestureHandled = true
        lastGestureAt = android.os.SystemClock.uptimeMillis()
        if (!hasMicPermission()) {
            launchPermissionActivity()
            return
        }
        executor.execute { prepareAndStartRecording() }
    }

    /** Key-up on the layout voice key: stop and transcribe. */
    fun onPressEnd() {
        Log.d(TAG, "onPressEnd: gestureHandled=$gestureHandled sessionActive=$sessionActive")
        if (!gestureHandled) return
        gestureHandled = false
        lastGestureAt = android.os.SystemClock.uptimeMillis()
        pendingRelease = true
        if (sessionActive) finalizeOnExecutor()
    }

    /**
     * Tap-complete event for KeyCode.VOICE_INPUT (fires for toolbar mic AND layout key).
     * Returns true when dictation handled this gesture, so the legacy
     * switch-to-external-voice-IME path must be skipped.
     */
    fun onVoiceKeyTap(): Boolean {
        Log.d(TAG, "onVoiceKeyTap: gestureHandled=$gestureHandled sessionActive=$sessionActive recording=$recording")
        // this tap belongs to a press/release gesture (it can arrive before OR after
        // the release) — consume it so the legacy path stays out, and leave the
        // release event something to finalize
        if (gestureHandled || android.os.SystemClock.uptimeMillis() - lastGestureAt < 1500) {
            return true
        }
        if (!NeedleEngine.available()) return false
        if (!hasMicPermission()) {
            launchPermissionActivity()
            return true
        }
        if (sessionActive || recording) {
            // second tap of toggle mode: stop and commit
            finalizeOnExecutor()
            return true
        }
        // first tap of toggle mode: start; keeps recording until the next tap
        executor.execute { prepareAndStartRecording() }
        return true
    }

    private fun prepareAndStartRecording() {
        val model = ModelManager.modelFile(ime)
        if (!model.exists()) {
            if (ModelManager.startDownload(ime)) {
                toast("Downloading dictation model (17 MB)…")
            } else {
                toast("Dictation model is downloading…")
            }
            return
        }
        if (!NeedleEngine.ensureLoaded(model)) {
            toast("Dictation failed to load")
            return
        }
        startRecorder()
    }

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
            val tmp = FloatArray(SAMPLE_RATE / 25) // 40 ms — amplitude granularity for the waveform
            while (recording && totalSamples < MAX_SAMPLES) {
                val n = rec.read(tmp, 0, tmp.size, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    synchronized(chunks) {
                        chunks.add(tmp.copyOf(n))
                        totalSamples += n
                    }
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = tmp[i].toDouble()
                        sum += v * v
                    }
                    val rms = kotlin.math.sqrt(sum / n)
                    waveform?.pushAmplitude(rms.toFloat()) // thread-safe (postInvalidateOnAnimation)
                }
            }
            // hit the 30 s cap with nobody pressing stop (user walked away) — auto-finalize
            if (totalSamples >= MAX_SAMPLES && sessionActive) {
                finalizeOnExecutor()
            }
        }.also { it.start() }
        showWaveform()
        if (pendingRelease) finalizeOnExecutor()
    }

    private fun finalizeOnExecutor() {
        sessionActive = false
        hideWaveform()
        executor.execute {
            val pcm = stopRecorder() ?: return@execute
            val keyboardLanguage = currentLanguage()
            // restricted mode: the engine picks between the keyboard's active languages;
            // anything else it thinks it heard is ignored and re-decoded with the keyboard language
            var text = if (allowedLanguages.isNotEmpty()) {
                val (autoText, detected) = NeedleEngine.transcribeAutoDetect(pcm)
                when {
                    autoText.isEmpty() -> ""
                    detected in allowedLanguages -> autoText.trim()
                    else -> {
                        Log.d(TAG, "detected language $detected, not in $allowedLanguages — re-decoding with keyboard language $keyboardLanguage")
                        NeedleEngine.transcribe(pcm, keyboardLanguage ?: allowedLanguages.first(), null).trim()
                    }
                }
            } else {
                NeedleEngine.transcribe(pcm, keyboardLanguage, null).trim()
            }
            if (text.isEmpty()) {
                main.post { toast("Nothing heard") }
                return@execute
            }
            // every dictation becomes a clean sentence: end punctuation + space,
            // so consecutive recordings read as separate sentences
            if (text.last() !in ".!?…") text += "."
            text += " "
            main.post {
                ime.currentInputConnection?.commitText(text, 1)
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
            @Suppress("DEPRECATION")
            val imm = ime.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            val code = imm.currentInputMethodSubtype?.locale
                ?: ime.resources.configuration.locales[0].language
            when (code.lowercase().substringBefore('_')) {
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
