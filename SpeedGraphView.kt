package com.example.mytunnel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A tiny sparkline showing recent network speed samples (KB/s).
 * Feed it new values with addSample(); it keeps the last [maxSamples]
 * and redraws as a simple connected-line graph.
 */
class SpeedGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxSamples = 30
    private val samples = ArrayDeque<Float>()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8BC98B.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    fun addSample(kbPerSecond: Float) {
        if (samples.size >= maxSamples) samples.removeFirst()
        samples.addLast(kbPerSecond)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2) return

        val maxVal = (samples.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val stepX = width.toFloat() / (maxSamples - 1).coerceAtLeast(1)
        val startIndex = maxSamples - samples.size

        var prevX = 0f
        var prevY = 0f
        samples.forEachIndexed { i, value ->
            val x = (startIndex + i) * stepX
            val y = height - (value / maxVal) * height
            if (i > 0) {
                canvas.drawLine(prevX, prevY, x, y, linePaint)
            }
            prevX = x
            prevY = y
        }
    }
}
