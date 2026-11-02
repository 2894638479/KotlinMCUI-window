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
    fun `an empty target still has a finite scale`() {
        // A minimised window has no pixels, but its root scale must not become zero or non-finite:
        // empty output is expressed by the viewport, never by pausing the scale.
        assertEquals(1.0, guiScaleFor(0, 0))
    }

    @Test
    fun `growing the window never lowers the scale`() {
        val before = guiScaleFor(960, 540)
        assertEquals(4.5, guiScaleFor(1920, 1080))
        assertTrue(guiScaleFor(1920, 1080) > before)
        assertTrue(guiScaleFor(1440, 810) > before)
    }

    @Test
    fun `an output extent is the target's real backing size`() {
        // The two axes are measured separately, so a non-uniform display keeps both fractions.
        assertEquals(480, outputExtent(320, 1.5))
        assertEquals(384, outputExtent(240, 1.6))
        // A partial last pixel still needs a bitmap pixel: 101 * 1.25 = 126.25 and 61 * 1.5 = 91.5.
        assertEquals(127, outputExtent(101, 1.25))
        assertEquals(92, outputExtent(61, 1.5))
        // An exact whole extent does not gain a pixel through floating point.
        assertEquals(480, outputExtent(384, 1.25))
        // A target that has no pixels, or a transform that cannot produce any, is empty output.
        assertEquals(0, outputExtent(0, 1.5))
        assertEquals(0, outputExtent(320, 0.0))
        assertEquals(0, outputExtent(320, Double.NaN))
        assertEquals(0, outputExtent(320, Double.POSITIVE_INFINITY))
    }
}
