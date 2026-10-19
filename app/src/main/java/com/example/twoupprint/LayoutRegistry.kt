package com.example.twoupprint

import android.content.Context
import androidx.annotation.DrawableRes

/**
 * Represents a single N-up page layout configuration (cols x rows).
 *
 * Two independent orientation axes:
 *   • [landscape] — final output sheet orientation (the physical A4 page).
 *   • [subPageLandscape] — orientation of each *source page* being arranged
 *     on the sheet. Controls both the icon preview aspect ratio per cell and
 *     the aspect ratio expectation for scale-to-fit in the PDF merger.
 */
data class PrintLayout(
    val printerId: String,
    val displayName: String,
    val cols: Int,
    val rows: Int,
    val landscape: Boolean,
    val subPageLandscape: Boolean = false,
    @DrawableRes val iconResId: Int = R.drawable.ic_layout_2x2,
    val isCustom: Boolean = false,
    val isSplit: Boolean = false
) {
    val pagesPerSheet: Int get() = cols * rows
}

/**
 * Catalog and template definitions for built-in and custom X:Y layouts.
 * Delegates persistence operations to [PrintPreferences] for backward-compatibility.
 */
object LayoutRegistry {

    const val PRINTER_ID_SPLIT_PAGES = "split_pages"

    /**
     * Default configurations:
     *   2×1         →  sub-page Portrait,  final sheet Landscape
     *   1×2         →  sub-page Landscape, final sheet Portrait
     *   2×2         →  sub-page Portrait,   final sheet Portrait (Regular PDF optimal grid)
     *   2×3         →  sub-page Landscape, final sheet Portrait
     *   2×4         →  sub-page Landscape, final sheet Portrait
     *   split_pages →  split 1 page into 2 (Left/Right) or more based on user settings
     */
    val builtInLayouts: List<PrintLayout> = listOf(
        PrintLayout("nup_2x1", "2-Up Side by Side (2×1)", 2, 1, landscape = true,  subPageLandscape = false, R.drawable.ic_layout_2x1),
        PrintLayout("nup_1x2", "2-Up Stacked (1×2)",      1, 2, landscape = false, subPageLandscape = true,  R.drawable.ic_layout_1x2),
        PrintLayout("nup_2x2", "4-Up Grid (2×2)",         2, 2, landscape = false, subPageLandscape = false, R.drawable.ic_layout_2x2),
        PrintLayout("nup_2x3", "6-Up Grid (2×3)",         2, 3, landscape = false, subPageLandscape = true,  R.drawable.ic_layout_2x3),
        PrintLayout("nup_2x4", "8-Up Grid (2×4)",         2, 4, landscape = false, subPageLandscape = true,  R.drawable.ic_layout_2x4),
        PrintLayout(PRINTER_ID_SPLIT_PAGES, "Split Pages (1→2 Left/Right)", 2, 1, landscape = false, subPageLandscape = false, R.drawable.ic_layout_split, isCustom = false, isSplit = true)
    )

    /**
     * Infers sensible defaults for a given grid:
     *   cols == rows → Portrait sheet,  Portrait sub-pages (e.g. 2×2, 3×3 square grids on portrait sheet)
     *   cols > rows  → Landscape sheet, Portrait sub-pages (e.g. 2×1, 3×2)
     *   rows > cols  → Portrait sheet,  Landscape sub-pages (e.g. 1×2, 2×3)
     */
    fun inferDefaults(cols: Int, rows: Int): Pair<Boolean, Boolean> {
        return if (cols == rows) {
            Pair(false, false)
        } else if (cols > rows) {
            Pair(true, false)
        } else {
            Pair(false, true)
        }
    }

    /**
     * Infers default sheet orientation for split pages.
     */
    fun inferSplitDefaultLandscape(cols: Int, rows: Int): Boolean {
        return rows > cols
    }

    // --- Backward-compatible delegates to PrintPreferences ---

    fun getAllLayouts(context: Context): List<PrintLayout> = PrintPreferences.getAllLayouts(context)

    fun isLayoutEnabled(context: Context, printerId: String): Boolean = PrintPreferences.isLayoutEnabled(context, printerId)

    fun setLayoutEnabled(context: Context, printerId: String, enabled: Boolean) {
        PrintPreferences.setLayoutEnabled(context, printerId, enabled)
    }

    fun getLayoutOrientation(context: Context, printerId: String, defaultLandscape: Boolean): Boolean =
        PrintPreferences.getLayoutOrientation(context, printerId, defaultLandscape)

    fun setLayoutOrientation(context: Context, printerId: String, landscape: Boolean) {
        PrintPreferences.setLayoutOrientation(context, printerId, landscape)
    }

