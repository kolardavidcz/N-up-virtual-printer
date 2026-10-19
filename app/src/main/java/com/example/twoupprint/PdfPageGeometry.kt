package com.example.twoupprint

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject

/**
 * Visual bounding metrics for a PDF page in upright coordinates [0, 0, visualW, visualH].
 */
data class PageVisualBounds(
    val rawW: Float,
    val rawH: Float,
    val rawX: Float,
    val rawY: Float,
    val rotation: Int,
    val visualW: Float,
    val visualH: Float
) {
    val isRot90or270: Boolean get() = (rotation == 90 || rotation == 270)
}

/**
 * Upright 2D bounding rectangle in visual space [0, 0, visualW, visualH].
 */
data class VisualRect(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float
) {
    val width: Float get() = (maxX - minX).coerceAtLeast(0f)
    val height: Float get() = (maxY - minY).coerceAtLeast(0f)
}

/**
 * Slot positioning and scale metrics for placing a visual page or tile within a designated slot.
 */
data class SlotPlacement(
    val scale: Float,
    val destW: Float,
    val destH: Float,
    val tx: Float,
    val ty: Float
)

/**
 * Unified mathematical geometry engine for PDF page transformations,
 * rotation normalization, visual coordinate mapping, and slot placement.
 */
object PdfPageGeometry {

    /**
     * Normalizes rotation angle to canonical 0, 90, 180, or 270 degrees.
     */
    fun normalizeRotation(rotation: Int): Int {
        return ((rotation % 360) + 360) % 360
    }

    /**
     * Extracts visual and raw bounding metrics from [cropBox] and [rotation].
     */
    fun getVisualBounds(cropBox: PDRectangle, rotation: Int): PageVisualBounds {
        val normRot = normalizeRotation(rotation)
        val isRot90or270 = (normRot == 90 || normRot == 270)
        val rawW = cropBox.width
        val rawH = cropBox.height
        val rawX = cropBox.lowerLeftX
        val rawY = cropBox.lowerLeftY
        val visualW = if (isRot90or270) rawH else rawW
        val visualH = if (isRot90or270) rawW else rawH

        return PageVisualBounds(
            rawW = rawW,
            rawH = rawH,
            rawX = rawX,
            rawY = rawY,
            rotation = normRot,
            visualW = visualW,
            visualH = visualH
        )
    }

    /**
     * Extracts visual and raw bounding metrics directly from a [PDPage].
     */
    fun getVisualBounds(page: PDPage): PageVisualBounds {
        val cropBox = page.cropBox ?: page.mediaBox ?: PDRectangle.A4
        return getVisualBounds(cropBox, page.rotation)
    }

    /**
     * Constructs a 6-element PDF Form affine transformation matrix array `[a, b, c, d, e, f]`
     * such that the Form XObject's content is rendered 100% upright starting from `(0, 0)`
     * with dimensions `[visualW, visualH]`.
     */
    fun createUprightFormMatrix(rawX: Float, rawY: Float, rawW: Float, rawH: Float, rotation: Int): COSArray {
        val normRot = normalizeRotation(rotation)
        val matrixArray = COSArray()
        when (normRot) {
            90 -> {
                // 90° clockwise in PDF: [0, -1, 1, 0, -rawY, rawX + rawW]
                matrixArray.add(COSFloat(0f))
                matrixArray.add(COSFloat(-1f))
                matrixArray.add(COSFloat(1f))
                matrixArray.add(COSFloat(0f))
                matrixArray.add(COSFloat(-rawY))
                matrixArray.add(COSFloat(rawX + rawW))
            }
            180 -> {
                // 180° rotation: [-1, 0, 0, -1, rawX + rawW, rawY + rawH]
                matrixArray.add(COSFloat(-1f))
                matrixArray.add(COSFloat(0f))
                matrixArray.add(COSFloat(0f))
                matrixArray.add(COSFloat(-1f))
                matrixArray.add(COSFloat(rawX + rawW))
                matrixArray.add(COSFloat(rawY + rawH))
            }
            270 -> {
                // 270° clockwise (90° CCW): [0, 1, -1, 0, rawY + rawH, -rawX]
                matrixArray.add(COSFloat(0f))
                matrixArray.add(COSFloat(1f))
                matrixArray.add(COSFloat(-1f))
                matrixArray.add(COSFloat(0f))
                matrixArray.add(COSFloat(rawY + rawH))
                matrixArray.add(COSFloat(-rawX))
            }
            else -> {
                // 0° rotation: [1, 0, 0, 1, -rawX, -rawY]
                matrixArray.add(COSFloat(1f))
                matrixArray.add(COSFloat(0f))
                matrixArray.add(COSFloat(0f))
                matrixArray.add(COSFloat(1f))
                matrixArray.add(COSFloat(-rawX))
                matrixArray.add(COSFloat(-rawY))
            }
        }
        return matrixArray
    }

