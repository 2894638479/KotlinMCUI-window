package io.github.u2894638479.kotlinmcuiwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The projection arithmetic, which is the half of `withTransform` Java2D has no place for. */
class WindowPerspectiveTest {
    @Test
    fun `an affine matrix is recognised as one Java2D could hold`() {
        assertTrue(perspective(m00 = 2f, m11 = 3f, m02 = 5f, m12 = 7f).isAffine)
        assertFalse(perspective(m20 = 0.001f).isAffine)
        assertFalse(perspective(m21 = 0.001f).isAffine)
        assertFalse(perspective(m22 = 2f).isAffine)
    }

    @Test
    fun `an affine matrix projects exactly as it multiplies`() {
        val affine = perspective(m00 = 2f, m11 = 3f, m02 = 5f, m12 = 7f)
        assertEquals(2f * 4f + 5f, affine.projectX(4f, 6f))
        assertEquals(3f * 6f + 7f, affine.projectY(4f, 6f))
    }

    @Test
    fun `the third row divides by w, which is the foreshortening`() {
        // w = 1 + 0.01x, so the scale of a step shrinks as the point moves right.
        val tilted = perspective(m20 = 0.01f)
        assertEquals(1f / 1.01f, tilted.projectX(1f, 0f), 1e-6f)
        assertEquals(2f / 1.02f, tilted.projectX(2f, 0f), 1e-6f)
        assertTrue(tilted.projectX(2f, 0f) - tilted.projectX(1f, 0f) < 1f)
    }

    @Test
    fun `concat applies the other transform first`() {
        // A translation of 10 followed by a scale of 2 is (p + 10) * 2, not p * 2 + 10.
        val scale = perspective(m00 = 2f, m11 = 2f)
        val translate = perspective(m02 = 10f)
        assertEquals((3f + 10f) * 2f, scale.concat(translate).projectX(3f, 0f))
    }

    @Test
    fun `a degenerate w leaves the point where it was`() {
        // w = 0 has no answer; dropping the point would blank a frame, so it is passed through.
        val degenerate = perspective(m20 = 0f, m21 = 0f, m22 = 0f)
        assertEquals(4f, degenerate.projectX(4f, 6f))
        assertEquals(6f, degenerate.projectY(4f, 6f))
    }

    private fun perspective(
        m00: Float = 1f, m01: Float = 0f, m02: Float = 0f,
        m10: Float = 0f, m11: Float = 1f, m12: Float = 0f,
        m20: Float = 0f, m21: Float = 0f, m22: Float = 1f,
    ) = WindowPerspective(m00, m01, m02, m10, m11, m12, m20, m21, m22)
}
