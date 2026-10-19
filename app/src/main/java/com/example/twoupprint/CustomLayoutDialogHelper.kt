package com.example.twoupprint

import android.content.Context
import android.graphics.Color
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Dialog builder helper for configuring and creating custom X:Y grid layouts.
 */
object CustomLayoutDialogHelper {

    /**
     * Shows a modal dialog allowing the user to specify custom rows, columns,
     * and preferred sheet/subpage orientations.
     */
    fun showAddCustomLayoutDialog(
        context: Context,
        onLayoutAdded: (cols: Int, rows: Int) -> Unit
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val colsInput = EditText(context).apply {
            hint = "Columns X (e.g. 3)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("3")
        }

        val rowsInput = EditText(context).apply {
            hint = "Rows Y (e.g. 3)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("3")
        }

        val sheetSwitch = MaterialSwitch(context).apply {
            text = "Landscape output sheet"
            setPadding(0, 16, 0, 0)
        }

        val subPageSwitch = MaterialSwitch(context).apply {
            text = "Landscape sub-pages"
            setPadding(0, 8, 0, 0)
        }

        fun updateSuitableOrientation() {
            val cols = colsInput.text.toString().toIntOrNull() ?: 3
            val rows = rowsInput.text.toString().toIntOrNull() ?: 3
            val (sheetLandscape, subLandscape) = LayoutRegistry.inferDefaults(cols, rows)
            sheetSwitch.isChecked = sheetLandscape
            subPageSwitch.isChecked = subLandscape
        }

        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSuitableOrientation()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }

        colsInput.addTextChangedListener(textWatcher)
        rowsInput.addTextChangedListener(textWatcher)
        updateSuitableOrientation()

        container.addView(TextView(context).apply {
            text = "Columns (X):"
            setTextColor(Color.parseColor("#D0BCFF"))
        })
        container.addView(colsInput)
        container.addView(TextView(context).apply {
            text = "Rows (Y):"
            setTextColor(Color.parseColor("#D0BCFF"))
            setPadding(0, 16, 0, 0)
        })
        container.addView(rowsInput)
        container.addView(sheetSwitch)
        container.addView(subPageSwitch)

        AlertDialog.Builder(context)
            .setTitle("Add Custom Layout")
            .setView(container)
            .setPositiveButton("Add Printer") { _, _ ->
                val cols = colsInput.text.toString().toIntOrNull() ?: 2
                val rows = rowsInput.text.toString().toIntOrNull() ?: 2
                val landscape = sheetSwitch.isChecked
                val subLandscape = subPageSwitch.isChecked

                val added = LayoutRegistry.addCustomLayout(context, cols, rows, landscape, subLandscape)
                if (added) {
                    Toast.makeText(context, "Added ${cols}x${rows} printer layout", Toast.LENGTH_SHORT).show()
                    onLayoutAdded(cols, rows)
                } else {
                    Toast.makeText(context, "Layout already exists or invalid values", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
