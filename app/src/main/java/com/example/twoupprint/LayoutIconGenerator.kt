package com.example.twoupprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Generates crisp, clean grid diagram preview icons for virtual printers.
 *
 * Two independent orientation axes are visualized:
 *   • The outer sheet frame uses A4 aspect ratio (1:√2) in [sheetLandscape] orientation.
 *   • Each sub-page cell inside the grid uses A4 aspect ratio (1:√2) in
 *     [subPageLandscape] orientation, centered within its grid slot.
 *
 * Rendered in solid white lines and page slots on a 100% transparent background.
 */
object LayoutIconGenerator {

    /**
     * Overload that pulls sub-page orientation from the [PrintLayout] data class.
     */
    fun generateIconBitmap(layout: PrintLayout, size: Int = 256): Bitmap {
        return generateIconBitmap(layout.cols, layout.rows, layout.landscape, layout.subPageLandscape, size)
    }

    fun generateIconBitmap(
        cols: Int,
        rows: Int,
        sheetLandscape: Boolean,
        subPageLandscape: Boolean = !sheetLandscape,
        size: Int = 256
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val margin = 16f
        val a4Ratio = 1.414f  // √2 ≈ 1.414

        // --- Outer sheet frame (A4 proportions) ---
        val sheetRect = computeA4Rect(size.toFloat(), margin, a4Ratio, sheetLandscape)

        val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawRoundRect(sheetRect, 14f, 14f, sheetPaint)

        // --- Grid layout ---
        val innerPadding = (sheetRect.width() * 0.08f).coerceAtLeast(10f)
        val availableGridW = sheetRect.width() - (innerPadding * 2)
        val availableGridH = sheetRect.height() - (innerPadding * 2)

        val gap = (availableGridW / (cols * 5f)).coerceIn(3f, 10f)

        // Each grid slot (the area available for one sub-page)
        val slotW = (availableGridW - (gap * (cols - 1))) / cols
        val slotH = (availableGridH - (gap * (rows - 1))) / rows

        // Sub-page A4 aspect ratio within each slot
        val subAspect = if (subPageLandscape) a4Ratio else (1f / a4Ratio)  // w/h ratio

        val pageFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val slotLeft = sheetRect.left + innerPadding + c * (slotW + gap)
                val slotTop = sheetRect.top + innerPadding + r * (slotH + gap)

                // Fit the A4-proportioned sub-page inside the slot, centered
                val (pageW, pageH) = fitAspectInSlot(slotW, slotH, subAspect)

                val pageLeft = slotLeft + (slotW - pageW) / 2f
                val pageTop = slotTop + (slotH - pageH) / 2f

                val cornerRadius = (pageW * 0.10f).coerceIn(2f, 8f)
                val pageRect = RectF(pageLeft, pageTop, pageLeft + pageW, pageTop + pageH)
                canvas.drawRoundRect(pageRect, cornerRadius, cornerRadius, pageFillPaint)
            }
        }

        return bitmap
    }

    /**
     * Computes a centered A4-proportioned rectangle within a square canvas.
     */
    private fun computeA4Rect(canvasSize: Float, margin: Float, a4Ratio: Float, landscape: Boolean): RectF {
        val maxW = canvasSize - margin * 2
        val maxH = canvasSize - margin * 2

        val targetW: Float
        val targetH: Float

        if (landscape) {
            // Landscape: width > height
            targetW = maxW
            targetH = maxW / a4Ratio
        } else {
            // Portrait: height > width
            targetH = maxH
            targetW = maxH / a4Ratio
        }

        // Scale down if it exceeds bounds
        val scale = minOf(maxW / targetW, maxH / targetH, 1f)
        val finalW = targetW * scale
        val finalH = targetH * scale

        val left = (canvasSize - finalW) / 2f
        val top = (canvasSize - finalH) / 2f
        return RectF(left, top, left + finalW, top + finalH)
    }

    /**
     * Fits a rectangle with the given aspect ratio (w/h) inside a slot,
     * returning the fitted width and height.
     */
    private fun fitAspectInSlot(slotW: Float, slotH: Float, aspect: Float): Pair<Float, Float> {
        val fitByWidth = Pair(slotW, slotW / aspect)
        val fitByHeight = Pair(slotH * aspect, slotH)

        return if (fitByWidth.second <= slotH) {
            fitByWidth
        } else {
            fitByHeight
        }
    }
}
