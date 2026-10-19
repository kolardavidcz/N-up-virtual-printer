package com.example.twoupprint

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages persistence and user preferences for N-Up Print layouts and settings
 * via [SharedPreferences].
 */
object PrintPreferences {

    const val PREFS_NAME = "twoupprint_prefs"

    private const val KEY_CUSTOM_LAYOUTS = "custom_layouts_json"
    private const val KEY_DISABLED_LAYOUT_IDS = "disabled_layout_ids_set"
    private const val KEY_ADD_TEXT_CONTRAST = "add_text_contrast"
    private const val KEY_ENABLE_CLICKABLE_LINKS = "enable_clickable_links"
    private const val KEY_ENABLE_BEST_FIT = "enable_best_fit"
    private const val KEY_ENABLE_AUTO_TRIM_LETTERBOX = "enable_auto_trim_letterbox"
    private const val KEY_SPACE_DISTRIBUTION_MODE = "space_distribution_mode"
    private const val KEY_NOTEWISE_EXPORT_ENABLED = "notewise_export_enabled"
    private const val KEY_SMART_BASE_PAPER_SIZE = "smart_base_paper_size"
    private const val KEY_SPLIT_COLS = "split_cols"
    private const val KEY_SPLIT_ROWS = "split_rows"
    private const val KEY_SAVE_DIRECTORY_URI = "save_directory_uri"
    private const val KEY_PROMPT_EACH_PRINT = "prompt_each_print"

    private const val KEY_MARGIN_TOP_MM = "margin_top_mm"
    private const val KEY_MARGIN_BOTTOM_MM = "margin_bottom_mm"
    private const val KEY_MARGIN_LEFT_MM = "margin_left_mm"
    private const val KEY_MARGIN_RIGHT_MM = "margin_right_mm"
    private const val KEY_LEGACY_MARGIN_MM = "page_margin_mm"

    private const val KEY_PREFS_SCHEMA_VERSION = "prefs_schema_version"
    private const val CURRENT_SCHEMA_VERSION = 2

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun checkSchemaMigration(prefs: SharedPreferences) {
        if (prefs.getInt(KEY_PREFS_SCHEMA_VERSION, 0) < CURRENT_SCHEMA_VERSION) {
            prefs.edit()
                .remove("orientation_nup_2x2")
                .remove("subpage_orientation_nup_2x2")
                .putInt(KEY_PREFS_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                .apply()
        }
    }

    fun getAllLayouts(context: Context): List<PrintLayout> {
        val prefs = getPrefs(context)
        checkSchemaMigration(prefs)

        val rawList = LayoutRegistry.builtInLayouts + getCustomLayouts(context)
        return rawList.map { layout ->
            if (layout.printerId == LayoutRegistry.PRINTER_ID_SPLIT_PAGES) {
                val splitCols = getSplitCols(context)
                val splitRows = getSplitRows(context)
                val defaultLandscape = LayoutRegistry.inferSplitDefaultLandscape(splitCols, splitRows)
                val effectiveLandscape = getLayoutOrientation(context, layout.printerId, defaultLandscape)
                val count = splitCols * splitRows
                val desc = when {
                    splitCols == 2 && splitRows == 1 -> "1→2 Left/Right"
                    splitCols == 1 && splitRows == 2 -> "1→2 Top/Bottom"
                    splitCols == 2 && splitRows == 2 -> "1→4 Grid"
                    else -> "1→$count (${splitCols}×${splitRows})"
                }
                layout.copy(
                    cols = splitCols,
                    rows = splitRows,
                    displayName = "Split Pages ($desc)",
                    landscape = effectiveLandscape,
                    subPageLandscape = false
                )
            } else {
                val effectiveLandscape = getLayoutOrientation(context, layout.printerId, layout.landscape)
                val effectiveSubLandscape = getSubPageOrientation(context, layout.printerId, layout.subPageLandscape)
                layout.copy(landscape = effectiveLandscape, subPageLandscape = effectiveSubLandscape)
            }
        }
    }

    fun isLayoutEnabled(context: Context, printerId: String): Boolean {
        val prefs = getPrefs(context)
        val disabledSet = prefs.getStringSet(KEY_DISABLED_LAYOUT_IDS, emptySet()) ?: emptySet()
        return !disabledSet.contains(printerId)
    }

    fun setLayoutEnabled(context: Context, printerId: String, enabled: Boolean) {
        val prefs = getPrefs(context)
        val disabledSet = prefs.getStringSet(KEY_DISABLED_LAYOUT_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) {
            disabledSet.remove(printerId)
        } else {
            disabledSet.add(printerId)
        }
        prefs.edit().putStringSet(KEY_DISABLED_LAYOUT_IDS, disabledSet).apply()
    }

    fun getLayoutOrientation(context: Context, printerId: String, defaultLandscape: Boolean): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean("orientation_$printerId", defaultLandscape)
    }

