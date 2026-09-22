package com.example.twoupprint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Live visual preview showing how subpages sit on a sheet with configurable
 * outer margins and inner gutters (0 mm, 3 mm, 6 mm).
 */
class MarginPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var marginMm: Int = 0

    private val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2D2B33")
        style = Paint.Style.FILL
    }

    private val sheetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#49454F")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#383540")
        style = Paint.Style.FILL
    }

    private val slotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D0BCFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val miniLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#605D66")
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }

    private val sheetRect = RectF()
    private val slotRect = RectF()

    fun setMarginMm(mm: Int) {
        if (this.marginMm != mm) {
            this.marginMm = mm
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Draw outer sheet box with 16:9 or A4 proportions
        val padding = 12f
        val availW = w - 2 * padding
        val availH = h - 2 * padding

        // Landscape sheet ratio (~1.414 or 1.6)
        val targetRatio = 1.45f
        val sheetW: Float
        val sheetH: Float
        if (availW / availH > targetRatio) {
            sheetH = availH
            sheetW = sheetH * targetRatio
        } else {
            sheetW = availW
            sheetH = sheetW / targetRatio
        }

        val sheetLeft = (w - sheetW) / 2f
        val sheetTop = (h - sheetH) / 2f
        sheetRect.set(sheetLeft, sheetTop, sheetLeft + sheetW, sheetTop + sheetH)

        val sheetCorner = 10f
        canvas.drawRoundRect(sheetRect, sheetCorner, sheetCorner, sheetPaint)
        canvas.drawRoundRect(sheetRect, sheetCorner, sheetCorner, sheetBorderPaint)

        // Calculate visual margin gap based on marginMm
        // 0mm -> 1.5dp gap, 3mm -> 6dp gap, 6mm -> 11dp gap
        val visualMargin = when (marginMm) {
            0 -> 1.5f * density
            3 -> 6.5f * density
            else -> 12f * density
        }

        val printableW = sheetW - 2 * visualMargin
        val printableH = sheetH - 2 * visualMargin

        val cols = 2
        val rows = 2

        val slotW = (printableW - (cols - 1) * visualMargin) / cols
        val slotH = (printableH - (rows - 1) * visualMargin) / rows

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val sLeft = sheetLeft + visualMargin + c * (slotW + visualMargin)
                val sTop = sheetTop + visualMargin + r * (slotH + visualMargin)
                slotRect.set(sLeft, sTop, sLeft + slotW, sTop + slotH)

                val slotCorner = 6f
                canvas.drawRoundRect(slotRect, slotCorner, slotCorner, slotPaint)
                canvas.drawRoundRect(slotRect, slotCorner, slotCorner, slotBorderPaint)

                // Mini content lines inside each slot for realistic slide preview
                val lineInsetX = slotW * 0.2f
                val lineStartY = sTop + slotH * 0.35f
                val lineSpacing = slotH * 0.22f
                canvas.drawLine(sLeft + lineInsetX, lineStartY, sLeft + slotW - lineInsetX, lineStartY, miniLinePaint)
                canvas.drawLine(sLeft + lineInsetX, lineStartY + lineSpacing, sLeft + slotW * 0.65f, lineStartY + lineSpacing, miniLinePaint)
            }
        }
    }

    private val density: Float
        get() = resources.displayMetrics.density
}
