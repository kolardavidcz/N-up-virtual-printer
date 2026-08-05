package com.example.twoupprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * High-precision vector icon generator for standard and custom X:Y virtual printers.
 * Renders crisp, anti-aliased white-on-transparent sheet diagrams that scale gracefully from 2x1 up to 10x10.
 */
object LayoutIconGenerator {

    fun generateIconBitmap(cols: Int, rows: Int, landscape: Boolean, size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val margin = 16f
        val a4Ratio = 1.414f // Standard ISO A4 aspect ratio

        // Calculate sheet rectangle maintaining true A4 aspect ratio
        val sheetRect = if (landscape) {
            val availableH = size - margin * 2
            val maxW = size - margin * 2
            val calcW = availableH * a4Ratio
            if (calcW <= maxW) {
                val left = (size - calcW) / 2f
                RectF(left, margin, left + calcW, margin + availableH)
            } else {
                val calcH = maxW / a4Ratio
                val top = (size - calcH) / 2f
                RectF(margin, top, margin + maxW, top + calcH)
            }
        } else {
            val availableW = size - margin * 2
            val maxH = size - margin * 2
            val calcH = availableW * a4Ratio
            if (calcH <= maxH) {
                val top = (size - calcH) / 2f
                RectF(margin, top, margin + availableW, top + calcH)
            } else {
                val calcW = maxH / a4Ratio
                val left = (size - calcW) / 2f
                RectF(left, margin, left + calcW, margin + maxH)
            }
        }

        // Outer A4 Sheet Frame (Solid White 6px Stroke, Transparent Fill)
        val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        canvas.drawRoundRect(sheetRect, 14f, 14f, sheetPaint)

        // Dynamic Inner Padding & Gap calculation based on grid density
        val innerPadding = (sheetRect.width() * 0.08f).coerceAtLeast(10f)
        val availableGridW = sheetRect.width() - (innerPadding * 2)
        val availableGridH = sheetRect.height() - (innerPadding * 2)

        val gap = (availableGridW / (cols * 5f)).coerceIn(3f, 10f)

        val cellW = (availableGridW - (gap * (cols - 1))) / cols
        val cellH = (availableGridH - (gap * (rows - 1))) / rows

        val cornerRadius = (cellW * 0.12f).coerceIn(2f, 8f)

        // Sub-page slot fill (Solid White)
        val pageFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        // Sub-page slot stroke (for dense grids to maintain separation)
        val pageStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.WHITE
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = sheetRect.left + innerPadding + c * (cellW + gap)
                val top = sheetRect.top + innerPadding + r * (cellH + gap)
                val right = left + cellW
                val bottom = top + cellH

                val pageRect = RectF(left, top, right, bottom)
                canvas.drawRoundRect(pageRect, cornerRadius, cornerRadius, pageFillPaint)
                canvas.drawRoundRect(pageRect, cornerRadius, cornerRadius, pageStrokePaint)
            }
        }

        return bitmap
    }
}
