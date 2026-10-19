package com.example.twoupprint.notewise

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * High-performance PDF to Notewise (.notewise) converter.
 *
 * Implements Path B (Direct Generation):
 *   - Extracts text paragraphs and bullet points with 1:1 exact bounding boxes and dynamic typography
 *   - Dynamically determines font sizes (round(dominantSize)), bold/italic styling, and colors from PDF spans
 *   - Detects LaTeX / math formulas and renders them as high-resolution images placed at exact coordinates
 *   - Extracts embedded PDF images at exact transformation bounding boxes
 *   - Packages directly into native .notewise ZIP archives with 76-char Base64 MIME wrapping
 */
object NotewisePdfConverter {

    // Matches bullet symbols (•, -, *, etc.), numbered items (1., 1)), or lettered items (a., a))
    val BULLET_REGEX: Regex = Regex("^(?:[•\\-*–—▪▫‣\\u25cf\\u25cb\\u25e6\\uf0b7]|\\d+[.)]|[a-zA-Z][.)]|[ivxLCDM]+[.)])\\s+")
    val H2_REGEX: Regex = Regex("^(?:##\\s+|BLOK\\s+\\d+)")
    val H3_REGEX: Regex = Regex("^(?:###\\s+|\\d+\\.\\d+\\s+[A-Z\\u00C0-\\u017E])")

