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
 * Native Material 3 MainActivity with spacious UI hierarchy:
 * - Independent layout printer enable/disable toggles
 * - Dynamic custom X:Y layout creator
 * - Zero emojis, spacious layout structure, and clean typography
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnAddLayout: MaterialButton
    private lateinit var layoutCardsContainer: LinearLayout
    private lateinit var pathText: TextView
    private lateinit var btnSetLocation: MaterialButton
    private lateinit var btnResetLocation: MaterialButton
    private lateinit var promptSwitch: MaterialSwitch
    private lateinit var batteryCard: View
    private lateinit var batteryTitle: TextView
    private lateinit var batterySub: TextView
    private lateinit var btnBatteryAction: MaterialButton
    private lateinit var btnOpenSettings: View
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
        pathText = findViewById(R.id.pathText)
        btnSetLocation = findViewById(R.id.btnSetLocation)
        btnResetLocation = findViewById(R.id.btnResetLocation)
        promptSwitch = findViewById(R.id.promptSwitch)
        batteryCard = findViewById(R.id.batteryCard)
        batteryTitle = findViewById(R.id.batteryTitle)
        batterySub = findViewById(R.id.batterySub)
        btnBatteryAction = findViewById(R.id.btnBatteryAction)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnTroubleshoot = findViewById(R.id.btnTroubleshoot)

        btnAddLayout.setOnClickListener {
            showAddCustomLayoutDialog()
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
                val bitmap = LayoutIconGenerator.generateIconBitmap(layout.cols, layout.rows, layout.landscape)
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

            val orientationStr = if (layout.landscape) "Landscape" else "Portrait"
            val subView = TextView(this).apply {
                text = "${layout.pagesPerSheet} pages per sheet • ${layout.cols}×${layout.rows}"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 13f
                setPadding(0, 4, 0, 0)
            }

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 0)
            }

            val btnOrientation = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                val isLandscape = layout.landscape
                text = if (isLandscape) "Default: Landscape" else "Default: Portrait"
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
                            .setMessage("Delete ${layout.displayName}?")
                            .setPositiveButton("Remove") { _, _ ->
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

        val orientationSwitch = MaterialSwitch(this).apply {
            text = "Landscape sheet orientation"
            setPadding(0, 16, 0, 0)
        }

        fun updateSuitableOrientation() {
            val cols = colsInput.text.toString().toIntOrNull() ?: 3
            val rows = rowsInput.text.toString().toIntOrNull() ?: 3
            // Cols >= Rows -> Landscape is more suitable; Rows > Cols -> Portrait is more suitable
            orientationSwitch.isChecked = (cols >= rows)
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
        container.addView(orientationSwitch)

        AlertDialog.Builder(this)
            .setTitle("Add Custom Layout")
            .setView(container)
            .setPositiveButton("Add Printer") { _, _ ->
                val cols = colsInput.text.toString().toIntOrNull() ?: 2
                val rows = rowsInput.text.toString().toIntOrNull() ?: 2
                val landscape = orientationSwitch.isChecked

                val added = LayoutRegistry.addCustomLayout(this, cols, rows, landscape)
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
