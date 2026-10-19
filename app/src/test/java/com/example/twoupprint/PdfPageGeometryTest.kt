package com.example.twoupprint

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageGeometryTest {

    @Test
    fun testNormalizeRotation() {
        assertEquals(0, PdfPageGeometry.normalizeRotation(0))
        assertEquals(90, PdfPageGeometry.normalizeRotation(90))
        assertEquals(180, PdfPageGeometry.normalizeRotation(180))
        assertEquals(270, PdfPageGeometry.normalizeRotation(270))
        assertEquals(0, PdfPageGeometry.normalizeRotation(360))
        assertEquals(90, PdfPageGeometry.normalizeRotation(450))
        assertEquals(270, PdfPageGeometry.normalizeRotation(-90))
        assertEquals(180, PdfPageGeometry.normalizeRotation(-180))
        assertEquals(90, PdfPageGeometry.normalizeRotation(-270))
    }

    @Test
    fun testGetVisualBounds() {
        val cropBox = PDRectangle(500f, 800f) // width=500, height=800

        // 0 degrees rotation
        val bounds0 = PdfPageGeometry.getVisualBounds(cropBox, 0)
        assertEquals(500f, bounds0.visualW, 0.001f)
        assertEquals(800f, bounds0.visualH, 0.001f)
        assertFalse(bounds0.isRot90or270)

        // 90 degrees rotation (dimensions swapped in visual space)
        val bounds90 = PdfPageGeometry.getVisualBounds(cropBox, 90)
        assertEquals(800f, bounds90.visualW, 0.001f)
        assertEquals(500f, bounds90.visualH, 0.001f)
        assertTrue(bounds90.isRot90or270)

        // 180 degrees rotation (same visual aspect)
        val bounds180 = PdfPageGeometry.getVisualBounds(cropBox, 180)
        assertEquals(500f, bounds180.visualW, 0.001f)
        assertEquals(800f, bounds180.visualH, 0.001f)
        assertFalse(bounds180.isRot90or270)

        // 270 degrees rotation
        val bounds270 = PdfPageGeometry.getVisualBounds(cropBox, 270)
        assertEquals(800f, bounds270.visualW, 0.001f)
        assertEquals(500f, bounds270.visualH, 0.001f)
        assertTrue(bounds270.isRot90or270)
    }

    @Test
    fun testCreateUprightFormMatrixValues() {
        fun extractMatrixFloats(cosArray: COSArray): List<Float> {
            return (0 until cosArray.size()).map { (cosArray.get(it) as COSFloat).floatValue() }
        }

        val rawX = 10f
        val rawY = 20f
        val rawW = 300f
        val rawH = 400f

        // 0°: [1, 0, 0, 1, -rawX, -rawY]
        val m0 = extractMatrixFloats(PdfPageGeometry.createUprightFormMatrix(rawX, rawY, rawW, rawH, 0))
        assertEquals(listOf(1f, 0f, 0f, 1f, -10f, -20f), m0)

        // 90°: [0, -1, 1, 0, -rawY, rawX + rawW]
        val m90 = extractMatrixFloats(PdfPageGeometry.createUprightFormMatrix(rawX, rawY, rawW, rawH, 90))
        assertEquals(listOf(0f, -1f, 1f, 0f, -20f, 310f), m90)

        // 180°: [-1, 0, 0, -1, rawX + rawW, rawY + rawH]
        val m180 = extractMatrixFloats(PdfPageGeometry.createUprightFormMatrix(rawX, rawY, rawW, rawH, 180))
        assertEquals(listOf(-1f, 0f, 0f, -1f, 310f, 420f), m180)

        // 270°: [0, 1, -1, 0, rawY + rawH, -rawX]
        val m270 = extractMatrixFloats(PdfPageGeometry.createUprightFormMatrix(rawX, rawY, rawW, rawH, 270))
        assertEquals(listOf(0f, 1f, -1f, 0f, 420f, -10f), m270)
    }

    @Test
    fun testMapRawToVisual() {
        val rawX = 0f
        val rawY = 0f
        val rawW = 100f
        val rawH = 200f

        // Lower-left (0, 0)
        val p0 = PdfPageGeometry.mapRawToVisual(0f, 0f, rawX, rawY, rawW, rawH, 0)
        assertEquals(Pair(0f, 0f), p0)

        // Under 90 deg rotation, (0, 0) maps to (y - rawY, rawX + rawW - x) = (0, 100)
        val p90 = PdfPageGeometry.mapRawToVisual(0f, 0f, rawX, rawY, rawW, rawH, 90)
        assertEquals(Pair(0f, 100f), p90)

        // Under 180 deg rotation, (0, 0) maps to (rawX + rawW - x, rawY + rawH - y) = (100, 200)
        val p180 = PdfPageGeometry.mapRawToVisual(0f, 0f, rawX, rawY, rawW, rawH, 180)
        assertEquals(Pair(100f, 200f), p180)

        // Under 270 deg rotation, (0, 0) maps to (rawY + rawH - y, x - rawX) = (200, 0)
        val p270 = PdfPageGeometry.mapRawToVisual(0f, 0f, rawX, rawY, rawW, rawH, 270)
        assertEquals(Pair(200f, 0f), p270)
    }

    @Test
    fun testCalculateSlotPlacement() {
        val visualW = 100f
        val visualH = 200f
        val slotWidth = 200f
        val slotHeight = 200f

        // Fitting 100x200 into 200x200: scale = 1.0 (limited by height)
        val placement = PdfPageGeometry.calculateSlotPlacement(
            visualW = visualW,
            visualH = visualH,
            slotLeft = 50f,
            slotBottom = 50f,
            slotWidth = slotWidth,
            slotHeight = slotHeight,
            spaceDistributionMode = SpaceDistributionMode.CENTER
        )

        assertEquals(1.0f, placement.scale, 0.001f)
        assertEquals(100f, placement.destW, 0.001f)
        assertEquals(200f, placement.destH, 0.001f)
        // Center offset for spare width = 50 + (200 - 100) * 0.5 = 100
        assertEquals(100f, placement.tx, 0.001f)
        // Center offset for Y = 50 + (200 - 200) / 2 = 50
        assertEquals(50f, placement.ty, 0.001f)
    }

    @Test
    fun testCalculateTileVisualBounds() {
        val visualW = 600f
        val visualH = 400f
        val cols = 2
        val rows = 2

        // Row 0 (top), Col 0 (left)
        val topLeft = PdfPageGeometry.calculateTileVisualBounds(visualW, visualH, col = 0, row = 0, cols = cols, rows = rows)
        assertEquals(0f, topLeft.minX, 0.001f)
        assertEquals(300f, topLeft.maxX, 0.001f)
        assertEquals(200f, topLeft.minY, 0.001f)
        assertEquals(400f, topLeft.maxY, 0.001f)

        // Row 0 (top), Col 1 (right)
        val topRight = PdfPageGeometry.calculateTileVisualBounds(visualW, visualH, col = 1, row = 0, cols = cols, rows = rows)
        assertEquals(300f, topRight.minX, 0.001f)
        assertEquals(600f, topRight.maxX, 0.001f)
        assertEquals(200f, topRight.minY, 0.001f)
        assertEquals(400f, topRight.maxY, 0.001f)

        // Row 1 (bottom), Col 0 (left)
        val bottomLeft = PdfPageGeometry.calculateTileVisualBounds(visualW, visualH, col = 0, row = 1, cols = cols, rows = rows)
        assertEquals(0f, bottomLeft.minX, 0.001f)
        assertEquals(300f, bottomLeft.maxX, 0.001f)
        assertEquals(0f, bottomLeft.minY, 0.001f)
        assertEquals(200f, bottomLeft.maxY, 0.001f)

        // Row 1 (bottom), Col 1 (right)
        val bottomRight = PdfPageGeometry.calculateTileVisualBounds(visualW, visualH, col = 1, row = 1, cols = cols, rows = rows)
        assertEquals(300f, bottomRight.minX, 0.001f)
        assertEquals(600f, bottomRight.maxX, 0.001f)
        assertEquals(0f, bottomRight.minY, 0.001f)
        assertEquals(200f, bottomRight.maxY, 0.001f)
    }

    @Test
    fun testCalculateTileShift() {
        val tileBounds = VisualRect(minX = 100f, minY = 200f, maxX = 300f, maxY = 400f)
        val placement = SlotPlacement(scale = 2.0f, destW = 400f, destH = 400f, tx = 50f, ty = 80f)

        val (shiftX, shiftY) = PdfPageGeometry.calculateTileShift(placement, tileBounds)
        // shiftX = tx - minX * scale = 50 - 100 * 2 = -150
        assertEquals(-150f, shiftX, 0.001f)
        // shiftY = ty - minY * scale = 80 - 200 * 2 = -320
        assertEquals(-320f, shiftY, 0.001f)
    }

    @Test
    fun testTransformVisualRectToTile() {
        val tileBounds = VisualRect(minX = 100f, minY = 100f, maxX = 300f, maxY = 300f)
        val destLeft = 50f
        val destBottom = 50f
        val scale = 1.5f

        // Case 1: Fully inside tile
        val insideRect = VisualRect(minX = 120f, minY = 120f, maxX = 180f, maxY = 180f)
        val outInside = PdfPageGeometry.transformVisualRectToTile(insideRect, tileBounds, destLeft, destBottom, scale)
        org.junit.Assert.assertNotNull(outInside)
        // lowerLeftX = destLeft + (ox1 - tileBounds.minX) * scale = 50 + (120 - 100) * 1.5 = 50 + 30 = 80
        assertEquals(80f, outInside!!.lowerLeftX, 0.001f)
        assertEquals(80f, outInside.lowerLeftY, 0.001f)
        // upperRightX = destLeft + (ox2 - tileBounds.minX) * scale = 50 + (180 - 100) * 1.5 = 50 + 120 = 170
        assertEquals(170f, outInside.upperRightX, 0.001f)
        assertEquals(170f, outInside.upperRightY, 0.001f)

        // Case 2: Completely outside tile
        val outsideRect = VisualRect(minX = 400f, minY = 400f, maxX = 500f, maxY = 500f)
        val outOutside = PdfPageGeometry.transformVisualRectToTile(outsideRect, tileBounds, destLeft, destBottom, scale)
        org.junit.Assert.assertNull(outOutside)

        // Case 3: Partially overlapping tile (spanning across right edge)
        val partialRect = VisualRect(minX = 250f, minY = 150f, maxX = 350f, maxY = 250f)
        val outPartial = PdfPageGeometry.transformVisualRectToTile(partialRect, tileBounds, destLeft, destBottom, scale)
        org.junit.Assert.assertNotNull(outPartial)
        // ox1 = 250, ox2 = 300 (clipped to tileBounds.maxX)
        assertEquals(50f + (250f - 100f) * 1.5f, outPartial!!.lowerLeftX, 0.001f)
        assertEquals(50f + (300f - 100f) * 1.5f, outPartial.upperRightX, 0.001f)
    }
}
