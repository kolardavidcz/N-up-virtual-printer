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

    // Comprehensive URL pattern matching full URLs, www.* domains, and common TLD domains
    private val URL_PATTERN = Pattern.compile(
        """(?i)\b(?:https?://|www\.|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|org|net|edu|gov|cz|sk|eu|io|ai|de|uk|info|dev|app|me|co|cz))\b(?:/[^\s<>"{}|\\^`\[\]]*)?"""
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
        layout: PrintLayout
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
                            layout
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
                    layout
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
     * Transforms a source rectangle [x1, y1, x2, y2] to the destination N-up slot.
     */
    private fun transformRect(
        srcRect: PDRectangle,
        cropBox: PDRectangle,
        slotLeft: Float,
        slotBottom: Float,
        slotWidth: Float,
        slotHeight: Float,
        layout: PrintLayout
    ): PDRectangle {
        val srcW = cropBox.width
        val srcH = cropBox.height

        val srcIsLandscape = srcW > srcH
        val targetIsLandscape = layout.subPageLandscape
        val needsRotation = (srcIsLandscape != targetIsLandscape)

        val x1 = srcRect.lowerLeftX
        val y1 = srcRect.lowerLeftY
        val x2 = srcRect.upperRightX
        val y2 = srcRect.upperRightY

        if (needsRotation) {
            // 90° rotation transformation matching Matrix(0f, s, -s, 0f, e, f)
            val rotatedW = srcH
            val rotatedH = srcW
            val scale = Math.min(slotWidth / rotatedW, slotHeight / rotatedH)

            val destW = rotatedW * scale
            val destH = rotatedH * scale

            val tx = slotLeft + (slotWidth - destW) / 2f
            val ty = slotBottom + (slotHeight - destH) / 2f

            val e = tx + destW + scale * cropBox.lowerLeftY
            val f = ty - scale * cropBox.lowerLeftX

            // Mapping: X = -scale * y + e, Y = scale * x + f
            val xA = -scale * y1 + e
            val xB = -scale * y2 + e
            val yA = scale * x1 + f
            val yB = scale * x2 + f

            val minX = Math.min(xA, xB)
            val maxX = Math.max(xA, xB)
            val minY = Math.min(yA, yB)
            val maxY = Math.max(yA, yB)

            return PDRectangle().apply {
                lowerLeftX = minX
                lowerLeftY = minY
                upperRightX = maxX
                upperRightY = maxY
            }
        } else {
            // Standard fitting without rotation matching Matrix(s, 0f, 0f, s, tx, ty)
            val scale = Math.min(slotWidth / srcW, slotHeight / srcH)

            val destW = srcW * scale
            val destH = srcH * scale

            val tx = slotLeft + (slotWidth - destW) / 2f - cropBox.lowerLeftX * scale
            val ty = slotBottom + (slotHeight - destH) / 2f - cropBox.lowerLeftY * scale

            val minX = x1 * scale + tx
            val maxX = x2 * scale + tx
            val minY = y1 * scale + ty
            val maxY = y2 * scale + ty

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
                    val start = urlMatcher.start()
                    val end = urlMatcher.end()
                    val matchedUrl = urlMatcher.group()

                    if (start < textPositions.size && end <= textPositions.size) {
                        val subList = textPositions.subList(start, end)
                        val box = calculateBoundingBox(subList, cropBox)
                        if (box != null) {
                            results.add(Pair(matchedUrl, box))
                        }
                    }
                }

                val emailMatcher = EMAIL_PATTERN.matcher(text)
                while (emailMatcher.find()) {
                    val start = emailMatcher.start()
                    val end = emailMatcher.end()
                    val matchedEmail = emailMatcher.group()

                    if (start < textPositions.size && end <= textPositions.size) {
                        val subList = textPositions.subList(start, end)
                        val box = calculateBoundingBox(subList, cropBox)
                        if (box != null) {
                            results.add(Pair(matchedEmail, box))
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
