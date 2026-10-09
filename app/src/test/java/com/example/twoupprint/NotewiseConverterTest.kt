package com.example.twoupprint

import com.example.twoupprint.notewise.NotewiseImageBlock
import com.example.twoupprint.notewise.NotewiseNotebookBuilder
import com.example.twoupprint.notewise.NotewisePdfConverter
import com.example.twoupprint.notewise.NotewiseTextBlock
import com.example.twoupprint.notewise.PbNode
import com.example.twoupprint.notewise.PbNode.Companion.decodeVarint
import com.example.twoupprint.notewise.PbNode.Companion.encodeDouble
import com.example.twoupprint.notewise.PbNode.Companion.encodeFloat
import com.example.twoupprint.notewise.PbNode.Companion.encodeVarint
import com.example.twoupprint.notewise.PbNode.Companion.parseProtobuf
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

class NotewiseConverterTest {

    @Test
    fun testProtobufVarintEncodingDecoding() {
        val testValues = listOf(0L, 1L, 127L, 128L, 300L, 1024L, 65535L, 1790623528211L)
        for (v in testValues) {
            val encoded = encodeVarint(v)
            val (decoded, bytesRead) = decodeVarint(encoded, 0)
            assertEquals("Varint value must match", v, decoded)
            assertEquals("Must consume all encoded bytes", encoded.size, bytesRead)
        }
    }

    @Test
    fun testProtobufFloatAndDouble() {
        // Float 32
        val floatVal = 123.456f
        val floatBytes = encodeFloat(floatVal)
        assertEquals(4, floatBytes.size)

        val nodeFloat = PbNode(1, 5, floatVal)
        val serializedFloat = nodeFloat.serialize()
        val parsedFloat = parseProtobuf(serializedFloat)
        assertEquals(1, parsedFloat.size)
        assertEquals(1, parsedFloat[0].fieldNumber)
        assertEquals(5, parsedFloat[0].wireType)
        assertEquals(floatVal, parsedFloat[0].value as Float, 0.001f)

        // Double 64
        val doubleVal = 1024.0
        val doubleBytes = encodeDouble(doubleVal)
        assertEquals(8, doubleBytes.size)

        val nodeDouble = PbNode(7, 1, doubleBytes)
        val serializedDouble = nodeDouble.serialize()
        val parsedDouble = parseProtobuf(serializedDouble)
        assertEquals(1, parsedDouble.size)
        assertEquals(7, parsedDouble[0].fieldNumber)
        assertEquals(1, parsedDouble[0].wireType)
    }

    @Test
    fun testBase64Mime76LineWrapping() {
        // Create 200 bytes of arbitrary data
        val data = ByteArray(200) { (it % 256).toByte() }
        val wrappedBytes = NotewiseNotebookBuilder.encodeBase64Mime76(data)
        val wrappedStr = String(wrappedBytes, Charsets.US_ASCII)

        // Verify each line has <= 76 characters
        val lines = wrappedStr.lines().filter { it.isNotEmpty() }
        for (line in lines) {
            assertTrue("Each line must be <= 76 chars, was ${line.length}", line.length <= 76)
        }

        // Verify decoding produces exact original data
        val cleanB64 = wrappedStr.replace("\r", "").replace("\n", "")
        val decoded = Base64.getDecoder().decode(cleanB64)
        assertTrue("Decoded data must match original", data.contentEquals(decoded))
    }

