package com.example.twoupprint

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Merges source PDF pages N-up into a true vector output PDF.
 *
 * Supports arbitrary grid layouts (cols × rows) with independent
 * sheet orientation (landscape / portrait), subpages orientation,
 * and text contrast boosting (100% selectable text + colorful images preserved).
 */
object PdfMerger {

    private var initialized = false

    fun init(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    /**
     * Merges pages from [sourcePdfStream] into an N-up layout defined by [layout].
     * When [addTextContrast] is true, boosts faint gray text, low-opacity formulas,
     * and pencil notes to solid black while preserving selectable text and color images.
     */
    fun mergeNUp(
        sourcePdfStream: InputStream,
        outputStream: OutputStream,
        layout: PrintLayout = LayoutRegistry.builtInLayouts.first(),
        addTextContrast: Boolean = false,
        enableLinks: Boolean = false,
        bestFit: Boolean = true,
        marginTopMm: Int = 0,
        marginBottomMm: Int = 0,
        marginLeftMm: Int = 0,
        marginRightMm: Int = 0,
        autoTrimSlideBorders: Boolean = false,
        isPresentationSmart: Boolean = false,
        spaceDistributionMode: SpaceDistributionMode = SpaceDistributionMode.CENTER,
        targetPaper: TargetPaperSize = TargetPaperSize.A4,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        val srcDoc = PDDocument.load(sourcePdfStream)
        val outDoc = PDDocument()
        val layerUtility = LayerUtility(outDoc)

        try {
            val pageCount = srcDoc.numberOfPages
            if (pageCount == 0) return

            // If auto-crop is enabled, detect & trim letterbox margins if slides are present (never force on regular docs)
            val detectedSlideRatio = if (autoTrimSlideBorders || isPresentationSmart) {
                PdfContentTrimmer.trimDocumentSlides(srcDoc, forceSlideMode = false)
            } else {
                null
            }
            val isSlideMode = (detectedSlideRatio != null)

            // If text contrast enhancement is enabled, boost text & formulas directly in vector stream
            if (addTextContrast) {
                for (i in 0 until pageCount) {
                    val page = srcDoc.getPage(i)
                    VectorTextBooster.boostPage(srcDoc, page, preserveImages = true)
                }
            }

            val cols = layout.cols
            val rows = layout.rows

            // Margins in PDF points (72 points per inch, 25.4 mm per inch)
            val mmToPt = 72f / 25.4f
            val marginTopPt = marginTopMm * mmToPt
            val marginBottomPt = marginBottomMm * mmToPt
            val marginLeftPt = marginLeftMm * mmToPt
            val marginRightPt = marginRightMm * mmToPt

            val hasMargins = (marginTopMm > 0 || marginBottomMm > 0 || marginLeftMm > 0 || marginRightMm > 0)

            val baseGutterX = if (hasMargins) ((marginLeftPt + marginRightPt) / 2f).coerceAtLeast(0f) else 0f
            val gutterX = if (hasMargins) (baseGutterX * spaceDistributionMode.gutterMultiplier).coerceAtLeast(0f) else 0f
            val gutterY = if (hasMargins) ((marginTopPt + marginBottomPt) / 2f).coerceAtLeast(0f) else 0f

            // Sheet dimensions for output (adaptive Best Fit or fixed TargetPaperSize)
            val sheetRect = calculateSheetRectangle(
                srcDoc, layout, bestFit,
                marginTopPt, marginBottomPt, marginLeftPt, marginRightPt,
                gutterX, gutterY,
                targetPaper
            )
            val sheetW = sheetRect.width
            val sheetH = sheetRect.height

            val printableW = Math.max(10f, sheetW - marginLeftPt - marginRightPt)
            val printableH = Math.max(10f, sheetH - marginTopPt - marginBottomPt)

            val slotW = (printableW - (cols - 1) * gutterX) / cols.toFloat()
            val slotH = (printableH - (rows - 1) * gutterY) / rows.toFloat()

            var pageIdx = 0

            while (pageIdx < pageCount) {
                val outPage = PDPage(sheetRect)
                outDoc.addPage(outPage)

                val contentStream = PDPageContentStream(
                    outDoc,
                    outPage,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                )

                // Fill slots left-to-right, top-to-bottom
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        if (pageIdx >= pageCount) break

                        // PDF origin is bottom-left, so row 0 (top) has the highest Y
                        val slotLeft = marginLeftPt + col * (slotW + gutterX)
                        val slotBottom = marginBottomPt + (rows - 1 - row) * (slotH + gutterY)

                        drawVectorPageInSlot(
                            srcDoc, layerUtility, contentStream, pageIdx,
                            slotLeft = slotLeft, slotBottom = slotBottom,
                            slotWidth = slotW, slotHeight = slotH,
                            layout = layout,
                            col = col,
                            cols = cols,
                            spaceDistributionMode = spaceDistributionMode,
                            hasMargins = hasMargins,
                            isPresentationSmart = isSlideMode
                        )

                        // Transfer and transform hyperlinks with exact N-up coordinates if enabled
                        if (enableLinks) {
                            val srcPage = srcDoc.getPage(pageIdx)
                            PdfLinkEngine.processAndTransferLinks(
                                srcDoc = srcDoc,
                                srcPage = srcPage,
                                pageIndex = pageIdx,
                                outPage = outPage,
                                slotLeft = slotLeft,
                                slotBottom = slotBottom,
                                slotWidth = slotW,
                                slotHeight = slotH,
                                layout = layout,
                                col = col,
                                cols = cols,
                                spaceDistributionMode = spaceDistributionMode,
                                hasMargins = hasMargins,
                                isPresentationSmart = isSlideMode
                            )
                        }

                        onProgress?.invoke(pageIdx + 1, pageCount)
                        pageIdx++
                    }
                }

                contentStream.close()
            }

            outDoc.save(outputStream)
        } finally {
            outDoc.close()
            srcDoc.close()
        }
    }

    /**
     * Backward-compatible overload for symmetric margins.
     */
    fun mergeNUp(
        sourcePdfStream: InputStream,
        outputStream: OutputStream,
        layout: PrintLayout = LayoutRegistry.builtInLayouts.first(),
        addTextContrast: Boolean = false,
        enableLinks: Boolean = false,
        bestFit: Boolean = true,
        marginMm: Int = 0,
        autoTrimSlideBorders: Boolean = false,
        isPresentationSmart: Boolean = false,
        spaceDistributionMode: SpaceDistributionMode = SpaceDistributionMode.CENTER,
        targetPaper: TargetPaperSize = TargetPaperSize.A4,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        mergeNUp(
            sourcePdfStream, outputStream, layout,
            addTextContrast, enableLinks, bestFit,
            marginTopMm = marginMm,
            marginBottomMm = marginMm,
            marginLeftMm = marginMm,
            marginRightMm = marginMm,
            autoTrimSlideBorders = autoTrimSlideBorders,
            isPresentationSmart = isPresentationSmart,
            spaceDistributionMode = spaceDistributionMode,
            targetPaper = targetPaper,
            onProgress = onProgress
        )
    }

    /**
     * Dynamically calculates output sheet dimensions:
     * - If [bestFit] is true: adapts sheet aspect ratio to match the combined grid ratio of
     *   the source slides (e.g. 16:9, 4:3), completely eliminating letterbox white bars.
     * - If [bestFit] is false: maintains standard [targetPaper] (A4 or A5) sheet dimensions.
     */
    private fun calculateSheetRectangle(
        srcDoc: PDDocument,
        layout: PrintLayout,
        bestFit: Boolean,
        marginTopPt: Float,
        marginBottomPt: Float,
        marginLeftPt: Float,
        marginRightPt: Float,
        gutterX: Float,
        gutterY: Float,
        targetPaper: TargetPaperSize = TargetPaperSize.A4
    ): PDRectangle {
        val paperLong = targetPaper.heightPt // A4: 841.8898f, A5: 595.27563f
        val paperShort = targetPaper.widthPt // A4: 595.27563f, A5: 419.52756f

        if (!bestFit || srcDoc.numberOfPages == 0) {
            return if (layout.landscape) {
                PDRectangle(paperLong, paperShort)
            } else {
                PDRectangle(paperShort, paperLong)
            }
        }

        // Determine input slide aspect ratio from page 0
        val firstPage = srcDoc.getPage(0)
        val cropBox = firstPage.cropBox ?: firstPage.mediaBox ?: PDRectangle(paperShort, paperLong)
        val bounds = PdfPageGeometry.getVisualBounds(cropBox, firstPage.rotation)
        val srcW = bounds.visualW
        val srcH = bounds.visualH
        if (srcW <= 0f || srcH <= 0f) {
            return if (layout.landscape) PDRectangle(paperLong, paperShort) else PDRectangle(paperShort, paperLong)
        }

        val subRatio = srcW / srcH

        val cols = layout.cols
        val rows = layout.rows
        val gridRatio = (cols.toFloat() / rows.toFloat()) * subRatio

        // For standard paper pages or close to standard paper (ratio ~1.414 or 0.707), keep standard
        val paperRatio = paperLong / paperShort
        val sheetRatioExpected = if (layout.landscape) paperRatio else (1f / paperRatio)
        if (Math.abs(gridRatio - sheetRatioExpected) < 0.03f) {
            return if (layout.landscape) PDRectangle(paperLong, paperShort) else PDRectangle(paperShort, paperLong)
        }

        // Adaptive Best Fit: Size sheet so (slotW / slotH) == subRatio after subtracting margins & gutters
        return if (layout.landscape) {
            val baseW = paperLong
            val printableW = Math.max(10f, baseW - marginLeftPt - marginRightPt)
            val netW = printableW - (cols - 1) * gutterX
            val netH = netW / gridRatio
            val printableH = netH + (rows - 1) * gutterY
            val sheetH = printableH + marginTopPt + marginBottomPt
            PDRectangle(baseW, sheetH)
        } else {
            val baseH = paperLong
            val printableH = Math.max(10f, baseH - marginTopPt - marginBottomPt)
            val netH = printableH - (rows - 1) * gutterY
            val netW = netH * gridRatio
            val printableW = netW + (cols - 1) * gutterX
            val sheetW = printableW + marginLeftPt + marginRightPt
            PDRectangle(sheetW, baseH)
        }
    }

    /**
     * Convenience overload for file-based input/output.
     */
    fun mergeNUp(
        sourcePdfFile: File,
        outputPdfFile: File,
        layout: PrintLayout = LayoutRegistry.builtInLayouts.first(),
        addTextContrast: Boolean = false,
        enableLinks: Boolean = false,
        bestFit: Boolean = true,
        marginTopMm: Int = 0,
        marginBottomMm: Int = 0,
        marginLeftMm: Int = 0,
        marginRightMm: Int = 0,
        autoTrimSlideBorders: Boolean = false,
        isPresentationSmart: Boolean = false,
        spaceDistributionMode: SpaceDistributionMode = SpaceDistributionMode.CENTER,
        targetPaper: TargetPaperSize = TargetPaperSize.A4,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        sourcePdfFile.inputStream().use { input ->
            outputPdfFile.outputStream().use { output ->
                mergeNUp(
                    input, output, layout, addTextContrast, enableLinks, bestFit,
                    marginTopMm, marginBottomMm, marginLeftMm, marginRightMm,
                    autoTrimSlideBorders, isPresentationSmart,
                    spaceDistributionMode,
                    targetPaper,
                    onProgress
                )
            }
        }
    }

    /**
     * Backward-compatible overload for symmetric margins (file-based).
     */
    fun mergeNUp(
        sourcePdfFile: File,
        outputPdfFile: File,
        layout: PrintLayout = LayoutRegistry.builtInLayouts.first(),
        addTextContrast: Boolean = false,
        enableLinks: Boolean = false,
        bestFit: Boolean = true,
        marginMm: Int = 0,
        autoTrimSlideBorders: Boolean = false,
        isPresentationSmart: Boolean = false,
        spaceDistributionMode: SpaceDistributionMode = SpaceDistributionMode.CENTER,
        targetPaper: TargetPaperSize = TargetPaperSize.A4,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        mergeNUp(
            sourcePdfFile, outputPdfFile, layout,
            addTextContrast, enableLinks, bestFit,
            marginTopMm = marginMm,
            marginBottomMm = marginMm,
            marginLeftMm = marginMm,
            marginRightMm = marginMm,
            autoTrimSlideBorders = autoTrimSlideBorders,
            isPresentationSmart = isPresentationSmart,
            spaceDistributionMode = spaceDistributionMode,
            targetPaper = targetPaper,
            onProgress = onProgress
        )
    }

    /**
     * Legacy API: 2-up side-by-side (2×1 landscape) for backward compatibility.
     */
    fun mergeTwoUp(
        sourcePdfStream: InputStream,
        outputStream: OutputStream,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        mergeNUp(sourcePdfStream, outputStream, LayoutRegistry.builtInLayouts.first(), onProgress = onProgress)
    }

    private fun drawVectorPageInSlot(
        srcDoc: PDDocument,
        layerUtility: LayerUtility,
        contentStream: PDPageContentStream,
        pageIndex: Int,
        slotLeft: Float,
        slotBottom: Float,
        slotWidth: Float,
        slotHeight: Float,
        layout: PrintLayout,
        col: Int = 0,
        cols: Int = 1,
        spaceDistributionMode: SpaceDistributionMode = SpaceDistributionMode.CENTER,
        hasMargins: Boolean = false,
        isPresentationSmart: Boolean = false
    ) {
        val form = layerUtility.importPageAsForm(srcDoc, pageIndex)
        val srcPage = srcDoc.getPage(pageIndex)
        val cropBox = srcPage.cropBox ?: srcPage.mediaBox ?: PDRectangle.A4

        val bounds = PdfPageGeometry.applyUprightFormNormalization(form, cropBox, srcPage.rotation)

        // Content must ALWAYS remain upright in its grid slots. Never autorotate sideways or invert.
        contentStream.saveGraphicsState()

        val placement = PdfPageGeometry.calculateSlotPlacement(
            visualW = bounds.visualW,
            visualH = bounds.visualH,
            slotLeft = slotLeft,
            slotBottom = slotBottom,
            slotWidth = slotWidth,
            slotHeight = slotHeight,
            col = col,
            cols = cols,
            spaceDistributionMode = spaceDistributionMode,
            hasMargins = hasMargins
        )

        contentStream.transform(Matrix(placement.scale, 0f, 0f, placement.scale, placement.tx, placement.ty))
        contentStream.drawForm(form)
        contentStream.restoreGraphicsState()
    }
}
