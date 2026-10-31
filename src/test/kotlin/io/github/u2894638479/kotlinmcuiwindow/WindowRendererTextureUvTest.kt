package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.ColorArray
import io.github.u2894638479.kotlinmcui.math.MeasureArray
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import io.github.u2894638479.kotlinmcui.math.transform.Transform
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * UV range, nominal sizing and tint opacity, drawn through the real Java2D backend.
 *
 * These are the gaps a projected draw and a differently-sized decode used to leave: a projected
 * shape ignored its UV and stretched the whole image, a ready image sampled UV as decoded pixels
 * instead of the holder's nominal ones, and a translucent tint was applied twice. Every assertion
 * reads pixels from a real [BufferedImage], not from a call recorder.
 */
class WindowRendererTextureUvTest {
    private val size = 64
    private val renderer = WindowRenderer()

    private val probe = ImageSource.Resource("kotlinmcuiwindow:probe.png")
    private val malformed = ImageSource.Resource("kotlinmcuiwindow:")

    private val magenta = 0xFFFF00FF.toInt()
    private val black = 0xFF000000.toInt()

    /** A shallow projective row: the projected quad stays close to its affine shape. */
    private val tilt = Transform(1f, 0f, 0f, 0f, 1f, 0f, 0.0001f, 0f, 1f)

    @BeforeTest
    fun runReadsInline() {
        WindowImages.reset()
        WindowImages.executor = Executor { it.run() }
        WindowImages.publish = { it() }
        WindowTextures.forget(probe)
        WindowTextures.forget(malformed)
    }

    @AfterTest
    fun releaseSources() {
        WindowTextures.forget(probe)
        WindowTextures.forget(malformed)
        WindowImages.reset()
    }

    private fun surface(): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

    private fun holder(source: ImageSource, width: Int = 16, height: Int = 16) =
        ImageHolder(source, width.px, height.px)

