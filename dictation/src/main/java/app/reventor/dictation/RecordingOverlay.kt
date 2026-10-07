package app.reventor.dictation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

/**
 * Overlay covering ONLY the letter rows of the keyboard: while recording, the
 * letters disappear and a waveform line scrolls continuously right-to-left,
 * drawing the live microphone amplitude like an oscilloscope. The suggestion
 * strip above and the bottom row (spacebar / mic / enter) below stay visible.
 */
class RecordingOverlay(
    context: Context,
    private val backgroundColor: Int,
    lineColor: Int,
    private val fixedWidth: Int,
    private val fixedHeight: Int,
    private val letterAreaHeight: Int,
) : View(context) {

    private val bgPaint = Paint().apply { color = backgroundColor }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4f * resources.displayMetrics.density
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(fixedWidth, fixedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val w = fixedWidth.toFloat()
        val area = letterAreaHeight.coerceAtLeast(1).toFloat()
        val center = area / 2f
        val halfAmp = area * 0.42f
        canvas.drawRect(0f, 0f, w, area, bgPaint)
        if (!hasSample) return

        val step = w / (samples.size - 1)
        // the newest sample keeps moving left between arrivals -> continuous flow
        val sinceSample = (SystemClock.uptimeMillis() - lastSampleUptime)
            .coerceIn(0L, SAMPLE_INTERVAL_MS)
        val shift = (sinceSample / SAMPLE_INTERVAL_MS.toFloat()) * step

        val path = Path()
        var started = false
        for (i in samples.size downTo 1) {
            val idx = (head - (i - 1) + samples.size * 2) % samples.size
            val x = w - (i - 1) * step - shift
            if (x < 0) break
            val y = center - samples[idx] * halfAmp
            if (started) path.lineTo(x, y) else { path.moveTo(x, y); started = true }
        }
        canvas.drawPath(path, linePaint)
        postInvalidateOnAnimation()
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 40L
    }
}
