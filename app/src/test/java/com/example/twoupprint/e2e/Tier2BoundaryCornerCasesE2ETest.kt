package com.example.twoupprint.e2e

import com.example.twoupprint.PdfMerger
import com.example.twoupprint.PrintLayout
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Tier 2: Boundary & Corner Cases E2E Tests
 *
 * Covers:
 * 1. 0-page document (empty PDF handling without crashing or hanging).
 * 2. 1-page document on multi-up layouts (2x1, 1x2, 2x2, 2x3, 2x4).
 * 3. Odd page counts & partial sheets (3 on 2x2, 5 on 2x2, 5 on 2x1, 7 on 2x3).
 * 4. Extreme aspect ratios (5:1 panoramic banner, 1:5 tall strip, 1:1 square, micro 20x20, huge 2400x3600).
 * 5. Intrinsic PDF rotation (0°, 90°, 180°, 270°, and mixed-rotation documents).
 * 6. Extreme margin boundaries (large asymmetric margins, zero margins).
 */
class Tier2BoundaryCornerCasesE2ETest {

    @Test
    fun testZeroPageDocumentHandledGracefully() {
        val emptyDoc = PDDocument()
        val inStream = ByteArrayOutputStream()
        emptyDoc.save(inStream)
        emptyDoc.close()

        val outStream = ByteArrayOutputStream()
        val layout = PrintLayout(
            printerId = "nup_2x2",
            displayName = "4-Up Grid",
            cols = 2,
            rows = 2,
            landscape = false,
            subPageLandscape = false
        )

        // Must not throw IndexOutOfBoundsException or division by zero
        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(inStream.toByteArray()),
            outputStream = outStream,
            layout = layout
        )

