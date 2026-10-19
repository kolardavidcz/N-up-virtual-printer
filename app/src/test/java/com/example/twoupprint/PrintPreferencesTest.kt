package com.example.twoupprint

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class PrintPreferencesTest {

    @Test
    fun testPreferencesDelegationAndDefaults() {
        val storage = mutableMapOf<String, Any>()

        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            val key = args?.getOrNull(0) as? String
            val value = args?.getOrNull(1)
            when (method.name) {
                "putString" -> if (key != null && value != null) storage[key] = value
                "putInt" -> if (key != null && value != null) storage[key] = value
                "putBoolean" -> if (key != null && value != null) storage[key] = value
                "putStringSet" -> if (key != null && value != null) storage[key] = value
                "remove" -> if (key != null) storage.remove(key)
                "apply", "commit" -> return@newProxyInstance null
            }
            proxy
        } as SharedPreferences.Editor

        @Suppress("UNCHECKED_CAST")
        val prefsProxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            val key = args?.getOrNull(0) as? String
            when (method.name) {
                "getInt" -> (storage[key] as? Int) ?: args?.getOrNull(1) ?: 0
                "getBoolean" -> (storage[key] as? Boolean) ?: args?.getOrNull(1) ?: false
                "getString" -> (storage[key] as? String) ?: args?.getOrNull(1)
                "getStringSet" -> (storage[key] as? Set<String>) ?: args?.getOrNull(1) ?: emptySet<String>()
                "edit" -> editorProxy
                else -> null
            }
        } as SharedPreferences

        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefsProxy
        }

        // Test Text Contrast preference
        assertFalse(PrintPreferences.isTextContrastEnabled(context))
        PrintPreferences.setTextContrastEnabled(context, true)
        assertTrue(PrintPreferences.isTextContrastEnabled(context))

        // Test Clickable Links preference
        assertFalse(PrintPreferences.isLinksEnabled(context))
        PrintPreferences.setLinksEnabled(context, true)
        assertTrue(PrintPreferences.isLinksEnabled(context))

        // Test Margins
        assertEquals(0, PrintPreferences.getMarginTopMm(context))
        PrintPreferences.setMargins(context, 3, 4, 5, 6)
        assertEquals(3, PrintPreferences.getMarginTopMm(context))
        assertEquals(4, PrintPreferences.getMarginBottomMm(context))
        assertEquals(5, PrintPreferences.getMarginLeftMm(context))
        assertEquals(6, PrintPreferences.getMarginRightMm(context))

        // Test Split Grid
        assertEquals(2, PrintPreferences.getSplitCols(context))
        assertEquals(1, PrintPreferences.getSplitRows(context))
        PrintPreferences.setSplitGrid(context, 3, 2)
        assertEquals(3, PrintPreferences.getSplitCols(context))
        assertEquals(2, PrintPreferences.getSplitRows(context))

        // Test Save Directory & Prompt Each Print
        PrintPreferences.setSaveDirectoryUri(context, "content://test/uri")
        assertEquals("content://test/uri", PrintPreferences.getSaveDirectoryUri(context))
        PrintPreferences.clearSaveDirectoryUri(context)
        assertEquals(null, PrintPreferences.getSaveDirectoryUri(context))

        assertFalse(PrintPreferences.isPromptEachPrintEnabled(context))
        PrintPreferences.setPromptEachPrintEnabled(context, true)
        assertTrue(PrintPreferences.isPromptEachPrintEnabled(context))

        // Test Custom Layout Validation and Persistence
        // 1. 1x1 should be rejected (< 2 pages)
        val rejected1x1 = PrintPreferences.addCustomLayout(context, 1, 1, landscape = false, subPageLandscape = false)
        assertFalse("1x1 grid must be rejected", rejected1x1)

        // 2. 2x1 should be rejected (matches built-in layout)
        val rejected2x1 = PrintPreferences.addCustomLayout(context, 2, 1, landscape = true, subPageLandscape = false)
        assertFalse("2x1 built-in duplicate must be rejected", rejected2x1)

        // 3. 3x3 should be accepted
        val accepted3x3 = PrintPreferences.addCustomLayout(context, 3, 3, landscape = false, subPageLandscape = false)
        assertTrue("3x3 custom layout must be accepted", accepted3x3)
        val customLayouts = PrintPreferences.getCustomLayouts(context)
        assertEquals(1, customLayouts.size)
        assertEquals("custom_3x3", customLayouts[0].printerId)
        assertEquals(9, customLayouts[0].pagesPerSheet)

        // 4. Duplicate 3x3 should be rejected
        val duplicate3x3 = PrintPreferences.addCustomLayout(context, 3, 3, landscape = false, subPageLandscape = false)
        assertFalse("Duplicate 3x3 custom layout must be rejected", duplicate3x3)

        // 5. Remove custom layout and verify cleanup
        PrintPreferences.setLayoutOrientation(context, "custom_3x3", true)
        assertEquals(true, PrintPreferences.getLayoutOrientation(context, "custom_3x3", false))
        PrintPreferences.removeCustomLayout(context, "custom_3x3")
        assertTrue(PrintPreferences.getCustomLayouts(context).isEmpty())
        assertFalse("Removed custom layout orientation should be cleaned up", storage.containsKey("orientation_custom_3x3"))
    }

    @Test
    fun testPrintJobHandlerExecutorProperties() {
        val executor = PrintJobHandler.getExecutor()
        assertNotNull("PrintJobHandler background executor must not be null", executor)
        assertFalse("PrintJobHandler background executor must not be shut down", executor.isShutdown)

        // Verify task dispatch returns a Future
        var ran = false
        val future = executor.submit {
            ran = true
        }
        future.get(1, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Task submitted to executor should run", ran)
    }
}
