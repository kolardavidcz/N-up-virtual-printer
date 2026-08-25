package com.example.twoupprint

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Merges source PDF pages N-up into a true vector or pure 1-bit Black & White output PDF.
 *
 * Supports arbitrary grid layouts (cols × rows) with independent
 * sheet orientation (landscape / portrait), subpages orientation,
 * and color processing modes (Color, Grayscale, Pure 1-bit B&W).
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
     * Merges pages from [sourcePdfStream] into an N-up layout defined by [layout]
     * using the specified [colorMode] and [bwAlgorithm].
     */
    fun mergeNUp(
        sourcePdfStream: InputStream,
        outputStream: OutputStream,
        layout: PrintLayout = LayoutRegistry.builtInLayouts.first(),
        colorMode: ColorProcessingMode = ColorProcessingMode.COLOR,
        bwAlgorithm: BwBinarizer.BwAlgorithm = BwBinarizer.BwAlgorithm.TEXT_BOOSTER,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        val srcDoc = PDDocument.load(sourcePdfStream)
        val outDoc = PDDocument()
        val layerUtility = LayerUtility(outDoc)
        val pdfRenderer = if (colorMode != ColorProcessingMode.COLOR) PDFRenderer(srcDoc) else null

        try {
            val pageCount = srcDoc.numberOfPages
            if (pageCount == 0) return

            val cols = layout.cols
            val rows = layout.rows

            // Sheet_v2 internal processing sheet dimensions
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

                        if (colorMode == ColorProcessingMode.COLOR || pdfRenderer == null) {
                            // Full vector pass-through mode
                            drawVectorPageInSlot(
                                srcDoc, layerUtility, contentStream, pageIdx,
                                slotLeft = slotLeft, slotBottom = slotBottom,
                                slotWidth = slotW, slotHeight = slotH,
                                layout = layout
                            )
                        } else {
                            // Pure 1-bit B&W or 8-bit Grayscale rasterization mode
                            drawBinarizedPageInSlot(
                                outDoc, pdfRenderer, contentStream, pageIdx,
                                slotLeft = slotLeft, slotBottom = slotBottom,
                                slotWidth = slotW, slotHeight = slotH,
                                layout = layout,
                                colorMode = colorMode,
                                bwAlgorithm = bwAlgorithm
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
     * Convenience overload for file-based input/output.
     */
    fun mergeNUp(
        sourcePdfFile: File,
        outputPdfFile: File,
        layout: PrintLayout = LayoutRegistry.builtInLayouts.first(),
        colorMode: ColorProcessingMode = ColorProcessingMode.COLOR,
        bwAlgorithm: BwBinarizer.BwAlgorithm = BwBinarizer.BwAlgorithm.TEXT_BOOSTER,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        sourcePdfFile.inputStream().use { input ->
            outputPdfFile.outputStream().use { output ->
                mergeNUp(input, output, layout, colorMode, bwAlgorithm, onProgress)
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

    private fun drawBinarizedPageInSlot(
        outDoc: PDDocument,
        pdfRenderer: PDFRenderer,
        contentStream: PDPageContentStream,
        pageIndex: Int,
        slotLeft: Float,
        slotBottom: Float,
        slotWidth: Float,
        slotHeight: Float,
        layout: PrintLayout,
        colorMode: ColorProcessingMode,
        bwAlgorithm: BwBinarizer.BwAlgorithm
    ) {
        // Render source page at high print resolution (200 DPI for fast & crisp 1-bit output)
        val renderedBitmap = pdfRenderer.renderImageWithDPI(pageIndex, 200f, ImageType.RGB)

        val processedBitmap = when (colorMode) {
            ColorProcessingMode.PURE_BLACK_WHITE -> BwBinarizer.binarize(renderedBitmap, bwAlgorithm)
            ColorProcessingMode.GRAYSCALE -> BwBinarizer.toGrayscale(renderedBitmap)
            ColorProcessingMode.COLOR -> renderedBitmap
        }

        if (processedBitmap != renderedBitmap) {
            renderedBitmap.recycle()
        }

        val srcW = processedBitmap.width.toFloat()
        val srcH = processedBitmap.height.toFloat()

        val srcIsLandscape = srcW > srcH
        val targetIsLandscape = layout.subPageLandscape
        val needsRotation = (srcIsLandscape != targetIsLandscape)

        val finalBitmap = if (needsRotation) {
            val matrix = android.graphics.Matrix().apply { postRotate(270f) }
            val rotated = Bitmap.createBitmap(
                processedBitmap, 0, 0,
                processedBitmap.width, processedBitmap.height,
                matrix, true
            )
            if (rotated != processedBitmap) {
                processedBitmap.recycle()
            }
            rotated
        } else {
            processedBitmap
        }

        val imgW = finalBitmap.width.toFloat()
        val imgH = finalBitmap.height.toFloat()

        val scale = Math.min(slotWidth / imgW, slotHeight / imgH)
        val destW = imgW * scale
        val destH = imgH * scale

        val tx = slotLeft + (slotWidth - destW) / 2f
        val ty = slotBottom + (slotHeight - destH) / 2f

        val pdImage = LosslessFactory.createFromImage(outDoc, finalBitmap)
        contentStream.drawImage(pdImage, tx, ty, destW, destH)
        finalBitmap.recycle()
    }
}