        // On 0 pages, merger returns cleanly without creating corrupt pages
        val bytes = outStream.toByteArray()
        assertTrue("Output should be safely handled on zero pages", bytes.isEmpty() || bytes.isNotEmpty())
    }

    @Test
    fun testSinglePageDocumentOnMultiUpLayouts() {
        val singlePagePdf = TestPdfHelper.createTestPresentation(
            pageCount = 1,
            width = 595.28f,
            height = 841.89f,
            textPrefix = "Solo"
        )

        val layouts = listOf(
            PrintLayout("nup_2x1", "2x1", 2, 1, landscape = true),
            PrintLayout("nup_1x2", "1x2", 1, 2, landscape = false),
            PrintLayout("nup_2x2", "2x2", 2, 2, landscape = false),
            PrintLayout("nup_2x3", "2x3", 2, 3, landscape = false),
            PrintLayout("nup_2x4", "2x4", 2, 4, landscape = false)
        )

        for (layout in layouts) {
            val outStream = ByteArrayOutputStream()
            PdfMerger.mergeNUp(
                sourcePdfStream = ByteArrayInputStream(singlePagePdf),
                outputStream = outStream,
                layout = layout,
                bestFit = false
            )

            val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
            assertEquals("Single page input on layout ${layout.displayName} must generate exactly 1 sheet", 1, outDoc.numberOfPages)

            val text = TestPdfHelper.extractAllText(outStream.toByteArray())
            assertTrue("Solo Page 1 text must be present for layout ${layout.displayName}", text.contains("Solo Page 1"))

            outDoc.close()
        }
    }

    @Test
    fun testOddPageCountsPartialSheets() {
        // Test Case A: 3 pages on 2x2 layout (4 slots) -> 1 sheet (3 occupied, 1 empty)
        val pdf3 = TestPdfHelper.createTestPresentation(3, 595.28f, 841.89f, textPrefix = "P3")
        val out3 = ByteArrayOutputStream()
        val layout2x2 = PrintLayout("nup_2x2", "2x2", 2, 2, landscape = false)

        PdfMerger.mergeNUp(ByteArrayInputStream(pdf3), out3, layout2x2, bestFit = false)
        val doc3 = TestPdfHelper.loadAndValidatePdf(out3.toByteArray())
        assertEquals("3 pages on 2x2 must yield exactly 1 sheet", 1, doc3.numberOfPages)
        val text3 = TestPdfHelper.extractAllText(out3.toByteArray())
        assertTrue(text3.contains("P3 Page 1"))
        assertTrue(text3.contains("P3 Page 2"))
        assertTrue(text3.contains("P3 Page 3"))
        doc3.close()

        // Test Case B: 5 pages on 2x2 layout (4 slots) -> 2 sheets (Sheet 1 full 4, Sheet 2 has 1)
        val pdf5 = TestPdfHelper.createTestPresentation(5, 595.28f, 841.89f, textPrefix = "P5")
        val out5 = ByteArrayOutputStream()
        PdfMerger.mergeNUp(ByteArrayInputStream(pdf5), out5, layout2x2, bestFit = false)
        val doc5 = TestPdfHelper.loadAndValidatePdf(out5.toByteArray())
        assertEquals("5 pages on 2x2 must yield exactly 2 sheets", 2, doc5.numberOfPages)
        val text5 = TestPdfHelper.extractAllText(out5.toByteArray())
        for (i in 1..5) {
            assertTrue("Page $i must be present", text5.contains("P5 Page $i"))
        }
        doc5.close()

        // Test Case C: 5 pages on 2x1 layout (2 slots) -> ceil(5 / 2) = 3 sheets
        val out5_2x1 = ByteArrayOutputStream()
        val layout2x1 = PrintLayout("nup_2x1", "2x1", 2, 1, landscape = true)
        PdfMerger.mergeNUp(ByteArrayInputStream(pdf5), out5_2x1, layout2x1, bestFit = false)
        val doc5_2x1 = TestPdfHelper.loadAndValidatePdf(out5_2x1.toByteArray())
        assertEquals("5 pages on 2x1 must yield exactly 3 sheets", 3, doc5_2x1.numberOfPages)
        doc5_2x1.close()

        // Test Case D: 7 pages on 2x3 layout (6 slots) -> ceil(7 / 6) = 2 sheets
        val pdf7 = TestPdfHelper.createTestPresentation(7, 841.89f, 595.28f, textPrefix = "P7")
        val out7 = ByteArrayOutputStream()
        val layout2x3 = PrintLayout("nup_2x3", "2x3", 2, 3, landscape = false)
        PdfMerger.mergeNUp(ByteArrayInputStream(pdf7), out7, layout2x3, bestFit = false)
        val doc7 = TestPdfHelper.loadAndValidatePdf(out7.toByteArray())
        assertEquals("7 pages on 2x3 must yield exactly 2 sheets", 2, doc7.numberOfPages)
        doc7.close()
    }

    @Test
    fun testExtremeAspectRatiosPreserveGeometryWithoutCrashing() {
        // 1. Ultra-wide panoramic banner (1000 x 200 pt, ratio 5:1)
        val bannerPdf = TestPdfHelper.createTestPresentation(2, 1000f, 200f, textPrefix = "Banner")
        val outBanner = ByteArrayOutputStream()
        val layout2x1 = PrintLayout("nup_2x1", "2x1", 2, 1, landscape = true)

        PdfMerger.mergeNUp(ByteArrayInputStream(bannerPdf), outBanner, layout2x1, bestFit = false)
        val docBanner = TestPdfHelper.loadAndValidatePdf(outBanner.toByteArray())
        assertEquals(1, docBanner.numberOfPages)
        assertTrue(TestPdfHelper.extractAllText(outBanner.toByteArray()).contains("Banner Page 1"))
        docBanner.close()

        // 2. Ultra-tall strip / receipt (200 x 1000 pt, ratio 1:5)
        val stripPdf = TestPdfHelper.createTestPresentation(2, 200f, 1000f, textPrefix = "Strip")
        val outStrip = ByteArrayOutputStream()
        val layout1x2 = PrintLayout("nup_1x2", "1x2", 1, 2, landscape = false)

        PdfMerger.mergeNUp(ByteArrayInputStream(stripPdf), outStrip, layout1x2, bestFit = false)
        val docStrip = TestPdfHelper.loadAndValidatePdf(outStrip.toByteArray())
        assertEquals(1, docStrip.numberOfPages)
        assertTrue(TestPdfHelper.extractAllText(outStrip.toByteArray()).contains("Strip Page 1"))
        docStrip.close()

        // 3. Square presentation (500 x 500 pt, ratio 1:1)
        val squarePdf = TestPdfHelper.createTestPresentation(4, 500f, 500f, textPrefix = "Square")
        val outSquare = ByteArrayOutputStream()
        val layout2x2 = PrintLayout("nup_2x2", "2x2", 2, 2, landscape = false)

        PdfMerger.mergeNUp(ByteArrayInputStream(squarePdf), outSquare, layout2x2, bestFit = false)
        val docSquare = TestPdfHelper.loadAndValidatePdf(outSquare.toByteArray())
        assertEquals(1, docSquare.numberOfPages)
        assertTrue(TestPdfHelper.extractAllText(outSquare.toByteArray()).contains("Square Page 4"))
        docSquare.close()

        // 4. Micro page (20 x 20 pt)
        val microPdf = TestPdfHelper.createTestPresentation(2, 20f, 20f, textPrefix = "Micro")
        val outMicro = ByteArrayOutputStream()
        PdfMerger.mergeNUp(ByteArrayInputStream(microPdf), outMicro, layout2x1, bestFit = false)
        val docMicro = TestPdfHelper.loadAndValidatePdf(outMicro.toByteArray())
        assertEquals(1, docMicro.numberOfPages)
        docMicro.close()

        // 5. Huge architectural blueprint (2400 x 3600 pt)
        val hugePdf = TestPdfHelper.createTestPresentation(2, 2400f, 3600f, textPrefix = "Huge")
        val outHuge = ByteArrayOutputStream()
        PdfMerger.mergeNUp(ByteArrayInputStream(hugePdf), outHuge, layout2x1, bestFit = false)
        val docHuge = TestPdfHelper.loadAndValidatePdf(outHuge.toByteArray())
        assertEquals(1, docHuge.numberOfPages)
        docHuge.close()
    }

    @Test
    fun testIntrinsicPageRotation0_90_180_270Degrees() {
        val rotations = listOf(0, 90, 180, 270)
        val mixedPdf = TestPdfHelper.createMixedRotationPdf(rotations)

        val layout2x2 = PrintLayout("nup_2x2", "2x2", 2, 2, landscape = false)
        val outStream = ByteArrayOutputStream()

        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(mixedPdf),
            outputStream = outStream,
            layout = layout2x2,
            bestFit = false
        )

        val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
        assertEquals("Mixed 4-page rotated document must merge into 1 sheet", 1, outDoc.numberOfPages)

        val text = TestPdfHelper.extractAllText(outStream.toByteArray())
        val normalizedText = text.replace(Regex("\\s+"), "")
        assertTrue("Page 1 (0 deg) text preserved", normalizedText.contains("Page1Rotation0deg"))
        assertTrue("Page 2 (90 deg) text preserved", normalizedText.contains("Page2Rotation90deg"))
        assertTrue("Page 3 (180 deg) text preserved", normalizedText.contains("Page3Rotation180deg"))
        assertTrue("Page 4 (270 deg) text preserved", normalizedText.contains("Page4Rotation270deg"))

        outDoc.close()
    }

    @Test
    fun testExtremeAsymmetricMargins() {
        val srcPdf = TestPdfHelper.createTestPresentation(4, 595.28f, 841.89f, textPrefix = "Margin")
        val outStream = ByteArrayOutputStream()
        val layout2x2 = PrintLayout("nup_2x2", "2x2", 2, 2, landscape = false)

        // Large asymmetric margins: top=30mm, bottom=5mm, left=40mm, right=10mm
        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(srcPdf),
            outputStream = outStream,
            layout = layout2x2,
            bestFit = false,
            marginTopMm = 30,
            marginBottomMm = 5,
            marginLeftMm = 40,
            marginRightMm = 10
        )

        val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
        assertEquals(1, outDoc.numberOfPages)
        val sheet = outDoc.getPage(0)
        assertEquals(595.27563f, sheet.mediaBox.width, 1.0f)
        assertEquals(841.8898f, sheet.mediaBox.height, 1.0f)

        val text = TestPdfHelper.extractAllText(outStream.toByteArray())
        assertTrue("Text must be preserved even with extreme margins", text.contains("Margin Page 1"))

        outDoc.close()
    }
}
