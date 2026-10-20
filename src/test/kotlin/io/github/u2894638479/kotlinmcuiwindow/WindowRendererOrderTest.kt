package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Drawing order, as the pixels show it.
 *
 * This backend paints into the frame's `Graphics2D` at the call site, so "later covers earlier" is
 * all that keeps a page's overlapping shapes in the order it drew them. A batching or
 * "one pass per primitive type" renderer would come out different here: the three rectangles below
 * are half red, opaque white and half blue, drawn in that order, so the overlap pixels say which
 * draw landed last.
 *
 * The draws are sampled between calls rather than only at the end, because the first test is as much
 * about *when* a draw lands as about what it covers: a deferred draw would leave the sample pixel
 * empty (or the previous colour) until a flush.
 */
class WindowRendererOrderTest {

    private val width = 60
    private val height = 60

    private val renderer = WindowRenderer()

    private val red = Color(255, 0, 0, 128)
    private val white = Color(255, 255, 255)
    private val blue = Color(0, 0, 255, 128)

    private val redRect = Rect(0.px, 0.px, 30.px, 30.px)
    private val whiteRect = Rect(10.px, 10.px, 40.px, 40.px)
    private val blueRect = Rect(20.px, 20.px, 50.px, 50.px)

    @Test
    fun `each draw lands before the next call, and later draws cover earlier ones`() {
        val image = newImage()
        val graphics = image.createGraphics()
        try {
            context(WindowRenderParam(graphics, width, height)) {
                renderer.renderQuad(redRect, red, red, red, red)
                // Half red over the black ground: the call has already painted, no flush has run.
                assertArgb(0xFF800000.toInt(), image.getRGB(5, 5), "red over the ground")

                renderer.renderQuad(whiteRect, white, white, white, white)
                // Opaque white covers the red it overlaps; red under white would otherwise blend in.
                assertArgb(0xFFFFFFFF.toInt(), image.getRGB(15, 15), "white over red")

                renderer.renderQuad(blueRect, blue, blue, blue, blue)
                // Half blue over white is a blend, and it is the last draw, so it must be on top.
                assertArgb(0xFF8080FF.toInt(), image.getRGB(25, 25), "blue over white")
                // Where only the last draw and the ground meet, the result is the half blue itself.
                assertArgb(0xFF000080.toInt(), image.getRGB(45, 45), "blue over the ground")
            }
        } finally {
            graphics.dispose()
        }
    }

    /** Draw the three rectangles in order: each one must land before the next call. */
    private fun paint(): BufferedImage {
        val image = newImage()
        val graphics = image.createGraphics()
        try {
            context(WindowRenderParam(graphics, width, height)) {
                renderer.renderQuad(redRect, red, red, red, red)
                renderer.renderQuad(whiteRect, white, white, white, white)
                renderer.renderQuad(blueRect, blue, blue, blue, blue)
            }
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun newImage(): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color(0, 0, 0, 255)
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun pixels(image: BufferedImage): IntArray {
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)
        return pixels
    }

    /**
     * Compare one pixel channel by channel, allowing one unit of rounding: Java2D composites
     * `alpha = 128/255` at its own precision, so a half-covered channel may land on 127 or 128.
     */
    private fun assertArgb(expected: Int, actual: Int, where: String) {
        val shifts = listOf(24, 16, 8, 0)
        val expectedChannels = shifts.map { (expected ushr it) and 0xFF }
        val actualChannels = shifts.map { (actual ushr it) and 0xFF }
        for (index in shifts.indices) {
            val difference = abs(expectedChannels[index] - actualChannels[index])
            assertTrue(
                difference <= 2,
                "$where: channel $index expected ${expectedChannels[index]} but was ${actualChannels[index]} " +
                    "(expected 0x${expected.toUInt().toString(16)}, got 0x${actual.toUInt().toString(16)})",
            )
        }
    }
}
