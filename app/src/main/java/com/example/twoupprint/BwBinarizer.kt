package com.example.twoupprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Pure 1-bit Black & White (0 and 1) binarization engine.
 *
 * Implements high-performance algorithms to convert arbitrary RGB / ARGB bitmaps into
 * strictly two colors: #000000 (Black, 0) and #FFFFFF (White, 1).
 */
object BwBinarizer {

    enum class BwAlgorithm(val displayName: String, val description: String) {
        TEXT_BOOSTER(
            "Text Booster (High Contrast)",
            "Aggressive threshold: Faint, light gray, pencil notes, and colored text become solid deep black."
        ),
        INK_SAVER(
            "Ink / Toner Saver",
            "Conservative threshold: Only dark text and bold lines become black. Background tints and highlights turn pure white."
        ),
        ANY_COLOR_TO_BLACK(
            "Any Color → Black",
            "Any non-white or colored element (code syntax, formulas, charts) is forced to solid black so nothing is lost."
        ),
        OTSU_ADAPTIVE(
            "Otsu's Adaptive Threshold",
            "Automatically analyzes document histogram to calculate the mathematically optimal threshold."
        ),
        FLOYD_STEINBERG(
            "Floyd-Steinberg Dithering",
            "1-bit spatial error diffusion. Creates realistic shades for photos and complex graphics using micro-dot patterns."
        ),
        BAYER_DITHER(
            "Bayer Halftone Dithering",
            "Ordered 4x4 matrix dithering. Fast geometric dot patterning (newspaper/retro style)."
        ),
        MIDPOINT_50(
            "Standard Midpoint (50%)",
            "Balanced 50% luminance threshold (Y < 128 = Black, Y >= 128 = White)."
        )
    }

    enum class SampleType(val displayName: String) {
        DOCUMENT_TEXT("Document & Notes"),
        CODE_SYNTAX("Code & Syntax"),
        CHART_DIAGRAM("Chart & Diagram"),
        PHOTO_IMAGE("Photo & Artwork")
    }

    /**
     * Binarizes an input bitmap into a strictly 1-bit (Black #000000 and White #FFFFFF) bitmap
     * using the specified [algorithm].
     */
    fun binarize(src: Bitmap, algorithm: BwAlgorithm): Bitmap {
        val width = src.width
        val height = src.height

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val outPixels = when (algorithm) {
            BwAlgorithm.TEXT_BOOSTER -> binarizeThreshold(pixels, threshold = 205)
            BwAlgorithm.INK_SAVER -> binarizeThreshold(pixels, threshold = 95)
            BwAlgorithm.ANY_COLOR_TO_BLACK -> binarizeAnyColor(pixels)
            BwAlgorithm.OTSU_ADAPTIVE -> {
                val otsuThresh = computeOtsuThreshold(pixels)
                binarizeThreshold(pixels, threshold = otsuThresh)
            }
            BwAlgorithm.FLOYD_STEINBERG -> binarizeFloydSteinberg(pixels, width, height)
            BwAlgorithm.BAYER_DITHER -> binarizeBayer(pixels, width, height)
            BwAlgorithm.MIDPOINT_50 -> binarizeThreshold(pixels, threshold = 128)
        }

        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
        return outBitmap
    }

