package com.example.twoupprint

import android.content.Context
import androidx.annotation.DrawableRes
import org.json.JSONArray
import org.json.JSONObject

/**
 * Color processing mode for output documents.
 */
enum class ColorProcessingMode(val displayName: String, val description: String) {
    COLOR("Color (Original)", "Full color original output with vector preservation"),
    SMART_HIGH_CONTRAST("Smart High-Contrast", "Boosts faint text & math formulas to solid black (100% selectable text) while preserving colorful images"),
    GRAYSCALE("Grayscale (8-bit)", "Smooth photographic 256 shades of gray (0-255)"),
    PURE_BLACK_WHITE("Pure Black & White (1-bit)", "Strictly 0 and 1 (#000000 and #FFFFFF) with zero gray ink/toner")
}

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
    val isCustom: Boolean = false
) {
    val pagesPerSheet: Int get() = cols * rows
}

/**
 * Manages built-in and user-defined custom X:Y layouts with enable/disable,
 * editable default sheet orientation, sub-page orientation, and text contrast settings.
 */
object LayoutRegistry {

    private const val PREFS_NAME = "twoupprint_prefs"
    private const val KEY_CUSTOM_LAYOUTS = "custom_layouts_json"
    private const val KEY_DISABLED_LAYOUT_IDS = "disabled_layout_ids_set"
    private const val KEY_ADD_TEXT_CONTRAST = "add_text_contrast"
    private const val KEY_COLOR_PROCESSING_MODE = "global_color_processing_mode"
    private const val KEY_BW_ALGORITHM = "global_bw_algorithm"

    /**
     * Default configurations:
     *   2×1  →  sub-page Portrait,  final sheet Landscape
     *   1×2  →  sub-page Landscape, final sheet Portrait
     *   2×2  →  sub-page Landscape, final sheet Landscape
     *   2×3  →  sub-page Landscape, final sheet Portrait
     *   2×4  →  sub-page Landscape, final sheet Portrait
     */
    val builtInLayouts = listOf(
        PrintLayout("nup_2x1", "2-Up Side by Side (2×1)", 2, 1, landscape = true,  subPageLandscape = false, R.drawable.ic_layout_2x1),
        PrintLayout("nup_1x2", "2-Up Stacked (1×2)",      1, 2, landscape = false, subPageLandscape = true,  R.drawable.ic_layout_1x2),
        PrintLayout("nup_2x2", "4-Up Grid (2×2)",         2, 2, landscape = true,  subPageLandscape = true,  R.drawable.ic_layout_2x2),
        PrintLayout("nup_2x3", "6-Up Grid (2×3)",         2, 3, landscape = false, subPageLandscape = true,  R.drawable.ic_layout_2x3),
        PrintLayout("nup_2x4", "8-Up Grid (2×4)",         2, 4, landscape = false, subPageLandscape = true,  R.drawable.ic_layout_2x4)
    )

    fun getAllLayouts(context: Context): List<PrintLayout> {
        val rawList = builtInLayouts + getCustomLayouts(context)
        return rawList.map { layout ->
            val effectiveLandscape = getLayoutOrientation(context, layout.printerId, layout.landscape)
            val effectiveSubLandscape = getSubPageOrientation(context, layout.printerId, layout.subPageLandscape)
            layout.copy(landscape = effectiveLandscape, subPageLandscape = effectiveSubLandscape)
        }
    }

    fun isLayoutEnabled(context: Context, printerId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val disabledSet = prefs.getStringSet(KEY_DISABLED_LAYOUT_IDS, emptySet()) ?: emptySet()
        return !disabledSet.contains(printerId)
    }

    fun setLayoutEnabled(context: Context, printerId: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val disabledSet = prefs.getStringSet(KEY_DISABLED_LAYOUT_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) {
            disabledSet.remove(printerId)
        } else {
            disabledSet.add(printerId)
        }
        prefs.edit().putStringSet(KEY_DISABLED_LAYOUT_IDS, disabledSet).apply()
    }

    fun getLayoutOrientation(context: Context, printerId: String, defaultLandscape: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("orientation_$printerId", defaultLandscape)
    }

    fun setLayoutOrientation(context: Context, printerId: String, landscape: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("orientation_$printerId", landscape).apply()
    }

    fun getSubPageOrientation(context: Context, printerId: String, defaultLandscape: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("subpage_orientation_$printerId", defaultLandscape)
    }

    fun setSubPageOrientation(context: Context, printerId: String, landscape: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("subpage_orientation_$printerId", landscape).apply()
    }

    // --- "Add contrast for text" setting ---

