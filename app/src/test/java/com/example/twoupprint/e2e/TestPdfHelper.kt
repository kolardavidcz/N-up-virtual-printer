package com.example.twoupprint.e2e

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reusable test PDF generation and verification utilities for E2E tests.
 */
object TestPdfHelper {

    /**
     * Resolves a sample PDF by name across possible execution roots.
     */
    fun findSamplePdf(fileName: String): File {
        val candidates = listOf(
            File(fileName),
            File("..", fileName),
            File("C:\\Users\\kolar\\Desktop\\windows_native_projects\\TwoUpPrintv_v2", fileName),
            File("C:\\Users\\kolar\\Desktop\\TwoUpPrintv_v2", fileName)
        )
        for (candidate in candidates) {
            if (candidate.exists() && candidate.isFile && candidate.length() > 0) {
                return candidate
            }
        }
        throw IllegalArgumentException("Sample PDF not found: $fileName across candidates: $candidates")
    }

    /**
     * Creates an in-memory PDF presentation with the specified page count, dimensions, and rotation.
     */
    fun createTestPresentation(
        pageCount: Int,
        width: Float,
        height: Float,
        rotation: Int = 0,
        addText: Boolean = true,
        textPrefix: String = "Slide"
    ): ByteArray {
        val doc = PDDocument()
        for (i in 0 until pageCount) {
            val page = PDPage(PDRectangle(width, height))
            if (rotation != 0) {
                page.rotation = rotation
            }
            doc.addPage(page)

            if (addText) {
                val contentStream = PDPageContentStream(doc, page)
                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16f)
                val tx = Math.max(20f, width * 0.1f)
                val ty = Math.max(20f, height * 0.5f)
                contentStream.newLineAtOffset(tx, ty)
                contentStream.showText("$textPrefix Page ${i + 1}")
                contentStream.endText()
                contentStream.close()
            }
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    /**
     * Creates a PDF with mixed rotations across pages (e.g. 0, 90, 180, 270).
     */
    fun createMixedRotationPdf(
        rotations: List<Int>,
        width: Float = 595.27563f,
        height: Float = 841.8898f
    ): ByteArray {
        val doc = PDDocument()
        for ((idx, rot) in rotations.withIndex()) {
            val page = PDPage(PDRectangle(width, height))
            page.rotation = rot
            doc.addPage(page)

            val contentStream = PDPageContentStream(doc, page)
            contentStream.beginText()
            contentStream.setFont(PDType1Font.HELVETICA_BOLD, 14f)
            contentStream.newLineAtOffset(50f, 400f)
            contentStream.showText("Page ${idx + 1} Rotation $rot deg")
            contentStream.endText()
            contentStream.close()
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    /**
     * Extracts all text from a generated PDF byte array using PDFTextStripper.
     */
    fun extractAllText(pdfBytes: ByteArray): String {
        PDDocument.load(ByteArrayInputStream(pdfBytes)).use { doc ->
            val stripper = PDFTextStripper()
            return stripper.getText(doc)
        }
    }

    /**
     * Loads and validates that a PDF byte array is a structurally valid PDF.
     */
    fun loadAndValidatePdf(pdfBytes: ByteArray): PDDocument {
        require(pdfBytes.isNotEmpty()) { "PDF byte array must not be empty" }
        return PDDocument.load(ByteArrayInputStream(pdfBytes))
    }
}
