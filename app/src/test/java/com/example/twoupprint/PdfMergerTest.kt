package com.example.twoupprint

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PdfMergerTest {

    private fun createTestPresentation(pageCount: Int, width: Float, height: Float): ByteArray {
        val doc = PDDocument()
        for (i in 0 until pageCount) {
            val page = PDPage(PDRectangle(width, height))
            doc.addPage(page)
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    @Test
    fun testBestFit16x9_4Up() {
        // 16:9 slides: 960 x 540 pt
        val srcBytes = createTestPresentation(4, 960f, 540f)
        val outStream = ByteArrayOutputStream()

        val layout = PrintLayout(
            printerId = "grid_2x2",
            displayName = "4-Up Grid (2x2)",
            cols = 2,
            rows = 2,
            landscape = true,
            subPageLandscape = true
        )

        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(srcBytes),
            outputStream = outStream,
            layout = layout,
            addTextContrast = false,
            enableLinks = false,
            bestFit = true,
            marginMm = 0
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals(1, outDoc.numberOfPages)

        val outPage = outDoc.getPage(0)
        val w = outPage.mediaBox.width
        val h = outPage.mediaBox.height

        // 16:9 ratio: 960 / 540 = 1.7778
        // Sheet width is 841.89 pt, so sheet height should be 841.89 / (16/9) = 473.56 pt
        val ratio = w / h
        assertEquals(16f / 9f, ratio, 0.01f)
        assertEquals(841.8898f, w, 0.5f)
        assertEquals(473.56f, h, 0.5f)

        outDoc.close()
    }

    @Test
    fun testBestFit4x3_4Up() {
        // 4:3 slides: 1024 x 768 pt
        val srcBytes = createTestPresentation(4, 1024f, 768f)
        val outStream = ByteArrayOutputStream()

        val layout = PrintLayout(
            printerId = "grid_2x2",
            displayName = "4-Up Grid (2x2)",
            cols = 2,
            rows = 2,
            landscape = true,
            subPageLandscape = true
        )

        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(srcBytes),
            outputStream = outStream,
            layout = layout,
            addTextContrast = false,
            enableLinks = false,
            bestFit = true,
            marginMm = 0
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals(1, outDoc.numberOfPages)

        val outPage = outDoc.getPage(0)
        val w = outPage.mediaBox.width
        val h = outPage.mediaBox.height

        // 4:3 ratio: 1.3333
        val ratio = w / h
        assertEquals(4f / 3f, ratio, 0.01f)

        outDoc.close()
    }

    @Test
    fun testFixedA4WhenBestFitDisabled() {
        // 16:9 slides: 960 x 540 pt, but Best Fit is disabled
        val srcBytes = createTestPresentation(4, 960f, 540f)
        val outStream = ByteArrayOutputStream()

        val layout = PrintLayout(
            printerId = "grid_2x2",
            displayName = "4-Up Grid (2x2)",
            cols = 2,
            rows = 2,
            landscape = true,
            subPageLandscape = true
        )

        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(srcBytes),
            outputStream = outStream,
            layout = layout,
            addTextContrast = false,
            enableLinks = false,
            bestFit = false,
            marginMm = 0
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals(1, outDoc.numberOfPages)

        val outPage = outDoc.getPage(0)
        val w = outPage.mediaBox.width
        val h = outPage.mediaBox.height

        // Should strictly be A4 Landscape: 841.89 x 595.28 pt
        assertEquals(PDRectangle.A4.height, w, 0.5f)
        assertEquals(PDRectangle.A4.width, h, 0.5f)

        outDoc.close()
    }

    @Test
    fun testMarginsPreserveAspectRatio() {
        // 16:9 slides with 3mm margin
        val srcBytes = createTestPresentation(4, 960f, 540f)
        val outStream = ByteArrayOutputStream()

        val layout = PrintLayout(
            printerId = "grid_2x2",
            displayName = "4-Up Grid (2x2)",
            cols = 2,
            rows = 2,
            landscape = true,
            subPageLandscape = true
        )

        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(srcBytes),
            outputStream = outStream,
            layout = layout,
            addTextContrast = false,
            enableLinks = false,
            bestFit = true,
            marginMm = 3
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals(1, outDoc.numberOfPages)

        val outPage = outDoc.getPage(0)
        assertTrue(outPage.mediaBox.width > 0)
        assertTrue(outPage.mediaBox.height > 0)

        outDoc.close()
    }

    @Test
    fun testAsymmetricMargins() {
        // 16:9 slides with Top=6mm, Bottom=0mm, Left=3mm, Right=3mm
        val srcBytes = createTestPresentation(4, 960f, 540f)
        val outStream = ByteArrayOutputStream()

        val layout = PrintLayout(
            printerId = "grid_2x2",
            displayName = "4-Up Grid (2x2)",
            cols = 2,
            rows = 2,
            landscape = true,
            subPageLandscape = true
        )

        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(srcBytes),
            outputStream = outStream,
            layout = layout,
            addTextContrast = false,
            enableLinks = false,
            bestFit = true,
            marginTopMm = 6,
            marginBottomMm = 0,
            marginLeftMm = 3,
            marginRightMm = 3
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals(1, outDoc.numberOfPages)

        val outPage = outDoc.getPage(0)
        assertTrue(outPage.mediaBox.width > 0)
        assertTrue(outPage.mediaBox.height > 0)

        outDoc.close()
    }

    @Test
    fun testFixedA4PortraitWhenBestFitDisabled() {
        val srcBytes = createTestPresentation(4, 960f, 540f)
        val outStream = ByteArrayOutputStream()

        val layout = PrintLayout(
            printerId = "grid_2x2",
            displayName = "4-Up Grid (2x2)",
            cols = 2,
            rows = 2,
            landscape = false, // Sheet Portrait
            subPageLandscape = false
        )

        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(srcBytes),
            outputStream = outStream,
            layout = layout,
            addTextContrast = false,
            enableLinks = false,
            bestFit = false,
            marginMm = 0
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals(1, outDoc.numberOfPages)

        val outPage = outDoc.getPage(0)
        val w = outPage.mediaBox.width
        val h = outPage.mediaBox.height

        // Should strictly be A4 Portrait: 595.28 x 841.89 pt
        assertEquals(PDRectangle.A4.width, w, 0.5f)
        assertEquals(PDRectangle.A4.height, h, 0.5f)

        outDoc.close()
    }
}
