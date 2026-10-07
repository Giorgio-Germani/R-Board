package app.reventor.dictation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Overlay covering ONLY the letter rows of the keyboard: while recording, the
 * letters disappear and a circle pulses with the live microphone amplitude.
 * The suggestion strip above and the bottom row (spacebar / mic / enter) below
 * stay visible.
 */
class RecordingOverlay(
    context: Context,
    private val backgroundColor: Int,
    circleColor: Int,
    private val fixedWidth: Int,
    private val fixedHeight: Int,
    private val letterAreaHeight: Int,
) : View(context) {

    private val bgPaint = Paint().apply { color = backgroundColor }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = circleColor }
    private var targetAmp = 0f
    private var smoothedAmp = 0f

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
        val area = letterAreaHeight.coerceAtLeast(1).toFloat()
        val w = fixedWidth.toFloat()
        canvas.drawRect(0f, 0f, w, area, bgPaint)
        val radius = area * (0.12f + smoothedAmp * 0.30f)
        canvas.drawCircle(w / 2f, area / 2f, radius.coerceAtMost(area / 2f - 4f), circlePaint)
        postInvalidateOnAnimation()
    }
}
