package io.github.u2894638479.kotlinmcuiwindow

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The language lookup, against the core library's own files: they are on this classpath because this
 * backend compiles against the library, and what they say is what a window shows.
 */
class WindowTranslationsTest {
    @Test
    fun `a key the framework's files carry is resolved`() {
        val text = requireNotNull(WindowTranslations.get("kotlinmcui.layout", emptyArray())) {
            "the core library's language files are on the test classpath"
        }
        // Which file wins depends on this machine's locale; that the key itself is gone is the point.
        assertFalse(text.startsWith("kotlinmcui."), "an unknown key would come back as itself")
    }

    @Test
    fun `a key no file carries is null, so the framework shows the key`() {
        assertNull(WindowTranslations.get("kotlinmcui.no.such.key", emptyArray()))
    }

    @Test
    fun `format arguments are substituted`() {
        val text = requireNotNull(WindowTranslations.get("kotlinmcui.test_translate", arrayOf<Any?>("A", 7, 1.5)))
        assertTrue(text.contains("A"), text)
        assertTrue(text.contains("7"), text)
        assertTrue(text.contains("1.5"), text)
    }

    @Test
    fun `arguments that do not fit the placeholders leave the text alone`() {
        // A `%d` handed a string is Java's own failure; the frame must survive it.
        val text = requireNotNull(WindowTranslations.get("kotlinmcui.test_translate", arrayOf("A")))
        assertTrue(text.isNotEmpty())
    }
}
