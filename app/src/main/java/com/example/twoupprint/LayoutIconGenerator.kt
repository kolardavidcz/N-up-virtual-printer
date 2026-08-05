package com.example.twoupprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Dynamically generates a crisp white-on-transparent preview icon bitmap for any X:Y grid configuration.
 * Icons blend seamlessly with Android system print dialogs (Samsung Print Spooler).
 */
object LayoutIconGenerator {

    fun generateIconBitmap(cols: Int, rows: Int, landscape: Boolean, size: Int = 128): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Clear with transparent background
        canvas.drawColor(Color.TRANSPARENT)

        val margin = 10f
        val sheetRect = if (landscape) {
            val h = size - margin * 2
            val w = (h * 1.35f).coerceAtMost(size - margin * 2)
            val left = (size - w) / 2f
            RectF(left, margin, left + w, margin + h)
        } else {
            val w = size - margin * 2
            val h = (w * 1.35f).coerceAtMost(size - margin * 2)
            val top = (size - h) / 2f
            RectF(margin, top, margin + w, top + h)
        }

        // Outer sheet border: solid white stroke, transparent fill
        val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.WHITE
        }

        canvas.drawRoundRect(sheetRect, 10f, 10f, sheetPaint)

        // Grid cell placement
        val padding = 10f
        val gap = 4f.coerceAtMost((sheetRect.width() - padding * 2) / (cols * 3))

        val gridW = sheetRect.width() - (padding * 2)
        val gridH = sheetRect.height() - (padding * 2)

        val cellW = (gridW - (gap * (cols - 1))) / cols
        val cellH = (gridH - (gap * (rows - 1))) / rows

        // Solid white inner page slots
        val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = sheetRect.left + padding + c * (cellW + gap)
                val top = sheetRect.top + padding + r * (cellH + gap)
                val right = left + cellW
                val bottom = top + cellH

                val pageRect = RectF(left, top, right, bottom)
                canvas.drawRoundRect(pageRect, 4f, 4f, pagePaint)
            }
        }

        return bitmap
    }
}
