package com.example.twoupprint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Live visual preview showing how subpages sit on a sheet with configurable
 * per-side margins (Top, Bottom, Left, Right).
 */
class MarginPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var marginTopMm: Int = 0
    private var marginBottomMm: Int = 0
    private var marginLeftMm: Int = 0
    private var marginRightMm: Int = 0

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

    private val marginGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D8055E8") // Semi-transparent purple guide
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }

    private val miniLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#605D66")
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }

    private val sheetRect = RectF()
    private val slotRect = RectF()

    fun setMargins(top: Int, bottom: Int, left: Int, right: Int) {
        if (marginTopMm != top || marginBottomMm != bottom || marginLeftMm != left || marginRightMm != right) {
            marginTopMm = top
            marginBottomMm = bottom
            marginLeftMm = left
            marginRightMm = right
            invalidate()
        }
    }

    fun setMarginMm(mm: Int) {
        setMargins(mm, mm, mm, mm)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Outer sheet box with landscape presentation/A4 proportions
        val padding = 10f
        val availW = w - 2 * padding
        val availH = h - 2 * padding

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

        // Calculate visual margins per side
        val vTop = mmToVisualPx(marginTopMm)
        val vBottom = mmToVisualPx(marginBottomMm)
        val vLeft = mmToVisualPx(marginLeftMm)
        val vRight = mmToVisualPx(marginRightMm)

        // Internal gutters
        val vGutterX = ((vLeft + vRight) / 2f).coerceAtLeast(1.5f * density)
        val vGutterY = ((vTop + vBottom) / 2f).coerceAtLeast(1.5f * density)

        val printableW = (sheetW - (vLeft + vRight)).coerceAtLeast(20f)
        val printableH = (sheetH - (vTop + vBottom)).coerceAtLeast(20f)

        // Draw printable boundary guideline if any margin is > 0
        if (marginTopMm > 0 || marginBottomMm > 0 || marginLeftMm > 0 || marginRightMm > 0) {
            canvas.drawRect(
                sheetLeft + vLeft,
                sheetTop + vTop,
                sheetLeft + sheetW - vRight,
                sheetTop + sheetH - vBottom,
                marginGuidePaint
            )
        }

        val cols = 2
        val rows = 2

        val slotW = (printableW - (cols - 1) * vGutterX) / cols
        val slotH = (printableH - (rows - 1) * vGutterY) / rows

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val sLeft = sheetLeft + vLeft + c * (slotW + vGutterX)
                val sTop = sheetTop + vTop + r * (slotH + vGutterY)
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

    private fun mmToVisualPx(mm: Int): Float {
        return when (mm) {
            0 -> 1.5f * density
            3 -> 6.5f * density
            6 -> 12f * density
            else -> mm * 2f * density
        }
    }

    private val density: Float
        get() = resources.displayMetrics.density
}
