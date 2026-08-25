package com.example.twoupprint

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * Context-Aware Semantic Vector Stream Transformer for maximum text contrast
 * with near-zero false positives (e.g., dark code blocks) and false negatives (e.g., faint math/notes).
 *
 * Distinguishes by exact PDF grammar:
 * - Text Objects (BT ... ET): Snapped to Solid Black on light backgrounds, or Pure White in dark code blocks.
 * - Vector Strokes (S, s): Math symbols, fractions, square roots, brackets boosted to Solid Black.
 * - Vector Fills (f, f*, re): Light/medium background shading wiped to Pure White (saving toner),
 *   dark banners/boxes snapped to Deep Black.
 * - ExtGState Alpha (/ca, /CA): Boosts opacity to 1.0 (fully opaque).
 * - Images (/XObject /Image): 100% untouched in original full color.
 */
object VectorTextBooster {

    /**
     * Boosts text, math formulas, and lines on [page] in [doc].
     */
    fun boostPage(
        doc: PDDocument,
        page: PDPage,
        preserveImages: Boolean = true
    ) {
        // 1. Boost Graphic State Alpha / Opacity (/Resources/ExtGState)
        boostExtGStateAlpha(page)

        // 2. Parse and rewrite page content stream tokens with Semantic Grammar State Machine
        try {
            val parser = PDFStreamParser(page)
            parser.parse()
            val tokens = parser.tokens

            val modifiedTokens = processSemanticTokens(tokens)

            val byteOut = ByteArrayOutputStream()
            val writer = ContentStreamWriter(byteOut)
            writer.writeTokens(modifiedTokens)

            val newStream = PDStream(doc, byteOut.toByteArray().inputStream())
            page.setContents(newStream)
        } catch (_: Exception) {
            // Preserve original stream if non-standard encoding occurs
        }

        // 3. Recursively process nested Form XObjects
        processFormXObjects(doc, page)
    }

    private data class GraphicsState(
        var nonStrokingLum: Float = 0.0f,
        var strokingLum: Float = 0.0f,
        var activeBgLum: Float = 1.0f
    )

    private fun processSemanticTokens(tokens: List<Any>): List<Any> {
        val newTokens = ArrayList<Any>(tokens.size)
        val stateStack = ArrayDeque<GraphicsState>()
        var currentState = GraphicsState()

        var inTextObject = false
        var activeBackgroundLuminance = 1.0f // Default white paper

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]

