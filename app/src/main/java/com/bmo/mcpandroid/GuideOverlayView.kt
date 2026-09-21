package com.bmo.mcpandroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = 0xffff3b30.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 42f
        color = 0xffffffff.toInt()
        setShadowLayer(6f, 0f, 0f, 0xff000000.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = FrameStore.snapshot()
        val fw = frame?.width?.toFloat()?.coerceAtLeast(1f) ?: width.toFloat().coerceAtLeast(1f)
        val fh = frame?.height?.toFloat()?.coerceAtLeast(1f) ?: height.toFloat().coerceAtLeast(1f)

        // PreviewView uses FILL_CENTER. Apply the same center-crop transform so
        // normalized coordinates returned by MCP align with what the user sees.
        val scale = max(width / fw, height / fh)
        val renderedW = fw * scale
        val renderedH = fh * scale
        val offsetX = (width - renderedW) / 2f
        val offsetY = (height - renderedH) / 2f

        OverlayStore.snapshot().forEach { item ->
            val cx = offsetX + item.x.coerceIn(0f, 1f) * renderedW
            val cy = offsetY + item.y.coerceIn(0f, 1f) * renderedH
            val radius = item.radius.coerceIn(0.01f, 0.5f) * minOf(fw, fh) * scale
            canvas.drawCircle(cx, cy, radius, circlePaint)
            item.label?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText(it.take(80), cx + radius + 12f, cy, textPaint)
            }
        }
        postInvalidateDelayed(100)
    }
}
