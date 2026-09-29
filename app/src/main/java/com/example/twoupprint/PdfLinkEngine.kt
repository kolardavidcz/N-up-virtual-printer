package com.example.twoupprint

import android.util.Log
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.util.regex.Pattern

/**
 * High-precision engine for preserving existing PDF hyperlinks and auto-linkifying
 * plain-text URLs during N-up page merging.
 *
 * Performs exact affine coordinate transformation (scaling, translation, and 90° rotation)
 * so that clickable areas map perfectly to the arranged slots on the output A4 sheet.
 */
object PdfLinkEngine {

    private const val TAG = "PdfLinkEngine"

    // Comprehensive URL pattern matching full URLs, www.* domains, and standard TLD domains
    private val URL_PATTERN = Pattern.compile(
        """(?i)\b(?:https?://[^\s<>"{}|\\^`\[\]]+|www\.[a-z0-9-]+(?:\.[a-z0-9-]+)+(?:/[^\s<>"{}|\\^`\[\]]*)?|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|org|net|edu|gov|cz|sk|eu|io|ai|de|uk|info|dev|app|me|co|pl|at|ch|nl|be|it|fr|es|se|no|fi|dk|hu|ro|bg|hr|si|rs|gr|ua|ca|us|mx|br|ar|cl|au|nz|jp|cn|kr|in|sg|hk|tw|xyz|tech|online|site|store|blog|link|live|space|wiki|fm|tv|cc|to|gg|page|cloud|design|studio|digital|media)(?:/[^\s<>"{}|\\^`\[\]]*)?)"""
    )
    private val EMAIL_PATTERN = Pattern.compile(
        """\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"""
    )

