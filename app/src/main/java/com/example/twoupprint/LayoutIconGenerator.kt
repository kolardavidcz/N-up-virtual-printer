package com.example.twoupprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Generates crisp vector-style N-up sheet preview icons where each grid page
 * is rendered as a mini PDF document (with folded top-right corner and content lines).
 * Output is monocolor black + white on a transparent background.
 */
object LayoutIconGenerator {

    fun generateIconBitmap(cols: Int, rows: Int, landscape: Boolean, size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val margin = 14f
        val a4Ratio = 1.414f

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

        // Outer sheet frame: Solid White Stroke
        val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawRoundRect(sheetRect, 14f, 14f, sheetPaint)

        // Dynamic Inner Padding & Gap
        val innerPadding = (sheetRect.width() * 0.07f).coerceAtLeast(8f)
        val availableGridW = sheetRect.width() - (innerPadding * 2)
        val availableGridH = sheetRect.height() - (innerPadding * 2)

        val gap = (availableGridW / (cols * 6f)).coerceIn(3f, 8f)

        val cellW = (availableGridW - (gap * (cols - 1))) / cols
        val cellH = (availableGridH - (gap * (rows - 1))) / rows

        // Paints for Mini PDF Pages
        val pageFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        val pageOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.BLACK
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val foldFlapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 1.5f
            color = Color.BLACK
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (cellW * 0.08f).coerceIn(1.5f, 3f)
            color = Color.BLACK
            strokeCap = Paint.Cap.ROUND
        }

        val foldSize = (cellW.coerceAtMost(cellH) * 0.22f).coerceIn(4f, 16f)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = sheetRect.left + innerPadding + c * (cellW + gap)
                val top = sheetRect.top + innerPadding + r * (cellH + gap)
                val right = left + cellW
                val bottom = top + cellH

                // Mini PDF Page Path with Folded Top-Right Corner
                val pagePath = Path().apply {
                    moveTo(left, top)
                    lineTo(right - foldSize, top)
                    lineTo(right, top + foldSize)
                    lineTo(right, bottom)
                    lineTo(left, bottom)
                    close()
                }

                // Draw Page White Fill & Black Outline
                canvas.drawPath(pagePath, pageFillPaint)
                canvas.drawPath(pagePath, pageOutlinePaint)

                // Folded Corner Flap Triangle
                val flapPath = Path().apply {
                    moveTo(right - foldSize, top)
                    lineTo(right - foldSize, top + foldSize)
                    lineTo(right, top + foldSize)
                    close()
                }
                canvas.drawPath(flapPath, foldFlapPaint)

                // Content Lines inside mini PDF page
                if (cellH >= 18f && cellW >= 14f) {
                    val lineMarginX = cellW * 0.2f
                    val lineStartX = left + lineMarginX
                    val lineEndX = right - lineMarginX

                    val startY = top + foldSize + (cellH * 0.12f)
                    val lineGap = (bottom - startY) / 3.5f

                    for (i in 0..1) {
                        val ly = startY + i * lineGap
                        if (ly < bottom - 4f) {
                            val currentEndX = if (i == 1) left + (cellW * 0.55f) else lineEndX
                            canvas.drawLine(lineStartX, ly, currentEndX, ly, linePaint)
                        }
                    }
                }
            }
        }

        return bitmap
    }
}
