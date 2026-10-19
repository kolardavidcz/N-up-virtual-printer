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
    private lateinit var switchNotewiseExport: MaterialSwitch
    private lateinit var toggleGroupSmartPaperSize: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var switchAutoCrop: MaterialSwitch
    private lateinit var marginPreviewView: MarginPreviewView
    private lateinit var toggleGroupMarginTop: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var toggleGroupMarginBottom: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var toggleGroupMarginLeft: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var toggleGroupMarginRight: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var btnPresetAll0: MaterialButton
    private lateinit var btnPresetAll3: MaterialButton
    private lateinit var btnPresetHeaderSafe: MaterialButton
    private lateinit var toggleGroupSpaceDistribution: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var spaceDistributionDescriptionText: TextView
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
            CustomLayoutDialogHelper.showAddCustomLayoutDialog(this) { _, _ ->
                updateUIState()
            }
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

        // Notewise Export Switch Listener
        switchNotewiseExport = findViewById(R.id.switchNotewiseExport)
        switchNotewiseExport.isChecked = LayoutRegistry.isNotewiseExportEnabled(this)
        switchNotewiseExport.setOnCheckedChangeListener { _, isChecked ->
            LayoutRegistry.setNotewiseExportEnabled(this, isChecked)
        }

        // Smart Paper Size Default (A4 vs A5)
        toggleGroupSmartPaperSize = findViewById(R.id.toggleGroupSmartPaperSize)
        val currentSmartPaper = LayoutRegistry.getSmartBasePaperSize(this)
        if (currentSmartPaper == TargetPaperSize.A5) {
            toggleGroupSmartPaperSize.check(R.id.btnPaperA5)
        } else {
            toggleGroupSmartPaperSize.check(R.id.btnPaperA4)
        }

        toggleGroupSmartPaperSize.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val size = if (checkedId == R.id.btnPaperA5) TargetPaperSize.A5 else TargetPaperSize.A4
                LayoutRegistry.setSmartBasePaperSize(this, size)
            }
        }

        // Page & Slot Margins
        marginPreviewView = findViewById(R.id.marginPreviewView)
        toggleGroupMarginTop = findViewById(R.id.toggleGroupMarginTop)
        toggleGroupMarginBottom = findViewById(R.id.toggleGroupMarginBottom)
        toggleGroupMarginLeft = findViewById(R.id.toggleGroupMarginLeft)
        toggleGroupMarginRight = findViewById(R.id.toggleGroupMarginRight)
        btnPresetAll0 = findViewById(R.id.btnPresetAll0)
        btnPresetAll3 = findViewById(R.id.btnPresetAll3)
        btnPresetHeaderSafe = findViewById(R.id.btnPresetHeaderSafe)
        marginDescriptionText = findViewById(R.id.marginDescriptionText)
        toggleGroupSpaceDistribution = findViewById(R.id.toggleGroupSpaceDistribution)
        spaceDistributionDescriptionText = findViewById(R.id.spaceDistributionDescriptionText)

        toggleGroupSpaceDistribution.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isUpdatingMarginUI) {
                val mode = when (checkedId) {
                    R.id.btnSpaceCenter -> SpaceDistributionMode.CENTER
                    R.id.btnSpace2to1 -> SpaceDistributionMode.RATIO_2_1
                    R.id.btnSpaceMaxMiddle -> SpaceDistributionMode.MAX_MIDDLE
                    else -> SpaceDistributionMode.CENTER
                }
                LayoutRegistry.setSpaceDistributionMode(this, mode)
                refreshMarginsUI()
            }
        }

        // Auto-Crop Slide Letterbox Borders Switch Listener
        switchAutoCrop = findViewById(R.id.switchAutoCrop)
        switchAutoCrop.isChecked = LayoutRegistry.isAutoTrimEnabled(this)
        switchAutoCrop.setOnCheckedChangeListener { _, isChecked ->
            LayoutRegistry.setAutoTrimEnabled(this, isChecked)
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
        handleTestIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTestIntent(intent)
    }

    private fun handleTestIntent(intent: Intent?) {
        if (intent == null) return
        val testAction = intent.getStringExtra("test_action") ?: return
        android.util.Log.i("TwoUpPrintTest", "Received test_action: $testAction")

        Thread {
            try {
                PdfMerger.init(applicationContext)

                if (testAction == "convert_pdf" || testAction == "e2e_full") {
                    val inputPath = intent.getStringExtra("input_path")
                        ?: "/sdcard/Download/molekulova.pdf"
                    val inputFile = java.io.File(inputPath)
                    if (!inputFile.exists()) {
                        android.util.Log.e("TwoUpPrintTest", "Input file does not exist: $inputPath")
                        return@Thread
                    }

                    val outDir = java.io.File("/sdcard/Download/TwoUpPrint")
                    if (!outDir.exists()) outDir.mkdirs()

                    // 1. Notewise export test
                    val outNotewiseName = intent.getStringExtra("output_notewise_name") ?: "Molekulova_E2E_v222.notewise"
                    val outNotewise = java.io.File(outDir, outNotewiseName)
                    outNotewise.outputStream().use { outStream ->
                        com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile).use { doc ->
                            com.example.twoupprint.notewise.NotewisePdfConverter.convert(
                                doc, outStream, title = "Molekulova E2E Test"
                            )
                        }
                    }
                    android.util.Log.i("TwoUpPrintTest", "Generated ${outNotewise.name} (${outNotewise.length()} bytes)")

                    // 2. 2x1 N-up PDF merge test (Landscape A4, margins 3mm top+bottom)
                    val outPdfName = intent.getStringExtra("output_pdf_name") ?: "Molekulova_E2E_2x1.pdf"
                    val outPdf = java.io.File(outDir, outPdfName)
                    val layout2x1 = LayoutRegistry.findLayoutById(applicationContext, "nup_2x1")
                    val marginTopMm = LayoutRegistry.getMarginTopMm(applicationContext)
                    val marginBottomMm = LayoutRegistry.getMarginBottomMm(applicationContext)
                    val marginLeftMm = LayoutRegistry.getMarginLeftMm(applicationContext)
                    val marginRightMm = LayoutRegistry.getMarginRightMm(applicationContext)
                    val spaceMode = LayoutRegistry.getSpaceDistributionMode(applicationContext)

                    inputFile.inputStream().use { inStream ->
                        outPdf.outputStream().use { outStream ->
                            PdfMerger.mergeNUp(
                                inStream, outStream, layout2x1,
                                addTextContrast = false,
                                enableLinks = false,
                                bestFit = false,
                                marginTopMm = marginTopMm,
                                marginBottomMm = marginBottomMm,
                                marginLeftMm = marginLeftMm,
                                marginRightMm = marginRightMm,
                                autoTrimSlideBorders = false,
                                isPresentationSmart = false,
                                spaceDistributionMode = spaceMode
                            )
                        }
                    }
                    android.util.Log.i("TwoUpPrintTest", "Generated ${outPdf.name} (${outPdf.length()} bytes)")
                    android.util.Log.i("TwoUpPrintTest", "E2E_TEST_COMPLETED_SUCCESSFULLY")
                }
            } catch (e: Exception) {
                android.util.Log.e("TwoUpPrintTest", "Error in handleTestIntent", e)
            }
        }.start()
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

        // Contrast, links, notewise, and margin states
        switchTextContrast.isChecked = LayoutRegistry.isTextContrastEnabled(this)
        switchEnableLinks.isChecked = LayoutRegistry.isLinksEnabled(this)
        switchNotewiseExport.isChecked = LayoutRegistry.isNotewiseExportEnabled(this)
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

        val callbacks = LayoutCardViewBinder.Callbacks(
            onToggleOrientation = { layout ->
                LayoutRegistry.setLayoutOrientation(this, layout.printerId, !layout.landscape)
                updateUIState()
            },
            onToggleSubPageOrientation = { layout ->
                LayoutRegistry.setSubPageOrientation(this, layout.printerId, !layout.subPageLandscape)
                updateUIState()
            },
            onConfigureSplit = {
                SplitDialogHelper.showSplitSettingsDialog(this) { updateUIState() }
            },
            onRemoveCustomLayout = { layout ->
                showRemoveCustomLayoutDialog(layout)
            },
            onToggleEnabled = { layout, isChecked ->
                LayoutRegistry.setLayoutEnabled(this, layout.printerId, isChecked)
            }
        )

        for (layout in allLayouts) {
            val isEnabled = LayoutRegistry.isLayoutEnabled(this, layout.printerId)
            val cardView = LayoutCardViewBinder.createCardView(this, layout, isEnabled, callbacks)
            layoutCardsContainer.addView(cardView)
        }
    }

    private fun showRemoveCustomLayoutDialog(layout: PrintLayout) {
        AlertDialog.Builder(this)
            .setTitle("Remove Custom Layout")
            .setMessage("Delete ${layout.displayName}?\n\nTip: If this printer still appears in Samsung's menu after removing, clear Print Spooler data under System & Reliability.")
            .setPositiveButton("Remove & Open Spooler") { _, _ ->
                LayoutRegistry.removeCustomLayout(this, layout.printerId)
                updateUIState()
                openPrintSpoolerInfo()
            }
            .setNeutralButton("Remove Only") { _, _ ->
                LayoutRegistry.removeCustomLayout(this, layout.printerId)
                updateUIState()
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
        val mode = LayoutRegistry.getSpaceDistributionMode(this)
        updateMarginSelectionUI(top, bottom, left, right, mode)
    }

    private fun updateMarginSelectionUI(top: Int, bottom: Int, left: Int, right: Int, mode: SpaceDistributionMode) {
        marginPreviewView.setMargins(top, bottom, left, right)
        marginPreviewView.setSpaceDistributionMode(mode)

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
            when (mode) {
                SpaceDistributionMode.CENTER -> toggleGroupSpaceDistribution.check(R.id.btnSpaceCenter)
                SpaceDistributionMode.RATIO_2_1 -> toggleGroupSpaceDistribution.check(R.id.btnSpace2to1)
                SpaceDistributionMode.MAX_MIDDLE -> toggleGroupSpaceDistribution.check(R.id.btnSpaceMaxMiddle)
            }
        } finally {
            isUpdatingMarginUI = false
        }

        marginDescriptionText.text = "Top: ${top} mm • Bottom: ${bottom} mm • Left: ${left} mm • Right: ${right} mm"

        spaceDistributionDescriptionText.text = when (mode) {
            SpaceDistributionMode.CENTER -> "Center: Balanced 1:1 distribution across margins and middle"
            SpaceDistributionMode.RATIO_2_1 -> "2:1 Middle: Extra space prioritized 2:1 into center gutter"
            SpaceDistributionMode.MAX_MIDDLE -> "Max Middle: Slides flush to outer margins • 100% spare space in middle"
        }
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