    /**
     * Extracts existing link annotations and discovers plain-text URLs on [srcPage],
     * transforms their bounding boxes to fit into [slotLeft, slotBottom, slotWidth, slotHeight],
     * and attaches live clickable [PDAnnotationLink] objects to [outPage].
     */
    fun processAndTransferLinks(
        srcDoc: PDDocument,
        srcPage: PDPage,
        pageIndex: Int,
        outPage: PDPage,
        slotLeft: Float,
        slotBottom: Float,
        slotWidth: Float,
        slotHeight: Float,
        layout: PrintLayout,
        col: Int = 0,
        cols: Int = 1,
        spaceDistributionMode: SpaceDistributionMode = SpaceDistributionMode.CENTER,
        isPresentationSmart: Boolean = false
    ) {
        val cropBox = srcPage.cropBox ?: srcPage.mediaBox ?: return
        val existingLinkRects = mutableListOf<PDRectangle>()

        val outAnnotations = outPage.annotations ?: mutableListOf<PDAnnotation>().also {
            outPage.annotations = it
        }

        var linkCount = 0

        // -------------------------------------------------------------
        // Layer 1: Transfer & Transform Existing PDF Link Annotations
        // -------------------------------------------------------------
        try {
            val srcAnnotations = srcPage.annotations
            if (srcAnnotations != null) {
                for (annot in srcAnnotations) {
                    if (annot is PDAnnotationLink) {
                        val srcRect = annot.rectangle ?: continue
                        existingLinkRects.add(srcRect)

                        val transformedRect = transformRect(
                            srcRect, cropBox,
                            slotLeft, slotBottom, slotWidth, slotHeight,
                            layout,
                            col = col,
                            cols = cols,
                            spaceDistributionMode = spaceDistributionMode,
                            pageRotation = srcPage.rotation,
                            isPresentationSmart = isPresentationSmart
                        )

                        val targetAction = annot.action
                        val targetDest = annot.destination

                        if (targetAction != null || targetDest != null) {
                            val newLink = PDAnnotationLink().apply {
                                rectangle = transformedRect
                                action = targetAction
                                destination = targetDest
                                page = outPage
                                isPrinted = true
                                highlightMode = PDAnnotationLink.HIGHLIGHT_MODE_INVERT

                                borderStyle = PDBorderStyleDictionary().apply {
                                    width = 0f
                                }
                                val border = COSArray().apply {
                                    add(COSInteger.ZERO)
                                    add(COSInteger.ZERO)
                                    add(COSInteger.ZERO)
                                }
                                cosObject.setItem(COSName.BORDER, border)
                                cosObject.setItem(COSName.BS, borderStyle.cosObject)
                            }

                            outAnnotations.add(newLink)
                            linkCount++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error transferring existing link annotations: ${e.message}")
        }

        // -------------------------------------------------------------
        // Layer 2: Auto-Detect Plain-Text URLs & Linkify
        // -------------------------------------------------------------
        try {
            val plainTextLinks = extractPlainTextUrls(srcDoc, pageIndex, cropBox)
            for ((rawUrl, srcRect) in plainTextLinks) {
                // Avoid duplicating already annotated links
                if (isOverlappingExisting(srcRect, existingLinkRects)) continue

                val normalizedUrl = if (rawUrl.startsWith("http://", ignoreCase = true) ||
                    rawUrl.startsWith("https://", ignoreCase = true)
                ) {
                    rawUrl
                } else if (rawUrl.contains("@") && !rawUrl.startsWith("mailto:", ignoreCase = true)) {
                    "mailto:$rawUrl"
                } else {
                    "https://$rawUrl"
                }

                val transformedRect = transformRect(
                    srcRect, cropBox,
                    slotLeft, slotBottom, slotWidth, slotHeight,
                    layout,
                    col = col,
                    cols = cols,
                    spaceDistributionMode = spaceDistributionMode,
                    pageRotation = srcPage.rotation,
                    isPresentationSmart = isPresentationSmart
                )

                val newLink = PDAnnotationLink().apply {
                    rectangle = transformedRect
                    action = PDActionURI().apply {
                        uri = normalizedUrl
                    }
                    page = outPage
                    isPrinted = true
                    highlightMode = PDAnnotationLink.HIGHLIGHT_MODE_INVERT

                    borderStyle = PDBorderStyleDictionary().apply {
                        width = 0f
                    }
                    val border = COSArray().apply {
                        add(COSInteger.ZERO)
                        add(COSInteger.ZERO)
                        add(COSInteger.ZERO)
                    }
                    cosObject.setItem(COSName.BORDER, border)
                    cosObject.setItem(COSName.BS, borderStyle.cosObject)
                }

                outAnnotations.add(newLink)
                existingLinkRects.add(srcRect)
                linkCount++
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error discovering plain-text URLs: ${e.message}")
        }

        if (linkCount > 0) {
            Log.i(TAG, "Added $linkCount clickable links to sheet for page $pageIndex")
        }
    }

    /**
     * Cleans trailing punctuation, quotes, and unbalanced parentheses/brackets from matched URLs.
     * Returns Triple(cleanedUrl, adjustedStart, adjustedEnd) or null if invalid.
     */
    private fun cleanUrlMatch(matchedText: String, start: Int, end: Int): Triple<String, Int, Int>? {
        var url = matchedText
        var s = start
        var e = end

        // 1. Trim leading punctuation / quotes / brackets
        while (url.isNotEmpty() && (url.startsWith("(") || url.startsWith("[") || url.startsWith("{") ||
                    url.startsWith("\"") || url.startsWith("'") || url.startsWith("<") || url.startsWith("«"))) {
            url = url.substring(1)
            s++
        }

        // 2. Trim trailing punctuation / quotes / unbalanced brackets
        val trailingPunctuation = charArrayOf('.', ',', ';', ':', '!', '?', '"', '\'', '>', '»', ']', '}')
        while (url.isNotEmpty()) {
            val lastChar = url.last()
            if (lastChar in trailingPunctuation) {
                url = url.dropLast(1)
                e--
            } else if (lastChar == ')') {
                val openCount = url.count { it == '(' }
                val closeCount = url.count { it == ')' }
                if (closeCount > openCount) {
                    url = url.dropLast(1)
                    e--
                } else {
                    break
                }
            } else {
                break
            }
        }

        if (url.length < 3 || s >= e) return null
        return Triple(url, s, e)
    }

    /**
     * Transforms a source rectangle [x1, y1, x2, y2] to the destination N-up slot.
     */
    private fun transformRect(
        srcRect: PDRectangle,
        cropBox: PDRectangle,
        slotLeft: Float,
        slotBottom: Float,
        slotWidth: Float,
        slotHeight: Float,
        layout: PrintLayout,
        col: Int = 0,
        cols: Int = 1,
        spaceDistributionMode: SpaceDistributionMode = SpaceDistributionMode.CENTER,
        pageRotation: Int = 0,
        isPresentationSmart: Boolean = false
    ): PDRectangle {
        val pageRot = ((pageRotation % 360) + 360) % 360
        val isRot90or270 = (pageRot == 90 || pageRot == 270)
        val rawW = cropBox.width
        val rawH = cropBox.height
        val rawX = cropBox.lowerLeftX
        val rawY = cropBox.lowerLeftY

        val visualW = if (isRot90or270) rawH else rawW
        val visualH = if (isRot90or270) rawW else rawH

        val srcIsLandscape = visualW > visualH
        val targetIsLandscape = layout.subPageLandscape
        val needsRotation = if (isPresentationSmart) false else (srcIsLandscape != targetIsLandscape)

        // 1. Transform raw [x1, y1, x2, y2] to visual upright space in [0, 0, visualW, visualH]
        val rx1 = srcRect.lowerLeftX
        val ry1 = srcRect.lowerLeftY
        val rx2 = srcRect.upperRightX
        val ry2 = srcRect.upperRightY

        fun mapToVisual(x: Float, y: Float): Pair<Float, Float> {
            return when (pageRot) {
                90 -> Pair(y - rawY, rawX + rawW - x)
                180 -> Pair(rawX + rawW - x, rawY + rawH - y)
                270 -> Pair(rawY + rawH - y, x - rawX)
                else -> Pair(x - rawX, y - rawY)
            }
        }

        val p1 = mapToVisual(rx1, ry1)
        val p2 = mapToVisual(rx2, ry2)
        val vxMin = Math.min(p1.first, p2.first)
        val vxMax = Math.max(p1.first, p2.first)
        val vyMin = Math.min(p1.second, p2.second)
        val vyMax = Math.max(p1.second, p2.second)

        // 2. Transform visual rectangle into slot
        if (needsRotation) {
            val rotatedW = visualH
            val rotatedH = visualW
            val scale = Math.min(slotWidth / rotatedW, slotHeight / rotatedH)

            val destW = rotatedW * scale
            val destH = rotatedH * scale

            val spareW = (slotWidth - destW).coerceAtLeast(0f)
            val offsetRatio = if (cols > 1) {
                val normCol = col.toFloat() / (cols - 1).toFloat()
                val bias = (normCol - 0.5f) * spaceDistributionMode.factor
                0.5f + bias
            } else {
                0.5f
            }

            val tx = slotLeft + spareW * offsetRatio
            val ty = slotBottom + (slotHeight - destH) / 2f

            val xA = tx + destW - scale * vyMin
            val xB = tx + destW - scale * vyMax
            val yA = ty + scale * vxMin
            val yB = ty + scale * vxMax

            return PDRectangle().apply {
                lowerLeftX = Math.min(xA, xB)
                lowerLeftY = Math.min(yA, yB)
                upperRightX = Math.max(xA, xB)
                upperRightY = Math.max(yA, yB)
            }
        } else {
            val scale = Math.min(slotWidth / visualW, slotHeight / visualH)

            val destW = visualW * scale
            val destH = visualH * scale

            val spareW = (slotWidth - destW).coerceAtLeast(0f)
            val offsetRatio = if (cols > 1) {
                val normCol = col.toFloat() / (cols - 1).toFloat()
                val bias = (normCol - 0.5f) * spaceDistributionMode.factor
                0.5f + bias
            } else {
                0.5f
            }

            val tx = slotLeft + spareW * offsetRatio
            val ty = slotBottom + (slotHeight - destH) / 2f

            val minX = tx + vxMin * scale
            val maxX = tx + vxMax * scale
            val minY = ty + vyMin * scale
            val maxY = ty + vyMax * scale

            return PDRectangle().apply {
                lowerLeftX = minX
                lowerLeftY = minY
                upperRightX = maxX
                upperRightY = maxY
            }
        }
    }

    /**
     * Extracts plain-text URLs and their exact glyph bounding boxes from [pageIndex].
     */
    private fun extractPlainTextUrls(
        doc: PDDocument,
        pageIndex: Int,
        cropBox: PDRectangle
    ): List<Pair<String, PDRectangle>> {
        val results = mutableListOf<Pair<String, PDRectangle>>()

        val stripper = object : PDFTextStripper() {
            init {
                startPage = pageIndex + 1
                endPage = pageIndex + 1
            }

            override fun writeString(text: String, textPositions: List<TextPosition>) {
                val urlMatcher = URL_PATTERN.matcher(text)
                while (urlMatcher.find()) {
                    val rawStart = urlMatcher.start()
                    val rawEnd = urlMatcher.end()
                    val matchedUrl = urlMatcher.group()

                    val cleaned = cleanUrlMatch(matchedUrl, rawStart, rawEnd) ?: continue
                    val (url, start, end) = cleaned

                    if (start < textPositions.size && end <= textPositions.size && start < end) {
                        val subList = textPositions.subList(start, end)
                        val box = calculateBoundingBox(subList, cropBox)
                        if (box != null) {
                            results.add(Pair(url, box))
                        }
                    }
                }

                val emailMatcher = EMAIL_PATTERN.matcher(text)
                while (emailMatcher.find()) {
                    val rawStart = emailMatcher.start()
                    val rawEnd = emailMatcher.end()
                    val matchedEmail = emailMatcher.group()

                    val cleaned = cleanUrlMatch(matchedEmail, rawStart, rawEnd) ?: continue
                    val (email, start, end) = cleaned

                    if (start < textPositions.size && end <= textPositions.size && start < end) {
                        val subList = textPositions.subList(start, end)
                        val box = calculateBoundingBox(subList, cropBox)
                        if (box != null) {
                            results.add(Pair(email, box))
                        }
                    }
                }
            }

            private fun calculateBoundingBox(
                positions: List<TextPosition>,
                pageCrop: PDRectangle
            ): PDRectangle? {
                if (positions.isEmpty()) return null

                var minX = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE

                for (tp in positions) {
                    val x = pageCrop.lowerLeftX + tp.xDirAdj
                    // In PDFBox, tp.yDirAdj is measured from the top of the cropBox
                    val y = pageCrop.upperRightY - tp.yDirAdj
                    val w = tp.widthDirAdj
                    val h = Math.max(tp.heightDir, tp.fontSizeInPt)

                    if (x < minX) minX = x
                    if (x + w > maxX) maxX = x + w
                    if (y < minY) minY = y
                    if (y + h > maxY) maxY = y + h
                }

                if (minX >= maxX || minY >= maxY) return null

                // Add 1.5pt touch target padding
                val pad = 1.5f
                return PDRectangle().apply {
                    lowerLeftX = minX - pad
                    lowerLeftY = minY - pad
                    upperRightX = maxX + pad
                    upperRightY = maxY + pad
                }
            }
        }

        stripper.getText(doc)
        return results
    }

    private fun isOverlappingExisting(
        target: PDRectangle,
        existingList: List<PDRectangle>
    ): Boolean {
        for (exist in existingList) {
            val overlapX = Math.max(0f, Math.min(target.upperRightX, exist.upperRightX) - Math.max(target.lowerLeftX, exist.lowerLeftX))
            val overlapY = Math.max(0f, Math.min(target.upperRightY, exist.upperRightY) - Math.max(target.lowerLeftY, exist.lowerLeftY))
            val overlapArea = overlapX * overlapY
            val targetArea = target.width * target.height

            if (targetArea > 0 && (overlapArea / targetArea) > 0.4f) {
                return true
            }
        }
        return false
    }
}
