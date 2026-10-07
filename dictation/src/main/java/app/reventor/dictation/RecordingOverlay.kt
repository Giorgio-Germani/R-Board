package app.reventor.dictation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Full-size overlay over the keyboard area: hides the keys while recording and
 * draws a circle that grows/shrinks with the live microphone amplitude,
 * centered where the keyboard keys normally are.
 *
 * The amplitude is smoothed per frame (fast attack, slow release) so the circle
 * pulses naturally instead of jumping with raw chunk loudness.
 */
class RecordingOverlay(context: Context, background: Int, circleColor: Int) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = circleColor }
    private var targetAmp = 0f
    private var smoothedAmp = 0f
    private var running = true

    init {
        setBackgroundColor(background)
    }

    /** rms is the root-mean-square of a chunk in [-1..1] PCM range. Thread-safe. */
    fun setAmplitude(rms: Float) {
        targetAmp = (rms * 4f).coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    fun stopAnimating() {
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        // fast attack / slow release keeps the motion continuous between chunks
        smoothedAmp += (targetAmp - smoothedAmp) * (if (targetAmp > smoothedAmp) 0.45f else 0.10f)
        val minDim = minOf(width, height).toFloat()
        val radius = minDim * 0.10f + smoothedAmp * minDim * 0.32f
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        if (running) postInvalidateOnAnimation()
    }
}
