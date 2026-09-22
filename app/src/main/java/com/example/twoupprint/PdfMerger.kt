package com.example.twoupprint

import android.content.Context
import android.graphics.Bitmap
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
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        val srcDoc = PDDocument.load(sourcePdfStream)
        val outDoc = PDDocument()
        val layerUtility = LayerUtility(outDoc)

        try {
            val pageCount = srcDoc.numberOfPages
            if (pageCount == 0) return

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

            val gutterX = ((marginLeftPt + marginRightPt) / 2f).coerceAtLeast(0f)
            val gutterY = ((marginTopPt + marginBottomPt) / 2f).coerceAtLeast(0f)

            // Sheet dimensions for output (adaptive Best Fit or fixed A4)
            val sheetRect = calculateSheetRectangle(
                srcDoc, layout, bestFit,
                marginTopPt, marginBottomPt, marginLeftPt, marginRightPt,
                gutterX, gutterY
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
                            layout = layout
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
                                layout = layout
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
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        mergeNUp(
            sourcePdfStream, outputStream, layout,
            addTextContrast, enableLinks, bestFit,
            marginTopMm = marginMm,
            marginBottomMm = marginMm,
            marginLeftMm = marginMm,
            marginRightMm = marginMm,
            onProgress = onProgress
        )
    }

    /**
     * Dynamically calculates output sheet dimensions:
     * - If [bestFit] is true: adapts sheet aspect ratio to match the combined grid ratio of
     *   the source slides (e.g. 16:9, 4:3), completely eliminating letterbox white bars.
     * - If [bestFit] is false: maintains standard ISO A4 sheet dimensions.
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
        gutterY: Float
    ): PDRectangle {
        val a4Long = PDRectangle.A4.height // 841.8898f
        val a4Short = PDRectangle.A4.width // 595.27563f

        if (!bestFit || srcDoc.numberOfPages == 0) {
            return if (layout.landscape) {
                PDRectangle(a4Long, a4Short)
            } else {
                PDRectangle(a4Short, a4Long)
            }
        }

        // Determine input slide aspect ratio from page 0
        val firstPage = srcDoc.getPage(0)
        val cropBox = firstPage.cropBox ?: firstPage.mediaBox
        val srcW = cropBox.width
        val srcH = cropBox.height
        if (srcW <= 0f || srcH <= 0f) {
            return if (layout.landscape) PDRectangle(a4Long, a4Short) else PDRectangle(a4Short, a4Long)
        }

        val srcIsLandscape = srcW > srcH
        val targetIsLandscape = layout.subPageLandscape
        val needsRotation = (srcIsLandscape != targetIsLandscape)

        val subW = if (needsRotation) srcH else srcW
        val subH = if (needsRotation) srcW else srcH
        val subRatio = subW / subH

        val cols = layout.cols
        val rows = layout.rows
        val gridRatio = (cols.toFloat() / rows.toFloat()) * subRatio

        // For A4 pages or close to A4 (ratio ~1.414 or 0.707), keep A4 standard
        val a4Ratio = a4Long / a4Short
        val sheetRatioExpected = if (layout.landscape) a4Ratio else (1f / a4Ratio)
        if (Math.abs(gridRatio - sheetRatioExpected) < 0.03f) {
            return if (layout.landscape) PDRectangle(a4Long, a4Short) else PDRectangle(a4Short, a4Long)
        }

        // Adaptive Best Fit: Size sheet so (slotW / slotH) == subRatio after subtracting margins & gutters
        return if (layout.landscape) {
            val baseW = a4Long
            val printableW = Math.max(10f, baseW - marginLeftPt - marginRightPt)
            val netW = printableW - (cols - 1) * gutterX
            val netH = netW / gridRatio
            val printableH = netH + (rows - 1) * gutterY
            val sheetH = printableH + marginTopPt + marginBottomPt
            PDRectangle(baseW, sheetH)
        } else {
            val baseH = a4Long
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
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        sourcePdfFile.inputStream().use { input ->
            outputPdfFile.outputStream().use { output ->
                mergeNUp(
                    input, output, layout, addTextContrast, enableLinks, bestFit,
                    marginTopMm, marginBottomMm, marginLeftMm, marginRightMm,
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
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        mergeNUp(
            sourcePdfFile, outputPdfFile, layout,
            addTextContrast, enableLinks, bestFit,
            marginTopMm = marginMm,
            marginBottomMm = marginMm,
            marginLeftMm = marginMm,
            marginRightMm = marginMm,
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
        layout: PrintLayout
    ) {
        val form = layerUtility.importPageAsForm(srcDoc, pageIndex)
        val srcPage = srcDoc.getPage(pageIndex)
        val cropBox = srcPage.cropBox ?: srcPage.mediaBox

        val srcW = cropBox.width
        val srcH = cropBox.height

        val srcIsLandscape = srcW > srcH
        val targetIsLandscape = layout.subPageLandscape

        val needsRotation = (srcIsLandscape != targetIsLandscape)

        contentStream.saveGraphicsState()

        if (needsRotation) {
            val rotatedW = srcH
            val rotatedH = srcW
            val scale = Math.min(slotWidth / rotatedW, slotHeight / rotatedH)

            val destW = rotatedW * scale
            val destH = rotatedH * scale

            val tx = slotLeft + (slotWidth - destW) / 2f
            val ty = slotBottom + (slotHeight - destH) / 2f

            val e = tx + destW + scale * cropBox.lowerLeftY
            val f = ty - scale * cropBox.lowerLeftX

            contentStream.transform(Matrix(0f, scale, -scale, 0f, e, f))
        } else {
            val scale = Math.min(slotWidth / srcW, slotHeight / srcH)

            val destW = srcW * scale
            val destH = srcH * scale

            val tx = slotLeft + (slotWidth - destW) / 2f - cropBox.lowerLeftX * scale
            val ty = slotBottom + (slotHeight - destH) / 2f - cropBox.lowerLeftY * scale

            contentStream.transform(Matrix(scale, 0f, 0f, scale, tx, ty))
        }

        contentStream.drawForm(form)
        contentStream.restoreGraphicsState()
    }
}