    private fun imageFile(width: Int, height: Int, name: String): File {
        val file = File(System.getProperty("java.io.tmpdir"), name)
        file.deleteOnExit()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    // Four distinct quadrants, so a stretched image and a tiled one differ everywhere.
                    val left = x < width / 2
                    val top = y < height / 2
                    setRGB(x, y, if (top) (if (left) 0xFFFF0000.toInt() else 0xFF00FF00.toInt())
                    else (if (left) 0xFF0000FF.toInt() else 0xFFFFFFFF.toInt()))
                }
            }
        }, "png", file)
        return file
    }

    private fun load(file: File) {
        assertTrue(WindowImages.holder(file).isEmpty, "the first ask only starts the read")
        assertFalse(WindowImages.holder(file).isEmpty, "the inline read should have finished")
    }

    private fun drawQuad(
        graphics: java.awt.Graphics2D,
        rect: Rect,
        uv: Rect,
        holder: ImageHolder,
        tint: Color = Color.WHITE,
    ) {
        context(WindowRenderParam(graphics)) {
            renderer.renderQuad(rect, tint, tint, tint, tint, holder, uv)
        }
    }

    private fun drawProjectedQuad(graphics: java.awt.Graphics2D, rect: Rect, uv: Rect, holder: ImageHolder) {
        context(WindowRenderParam(graphics)) {
            renderer.withTransform(tilt) {
                renderer.renderQuad(rect, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, holder, uv)
            }
        }
    }

    private fun drawTriangle(
        graphics: java.awt.Graphics2D,
        uv: FloatArray,
        holder: ImageHolder,
        tint: Color = Color.WHITE,
        projected: Boolean = false,
    ) {
        val colors = ColorArray(3) { tint }
        val values = floatArrayOf(
            0f, 0f, uv[0], uv[1],
            16f, 0f, uv[2], uv[3],
            0f, 16f, uv[4], uv[5],
        )
        val vertices = MeasureArray(values.size)
        for (index in values.indices) vertices[index] = values[index].px
        context(WindowRenderParam(graphics)) {
            if (projected) {
                renderer.withTransform(tilt) { renderer.renderTriangles(vertices, colors, holder) }
            } else {
                renderer.renderTriangles(vertices, colors, holder)
            }
        }
    }

    private fun assertArgb(expected: Int, actual: Int, tolerance: Int, where: String) {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val want = (expected ushr shift) and 0xFF
            val got = (actual ushr shift) and 0xFF
            assertTrue(
                abs(want - got) <= tolerance,
                "$where: expected 0x${expected.toUInt().toString(16)} but was 0x${actual.toUInt().toString(16)} " +
                    "(channel $shift: $want vs $got)",
            )
        }
    }

    @Test
    fun `a projected quad samples the UV range it was given`() {
        val magentaSurface = surface()
        val magentaGraphics = magentaSurface.createGraphics()
        try {
            drawProjectedQuad(
                magentaGraphics,
                Rect(0.px, 0.px, 16.px, 16.px),
                Rect(0.px, 0.px, 8.px, 8.px),
                holder(malformed),
            )
        } finally {
            magentaGraphics.dispose()
        }

        val blackSurface = surface()
        val blackGraphics = blackSurface.createGraphics()
        try {
            drawProjectedQuad(
                blackGraphics,
                Rect(0.px, 0.px, 16.px, 16.px),
                Rect(8.px, 0.px, 16.px, 8.px),
                holder(malformed),
            )
        } finally {
            blackGraphics.dispose()
        }

        assertEquals(magenta, magentaSurface.getRGB(8, 8), "UV 0..8 lies in the magenta quadrant")
        assertEquals(black, blackSurface.getRGB(8, 8), "UV 8..16 lies in the black quadrant")
        assertNotEquals(
            magentaSurface.getRGB(8, 8),
            blackSurface.getRGB(8, 8),
            "two projected draws that differ only in UV must not produce the same pixels",
        )
    }

    @Test
    fun `a projected textured triangle samples the UV range it was given`() {
        val magentaSurface = surface()
        val magentaGraphics = magentaSurface.createGraphics()
        try {
            drawTriangle(
                magentaGraphics,
                floatArrayOf(0f, 0f, 8f, 0f, 0f, 8f),
                holder(malformed),
                projected = true,
            )
        } finally {
            magentaGraphics.dispose()
        }

        val blackSurface = surface()
        val blackGraphics = blackSurface.createGraphics()
        try {
            drawTriangle(
                blackGraphics,
                floatArrayOf(8f, 0f, 16f, 0f, 8f, 8f),
                holder(malformed),
                projected = true,
            )
        } finally {
            blackGraphics.dispose()
        }

        assertEquals(magenta, magentaSurface.getRGB(4, 4), "the projected magenta triangle keeps its UV")
        assertEquals(black, blackSurface.getRGB(4, 4), "the projected black triangle keeps its UV")
        assertNotEquals(
            magentaSurface.getRGB(4, 4),
            blackSurface.getRGB(4, 4),
            "a projected triangle must not ignore its UV either",
        )
    }

    @Test
    fun `a decoded image smaller than its nominal size fills the same UV range`() {
        val file = imageFile(2, 2, "kotlinmcuiwindow-2x2-nominal16.png")
        load(file)
        val image = surface()
        val graphics = image.createGraphics()
        try {
            // 2x2 pixels declared 16x16: UV 0..16 is the whole image. Treating UV as decoded pixels
            // would tile the two-pixel image eight times and show red in every quadrant.
            drawQuad(
                graphics,
                Rect(0.px, 0.px, 32.px, 32.px),
                Rect(0.px, 0.px, 16.px, 16.px),
                ImageHolder(ImageSource.LocalFile(file), 16.px, 16.px),
            )
        } finally {
            graphics.dispose()
        }

        assertEquals(0xFFFF0000.toInt(), image.getRGB(4, 4), "top-left of the decoded image")
        assertEquals(0xFF00FF00.toInt(), image.getRGB(28, 4), "top-right of the decoded image")
        assertEquals(0xFF0000FF.toInt(), image.getRGB(4, 28), "bottom-left of the decoded image")
        assertEquals(0xFFFFFFFF.toInt(), image.getRGB(28, 28), "bottom-right of the decoded image")
    }

    @Test
    fun `a tiny nominal size with a matching tiny UV still draws the whole image`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val tiny = Float.MIN_VALUE.px
            drawQuad(
                graphics,
                Rect(0.px, 0.px, 16.px, 16.px),
                Rect(0.px, 0.px, tiny, tiny),
                ImageHolder(probe, tiny, tiny),
            )
            // The first call only schedules the read; the second has the pixels.
            drawQuad(
                graphics,
                Rect(0.px, 0.px, 16.px, 16.px),
                Rect(0.px, 0.px, tiny, tiny),
                ImageHolder(probe, tiny, tiny),
            )
        } finally {
            graphics.dispose()
        }

        // 4 / Float.MIN_VALUE must normalize through Double: a Float infinity would collapse the
        // mapping and leave the quad untouched.
        assertEquals(0xFFFF0000.toInt(), image.getRGB(8, 8), "a tiny nominal size still covers the UV range")
    }

    @Test
    fun `a coloured tint applies its opacity once on a missing quad`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val tint = Color(255, 0, 0, 128)
            val quad = Rect(0.px, 0.px, 16.px, 16.px)
            val uv = Rect(0.px, 0.px, 16.px, 16.px)
            drawQuad(graphics, quad, uv, holder(malformed), tint)
            // The red tint multiplies the texture's RGB: magenta loses its blue channel, and the
            // checkerboard's black cell stays black. The tint's alpha scales opacity once, so the
            // result sits at 128 rather than climbing.
            assertArgb(0x80FF0000.toInt(), image.getRGB(2, 2), 4, "tinted magenta cell")
            assertArgb(0x80000000.toInt(), image.getRGB(12, 2), 4, "tinted black cell")
            assertNotEquals(image.getRGB(2, 2), image.getRGB(12, 2), "the texture is still sampled")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `a coloured tint applies its opacity once on a missing triangle`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            drawTriangle(
                graphics,
                floatArrayOf(0f, 0f, 16f, 0f, 0f, 16f),
                holder(malformed),
                tint = Color(255, 0, 0, 128),
            )
            // Both the magenta and the black cell of the checkerboard are opaque; a double-applied
            // tint would leave alpha near 192 here instead of the tint's own 128.
            assertEquals(128, image.getRGB(2, 2) ushr 24, "tinted magenta cell alpha")
            assertEquals(128, image.getRGB(1, 10) ushr 24, "tinted black cell alpha")
            assertNotEquals(image.getRGB(2, 2), image.getRGB(1, 10), "the texture is still sampled")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `transparent texels of a decoded image stay transparent under a coloured tint`() {
        val file = File(System.getProperty("java.io.tmpdir"), "kotlinmcuiwindow-half-transparent.png")
        file.deleteOnExit()
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until 4) {
                for (x in 0 until 4) {
                    // Left half transparent, right half opaque green.
                    setRGB(x, y, if (x < 2) 0x00000000 else 0xFF00FF00.toInt())
                }
            }
        }, "png", file)
        load(file)

        val image = surface()
        val graphics = image.createGraphics()
        try {
            drawQuad(
                graphics,
                Rect(0.px, 0.px, 16.px, 16.px),
                Rect(0.px, 0.px, 4.px, 4.px),
                ImageHolder(ImageSource.LocalFile(file), 4.px, 4.px),
                Color(255, 0, 0, 128),
            )
        } finally {
            graphics.dispose()
        }

        assertEquals(0, image.getRGB(4, 8) ushr 24, "a transparent texel must not be tinted into existence")
        assertEquals(128, image.getRGB(12, 8) ushr 24, "an opaque texel takes the tint's opacity once")
    }
}