            if (token is Operator) {
                val opName = token.name

                when (opName) {
                    // --- Text Object Scope ---
                    "BT" -> {
                        inTextObject = true
                        newTokens.add(token)
                    }
                    "ET" -> {
                        inTextObject = false
                        newTokens.add(token)
                    }

                    // --- Graphics State Push / Pop ---
                    "q" -> {
                        stateStack.push(currentState.copy())
                        newTokens.add(token)
                    }
                    "Q" -> {
                        if (stateStack.isNotEmpty()) {
                            currentState = stateStack.pop()
                            activeBackgroundLuminance = currentState.activeBgLum
                        }
                        newTokens.add(token)
                    }

                    // --- Fill Shape Operations (re, f, f*, B, B*) ---
                    "f", "f*" -> {
                        // We just executed a background fill
                        activeBackgroundLuminance = currentState.nonStrokingLum
                        currentState.activeBgLum = activeBackgroundLuminance
                        newTokens.add(token)
                    }
                    "B", "B*" -> {
                        // Fill and Stroke together
                        activeBackgroundLuminance = currentState.nonStrokingLum
                        currentState.activeBgLum = activeBackgroundLuminance
                        newTokens.add(token)
                    }

                    // --- Non-stroking & Stroking Grayscale (e.g. "0.4 g" or "0.4 G") ---
                    "g" -> {
                        if (newTokens.isNotEmpty() && newTokens.last() is COSNumber) {
                            val grayNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val origGray = grayNum.floatValue()
                            val mappedGray = mapNonStrokingLuminance(origGray, inTextObject, activeBackgroundLuminance)
                            currentState.nonStrokingLum = mappedGray
                            newTokens.add(COSFloat(mappedGray))
                        }
                        newTokens.add(token)
                    }
                    "G" -> {
                        if (newTokens.isNotEmpty() && newTokens.last() is COSNumber) {
                            val grayNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val origGray = grayNum.floatValue()
                            val mappedGray = mapStrokingLuminance(origGray)
                            currentState.strokingLum = mappedGray
                            newTokens.add(COSFloat(mappedGray))
                        }
                        newTokens.add(token)
                    }

                    // --- Non-stroking & Stroking RGB (e.g. "r g b rg" or "r g b RG") ---
                    "rg" -> {
                        if (newTokens.size >= 3 &&
                            newTokens[newTokens.size - 1] is COSNumber &&
                            newTokens[newTokens.size - 2] is COSNumber &&
                            newTokens[newTokens.size - 3] is COSNumber
                        ) {
                            val bNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val gNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val rNum = newTokens.removeAt(newTokens.size - 1) as COSNumber

                            val r = rNum.floatValue()
                            val g = gNum.floatValue()
                            val b = bNum.floatValue()

                            val lum = 0.299f * r + 0.587f * g + 0.114f * b
                            val mappedLum = mapNonStrokingLuminance(lum, inTextObject, activeBackgroundLuminance)
                            currentState.nonStrokingLum = mappedLum

                            newTokens.add(COSFloat(mappedLum))
                            newTokens.add(COSFloat(mappedLum))
                            newTokens.add(COSFloat(mappedLum))
                        }
                        newTokens.add(token)
                    }
                    "RG" -> {
                        if (newTokens.size >= 3 &&
                            newTokens[newTokens.size - 1] is COSNumber &&
                            newTokens[newTokens.size - 2] is COSNumber &&
                            newTokens[newTokens.size - 3] is COSNumber
                        ) {
                            val bNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val gNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val rNum = newTokens.removeAt(newTokens.size - 1) as COSNumber

                            val r = rNum.floatValue()
                            val g = gNum.floatValue()
                            val b = bNum.floatValue()

                            val lum = 0.299f * r + 0.587f * g + 0.114f * b
                            val mappedLum = mapStrokingLuminance(lum)
                            currentState.strokingLum = mappedLum

                            newTokens.add(COSFloat(mappedLum))
                            newTokens.add(COSFloat(mappedLum))
                            newTokens.add(COSFloat(mappedLum))
                        }
                        newTokens.add(token)
                    }

                    // --- Non-stroking & Stroking CMYK (e.g. "c m y k k" or "c m y k K") ---
                    "k" -> {
                        if (newTokens.size >= 4 &&
                            newTokens[newTokens.size - 1] is COSNumber &&
                            newTokens[newTokens.size - 2] is COSNumber &&
                            newTokens[newTokens.size - 3] is COSNumber &&
                            newTokens[newTokens.size - 4] is COSNumber
                        ) {
                            val kNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val yNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val mNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val cNum = newTokens.removeAt(newTokens.size - 1) as COSNumber

                            val c = cNum.floatValue()
                            val m = mNum.floatValue()
                            val y = yNum.floatValue()
                            val kVal = kNum.floatValue()

                            val lum = 1.0f - kVal - (0.299f * c + 0.587f * m + 0.114f * y)
                            val mappedLum = mapNonStrokingLuminance(lum, inTextObject, activeBackgroundLuminance)
                            currentState.nonStrokingLum = mappedLum

                            if (mappedLum == 0.0f) {
                                // Solid Black in CMYK
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(1.0f))
                            } else {
                                // Pure White in CMYK
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                            }
                        }
                        newTokens.add(token)
                    }
                    "K" -> {
                        if (newTokens.size >= 4 &&
                            newTokens[newTokens.size - 1] is COSNumber &&
                            newTokens[newTokens.size - 2] is COSNumber &&
                            newTokens[newTokens.size - 3] is COSNumber &&
                            newTokens[newTokens.size - 4] is COSNumber
                        ) {
                            val kNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val yNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val mNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val cNum = newTokens.removeAt(newTokens.size - 1) as COSNumber

                            val c = cNum.floatValue()
                            val m = mNum.floatValue()
                            val y = yNum.floatValue()
                            val kVal = kNum.floatValue()

                            val lum = 1.0f - kVal - (0.299f * c + 0.587f * m + 0.114f * y)
                            val mappedLum = mapStrokingLuminance(lum)
                            currentState.strokingLum = mappedLum

                            if (mappedLum == 0.0f) {
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(1.0f))
                            } else {
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                            }
                        }
                        newTokens.add(token)
                    }