    /**
     * Converts an input bitmap to smooth 8-bit grayscale (0-255).
     */
    fun toGrayscale(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color ushr 24) and 0xFF
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return outBitmap
    }

    // --- Algorithm Implementations ---

    private fun binarizeThreshold(pixels: IntArray, threshold: Int): IntArray {
        val out = IntArray(pixels.size)
        val black = Color.BLACK
        val white = Color.WHITE

        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            if (a < 128) {
                out[i] = white
                continue
            }
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val lum = (299 * r + 587 * g + 114 * b) / 1000
            out[i] = if (lum < threshold) black else white
        }
        return out
    }

    private fun binarizeAnyColor(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        val black = Color.BLACK
        val white = Color.WHITE

        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            if (a < 128) {
                out[i] = white
                continue
            }
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF

            // If not almost pure white, treat as content (Black)
            val isWhite = (r >= 238 && g >= 238 && b >= 238)
            out[i] = if (isWhite) white else black
        }
        return out
    }

    private fun computeOtsuThreshold(pixels: IntArray): Int {
        val histogram = IntArray(256)
        var total = 0

        for (c in pixels) {
            val a = (c ushr 24) and 0xFF
            if (a >= 128) {
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val lum = (299 * r + 587 * g + 114 * b) / 1000
                histogram[lum]++
                total++
            }
        }

        if (total == 0) return 128

        var sum = 0.0
        for (t in 0..255) sum += t * histogram[t]

        var sumB = 0.0
        var wB = 0
        var maxVariance = 0.0
        var threshold = 128

        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += t.toDouble() * histogram[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF

            val betweenVariance = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (betweenVariance > maxVariance) {
                maxVariance = betweenVariance
                threshold = t
            }
        }
        return threshold
    }

    private fun binarizeFloydSteinberg(pixels: IntArray, width: Int, height: Int): IntArray {
        // Work with a float grayscale buffer to avoid rounding buildup
        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            if (a < 128) {
                gray[i] = 255f
            } else {
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                gray[i] = (0.299f * r + 0.587f * g + 0.114f * b)
            }
        }

        val out = IntArray(width * height)
        val black = Color.BLACK
        val white = Color.WHITE

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val oldVal = gray[idx]
                val newVal = if (oldVal < 128f) 0f else 255f
                out[idx] = if (newVal == 0f) black else white

                val error = oldVal - newVal

                // Distribute error to 4 neighbors:
                // (x + 1, y    ) -> 7/16
                // (x - 1, y + 1) -> 3/16
                // (x    , y + 1) -> 5/16
                // (x + 1, y + 1) -> 1/16
                if (x + 1 < width) {
                    gray[idx + 1] += error * (7f / 16f)
                }
                if (y + 1 < height) {
                    if (x - 1 >= 0) {
                        gray[(y + 1) * width + (x - 1)] += error * (3f / 16f)
                    }
                    gray[(y + 1) * width + x] += error * (5f / 16f)
                    if (x + 1 < width) {
                        gray[(y + 1) * width + (x + 1)] += error * (1f / 16f)
                    }
                }
            }
        }
        return out
    }

    private val BAYER_MATRIX_4X4 = arrayOf(
        intArrayOf( 0,  8,  2, 10),
        intArrayOf(12,  4, 14,  6),
        intArrayOf( 3, 11,  1,  9),
        intArrayOf(15,  7, 13,  5)
    )

    private fun binarizeBayer(pixels: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(pixels.size)
        val black = Color.BLACK
        val white = Color.WHITE

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val c = pixels[idx]
                val a = (c ushr 24) and 0xFF
                if (a < 128) {
                    out[idx] = white
                    continue
                }
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val lum = (299 * r + 587 * g + 114 * b) / 1000

                // Map 4x4 Bayer value (0..15) to threshold (8..248)
                val mVal = BAYER_MATRIX_4X4[y % 4][x % 4]
                val thresh = (mVal + 0.5f) * (255f / 16f)

                out[idx] = if (lum < thresh) black else white
            }
        }
        return out
    }

    // --- Synthetic Showcase Test Sample Generators ---

    fun generateSampleBitmap(type: SampleType, width: Int = 360, height: Int = 240): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (type) {
            SampleType.DOCUMENT_TEXT -> {
                // Background highlight box
                paint.color = Color.parseColor("#FFF3CD") // Soft yellow highlight
                canvas.drawRoundRect(RectF(16f, 16f, width - 16f, 70f), 8f, 8f, paint)

                // Bold dark header
                paint.color = Color.parseColor("#1B1B1F")
                paint.textSize = 17f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("Project Architecture & Report", 28f, 44f, paint)

                paint.textSize = 12f
                paint.typeface = Typeface.DEFAULT
                paint.color = Color.parseColor("#856404")
                canvas.drawText("Note: Important findings highlighted in yellow", 28f, 62f, paint)

                // Body text (dark)
                paint.color = Color.parseColor("#212529")
                canvas.drawText("• Standard dark text paragraph with high legibility.", 20f, 96f, paint)

                // Light blue hyperlink
                paint.color = Color.parseColor("#0D6EFD")
                canvas.drawText("• Blue Hyperlink: https://example.org/spec-v2.pdf", 20f, 122f, paint)

                // Faint gray text (pencil / watermark style)
                paint.color = Color.parseColor("#A0AEC0")
                canvas.drawText("• Faint annotation: Verified on 2026-08-25 (pencil note)", 20f, 148f, paint)

                // Colored tag pill
                paint.color = Color.parseColor("#D1E7DD")
                canvas.drawRoundRect(RectF(20f, 168f, 140f, 196f), 14f, 14f, paint)
                paint.color = Color.parseColor("#0F5132")
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("STATUS: PASSED", 28f, 186f, paint)
            }

            SampleType.CODE_SYNTAX -> {
                // Light code background
                paint.color = Color.parseColor("#F8F9FA")
                canvas.drawRoundRect(RectF(12f, 12f, width - 12f, height - 12f), 8f, 8f, paint)

                paint.typeface = Typeface.MONOSPACE
                paint.textSize = 12.5f

                // Line 1: Keyword (Purple) + Function (Blue)
                paint.color = Color.parseColor("#7928CA") // Purple
                canvas.drawText("fun ", 24f, 38f, paint)
                paint.color = Color.parseColor("#0070F3") // Blue
                canvas.drawText("processVectorPdf", 60f, 38f, paint)
                paint.color = Color.parseColor("#212529")
                canvas.drawText("(job: PrintJob): Boolean {", 195f, 38f, paint)

                // Line 2: Comment (Green)
                paint.color = Color.parseColor("#198754")
                canvas.drawText("    // Extract document attributes & colors", 24f, 66f, paint)

                // Line 3: Val declaration
                paint.color = Color.parseColor("#D63384") // Pink keyword
                canvas.drawText("    val ", 24f, 94f, paint)
                paint.color = Color.parseColor("#FD7E14") // Orange var
                canvas.drawText("isColor ", 68f, 94f, paint)
                paint.color = Color.parseColor("#212529")
                canvas.drawText("= job.info.attributes.isColor", 130f, 94f, paint)

                // Line 4: String literal (Red/Coral)
                paint.color = Color.parseColor("#E03131")
                canvas.drawText("    val status = \"B&W 1-bit complete\"", 24f, 122f, paint)

                // Line 5: Number / Boolean (Cyan)
                paint.color = Color.parseColor("#0CA678")
                canvas.drawText("    return true", 24f, 150f, paint)
                paint.color = Color.parseColor("#212529")
                canvas.drawText("}", 24f, 178f, paint)
            }

            SampleType.CHART_DIAGRAM -> {
                // Draw a mini multi-color bar chart and pie diagram
                paint.style = Paint.Style.FILL

                // Bar 1: Blue
                paint.color = Color.parseColor("#3B82F6")
                canvas.drawRect(30f, 80f, 65f, 180f, paint)

                // Bar 2: Emerald Green
                paint.color = Color.parseColor("#10B981")
                canvas.drawRect(75f, 50f, 110f, 180f, paint)

                // Bar 3: Orange
                paint.color = Color.parseColor("#F59E0B")
                canvas.drawRect(120f, 110f, 155f, 180f, paint)

                // Bar 4: Rose Red
                paint.color = Color.parseColor("#EF4444")
                canvas.drawRect(165f, 35f, 200f, 180f, paint)

                // Baseline
                paint.color = Color.BLACK
                paint.strokeWidth = 2.5f
                canvas.drawLine(20f, 180f, 215f, 180f, paint)

                // Pie slice circle
                paint.color = Color.parseColor("#6366F1") // Indigo
                canvas.drawArc(RectF(240f, 60f, 330f, 150f), 0f, 120f, true, paint)

                paint.color = Color.parseColor("#EC4899") // Pink
                canvas.drawArc(RectF(240f, 60f, 330f, 150f), 120f, 150f, true, paint)

                paint.color = Color.parseColor("#FBBF24") // Amber
                canvas.drawArc(RectF(240f, 60f, 330f, 150f), 270f, 90f, true, paint)

                // Title
                paint.color = Color.parseColor("#111827")
                paint.textSize = 13f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("Quarterly Metrics & Distribution", 24f, 28f, paint)
                paint.textSize = 10f
                paint.typeface = Typeface.DEFAULT
                canvas.drawText("Q1    Q2    Q3    Q4", 38f, 198f, paint)
            }

            SampleType.PHOTO_IMAGE -> {
                // Draw a synthetic photographic scene: Sun, gradient sky, mountains, tree
                // Sun
                paint.color = Color.parseColor("#F59E0B")
                canvas.drawCircle(80f, 60f, 28f, paint)

                // Mountain 1 (Purple/Navy)
                paint.color = Color.parseColor("#4338CA")
                val path1 = android.graphics.Path().apply {
                    moveTo(20f, 190f)
                    lineTo(130f, 75f)
                    lineTo(240f, 190f)
                    close()
                }
                canvas.drawPath(path1, paint)

                // Mountain 2 (Teal)
                paint.color = Color.parseColor("#0D9488")
                val path2 = android.graphics.Path().apply {
                    moveTo(140f, 190f)
                    lineTo(230f, 95f)
                    lineTo(320f, 190f)
                    close()
                }
                canvas.drawPath(path2, paint)

                // Foreground Ground
                paint.color = Color.parseColor("#15803D")
                canvas.drawRect(0f, 180f, width.toFloat(), height.toFloat(), paint)

                // Tree trunk & leaves
                paint.color = Color.parseColor("#78350F")
                canvas.drawRect(280f, 140f, 292f, 190f, paint)
                paint.color = Color.parseColor("#16A34A")
                canvas.drawCircle(286f, 130f, 22f, paint)

                paint.color = Color.parseColor("#111827")
                paint.textSize = 12f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("Continuous Tone & Artwork Sample", 16f, 225f, paint)
            }
        }

        return bitmap
    }
}
