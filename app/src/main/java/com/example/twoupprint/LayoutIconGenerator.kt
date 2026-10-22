package com.example.twoupprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import java.util.concurrent.ConcurrentHashMap

/**
 * Generates crisp, clean grid diagram preview icons for virtual printers.
 *
 * Two independent orientation axes are visualized:
 *   • The outer sheet frame uses A4 aspect ratio (1:√2) in [sheetLandscape] orientation.
 *   • Each sub-page cell inside the grid uses A4 aspect ratio (1:√2) in
 *     [subPageLandscape] orientation, centered within its grid slot.
 *
 * Rendered in solid white lines and page slots on a 100% transparent background.
 * Cached in-memory to prevent redundant allocations and Binder IPC stalls.
 */
object LayoutIconGenerator {

    private const val DEFAULT_ICON_SIZE = 128
    private val iconCache = ConcurrentHashMap<String, Icon>()

    /**
     * Retrieves or generates a cached [Icon] for the given [PrintLayout].
     * Avoids re-drawing bitmaps and allocating memory on every discovery tick.
     */
    fun getCachedIcon(layout: PrintLayout, size: Int = DEFAULT_ICON_SIZE): Icon? {
        val cacheKey = getCacheKey(layout, size)
        val cached = iconCache[cacheKey]
        if (cached != null) return cached

        val bitmap = try {
            generateIconBitmap(layout, size)
        } catch (_: Throwable) {
            null
        } ?: return null

        val icon = try {
            Icon.createWithBitmap(bitmap)
        } catch (_: Throwable) {
            null
        } ?: return null

        iconCache[cacheKey] = icon
        return icon
    }

    /**
     * Generates cache key for a layout and requested dimension.
     */
    fun getCacheKey(layout: PrintLayout, size: Int = DEFAULT_ICON_SIZE): String {
        return "${layout.printerId}_${layout.cols}x${layout.rows}_${layout.landscape}_${layout.subPageLandscape}_${layout.isSplit}_$size"
    }

    /**
     * Resolves layout by [printerId] and retrieves its cached [Icon].
     */
    fun getCachedIcon(context: Context, printerId: String?, size: Int = DEFAULT_ICON_SIZE): Icon? {
        val layout = LayoutRegistry.findLayoutById(context, printerId)
        return getCachedIcon(layout, size)
    }

    /**
     * Allows inserting an icon directly into cache (used for testing or custom bindings).
     */
    fun putCachedIcon(layout: PrintLayout, icon: Icon, size: Int = DEFAULT_ICON_SIZE) {
        iconCache[getCacheKey(layout, size)] = icon
    }

    /**
     * Pre-warms the in-memory icon cache for a collection of layouts.
     */
    fun prewarmIcons(layouts: Collection<PrintLayout>, size: Int = DEFAULT_ICON_SIZE) {
        for (layout in layouts) {
            getCachedIcon(layout, size)
        }
    }

    /**
     * Returns true if an icon for the given layout and size is present in cache.
     */
    fun isIconCached(layout: PrintLayout, size: Int = DEFAULT_ICON_SIZE): Boolean {
        return iconCache.containsKey(getCacheKey(layout, size))
    }

    /**
     * Clears cached icons (useful for tests or memory trim).
     */
    fun clearCache() {
        iconCache.clear()
    }

    /**
     * Overload that pulls orientations from the [PrintLayout] data class.
     */
    fun generateIconBitmap(layout: PrintLayout, size: Int = DEFAULT_ICON_SIZE): Bitmap {
        if (layout.isSplit) {
            return generateSplitIconBitmap(layout.cols, layout.rows, layout.landscape, size)
        }
        return generateIconBitmap(layout.cols, layout.rows, layout.landscape, layout.subPageLandscape, size)
    }

    fun generateSplitIconBitmap(
        cols: Int,
        rows: Int,
        sheetLandscape: Boolean,
        size: Int = 256
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val margin = 16f
        val a4Ratio = 1.414f

        val incomingLandscape = (cols >= rows)
        val sheetRect = computeA4Rect(size.toFloat(), margin, a4Ratio, incomingLandscape)

        val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawRoundRect(sheetRect, 14f, 14f, sheetPaint)

        // Dashed split lines
        val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#D0BCFF")
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 6f), 0f)
        }

        val tileW = sheetRect.width() / cols.toFloat()
        for (c in 1 until cols) {
            val x = sheetRect.left + c * tileW
            canvas.drawLine(x, sheetRect.top + 6f, x, sheetRect.bottom - 6f, dashPaint)
        }

        val tileH = sheetRect.height() / rows.toFloat()
        for (r in 1 until rows) {
            val y = sheetRect.top + r * tileH
            canvas.drawLine(sheetRect.left + 6f, y, sheetRect.right - 6f, y, dashPaint)
        }

        val pageFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#777777")
            strokeCap = Paint.Cap.ROUND
        }

        val subAspect = 1f / a4Ratio
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val tileLeft = sheetRect.left + c * tileW
                val tileTop = sheetRect.top + r * tileH
                val pad = tileW * 0.15f
                val slotW = tileW - pad * 2
                val slotH = tileH - pad * 2
                val (fittedW, fittedH) = fitAspectInSlot(slotW, slotH, subAspect)
                val subLeft = tileLeft + (tileW - fittedW) / 2f
                val subTop = tileTop + (tileH - fittedH) / 2f
                val subRect = RectF(subLeft, subTop, subLeft + fittedW, subTop + fittedH)

                canvas.drawRoundRect(subRect, 4f, 4f, pageFillPaint)

                val numLines = 3
                val linePadX = fittedW * 0.18f
                val lineH = (fittedH * 0.08f).coerceIn(2f, 4f)
                linePaint.strokeWidth = lineH
                val availableY = fittedH * 0.6f
                val lineSpacing = availableY / (numLines + 1)
                for (i in 1..numLines) {
                    val lineY = subTop + fittedH * 0.2f + i * lineSpacing
                    val lineWidthRatio = if (i == numLines) 0.55f else 0.82f
                    canvas.drawLine(
                        subLeft + linePadX,
                        lineY,
                        subLeft + linePadX + (fittedW - linePadX * 2) * lineWidthRatio,
                        lineY,
                        linePaint
                    )
                }
            }
        }

        return bitmap
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

        // Sub-page A4 aspect ratio within each slot (w / h)
        val subAspect = if (subPageLandscape) a4Ratio else (1f / a4Ratio)

        val pageFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#777777")
            strokeCap = Paint.Cap.ROUND
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

                // Draw subtle mini document lines if space permits
                val pPadX = pageW * 0.20f
                val pPadY = pageH * 0.25f
                val availableLineH = pageH - pPadY * 2
                val lineStroke = (pageW * 0.07f).coerceIn(1.5f, 4f)
                linePaint.strokeWidth = lineStroke

                if (availableLineH >= 8f && (pageW - pPadX * 2) >= 8f) {
                    val line1Y = pageTop + pPadY + availableLineH * 0.25f
                    val line2Y = pageTop + pPadY + availableLineH * 0.75f

                    val lx1 = pageLeft + pPadX
                    val lx2Full = pageLeft + pageW - pPadX
                    val lx2Short = pageLeft + pPadX + (pageW - pPadX * 2) * 0.65f

                    canvas.drawLine(lx1, line1Y, lx2Full, line1Y, linePaint)
                    canvas.drawLine(lx1, line2Y, lx2Short, line2Y, linePaint)
                }
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
            targetW = maxW
            targetH = maxW / a4Ratio
        } else {
            targetH = maxH
            targetW = maxH / a4Ratio
        }

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
