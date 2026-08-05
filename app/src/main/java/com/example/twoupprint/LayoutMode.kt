package com.example.twoupprint

import android.print.PrintAttributes
import androidx.annotation.DrawableRes

/**
 * Defines the available N-up layout modes.
 * Each mode appears as a separate virtual printer in the system print dialog.
 */
enum class LayoutMode(
    val printerId: String,
    val displayName: String,
    val cols: Int,
    val rows: Int,
    val landscape: Boolean,
    @DrawableRes val iconResId: Int
) {
    /** 2 portrait pages side-by-side on a landscape sheet */
    SIDE_BY_SIDE_2x1(
        printerId = "nup_2x1",
        displayName = "2-Up ▸ Side by Side (2×1)",
        cols = 2, rows = 1,
        landscape = true,
        iconResId = R.drawable.ic_layout_2x1
    ),

    /** 2 portrait pages stacked vertically on a portrait sheet */
    STACKED_1x2(
        printerId = "nup_1x2",
        displayName = "2-Up ▸ Stacked (1×2)",
        cols = 1, rows = 2,
        landscape = false,
        iconResId = R.drawable.ic_layout_1x2
    ),

    /** 4 pages in a 2×2 grid on a landscape sheet */
    GRID_2x2(
        printerId = "nup_2x2",
        displayName = "4-Up ▸ Grid (2×2)",
        cols = 2, rows = 2,
        landscape = true,
        iconResId = R.drawable.ic_layout_2x2
    ),

    /** 6 pages in a 2×3 grid on a portrait sheet */
    GRID_2x3(
        printerId = "nup_2x3",
        displayName = "6-Up ▸ Grid (2×3)",
        cols = 2, rows = 3,
        landscape = false,
        iconResId = R.drawable.ic_layout_2x3
    ),

    /** 8 pages in a 2×4 grid on a portrait sheet */
    GRID_2x4(
        printerId = "nup_2x4",
        displayName = "8-Up ▸ Grid (2×4)",
        cols = 2, rows = 4,
        landscape = false,
        iconResId = R.drawable.ic_layout_2x4
    );

    /** Number of source pages that fit on one output sheet */
    val pagesPerSheet: Int get() = cols * rows

    /** The default media size to advertise for this layout */
    val defaultMediaSize: PrintAttributes.MediaSize
        get() = PrintAttributes.MediaSize.ISO_A4

    companion object {
        fun fromPrinterId(localId: String): LayoutMode? =
            entries.find { it.printerId == localId }
    }
}

