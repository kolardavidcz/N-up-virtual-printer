package com.example.twoupprint

import android.content.Context
import android.util.Log
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
 * High-performance vector engine for Reverse N-Up (Page Splitting).
 *
 * Takes multi-column, side-by-side (2-up), or grid PDF sheets and divides
 * each source page into [cols] x [rows] individual vector sub-pages (e.g. 1 -> 2 Left/Right).
 *
 * Slicing is performed strictly in upright visual coordinates (rotation-invariant).
 * Sub-pages are scaled and centered onto target paper sheets (A4 or A5) with zero rasterization.
 */
object PdfSplitter {

    private const val TAG = "PdfSplitter"

    fun init(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /**
     * Splits each page of [sourcePdfStream] into [layout.cols] x [layout.rows] separate pages,
     * writing the resulting multi-page PDF document to [outputStream].
     */
    fun splitPages(
        sourcePdfStream: InputStream,
        outputStream: OutputStream,
        layout: PrintLayout = LayoutRegistry.builtInLayouts.last(),
        addTextContrast: Boolean = false,
        enableLinks: Boolean = false,
        marginTopMm: Int = 0,
        marginBottomMm: Int = 0,
        marginLeftMm: Int = 0,
        marginRightMm: Int = 0,
        targetPaper: TargetPaperSize = TargetPaperSize.A4,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        val srcDoc = PDDocument.load(sourcePdfStream)
        val outDoc = PDDocument()

        try {
            if (addTextContrast) {
                for (page in srcDoc.pages) {
                    VectorTextBooster.boostPage(srcDoc, page, preserveImages = true)
                }
            }

            val layerUtility = LayerUtility(outDoc)
            val srcPageCount = srcDoc.numberOfPages
            val cols = layout.cols.coerceAtLeast(1)
            val rows = layout.rows.coerceAtLeast(1)
            val tilesPerPage = cols * rows
            val totalOutputPages = srcPageCount * tilesPerPage

            val mmToPt = 72f / 25.4f
            val marginTopPt = marginTopMm * mmToPt
            val marginBottomPt = marginBottomMm * mmToPt
            val marginLeftPt = marginLeftMm * mmToPt
            val marginRightPt = marginRightMm * mmToPt

            val paperLong = targetPaper.heightPt
            val paperShort = targetPaper.widthPt
            val sheetRect = if (layout.landscape) {
                PDRectangle(paperLong, paperShort)
            } else {
                PDRectangle(paperShort, paperLong)
            }
            val sheetW = sheetRect.width
            val sheetH = sheetRect.height

            val printableW = Math.max(10f, sheetW - marginLeftPt - marginRightPt)
            val printableH = Math.max(10f, sheetH - marginTopPt - marginBottomPt)

            var outputPageIdx = 0

            for (srcIdx in 0 until srcPageCount) {
                val srcPage = srcDoc.getPage(srcIdx)
                val cropBox = srcPage.cropBox ?: srcPage.mediaBox ?: PDRectangle.A4

                val form = layerUtility.importPageAsForm(srcDoc, srcIdx)
                val bounds = PdfPageGeometry.applyUprightFormNormalization(form, cropBox, srcPage.rotation)
                val visualW = bounds.visualW
                val visualH = bounds.visualH

                // Standard reading order: top-to-bottom (row 0 to rows - 1), left-to-right (col 0 to cols - 1)
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val outPage = PDPage(sheetRect)
                        outDoc.addPage(outPage)

                        val tileBounds = PdfPageGeometry.calculateTileVisualBounds(visualW, visualH, c, r, cols, rows)
                        val placement = PdfPageGeometry.calculateSlotPlacement(
                            visualW = tileBounds.width,
                            visualH = tileBounds.height,
                            slotLeft = marginLeftPt,
                            slotBottom = marginBottomPt,
                            slotWidth = printableW,
                            slotHeight = printableH
                        )
                        val (shiftX, shiftY) = PdfPageGeometry.calculateTileShift(placement, tileBounds)

                        val contentStream = PDPageContentStream(
                            outDoc,
                            outPage,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        )
                        contentStream.saveGraphicsState()
                        // Clip strictly to destination slot
                        contentStream.addRect(placement.tx, placement.ty, placement.destW, placement.destH)
                        contentStream.clip()
                        contentStream.transform(Matrix(placement.scale, 0f, 0f, placement.scale, shiftX, shiftY))
                        contentStream.drawForm(form)
                        contentStream.restoreGraphicsState()
                        contentStream.close()

                        if (enableLinks) {
                            PdfLinkEngine.processAndTransferLinksForTile(
                                srcDoc = srcDoc,
                                srcPage = srcPage,
                                pageIndex = srcIdx,
                                outPage = outPage,
                                tileBounds = tileBounds,
                                destLeft = placement.tx,
                                destBottom = placement.ty,
                                scale = placement.scale
                            )
                        }

                        outputPageIdx++
                        onProgress?.invoke(outputPageIdx, totalOutputPages)
                    }
                }
            }

            outDoc.save(outputStream)
        } finally {
            try { outDoc.close() } catch (_: Exception) {}
            try { srcDoc.close() } catch (_: Exception) {}
        }
    }

    /**
     * File-based overload of [splitPages].
     */
    fun splitPages(
        sourcePdfFile: File,
        outputPdfFile: File,
        layout: PrintLayout = LayoutRegistry.builtInLayouts.last(),
        addTextContrast: Boolean = false,
        enableLinks: Boolean = false,
        marginTopMm: Int = 0,
        marginBottomMm: Int = 0,
        marginLeftMm: Int = 0,
        marginRightMm: Int = 0,
        targetPaper: TargetPaperSize = TargetPaperSize.A4,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        sourcePdfFile.inputStream().use { input ->
            outputPdfFile.outputStream().use { output ->
                splitPages(
                    input, output, layout, addTextContrast, enableLinks,
                    marginTopMm, marginBottomMm, marginLeftMm, marginRightMm,
                    targetPaper, onProgress
                )
            }
        }
    }
}
