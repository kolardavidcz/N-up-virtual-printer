package com.example.twoupprint.notewise

import com.example.twoupprint.notewise.PbNode.Companion.encodeDouble
import com.example.twoupprint.notewise.PbNode.Companion.makeFloatNode
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Data model for an extracted text element to place on a Notewise canvas.
 */
data class NotewiseTextBlock(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val fontSize: Int,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrikethrough: Boolean = false,
    val colorHex: String = "#000000",
    val isBullet: Boolean = false,
    val headingLevel: Int = 0
)

/**
 * Data model for an extracted image element to place on a Notewise canvas.
 */
data class NotewiseImageBlock(
    val imageBytes: ByteArray,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val pixelWidth: Int,
    val pixelHeight: Int
)

/**
 * Builds and serializes native Notewise (.notewise) document archives.
 *
 * Adheres strictly to Notewise Protocol Buffer specifications:
 *   - Base64 encoding wrapped with newlines every 76 characters on `note` and `page/`
 *   - Strictly sequential element indexing (`field 10 = 0, 1, 2, ..., N-1`)
 *   - 24-character random IDs for documents, pages, and image assets
 *   - Layering order: Images placed first (background), text blocks placed second (on top)
 */
class NotewiseNotebookBuilder(
    val title: String,
    val defaultCanvasW: Int = 2480,
    val defaultCanvasH: Int = 3508
) {

    val docId: String = generateRandomId(24)
    val rootPageId: String = generateRandomId(24)
    val pageIds = mutableListOf<String>()
    private val pagePayloads = mutableMapOf<String, ByteArray>()
    private val imageAssets = mutableMapOf<String, ByteArray>()
    private val imageHashToId = mutableMapOf<String, String>()
    private val imageDimensions = mutableMapOf<String, Pair<Int, Int>>()

    private val random = SecureRandom()

    /**
     * Adds a converted page to the notebook with scaled text and image elements.
     *
     * @return Unique 24-char page ID.
     */
    fun addPage(
        textBlocks: List<NotewiseTextBlock>,
        images: List<NotewiseImageBlock>,
        canvasW: Int = defaultCanvasW,
        canvasH: Int = defaultCanvasH
    ): String {
        val pageId = generateRandomId(24)
        pageIds.add(pageId)

        val elements = mutableListOf<PbNode>()

        // 1. Add Image Elements FIRST (background graphics beneath text)
        for (img in images) {
            val imgHash = sha256Hex(img.imageBytes)
            val imgId: String
            val (imgW, imgH) = if (imageHashToId.containsKey(imgHash)) {
                imgId = imageHashToId[imgHash]!!
                imageDimensions[imgId]!!
            } else {
                imgId = generateRandomId(24)
                imageHashToId[imgHash] = imgId
                imageAssets[imgId] = img.imageBytes
                val dims = Pair(img.pixelWidth, img.pixelHeight)
                imageDimensions[imgId] = dims
                dims
            }

            val imgElem = createImageElement(
                imgId = imgId,
                left = img.left,
                top = img.top,
                right = img.right,
                bottom = img.bottom,
                imgW = imgW,
                imgH = imgH
            )
            elements.add(imgElem)
        }

        // 2. Add Text Elements SECOND (text stays cleanly visible and selectable on top)
        for (tb in textBlocks) {
            val leftP = tb.left
            val topP = tb.top
            val rightP = maxOf(tb.right + 35.0f, leftP + 100.0f)
            val bottomP = maxOf(tb.bottom + 12.0f, topP + 25.0f)

            val textElem = createTextElement(
                text = tb.text,
                left = leftP,
                top = topP,
                right = rightP,
                bottom = bottomP,
                fontSize = tb.fontSize,
                isBold = tb.isBold,
                isItalic = tb.isItalic,
                isUnderline = tb.isUnderline,
                isStrikethrough = tb.isStrikethrough,
                colorHex = tb.colorHex
            )
            elements.add(textElem)
        }

        // Construct full page message matching official Notewise wire schema
        val pageSettings = createPageSettings(canvasW, canvasH)
        val pageNodes = listOf(
            PbNode(1, 2, pageId.toByteArray(Charsets.UTF_8)),
            *elements.toTypedArray(),
            pageSettings,
            PbNode(7, 1, encodeDouble(1024.0)),
            PbNode(11, 2, rootPageId.toByteArray(Charsets.UTF_8))
        )

        val serializedPage = pageNodes.map { it.serialize() }.reduce { acc, bytes -> acc + bytes }
        val b64Page = encodeBase64Mime76(serializedPage)
        pagePayloads[pageId] = b64Page

        return pageId
    }

    /**
     * Packages the entire notebook into a .notewise ZIP archive (DEFLATE).
     */
    fun buildArchive(outputStream: OutputStream) {
        val rootMetaNode = PbNode(11, 2, listOf(
            PbNode(1, 2, rootPageId.toByteArray(Charsets.UTF_8)),
            PbNode(2, 2, title.toByteArray(Charsets.UTF_8)),
            PbNode(3, 1, encodeDouble(1024.0)),
            PbNode(5, 2, byteArrayOf())
        ))

        val noteNodes = mutableListOf<PbNode>(
            PbNode(1, 2, docId.toByteArray(Charsets.UTF_8)),
            PbNode(2, 2, title.toByteArray(Charsets.UTF_8))
        )
        for (pid in pageIds) {
            noteNodes.add(PbNode(4, 2, pid.toByteArray(Charsets.UTF_8)))
        }
        for (iid in imageAssets.keys) {
            noteNodes.add(PbNode(5, 2, iid.toByteArray(Charsets.UTF_8)))
        }
        noteNodes.add(PbNode(7, 0, 5L)) // Document type 5
        noteNodes.add(rootMetaNode)

        val serializedNote = noteNodes.map { it.serialize() }.reduce { acc, bytes -> acc + bytes }
        val b64Note = encodeBase64Mime76(serializedNote)

        ZipOutputStream(outputStream).use { zos ->
            zos.setMethod(ZipEntry.DEFLATED)

            // Write note metadata
            val noteEntry = ZipEntry("note")
            zos.putNextEntry(noteEntry)
            zos.write(b64Note)
            zos.closeEntry()

            // Write pages
            for ((pid, pdata) in pagePayloads) {
                val pageEntry = ZipEntry("page/$pid")
                zos.putNextEntry(pageEntry)
                zos.write(pdata)
                zos.closeEntry()
            }

            // Write image assets (raw WebP)
            for ((iid, idata) in imageAssets) {
                val imgEntry = ZipEntry("image/$iid")
                zos.putNextEntry(imgEntry)
                zos.write(idata)
                zos.closeEntry()
            }
        }
    }

    private fun createTextElement(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        fontSize: Int,
        isBold: Boolean,
        isItalic: Boolean = false,
        isUnderline: Boolean = false,
        isStrikethrough: Boolean = false,
        colorHex: String = "#000000"
    ): PbNode {
        val elementId = generateRandomId(30)
        val transform = listOf(
            makeFloatNode(1, 1.0f),
            makeFloatNode(3, 0.0f),
            makeFloatNode(5, 1.0f),
            makeFloatNode(6, 0.0f),
            makeFloatNode(9, 1.0f)
        )
        val frame = listOf(
            makeFloatNode(1, left),
            makeFloatNode(2, top),
            makeFloatNode(3, right),
            makeFloatNode(4, bottom)
        )
        val textColor = listOf(
            PbNode(1, 2, colorHex.toByteArray(Charsets.UTF_8)),
            makeFloatNode(2, 1.0f) // fully opaque alpha
        )
        val fontInfo = listOf(
            PbNode(1, 0, fontSize.toLong()),
            PbNode(2, 2, "gf-roboto".toByteArray(Charsets.UTF_8)),
            PbNode(3, 2, "Roboto".toByteArray(Charsets.UTF_8))
        )
        val richText = mutableListOf(
            PbNode(1, 2, text.toByteArray(Charsets.UTF_8)),
            PbNode(2, 0, fontSize.toLong()), // Run font size in Notewise
            PbNode(3, 2, textColor)
        )
        if (isBold) {
            richText.add(PbNode(4, 0, 1L))
        }
        if (isItalic) {
            richText.add(PbNode(5, 0, 1L))
        }
        if (isUnderline) {
            richText.add(PbNode(7, 0, 1L))
        }
        if (isStrikethrough) {
            richText.add(PbNode(8, 0, 1L))
        }
        richText.add(PbNode(6, 2, fontInfo))

        val content = listOf(
            PbNode(1, 2, richText),
            PbNode(2, 0, 1L), // alignment: left = 1
            PbNode(3, 0, 1L)
        )
        val textData = listOf(
            PbNode(7, 2, frame),
            PbNode(9, 2, content),
            PbNode(13, 2, listOf(
                makeFloatNode(1, left),
                makeFloatNode(2, top)
            )),
            makeFloatNode(14, maxOf(10f, right - left))
        )
        val timestampMs = System.currentTimeMillis()
        val element = listOf(
            PbNode(1, 2, listOf(PbNode(8, 2, elementId.toByteArray(Charsets.UTF_8)))),
            PbNode(2, 0, timestampMs),
            PbNode(3, 2, transform),
            PbNode(8, 2, textData)
        )
        return PbNode(4, 2, element)
    }

    private fun createImageElement(
        imgId: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        imgW: Int,
        imgH: Int
    ): PbNode {
        val elementId = generateRandomId(30)
        val widthP = right - left
        val heightP = bottom - top
        val tfNode = listOf(
            makeFloatNode(1, if (imgW > 0) widthP / imgW.toFloat() else 1.0f),
            makeFloatNode(3, left),
            makeFloatNode(5, if (imgH > 0) heightP / imgH.toFloat() else 1.0f),
            makeFloatNode(6, top),
            makeFloatNode(9, 1.0f)
        )
        val cropNode = listOf(
            makeFloatNode(1, 0.0f),
            makeFloatNode(2, 0.0f),
            makeFloatNode(3, imgW.toFloat()),
            makeFloatNode(4, imgH.toFloat())
        )
        val imgRefNode = listOf(
            PbNode(1, 2, imgId.toByteArray(Charsets.UTF_8)),
            PbNode(2, 2, cropNode),
            makeFloatNode(10, 1.0f)
        )
        val element = listOf(
            PbNode(1, 2, listOf(PbNode(8, 2, elementId.toByteArray(Charsets.UTF_8)))),
            PbNode(2, 0, System.currentTimeMillis()),
            PbNode(3, 2, tfNode),
            PbNode(7, 2, imgRefNode)
        )
        return PbNode(4, 2, element)
    }

    private fun createPageSettings(canvasW: Int, canvasH: Int): PbNode {
        val dimMsg = listOf(
            PbNode(1, 0, 1L), // Paper type A4
            PbNode(3, 0, canvasW.toLong()),
            PbNode(4, 0, canvasH.toLong())
        )
        val colorMsg = listOf(
            PbNode(1, 2, "#FFFFFF".toByteArray(Charsets.UTF_8)),
            makeFloatNode(2, 1.0f)
        )
        return PbNode(6, 2, listOf(
            PbNode(1, 2, dimMsg),
            PbNode(2, 2, colorMsg),
            PbNode(3, 2, byteArrayOf()), // Empty bytes
            PbNode(7, 0, 5L),            // Pattern 5
            PbNode(8, 0, 5L)             // Pattern style 5
        ))
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

        fun generateRandomId(length: Int = 24): String {
            val rng = SecureRandom()
            val sb = java.lang.StringBuilder(length)
            for (i in 0 until length) {
                sb.append(ID_CHARS[rng.nextInt(ID_CHARS.length)])
            }
            return sb.toString()
        }

        fun encodeBase64Mime76(data: ByteArray): ByteArray {
            val encoder = java.util.Base64.getMimeEncoder(76, byteArrayOf('\n'.code.toByte()))
            val encoded = encoder.encode(data)
            return if (encoded.isNotEmpty() && encoded.last() != '\n'.code.toByte()) {
                encoded + byteArrayOf('\n'.code.toByte())
            } else {
                encoded
            }
        }
    }
}
