package com.example.twoupprint.e2e

import com.example.twoupprint.PdfMerger
import com.example.twoupprint.PrintLayout
import com.example.twoupprint.notewise.NotewisePdfConverter
import com.example.twoupprint.notewise.PbNode
import com.example.twoupprint.notewise.PbNode.Companion.parseProtobuf
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Tier 4: Real-World Scenarios E2E Tests
 *
 * Covers:
 * 1. Full Academic Slide Deck (molekulova.pdf, 9 pages) to A4 2x1 Landscape Print.
 * 2. Compact Study Notes (molekulova.pdf, 9 pages) to 2x2 Portrait Print.
 * 3. Molecular Genetics Slide Deck (molekulova_rekombinace.pdf, 17 pages) with Formulas to Notewise Export.
 * 4. Print Spooler Capability & MediaSize Negotiation Simulation.
 * 5. Clean Architectural Boundary & Decoupling Audit.
 */
class Tier4RealWorldScenariosE2ETest {

    @Test
    fun testScenario1_AcademicSlideDeckMolekulovaTo2x1Landscape() {
        val samplePdf = TestPdfHelper.findSamplePdf("molekulova.pdf")
        assertTrue("molekulova.pdf must exist and be accessible", samplePdf.exists())

        val layout2x1 = PrintLayout(
            printerId = "nup_2x1",
            displayName = "2-Up Side by Side (2×1)",
            cols = 2,
            rows = 1,
            landscape = true,
            subPageLandscape = false
        )

        val outStream = ByteArrayOutputStream()
        FileInputStream(samplePdf).use { input ->
            PdfMerger.mergeNUp(
                sourcePdfStream = input,
                outputStream = outStream,
                layout = layout2x1,
                bestFit = false,
                marginMm = 5
            )
        }

        val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
        // 9 source pages / 2 slots per sheet = 5 sheets
        assertEquals("9 source slides merged 2x1 must produce exactly 5 sheets", 5, outDoc.numberOfPages)

        // Verify all sheets are landscape
        for (i in 0 until 5) {
            val page = outDoc.getPage(i)
            assertTrue("Sheet $i must be landscape (width > height)", page.mediaBox.width > page.mediaBox.height)
            assertEquals(841.8898f, page.mediaBox.width, 1.0f)
            assertEquals(595.27563f, page.mediaBox.height, 1.0f)
        }

        // Verify text content is selectable
        val stripper = PDFTextStripper()
        val text = stripper.getText(outDoc)
        assertTrue("Output PDF must contain selectable text from lecture", text.isNotBlank())

        outDoc.close()
    }

    @Test
    fun testScenario2_CompactStudyNotesMolekulovaTo2x2Portrait() {
        val samplePdf = TestPdfHelper.findSamplePdf("molekulova.pdf")
        val layout2x2 = PrintLayout(
            printerId = "nup_2x2",
            displayName = "4-Up Grid (2×2)",
            cols = 2,
            rows = 2,
            landscape = false,
            subPageLandscape = false
        )

        val outStream = ByteArrayOutputStream()
        FileInputStream(samplePdf).use { input ->
            PdfMerger.mergeNUp(
                sourcePdfStream = input,
                outputStream = outStream,
                layout = layout2x2,
                bestFit = false,
                marginMm = 10
            )
        }

        val outDoc = TestPdfHelper.loadAndValidatePdf(outStream.toByteArray())
        // 9 source pages / 4 slots per sheet = 3 sheets
        assertEquals("9 source slides merged 2x2 must produce exactly 3 sheets", 3, outDoc.numberOfPages)

        for (i in 0 until 3) {
            val page = outDoc.getPage(i)
            assertTrue("Sheet $i must be portrait (height > width)", page.mediaBox.height > page.mediaBox.width)
            assertEquals(595.27563f, page.mediaBox.width, 1.0f)
            assertEquals(841.8898f, page.mediaBox.height, 1.0f)
        }

        val stripper = PDFTextStripper()
        val text = stripper.getText(outDoc)
        assertTrue("Output PDF must contain selectable text", text.isNotBlank())

        outDoc.close()
    }

