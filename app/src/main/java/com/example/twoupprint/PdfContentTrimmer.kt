package com.example.twoupprint

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent detector and de-letterboxer for presentation slides pre-baked onto
 * standard page formats (e.g. ISO A4, US Letter) by mobile apps (PowerPoint, Google Slides,
 * Chrome, Samsung Notes, Acrobat).
 *
 * Trims empty letterbox (horizontal) and pillarbox (vertical) margins so that slides expand
 * to fill N-up grid slots compactly without double padding.
 */
class PdfContentTrimmer {

    companion object {
        /**
         * Standard presentation slide aspect ratios:
         * - 16:9 (1.7778): Modern widescreen standard (Google Slides, PowerPoint, Keynote default)
         * - 16:10 (1.6000): Widescreen laptops & tablets
         * - 3:2 (1.5000): Surface devices & classic presentations
         * - 4:3 (1.3333): Traditional presentations & iPads
         */
        val CANDIDATE_RATIOS = floatArrayOf(
            16f / 9f,
            16f / 10f,
            3f / 2f,
            4f / 3f
        )

        /**
         * Singleton instance for convenient stateless trimming.
         */
        val instance = PdfContentTrimmer()

        /**
         * Trims all pages in [doc] if slide letterboxing is detected.
         *
         * @param doc The source PDF document.
         * @param forceSlideMode True if "Presentation Smart" is selected, forcing slide detection.
         * @return The detected slide aspect ratio if letterboxing was trimmed, or null if untouched.
         */
        fun trimDocumentSlides(doc: PDDocument, forceSlideMode: Boolean = false): Float? {
            return instance.trimDocument(doc, forceSlideMode)
        }

        /**
         * Detects the effective slide bounding box for a single page.
         */
        fun detectContentBox(
            doc: PDDocument,
            page: PDPage,
            pageIndex: Int,
            forceSlideMode: Boolean = false
        ): PDRectangle {
            return instance.detectPageContentBox(doc, page, pageIndex, forceSlideMode)
        }
    }

    /**
     * Bounding box of detected visual content (text, vectors, images) on a page.
     */
    data class ContentBounds(
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
        val hasContent: Boolean
    ) {
        val width: Float get() = if (hasContent) max(0f, maxX - minX) else 0f
        val height: Float get() = if (hasContent) max(0f, maxY - minY) else 0f
    }

    /**
     * Inspects the document to determine if it contains letterboxed slides. If so,
     * updates each page's in-memory [PDPage.setCropBox] to the trimmed slide rectangle.
     */
    fun trimDocument(doc: PDDocument, forceSlideMode: Boolean = false): Float? {
        val pageCount = doc.numberOfPages
        if (pageCount == 0) return null

        // 1. Detect document-wide slide ratio by inspecting initial pages
        val detectedRatio = detectDocumentSlideRatio(doc) ?: if (forceSlideMode) (16f / 9f) else null
        if (detectedRatio == null) {
            // Normal document (not a letterboxed presentation), leave untouched
            return null
        }

        // 2. Apply trimmed crop box to every page in the document
        for (i in 0 until pageCount) {
            val page = doc.getPage(i)
            val trimmedBox = calculateTrimmedBoxForRatio(page, detectedRatio)
            page.cropBox = trimmedBox
        }

        return detectedRatio
    }

    /**
     * Detects if the document has a consistent slide aspect ratio across its pages.
     */
    fun detectDocumentSlideRatio(doc: PDDocument): Float? {
        val pageCount = doc.numberOfPages
        val sampleSize = min(pageCount, 5)

        for (i in 0 until sampleSize) {
            val page = doc.getPage(i)
            val ratio = detectPageSlideRatio(doc, page, i)
            if (ratio != null) {
                return ratio
            }
        }
        return null
    }