    /**
     * Normalizes a [PDFormXObject] so its BBox is strictly `[0, 0, visualW, visualH]`
     * and its Form Matrix incorporates intrinsic rotation to guarantee upright rendering.
     * Returns the computed [PageVisualBounds].
     */
    fun applyUprightFormNormalization(form: PDFormXObject, cropBox: PDRectangle, rotation: Int): PageVisualBounds {
        val bounds = getVisualBounds(cropBox, rotation)
        form.bBox = PDRectangle(bounds.visualW, bounds.visualH)
        form.cosObject.setItem(
            COSName.MATRIX,
            createUprightFormMatrix(bounds.rawX, bounds.rawY, bounds.rawW, bounds.rawH, bounds.rotation)
        )
        return bounds
    }

    /**
     * Maps raw PDF coordinates `(x, y)` to upright visual coordinates `(vx, vy)`.
     */
    fun mapRawToVisual(
        x: Float,
        y: Float,
        rawX: Float,
        rawY: Float,
        rawW: Float,
        rawH: Float,
        rotation: Int
    ): Pair<Float, Float> {
        return when (normalizeRotation(rotation)) {
            90 -> Pair(y - rawY, rawX + rawW - x)
            180 -> Pair(rawX + rawW - x, rawY + rawH - y)
            270 -> Pair(rawY + rawH - y, x - rawX)
            else -> Pair(x - rawX, y - rawY)
        }
    }

    /**
     * Maps a raw PDF rectangle [rect] to upright visual coordinates [VisualRect].
     */
    fun mapRectToVisual(rect: PDRectangle, cropBox: PDRectangle, rotation: Int): VisualRect {
        val bounds = getVisualBounds(cropBox, rotation)
        val p1 = mapRawToVisual(rect.lowerLeftX, rect.lowerLeftY, bounds.rawX, bounds.rawY, bounds.rawW, bounds.rawH, bounds.rotation)
        val p2 = mapRawToVisual(rect.upperRightX, rect.upperRightY, bounds.rawX, bounds.rawY, bounds.rawW, bounds.rawH, bounds.rotation)
        return VisualRect(
            minX = Math.min(p1.first, p2.first),
            minY = Math.min(p1.second, p2.second),
            maxX = Math.max(p1.first, p2.first),
            maxY = Math.max(p1.second, p2.second)
        )
    }

    /**
     * Calculates scale, dimensions, and translation offsets for placing visual content
     * inside a slot, considering space distribution mode and margins.
     */
    fun calculateSlotPlacement(
        visualW: Float,
        visualH: Float,
        slotLeft: Float,
        slotBottom: Float,
        slotWidth: Float,
        slotHeight: Float,
        col: Int = 0,
        cols: Int = 1,
        spaceDistributionMode: SpaceDistributionMode = SpaceDistributionMode.CENTER,
        hasMargins: Boolean = false
    ): SlotPlacement {
        val scale = Math.min(slotWidth / visualW, slotHeight / visualH)
        val destW = visualW * scale
        val destH = visualH * scale

        val spareW = (slotWidth - destW).coerceAtLeast(0f)
        val offsetRatio = if (hasMargins && cols > 1) {
            val normCol = col.toFloat() / (cols - 1).toFloat()
            val bias = (normCol - 0.5f) * spaceDistributionMode.factor
            0.5f + bias
        } else {
            0.5f
        }

        val tx = slotLeft + spareW * offsetRatio
        val ty = slotBottom + (slotHeight - destH) / 2f
        return SlotPlacement(scale, destW, destH, tx, ty)
    }

    /**
     * Transforms a source rectangle [srcRect] to slot coordinates on the output page.
     */
    fun transformRectToSlot(
        srcRect: PDRectangle,
        cropBox: PDRectangle,
        slotLeft: Float,
        slotBottom: Float,
        slotWidth: Float,
        slotHeight: Float,
        col: Int = 0,
        cols: Int = 1,
        spaceDistributionMode: SpaceDistributionMode = SpaceDistributionMode.CENTER,
        hasMargins: Boolean = false,
        pageRotation: Int = 0
    ): PDRectangle {
        val bounds = getVisualBounds(cropBox, pageRotation)
        val vRect = mapRectToVisual(srcRect, cropBox, pageRotation)
        val placement = calculateSlotPlacement(
            visualW = bounds.visualW,
            visualH = bounds.visualH,
            slotLeft = slotLeft,
            slotBottom = slotBottom,
            slotWidth = slotWidth,
            slotHeight = slotHeight,
            col = col,
            cols = cols,
            spaceDistributionMode = spaceDistributionMode,
            hasMargins = hasMargins
        )

        return PDRectangle().apply {
            lowerLeftX = placement.tx + vRect.minX * placement.scale
            lowerLeftY = placement.ty + vRect.minY * placement.scale
            upperRightX = placement.tx + vRect.maxX * placement.scale
            upperRightY = placement.ty + vRect.maxY * placement.scale
        }
    }
}
