package app.reventor.dictation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Overlay sized exactly to the keyboard view: hides the keys while recording
 * and draws a circle that pulses with the live microphone amplitude.
 *
 * The amplitude is smoothed per frame (fast attack, slow release) so the circle
 * pulses naturally instead of jumping with raw chunk loudness.
 */
class RecordingOverlay(
    context: Context,
    background: Int,
    circleColor: Int,
    private val fixedWidth: Int,
    private val fixedHeight: Int,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = circleColor }
    private var targetAmp = 0f
    private var smoothedAmp = 0f

    init {
        setBackgroundColor(background)
    }

    /** rms is the root-mean-square of a chunk in [-1..1] PCM range. Thread-safe. */
    fun setAmplitude(rms: Float) {
        targetAmp = (rms * 4f).coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(fixedWidth, fixedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        // fast attack / slow release keeps the motion continuous between chunks
        smoothedAmp += (targetAmp - smoothedAmp) * (if (targetAmp > smoothedAmp) 0.45f else 0.10f)
        val minDim = minOf(fixedWidth, fixedHeight).toFloat()
        val radius = minDim * 0.10f + smoothedAmp * minDim * 0.32f
        canvas.drawCircle(fixedWidth / 2f, fixedHeight / 2f, radius, paint)
        postInvalidateOnAnimation()
    }
}
