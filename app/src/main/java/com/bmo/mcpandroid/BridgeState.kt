package com.bmo.mcpandroid

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

data class CameraFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtMs: Long
)

object FrameStore {
    private val latest = AtomicReference<CameraFrame?>(null)

    fun update(bitmap: Bitmap, maxWidth: Int = 1280, quality: Int = 72) {
        val scaled = if (bitmap.width > maxWidth) {
            val h = (bitmap.height * (maxWidth.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, maxWidth, h, true)
        } else bitmap

        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(35, 92), output)
        latest.set(CameraFrame(output.toByteArray(), scaled.width, scaled.height, System.currentTimeMillis()))
        if (scaled !== bitmap) scaled.recycle()
    }

    fun snapshot(): CameraFrame? = latest.get()
}

data class CircleOverlay(
    val x: Float,
    val y: Float,
    val radius: Float,
    val label: String?,
    val expiresAtMs: Long
)

object OverlayStore {
    private val circles = CopyOnWriteArrayList<CircleOverlay>()

    fun add(circle: CircleOverlay) {
        prune()
        circles.add(circle)
    }

    fun clear() = circles.clear()

    fun snapshot(): List<CircleOverlay> {
        prune()
        return circles.toList()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        circles.removeAll { it.expiresAtMs <= now }
    }
}

object TunnelState {
    @Volatile var status: String = "DISCONNECTED"
    @Volatile var lastError: String? = null
}