    /**
     * Analyzes a single page and detects if it contains a letterboxed presentation slide.
     * Returns the detected ratio (e.g. 1.7778 for 16:9), or null if regular document.
     */
    fun detectPageSlideRatio(doc: PDDocument, page: PDPage, pageIndex: Int): Float? {
        val origBox = page.cropBox ?: page.mediaBox ?: return null
        val pageW = origBox.width
        val pageH = origBox.height

        if (pageW <= 0f || pageH <= 0f) return null

        // Already native landscape slide (e.g. 960x540 or 1024x768, ratio != A4 landscape 1.414)
        val currentRatio = pageW / pageH
        if (currentRatio > 1.25f && currentRatio < 2.0f && abs(currentRatio - 1.4142f) > 0.06f) {
            return null
        }

        val content = detectContentBounds(doc, page, pageIndex)
        if (!content.hasContent) return null

        // Case A: Portrait Canvas (e.g. A4 Portrait 595 x 842 pt)
        if (pageH > pageW) {
            val emptyBottom = content.minY - origBox.lowerLeftY
            val emptyTop = origBox.upperRightY - content.maxY

            // Normal documents have text near top and bottom (empty margins < 12% of page height)
            // Slide presentations on A4 portrait have letterbox bars of 23% - 30% of page height!
            val minLetterboxMargin = pageH * 0.12f // ~100 pt on A4
            if (emptyBottom < minLetterboxMargin || emptyTop < minLetterboxMargin) {
                return null // Normal document with top/bottom content
            }

            // Test candidate ratios from widest (16:9) to narrowest (4:3)
            for (ratio in CANDIDATE_RATIOS) {
                val slideH = pageW / ratio
                val padY = (pageH - slideH) / 2f
                val slideBottom = origBox.lowerLeftY + padY
                val slideTop = slideBottom + slideH

                val tolerance = 8f
                if (slideBottom <= content.minY + tolerance && slideTop >= content.maxY - tolerance) {
                    return ratio
                }
            }
        }
        // Case B: Landscape Canvas (e.g. A4 Landscape 842 x 595 pt)
        else {
            // 16:9 on A4 Landscape has ~61 pt top and bottom letterbox bars
            val ratio16x9 = 16f / 9f
            val slideH16 = pageW / ratio16x9
            val padY16 = (pageH - slideH16) / 2f

            if (padY16 > 25f) {
                val emptyBottom = content.minY - origBox.lowerLeftY
                val emptyTop = origBox.upperRightY - content.maxY
                if (emptyBottom >= padY16 - 12f && emptyTop >= padY16 - 12f) {
                    return ratio16x9
                }
            }

            // 4:3 on A4 Landscape has ~24 pt left and right pillarbox bars
            val ratio4x3 = 4f / 3f
            val slideW43 = pageH * ratio4x3
            val padX43 = (pageW - slideW43) / 2f

            if (padX43 > 15f) {
                val emptyLeft = content.minX - origBox.lowerLeftX
                val emptyRight = origBox.upperRightX - content.maxX
                if (emptyLeft >= padX43 - 8f && emptyRight >= padX43 - 8f) {
                    return ratio4x3
                }
            }
        }

        return null
    }

    /**
     * Calculates the trimmed [PDRectangle] for [page] given a confirmed [slideRatio].
     */
    fun calculateTrimmedBoxForRatio(page: PDPage, slideRatio: Float): PDRectangle {
        val origBox = page.cropBox ?: page.mediaBox ?: return PDRectangle.A4
        val pageW = origBox.width
        val pageH = origBox.height

        if (pageW <= 0f || pageH <= 0f) return origBox

        // Already native slide format
        val currentRatio = pageW / pageH
        if (currentRatio > 1.25f && currentRatio < 2.0f && abs(currentRatio - 1.4142f) > 0.06f) {
            return origBox
        }

        if (pageH > pageW) {
            // Portrait sheet: slide fills width, trimmed top and bottom
            val slideH = pageW / slideRatio
            val padY = (pageH - slideH) / 2f
            return PDRectangle(origBox.lowerLeftX, origBox.lowerLeftY + padY, pageW, slideH)
        } else {
            // Landscape sheet
            if (slideRatio > currentRatio) {
                // Widescreen (e.g. 16:9 on A4 landscape): fills width, trimmed top and bottom
                val slideH = pageW / slideRatio
                val padY = (pageH - slideH) / 2f
                return PDRectangle(origBox.lowerLeftX, origBox.lowerLeftY + padY, pageW, slideH)
            } else {
                // Narrower slide (e.g. 4:3 on A4 landscape): fills height, trimmed left and right
                val slideW = pageH * slideRatio
                val padX = (pageW - slideW) / 2f
                return PDRectangle(origBox.lowerLeftX + padX, origBox.lowerLeftY, slideW, pageH)
            }
        }
    }

