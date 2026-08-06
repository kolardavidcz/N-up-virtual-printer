package com.example.twoupprint

import android.content.Context
import androidx.annotation.DrawableRes
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a single N-up page layout configuration (cols x rows).
 */
data class PrintLayout(
    val printerId: String,
    val displayName: String,
    val cols: Int,
    val rows: Int,
    val landscape: Boolean,
    @DrawableRes val iconResId: Int = R.drawable.ic_layout_2x2,
    val isCustom: Boolean = false
) {
    val pagesPerSheet: Int get() = cols * rows
}

/**
 * Manages built-in and user-defined custom X:Y layouts with enable/disable support.
 */
object LayoutRegistry {

    private const val PREFS_NAME = "twoupprint_prefs"
    private const val KEY_CUSTOM_LAYOUTS = "custom_layouts_json"
    private const val KEY_DISABLED_LAYOUT_IDS = "disabled_layout_ids_set"

    val builtInLayouts = listOf(
        PrintLayout("nup_2x1", "2-Up Side by Side (2×1)", 2, 1, landscape = true, R.drawable.ic_layout_2x1),
        PrintLayout("nup_1x2", "2-Up Stacked (1×2)", 1, 2, landscape = false, R.drawable.ic_layout_1x2),
        PrintLayout("nup_2x2", "4-Up Grid (2×2)", 2, 2, landscape = true, R.drawable.ic_layout_2x2),
        PrintLayout("nup_2x3", "6-Up Grid (2×3)", 2, 3, landscape = false, R.drawable.ic_layout_2x3),
        PrintLayout("nup_2x4", "8-Up Grid (2×4)", 2, 4, landscape = false, R.drawable.ic_layout_2x4)
    )

    fun getAllLayouts(context: Context): List<PrintLayout> {
        return builtInLayouts + getCustomLayouts(context)
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
                val landscape = obj.getBoolean("landscape")
                val totalPages = cols * rows
                val id = "custom_${cols}x${rows}_${if (landscape) "l" else "p"}"
                val name = "${totalPages}-Up Custom (${cols}×${rows})"

                val iconRes = when {
                    cols == 2 && rows == 1 -> R.drawable.ic_layout_2x1
                    cols == 1 && rows == 2 -> R.drawable.ic_layout_1x2
                    cols == 2 && rows == 2 -> R.drawable.ic_layout_2x2
                    cols == 2 && rows == 3 -> R.drawable.ic_layout_2x3
                    cols == 2 && rows == 4 -> R.drawable.ic_layout_2x4
                    else -> R.drawable.ic_layout_custom
                }

                list.add(PrintLayout(id, name, cols, rows, landscape, iconRes, isCustom = true))
            }
        } catch (_: Exception) { }

        return list
    }

    fun addCustomLayout(context: Context, cols: Int, rows: Int, landscape: Boolean): Boolean {
        if (cols < 1 || rows < 1 || cols > 10 || rows > 10) return false

        val existing = getCustomLayouts(context).toMutableList()
        if (existing.any { it.cols == cols && it.rows == rows && it.landscape == landscape }) {
            return false
        }

        val jsonArray = JSONArray()
        for (item in existing) {
            jsonArray.put(JSONObject().apply {
                put("cols", item.cols)
                put("rows", item.rows)
                put("landscape", item.landscape)
            })
        }

        jsonArray.put(JSONObject().apply {
            put("cols", cols)
            put("rows", rows)
            put("landscape", landscape)
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
            })
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_LAYOUTS, jsonArray.toString())
            .apply()
    }

    fun findLayoutById(context: Context, printerId: String?): PrintLayout {
        if (printerId == null) return builtInLayouts.first()
        return getAllLayouts(context).find { it.printerId == printerId } ?: builtInLayouts.first()
    }
}
