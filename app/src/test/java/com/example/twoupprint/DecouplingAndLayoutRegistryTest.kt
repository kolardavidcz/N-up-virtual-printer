package com.example.twoupprint

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class DecouplingAndLayoutRegistryTest {

    @Test
    fun testBuiltInLayoutsContainsOnlyValidNUpGrids() {
        val layouts = LayoutRegistry.builtInLayouts
        assertEquals("There should be exactly 5 built-in N-up layouts", 5, layouts.size)

        val layoutIds = layouts.map { it.printerId }
        val expectedIds = listOf("nup_2x1", "nup_1x2", "nup_2x2", "nup_2x3", "nup_2x4")
        assertEquals(expectedIds, layoutIds)

        assertFalse(
            "nup_notewise must not exist in builtInLayouts",
            layoutIds.contains("nup_notewise")
        )

        for (layout in layouts) {
            assertTrue("Layout ${layout.printerId} cols must be >= 1", layout.cols >= 1)
            assertTrue("Layout ${layout.printerId} rows must be >= 1", layout.rows >= 1)
            assertTrue("Layout ${layout.printerId} pagesPerSheet must be >= 2", layout.pagesPerSheet >= 2)
            assertFalse("Built-in layout ${layout.printerId} must not be marked as custom", layout.isCustom)
        }
    }

    @Test
    fun testFindLayoutByIdFallback() {
        val defaultLayout = LayoutRegistry.builtInLayouts.first()
        val context = DummyTestContext()

        val foundNull = LayoutRegistry.findLayoutById(context, null)
        assertEquals(defaultLayout.printerId, foundNull.printerId)

        val foundNotewise = LayoutRegistry.findLayoutById(context, "nup_notewise")
        assertEquals(defaultLayout.printerId, foundNotewise.printerId)

        val foundUnknown = LayoutRegistry.findLayoutById(context, "non_existent_layout_id")
        assertEquals(defaultLayout.printerId, foundUnknown.printerId)

        val found2x2 = LayoutRegistry.findLayoutById(context, "nup_2x2")
        assertEquals("nup_2x2", found2x2.printerId)
    }

    @Test
    fun testInferDefaults() {
        // Square grid (cols == rows) -> portrait sheet, portrait subpages
        val (sheetSquare, subSquare) = LayoutRegistry.inferDefaults(2, 2)
        assertFalse("Square grid sheet should be portrait (landscape=false)", sheetSquare)
        assertFalse("Square grid subpages should be portrait (subPageLandscape=false)", subSquare)

        // Horizontal grid (cols > rows) -> landscape sheet, portrait subpages
        val (sheetHoriz, subHoriz) = LayoutRegistry.inferDefaults(2, 1)
        assertTrue("Horizontal grid sheet should be landscape (landscape=true)", sheetHoriz)
        assertFalse("Horizontal grid subpages should be portrait (subPageLandscape=false)", subHoriz)

        // Vertical grid (rows > cols) -> portrait sheet, landscape subpages
        val (sheetVert, subVert) = LayoutRegistry.inferDefaults(1, 2)
        assertFalse("Vertical grid sheet should be portrait (landscape=false)", sheetVert)
        assertTrue("Vertical grid subpages should be landscape (subPageLandscape=true)", subVert)
    }

    @Test
    fun testDeadFilesAreEliminatedFromRepository() {
        val projectRoot = findProjectRoot()
        assertNotNull("Project root must be found", projectRoot)

        val layoutModeFile = File(projectRoot, "app/src/main/java/com/example/twoupprint/LayoutMode.kt")
        assertFalse("LayoutMode.kt must be deleted from codebase", layoutModeFile.exists())

        val bwBinarizerFile = File(projectRoot, "app/src/main/java/com/example/twoupprint/BwBinarizer.kt")
        assertFalse("BwBinarizer.kt must be deleted from codebase", bwBinarizerFile.exists())
    }

    @Test
    fun testCorePrintingClassesHaveZeroNotewiseReferences() {
        val projectRoot = findProjectRoot()
        assertNotNull("Project root must be found", projectRoot)

        val coreSourceDir = File(projectRoot, "app/src/main/java/com/example/twoupprint")
        val targetFiles = listOf(
            File(coreSourceDir, "PrintJobHandler.kt"),
            File(coreSourceDir, "TwoUpPrintService.kt"),
            File(coreSourceDir, "TwoUpDiscoverySession.kt"),
            File(coreSourceDir, "LayoutRegistry.kt")
        )

        for (file in targetFiles) {
            assertTrue("File ${file.name} must exist at ${file.absolutePath}", file.exists())
            val content = file.readText()

            assertFalse(
                "File ${file.name} must not import Notewise classes",
                content.contains("com.example.twoupprint.notewise")
            )
            assertFalse(
                "File ${file.name} must not reference nup_notewise",
                content.contains("nup_notewise")
            )
        }
    }

    private class DummyTestContext : ContextWrapper(null) {
        private val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, _ ->
            if (method.name == "apply" || method.name == "commit") null else proxy
        } as SharedPreferences.Editor

        private val prefsProxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getInt" -> if (args?.getOrNull(0) == "prefs_schema_version") 2 else args?.getOrNull(1) ?: 0
                "getBoolean" -> args?.getOrNull(1) ?: false
                "getString" -> args?.getOrNull(1)
                "getStringSet" -> args?.getOrNull(1) ?: emptySet<String>()
                "edit" -> editorProxy
                else -> null
            }
        } as SharedPreferences

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsProxy
        }
    }

    private fun findProjectRoot(): File? {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }
}