    // Fallback 1x1 transparent PNG bytes for headless/JVM environments where Android Bitmap is unavailable
    private val FALLBACK_IMAGE_BYTES: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(),
        0x89.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0B.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(),
        0x54.toByte(), 0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0x60.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x02.toByte(), 0x00.toByte(), 0x01.toByte(), 0xE2.toByte(), 0x21.toByte(), 0xBC.toByte(), 0x33.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(),
        0x42.toByte(), 0x60.toByte(), 0x82.toByte()
    )

    data class RawLine(
        val text: String,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
        val dominantFontSize: Float,
        val isBold: Boolean,
        val isItalic: Boolean,
        val colorHex: String = "#000000",
        val isMath: Boolean = false,
        val isBullet: Boolean = false,
        val isH1: Boolean = false,
        val isH2: Boolean = false,
        val isH3: Boolean = false,
        val isHeading: Boolean = false
    )

    data class MutableParagraph(
        var text: String,
        var minX: Float,
        var minY: Float,
        var maxX: Float,
        var maxY: Float,
        var dominantFontSize: Float,
        var isBold: Boolean,
        var isItalic: Boolean,
        var colorHex: String = "#000000",
        var isBullet: Boolean = false,
        var isH1: Boolean = false,
        var isH2: Boolean = false,
        var isH3: Boolean = false,
        var isHeading: Boolean = false,
        var headingLevel: Int = 0
    )

    data class PageContent(
        val textBlocks: List<NotewiseTextBlock>,
        val mathImages: List<NotewiseImageBlock>
    )

    /**
     * Checks if a font name indicates LaTeX / TeX mathematical typography.
     */
    fun isMathFont(fontName: String?): Boolean {
        if (fontName == null) return false
        val n = fontName.lowercase()
        return n.contains("cmmi") || n.contains("cmsy") || n.contains("cmex") ||
               n.contains("msam") || n.contains("msbm") || n.contains("eufm") ||
               n.contains("wasy") || n.contains("stix") || n.contains("math") ||
               n.contains("symbol") || n.contains("mtpro") || n.contains("txsy") ||
               n.contains("txex") || n.contains("pxsy") || n.contains("pxex") ||
               n.contains("dsrom")
    }

    /**
     * Checks if a character belongs to standard mathematical Unicode blocks or operators.
     */
    fun isMathSymbol(c: Char): Boolean {
        return (c in '\u2200'..'\u22FF') // Mathematical Operators (∀, ∃, ∇, ∈, ∏, ∑, ∫, √, ∝, ∞, ≠, ≤, ≥, etc.)
            || (c in '\u2A00'..'\u2AFF') // Supplemental Mathematical Operators
            || (c in '\u2190'..'\u21FF') // Arrows (→, ←, ⇒, etc.)
            || (c in '\u27C0'..'\u27EF') // Misc Math Symbols-A
            || (c in '\u2980'..'\u29FF') // Misc Math Symbols-B
            || (c in '\u0391'..'\u03A9') // Greek Uppercase (Γ, Δ, Θ, etc.)
            || (c in '\u03B1'..'\u03C9') // Greek Lowercase (α, β, γ, etc.)
            || c == '±' || c == '×' || c == '÷' || c == '√' || c == '∞' || c == '≠' || c == '≤' || c == '≥' || c == '≈' || c == '≡'
    }

    /**
     * Converts a PDF file to a native .notewise notebook file.
     */
    fun convert(
        pdfFile: File,
        outputNotewiseFile: File,
        title: String? = null,
        splitBullets: Boolean = true
    ) {
        PDDocument.load(pdfFile).use { doc ->
            outputNotewiseFile.outputStream().use { outStream ->
                val docTitle = title ?: pdfFile.nameWithoutExtension
                convert(doc, outStream, docTitle, splitBullets)
            }
        }
    }

    /**
     * Converts an open [PDDocument] to a .notewise archive written to [outputStream].
     */
    fun convert(
        doc: PDDocument,
        outputStream: OutputStream,
        title: String = "Notebook",
        splitBullets: Boolean = true
    ) {
        val builder = NotewiseNotebookBuilder(title = title)
        val pageCount = doc.numberOfPages

        for (pageIdx in 0 until pageCount) {
            val page = doc.getPage(pageIdx)
            val cropBox = page.cropBox ?: page.mediaBox ?: PDRectangle.A4

            val rot = ((page.rotation % 360) + 360) % 360
            val isRot90or270 = (rot == 90 || rot == 270)
            val rawW = if (isRot90or270) cropBox.height else cropBox.width
            val rawH = if (isRot90or270) cropBox.width else cropBox.height

            val isLandscape = rawW > rawH
            val canvasW = if (isLandscape) 3508 else 2480
            val canvasH = if (isLandscape) 2480 else 3508

            val scaleX = if (rawW > 0f) canvasW.toFloat() / rawW else 1.0f
            val scaleY = if (rawH > 0f) canvasH.toFloat() / rawH else 1.0f

            // 1. Extract text blocks and math formula images from page
            val pageContent = extractPageContent(
                doc = doc,
                pageIndex = pageIdx,
                page = page,
                scaleX = scaleX,
                scaleY = scaleY,
                rawW = rawW,
                rawH = rawH,
                splitBullets = splitBullets
            )

            // 2. Extract embedded images from page at exact bounding boxes
            val embeddedImages = extractPageImages(page, scaleX, scaleY, canvasW, canvasH)

            // Combine embedded PDF images + rendered math formula images
            val allImages = embeddedImages + pageContent.mathImages

            // 3. Add to Notewise builder
            builder.addPage(
                textBlocks = pageContent.textBlocks,
                images = allImages,
                canvasW = canvasW,
                canvasH = canvasH
            )
        }

        builder.buildArchive(outputStream)
    }

    fun extractPageTextBlocks(
        doc: PDDocument,
        pageIndex: Int,
        scaleX: Float,
        scaleY: Float,
        splitBullets: Boolean
    ): List<NotewiseTextBlock> {
        val page = doc.getPage(pageIndex)
        val cropBox = page.cropBox ?: page.mediaBox ?: PDRectangle.A4
        val rot = ((page.rotation % 360) + 360) % 360
        val isRot90or270 = (rot == 90 || rot == 270)
        val rawW = if (isRot90or270) cropBox.height else cropBox.width
        val rawH = if (isRot90or270) cropBox.width else cropBox.height
        return extractPageContent(doc, pageIndex, page, scaleX, scaleY, rawW, rawH, splitBullets).textBlocks
    }

    private fun extractPageContent(
        doc: PDDocument,
        pageIndex: Int,
        page: PDPage,
        scaleX: Float,
        scaleY: Float,
        rawW: Float,
        rawH: Float,
        splitBullets: Boolean
    ): PageContent {
        val lines = mutableListOf<RawLine>()

        try {
            val stripper = object : PDFTextStripper() {
                private val tpColors = IdentityHashMap<TextPosition, String>()

                init {
                    startPage = pageIndex + 1
                    endPage = pageIndex + 1
                    sortByPosition = true
                }

                override fun processTextPosition(textPosition: TextPosition) {
                    try {
                        val color = graphicsState?.nonStrokingColor
                        if (color != null) {
                            val rgb = color.toRGB()
                            val hex = String.format("#%02X%02X%02X", (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
                            tpColors[textPosition] = hex
                        }
                    } catch (_: Throwable) {
                        // Keep default color
                    }
                    super.processTextPosition(textPosition)
                }

                override fun writeString(text: String, textPositions: List<TextPosition>) {
                    val trimmed = text.trim()
                    if (trimmed.isEmpty() || textPositions.isEmpty()) return

                    var minX = Float.MAX_VALUE
                    var maxX = -Float.MAX_VALUE
                    var minY = Float.MAX_VALUE
                    var maxY = -Float.MAX_VALUE

                    var boldCount = 0
                    var italicCount = 0
                    var mathFontCount = 0
                    val sizeCounts = mutableMapOf<Int, Int>()
                    val colorCounts = mutableMapOf<String, Int>()

                    for (tp in textPositions) {
                        val x = tp.xDirAdj
                        val y = tp.yDirAdj
                        val w = tp.widthDirAdj
                        val h = maxOf(tp.heightDir, tp.fontSizeInPt)

                        if (x < minX) minX = x
                        if (x + w > maxX) maxX = x + w
                        if (y - h < minY) minY = y - h
                        if (y > maxY) maxY = y

                        val roundedSize = (tp.fontSizeInPt * 10f).roundToInt()
                        sizeCounts[roundedSize] = (sizeCounts[roundedSize] ?: 0) + 1

                        val hex = tpColors[tp] ?: "#000000"
                        colorCounts[hex] = (colorCounts[hex] ?: 0) + 1

                        val fName = tp.font?.name?.lowercase() ?: ""
                        val fDesc = tp.font?.fontDescriptor
                        val weight = try { fDesc?.fontWeight ?: 0f } catch (_: Throwable) { 0f }
                        val isFontBold = fName.contains("bold") || fName.contains("black") ||
                                fName.contains("heavy") || fName.contains("demi") ||
                                fName.contains("semibold") || fName.contains("w6") ||
                                fName.contains("w7") || fName.contains("w8") || fName.contains("w9") ||
                                (fDesc?.isForceBold == true) || (weight >= 600f)

                        val isFontItalic = fName.contains("italic") || fName.contains("oblique") ||
                                fName.contains("slanted") ||
                                (fDesc?.isItalic == true)

                        if (isFontBold) boldCount++
                        if (isFontItalic) italicCount++
                        if (isMathFont(fName)) mathFontCount++
                    }

                    val dominantSize = if (sizeCounts.isNotEmpty()) {
                        val maxEntry = sizeCounts.maxByOrNull { it.value }!!
                        maxEntry.key / 10f
                    } else {
                        12.0f
                    }

                    val dominantColor = colorCounts.maxByOrNull { it.value }?.key ?: "#000000"
                    val isBold = boldCount > textPositions.size / 2
                    val isItalic = italicCount > textPositions.size / 2

                    val mathSymbolCount = trimmed.count { isMathSymbol(it) }
                    val nonSpace = trimmed.count { !it.isWhitespace() }
                    val mathFontRatio = if (textPositions.isNotEmpty()) mathFontCount.toFloat() / textPositions.size else 0f
                    val mathSymbolRatio = if (nonSpace > 0) mathSymbolCount.toFloat() / nonSpace else 0f

                    // High-confidence math formula detection:
                    // 1. Explicit math fonts (cmmi, cmsy, cmex, msam, stix, etc.)
                    // 2. High density of math symbols/operators
                    val isMath = (mathFontCount > 0 && (mathFontRatio >= 0.2f || mathFontCount >= 2 || mathSymbolCount >= 1)) ||
                                 (mathSymbolCount >= 2 && mathSymbolRatio >= 0.25f) ||
                                 (mathSymbolCount >= 4)

                    val isH1 = dominantSize >= 21.0f || trimmed.startsWith("# ")
                    val isH2 = (dominantSize in 16.5f..21.0f) || H2_REGEX.containsMatchIn(trimmed)
                    val isH3 = H3_REGEX.containsMatchIn(trimmed)
                    val isHeading = isH1 || isH2 || isH3
                    val isBullet = BULLET_REGEX.containsMatchIn(trimmed)

                    lines.add(
                        RawLine(
                            text = trimmed,
                            minX = minX,
                            minY = minY,
                            maxX = maxX,
                            maxY = maxY,
                            dominantFontSize = dominantSize,
                            isBold = isBold,
                            isItalic = isItalic,
                            colorHex = dominantColor,
                            isMath = isMath,
                            isBullet = isBullet,
                            isH1 = isH1,
                            isH2 = isH2,
                            isH3 = isH3,
                            isHeading = isHeading
                        )
                    )
                }
            }
            stripper.getText(doc)
        } catch (t: Throwable) {
            t.printStackTrace()
            return PageContent(emptyList(), emptyList())
        }

        if (lines.isEmpty()) return PageContent(emptyList(), emptyList())

        // 1. Group math lines into formula regions
        val mathRegions = mutableListOf<MutableList<RawLine>>()
        var currentMathRegion: MutableList<RawLine>? = null

        for (line in lines) {
            if (line.isMath) {
                if (currentMathRegion == null) {
                    currentMathRegion = mutableListOf(line)
                } else {
                    val lastLine = currentMathRegion.last()
                    val verticalGap = line.minY - lastLine.maxY
                    if (verticalGap <= maxOf(lastLine.dominantFontSize, line.dominantFontSize) * 2.5f) {
                        currentMathRegion.add(line)
                    } else {
                        mathRegions.add(currentMathRegion)
                        currentMathRegion = mutableListOf(line)
                    }
                }
            } else {
                if (currentMathRegion != null) {
                    val lastLine = currentMathRegion.last()
                    val verticalGap = line.minY - lastLine.maxY
                    val isShortConnector = line.text.length <= 4 &&
                            verticalGap <= maxOf(lastLine.dominantFontSize, line.dominantFontSize) * 1.5f &&
                            (line.text.any { it in "=+-*/()[]<>," } || line.dominantFontSize <= lastLine.dominantFontSize)
                    if (isShortConnector) {
                        currentMathRegion.add(line)
                    } else {
                        mathRegions.add(currentMathRegion)
                        currentMathRegion = null
                    }
                }
            }
        }
        if (currentMathRegion != null) {
            mathRegions.add(currentMathRegion)
        }

        // 2. Render math formula regions as clipped images
        val mathImages = renderMathRegions(doc, pageIndex, rawW, rawH, scaleX, scaleY, mathRegions)

        // 3. Exclude math lines from regular text paragraphs to avoid fragmented duplicates
        val mathLineSet = mathRegions.flatten().toSet()
        val textLines = lines.filter { it !in mathLineSet }

        // Order text lines reading-order aware (supporting 2-column slides / documents)
        val orderedTextLines = orderLinesByReadingOrder(textLines, rawW, rawH)

        // 4. Group remaining text lines into coherent paragraphs
        val paragraphs = mutableListOf<MutableParagraph>()
        var current: MutableParagraph? = null

        for (line in orderedTextLines) {
            if (current == null) {
                val headingLvl = if (line.isH1) 1 else if (line.isH2) 2 else if (line.isH3) 3 else 0
                current = MutableParagraph(
                    text = line.text,
                    minX = line.minX,
                    minY = line.minY,
                    maxX = line.maxX,
                    maxY = line.maxY,
                    dominantFontSize = line.dominantFontSize,
                    isBold = line.isBold,
                    isItalic = line.isItalic,
                    colorHex = line.colorHex,
                    isBullet = line.isBullet,
                    isH1 = line.isH1,
                    isH2 = line.isH2,
                    isH3 = line.isH3,
                    isHeading = line.isHeading,
                    headingLevel = headingLvl
                )
                continue
            }

            val verticalGap = line.minY - current.maxY
            val fontSizeDiff = abs(line.dominantFontSize - current.dominantFontSize)

            val isHeadingCont = current.isHeading && verticalGap <= 22.0f && (
                (current.isH1 && (line.isH1 || line.text.startsWith("["))) ||
                (current.isH2 && (line.isH2 || line.text.startsWith("["))) ||
                (current.isH3 && (line.isH3 || line.text.startsWith("[")))
            )

            val canMergeBody = !current.isHeading &&
                    !line.isHeading &&
                    !(splitBullets && line.isBullet) &&
                    current.isBold == line.isBold &&
                    current.isItalic == line.isItalic &&
                    current.colorHex.equals(line.colorHex, ignoreCase = true) &&
                    fontSizeDiff <= 1.5f &&
                    verticalGap <= (current.dominantFontSize * 1.6f) &&
                    verticalGap >= -4.0f &&
                    abs(line.minX - current.minX) <= 30.0f

            if (isHeadingCont || canMergeBody) {
                current.text += " " + line.text
                current.minX = minOf(current.minX, line.minX)
                current.maxX = maxOf(current.maxX, line.maxX)
                current.maxY = maxOf(current.maxY, line.maxY)
            } else {
                paragraphs.add(current)
                val headingLvl = if (line.isH1) 1 else if (line.isH2) 2 else if (line.isH3) 3 else 0
                current = MutableParagraph(
                    text = line.text,
                    minX = line.minX,
                    minY = line.minY,
                    maxX = line.maxX,
                    maxY = line.maxY,
                    dominantFontSize = line.dominantFontSize,
                    isBold = line.isBold,
                    isItalic = line.isItalic,
                    colorHex = line.colorHex,
                    isBullet = line.isBullet,
                    isH1 = line.isH1,
                    isH2 = line.isH2,
                    isH3 = line.isH3,
                    isHeading = line.isHeading,
                    headingLevel = headingLvl
                )
            }
        }
        if (current != null) {
            paragraphs.add(current)
        }

        // Determine dominant body font size of the page based on character frequency
        val charCountsBySize = mutableMapOf<Int, Int>()
        for (line in textLines) {
            val rounded = (line.dominantFontSize * 10f).roundToInt()
            charCountsBySize[rounded] = (charCountsBySize[rounded] ?: 0) + line.text.length
        }
        val dominantBodyPt = if (charCountsBySize.isNotEmpty()) {
            charCountsBySize.maxByOrNull { it.value }!!.key / 10f
        } else {
            10.0f
        }

        // 5. Convert to Notewise canvas coordinates and proportional dynamic font sizes with horizontal safety padding
        val textBlocks = paragraphs.map { p ->
            val leftP = p.minX * scaleX
            val topP = p.minY * scaleY
            val boxW = (p.maxX - p.minX) * scaleX

            // Proportional font scaling relative to the page's dominant body font size
            val relScale = if (dominantBodyPt > 0f) p.dominantFontSize / dominantBodyPt else 1.0f
            val baseNotewiseBody = 10.5f // Standard readable note body size in Notewise
            var targetFs = (relScale * baseNotewiseBody).roundToInt().coerceIn(7, 32)

            // Width-aware scaling for single-line elements to prevent unwanted newline splits and overlaps
            val isSingleLineInPdf = (p.maxY - p.minY) <= (p.dominantFontSize * 1.55f)
            val nonWhitespaceChars = p.text.count { !it.isWhitespace() }
            if (isSingleLineInPdf && nonWhitespaceChars > 0 && boxW > 10f) {
                // In Roboto, average character width is ~0.60 * fontSize in canvas coordinates
                val maxFitFs = ((boxW / nonWhitespaceChars.toFloat()) / 0.62f).roundToInt()
                if (maxFitFs in 7 until targetFs) {
                    targetFs = maxFitFs
                }
            }

            // Ensure width has safety padding (+35pt) so Flutter's Roboto text renderer does not wrap
            val rightP = maxOf(leftP + boxW + 35.0f, leftP + 60.0f)
            val bottomP = maxOf(p.maxY * scaleY + 8.0f, topP + 18.0f)

            NotewiseTextBlock(
                text = p.text,
                left = leftP,
                top = topP,
                right = rightP,
                bottom = bottomP,
                fontSize = targetFs,
                isBold = p.isBold,
                isItalic = p.isItalic,
                colorHex = p.colorHex,
                isBullet = p.isBullet,
                headingLevel = p.headingLevel
            )
        }

        return PageContent(textBlocks, mathImages)
    }

    /**
     * Orders text lines in natural reading order.
     * Detects 2-column layouts (typical of landscape lecture slides and 2-up presentations)
     * and sequences lines column-by-column to avoid interleaving or horizontal concatenation.
     */
    private fun orderLinesByReadingOrder(lines: List<RawLine>, rawW: Float, rawH: Float): List<RawLine> {
        if (lines.size < 6) return lines.sortedWith(compareBy({ it.minY }, { it.minX }))

        // Multi-column detection (especially common in 2-up landscape slides / handouts)
        val candidateGutterX = rawW * 0.5f
        val leftLines = lines.filter { it.maxX <= candidateGutterX + 15.0f }
        val rightLines = lines.filter { it.minX >= candidateGutterX - 15.0f }
        val spanningLines = lines.filter { it.minX < candidateGutterX - 15.0f && it.maxX > candidateGutterX + 15.0f }

        val is2Col = leftLines.size >= 4 && rightLines.size >= 4 &&
                spanningLines.size <= 0.20f * (leftLines.size + rightLines.size)

        if (!is2Col) {
            return lines.sortedWith(compareBy({ it.minY }, { it.minX }))
        }

        // 2-column layout: separate full-width headers and footers from column bodies
        val topOfCols = minOf(leftLines.minOf { it.minY }, rightLines.minOf { it.minY })
        val bottomOfCols = maxOf(leftLines.maxOf { it.maxY }, rightLines.maxOf { it.maxY })

        val headers = spanningLines.filter { it.maxY <= topOfCols + 20.0f }.sortedBy { it.minY }
        val footers = spanningLines.filter { it.minY >= bottomOfCols - 20.0f }.sortedBy { it.minY }
        val unassigned = spanningLines.filter { it !in headers && it !in footers }

        // Assign any mid-spanning lines to the column containing their horizontal midpoint
        val leftCol = (leftLines + unassigned.filter { (it.minX + it.maxX) / 2.0f < candidateGutterX }).sortedBy { it.minY }
        val rightCol = (rightLines + unassigned.filter { (it.minX + it.maxX) / 2.0f >= candidateGutterX }).sortedBy { it.minY }

        return headers + leftCol + rightCol + footers
    }

    private fun renderMathRegions(
        doc: PDDocument,
        pageIndex: Int,
        rawW: Float,
        rawH: Float,
        scaleX: Float,
        scaleY: Float,
        mathRegions: List<List<RawLine>>
    ): List<NotewiseImageBlock> {
        if (mathRegions.isEmpty()) return emptyList()

        val mathImages = mutableListOf<NotewiseImageBlock>()
        val renderScale = 2.5f

        val pageBitmap = try {
            val renderer = PDFRenderer(doc)
            renderer.renderImage(pageIndex, renderScale)
        } catch (_: Throwable) {
            null
        }

        try {
            for (region in mathRegions) {
                val rMinX = region.minOf { it.minX }
                val rMinY = region.minOf { it.minY }
                val rMaxX = region.maxOf { it.maxX }
                val rMaxY = region.maxOf { it.maxY }

                val padX = 4.0f
                val padY = 3.0f
                val leftPt = maxOf(0f, rMinX - padX)
                val topPt = maxOf(0f, rMinY - padY)
                val rightPt = minOf(rawW, rMaxX + padX)
                val bottomPt = minOf(rawH, rMaxY + padY)

                if (rightPt <= leftPt || bottomPt <= topPt) continue

                val pw = maxOf(1, ((rightPt - leftPt) * renderScale).roundToInt())
                val ph = maxOf(1, ((bottomPt - topPt) * renderScale).roundToInt())

                val imgBytes = if (pageBitmap != null) {
                    val px = (leftPt * renderScale).toInt().coerceIn(0, pageBitmap.width - 1)
                    val py = (topPt * renderScale).toInt().coerceIn(0, pageBitmap.height - 1)
                    val cropW = pw.coerceIn(1, pageBitmap.width - px)
                    val cropH = ph.coerceIn(1, pageBitmap.height - py)

                    val croppedBmp = try {
                        Bitmap.createBitmap(pageBitmap, px, py, cropW, cropH)
                    } catch (_: Throwable) {
                        null
                    }

                    if (croppedBmp != null) {
                        val bytes = compressBitmapToWebp(croppedBmp)
                        try {
                            croppedBmp.recycle()
                        } catch (_: Throwable) {}
                        bytes
                    } else {
                        FALLBACK_IMAGE_BYTES
                    }
                } else {
                    FALLBACK_IMAGE_BYTES
                }

                mathImages.add(
                    NotewiseImageBlock(
                        imageBytes = imgBytes,
                        left = leftPt * scaleX,
                        top = topPt * scaleY,
                        right = rightPt * scaleX,
                        bottom = bottomPt * scaleY,
                        pixelWidth = pw,
                        pixelHeight = ph
                    )
                )
            }
        } finally {
            try {
                pageBitmap?.recycle()
            } catch (_: Throwable) {}
        }

        return mathImages
    }

    private class PdfImageExtractor(
        private val targetPage: PDPage,
        val scaleX: Float,
        val scaleY: Float
    ) : PDFGraphicsStreamEngine(targetPage) {

        val imageBlocks = mutableListOf<NotewiseImageBlock>()

        override fun drawImage(pdImage: PDImage) {
            val ctm = graphicsState.currentTransformationMatrix
            val cropBox = targetPage.cropBox ?: targetPage.mediaBox ?: PDRectangle.A4
            val rot = ((targetPage.rotation % 360) + 360) % 360

            val p0 = ctm.transformPoint(0f, 0f)
            val p1 = ctm.transformPoint(1f, 0f)
            val p2 = ctm.transformPoint(1f, 1f)
            val p3 = ctm.transformPoint(0f, 1f)

            val cropLeft = cropBox.lowerLeftX
            val cropBottom = cropBox.lowerLeftY
            val cropWidth = cropBox.width
            val cropHeight = cropBox.height

            fun toVisual(px: Float, py: Float): Pair<Float, Float> {
                val uX = px - cropLeft
                val uY = py - cropBottom
                return when (rot) {
                    90 -> Pair(uY, uX)
                    180 -> Pair(cropWidth - uX, uY)
                    270 -> Pair(cropHeight - uY, cropWidth - uX)
                    else -> Pair(uX, cropHeight - uY)
                }
            }

            val v0 = toVisual(p0.x, p0.y)
            val v1 = toVisual(p1.x, p1.y)
            val v2 = toVisual(p2.x, p2.y)
            val v3 = toVisual(p3.x, p3.y)

            val minX = minOf(v0.first, v1.first, v2.first, v3.first)
            val maxX = maxOf(v0.first, v1.first, v2.first, v3.first)
            val minY = minOf(v0.second, v1.second, v2.second, v3.second)
            val maxY = maxOf(v0.second, v1.second, v2.second, v3.second)

            if (!minX.isFinite() || !maxX.isFinite() || !minY.isFinite() || !maxY.isFinite()) return
            if (maxX <= minX || maxY <= minY) return

            val imgW = pdImage.width
            val imgH = pdImage.height

            val rawBytes = try {
                pdImage.createInputStream()?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            } ?: FALLBACK_IMAGE_BYTES

            val finalBytes = compressToWebp(rawBytes)

            imageBlocks.add(
                NotewiseImageBlock(
                    imageBytes = finalBytes,
                    left = minX * scaleX,
                    top = minY * scaleY,
                    right = maxX * scaleX,
                    bottom = maxY * scaleY,
                    pixelWidth = imgW,
                    pixelHeight = imgH
                )
            )
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {}
        override fun clip(windingRule: Path.FillType) {}
        override fun moveTo(x: Float, y: Float) {}
        override fun lineTo(x: Float, y: Float) {}
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {}
        override fun getCurrentPoint(): PointF = PointF(0f, 0f)
        override fun closePath() {}
        override fun endPath() {}
        override fun strokePath() {}
        override fun fillPath(windingRule: Path.FillType) {}
        override fun fillAndStrokePath(windingRule: Path.FillType) {}
        override fun shadingFill(shadingName: COSName) {}
    }

    private fun extractPageImages(
        page: PDPage,
        scaleX: Float,
        scaleY: Float,
        canvasW: Int,
        canvasH: Int
    ): List<NotewiseImageBlock> {
        val result = mutableListOf<NotewiseImageBlock>()

        try {
            val extractor = PdfImageExtractor(page, scaleX, scaleY)
            extractor.processPage(page)
            result.addAll(extractor.imageBlocks)
        } catch (t: Throwable) {
            try {
                android.util.Log.w("NotewisePdfConverter", "Image stream extraction note: ${t.message}")
            } catch (_: Throwable) {
                System.err.println("NotewisePdfConverter: Image stream extraction note: ${t.message}")
            }
        }

        // Defensive fallback: if stream extraction yielded no images, check page resources
        if (result.isEmpty()) {
            val resources = page.resources
            if (resources != null) {
                for (name in resources.xObjectNames) {
                    val xObj = try {
                        resources.getXObject(name)
                    } catch (_: Exception) {
                        null
                    }

                    if (xObj is PDImageXObject) {
                        val rawBytes = try {
                            xObj.createInputStream()?.use { it.readBytes() }
                        } catch (_: Exception) {
                            null
                        } ?: continue

                        val webpBytes = compressToWebp(rawBytes)
                        val imgW = xObj.width
                        val imgH = xObj.height

                        result.add(
                            NotewiseImageBlock(
                                imageBytes = webpBytes,
                                left = 0f,
                                top = 0f,
                                right = canvasW.toFloat(),
                                bottom = canvasH.toFloat(),
                                pixelWidth = imgW,
                                pixelHeight = imgH
                            )
                        )
                    }
                }
            }
        }

        return result
    }

    private fun compressBitmapToWebp(bmp: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        return try {
            val ok = bmp.compress(Bitmap.CompressFormat.WEBP, 95, bos)
            if (ok && bos.size() > 0) {
                bos.toByteArray()
            } else {
                val bosPng = ByteArrayOutputStream()
                if (bmp.compress(Bitmap.CompressFormat.PNG, 100, bosPng) && bosPng.size() > 0) {
                    bosPng.toByteArray()
                } else {
                    FALLBACK_IMAGE_BYTES
                }
            }
        } catch (_: Throwable) {
            FALLBACK_IMAGE_BYTES
        }
    }

    fun compressToWebp(rawBytes: ByteArray): ByteArray {
        return try {
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
            if (bmp != null) {
                val bos = ByteArrayOutputStream()
                val ok = bmp.compress(Bitmap.CompressFormat.WEBP, 90, bos)
                bmp.recycle()
                if (ok && bos.size() > 0) bos.toByteArray() else rawBytes
            } else {
                rawBytes
            }
        } catch (_: Throwable) {
            // Graceful fallback for JVM testing environments
            rawBytes
        }
    }
}
