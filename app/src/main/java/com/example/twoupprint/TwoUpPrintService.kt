package com.example.twoupprint

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.print.PrintAttributes
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TwoUpPrintService : PrintService() {

    override fun onCreate() {
        super.onCreate()
        // Pre-warm PDFBox resource loader and layout preview icons on background worker
        PrintJobHandler.getExecutor().execute {
            try {
                PdfMerger.init(applicationContext)
                LayoutIconGenerator.prewarmIcons(LayoutRegistry.getEnabledLayouts(applicationContext))
            } catch (t: Throwable) {
                android.util.Log.w("TwoUpPrintService", "Background warm-up note: ${t.message}")
            }
        }
    }

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

        // Read media size requested by the user in the system print dialog
        val mediaSize = printJob.info?.attributes?.mediaSize

        // Resolve target paper size:
        // If the user explicitly selected ISO A4 or ISO A5 in the print dialog, honor it.
        // Otherwise ("Smart" or unspecified), use the configured Smart Base Paper Size from app settings.
        val targetPaper = when {
            mediaSize?.id == "ISO_A5" || (mediaSize != null && mediaSize.widthMils == TargetPaperSize.A5.widthMils && mediaSize.heightMils == TargetPaperSize.A5.heightMils) -> TargetPaperSize.A5
            mediaSize?.id == "ISO_A4" || (mediaSize != null && mediaSize.widthMils == TargetPaperSize.A4.widthMils && mediaSize.heightMils == TargetPaperSize.A4.heightMils) -> TargetPaperSize.A4
            else -> LayoutRegistry.getSmartBasePaperSize(applicationContext)
        }

        val isPresentationSmart = (mediaSize?.id == "MEDIA_PRESENTATION_SMART")

        // Final merged sheet orientation is determined strictly by in-app layout configuration (baseLayout.landscape).
        // The print dialog orientation controls how the source application formats raw pages (defaulting to portrait).
        val layout = baseLayout.copy(
            landscape = baseLayout.landscape,
            subPageLandscape = baseLayout.subPageLandscape
        )

        // Determine if text contrast enhancement and clickable links are enabled
        val addTextContrast = LayoutRegistry.isTextContrastEnabled(applicationContext)
        val enableLinks = LayoutRegistry.isLinksEnabled(applicationContext)

        val bestFit = false // Output is always standard A4 page

        val marginTopMm = LayoutRegistry.getMarginTopMm(applicationContext)
        val marginBottomMm = LayoutRegistry.getMarginBottomMm(applicationContext)
        val marginLeftMm = LayoutRegistry.getMarginLeftMm(applicationContext)
        val marginRightMm = LayoutRegistry.getMarginRightMm(applicationContext)
        val spaceDistributionMode = LayoutRegistry.getSpaceDistributionMode(applicationContext)

        val autoTrimEnabled = LayoutRegistry.isAutoTrimEnabled(applicationContext)
        val autoTrimSlideBorders = autoTrimEnabled || isPresentationSmart

        // Extract website / page title from print job metadata if available
        val docName = extractCleanDocumentName(printJob)

        val fileName = if (layout.isSplit) {
            val suffix = "split_${layout.cols}x${layout.rows}"
            if (docName != null) {
                "${docName}_$suffix.pdf"
            } else {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                "${suffix}_$stamp.pdf"
            }
        } else {
            if (docName != null) {
                "${docName}_${layout.cols}x${layout.rows}.pdf"
            } else {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                "${layout.cols}x${layout.rows}_$stamp.pdf"
            }
        }

        // Check if prompt_each_print is enabled
        val isPromptEachPrint = PrintPreferences.isPromptEachPrintEnabled(applicationContext)

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
                marginRightMm,
                autoTrimSlideBorders,
                isPresentationSmart,
                spaceDistributionMode,
                targetPaper
            )
            return
        }

        // Start processing immediately — destination resolution (custom SAF or Downloads)
        // runs safely on the background executor inside PrintJobHandler, never blocking the main thread.
        PrintJobHandler(
            applicationContext, printJob, documentData, destinationUri = null,
            layout, fileName, addTextContrast, enableLinks,
            bestFit,
            marginTopMm, marginBottomMm, marginLeftMm, marginRightMm,
            autoTrimSlideBorders, isPresentationSmart,
            spaceDistributionMode,
            targetPaper
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
