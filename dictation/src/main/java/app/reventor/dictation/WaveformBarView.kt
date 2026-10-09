package app.reventor.dictation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * Recording waveform inside the suggestion strip: while recording, this view
 * covers the middle of the bar (where the word suggestions normally appear)
 * and draws the live microphone amplitude as a continuous right-to-left
 * flowing waveform line, like a voice message.
 */
class WaveformBarView(
    context: Context,
    private val backgroundColor: Int,
    lineColor: Int,
) : View(context) {

    private val bgPaint = Paint().apply { color = backgroundColor }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        // stroke width is set per-draw: the bar is narrow, so a fixed dp width
        // would make the 72 overlapping bars fuse into a solid ribbon
    }

    /** amplitude history; head = newest sample */
    private val samples = FloatArray(72)
    private var head = samples.size - 1
    private var lastSampleUptime = 0L
    private var hasSample = false

    /** rms is the root-mean-square of a chunk in [-1..1] PCM range. Thread-safe. */
    fun pushAmplitude(rms: Float) {
        head = (head + 1) % samples.size
        samples[head] = (rms * 4f).coerceIn(0f, 1f)
        lastSampleUptime = SystemClock.uptimeMillis()
        hasSample = true
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(bgPaint)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val center = h / 2f
        val halfAmp = h * 0.38f
        if (!hasSample) return

        val step = w / (samples.size - 1)
        linePaint.strokeWidth = (step * 0.55f).coerceIn(2f, 4f * resources.displayMetrics.density)
        // the newest sample keeps moving left between arrivals -> continuous flow
        val sinceSample = (SystemClock.uptimeMillis() - lastSampleUptime)
            .coerceIn(0L, SAMPLE_INTERVAL_MS)
        val shift = (sinceSample / SAMPLE_INTERVAL_MS.toFloat()) * step

        // symmetric waveform: each sample is a rounded bar going up AND down
        // from the center line, like a voice message
        for (i in 0 until samples.size) {
            val idx = (head - i + samples.size * 2) % samples.size
            val x = w - i * step - shift
            if (x < 0) break
            val amp = samples[idx] * halfAmp
            canvas.drawLine(x, center - amp, x, center + amp, linePaint)
        }
        postInvalidateOnAnimation()
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 40L
    }
}