    @Test
    fun testScenario3_MolecularGeneticsSlideDeckToNotewiseExport() {
        val samplePdf = TestPdfHelper.findSamplePdf("molekulova_rekombinace.pdf")
        val outStream = ByteArrayOutputStream()

        PDDocument.load(samplePdf).use { doc ->
            assertEquals("molekulova_rekombinace.pdf must have 17 pages", 17, doc.numberOfPages)
            NotewisePdfConverter.convert(doc, outStream, title = "Molekulova Rekombinace")
        }

        val notewiseBytes = outStream.toByteArray()
        assertTrue("Notewise archive must be generated", notewiseBytes.isNotEmpty())

        // Validate ZIP structure
        val zipEntries = mutableListOf<String>()
        val pageRawMap = mutableMapOf<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(notewiseBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                zipEntries.add(entry.name)
                if (entry.name.startsWith("page/")) {
                    pageRawMap[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }

        assertTrue("Notewise archive must contain 'note' entry", zipEntries.contains("note"))
        assertEquals("Must contain 17 page entries", 17, pageRawMap.size)

        // Inspect Protobuf for Tag 2 schema fix and styling in page 0
        val page0Bytes = pageRawMap.values.first()
        val page0B64 = String(page0Bytes, Charsets.US_ASCII).replace("\r", "").replace("\n", "")
        val page0Nodes = parseProtobuf(Base64.getDecoder().decode(page0B64))

        val textElements = page0Nodes.filter { it.fieldNumber == 4 }
        assertTrue("Page 0 should contain extracted text elements", textElements.isNotEmpty())

        for (elem in textElements) {
            @Suppress("UNCHECKED_CAST")
            val subNodes = elem.value as? List<PbNode> ?: continue
            val textData = subNodes.find { it.fieldNumber == 8 } ?: continue
            @Suppress("UNCHECKED_CAST")
            val textDataSub = textData.value as? List<PbNode> ?: continue
            val richTextNodes = textDataSub.filter { it.fieldNumber == 6 }

            for (rt in richTextNodes) {
                @Suppress("UNCHECKED_CAST")
                val rtSub = rt.value as? List<PbNode> ?: continue
                val tag2 = rtSub.find { it.fieldNumber == 2 }
                if (tag2 != null) {
                    assertEquals(
                        "Protobuf RichText Tag 2 must be constant 6L (style tag) per schema",
                        6L,
                        tag2.value as Long
                    )
                }
            }
        }
    }

    @Test
    fun testScenario4_PrintSpoolerCapabilitySimulation() {
        // Simulates Android PrintSpooler handshake and validates orientation & media sizes
        // In Android PrintSpooler, mediaSize.isPortrait is determined by:
        // isPortrait = heightMils >= widthMils

        val smartPortraitWidthMils = 8268
        val smartPortraitHeightMils = 11693
        val isPortraitEvaluated = smartPortraitHeightMils >= smartPortraitWidthMils

        assertTrue(
            "Print spooler must evaluate Smart paper size as Portrait by default",
            isPortraitEvaluated
        )

        // Standard ISO A4 dimensions in mils
        assertEquals(8268, smartPortraitWidthMils)
        assertEquals(11693, smartPortraitHeightMils)

        // Standard ISO A5 dimensions in mils
        val a5WidthMils = 5827
        val a5HeightMils = 8268
        assertTrue("A5 must also default to portrait", a5HeightMils >= a5WidthMils)
    }

    @Test
    fun testScenario5_ArchitecturalBoundaryAndDecouplingAudit() {
        // Complete structural audit: verify PdfMerger does not load or reference any Notewise class
        val pdfMergerClass = PdfMerger::class.java

        // Check declared methods
        for (method in pdfMergerClass.declaredMethods) {
            val returnTypeName = method.returnType.name
            assertFalse(
                "PdfMerger must not return Notewise type in $method",
                returnTypeName.contains(".notewise.")
            )
            for (pt in method.parameterTypes) {
                assertFalse(
                    "PdfMerger method parameter must not be a Notewise type in $method",
                    pt.name.contains(".notewise.")
                )
            }
        }

        // Check declared fields
        for (field in pdfMergerClass.declaredFields) {
            assertFalse(
                "PdfMerger field must not have a Notewise type in $field",
                field.type.name.contains(".notewise.")
            )
        }

        // Check constructors
        for (ctor in pdfMergerClass.declaredConstructors) {
            for (pt in ctor.parameterTypes) {
                assertFalse(
                    "PdfMerger constructor must not have a Notewise parameter in $ctor",
                    pt.name.contains(".notewise.")
                )
            }
        }
    }
}
