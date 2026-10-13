package io.github.u2894638479.kotlinmcuiwindow

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stack's floor rule. Getting this wrong is not visible from the window's title - an empty stack
 * keeps the title it had - but it leaves a window that repaints nothing and cannot be returned from.
 */
class WindowFrameTest {

    @Test
    fun `the bottom page is the floor`() {
        assertFalse(endsPage(1), "closing the only page must put the window away, not end the page")
    }

    @Test
    fun `a page with one below it is ended`() {
        assertTrue(endsPage(2))
        assertTrue(endsPage(7))
    }

    @Test
    fun `an empty stack ends nothing`() {
        assertFalse(endsPage(0))
    }
}