    fun getSubPageOrientation(context: Context, printerId: String, defaultLandscape: Boolean): Boolean =
        PrintPreferences.getSubPageOrientation(context, printerId, defaultLandscape)

    fun setSubPageOrientation(context: Context, printerId: String, landscape: Boolean) {
        PrintPreferences.setSubPageOrientation(context, printerId, landscape)
    }

    fun isTextContrastEnabled(context: Context): Boolean = PrintPreferences.isTextContrastEnabled(context)

    fun setTextContrastEnabled(context: Context, enabled: Boolean) {
        PrintPreferences.setTextContrastEnabled(context, enabled)
    }

    fun isLinksEnabled(context: Context): Boolean = PrintPreferences.isLinksEnabled(context)

    fun setLinksEnabled(context: Context, enabled: Boolean) {
        PrintPreferences.setLinksEnabled(context, enabled)
    }

    fun isBestFitEnabled(context: Context): Boolean = PrintPreferences.isBestFitEnabled(context)

    fun setBestFitEnabled(context: Context, enabled: Boolean) {
        PrintPreferences.setBestFitEnabled(context, enabled)
    }

    fun isAutoTrimEnabled(context: Context): Boolean = PrintPreferences.isAutoTrimEnabled(context)

    fun setAutoTrimEnabled(context: Context, enabled: Boolean) {
        PrintPreferences.setAutoTrimEnabled(context, enabled)
    }

    fun isNotewiseExportEnabled(context: Context): Boolean = PrintPreferences.isNotewiseExportEnabled(context)

    fun setNotewiseExportEnabled(context: Context, enabled: Boolean) {
        PrintPreferences.setNotewiseExportEnabled(context, enabled)
    }

    fun getSmartBasePaperSize(context: Context): TargetPaperSize = PrintPreferences.getSmartBasePaperSize(context)

    fun setSmartBasePaperSize(context: Context, paperSize: TargetPaperSize) {
        PrintPreferences.setSmartBasePaperSize(context, paperSize)
    }

    fun getSplitCols(context: Context): Int = PrintPreferences.getSplitCols(context)

    fun getSplitRows(context: Context): Int = PrintPreferences.getSplitRows(context)

    fun setSplitGrid(context: Context, cols: Int, rows: Int) {
        PrintPreferences.setSplitGrid(context, cols, rows)
    }

    fun getMarginTopMm(context: Context): Int = PrintPreferences.getMarginTopMm(context)

    fun getMarginBottomMm(context: Context): Int = PrintPreferences.getMarginBottomMm(context)

    fun getMarginLeftMm(context: Context): Int = PrintPreferences.getMarginLeftMm(context)

    fun getMarginRightMm(context: Context): Int = PrintPreferences.getMarginRightMm(context)

    fun setMarginTopMm(context: Context, mm: Int) {
        PrintPreferences.setMarginTopMm(context, mm)
    }

    fun setMarginBottomMm(context: Context, mm: Int) {
        PrintPreferences.setMarginBottomMm(context, mm)
    }

    fun setMarginLeftMm(context: Context, mm: Int) {
        PrintPreferences.setMarginLeftMm(context, mm)
    }

    fun setMarginRightMm(context: Context, mm: Int) {
        PrintPreferences.setMarginRightMm(context, mm)
    }

    fun setMargins(context: Context, top: Int, bottom: Int, left: Int, right: Int) {
        PrintPreferences.setMargins(context, top, bottom, left, right)
    }

    fun getMarginMm(context: Context): Int = PrintPreferences.getMarginMm(context)

    fun setMarginMm(context: Context, marginMm: Int) {
        PrintPreferences.setMarginMm(context, marginMm)
    }

    fun getSpaceDistributionMode(context: Context): SpaceDistributionMode =
        PrintPreferences.getSpaceDistributionMode(context)

    fun setSpaceDistributionMode(context: Context, mode: SpaceDistributionMode) {
        PrintPreferences.setSpaceDistributionMode(context, mode)
    }

    fun getEnabledLayouts(context: Context): List<PrintLayout> = PrintPreferences.getEnabledLayouts(context)

    fun getCustomLayouts(context: Context): List<PrintLayout> = PrintPreferences.getCustomLayouts(context)

    fun addCustomLayout(context: Context, cols: Int, rows: Int, landscape: Boolean, subPageLandscape: Boolean): Boolean =
        PrintPreferences.addCustomLayout(context, cols, rows, landscape, subPageLandscape)

    fun removeCustomLayout(context: Context, printerId: String) {
        PrintPreferences.removeCustomLayout(context, printerId)
    }

    fun findLayoutById(context: Context, printerId: String?): PrintLayout =
        PrintPreferences.findLayoutById(context, printerId)
}