    fun isTextContrastEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ADD_TEXT_CONTRAST, false) // default false
    }

    fun setTextContrastEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ADD_TEXT_CONTRAST, enabled).apply()
    }

    // --- "Make URLs and links clickable" setting ---

    fun isLinksEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("enable_clickable_links", false) // default false
    }

    fun setLinksEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enable_clickable_links", enabled).apply()
    }

    // --- "Adaptive Best Fit for presentations" setting ---

    fun isBestFitEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("enable_best_fit", true) // default true for optimal presentation fit
    }

    fun setBestFitEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enable_best_fit", enabled).apply()
    }

    // --- "Page & Slot Margins" setting (Top, Bottom, Left, Right) ---

    fun getMarginTopMm(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("margin_top_mm", prefs.getInt("page_margin_mm", 0))
    }

    fun getMarginBottomMm(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("margin_bottom_mm", prefs.getInt("page_margin_mm", 0))
    }

    fun getMarginLeftMm(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("margin_left_mm", prefs.getInt("page_margin_mm", 0))
    }

    fun getMarginRightMm(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("margin_right_mm", prefs.getInt("page_margin_mm", 0))
    }

    fun setMarginTopMm(context: Context, mm: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("margin_top_mm", mm).apply()
    }

    fun setMarginBottomMm(context: Context, mm: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("margin_bottom_mm", mm).apply()
    }

    fun setMarginLeftMm(context: Context, mm: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("margin_left_mm", mm).apply()
    }

    fun setMarginRightMm(context: Context, mm: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("margin_right_mm", mm).apply()
    }

    fun setMargins(context: Context, top: Int, bottom: Int, left: Int, right: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("margin_top_mm", top)
            .putInt("margin_bottom_mm", bottom)
            .putInt("margin_left_mm", left)
            .putInt("margin_right_mm", right)
            .apply()
    }

    fun getMarginMm(context: Context): Int {
        return getMarginTopMm(context)
    }

    fun setMarginMm(context: Context, marginMm: Int) {
        setMargins(context, marginMm, marginMm, marginMm, marginMm)
    }

    fun getColorMode(context: Context): ColorProcessingMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_COLOR_PROCESSING_MODE, ColorProcessingMode.COLOR.name)
        return try {
            ColorProcessingMode.valueOf(name ?: ColorProcessingMode.COLOR.name)
        } catch (_: Exception) {
            ColorProcessingMode.COLOR
        }
    }

    fun setColorMode(context: Context, mode: ColorProcessingMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COLOR_PROCESSING_MODE, mode.name).apply()
    }

    fun getBwAlgorithm(context: Context): BwBinarizer.BwAlgorithm {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_BW_ALGORITHM, BwBinarizer.BwAlgorithm.TEXT_BOOSTER.name)
        return try {
            BwBinarizer.BwAlgorithm.valueOf(name ?: BwBinarizer.BwAlgorithm.TEXT_BOOSTER.name)
        } catch (_: Exception) {
            BwBinarizer.BwAlgorithm.TEXT_BOOSTER
        }
    }

    fun setBwAlgorithm(context: Context, algorithm: BwBinarizer.BwAlgorithm) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BW_ALGORITHM, algorithm.name).apply()
    }

    fun getEnabledLayouts(context: Context): List<PrintLayout> {
        return getAllLayouts(context).filter { isLayoutEnabled(context, it.printerId) }
    }

    fun getCustomLayouts(context: Context): List<PrintLayout> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CUSTOM_LAYOUTS, null) ?: return emptyList()

        val list = mutableListOf<PrintLayout>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val cols = obj.getInt("cols")
                val rows = obj.getInt("rows")
                val defaultLandscape = obj.optBoolean("landscape", cols >= rows)
                val subPageLandscape = obj.optBoolean("subPageLandscape", cols < rows)
                val totalPages = cols * rows
                val id = "custom_${cols}x${rows}"
                val name = "${totalPages}-Up Custom (${cols}×${rows})"

                val iconRes = when {
                    cols == 2 && rows == 1 -> R.drawable.ic_layout_2x1
                    cols == 1 && rows == 2 -> R.drawable.ic_layout_1x2
                    cols == 2 && rows == 2 -> R.drawable.ic_layout_2x2
                    cols == 2 && rows == 3 -> R.drawable.ic_layout_2x3
                    cols == 2 && rows == 4 -> R.drawable.ic_layout_2x4
                    else -> R.drawable.ic_layout_custom
                }

                list.add(PrintLayout(id, name, cols, rows, defaultLandscape, subPageLandscape, iconRes, isCustom = true))
            }
        } catch (_: Exception) { }

        return list
    }

    fun addCustomLayout(context: Context, cols: Int, rows: Int, landscape: Boolean, subPageLandscape: Boolean): Boolean {
        if (cols < 1 || rows < 1 || cols > 10 || rows > 10) return false

        val existing = getCustomLayouts(context).toMutableList()
        if (existing.any { it.cols == cols && it.rows == rows }) {
            return false
        }

        val jsonArray = JSONArray()
        for (item in existing) {
            jsonArray.put(JSONObject().apply {
                put("cols", item.cols)
                put("rows", item.rows)
                put("landscape", item.landscape)
                put("subPageLandscape", item.subPageLandscape)
            })
        }

        jsonArray.put(JSONObject().apply {
            put("cols", cols)
            put("rows", rows)
            put("landscape", landscape)
            put("subPageLandscape", subPageLandscape)
        })

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_LAYOUTS, jsonArray.toString())
            .apply()

        return true
    }

    fun removeCustomLayout(context: Context, printerId: String) {
        val existing = getCustomLayouts(context).filterNot { it.printerId == printerId }
        val jsonArray = JSONArray()
        for (item in existing) {
            jsonArray.put(JSONObject().apply {
                put("cols", item.cols)
                put("rows", item.rows)
                put("landscape", item.landscape)
                put("subPageLandscape", item.subPageLandscape)
            })
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_LAYOUTS, jsonArray.toString())
            .apply()
    }

    /**
     * Infers sensible defaults for a given grid:
     *   cols >= rows → Landscape sheet, Portrait sub-pages (e.g. 2×1, 3×2)
     *   rows > cols  → Portrait sheet,  Landscape sub-pages (e.g. 1×2, 2×3)
     */
    fun inferDefaults(cols: Int, rows: Int): Pair<Boolean, Boolean> {
        val sheetLandscape = cols >= rows
        val subLandscape = !sheetLandscape
        return Pair(sheetLandscape, subLandscape)
    }

    fun findLayoutById(context: Context, printerId: String?): PrintLayout {
        if (printerId == null) return builtInLayouts.first()
        return getAllLayouts(context).find { it.printerId == printerId } ?: builtInLayouts.first()
    }
}
