package com.example.twoupprint

import com.example.twoupprint.e2e.TestPdfHelper
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream

class PdfSplitterTest {

    @Test
    fun testSplit2x1_DoublesPageCountAndGeneratesExactA4PortraitSheets() {
        // Create 2-page landscape document (e.g. 2-up spreads: width 842, height 595)
        val doc = PDDocument()
        for (i in 1..2) {
            val page = PDPage(PDRectangle(841.89f, 595.28f))
            doc.addPage(page)
            val cs = PDPageContentStream(doc, page)
            // Left text
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 18f)
            cs.newLineAtOffset(100f, 300f)
            cs.showText("Spread $i Left Half Content")
            cs.endText()
            // Right text
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 18f)
            cs.newLineAtOffset(550f, 300f)
            cs.showText("Spread $i Right Half Content")
            cs.endText()
            cs.close()
        }
        val inBytes = ByteArrayOutputStream().also { doc.save(it); doc.close() }.toByteArray()

        val splitLayout = PrintLayout(
            printerId = "split_pages",
            displayName = "Split Pages (1→2)",
            cols = 2,
            rows = 1,
            landscape = false,
            isSplit = true
        )

        val outStream = ByteArrayOutputStream()
        PdfSplitter.splitPages(
            sourcePdfStream = ByteArrayInputStream(inBytes),
            outputStream = outStream,
            layout = splitLayout,
            targetPaper = TargetPaperSize.A4
        )

        val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
        // 2 spreads * 2 = 4 pages
        assertThat(outDoc.numberOfPages).isEqualTo(4)

        for (p in 0 until 4) {
            val sheet = outDoc.getPage(p)
            assertThat(sheet.mediaBox.width).isWithin(0.5f).of(TargetPaperSize.A4.widthPt)
            assertThat(sheet.mediaBox.height).isWithin(0.5f).of(TargetPaperSize.A4.heightPt)
            assertThat(sheet.mediaBox.height).isGreaterThan(sheet.mediaBox.width) // Portrait
        }

        // Verify content distribution
        val stripper = PDFTextStripper()
        stripper.startPage = 1
        stripper.endPage = 1
        val page1Text = stripper.getText(outDoc)
        assertThat(page1Text).contains("Spread 1 Left Half Content")

        stripper.startPage = 2
        stripper.endPage = 2
        val page2Text = stripper.getText(outDoc)
        assertThat(page2Text).contains("Spread 1 Right Half Content")

        outDoc.close()
    }

    @Test
    fun testSplit1x2_TopBottomSplitGeneratesLandscapeSheets() {
        // Create 1-page tall document (e.g. 2 slides stacked vertically: width 595, height 842)
        val doc = PDDocument()
        val page = PDPage(PDRectangle(595.28f, 841.89f))
        doc.addPage(page)
        val cs = PDPageContentStream(doc, page)
        // Top half
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_BOLD, 16f)
        cs.newLineAtOffset(100f, 650f)
        cs.showText("Top Slide Header")
        cs.endText()
        // Bottom half
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_BOLD, 16f)
        cs.newLineAtOffset(100f, 200f)
        cs.showText("Bottom Slide Footer")
        cs.endText()
        cs.close()

        val inBytes = ByteArrayOutputStream().also { doc.save(it); doc.close() }.toByteArray()

        val splitLayout = PrintLayout(
            printerId = "split_pages",
            displayName = "Split Pages (1→2 Top/Bottom)",
            cols = 1,
            rows = 2,
            landscape = true, // Landscape output sheet
            isSplit = true
        )

        val outStream = ByteArrayOutputStream()
        PdfSplitter.splitPages(
            sourcePdfStream = ByteArrayInputStream(inBytes),
            outputStream = outStream,
            layout = splitLayout,
            targetPaper = TargetPaperSize.A4
        )

        val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
        assertThat(outDoc.numberOfPages).isEqualTo(2)

        val sheet1 = outDoc.getPage(0)
        assertThat(sheet1.mediaBox.width).isWithin(0.5f).of(TargetPaperSize.A4.heightPt) // Landscape width
        assertThat(sheet1.mediaBox.height).isWithin(0.5f).of(TargetPaperSize.A4.widthPt)  // Landscape height

        val stripper = PDFTextStripper()
        stripper.startPage = 1
        stripper.endPage = 1
        assertThat(stripper.getText(outDoc)).contains("Top Slide Header")

        stripper.startPage = 2
        stripper.endPage = 2
        assertThat(stripper.getText(outDoc)).contains("Bottom Slide Footer")

        outDoc.close()
    }

    @Test
    fun testSplit2x2_QuadruplesPageCountInto4Quadrants() {
        val inBytes = TestPdfHelper.createTestPresentation(
            pageCount = 3,
            width = 595.28f,
            height = 841.89f,
            addText = true
        )

        val splitLayout = PrintLayout(
            printerId = "split_pages",
            displayName = "Split Pages (1→4 Grid)",
            cols = 2,
            rows = 2,
            landscape = false,
            isSplit = true
        )

        val outStream = ByteArrayOutputStream()
        PdfSplitter.splitPages(
            sourcePdfStream = ByteArrayInputStream(inBytes),
            outputStream = outStream,
            layout = splitLayout,
            targetPaper = TargetPaperSize.A4
        )

        val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
        // 3 source pages * 4 quadrants = 12 pages
        assertThat(outDoc.numberOfPages).isEqualTo(12)
        outDoc.close()
    }

    @Test
    fun testSplitTargetPaperSizeA5_GeneratesExactA5Dimensions() {
        val inBytes = TestPdfHelper.createTestPresentation(
            pageCount = 2,
            width = 841.89f,
            height = 595.28f
        )

        val splitLayout = PrintLayout(
            printerId = "split_pages",
            displayName = "Split Pages (1→2)",
            cols = 2,
            rows = 1,
            landscape = false,
            isSplit = true
        )

        val outStream = ByteArrayOutputStream()
        PdfSplitter.splitPages(
            sourcePdfStream = ByteArrayInputStream(inBytes),
            outputStream = outStream,
            layout = splitLayout,
            targetPaper = TargetPaperSize.A5
        )

        val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
        assertThat(outDoc.numberOfPages).isEqualTo(4)

        val sheet1 = outDoc.getPage(0)
        assertThat(sheet1.mediaBox.width).isWithin(0.5f).of(TargetPaperSize.A5.widthPt)
        assertThat(sheet1.mediaBox.height).isWithin(0.5f).of(TargetPaperSize.A5.heightPt)
        outDoc.close()
    }

    @Test
    fun testSplitWithRotatedSourcePage_MaintainsUprightSlicing() {
        // Page rotated 90 degrees
        val inBytes = TestPdfHelper.createTestPresentation(
            pageCount = 1,
            width = 595.28f,
            height = 841.89f,
            rotation = 90
        )

        val splitLayout = PrintLayout(
            printerId = "split_pages",
            displayName = "Split Pages (1→2)",
            cols = 2,
            rows = 1,
            landscape = false,
            isSplit = true
        )

        val outStream = ByteArrayOutputStream()
        PdfSplitter.splitPages(
            sourcePdfStream = ByteArrayInputStream(inBytes),
            outputStream = outStream,
            layout = splitLayout,
            targetPaper = TargetPaperSize.A4
        )

        val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
        assertThat(outDoc.numberOfPages).isEqualTo(2)

        // Output pages must be upright portrait A4
        val sheet1 = outDoc.getPage(0)
        assertThat(sheet1.mediaBox.width).isWithin(0.5f).of(TargetPaperSize.A4.widthPt)
        assertThat(sheet1.mediaBox.height).isWithin(0.5f).of(TargetPaperSize.A4.heightPt)
        assertThat(sheet1.rotation).isEqualTo(0) // upright
        outDoc.close()
    }

    @Test
    fun testSplitRealWorldSamplePdf() {
        val samplePdf = TestPdfHelper.findSamplePdf("molekulova.pdf")
        val splitLayout = PrintLayout(
            printerId = "split_pages",
            displayName = "Split Pages (1→2)",
            cols = 2,
            rows = 1,
            landscape = false,
            isSplit = true
        )

        val outStream = ByteArrayOutputStream()
        FileInputStream(samplePdf).use { input ->
            PdfSplitter.splitPages(
                sourcePdfStream = input,
                outputStream = outStream,
                layout = splitLayout,
                targetPaper = TargetPaperSize.A4
            )
        }

        val inDoc = PDDocument.load(samplePdf)
        val inPages = inDoc.numberOfPages
        inDoc.close()

        val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
        // molekulova.pdf has 9 pages, split 2x1 -> 18 pages
        assertThat(outDoc.numberOfPages).isEqualTo(inPages * 2)
        outDoc.close()
    }

    @Test
    fun testSplitPreservesHyperlinksAcrossSubPages() {
        val doc = PDDocument()
        val page = PDPage(PDRectangle(841.89f, 595.28f))
        doc.addPage(page)

        // Add a link on the left half [100, 200, 250, 250]
        val linkLeft = PDAnnotationLink().apply {
            rectangle = PDRectangle(100f, 200f, 150f, 50f)
            action = PDActionURI().apply { uri = "https://example.com/left" }
        }

        // Add a link on the right half [550, 200, 250, 250]
        val linkRight = PDAnnotationLink().apply {
            rectangle = PDRectangle(550f, 200f, 150f, 50f)
            action = PDActionURI().apply { uri = "https://example.com/right" }
        }

        page.annotations = mutableListOf(linkLeft, linkRight)

        val inBytes = ByteArrayOutputStream().also { doc.save(it); doc.close() }.toByteArray()

        val splitLayout = PrintLayout(
            printerId = "split_pages",
            displayName = "Split Pages (1→2)",
            cols = 2,
            rows = 1,
            landscape = false,
            isSplit = true
        )

        val outStream = ByteArrayOutputStream()
        PdfSplitter.splitPages(
            sourcePdfStream = ByteArrayInputStream(inBytes),
            outputStream = outStream,
            layout = splitLayout,
            enableLinks = true,
            targetPaper = TargetPaperSize.A4
        )

        val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
        assertThat(outDoc.numberOfPages).isEqualTo(2)

        // Output Page 1 (Left tile) should contain the left link
        val outPage1 = outDoc.getPage(0)
        val annots1 = outPage1.annotations.filterIsInstance<PDAnnotationLink>()
        assertThat(annots1).isNotEmpty()
        val uri1 = (annots1.first().action as? PDActionURI)?.uri
        assertThat(uri1).isEqualTo("https://example.com/left")

        // Output Page 2 (Right tile) should contain the right link
        val outPage2 = outDoc.getPage(1)
        val annots2 = outPage2.annotations.filterIsInstance<PDAnnotationLink>()
        assertThat(annots2).isNotEmpty()
        val uri2 = (annots2.first().action as? PDActionURI)?.uri
        assertThat(uri2).isEqualTo("https://example.com/right")

        outDoc.close()
    }
}
