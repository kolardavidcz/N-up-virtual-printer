package com.example.twoupprint

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Modernized view binder responsible for generating clean Material 3 layout configuration cards.
 */
object LayoutCardViewBinder {

    data class Callbacks(
        val onToggleOrientation: (PrintLayout) -> Unit,
        val onToggleSubPageOrientation: (PrintLayout) -> Unit,
        val onConfigureSplit: (PrintLayout) -> Unit,
        val onRemoveCustomLayout: (PrintLayout) -> Unit,
        val onToggleEnabled: (PrintLayout, Boolean) -> Unit
    )

    fun createCardView(
        context: Context,
        layout: PrintLayout,
        isEnabled: Boolean,
        callbacks: Callbacks
    ): View {
        val density = context.resources.displayMetrics.density

        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (6 * density).toInt())
            }
            setCardBackgroundColor(Color.parseColor("#211F26"))
            radius = 12f * density
            strokeColor = Color.parseColor("#49454F")
            strokeWidth = (1 * density).toInt()
            cardElevation = 0f
        }

        val cardContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (14 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        val iconPx = (44 * density).toInt()
        val iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
            val bitmap = LayoutIconGenerator.generateIconBitmap(layout)
            setImageBitmap(bitmap)
        }

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins((12 * density).toInt(), 0, (8 * density).toInt(), 0)
            }
        }

        val titleView = TextView(context).apply {
            text = layout.displayName
            setTextColor(Color.parseColor("#E6E0E9"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }

        val sheetStr = if (layout.landscape) "Landscape" else "Portrait"
        val subStr = if (layout.subPageLandscape) "Landscape" else "Portrait"
        val subView = TextView(context).apply {
            text = if (layout.isSplit) {
                "Split 1 page → ${layout.cols * layout.rows} pages (${layout.cols}×${layout.rows}) • Output: $sheetStr"
            } else {
                "${layout.pagesPerSheet} pages/sheet • ${layout.cols}×${layout.rows} • Sheet: $sheetStr • Subpages: $subStr"
            }
            setTextColor(Color.parseColor("#CAC4D0"))
            textSize = 11f
            setPadding(0, 1, 0, 0)
        }

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2, 0, 0)
        }

        val btnPadH = (6 * density).toInt()
        val btnPadV = (2 * density).toInt()

        val btnOrientation = createBorderlessButton(context, btnPadH, btnPadV).apply {
            val isLandscape = layout.landscape
            val prefix = if (layout.isSplit) "Output: " else "Sheet: "
            text = if (isLandscape) "${prefix}Landscape" else "${prefix}Portrait"
            setTextColor(Color.parseColor("#D0BCFF"))
            setOnClickListener {
                callbacks.onToggleOrientation(layout)
            }
        }
        actionRow.addView(btnOrientation)

        if (layout.isSplit) {
            val btnSplitDivision = createBorderlessButton(context, btnPadH, btnPadV).apply {
                val count = layout.cols * layout.rows
                text = "Split: ${layout.cols}×${layout.rows} ($count pages)"
                setTextColor(Color.parseColor("#CCC2DC"))
                setOnClickListener {
                    callbacks.onConfigureSplit(layout)
                }
            }
            actionRow.addView(btnSplitDivision)
        } else {
            val btnSubPage = createBorderlessButton(context, btnPadH, btnPadV).apply {
                val isSubLandscape = layout.subPageLandscape
                text = if (isSubLandscape) "Subpages: Landscape" else "Subpages: Portrait"
                setTextColor(Color.parseColor("#CCC2DC"))
                setOnClickListener {
                    callbacks.onToggleSubPageOrientation(layout)
                }
            }
            actionRow.addView(btnSubPage)
        }

        if (layout.isCustom) {
            val btnRemove = createBorderlessButton(context, btnPadH, btnPadV).apply {
                text = "Remove"
                setTextColor(Color.parseColor("#F2B8B5"))
                setOnClickListener {
                    callbacks.onRemoveCustomLayout(layout)
                }
            }
            actionRow.addView(btnRemove)
        }

        textContainer.addView(titleView)
        textContainer.addView(subView)
        textContainer.addView(actionRow)

        val enableSwitch = MaterialSwitch(context).apply {
            isChecked = isEnabled
            setOnCheckedChangeListener { _, checked ->
                callbacks.onToggleEnabled(layout, checked)
            }
        }

        cardContent.addView(iconView)
        cardContent.addView(textContainer)
        cardContent.addView(enableSwitch)

        card.addView(cardContent)
        return card
    }

    private fun createBorderlessButton(context: Context, padH: Int, padV: Int): MaterialButton {
        return MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            setPadding(padH, padV, padH, padV)
        }
    }
}
