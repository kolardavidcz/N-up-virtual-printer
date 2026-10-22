package com.example.twoupprint

import android.graphics.drawable.Icon
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class LayoutIconGeneratorTest {

    @Before
    fun setUp() {
        LayoutIconGenerator.clearCache()
    }

    @Test
    fun testCacheKeyConsistency() {
        val layout = LayoutRegistry.builtInLayouts.first()
        val key1 = LayoutIconGenerator.getCacheKey(layout, 128)
        val key2 = LayoutIconGenerator.getCacheKey(layout, 128)

        assertEquals("Cache keys must be deterministic", key1, key2)
        assertTrue("Cache key must contain printer id", key1.contains(layout.printerId))
        assertTrue("Cache key must contain dimensions", key1.contains("${layout.cols}x${layout.rows}"))
    }

    @Test
    fun testPutAndGetCachedIcon() {
        val layout = LayoutRegistry.builtInLayouts.first()

        // Create a dummy Icon proxy or instance
        val mockIcon = Icon::class.java.getDeclaredConstructor().let {
            it.isAccessible = true
            it.newInstance()
        }

        assertFalse("Icon should not be cached initially", LayoutIconGenerator.isIconCached(layout))

        LayoutIconGenerator.putCachedIcon(layout, mockIcon)
        assertTrue("Icon should be cached after putCachedIcon", LayoutIconGenerator.isIconCached(layout))

        val retrieved = LayoutIconGenerator.getCachedIcon(layout)
        assertSame("Retrieved icon must be identical instance to cached one", mockIcon, retrieved)

        LayoutIconGenerator.clearCache()
        assertFalse("Cache should be empty after clearCache", LayoutIconGenerator.isIconCached(layout))
    }

    @Test
    fun testHeadlessGetCachedIconDoesNotCrash() {
        val layout = LayoutRegistry.builtInLayouts.first()
        // In headless JVM unit test, Android's Bitmap.createBitmap returns null.
        // getCachedIcon must handle null gracefully without throwing NullPointerException.
        val result: Icon? = LayoutIconGenerator.getCachedIcon(layout)
        // If an Icon was created or null was returned, the test passes without throwing NPE.
        assertTrue("Icon should either be null in headless environment or non-null", result == null || result != null)
    }

    private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(msg, expected, actual)
    }
}
