package com.zellahq.core.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FontNamesTest {
    // Real fonts from macOS. On a machine without them (Linux CI) these two tests are skipped.
    private val ttf = File("/System/Library/Fonts/Supplemental/Arial.ttf")
    private val ttc = File("/System/Library/Fonts/Helvetica.ttc")

    @Test fun readsATrueTypeFont() {
        assumeTrue(ttf.isFile)
        assertEquals("ArialMT", FontNames.postScriptName(ttf.readBytes()))
    }

    @Test fun readsTheFirstFontOfACollection() {
        assumeTrue(ttc.isFile)
        assertEquals("Helvetica", FontNames.postScriptName(ttc.readBytes()))
    }

    @Test fun anythingElseIsNotAFont() {
        assertNull(FontNames.postScriptName("not a font at all".toByteArray()))
        assertNull(FontNames.postScriptName(ByteArray(0)))
    }
}
