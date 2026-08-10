package com.example.twoupprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Generates crisp, clean grid diagram preview icons for virtual printers.
 *
 * The outer sheet respects the configured orientation (landscape or portrait A4).
 * Each sub-page slot is drawn as a correctly-proportioned portrait A4 mini-page
 * (source documents are always portrait A4), centered within its grid slot.
 * This ensures icons look realistic regardless of sheet orientation.
 */
object LayoutIconGenerator {

    private const val A4_RATIO = 1.4142f  // Portrait A4: height/width = √2

    fun generateIconBitmap(cols: Int, rows: Int, landscape: Boolean, size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val margin = 14f

        // --- Outer sheet rectangle at correct A4 ratio ---
        val sheetRect = calcSheetRect(size, margin, landscape)

        // Sheet background fill (very subtle dark fill so sheet is visible)
        val sheetBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(40, 255, 255, 255)
        }
        canvas.drawRoundRect(sheetRect, 12f, 12f, sheetBgPaint)

        // Sheet outline
        val sheetStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5.5f
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawRoundRect(sheetRect, 12f, 12f, sheetStrokePaint)

        // --- Grid layout ---
        val innerPad = (sheetRect.width() * 0.07f).coerceAtLeast(7f)
        val gap = (sheetRect.width() * 0.04f).coerceIn(2f, 9f)

        val availW = sheetRect.width() - innerPad * 2
        val availH = sheetRect.height() - innerPad * 2

        val slotW = (availW - gap * (cols - 1)) / cols
        val slotH = (availH - gap * (rows - 1)) / rows

        // Sub-pages are always portrait A4 documents — fit them into the slot maintaining ratio
        var subW = slotW
        var subH = slotW * A4_RATIO
        if (subH > slotH) {
            subH = slotH
            subW = slotH / A4_RATIO
        }

        val cornerR = (subW * 0.10f).coerceIn(2f, 7f)

        // Sub-page fill — white with slight transparency so the sheet bg shows through gaps
        val pageFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(235, 255, 255, 255)
        }

        // Subtle inner stroke on sub-pages for depth
        val pageStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.argb(80, 180, 180, 180)
        }

        // Faint horizontal "text line" hints inside each sub-page
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(55, 100, 100, 100)
            strokeCap = Paint.Cap.ROUND
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val slotLeft = sheetRect.left + innerPad + c * (slotW + gap)
                val slotTop  = sheetRect.top  + innerPad + r * (slotH + gap)

                // Center sub-page within slot
                val pageLeft = slotLeft + (slotW - subW) / 2f
                val pageTop  = slotTop  + (slotH - subH) / 2f
                val pageRect = RectF(pageLeft, pageTop, pageLeft + subW, pageTop + subH)

                canvas.drawRoundRect(pageRect, cornerR, cornerR, pageFillPaint)
                canvas.drawRoundRect(pageRect, cornerR, cornerR, pageStrokePaint)

                // Draw 3–4 faint content lines if sub-page is large enough
                if (subW >= 20f && subH >= 28f) {
                    val lineMarginH = subW * 0.16f
                    val lineMarginV = subH * 0.18f
                    val lineHeight  = (subH * 0.065f).coerceIn(2f, 5f)
                    val lineSpacing = lineHeight * 2.4f
                    val lineCount   = ((subH - lineMarginV * 2) / lineSpacing).toInt().coerceIn(2, 5)
                    val linesRect   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = Color.argb(50, 90, 90, 90)
                    }
                    for (i in 0 until lineCount) {
                        val ly = pageTop + lineMarginV + i * lineSpacing
                        val lineW = if (i == lineCount - 1) (subW - lineMarginH * 2) * 0.6f
                                    else subW - lineMarginH * 2
                        val lineRect = RectF(
                            pageLeft + lineMarginH, ly,
                            pageLeft + lineMarginH + lineW, ly + lineHeight
                        )
                        canvas.drawRoundRect(lineRect, lineHeight / 2f, lineHeight / 2f, linesRect)
                    }
                }
            }
        }

        return bitmap
    }

    private fun calcSheetRect(size: Int, margin: Float, landscape: Boolean): RectF {
        val available = size - margin * 2
        return if (landscape) {
            // Landscape A4: width/height = √2
            val h = available / A4_RATIO
            val w = available
            val actualH = h.coerceAtMost(available)
            val actualW = if (actualH < h) actualH * A4_RATIO else w
            val top  = (size - actualH) / 2f
            val left = (size - actualW) / 2f
            RectF(left, top, left + actualW, top + actualH)
        } else {
            // Portrait A4: height/width = √2
            val w = available / A4_RATIO
            val h = available
            val actualW = w.coerceAtMost(available)
            val actualH = if (actualW < w) actualW * A4_RATIO else h
            val top  = (size - actualH) / 2f
            val left = (size - actualW) / 2f
            RectF(left, top, left + actualW, top + actualH)
        }
    }
}
