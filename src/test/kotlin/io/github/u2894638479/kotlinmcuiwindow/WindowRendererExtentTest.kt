package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The destination extent a quad is filled at, through the real Java2D renderer.
 *
 * Two finite edges are not enough for a fillable rectangle: `-Float.MAX_VALUE` to `Float.MAX_VALUE`
 * subtracts to an infinite width or height, which the input contract rejects just as it rejects a
 * non-positive extent. A rejected quad must leave the surface untouched, and the textured overload
 * must reject it before it touches the texture store - so a resource is never scheduled for a draw
 * that cannot happen. Each case is drawn by the renderer into a real image and read back, never by
 * repeating the width and height arithmetic here.
 */
class WindowRendererExtentTest {
    private val size = 32
    private val renderer = WindowRenderer()

    private val probe = ImageSource.Resource("kotlinmcuiwindow:probe.png")
    private val uv = Rect(0.px, 0.px, 16.px, 16.px)

    /** Reads the renderer scheduled through [WindowImages]; none may be started by a rejected quad. */
    private var reads = 0

    @BeforeTest
    fun runReadsInline() {
        WindowImages.reset()
        reads = 0
        WindowImages.executor = Executor {
            reads++
            it.run()
        }
        WindowImages.publish = { it() }
        // Every case starts with the texture uncached, so a lookup here would be a scheduled read.
        WindowTextures.forget(probe)
    }

    @AfterTest
    fun releaseTextures() {
        WindowTextures.forget(probe)
        WindowImages.reset()
    }

    private fun surface(): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

    private fun untouched(image: BufferedImage, where: String) {
        for (y in 0 until size) for (x in 0 until size) {
            assertEquals(0, image.getRGB(x, y) ushr 24, "$where: pixel ($x, $y) should have stayed untouched")
        }
    }

    private fun drawColored(graphics: Graphics2D, rect: Rect) {
        val red = Color(255, 0, 0)
        context(WindowRenderParam(graphics)) { renderer.renderQuad(rect, red, red, red, red) }
    }

    /** Draws twice: the first textured call only schedules the read, the second samples it. */
    private fun drawTextured(graphics: Graphics2D, rect: Rect) {
        val holder = ImageHolder(probe, 16.px, 16.px)
        context(WindowRenderParam(graphics)) {
            renderer.renderQuad(rect, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, holder, uv)
        }
        context(WindowRenderParam(graphics)) {
            renderer.renderQuad(rect, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, holder, uv)
        }
    }

    @Test
    fun `opposite huge horizontal edges leave a colour quad undrawn`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            // Finite edges, positive order, but the width between them overflows to infinity.
            drawColored(graphics, Rect((-Float.MAX_VALUE).px, 8.px, Float.MAX_VALUE.px, 24.px))
        } finally {
            graphics.dispose()
        }
        untouched(image, "infinite width")
    }

    @Test
    fun `opposite huge vertical edges leave a colour quad undrawn`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            drawColored(graphics, Rect(8.px, (-Float.MAX_VALUE).px, 24.px, Float.MAX_VALUE.px))
        } finally {
            graphics.dispose()
        }
        untouched(image, "infinite height")
    }

    @Test
    fun `opposite huge horizontal edges leave a textured quad undrawn and unscheduled`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            drawTextured(graphics, Rect((-Float.MAX_VALUE).px, 8.px, Float.MAX_VALUE.px, 24.px))
        } finally {
            graphics.dispose()
        }
        untouched(image, "infinite width")
        assertEquals(0, reads, "a quad with no finite width must be rejected before the texture store is asked")
    }

    @Test
    fun `opposite huge vertical edges leave a textured quad undrawn and unscheduled`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            drawTextured(graphics, Rect(8.px, (-Float.MAX_VALUE).px, 24.px, Float.MAX_VALUE.px))
        } finally {
            graphics.dispose()
        }
        untouched(image, "infinite height")
        assertEquals(0, reads, "a quad with no finite height must be rejected before the texture store is asked")
    }

    @Test
    fun `a normal colour quad still draws`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            drawColored(graphics, Rect(4.px, 4.px, 20.px, 20.px))
        } finally {
            graphics.dispose()
        }
        assertEquals(0xFFFF0000.toInt(), image.getRGB(12, 12), "an ordinary quad must still be filled")
        assertEquals(0, image.getRGB(2, 2) ushr 24, "the fill must stay inside the quad")
    }

    @Test
    fun `a normal textured quad still draws and reads its texture`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            drawTextured(graphics, Rect(4.px, 4.px, 20.px, 20.px))
        } finally {
            graphics.dispose()
        }
        assertTrue(reads > 0, "an ordinary textured quad may still schedule its read")
        assertNotEquals(0, image.getRGB(12, 12) ushr 24, "an ordinary textured quad must still be filled")
        assertEquals(0, image.getRGB(2, 2) ushr 24, "the fill must stay inside the quad")
    }
}
