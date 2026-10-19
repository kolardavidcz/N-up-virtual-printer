package com.example.twoupprint

/**
 * Standard paper sizes supported for N-up merged sheet rendering and spooler capabilities.
 *
 * Dimensions:
 * - ISO A4: 210 x 297 mm = 595.27563 pt x 841.8898 pt = 8268 x 11693 mils
 * - ISO A5: 148 x 210 mm = 419.52756 pt x 595.27563 pt = 5827 x 8268 mils
 */
enum class TargetPaperSize(
    val id: String,
    val displayName: String,
    val widthPt: Float,
    val heightPt: Float,
    val widthMils: Int,
    val heightMils: Int
) {
    A4("A4", "A4 (Standard)", 595.27563f, 841.8898f, 8268, 11693),
    A5("A5", "A5 (Compact)", 419.52756f, 595.27563f, 5827, 8268);

    companion object {
        fun fromId(id: String?): TargetPaperSize {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: A4
        }
    }
}
