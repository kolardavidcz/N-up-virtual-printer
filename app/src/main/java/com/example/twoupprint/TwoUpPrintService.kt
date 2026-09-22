package com.example.twoupprint

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TwoUpPrintService : PrintService() {

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return TwoUpDiscoverySession(this)
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        if (printJob.isQueued || printJob.isStarted) {
            printJob.cancel()
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        if (printJob.isQueued) {
            printJob.start()
        }

        val documentData = printJob.document.data
        if (documentData == null) {
            printJob.fail("No document data provided")
            return
        }

        val localId = printJob.info.printerId?.localId
        val baseLayout = LayoutRegistry.findLayoutById(applicationContext, localId)

        // Read orientation requested by the user in the system print dialog (subpages orientation / Seet_v1 protocol)
        val mediaSize = printJob.info?.attributes?.mediaSize
        val userSelectedSubPageLandscape = if (mediaSize != null) !mediaSize.isPortrait else baseLayout.subPageLandscape

        // Layout keeps configured sheet orientation (Sheet_v2 internal processing)
        val layout = baseLayout.copy(subPageLandscape = userSelectedSubPageLandscape)

        // Determine if text contrast enhancement and clickable links are enabled
        val addTextContrast = LayoutRegistry.isTextContrastEnabled(applicationContext)
        val enableLinks = LayoutRegistry.isLinksEnabled(applicationContext)

        // Determine Best Fit and Margin settings
        val isExplicitMatchSize = mediaSize?.id == "MEDIA_MATCH_SIZE"
        val bestFit = isExplicitMatchSize || LayoutRegistry.isBestFitEnabled(applicationContext)

        val marginTopMm = LayoutRegistry.getMarginTopMm(applicationContext)
        val marginBottomMm = LayoutRegistry.getMarginBottomMm(applicationContext)
        val marginLeftMm = LayoutRegistry.getMarginLeftMm(applicationContext)
        val marginRightMm = LayoutRegistry.getMarginRightMm(applicationContext)

        // Extract website / page title from print job metadata if available
        val docName = extractCleanDocumentName(printJob)

        val fileName = if (docName != null) {
            "${docName}_${layout.cols}x${layout.rows}.pdf"
        } else {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            "${layout.cols}x${layout.rows}_$stamp.pdf"
        }

        // Check if prompt_each_print is enabled
        val prefs = getSharedPreferences("twoupprint_prefs", Context.MODE_PRIVATE)
        val isPromptEachPrint = prefs.getBoolean("prompt_each_print", false)

        if (isPromptEachPrint) {
            PendingPrintJobManager.enqueuePromptJob(
                applicationContext,
                printJob,
                documentData,
                layout,
                fileName,
                addTextContrast,
                enableLinks,
                bestFit,
                marginTopMm,
                marginBottomMm,
                marginLeftMm,
                marginRightMm
            )
            return
        }

        // Check if the user has set a preferred save directory in the app settings
        val savedDirUriStr = prefs.getString("save_directory_uri", null)

        var destinationUri: Uri? = null
        if (savedDirUriStr != null) {
            try {
                val dirUri = Uri.parse(savedDirUriStr)
                val docUri = androidx.documentfile.provider.DocumentFile
                    .fromTreeUri(applicationContext, dirUri)
                    ?.createFile("application/pdf", fileName)
                    ?.uri

                destinationUri = docUri
            } catch (_: Exception) {
                // Fall through to default Downloads save
            }
        }

        // Start processing — saves to chosen directory or Downloads/TwoUpPrint/ fallback
        PrintJobHandler(
            applicationContext, printJob, documentData, destinationUri,
            layout, fileName, addTextContrast, enableLinks,
            bestFit,
            marginTopMm, marginBottomMm, marginLeftMm, marginRightMm
        ).start()
    }

    private fun extractCleanDocumentName(printJob: PrintJob): String? {
        val rawName = printJob.info?.label
            ?.takeIf { it.isNotBlank() }
            ?: printJob.document?.info?.name?.takeIf { it.isNotBlank() }
            ?: return null

        // 1. Strip trailing .pdf extension
        var cleaned = rawName.replace(Regex("(?i)\\.pdf$"), "")

        // 2. Replace illegal filename characters with space
        cleaned = cleaned.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ")

        // 3. Normalize whitespace
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()

        // 4. Truncate if excessively long (max 60 chars)
        if (cleaned.length > 60) {
            cleaned = cleaned.substring(0, 60).trim()
        }

        // Filter out generic names
        val lower = cleaned.lowercase(Locale.US)
        if (cleaned.isEmpty() || lower == "print" || lower == "document" || lower == "pdf") {
            return null
        }

        return cleaned
    }
}