    /**
     * Detects page content box for a single page with optional slide mode override.
     */
    fun detectPageContentBox(
        doc: PDDocument,
        page: PDPage,
        pageIndex: Int,
        forceSlideMode: Boolean = false
    ): PDRectangle {
        val origBox = page.cropBox ?: page.mediaBox ?: return PDRectangle.A4
        val detectedRatio = detectPageSlideRatio(doc, page, pageIndex)
        val effectiveRatio = detectedRatio ?: if (forceSlideMode) (16f / 9f) else null

        return if (effectiveRatio != null) {
            calculateTrimmedBoxForRatio(page, effectiveRatio)
        } else {
            origBox
        }
    }

    /**
     * Discovers content bounds combining text glyphs and content stream vector elements.
     */
    fun detectContentBounds(doc: PDDocument, page: PDPage, pageIndex: Int): ContentBounds {
        val cropBox = page.cropBox ?: page.mediaBox ?: PDRectangle.A4
        val pageW = cropBox.width
        val pageH = cropBox.height

        val streamBounds = scanStreamBounds(page, pageW, pageH)

        // Only scan text stripper if PDFBox resource loader has been initialized (Android runtime)
        val textBounds = if (PDFBoxResourceLoader.isReady()) {
            scanTextBounds(doc, pageIndex, cropBox)
        } else {
            ContentBounds(0f, 0f, 0f, 0f, false)
        }

        if (!textBounds.hasContent && !streamBounds.hasContent) {
            return ContentBounds(0f, 0f, 0f, 0f, false)
        }
        if (!textBounds.hasContent) return streamBounds
        if (!streamBounds.hasContent) return textBounds

        return ContentBounds(
            minX = min(textBounds.minX, streamBounds.minX),
            minY = min(textBounds.minY, streamBounds.minY),
            maxX = max(textBounds.maxX, streamBounds.maxX),
            maxY = max(textBounds.maxY, streamBounds.maxY),
            hasContent = true
        )
    }

    private fun scanTextBounds(doc: PDDocument, pageIndex: Int, cropBox: PDRectangle): ContentBounds {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var hasText = false

        try {
            val stripper = object : PDFTextStripper() {
                init {
                    startPage = pageIndex + 1
                    endPage = pageIndex + 1
                }

                override fun writeString(text: String, textPositions: List<TextPosition>) {
                    if (text.trim().isEmpty()) return
                    for (tp in textPositions) {
                        val x = cropBox.lowerLeftX + tp.xDirAdj
                        val y = cropBox.upperRightY - tp.yDirAdj
                        val w = tp.widthDirAdj
                        val h = max(tp.heightDir, tp.fontSizeInPt)

                        if (x < minX) minX = x
                        if (x + w > maxX) maxX = x + w
                        if (y < minY) minY = y
                        if (y + h > maxY) maxY = y + h
                        hasText = true
                    }
                }
            }
            stripper.getText(doc)
        } catch (_: Throwable) { }

        return if (hasText) {
            ContentBounds(minX, minY, maxX, maxY, true)
        } else {
            ContentBounds(0f, 0f, 0f, 0f, false)
        }
    }

