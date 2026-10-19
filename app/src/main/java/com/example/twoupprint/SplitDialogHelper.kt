package com.example.twoupprint

import android.content.Context
import android.graphics.Color
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Dialog builder helper for configuring Reverse N-Up (Split Pages) divisions.
 */
object SplitDialogHelper {

    /**
     * Shows single choice selection for standard split presets or opens custom split dialog.
     */
    fun showSplitSettingsDialog(
        context: Context,
        onSplitUpdated: () -> Unit
    ) {
        val options = arrayOf(
            "1 → 2 Pages (Side-by-Side: Left / Right) [2×1]",
            "1 → 2 Pages (Stacked: Top / Bottom) [1×2]",
            "1 → 4 Pages (4 Quadrants) [2×2]",
            "Custom Division..."
        )
        val currentCols = LayoutRegistry.getSplitCols(context)
        val currentRows = LayoutRegistry.getSplitRows(context)
        val checkedItem = when {
            currentCols == 2 && currentRows == 1 -> 0
            currentCols == 1 && currentRows == 2 -> 1
            currentCols == 2 && currentRows == 2 -> 2
            else -> 3
        }

        AlertDialog.Builder(context)
            .setTitle("Split Pages Division")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                when (which) {
                    0 -> {
                        LayoutRegistry.setSplitGrid(context, 2, 1)
                        onSplitUpdated()
                        dialog.dismiss()
                    }
                    1 -> {
                        LayoutRegistry.setSplitGrid(context, 1, 2)
                        onSplitUpdated()
                        dialog.dismiss()
                    }
                    2 -> {
                        LayoutRegistry.setSplitGrid(context, 2, 2)
                        onSplitUpdated()
                        dialog.dismiss()
                    }
                    3 -> {
                        dialog.dismiss()
                        showCustomSplitDialog(context, onSplitUpdated)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows modal dialog for entering arbitrary columns and rows (1..10) for page splitting.
     */
    fun showCustomSplitDialog(
        context: Context,
        onSplitUpdated: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, (10 * density).toInt(), pad, (10 * density).toInt())
        }

        val colsInput = EditText(context).apply {
            hint = "Columns X (e.g. 2)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(LayoutRegistry.getSplitCols(context).toString())
        }

        val rowsInput = EditText(context).apply {
            hint = "Rows Y (e.g. 1)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(LayoutRegistry.getSplitRows(context).toString())
        }

        container.addView(TextView(context).apply {
            text = "Columns (Horizontal slices):"
            setTextColor(Color.parseColor("#D0BCFF"))
        })
        container.addView(colsInput)
        container.addView(TextView(context).apply {
            text = "Rows (Vertical slices):"
            setTextColor(Color.parseColor("#D0BCFF"))
            setPadding(0, (12 * density).toInt(), 0, 0)
        })
        container.addView(rowsInput)

        AlertDialog.Builder(context)
            .setTitle("Custom Page Split")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val cols = colsInput.text.toString().toIntOrNull() ?: 2
                val rows = rowsInput.text.toString().toIntOrNull() ?: 1
                if (cols in 1..10 && rows in 1..10 && (cols * rows) >= 2) {
                    LayoutRegistry.setSplitGrid(context, cols, rows)
                    onSplitUpdated()
                    Toast.makeText(context, "Page split set to ${cols}×${rows} (${cols * rows} pages)", Toast.LENGTH_SHORT).show()
                } else if (cols in 1..10 && rows in 1..10) {
                    Toast.makeText(context, "Page split division must produce at least 2 sub-pages (e.g. 2×1)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Columns and rows must be between 1 and 10", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
