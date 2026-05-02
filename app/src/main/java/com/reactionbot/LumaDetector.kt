package com.reactionbot

import java.nio.ByteBuffer

/**
 * Zero-allocation luma detector.
 * Reads directly from a ByteBuffer backed by ImageReader — no copies, no file I/O.
 *
 * Frame format: PixelFormat.RGBA_8888 (4 bytes per pixel)
 * Luma = 0.299*R + 0.587*G + 0.114*B  (ITU-R BT.601)
 */
object LumaDetector {

    private const val DARK_THRESHOLD = 50

    /**
     * Returns integer luma [0..255] at pixel (x, y).
     * Buffer position must be at the start of the plane data.
     */
    fun getLuma(
        buffer: ByteBuffer,
        x: Int,
        y: Int,
        rowStride: Int,
        pixelStride: Int
    ): Int {
        val base = y * rowStride + x * pixelStride
        if (base + 2 >= buffer.limit()) return 255
        val r = buffer.get(base).toInt() and 0xFF
        val g = buffer.get(base + 1).toInt() and 0xFF
        val b = buffer.get(base + 2).toInt() and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    fun isDark(luma: Int, threshold: Int = DARK_THRESHOLD): Boolean = luma < threshold
}