    private fun scanStreamBounds(page: PDPage, pageW: Float, pageH: Float): ContentBounds {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var hasElements = false

        try {
            val parser = PDFStreamParser(page)
            parser.parse()
            val tokens = parser.tokens

            var currentTextX = 0f
            var currentTextY = 0f

            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                if (token is Operator) {
                    val name = token.name
                    when (name) {
                        // Rectangle: x y w h re
                        "re" -> {
                            if (i >= 4) {
                                val oX = (tokens[i - 4] as? COSNumber)?.floatValue()
                                val oY = (tokens[i - 3] as? COSNumber)?.floatValue()
                                val oW = (tokens[i - 2] as? COSNumber)?.floatValue()
                                val oH = (tokens[i - 1] as? COSNumber)?.floatValue()
                                if (oX != null && oY != null && oW != null && oH != null) {
                                    val isFullPage = (oW >= pageW - 4f && oH >= pageH - 4f)
                                    if (!isFullPage && oW > 2f && oH > 2f) {
                                        val rx1 = min(oX, oX + oW)
                                        val rx2 = max(oX, oX + oW)
                                        val ry1 = min(oY, oY + oH)
                                        val ry2 = max(oY, oY + oH)
                                        minX = min(minX, rx1)
                                        maxX = max(maxX, rx2)
                                        minY = min(minY, ry1)
                                        maxY = max(maxY, ry2)
                                        hasElements = true
                                    }
                                }
                            }
                        }
                        // Transform matrix: a b c d e f cm (e.g. before image Do)
                        "cm" -> {
                            if (i >= 6) {
                                val a = (tokens[i - 6] as? COSNumber)?.floatValue()
                                val d = (tokens[i - 3] as? COSNumber)?.floatValue()
                                val e = (tokens[i - 2] as? COSNumber)?.floatValue()
                                val f = (tokens[i - 1] as? COSNumber)?.floatValue()
                                if (a != null && d != null && e != null && f != null) {
                                    var isDo = false
                                    for (j in (i + 1)..min(tokens.size - 1, i + 3)) {
                                        if ((tokens[j] as? Operator)?.name == "Do") {
                                            isDo = true
                                            break
                                        }
                                    }
                                    if (isDo) {
                                        val isFullPage = (abs(a) >= pageW - 4f && abs(d) >= pageH - 4f)
                                        if (!isFullPage && abs(a) > 2f && abs(d) > 2f) {
                                            val ix1 = min(e, e + a)
                                            val ix2 = max(e, e + a)
                                            val iy1 = min(f, f + d)
                                            val iy2 = max(f, f + d)
                                            minX = min(minX, ix1)
                                            maxX = max(maxX, ix2)
                                            minY = min(minY, iy1)
                                            maxY = max(maxY, iy2)
                                            hasElements = true
                                        }
                                    }
                                }
                            }
                        }
                        // Path coordinates: x y m or x y l
                        "m", "l" -> {
                            if (i >= 2) {
                                val px = (tokens[i - 2] as? COSNumber)?.floatValue()
                                val py = (tokens[i - 1] as? COSNumber)?.floatValue()
                                if (px != null && py != null && px in 0f..pageW && py in 0f..pageH) {
                                    minX = min(minX, px)
                                    maxX = max(maxX, px)
                                    minY = min(minY, py)
                                    maxY = max(maxY, py)
                                    hasElements = true
                                }
                            }
                        }
                        // Text matrix: a b c d e f Tm
                        "Tm" -> {
                            if (i >= 6) {
                                val e = (tokens[i - 2] as? COSNumber)?.floatValue()
                                val f = (tokens[i - 1] as? COSNumber)?.floatValue()
                                if (e != null && f != null && e in -50f..(pageW + 50f) && f in -50f..(pageH + 50f)) {
                                    currentTextX = e
                                    currentTextY = f
                                }
                            }
                        }
                        // Text displacement: tx ty Td or tx ty TD
                        "Td", "TD" -> {
                            if (i >= 2) {
                                val tx = (tokens[i - 2] as? COSNumber)?.floatValue()
                                val ty = (tokens[i - 1] as? COSNumber)?.floatValue()
                                if (tx != null && ty != null) {
                                    currentTextX += tx
                                    currentTextY += ty
                                }
                            }
                        }
                        // Text operators: Tj, TJ, ', "
                        "Tj", "TJ", "'", "\"" -> {
                            if (currentTextX in 0f..pageW && currentTextY in 0f..pageH) {
                                minX = min(minX, currentTextX)
                                maxX = max(maxX, currentTextX)
                                minY = min(minY, currentTextY)
                                maxY = max(maxY, currentTextY + 12f)
                                hasElements = true
                            }
                        }
                    }
                }
                i++
            }
        } catch (_: Exception) { }

        return if (hasElements) {
            ContentBounds(minX, minY, maxX, maxY, true)
        } else {
            ContentBounds(0f, 0f, 0f, 0f, false)
        }
    }
}
