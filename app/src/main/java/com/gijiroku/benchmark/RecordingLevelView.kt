package com.gijiroku.benchmark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.log10

/** Rolling input levels only; no audio samples or speaker features are retained. */
class RecordingLevelView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val levels = FloatArray(48)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun pushLevel(peak: Float) {
        levels.copyInto(levels, 0, 1)
        levels[levels.lastIndex] = ((20f * log10(peak.coerceAtLeast(0.001f)) + 60f) / 60f).coerceIn(0f, 1f)
        invalidate()
    }

    fun clear() {
        levels.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = ContextCompat.getColor(context, R.color.voice_accent)
        val step = width.toFloat() / levels.size
        val center = height / 2f
        levels.forEachIndexed { index, level ->
            val barHeight = maxOf(resources.displayMetrics.density * 3f, level * height * 0.85f)
            val left = index * step
            canvas.drawRoundRect(left, center - barHeight / 2, left + step * 0.55f,
                center + barHeight / 2, step, step, paint)
        }
    }
}
