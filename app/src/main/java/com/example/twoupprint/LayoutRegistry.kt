package com.example.twoupprint

import android.content.Context
import androidx.annotation.DrawableRes
import org.json.JSONArray
import org.json.JSONObject

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
 * editable default sheet orientation, and sub-page orientation support.
 */
object LayoutRegistry {

    private const val PREFS_NAME = "twoupprint_prefs"
    private const val KEY_CUSTOM_LAYOUTS = "custom_layouts_json"
    private const val KEY_DISABLED_LAYOUT_IDS = "disabled_layout_ids_set"

    /**
     * Default configurations:
     *   2×1  →  sub-page Portrait,  final sheet Landscape
     *   1×2  →  sub-page Landscape, final sheet Portrait
     *   2×2  →  sub-page Landscape, final sheet Portrait
     *   2×3  →  sub-page Landscape, final sheet Portrait
     *   2×4  →  sub-page Landscape, final sheet Portrait
     */
    val builtInLayouts = listOf(
        PrintLayout("nup_2x1", "2-Up Side by Side (2×1)", 2, 1, landscape = true,  subPageLandscape = false, R.drawable.ic_layout_2x1),
        PrintLayout("nup_1x2", "2-Up Stacked (1×2)",      1, 2, landscape = false, subPageLandscape = true,  R.drawable.ic_layout_1x2),
        PrintLayout("nup_2x2", "4-Up Grid (2×2)",         2, 2, landscape = false, subPageLandscape = true,  R.drawable.ic_layout_2x2),
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
