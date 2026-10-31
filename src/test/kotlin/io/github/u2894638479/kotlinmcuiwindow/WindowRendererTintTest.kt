package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.ColorArray
import io.github.u2894638479.kotlinmcui.math.MeasureArray
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tint on a texture is a per-texel RGB multiply plus one alpha scale, drawn over the UI once.
 *
 * The regression that motivated these cases: a tint applied with `SRC_ATOP` after a `TexturePaint`
 * also painted the tint's colour where the texture was transparent, recolouring the background that
 * was already there, and mixed the tint into the destination for half-alpha texels.
 */
class WindowRendererTintTest {
    private val renderer = WindowRenderer()

    @BeforeTest
    fun runReadsInline() {
        WindowImages.reset()
        WindowImages.executor = Executor { it.run() }
        WindowImages.publish = { it() }
    }

    @AfterTest
    fun releaseSources() {
        WindowImages.reset()
    }

    private fun surface(): BufferedImage = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)

    /** Write a real PNG, let the inline reader decode it, and answer the ready holder for it. */
    private fun texture(name: String, width: Int, height: Int, pixel: (Int, Int) -> Int): ImageHolder {
        val file = File(System.getProperty("java.io.tmpdir"), "kotlinmcuiwindow-tint-$name.png")
        file.deleteOnExit()
        file.delete()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) {
                for (x in 0 until width) setRGB(x, y, pixel(x, y))
            }
        }, "png", file)
        assertTrue(WindowImages.holder(file).isEmpty, "the first ask only starts the read")
        val holder = WindowImages.holder(file)
        assertTrue(!holder.isEmpty, "the inline read should have finished")
        return holder
    }

    private fun drawQuad(
        graphics: Graphics2D,
        rect: Rect,
        uv: Rect,
        holder: ImageHolder,
        tint: Color,
    ) {
        context(WindowRenderParam(graphics)) {
            renderer.renderQuad(rect, tint, tint, tint, tint, holder, uv)
        }
    }

    private fun drawTriangle(graphics: Graphics2D, uv: FloatArray, holder: ImageHolder, tint: Color) {
        val colors = ColorArray(3) { tint }
        val values = floatArrayOf(
            0f, 0f, uv[0], uv[1],
            16f, 0f, uv[2], uv[3],
            0f, 16f, uv[4], uv[5],
        )
        val vertices = MeasureArray(values.size)
        for (index in values.indices) vertices[index] = values[index].px
        context(WindowRenderParam(graphics)) {
            renderer.renderTriangles(vertices, colors, holder)
        }
    }

    private fun paintBlueBackground(graphics: Graphics2D) {
        graphics.color = java.awt.Color(0, 0, 255)
        graphics.fillRect(0, 0, 32, 32)
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
    fun `a coloured tint leaves transparent texels of the destination byte-identical`() {
        // Left half transparent red, right half opaque red: the transparent texels must not let the
        // green tint touch the blue background that was already painted.
        val holder = texture("transparent-red", 4, 4) { x, _ ->
            if (x < 2) 0x00FF0000 else 0xFFFF0000.toInt()
        }
        val image = surface()
        val graphics = image.createGraphics()
        try {
            paintBlueBackground(graphics)
            drawQuad(
                graphics,
                Rect(0.px, 0.px, 16.px, 16.px),
                Rect(0.px, 0.px, 4.px, 4.px),
                holder,
                Color(0, 255, 0, 128),
            )
        } finally {
            graphics.dispose()
        }

        assertEquals(
            0xFF0000FF.toInt(),
            image.getRGB(4, 8),
            "a transparent texel must leave the existing background untouched, not tint it",
        )
        // Opaque red times green is black, at the tint's half opacity, over blue.
        assertArgb(0xFF00007F.toInt(), image.getRGB(12, 8), 1, "opaque texel multiplied by the tint")
    }

    @Test
    fun `half alpha source and half alpha tint apply each alpha once`() {
        val holder = texture("half-red", 4, 4) { _, _ -> 0x80FF0000.toInt() }
        val image = surface()
        val graphics = image.createGraphics()
        try {
            paintBlueBackground(graphics)
            val beforePaint = graphics.paint
            val beforeComposite = graphics.composite
            val beforeTransform = graphics.transform
            drawQuad(
                graphics,
                Rect(0.px, 0.px, 16.px, 16.px),
                Rect(0.px, 0.px, 4.px, 4.px),
                holder,
                Color(255, 255, 255, 128),
            )
            val actual = image.getRGB(8, 8)
            assertArgb(0xFF4000BF.toInt(), actual, 1, "texAlpha * tintAlpha over opaque blue, once")
            assertEquals(255, actual ushr 24, "the blue destination is opaque")
            assertSame(beforePaint, graphics.paint, "the paint must be restored")
            assertSame(beforeComposite, graphics.composite, "the composite must be restored")
            assertEquals(beforeTransform, graphics.transform, "the transform must be restored")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `half alpha source with a nonwhite half alpha tint multiplies RGB and applies alpha once`() {
        val holder = texture("half-colour", 4, 4) { _, _ -> 0x80FF8000.toInt() }
        val image = surface()
        val graphics = image.createGraphics()
        try {
            paintBlueBackground(graphics)
            drawQuad(
                graphics,
                Rect(0.px, 0.px, 16.px, 16.px),
                Rect(0.px, 0.px, 4.px, 4.px),
                holder,
                // (255, 128, 0) * (128, 255, 128) = (128, 128, 0), alpha 128 * 128.
                Color(128, 255, 128, 128),
            )
        } finally {
            graphics.dispose()
        }

        assertArgb(0xFF2020BF.toInt(), image.getRGB(8, 8), 2, "multiplied source RGB over blue")
    }

    @Test
    fun `changing the tint invalidates the single reusable tinted copy`() {
        val holder = texture("two-tints", 4, 4) { _, _ -> 0xFFFFFFFF.toInt() }
        val image = surface()
        val graphics = image.createGraphics()
        try {
            drawQuad(
                graphics,
                Rect(0.px, 0.px, 16.px, 16.px),
                Rect(0.px, 0.px, 4.px, 4.px),
                holder,
                Color(0, 255, 0, 255),
            )
            drawQuad(
                graphics,
                Rect(16.px, 0.px, 32.px, 16.px),
                Rect(0.px, 0.px, 4.px, 4.px),
                holder,
                Color(0, 0, 255, 255),
            )
        } finally {
            graphics.dispose()
        }

        assertEquals(0xFF00FF00.toInt(), image.getRGB(4, 8), "white times green")
        assertEquals(0xFF0000FF.toInt(), image.getRGB(20, 8), "white times blue")
        assertNotEquals(
            image.getRGB(4, 8),
            image.getRGB(20, 8),
            "the second tint must not reuse the first tinted copy",
        )
    }

    @Test
    fun `a degenerate UV sample multiplies the tint and floors a negative coordinate`() {
        // The last column is green, the first is red. UV -0.25 must floor to -1 and wrap to the last
        // column; truncation towards zero would sample the first column instead.
        val holder = texture("degenerate", 4, 4) { x, _ ->
            when (x) {
                0 -> 0xFFFF0000.toInt()
                3 -> 0xFF00FF00.toInt()
                else -> 0xFF0000FF.toInt()
            }
        }
        val image = surface()
        val graphics = image.createGraphics()
        try {
            drawTriangle(
                graphics,
                floatArrayOf(-0.25f, 0f, -0.25f, 0f, -0.25f, 0f),
                holder,
                Color(128, 128, 128, 128),
            )
        } finally {
            graphics.dispose()
        }

        // Green multiplied by a grey tint is 128, and the alpha is 255 * 128 / 255.
        assertArgb(0x80008000.toInt(), image.getRGB(2, 2), 1, "wrapped, tinted degenerate sample")
    }
}
