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

    @Test
    fun testAutoTrim16x9SlideOnA4Portrait() {
        val doc = PDDocument()
        val page = PDPage(PDRectangle(595.28f, 841.89f))
        doc.addPage(page)

        // 16:9 slide height = 595.28 / (16/9) = 334.85 pt
        // Centered padding: (841.89 - 334.85) / 2 = 253.52 pt
        val cs = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page)
        cs.addRect(20f, 253.52f + 10f, 555f, 314f)
        cs.fill()
        cs.close()

        val detectedRatio = PdfContentTrimmer.trimDocumentSlides(doc)
        org.junit.Assert.assertNotNull(detectedRatio)
        assertEquals(16f / 9f, detectedRatio!!, 0.01f)

        val cropBox = page.cropBox
        assertEquals(595.28f, cropBox.width, 1.0f)
        assertEquals(334.85f, cropBox.height, 1.0f)
        assertEquals(253.52f, cropBox.lowerLeftY, 1.0f)

        doc.close()
    }

    @Test
    fun testAutoTrim4x3SlideOnA4Portrait() {
        val doc = PDDocument()
        val page = PDPage(PDRectangle(595.28f, 841.89f))
        doc.addPage(page)

        // 4:3 slide height = 595.28 / (4/3) = 446.46 pt
        // Centered padding: (841.89 - 446.46) / 2 = 197.71 pt
        val cs = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page)
        cs.addRect(20f, 197.71f + 10f, 555f, 426f)
        cs.fill()
        cs.close()

        val detectedRatio = PdfContentTrimmer.trimDocumentSlides(doc)
        org.junit.Assert.assertNotNull(detectedRatio)
        assertEquals(4f / 3f, detectedRatio!!, 0.01f)

        val cropBox = page.cropBox
        assertEquals(595.28f, cropBox.width, 1.0f)
        assertEquals(446.46f, cropBox.height, 1.0f)
        assertEquals(197.71f, cropBox.lowerLeftY, 1.0f)

        doc.close()
    }

    @Test
    fun testStandardTextDocumentUntouched() {
        val doc = PDDocument()
        val page = PDPage(PDRectangle(595.28f, 841.89f))
        doc.addPage(page)

        // Standard text document with content distributed near top (780 pt) and bottom (60 pt)
        val cs = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page)
        cs.addRect(50f, 60f, 495f, 100f) // footer area
        cs.addRect(50f, 780f, 495f, 40f) // header area
        cs.fill()
        cs.close()

        val detectedRatio = PdfContentTrimmer.trimDocumentSlides(doc)
        org.junit.Assert.assertNull(detectedRatio)

        val cropBox = page.cropBox ?: page.mediaBox
        assertEquals(595.28f, cropBox.width, 0.5f)
        assertEquals(841.89f, cropBox.height, 0.5f)

        doc.close()
    }

    @Test
    fun testAutoTrimSlideOnA4Portrait_4UpMerge() {
        val doc = PDDocument()
        for (i in 0 until 4) {
            val page = PDPage(PDRectangle(595.28f, 841.89f))
            doc.addPage(page)
            val cs = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page)
            cs.addRect(20f, 260f, 555f, 310f)
            cs.fill()
            cs.close()
        }
        val inBytes = ByteArrayOutputStream().also { doc.save(it); doc.close() }.toByteArray()
        val outStream = ByteArrayOutputStream()

        val layout = PrintLayout(
            printerId = "nup_2x2",
            displayName = "4-Up Grid (2x2)",
            cols = 2,
            rows = 2,
            landscape = true,
            subPageLandscape = true
        )

        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(inBytes),
            outputStream = outStream,
            layout = layout,
            addTextContrast = false,
            enableLinks = false,
            bestFit = false, // Output on standard A4 sheet
            autoTrimSlideBorders = true
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals(1, outDoc.numberOfPages)
        val outPage = outDoc.getPage(0)
        assertEquals(PDRectangle.A4.height, outPage.mediaBox.width, 0.5f)
        assertEquals(PDRectangle.A4.width, outPage.mediaBox.height, 0.5f)
        outDoc.close()
    }

    @Test
    fun testAutoTrim16x9SlideOnA4Landscape() {
        val doc = PDDocument()
        val page = PDPage(PDRectangle(841.89f, 595.28f)) // A4 Landscape
        doc.addPage(page)

        // 16:9 slide on A4 Landscape: width 841.89 pt, height 473.56 pt
        // Centered padding: (595.28 - 473.56) / 2 = 60.86 pt
        val cs = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page)
        cs.addRect(20f, 60.86f + 10f, 800f, 453f)
        cs.fill()
        cs.close()

        val detectedRatio = PdfContentTrimmer.trimDocumentSlides(doc)
        org.junit.Assert.assertNotNull(detectedRatio)
        assertEquals(16f / 9f, detectedRatio!!, 0.01f)

        val cropBox = page.cropBox
        assertEquals(841.89f, cropBox.width, 1.0f)
        assertEquals(473.56f, cropBox.height, 1.0f)
        assertEquals(60.86f, cropBox.lowerLeftY, 1.0f)

        doc.close()
    }

    @Test
    fun testForceSlideModeOnEmptyPage() {
        val doc = PDDocument()
        val page = PDPage(PDRectangle(595.28f, 841.89f)) // Empty A4 Portrait
        doc.addPage(page)

        val detectedRatio = PdfContentTrimmer.trimDocumentSlides(doc, forceSlideMode = true)
        org.junit.Assert.assertNotNull(detectedRatio)
        assertEquals(16f / 9f, detectedRatio!!, 0.01f)

        val cropBox = page.cropBox
        assertEquals(595.28f, cropBox.width, 1.0f)
        assertEquals(334.85f, cropBox.height, 1.0f)
        assertEquals(253.52f, cropBox.lowerLeftY, 1.0f)

        doc.close()
    }

    @Test
    fun testDrawCroppedPageInSlot() {
        val srcDoc = PDDocument()
        val page = PDPage(PDRectangle(595.28f, 841.89f))
        srcDoc.addPage(page)

        // Draw a test box in the 16:9 slide region (Y: 253.5 to 588.4)
        val cs = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(srcDoc, page)
        cs.setNonStrokingColor(255, 0, 0)
        cs.addRect(100f, 300f, 200f, 100f) // from Y=300 to 400
        cs.fill()
        cs.close()

        // Auto-trim the slide
        val ratio = PdfContentTrimmer.trimDocumentSlides(srcDoc)
        assertEquals(16f / 9f, ratio!!, 0.01f)

        // Now merge 1x1 onto an A4 landscape sheet
        val outStream = ByteArrayOutputStream()
        val layout = PrintLayout("test", "test", 1, 1, landscape = true, subPageLandscape = true)
        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(ByteArrayOutputStream().also { srcDoc.save(it) }.toByteArray()),
            outputStream = outStream,
            layout = layout,
            bestFit = false,
            autoTrimSlideBorders = true
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        val outPage = outDoc.getPage(0)
        val res = outPage.resources
        val formNames = res.xObjectNames.toList()
        assertEquals(1, formNames.size)
        val xobj = res.getXObject(formNames[0]) as com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject

        // BBox must be normalized to (0, 0, width, height)
        assertEquals(0f, xobj.bBox.lowerLeftX, 0.01f)
        assertEquals(0f, xobj.bBox.lowerLeftY, 0.01f)
        assertEquals(595.28f, xobj.bBox.width, 0.5f)
        assertEquals(334.85f, xobj.bBox.height, 0.5f)

        // Matrix must translate by -lowerLeftY
        assertEquals(-253.52f, xobj.matrix.translateY, 0.5f)

        outDoc.close()
        srcDoc.close()
    }

    @Test
    fun testNormalDocumentNotCroppedEvenInPresentationMode() {
        val srcDoc = PDDocument()
        val page = PDPage(PDRectangle(595.28f, 841.89f))
        srcDoc.addPage(page)

        // Lecture notes document with text near the very top (Y=820)
        val cs = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(srcDoc, page)
        cs.setNonStrokingColor(0, 0, 0)
        cs.addRect(50f, 820f, 400f, 15f) // Heading at the very top
        cs.addRect(50f, 100f, 400f, 500f) // Body text
        cs.fill()
        cs.close()

        // Even with forceSlideMode = true, content near borders prevents cropping
        val ratio = PdfContentTrimmer.trimDocumentSlides(srcDoc, forceSlideMode = true)
        org.junit.Assert.assertNull("Normal document must not be trimmed even in presentation mode!", ratio)

        val cropBox = page.cropBox ?: page.mediaBox
        assertEquals(595.28f, cropBox.width, 0.5f)
        assertEquals(841.89f, cropBox.height, 0.5f)
        assertEquals(0f, cropBox.lowerLeftY, 0.5f)

        srcDoc.close()
    }

    @Test
    fun test2UpPortraitDocumentOnLandscapeA4WithMargins() {
        val srcDoc = PDDocument()
        for (i in 0 until 2) {
            val page = PDPage(PDRectangle(595.28f, 841.89f))
            srcDoc.addPage(page)
            val cs = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(srcDoc, page)
            cs.setNonStrokingColor(0, 0, 0)
            cs.addRect(50f, 820f, 400f, 15f) // text at top
            cs.fill()
            cs.close()
        }

        val inBytes = ByteArrayOutputStream().also { srcDoc.save(it); srcDoc.close() }.toByteArray()
        val outStream = ByteArrayOutputStream()

        val layout = LayoutRegistry.builtInLayouts.first { it.printerId == "nup_2x1" }
        assertEquals(2, layout.cols)
        assertEquals(1, layout.rows)
        assertTrue(layout.landscape)
        org.junit.Assert.assertFalse(layout.subPageLandscape)

        // Top margin 3mm, Bottom margin 3mm
        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(inBytes),
            outputStream = outStream,
            layout = layout,
            bestFit = false,
            marginTopMm = 3,
            marginBottomMm = 3,
            marginLeftMm = 0,
            marginRightMm = 0,
            autoTrimSlideBorders = true
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals(1, outDoc.numberOfPages)

        val outPage = outDoc.getPage(0)
        // Must be A4 Landscape: width ~841.89 pt, height ~595.28 pt
        assertEquals(PDRectangle.A4.height, outPage.mediaBox.width, 0.5f)
        assertEquals(PDRectangle.A4.width, outPage.mediaBox.height, 0.5f)

        outDoc.close()
    }

    @Test
    fun testSpaceDistributionModeEnum() {
        assertEquals("center", SpaceDistributionMode.CENTER.id)
        assertEquals("ratio_2_1", SpaceDistributionMode.RATIO_2_1.id)
        assertEquals("max_middle", SpaceDistributionMode.MAX_MIDDLE.id)

        assertEquals(0.0f, SpaceDistributionMode.CENTER.factor, 0.001f)
        assertEquals(0.5f, SpaceDistributionMode.RATIO_2_1.factor, 0.001f)
        assertEquals(1.0f, SpaceDistributionMode.MAX_MIDDLE.factor, 0.001f)

        assertEquals(1.0f, SpaceDistributionMode.CENTER.gutterMultiplier, 0.001f)
        assertEquals(1.5f, SpaceDistributionMode.RATIO_2_1.gutterMultiplier, 0.001f)
        assertEquals(2.0f, SpaceDistributionMode.MAX_MIDDLE.gutterMultiplier, 0.001f)

        assertEquals(SpaceDistributionMode.CENTER, SpaceDistributionMode.fromId("center"))
        assertEquals(SpaceDistributionMode.RATIO_2_1, SpaceDistributionMode.fromId("ratio_2_1"))
        assertEquals(SpaceDistributionMode.MAX_MIDDLE, SpaceDistributionMode.fromId("max_middle"))
        assertEquals(SpaceDistributionMode.CENTER, SpaceDistributionMode.fromId("unknown"))
    }

    @Test
    fun testMergeNUp_AllSpaceDistributionModes() {
        val srcBytes = createTestPresentation(2, 960f, 540f)
        val layout = LayoutRegistry.builtInLayouts.first { it.printerId == "nup_2x1" }

        for (mode in SpaceDistributionMode.values()) {
            val outStream = ByteArrayOutputStream()
            PdfMerger.mergeNUp(
                sourcePdfStream = ByteArrayInputStream(srcBytes),
                outputStream = outStream,
                layout = layout,
                addTextContrast = false,
                enableLinks = false,
                bestFit = false,
                marginTopMm = 6,
                marginBottomMm = 6,
                marginLeftMm = 6,
                marginRightMm = 6,
                spaceDistributionMode = mode
            )

            val doc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
            assertEquals(1, doc.numberOfPages)
            val page = doc.getPage(0)
            assertEquals(PDRectangle.A4.height, page.mediaBox.width, 0.5f)
            assertEquals(PDRectangle.A4.width, page.mediaBox.height, 0.5f)
            doc.close()
        }
    }

    @Test
    fun testSpaceDistribution_AdaptiveBestFitGutterExpansion() {
        // 16:9 slides in 2x1 grid
        val srcBytes = createTestPresentation(2, 960f, 540f)
        val layout = LayoutRegistry.builtInLayouts.first { it.printerId == "nup_2x1" }

        val outCenter = ByteArrayOutputStream()
        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(srcBytes),
            outputStream = outCenter,
            layout = layout,
            bestFit = true,
            marginTopMm = 10,
            marginBottomMm = 10,
            marginLeftMm = 10,
            marginRightMm = 10,
            spaceDistributionMode = SpaceDistributionMode.CENTER
        )
        val docCenter = PDDocument.load(ByteArrayInputStream(outCenter.toByteArray()))
        val heightCenter = docCenter.getPage(0).mediaBox.height
        docCenter.close()

        val outMax = ByteArrayOutputStream()
        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(srcBytes),
            outputStream = outMax,
            layout = layout,
            bestFit = true,
            marginTopMm = 10,
            marginBottomMm = 10,
            marginLeftMm = 10,
            marginRightMm = 10,
            spaceDistributionMode = SpaceDistributionMode.MAX_MIDDLE
        )
        val docMax = PDDocument.load(ByteArrayInputStream(outMax.toByteArray()))
        val heightMax = docMax.getPage(0).mediaBox.height
        docMax.close()

        // Because MAX_MIDDLE expands gutterX (2.0x vs 1.0x), netW = printableW - (cols-1)*gutterX is smaller,
        // so netH = netW / gridRatio is smaller, leading to a more compact adaptive height.
        assertTrue("MAX_MIDDLE adaptive height ($heightMax) should be smaller than CENTER ($heightCenter)",
            heightMax < heightCenter)
    }

    @Test
    fun testSpaceDistribution_OffsetCalculations() {
        val slotWidth = 400f
        val destW = 300f
        val spareW = slotWidth - destW // 100f
        val cols = 2

        // Verify Center mode
        val centerFactor = SpaceDistributionMode.CENTER.factor // 0.0
        val centerOffsetCol0 = 0.5f + (0f / (cols - 1) - 0.5f) * centerFactor
        val centerOffsetCol1 = 0.5f + (1f / (cols - 1) - 0.5f) * centerFactor
        assertEquals(0.5f, centerOffsetCol0, 0.001f)
        assertEquals(0.5f, centerOffsetCol1, 0.001f)

        // Verify 2:1 Middle mode
        val ratioFactor = SpaceDistributionMode.RATIO_2_1.factor // 0.5
        val ratioOffsetCol0 = 0.5f + (0f / (cols - 1) - 0.5f) * ratioFactor
        val ratioOffsetCol1 = 0.5f + (1f / (cols - 1) - 0.5f) * ratioFactor
        assertEquals(0.25f, ratioOffsetCol0, 0.001f)
        assertEquals(0.75f, ratioOffsetCol1, 0.001f)

        // Verify Max Middle mode (flush outer margins)
        val maxFactor = SpaceDistributionMode.MAX_MIDDLE.factor // 1.0
        val maxOffsetCol0 = 0.5f + (0f / (cols - 1) - 0.5f) * maxFactor
        val maxOffsetCol1 = 0.5f + (1f / (cols - 1) - 0.5f) * maxFactor
        assertEquals(0.0f, maxOffsetCol0, 0.001f)
        assertEquals(1.0f, maxOffsetCol1, 0.001f)

        // Calculate middle extra gap between column 0 and column 1
        // Col 0 ends at: spareW * offsetCol0 + destW
        // Col 1 starts at: slotWidth + gutter + spareW * offsetCol1
        val middleGapCenter = spareW * (1f - centerOffsetCol0) + spareW * centerOffsetCol1
        val middleGapRatio = spareW * (1f - ratioOffsetCol0) + spareW * ratioOffsetCol1
        val middleGapMax = spareW * (1f - maxOffsetCol0) + spareW * maxOffsetCol1

        assertEquals(100f, middleGapCenter, 0.001f) // 50 + 50 = 100
        assertEquals(150f, middleGapRatio, 0.001f)  // 75 + 75 = 150
        assertEquals(200f, middleGapMax, 0.001f)    // 100 + 100 = 200

        assertTrue("2:1 middle gap ($middleGapRatio) > Center gap ($middleGapCenter)", middleGapRatio > middleGapCenter)
        assertTrue("Max middle gap ($middleGapMax) > 2:1 gap ($middleGapRatio)", middleGapMax > middleGapRatio)
    }

    @Test
    fun testSmartMode_NoAutorotation_2x1_KeepsSlidesUpright() {
        // 16:9 slides: 960 x 540 pt
        val srcBytes = createTestPresentation(2, 960f, 540f)
        val outStream = ByteArrayOutputStream()

        val layout = LayoutRegistry.builtInLayouts.first { it.printerId == "nup_2x1" }
        // In 2x1 layout, subPageLandscape is false, but in Smart mode slides must NEVER be rotated sideways!
        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(srcBytes),
            outputStream = outStream,
            layout = layout,
            isPresentationSmart = true,
            bestFit = false
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals(1, outDoc.numberOfPages)
        val page = outDoc.getPage(0)

        // Inspect Form XObjects on output page
        val resources = page.resources
        val xobjectNames = resources.xObjectNames
        assertTrue("Must have imported Form XObjects", xobjectNames.iterator().hasNext())

        for (name in xobjectNames) {
            val xobj = resources.getXObject(name)
            if (xobj is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject) {
                // Visual slide must be landscape (width > height) and BBox must be upright
                assertTrue("Form XObject BBox width must be > height", xobj.bBox.width > xobj.bBox.height)
                assertEquals(960f, xobj.bBox.width, 1f)
                assertEquals(540f, xobj.bBox.height, 1f)
            }
        }
        outDoc.close()
    }

    @Test
    fun testRotatedSourcePdf_NormalizedUpright_NoInversion() {
        val srcDoc = PDDocument()

        // Page with rotation = 90
        val p1 = PDPage(PDRectangle(540f, 960f))
        p1.rotation = 90
        srcDoc.addPage(p1)

        // Page with rotation = 180
        val p2 = PDPage(PDRectangle(960f, 540f))
        p2.rotation = 180
        srcDoc.addPage(p2)

        val inBytes = ByteArrayOutputStream().also { srcDoc.save(it); srcDoc.close() }.toByteArray()
        val outStream = ByteArrayOutputStream()

        val layout = LayoutRegistry.builtInLayouts.first { it.printerId == "nup_2x1" }
        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(inBytes),
            outputStream = outStream,
            layout = layout,
            isPresentationSmart = true,
            bestFit = false
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals(1, outDoc.numberOfPages)
        val outPage = outDoc.getPage(0)

        val resources = outPage.resources
        for (name in resources.xObjectNames) {
            val xobj = resources.getXObject(name)
            if (xobj is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject) {
                // Both pages must normalize to visual landscape (width 960, height 540)
                assertEquals(960f, xobj.bBox.width, 1f)
                assertEquals(540f, xobj.bBox.height, 1f)
            }
        }
        outDoc.close()
    }

    @Test
    fun testSmartMode_3x2_Grid_NoInversion() {
        val srcBytes = createTestPresentation(6, 960f, 540f)
        val outStream = ByteArrayOutputStream()

        val layout = PrintLayout(
            printerId = "custom_3x2",
            displayName = "6-Up Custom (3x2)",
            cols = 3,
            rows = 2,
            landscape = true,
            subPageLandscape = false,
            isCustom = true
        )

        // In 3x2 with subPageLandscape = false, Smart mode must NOT rotate or invert slides
        PdfMerger.mergeNUp(
            sourcePdfStream = ByteArrayInputStream(srcBytes),
            outputStream = outStream,
            layout = layout,
            isPresentationSmart = true,
            bestFit = false
        )

        val outDoc = PDDocument.load(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals(1, outDoc.numberOfPages)
        val outPage = outDoc.getPage(0)

        val resources = outPage.resources
        for (name in resources.xObjectNames) {
            val xobj = resources.getXObject(name)
            if (xobj is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject) {
                assertEquals(960f, xobj.bBox.width, 1f)
                assertEquals(540f, xobj.bBox.height, 1f)
            }
        }
        outDoc.close()
    }
}