    fun setLayoutOrientation(context: Context, printerId: String, landscape: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean("orientation_$printerId", landscape).apply()
    }

    fun getSubPageOrientation(context: Context, printerId: String, defaultLandscape: Boolean): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean("subpage_orientation_$printerId", defaultLandscape)
    }

    fun setSubPageOrientation(context: Context, printerId: String, landscape: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean("subpage_orientation_$printerId", landscape).apply()
    }

    // --- "Add contrast for text" setting ---

    fun isTextContrastEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_ADD_TEXT_CONTRAST, false)
    }

    fun setTextContrastEnabled(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(KEY_ADD_TEXT_CONTRAST, enabled).apply()
    }

    // --- "Make URLs and links clickable" setting ---

    fun isLinksEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_ENABLE_CLICKABLE_LINKS, false)
    }

    fun setLinksEnabled(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(KEY_ENABLE_CLICKABLE_LINKS, enabled).apply()
    }

    // --- "Adaptive Best Fit for presentations" setting ---

    fun isBestFitEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_ENABLE_BEST_FIT, true)
    }

    fun setBestFitEnabled(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(KEY_ENABLE_BEST_FIT, enabled).apply()
    }

    // --- "Auto-crop slide letterbox borders" setting ---

    fun isAutoTrimEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_ENABLE_AUTO_TRIM_LETTERBOX, false)
    }

    fun setAutoTrimEnabled(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(KEY_ENABLE_AUTO_TRIM_LETTERBOX, enabled).apply()
    }

    // --- "Export .notewise notebook alongside PDF" setting ---

    fun isNotewiseExportEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_NOTEWISE_EXPORT_ENABLED, false)
    }

    fun setNotewiseExportEnabled(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(KEY_NOTEWISE_EXPORT_ENABLED, enabled).apply()
    }

    // --- "Smart base paper size" setting (A4 vs A5) ---

    fun getSmartBasePaperSize(context: Context): TargetPaperSize {
        val prefs = getPrefs(context)
        val id = prefs.getString(KEY_SMART_BASE_PAPER_SIZE, TargetPaperSize.A4.id)
        return TargetPaperSize.fromId(id)
    }

    fun setSmartBasePaperSize(context: Context, paperSize: TargetPaperSize) {
        val prefs = getPrefs(context)
        prefs.edit().putString(KEY_SMART_BASE_PAPER_SIZE, paperSize.id).apply()
    }

    // --- "Split Pages" setting (cols × rows) ---

    fun getSplitCols(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_SPLIT_COLS, 2)
    }

    fun getSplitRows(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_SPLIT_ROWS, 1)
    }

    fun setSplitGrid(context: Context, cols: Int, rows: Int) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putInt(KEY_SPLIT_COLS, cols.coerceIn(1, 10))
            .putInt(KEY_SPLIT_ROWS, rows.coerceIn(1, 10))
            .apply()
    }

    // --- "Page & Slot Margins" setting (Top, Bottom, Left, Right) ---

    fun getMarginTopMm(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_MARGIN_TOP_MM, prefs.getInt(KEY_LEGACY_MARGIN_MM, 0))
    }

    fun getMarginBottomMm(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_MARGIN_BOTTOM_MM, prefs.getInt(KEY_LEGACY_MARGIN_MM, 0))
    }

    fun getMarginLeftMm(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_MARGIN_LEFT_MM, prefs.getInt(KEY_LEGACY_MARGIN_MM, 0))
    }

    fun getMarginRightMm(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_MARGIN_RIGHT_MM, prefs.getInt(KEY_LEGACY_MARGIN_MM, 0))
    }

    fun setMarginTopMm(context: Context, mm: Int) {
        val prefs = getPrefs(context)
        prefs.edit().putInt(KEY_MARGIN_TOP_MM, mm).apply()
    }

    fun setMarginBottomMm(context: Context, mm: Int) {
        val prefs = getPrefs(context)
        prefs.edit().putInt(KEY_MARGIN_BOTTOM_MM, mm).apply()
    }

    fun setMarginLeftMm(context: Context, mm: Int) {
        val prefs = getPrefs(context)
        prefs.edit().putInt(KEY_MARGIN_LEFT_MM, mm).apply()
    }

    fun setMarginRightMm(context: Context, mm: Int) {
        val prefs = getPrefs(context)
        prefs.edit().putInt(KEY_MARGIN_RIGHT_MM, mm).apply()
    }

    fun setMargins(context: Context, top: Int, bottom: Int, left: Int, right: Int) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putInt(KEY_MARGIN_TOP_MM, top)
            .putInt(KEY_MARGIN_BOTTOM_MM, bottom)
            .putInt(KEY_MARGIN_LEFT_MM, left)
            .putInt(KEY_MARGIN_RIGHT_MM, right)
            .apply()
    }

    fun getMarginMm(context: Context): Int {
        return getMarginTopMm(context)
    }

    fun setMarginMm(context: Context, marginMm: Int) {
        setMargins(context, marginMm, marginMm, marginMm, marginMm)
    }

    // --- "Extra Space Distribution" setting ---

    fun getSpaceDistributionMode(context: Context): SpaceDistributionMode {
        val prefs = getPrefs(context)
        val id = prefs.getString(KEY_SPACE_DISTRIBUTION_MODE, SpaceDistributionMode.CENTER.id)
        return SpaceDistributionMode.fromId(id)
    }

    fun setSpaceDistributionMode(context: Context, mode: SpaceDistributionMode) {
        val prefs = getPrefs(context)
        prefs.edit().putString(KEY_SPACE_DISTRIBUTION_MODE, mode.id).apply()
    }

    // --- Save directory URI and Prompt-each-print settings ---

    fun getSaveDirectoryUri(context: Context): String? {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_SAVE_DIRECTORY_URI, null)
    }

    fun setSaveDirectoryUri(context: Context, uri: String) {
        val prefs = getPrefs(context)
        prefs.edit().putString(KEY_SAVE_DIRECTORY_URI, uri).apply()
    }

    fun clearSaveDirectoryUri(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit().remove(KEY_SAVE_DIRECTORY_URI).apply()
    }

    fun isPromptEachPrintEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_PROMPT_EACH_PRINT, false)
    }

    fun setPromptEachPrintEnabled(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(KEY_PROMPT_EACH_PRINT, enabled).apply()
    }

    // --- Enabled layouts & custom layout catalog ---

    fun getEnabledLayouts(context: Context): List<PrintLayout> {
        return getAllLayouts(context).filter { isLayoutEnabled(context, it.printerId) }
    }

    private fun resolveLayoutIcon(cols: Int, rows: Int): Int {
        return when {
            cols == 2 && rows == 1 -> R.drawable.ic_layout_2x1
            cols == 1 && rows == 2 -> R.drawable.ic_layout_1x2
            cols == 2 && rows == 2 -> R.drawable.ic_layout_2x2
            cols == 2 && rows == 3 -> R.drawable.ic_layout_2x3
            cols == 2 && rows == 4 -> R.drawable.ic_layout_2x4
            else -> R.drawable.ic_layout_custom
        }
    }

    private fun serializeCustomLayouts(layouts: List<PrintLayout>): String {
        return layouts.joinToString(prefix = "[", postfix = "]") { layout ->
            """{"cols":${layout.cols},"rows":${layout.rows},"landscape":${layout.landscape},"subPageLandscape":${layout.subPageLandscape}}"""
        }
    }

    fun parseCustomLayoutsJson(jsonStr: String): List<PrintLayout> {
        val list = mutableListOf<PrintLayout>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val cols = obj.getInt("cols")
                val rows = obj.getInt("rows")
                val (inferredLandscape, inferredSubLandscape) = LayoutRegistry.inferDefaults(cols, rows)
                val defaultLandscape = obj.optBoolean("landscape", inferredLandscape)
                val subPageLandscape = obj.optBoolean("subPageLandscape", inferredSubLandscape)
                val totalPages = cols * rows
                val id = "custom_${cols}x${rows}"
                val name = "${totalPages}-Up Custom (${cols}×${rows})"
                list.add(PrintLayout(id, name, cols, rows, defaultLandscape, subPageLandscape, resolveLayoutIcon(cols, rows), isCustom = true))
            }
        } catch (_: Exception) { }

        // JVM unit test fallback when android.jar stubs return empty for JSONArray
        if (list.isEmpty() && jsonStr.contains("cols")) {
            val regex = Regex("""\{\s*"cols"\s*:\s*(\d+)\s*,\s*"rows"\s*:\s*(\d+)(?:\s*,\s*"landscape"\s*:\s*(true|false))?(?:\s*,\s*"subPageLandscape"\s*:\s*(true|false))?[^}]*\}""")
            for (match in regex.findAll(jsonStr)) {
                val cols = match.groupValues[1].toInt()
                val rows = match.groupValues[2].toInt()
                val (inferredLandscape, inferredSubLandscape) = LayoutRegistry.inferDefaults(cols, rows)
                val defaultLandscape = match.groupValues.getOrNull(3)?.toBooleanStrictOrNull() ?: inferredLandscape
                val subPageLandscape = match.groupValues.getOrNull(4)?.toBooleanStrictOrNull() ?: inferredSubLandscape
                val totalPages = cols * rows
                val id = "custom_${cols}x${rows}"
                val name = "${totalPages}-Up Custom (${cols}×${rows})"
                list.add(PrintLayout(id, name, cols, rows, defaultLandscape, subPageLandscape, resolveLayoutIcon(cols, rows), isCustom = true))
            }
        }

        return list
    }

    fun getCustomLayouts(context: Context): List<PrintLayout> {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_CUSTOM_LAYOUTS, null) ?: return emptyList()
        return parseCustomLayoutsJson(jsonStr)
    }

    fun addCustomLayout(context: Context, cols: Int, rows: Int, landscape: Boolean, subPageLandscape: Boolean): Boolean {
        if (cols < 1 || rows < 1 || cols > 10 || rows > 10 || (cols * rows < 2)) return false

        // Disallow adding configurations that already exist as built-in layouts
        if (LayoutRegistry.builtInLayouts.any { !it.isSplit && it.cols == cols && it.rows == rows }) {
            return false
        }

        val existing = getCustomLayouts(context).toMutableList()
        if (existing.any { it.cols == cols && it.rows == rows }) {
            return false
        }

        val totalPages = cols * rows
        val newLayout = PrintLayout(
            printerId = "custom_${cols}x${rows}",
            displayName = "${totalPages}-Up Custom (${cols}×${rows})",
            cols = cols,
            rows = rows,
            landscape = landscape,
            subPageLandscape = subPageLandscape,
            iconResId = resolveLayoutIcon(cols, rows),
            isCustom = true
        )
        existing.add(newLayout)

        getPrefs(context)
            .edit()
            .putString(KEY_CUSTOM_LAYOUTS, serializeCustomLayouts(existing))
            .apply()

        return true
    }

    fun removeCustomLayout(context: Context, printerId: String) {
        val existing = getCustomLayouts(context).filterNot { it.printerId == printerId }
        val prefs = getPrefs(context)
        val disabledSet = prefs.getStringSet(KEY_DISABLED_LAYOUT_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        disabledSet.remove(printerId)

        prefs.edit()
            .putString(KEY_CUSTOM_LAYOUTS, serializeCustomLayouts(existing))
            .remove("orientation_$printerId")
            .remove("subpage_orientation_$printerId")
            .putStringSet(KEY_DISABLED_LAYOUT_IDS, disabledSet)
            .apply()
    }

    fun findLayoutById(context: Context, printerId: String?): PrintLayout {
        if (printerId == null) return LayoutRegistry.builtInLayouts.first()
        return getAllLayouts(context).find { it.printerId == printerId } ?: LayoutRegistry.builtInLayouts.first()
    }
}
