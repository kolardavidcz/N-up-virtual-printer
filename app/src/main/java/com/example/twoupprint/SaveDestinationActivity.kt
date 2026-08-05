package com.example.twoupprint

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Clean translucent trampoline Activity triggered by high-priority print prompt notification.
 * Opens system Storage Access Framework picker (ACTION_CREATE_DOCUMENT) to choose output file destination.
 */
class SaveDestinationActivity : ComponentActivity() {

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
            PendingPrintJobManager.processDestination(this, uri)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultTitle = intent.getStringExtra("file_name")
            ?: PendingPrintJobManager.getPendingFileName()

        val safIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, defaultTitle)
        }

        createDocumentLauncher.launch(safIntent)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        PendingPrintJobManager.processDestination(this, null)
    }
}
