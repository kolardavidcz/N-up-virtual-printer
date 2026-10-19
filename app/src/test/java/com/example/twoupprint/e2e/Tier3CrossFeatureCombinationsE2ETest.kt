package com.example.twoupprint.e2e

import com.example.twoupprint.PdfMerger
import com.example.twoupprint.PrintLayout
import com.example.twoupprint.SpaceDistributionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Tier 3: Cross-Feature Combinations E2E Tests
 *
 * Systematic pairwise combination testing covering:
 * - Layouts (2x1, 1x2, 2x2, 2x3, 2x4)
 * - Best-Fit modes (adaptive true vs standard false)
 * - Margins (0mm, 10mm symmetric, asymmetric margins)
 * - Space distribution modes (CENTER, COMPACT, SPREAD)
 * - Contrast enhancement (true vs false)
 */
class Tier3CrossFeatureCombinationsE2ETest {

    data class MarginConfig(
        val name: String,
        val topMm: Int,
        val bottomMm: Int,
        val leftMm: Int,
        val rightMm: Int
    )

    private val testLayouts = listOf(
        PrintLayout("nup_2x1", "2-Up (2×1)", 2, 1, landscape = true, subPageLandscape = false),
        PrintLayout("nup_1x2", "2-Up (1×2)", 1, 2, landscape = false, subPageLandscape = true),
        PrintLayout("nup_2x2", "4-Up (2×2)", 2, 2, landscape = false, subPageLandscape = false),
        PrintLayout("nup_2x3", "6-Up (2×3)", 2, 3, landscape = false, subPageLandscape = true),
        PrintLayout("nup_2x4", "8-Up (2×4)", 2, 4, landscape = false, subPageLandscape = true)
    )

    private val marginConfigs = listOf(
        MarginConfig("Zero", 0, 0, 0, 0),
        MarginConfig("Symmetric10", 10, 10, 10, 10),
        MarginConfig("Asymmetric", 15, 5, 20, 10)
    )

    private val spaceModes = listOf(
        SpaceDistributionMode.CENTER,
        SpaceDistributionMode.RATIO_2_1,
        SpaceDistributionMode.MAX_MIDDLE
    )

    @Test
    fun testPairwiseLayoutAndBestFitCombinations() {
        val srcPdf16x9 = TestPdfHelper.createTestPresentation(
            pageCount = 6,
            width = 960f,
            height = 540f,
            textPrefix = "PW1"
        )

        for (layout in testLayouts) {
            for (bestFit in listOf(true, false)) {
                val outStream = ByteArrayOutputStream()

                PdfMerger.mergeNUp(
                    sourcePdfStream = ByteArrayInputStream(srcPdf16x9),
                    outputStream = outStream,
                    layout = layout,
                    bestFit = bestFit,
                    marginMm = 5
                )

                val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
                val expectedSheets = Math.ceil(6.0 / layout.pagesPerSheet).toInt()
                assertEquals(
                    "Layout ${layout.printerId} with bestFit=$bestFit must produce $expectedSheets sheets",
                    expectedSheets,
                    outDoc.numberOfPages
                )

                val firstPage = outDoc.getPage(0)
                assertTrue("Width must be positive", firstPage.mediaBox.width > 50f)
                assertTrue("Height must be positive", firstPage.mediaBox.height > 50f)

                if (bestFit && layout.landscape) {
                    // Sheet ratio adapts to grid ratio
                    assertTrue("Best fit landscape width should exceed height", firstPage.mediaBox.width > firstPage.mediaBox.height)
                }

                outDoc.close()
            }
        }
    }

    @Test
    fun testPairwiseMarginsAndSpaceDistribution() {
        val srcPdf = TestPdfHelper.createTestPresentation(
            pageCount = 4,
            width = 595.28f,
            height = 841.89f,
            textPrefix = "Space"
        )
        val layout = PrintLayout("nup_2x2", "2x2", 2, 2, landscape = false)

        for (margin in marginConfigs) {
            for (spaceMode in spaceModes) {
                val outStream = ByteArrayOutputStream()

                PdfMerger.mergeNUp(
                    sourcePdfStream = ByteArrayInputStream(srcPdf),
                    outputStream = outStream,
                    layout = layout,
                    bestFit = false,
                    marginTopMm = margin.topMm,
                    marginBottomMm = margin.bottomMm,
                    marginLeftMm = margin.leftMm,
                    marginRightMm = margin.rightMm,
                    spaceDistributionMode = spaceMode
                )

                val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
                assertEquals(1, outDoc.numberOfPages)

                val sheet = outDoc.getPage(0)
                assertEquals(595.27563f, sheet.mediaBox.width, 1.0f)
                assertEquals(841.8898f, sheet.mediaBox.height, 1.0f)

                val text = TestPdfHelper.extractAllText(outStream.toByteArray())
                assertTrue(
                    "Text must be preserved under ${margin.name} and $spaceMode",
                    text.contains("Space Page 1") && text.contains("Space Page 4")
                )

                outDoc.close()
            }
        }
    }

    @Test
    fun testTextContrastAndLinksCrossMatrix() {
        val srcPdf = TestPdfHelper.createTestPresentation(
            pageCount = 2,
            width = 595.28f,
            height = 841.89f,
            textPrefix = "Contrast"
        )
        val layout = PrintLayout("nup_2x1", "2x1", 2, 1, landscape = true)

        for (addContrast in listOf(false, true)) {
            for (enableLinks in listOf(false, true)) {
                val outStream = ByteArrayOutputStream()

                PdfMerger.mergeNUp(
                    sourcePdfStream = ByteArrayInputStream(srcPdf),
                    outputStream = outStream,
                    layout = layout,
                    addTextContrast = addContrast,
                    enableLinks = enableLinks,
                    bestFit = false,
                    marginMm = 8
                )

                val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
                assertEquals(1, outDoc.numberOfPages)

                val text = TestPdfHelper.extractAllText(outStream.toByteArray())
                assertTrue(text.contains("Contrast Page 1") && text.contains("Contrast Page 2"))

                outDoc.close()
            }
        }
    }
}
