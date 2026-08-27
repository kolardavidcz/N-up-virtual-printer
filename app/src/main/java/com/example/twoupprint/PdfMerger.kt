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
        addTextContrast: Boolean = true,
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

            // Sheet dimensions for output
            val sheetRect = if (layout.landscape) {
                PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)  // Landscape sheet
            } else {
                PDRectangle(PDRectangle.A4.width, PDRectangle.A4.height)  // Portrait sheet
            }
            val sheetW = sheetRect.width
            val sheetH = sheetRect.height
            val slotW = sheetW / cols.toFloat()
            val slotH = sheetH / rows.toFloat()

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
                        val slotLeft = col * slotW
                        val slotBottom = (rows - 1 - row) * slotH

                        drawVectorPageInSlot(
                            srcDoc, layerUtility, contentStream, pageIdx,
                            slotLeft = slotLeft, slotBottom = slotBottom,
                            slotWidth = slotW, slotHeight = slotH,
                            layout = layout
                        )

                        // Transfer and transform hyperlinks with exact N-up coordinates
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
     * Convenience overload for file-based input/output.
     */
    fun mergeNUp(
        sourcePdfFile: File,
        outputPdfFile: File,
        layout: PrintLayout = LayoutRegistry.builtInLayouts.first(),
        addTextContrast: Boolean = true,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        sourcePdfFile.inputStream().use { input ->
            outputPdfFile.outputStream().use { output ->
                mergeNUp(input, output, layout, addTextContrast, onProgress)
            }
        }
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
