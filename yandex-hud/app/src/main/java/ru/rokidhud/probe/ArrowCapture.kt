package ru.rokidhud.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.widget.ImageView
import java.security.MessageDigest

/**
 * Extracts a bounded in-memory representation of an ImageView's drawable.
 * Does not capture the phone display or infer a turn from unknown pixels.
 */
object ArrowCapture {
    data class Sample(val id: String, val bitmap: Bitmap, val digest: String)
    fun fromImage(view: ImageView, id: String): Sample? {
        val drawable = view.drawable ?: return null
        val intrinsicW = drawable.intrinsicWidth.takeIf { it > 0 } ?: view.width
        val intrinsicH = drawable.intrinsicHeight.takeIf { it > 0 } ?: view.height
        if (intrinsicW !in 1..4096 || intrinsicH !in 1..4096) return null
        val scale = minOf(128f / intrinsicW, 128f / intrinsicH, 1f)
        val w = (intrinsicW * scale).toInt().coerceAtLeast(1)
        val h = (intrinsicH * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val previous = drawable.bounds
        try {
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
        } catch (_: Exception) {
            bitmap.recycle()
            return null
        } finally {
            drawable.bounds = previous
        }
        val bytes = ByteArray(w * h * 4 + 8)
        val b = java.nio.ByteBuffer.wrap(bytes)
        b.putInt(w); b.putInt(h)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        pixels.forEach(b::putInt)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return Sample(id, bitmap, digest)
    }
}
