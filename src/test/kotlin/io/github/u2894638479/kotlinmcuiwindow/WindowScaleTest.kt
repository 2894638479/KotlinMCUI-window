package io.github.u2894638479.kotlinmcuiwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowScaleTest {
    @Test
    fun `the default window is scaled by the smaller of its two ratios`() {
        // 960 / 320 = 3 and 540 / 240 = 2.25, so the height is what binds.
        assertEquals(2.25, guiScaleFor(960, 540))
    }

    @Test
    fun `the scale keeps its fraction instead of rounding to a whole number`() {
        // The game would call this 2; anti-aliased glyphs let this backend use the whole surface.
        val scale = guiScaleFor(1000, 500)
        assertTrue(scale > 2.0 && scale < 2.25, "expected a fraction between 2 and 2.25, was $scale")
        assertEquals(500 / 240.0, scale)
    }

    @Test
    fun `a window too small for one unit stays at one`() {
        assertEquals(1.0, guiScaleFor(320, 240))
        assertEquals(1.0, guiScaleFor(100, 100))
    }

    @Test
    fun `a surface that has not been painted yet is one`() {
        // The root provider sets the size in frame(), so a page built before the first frame sees this.
        assertEquals(1.0, guiScaleFor(0, 0))
    }

    @Test
    fun `growing the window never lowers the scale`() {
        val before = guiScaleFor(960, 540)
        assertEquals(4.5, guiScaleFor(1920, 1080))
        assertTrue(guiScaleFor(1920, 1080) > before)
        assertTrue(guiScaleFor(1440, 810) > before)
    }
}
