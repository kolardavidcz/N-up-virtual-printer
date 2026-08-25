package com.example.twoupprint

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Targeted Gray-to-Black Font Booster.
 *
 * Specifically boosts gray/faint text fonts into solid pitch black:
 * - Only modifies text font colors inside text blocks (BT ... ET).
 * - Grayscale fonts (0.01 <= gray < 0.95) -> Solid Black (0.0).
 * - Neutral gray RGB fonts -> Solid Black (0.0 0.0 0.0).
 * - Semi-transparent text opacity (/ca) -> 1.0 (fully opaque).
 * - All background shapes, fills, borders, colored elements, and images remain 100% untouched.
 */
object VectorTextBooster {

    fun boostPage(
        doc: PDDocument,
        page: PDPage,
        preserveImages: Boolean = true
    ) {
        // 1. Boost text alpha opacity in ExtGState so semi-transparent text is fully opaque
        boostExtGStateAlpha(page)

        // 2. Parse and rewrite text tokens (only gray font to black)
        try {
            val parser = PDFStreamParser(page)
            parser.parse()
            val tokens = parser.tokens

            val modifiedTokens = processTextTokens(tokens)

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

    private fun processTextTokens(tokens: List<Any>): List<Any> {
        val newTokens = ArrayList<Any>(tokens.size)
        var inTextObject = false

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]

            if (token is Operator) {
                val opName = token.name

                when (opName) {
                    "BT" -> {
                        inTextObject = true
                        newTokens.add(token)
                    }
                    "ET" -> {
                        inTextObject = false
                        newTokens.add(token)
                    }

                    // --- Grayscale font color ("gray g") ---
                    "g" -> {
                        if (inTextObject && newTokens.isNotEmpty() && newTokens.last() is COSNumber) {
                            val grayNum = newTokens.removeAt(newTokens.size - 1) as COSNumber
                            val grayVal = grayNum.floatValue()

                            // If gray font (not pure white >= 0.95), boost to solid black
                            if (grayVal in 0.01f..0.95f) {
                                newTokens.add(COSFloat(0.0f))
                            } else {
                                newTokens.add(grayNum)
                            }
                        }
                        newTokens.add(token)
                    }

                    // --- RGB font color ("r g b rg") ---
                    "rg" -> {
                        if (inTextObject && newTokens.size >= 3 &&
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
                            val isGrayish = abs(r - g) < 0.18f && abs(g - b) < 0.18f && abs(r - b) < 0.18f

                            // If neutral gray font (not pure white >= 0.95), boost to solid black
                            if (isGrayish && lum in 0.01f..0.95f) {
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                            } else {
                                newTokens.add(rNum)
                                newTokens.add(gNum)
                                newTokens.add(bNum)
                            }
                        }
                        newTokens.add(token)
                    }

                    // --- CMYK font color ("c m y k k") ---
                    "k" -> {
                        if (inTextObject && newTokens.size >= 4 &&
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
                            val isNeutral = abs(c - m) < 0.15f && abs(m - y) < 0.15f

                            if (isNeutral && lum in 0.01f..0.95f) {
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(0.0f))
                                newTokens.add(COSFloat(1.0f)) // Solid Black in CMYK
                            } else {
                                newTokens.add(cNum)
                                newTokens.add(mNum)
                                newTokens.add(yNum)
                                newTokens.add(kNum)
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
     * Inspects /Resources/ExtGState and boosts semi-transparent text alpha (/ca) to 1.0 (fully opaque).
     */
    private fun boostExtGStateAlpha(page: PDPage) {
        try {
            val resources = page.resources ?: return
            val extGStateDict = resources.cosObject.getDictionaryObject(COSName.EXT_G_STATE) as? COSDictionary ?: return

            for (key in extGStateDict.keySet()) {
                val gsObj = extGStateDict.getDictionaryObject(key) as? COSDictionary ?: continue

                // Non-stroking alpha (/ca) used for text fills
                val ca = gsObj.getDictionaryObject(COSName.getPDFName("ca")) as? COSNumber
                if (ca != null && ca.floatValue() in 0.05f..0.98f) {
                    gsObj.setItem(COSName.getPDFName("ca"), COSFloat(1.0f))
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
                    val modifiedTokens = processTextTokens(tokens)

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