    @Test
    fun testNotewiseNotebookBuilderArchiveStructure() {
        val builder = NotewiseNotebookBuilder(title = "My Test Notebook")

        val dummyImage = ByteArray(32) { 0xFF.toByte() }
        val imgBlock = NotewiseImageBlock(
            imageBytes = dummyImage,
            left = 0f,
            top = 0f,
            right = 2480f,
            bottom = 3508f,
            pixelWidth = 100,
            pixelHeight = 100
        )

        val textBlocks = listOf(
            NotewiseTextBlock("Heading 1", 100f, 100f, 500f, 140f, fontSize = 24, isBold = true),
            NotewiseTextBlock("First paragraph with text content", 100f, 160f, 1200f, 220f, fontSize = 14),
            NotewiseTextBlock("• Bullet point item 1", 100f, 240f, 1000f, 280f, fontSize = 14, isBullet = true)
        )

        val pageId = builder.addPage(
            textBlocks = textBlocks,
            images = listOf(imgBlock),
            canvasW = 2480,
            canvasH = 3508
        )

        assertEquals(1, builder.pageIds.size)
        assertEquals(pageId, builder.pageIds[0])

        // Build ZIP archive
        val zipOut = ByteArrayOutputStream()
        builder.buildArchive(zipOut)
        val zipBytes = zipOut.toByteArray()
        assertTrue("Archive must not be empty", zipBytes.isNotEmpty())

        // Inspect ZIP entries
        val entryNames = mutableListOf<String>()
        val entryContents = mutableMapOf<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                entryContents[entry.name] = zis.readBytes()
                entry = zis.nextEntry
            }
        }

        assertTrue("Must contain 'note'", entryNames.contains("note"))
        assertTrue("Must contain 'page/$pageId'", entryNames.contains("page/$pageId"))
        assertTrue("Must contain an image entry", entryNames.any { it.startsWith("image/") })

        // Verify Base64 wrapping on note and page
        val noteRaw = String(entryContents["note"]!!, Charsets.US_ASCII)
        for (line in noteRaw.lines().filter { it.isNotEmpty() }) {
            assertTrue("Note lines must be <= 76 chars", line.length <= 76)
        }

        val pageRaw = String(entryContents["page/$pageId"]!!, Charsets.US_ASCII)
        for (line in pageRaw.lines().filter { it.isNotEmpty() }) {
            assertTrue("Page lines must be <= 76 chars", line.length <= 76)
        }

        // Parse note protobuf and verify field 11 rootPageId linking
        val noteDecoded = Base64.getDecoder().decode(noteRaw.replace("\r", "").replace("\n", ""))
        val noteNodes = parseProtobuf(noteDecoded)
        assertNotNull(noteNodes)
        val docIdNode = noteNodes.find { it.fieldNumber == 1 }
        assertNotNull("DocId must exist in note", docIdNode)
        val titleNode = noteNodes.find { it.fieldNumber == 2 }
        assertNotNull("Title must exist in note", titleNode)
        assertEquals("My Test Notebook", String(titleNode!!.value as ByteArray, Charsets.UTF_8))
        val rootMetaNode = noteNodes.find { it.fieldNumber == 11 }
        assertNotNull("Root metadata (field 11) must exist in note", rootMetaNode)

        // Parse page protobuf and verify Roboto font, anchors, and root linking in field 11
        val pageDecoded = Base64.getDecoder().decode(pageRaw.replace("\r", "").replace("\n", ""))
        val pageNodes = parseProtobuf(pageDecoded)
        val pageRootNode = pageNodes.find { it.fieldNumber == 11 }
        assertNotNull("Page must contain rootPageId link in field 11", pageRootNode)

        val elementNodes = pageNodes.filter { it.fieldNumber == 4 }
        // We have 1 image + 3 text elements = 4 elements total
        assertEquals(4, elementNodes.size)

        // Check text elements for Roboto font and field 13 / 14
        val textElements = elementNodes.drop(1) // first is image
        for (te in textElements) {
            @Suppress("UNCHECKED_CAST")
            val subNodes = te.value as List<PbNode>
            val uidNode = subNodes.find { it.fieldNumber == 1 }
            assertNotNull("Element UID (field 1) must exist", uidNode)
            @Suppress("UNCHECKED_CAST")
            val uidSub = uidNode!!.value as List<PbNode>
            val uidTag8 = uidSub.find { it.fieldNumber == 8 }
            assertNotNull("Element UID must have tag 8 submessage", uidTag8)

            val textDataNode = subNodes.find { it.fieldNumber == 8 }
            assertNotNull("TextData (field 8) must exist", textDataNode)
            @Suppress("UNCHECKED_CAST")
            val tdSub = textDataNode!!.value as List<PbNode>

            // Field 13 (anchor) and Field 14 (width) must exist
            val anchorNode = tdSub.find { it.fieldNumber == 13 }
            val widthNode = tdSub.find { it.fieldNumber == 14 }
            assertNotNull("Field 13 (anchor) must exist", anchorNode)
            assertNotNull("Field 14 (width) must exist", widthNode)

            // Content -> RichText -> FontInfo must have Roboto
            val contentNode = tdSub.find { it.fieldNumber == 9 }
            @Suppress("UNCHECKED_CAST")
            val contentSub = contentNode!!.value as List<PbNode>
            val richTextNode = contentSub.find { it.fieldNumber == 1 }
            @Suppress("UNCHECKED_CAST")
            val richTextSub = richTextNode!!.value as List<PbNode>
            val tag2Node = richTextSub.find { it.fieldNumber == 2 }
            assertNotNull("RichText Tag 2 must exist", tag2Node)
            val fontInfoNode = richTextSub.find { it.fieldNumber == 6 }
            @Suppress("UNCHECKED_CAST")
            val fontInfoSub = fontInfoNode!!.value as List<PbNode>

            val fontSizeNode = fontInfoSub.find { it.fieldNumber == 1 }
            assertEquals("RichText Tag 2 must match font size", fontSizeNode!!.value, tag2Node!!.value)

            val fontIdNode = fontInfoSub.find { it.fieldNumber == 2 }
            val fontFamNode = fontInfoSub.find { it.fieldNumber == 3 }
            assertEquals("gf-roboto", String(fontIdNode!!.value as ByteArray, Charsets.UTF_8))
            assertEquals("Roboto", String(fontFamNode!!.value as ByteArray, Charsets.UTF_8))
        }
    }

    @Test
    fun testBulletRegex() {
        val bulletSamples = listOf(
            "• Bullet point",
            "- Dash bullet",
            "* Asterisk bullet",
            "1. Numbered item",
            "2) Parenthesis item",
            "a) Lettered item",
            "A. Capital letter item"
        )
        for (sample in bulletSamples) {
            assertTrue("Should match bullet for '$sample'", NotewisePdfConverter.BULLET_REGEX.containsMatchIn(sample))
        }

        val nonBulletSamples = listOf(
            "-15 °C temperature",
            "--flag parameter",
            "Regular sentence with no bullet points.",
            "123 is a number without dot",
            "A word without delimiter"
        )
        for (sample in nonBulletSamples) {
            assertTrue("Should NOT match bullet for '$sample'", !NotewisePdfConverter.BULLET_REGEX.containsMatchIn(sample))
        }
    }

    @Test
    fun testFontSizeScalingFormula() {
        val scaleY = 3508f / 842f // standard A4 portrait scale
        fun calcNotewiseFontSize(pdfPt: Float): Int {
            return kotlin.math.max(8, (pdfPt * (scaleY / 3.0f)).roundToInt())
        }

        assertEquals(14, calcNotewiseFontSize(10f)) // footnote/caption
        assertEquals(17, calcNotewiseFontSize(12f)) // body text
        assertEquals(25, calcNotewiseFontSize(18f)) // H2 header
        assertEquals(33, calcNotewiseFontSize(24f)) // H1 title
    }

    @Test
    fun testNotewiseNotebookBuilderMultiPageAndOrientation() {
        val builder = NotewiseNotebookBuilder(title = "Orientation Test Notebook")

        // Page 1: Portrait (2480 x 3508)
        val p1Text = listOf(NotewiseTextBlock("Portrait Note", 100f, 100f, 800f, 150f, fontSize = 24))
        val pageId1 = builder.addPage(p1Text, emptyList(), canvasW = 2480, canvasH = 3508)

        // Page 2: Landscape (3508 x 2480)
        val p2Text = listOf(NotewiseTextBlock("Landscape Slide", 100f, 100f, 1200f, 180f, fontSize = 28))
        val pageId2 = builder.addPage(p2Text, emptyList(), canvasW = 3508, canvasH = 2480)

        val zipOut = ByteArrayOutputStream()
        builder.buildArchive(zipOut)
        val zipBytes = zipOut.toByteArray()
        assertTrue("Archive must not be empty", zipBytes.isNotEmpty())

        val entryNames = mutableListOf<String>()
        val pageContents = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                if (entry.name.startsWith("page/")) {
                    pageContents[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }

        assertTrue("Must contain 'note'", entryNames.contains("note"))
        assertTrue("Must contain page 1", entryNames.contains("page/$pageId1"))
        assertTrue("Must contain page 2", entryNames.contains("page/$pageId2"))

        // Verify orientation detection per page in protobuf PageSettings (field 6)
        val p1Raw = String(pageContents["page/$pageId1"]!!, Charsets.US_ASCII)
        val p1Decoded = Base64.getDecoder().decode(p1Raw.replace("\r", "").replace("\n", ""))
        val p1Nodes = parseProtobuf(p1Decoded)
        val p1Settings = p1Nodes.find { it.fieldNumber == 6 }
        assertNotNull("Page 1 must contain PageSettings (field 6)", p1Settings)
        @Suppress("UNCHECKED_CAST")
        val p1SettingsNodes = p1Settings!!.value as List<PbNode>
        val p1DimNode = p1SettingsNodes.find { it.fieldNumber == 1 }
        assertNotNull("Page 1 PageSettings must contain dimension (field 1)", p1DimNode)
        @Suppress("UNCHECKED_CAST")
        val p1DimSubNodes = p1DimNode!!.value as List<PbNode>
        val p1Width = p1DimSubNodes.find { it.fieldNumber == 3 }?.value as? Long
        val p1Height = p1DimSubNodes.find { it.fieldNumber == 4 }?.value as? Long
        assertEquals("Page 1 width should be 2480 (portrait)", 2480L, p1Width)
        assertEquals("Page 1 height should be 3508 (portrait)", 3508L, p1Height)

        val p2Raw = String(pageContents["page/$pageId2"]!!, Charsets.US_ASCII)
        val p2Decoded = Base64.getDecoder().decode(p2Raw.replace("\r", "").replace("\n", ""))
        val p2Nodes = parseProtobuf(p2Decoded)
        val p2Settings = p2Nodes.find { it.fieldNumber == 6 }
        assertNotNull("Page 2 must contain PageSettings (field 6)", p2Settings)
        @Suppress("UNCHECKED_CAST")
        val p2SettingsNodes = p2Settings!!.value as List<PbNode>
        val p2DimNode = p2SettingsNodes.find { it.fieldNumber == 1 }
        assertNotNull("Page 2 PageSettings must contain dimension (field 1)", p2DimNode)
        @Suppress("UNCHECKED_CAST")
        val p2DimSubNodes = p2DimNode!!.value as List<PbNode>
        val p2Width = p2DimSubNodes.find { it.fieldNumber == 3 }?.value as? Long
        val p2Height = p2DimSubNodes.find { it.fieldNumber == 4 }?.value as? Long
        assertEquals("Page 2 width should be 3508 (landscape)", 3508L, p2Width)
        assertEquals("Page 2 height should be 2480 (landscape)", 2480L, p2Height)
    }

    @Test
    fun testEndToEndMolekulovaPdfConversion() {
        val srcPdf = listOf(java.io.File("molekulova.pdf"), java.io.File("../molekulova.pdf")).firstOrNull { it.exists() }
        org.junit.Assert.assertNotNull("molekulova.pdf must exist", srcPdf)

        val outDir = if (java.io.File("molekulova.pdf").exists()) java.io.File(".") else java.io.File("..")
        val outFile = java.io.File(outDir, "molekulova_v221.notewise")
        NotewisePdfConverter.convert(srcPdf!!, outFile, title = "Molekulova Genetika")

        assertTrue("Output notewise file must exist", outFile.exists())
        assertTrue("Output notewise file must be non-empty", outFile.length() > 1000)

        // Verify it contains note and 9 pages
        ZipInputStream(java.io.FileInputStream(outFile)).use { zis ->
            val entries = mutableListOf<String>()
            var e = zis.nextEntry
            while (e != null) {
                entries.add(e.name)
                e = zis.nextEntry
            }
            assertTrue("Must contain note entry", entries.contains("note"))
            val pageEntries = entries.filter { it.startsWith("page/") }
            assertEquals("Must have 9 converted pages", 9, pageEntries.size)
        }
    }
}
