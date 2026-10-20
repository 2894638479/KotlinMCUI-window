package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.input.Key
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What one window must not share with the next.
 *
 * Every case is written against two instances rather than against the process, because that is the
 * distinction a second window turns on: the surface a scale is derived from, the pressed keys, the
 * cursor - and, in the other direction, the caches that are deliberately shared instead.
 *
 * These are the criteria that can be written today. Two windows on screen at once needs the
 * framework's root support, not just this backend's instances.
 */
class WindowIsolationTest {
    /** Any component will do as an event source; nothing here asks it to be displayable. */
    private val source = JPanel()

    private fun keyEvent(keyCode: Int) =
        KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, keyCode, KeyEvent.CHAR_UNDEFINED)

    @Test
    fun `a renderer derives its scale from its own surface`() {
        val small = WindowRenderer()
        val large = WindowRenderer()
        small.surfaceWidth = 960
        small.surfaceHeight = 540
        large.surfaceWidth = 1920
        large.surfaceHeight = 1080

        assertEquals(guiScaleFor(960, 540), small.guiScale)
        assertEquals(guiScaleFor(1920, 1080), large.guiScale)
        assertTrue(large.guiScale > small.guiScale)

        // Resizing one window is not resizing the other, which is what a shared surface would say.
        small.surfaceWidth = 800
        small.surfaceHeight = 600
        assertEquals(guiScaleFor(1920, 1080), large.guiScale)
    }

    @Test
    fun `an input keeps its own pressed keys`() {
        val first = WindowInput()
        val second = WindowInput()
        runCatching { first.keyPressed(keyEvent(KeyEvent.VK_A)) }

        assertTrue(first.isKeyDown(Key.A))
        assertFalse(second.isKeyDown(Key.A))

        first.releaseAll()
        assertFalse(first.isKeyDown(Key.A))
    }

    @Test
    fun `a cursor moves in the window it moved in`() {
        val first = WindowInput()
        val second = WindowInput()
        runCatching { first.mouseMoved(7, 11) }

        assertEquals(7.0, first.mouse.x.raw, 0.0)
        assertEquals(0.0, second.mouse.x.raw, 0.0)
    }

    @Test
    fun `a font is cached for the process rather than per window`() {
        assertSame(WindowRenderer().getFont("SansSerif"), WindowRenderer().getFont("SansSerif"))
    }

    @Test
    fun `a local image is cached for the process rather than per window`() {
        val file = File(System.getProperty("java.io.tmpdir"), "kotlinmcuiwindow-isolation.png")
        file.deleteOnExit()
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", file)
        WindowImages.reset()
        WindowImages.executor = Executor { it.run() }
        WindowImages.publish = { it() }
        try {
            WindowUtils().localImage(file) // the first ask starts the read
            val holder = WindowUtils().localImage(file) // the inline read has finished
            assertFalse(holder.isEmpty)
            assertSame(holder, WindowUtils().localImage(file), "the next window must not decode a copy")
        } finally {
            WindowImages.reset()
        }
    }
}