                    else -> {
                        newTokens.add(token)
                    }
                }
            } else {
                newTokens.add(token)
            }
            i++
        }
        return newTokens
    }

    /**
     * Maps non-stroking (fill) color based on whether we are inside text or a background shape:
     * - Inside Text:
     *   - Over Light Background (>= 0.50): Any text Y < 0.96 -> Solid Black (0.0).
     *   - Over Dark Background (< 0.50): Light syntax text Y >= 0.20 -> Pure White (1.0).
     * - Outside Text (Background Shapes/Fills):
     *   - Light/Medium Fills (>= 0.48): Wiped to Pure White (1.0) to save ink.
     *   - Dark Fills (< 0.48): Snapped to Solid Black (0.0).
     */
    private fun mapNonStrokingLuminance(
        lum: Float,
        inText: Boolean,
        activeBgLum: Float
    ): Float {
        return if (inText) {
            if (activeBgLum >= 0.50f) {
                // Text over Light / White Background: Boost any non-white font to solid black
                if (lum < 0.96f) 0.0f else 1.0f
            } else {
                // Text inside Dark Code Block / Terminal Box:
                // If text is lighter than the dark background, snap to pure white
                if (lum >= 0.20f) 1.0f else 0.0f
            }
        } else {
            // Background fill / shape:
            // Light/Medium fills (>= 0.48, like zebra stripes, gray cards) -> Pure White
            // Dark boxes (< 0.48) -> Solid Deep Black
            if (lum >= 0.48f) 1.0f else 0.0f
        }
    }

    /**
     * Maps stroking color (vector lines, square roots, fraction bars, coordinate axes, brackets):
     * Almost never background; boost anything darker than pure white to solid black.
     */
    private fun mapStrokingLuminance(lum: Float): Float {
        return if (lum < 0.92f) 0.0f else 1.0f
    }

    /**
     * Inspects /Resources/ExtGState dictionaries and boosts semi-transparent alpha values
     * (/ca for text/fill and /CA for stroke) to 1.0 (fully opaque).
     */
    private fun boostExtGStateAlpha(page: PDPage) {
        try {
            val resources = page.resources ?: return
            val extGStateDict = resources.cosObject.getDictionaryObject(COSName.EXT_G_STATE) as? COSDictionary ?: return

            for (key in extGStateDict.keySet()) {
                val gsObj = extGStateDict.getDictionaryObject(key) as? COSDictionary ?: continue

                // Non-stroking alpha (/ca)
                val ca = gsObj.getDictionaryObject(COSName.getPDFName("ca")) as? COSNumber
                if (ca != null && ca.floatValue() in 0.05f..0.98f) {
                    gsObj.setItem(COSName.getPDFName("ca"), COSFloat(1.0f))
                }

                // Stroking alpha (/CA)
                val caStroke = gsObj.getDictionaryObject(COSName.getPDFName("CA")) as? COSNumber
                if (caStroke != null && caStroke.floatValue() in 0.05f..0.98f) {
                    gsObj.setItem(COSName.getPDFName("CA"), COSFloat(1.0f))
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Recursively processes sub-form XObjects in /Resources/XObject.
     */
    private fun processFormXObjects(doc: PDDocument, page: PDPage) {
        try {
            val resources = page.resources ?: return
            for (name in resources.xObjectNames) {
                val xObj = resources.getXObject(name)
                if (xObj is PDFormXObject) {
                    val parser = PDFStreamParser(xObj)
                    parser.parse()
                    val tokens = parser.tokens
                    val modifiedTokens = processSemanticTokens(tokens)

                    val byteOut = ByteArrayOutputStream()
                    val writer = ContentStreamWriter(byteOut)
                    writer.writeTokens(modifiedTokens)

                    val newStream = PDStream(doc, byteOut.toByteArray().inputStream())
                    xObj.cosObject.setItem(COSName.CONTENTS, newStream.cosObject)
                }
            }
        } catch (_: Exception) { }
    }
}
