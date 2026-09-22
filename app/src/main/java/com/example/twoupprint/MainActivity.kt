package com.example.twoupprint

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Native Material 3 MainActivity:
 * - Virtual printer layouts with independent sheet and subpage orientations
 * - Custom X:Y layout creator
 * - "Add contrast for text" toggle (100% selectable text + color images preserved)
 * - Output destination settings & system reliability
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnAddLayout: MaterialButton
    private lateinit var layoutCardsContainer: LinearLayout
    private lateinit var switchTextContrast: MaterialSwitch
    private lateinit var switchEnableLinks: MaterialSwitch
    private lateinit var switchBestFit: MaterialSwitch
    private lateinit var marginPreviewView: MarginPreviewView
    private lateinit var toggleGroupMarginTop: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var toggleGroupMarginBottom: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var toggleGroupMarginLeft: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var toggleGroupMarginRight: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var btnPresetAll0: MaterialButton
    private lateinit var btnPresetAll3: MaterialButton
    private lateinit var btnPresetHeaderSafe: MaterialButton
    private var isUpdatingMarginUI: Boolean = false
    private lateinit var marginDescriptionText: TextView
    private lateinit var pathText: TextView
    private lateinit var btnSetLocation: MaterialButton
    private lateinit var btnResetLocation: MaterialButton
    private lateinit var promptSwitch: MaterialSwitch
    private lateinit var batteryCard: View
    private lateinit var batteryTitle: TextView
    private lateinit var batterySub: TextView
    private lateinit var btnBatteryAction: MaterialButton
    private lateinit var btnOpenSettings: View
    private lateinit var btnClearOldPrinters: View
    private lateinit var btnTroubleshoot: View

    private val directoryPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    getSharedPreferences("twoupprint_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("save_directory_uri", uri.toString())
                        .apply()
                    updateUIState()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnAddLayout = findViewById(R.id.btnAddLayout)
        layoutCardsContainer = findViewById(R.id.layoutCardsContainer)
        switchTextContrast = findViewById(R.id.switchTextContrast)

        pathText = findViewById(R.id.pathText)
        btnSetLocation = findViewById(R.id.btnSetLocation)
        btnResetLocation = findViewById(R.id.btnResetLocation)
        promptSwitch = findViewById(R.id.promptSwitch)
        batteryCard = findViewById(R.id.batteryCard)
        batteryTitle = findViewById(R.id.batteryTitle)
        batterySub = findViewById(R.id.batterySub)
        btnBatteryAction = findViewById(R.id.btnBatteryAction)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnClearOldPrinters = findViewById(R.id.btnClearOldPrinters)
        btnTroubleshoot = findViewById(R.id.btnTroubleshoot)

        btnAddLayout.setOnClickListener {
            showAddCustomLayoutDialog()
        }

        // Text Contrast Switch Listener
        switchTextContrast.isChecked = LayoutRegistry.isTextContrastEnabled(this)
        switchTextContrast.setOnCheckedChangeListener { _, isChecked ->
            LayoutRegistry.setTextContrastEnabled(this, isChecked)
        }

        // Clickable Links Switch Listener
        switchEnableLinks = findViewById(R.id.switchEnableLinks)
        switchEnableLinks.isChecked = LayoutRegistry.isLinksEnabled(this)
        switchEnableLinks.setOnCheckedChangeListener { _, isChecked ->
            LayoutRegistry.setLinksEnabled(this, isChecked)
        }

        // Slide Fit & Margins
        switchBestFit = findViewById(R.id.switchBestFit)
        marginPreviewView = findViewById(R.id.marginPreviewView)
        toggleGroupMarginTop = findViewById(R.id.toggleGroupMarginTop)
        toggleGroupMarginBottom = findViewById(R.id.toggleGroupMarginBottom)
        toggleGroupMarginLeft = findViewById(R.id.toggleGroupMarginLeft)
        toggleGroupMarginRight = findViewById(R.id.toggleGroupMarginRight)
        btnPresetAll0 = findViewById(R.id.btnPresetAll0)
        btnPresetAll3 = findViewById(R.id.btnPresetAll3)
        btnPresetHeaderSafe = findViewById(R.id.btnPresetHeaderSafe)
        marginDescriptionText = findViewById(R.id.marginDescriptionText)

        switchBestFit.isChecked = LayoutRegistry.isBestFitEnabled(this)
        switchBestFit.setOnCheckedChangeListener { _, isChecked ->
            LayoutRegistry.setBestFitEnabled(this, isChecked)
        }

        refreshMarginsUI()

        toggleGroupMarginTop.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isUpdatingMarginUI) {
                val mm = when (checkedId) {
                    R.id.btnTop0 -> 0
                    R.id.btnTop3 -> 3
                    R.id.btnTop6 -> 6
                    else -> 0
                }
                LayoutRegistry.setMarginTopMm(this, mm)
                refreshMarginsUI()
            }
        }

        toggleGroupMarginBottom.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isUpdatingMarginUI) {
                val mm = when (checkedId) {
                    R.id.btnBottom0 -> 0
                    R.id.btnBottom3 -> 3
                    R.id.btnBottom6 -> 6
                    else -> 0
                }
                LayoutRegistry.setMarginBottomMm(this, mm)
                refreshMarginsUI()
            }
        }

        toggleGroupMarginLeft.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isUpdatingMarginUI) {
                val mm = when (checkedId) {
                    R.id.btnLeft0 -> 0
                    R.id.btnLeft3 -> 3
                    R.id.btnLeft6 -> 6
                    else -> 0
                }
                LayoutRegistry.setMarginLeftMm(this, mm)
                refreshMarginsUI()
            }
        }

        toggleGroupMarginRight.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isUpdatingMarginUI) {
                val mm = when (checkedId) {
                    R.id.btnRight0 -> 0
                    R.id.btnRight3 -> 3
                    R.id.btnRight6 -> 6
                    else -> 0
                }
                LayoutRegistry.setMarginRightMm(this, mm)
                refreshMarginsUI()
            }
        }

        btnPresetAll0.setOnClickListener {
            LayoutRegistry.setMargins(this, 0, 0, 0, 0)
            refreshMarginsUI()
        }

        btnPresetAll3.setOnClickListener {
            LayoutRegistry.setMargins(this, 3, 3, 3, 3)
            refreshMarginsUI()
        }

        btnPresetHeaderSafe.setOnClickListener {
            LayoutRegistry.setMargins(this, 6, 0, 0, 0)
            refreshMarginsUI()
        }

        btnSetLocation.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            directoryPicker.launch(intent)
        }

        btnResetLocation.setOnClickListener {
            getSharedPreferences("twoupprint_prefs", Context.MODE_PRIVATE)
                .edit()
                .remove("save_directory_uri")
                .apply()
            updateUIState()
        }

        val prefs = getSharedPreferences("twoupprint_prefs", Context.MODE_PRIVATE)
        promptSwitch.isChecked = prefs.getBoolean("prompt_each_print", false)
        promptSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("prompt_each_print", isChecked).apply()
        }

        batteryCard.setOnClickListener {
            requestBatteryExemption()
        }
        btnBatteryAction.setOnClickListener {
            requestBatteryExemption()
        }

        btnOpenSettings.setOnClickListener {
            openPrintSettings()
        }

        btnClearOldPrinters.setOnClickListener {
            showClearOldPrintersDialog()
        }

        btnTroubleshoot.setOnClickListener {
            openPrintSpoolerInfo()
        }

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    private fun updateUIState() {
        val prefs = getSharedPreferences("twoupprint_prefs", Context.MODE_PRIVATE)
        val savedUriStr = prefs.getString("save_directory_uri", null)

        if (savedUriStr != null) {
            val uri = Uri.parse(savedUriStr)
            val docFile = DocumentFile.fromTreeUri(this, uri)
            pathText.text = docFile?.name ?: uri.lastPathSegment ?: "Custom Folder"
        } else {
            pathText.text = "Downloads/TwoUpPrint/ (default)"
        }

        // Contrast, links, and margin states
        switchTextContrast.isChecked = LayoutRegistry.isTextContrastEnabled(this)
        switchEnableLinks.isChecked = LayoutRegistry.isLinksEnabled(this)
        switchBestFit.isChecked = LayoutRegistry.isBestFitEnabled(this)
        refreshMarginsUI()

        // Battery status
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBatteryExempt = pm.isIgnoringBatteryOptimizations(packageName)

        if (isBatteryExempt) {
            batteryTitle.text = "Battery Optimization Disabled"
            batteryTitle.setTextColor(Color.parseColor("#E6E0E9"))
            batterySub.text = "Background print services remain active at all times"
            btnBatteryAction.text = "Disabled"
            btnBatteryAction.isEnabled = false
        } else {
            batteryTitle.text = "Battery Optimization Active"
            batteryTitle.setTextColor(Color.parseColor("#F2B8B5"))
            batterySub.text = "Background killer may stop print service. Tap to disable"
            btnBatteryAction.text = "Disable"
            btnBatteryAction.isEnabled = true
        }

        renderLayoutCards()
    }

    private fun renderLayoutCards() {
        layoutCardsContainer.removeAllViews()
        val allLayouts = LayoutRegistry.getAllLayouts(this)

        for (layout in allLayouts) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
                setCardBackgroundColor(Color.parseColor("#211F26"))
                radius = 20f * resources.displayMetrics.density
                strokeColor = Color.parseColor("#49454F")
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                cardElevation = 0f
            }

            val cardContent = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 20, 24, 20)
            }

            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(96, 96)
                val bitmap = LayoutIconGenerator.generateIconBitmap(layout)
                setImageBitmap(bitmap)
            }

            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    setMargins(20, 0, 16, 0)
                }
            }

            val titleView = TextView(this).apply {
                text = layout.displayName
                setTextColor(Color.parseColor("#E6E0E9"))
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val sheetStr = if (layout.landscape) "Landscape" else "Portrait"
            val subStr = if (layout.subPageLandscape) "Landscape" else "Portrait"
            val subView = TextView(this).apply {
                text = "${layout.pagesPerSheet} pages/sheet • ${layout.cols}×${layout.rows} • Sheet: $sheetStr • Subpages: $subStr"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 12f
                setPadding(0, 4, 0, 0)
            }

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 0)
            }

            val btnOrientation = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                val isLandscape = layout.landscape
                text = if (isLandscape) "Sheet: Landscape" else "Sheet: Portrait"
                setTextColor(Color.parseColor("#D0BCFF"))
                textSize = 12f
                insetTop = 0
                insetBottom = 0
                setOnClickListener {
                    LayoutRegistry.setLayoutOrientation(this@MainActivity, layout.printerId, !isLandscape)
                    updateUIState()
                }
            }
            actionRow.addView(btnOrientation)

            val btnSubPage = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                val isSubLandscape = layout.subPageLandscape
                text = if (isSubLandscape) "Subpages: Landscape" else "Subpages: Portrait"
                setTextColor(Color.parseColor("#CCC2DC"))
                textSize = 12f
                insetTop = 0
                insetBottom = 0
                setOnClickListener {
                    LayoutRegistry.setSubPageOrientation(this@MainActivity, layout.printerId, !isSubLandscape)
                    updateUIState()
                }
            }
            actionRow.addView(btnSubPage)

            if (layout.isCustom) {
                val btnRemove = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "Remove"
                    setTextColor(Color.parseColor("#F2B8B5"))
                    textSize = 12f
                    insetTop = 0
                    insetBottom = 0
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Remove Custom Layout")
                            .setMessage("Delete ${layout.displayName}?\n\nTip: If this printer still appears in Samsung's menu after removing, clear Print Spooler data under System & Reliability.")
                            .setPositiveButton("Remove & Open Spooler") { _, _ ->
                                LayoutRegistry.removeCustomLayout(this@MainActivity, layout.printerId)
                                updateUIState()
                                openPrintSpoolerInfo()
                            }
                            .setNeutralButton("Remove Only") { _, _ ->
                                LayoutRegistry.removeCustomLayout(this@MainActivity, layout.printerId)
                                updateUIState()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                actionRow.addView(btnRemove)
            }

            textContainer.addView(titleView)
            textContainer.addView(subView)
            textContainer.addView(actionRow)

            val enableSwitch = MaterialSwitch(this).apply {
                isChecked = LayoutRegistry.isLayoutEnabled(this@MainActivity, layout.printerId)
                setOnCheckedChangeListener { _, isChecked ->
                    LayoutRegistry.setLayoutEnabled(this@MainActivity, layout.printerId, isChecked)
                }
            }

            cardContent.addView(iconView)
            cardContent.addView(textContainer)
            cardContent.addView(enableSwitch)

            card.addView(cardContent)
            layoutCardsContainer.addView(card)
        }
    }

    private fun showAddCustomLayoutDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val colsInput = EditText(this).apply {
            hint = "Columns X (e.g. 3)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("3")
        }

        val rowsInput = EditText(this).apply {
            hint = "Rows Y (e.g. 3)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("3")
        }

        val sheetSwitch = MaterialSwitch(this).apply {
            text = "Landscape output sheet"
            setPadding(0, 16, 0, 0)
        }

        val subPageSwitch = MaterialSwitch(this).apply {
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

        container.addView(TextView(this).apply { text = "Columns (X):"; setTextColor(Color.parseColor("#D0BCFF")) })
        container.addView(colsInput)
        container.addView(TextView(this).apply { text = "Rows (Y):"; setTextColor(Color.parseColor("#D0BCFF")); setPadding(0, 16, 0, 0) })
        container.addView(rowsInput)
        container.addView(sheetSwitch)
        container.addView(subPageSwitch)

        AlertDialog.Builder(this)
            .setTitle("Add Custom Layout")
            .setView(container)
            .setPositiveButton("Add Printer") { _, _ ->
                val cols = colsInput.text.toString().toIntOrNull() ?: 2
                val rows = rowsInput.text.toString().toIntOrNull() ?: 2
                val landscape = sheetSwitch.isChecked
                val subLandscape = subPageSwitch.isChecked

                val added = LayoutRegistry.addCustomLayout(this, cols, rows, landscape, subLandscape)
                if (added) {
                    Toast.makeText(this, "Added ${cols}x${rows} printer layout", Toast.LENGTH_SHORT).show()
                    updateUIState()
                } else {
                    Toast.makeText(this, "Layout already exists or invalid values", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearOldPrintersDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Old / Phantom Printers")
            .setMessage("Samsung Print Spooler caches previously created printer options in its app memory.\n\nTo remove phantom printers from Samsung's print menu:\n1. Tap 'Open Storage Settings'\n2. Tap 'Storage'\n3. Tap 'Clear Data' or 'Clear Cache'\n\nThis will instantly wipe deleted printers from Samsung's print list.")
            .setPositiveButton("Open Storage Settings") { _, _ ->
                openPrintSpoolerInfo()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestBatteryExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) { }
        }
    }

    private fun refreshMarginsUI() {
        val top = LayoutRegistry.getMarginTopMm(this)
        val bottom = LayoutRegistry.getMarginBottomMm(this)
        val left = LayoutRegistry.getMarginLeftMm(this)
        val right = LayoutRegistry.getMarginRightMm(this)
        updateMarginSelectionUI(top, bottom, left, right)
    }

    private fun updateMarginSelectionUI(top: Int, bottom: Int, left: Int, right: Int) {
        marginPreviewView.setMargins(top, bottom, left, right)

        isUpdatingMarginUI = true
        try {
            when (top) {
                0 -> toggleGroupMarginTop.check(R.id.btnTop0)
                3 -> toggleGroupMarginTop.check(R.id.btnTop3)
                6 -> toggleGroupMarginTop.check(R.id.btnTop6)
                else -> toggleGroupMarginTop.clearChecked()
            }
            when (bottom) {
                0 -> toggleGroupMarginBottom.check(R.id.btnBottom0)
                3 -> toggleGroupMarginBottom.check(R.id.btnBottom3)
                6 -> toggleGroupMarginBottom.check(R.id.btnBottom6)
                else -> toggleGroupMarginBottom.clearChecked()
            }
            when (left) {
                0 -> toggleGroupMarginLeft.check(R.id.btnLeft0)
                3 -> toggleGroupMarginLeft.check(R.id.btnLeft3)
                6 -> toggleGroupMarginLeft.check(R.id.btnLeft6)
                else -> toggleGroupMarginLeft.clearChecked()
            }
            when (right) {
                0 -> toggleGroupMarginRight.check(R.id.btnRight0)
                3 -> toggleGroupMarginRight.check(R.id.btnRight3)
                6 -> toggleGroupMarginRight.check(R.id.btnRight6)
                else -> toggleGroupMarginRight.clearChecked()
            }
        } finally {
            isUpdatingMarginUI = false
        }

        marginDescriptionText.text = "Top: ${top} mm • Bottom: ${bottom} mm • Left: ${left} mm • Right: ${right} mm"
    }

    private fun openPrintSettings() {
        val intents = arrayOf(
            Intent("com.samsung.settings.PRINT_SETTINGS"),
            Intent(Settings.ACTION_PRINT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) { }
        }
    }

    private fun openPrintSpoolerInfo() {
        val packages = arrayOf("com.android.printspooler", "com.samsung.android.printspooler")
        for (pkg in packages) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                }
                startActivity(intent)
                return
            } catch (_: Exception) { }
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
        } catch (_: Exception) { }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
