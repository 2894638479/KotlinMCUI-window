package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.ColorArray
import io.github.u2894638479.kotlinmcui.math.MeasureArray
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.MutRect
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import io.github.u2894638479.kotlinmcui.math.transform.Transform
import java.awt.image.BufferedImage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The resource branch of the primitive overloads, through the real Java2D renderer and real pixels.
 *
 * A source that is not ready draws nothing; a source confirmed unusable draws the one shared
 * checkerboard through the same geometry, UV and tint. The checkerboard is magenta and black in
 * eight-pixel cells, so two sample pixels inside one quad say whether the texture was sampled at
 * all, and whether the quad's bounds were kept.
 *
 * Reads are made deterministic by running the background executor and its publication inline, the
 * way [WindowImages] already allows a test to replace them; nothing here sleeps. The ready case
 * uses a four-pixel red PNG under `assets/` in the test classpath, which is the same lookup a page's
 * `kotlinmcui:...` reference takes.
 */
class WindowRendererMissingTextureTest {
    private val size = 48
    private val renderer = WindowRenderer()

    private val probe = ImageSource.Resource("kotlinmcuiwindow:probe.png")
    private val absent = ImageSource.Resource("kotlinmcuiwindow:no-such-texture.png")
    private val malformed = ImageSource.Resource("kotlinmcuiwindow:")

    private val magenta = 0xFFFF00FF.toInt()
    private val black = 0xFF000000.toInt()

    @BeforeTest
    fun runReadsInline() {
        WindowImages.reset()
        WindowImages.executor = Executor { it.run() }
        WindowImages.publish = { it() }
        // These sources outlive a case in the object's caches; start each case unresolved again.
        WindowTextures.forget(probe)
        WindowTextures.forget(absent)
        WindowTextures.forget(malformed)
    }

    @AfterTest
    fun releaseSources() {
        WindowTextures.forget(probe)
        WindowTextures.forget(absent)
        WindowTextures.forget(malformed)
        WindowImages.reset()
    }

    private fun surface(): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

    private fun holder(source: ImageSource, width: Int = 16, height: Int = 16) =
        ImageHolder(source, width.px, height.px)

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

    private fun assertTransparent(image: BufferedImage, where: String) {
        for (y in 0 until size) for (x in 0 until size) {
            assertEquals(0, image.getRGB(x, y) ushr 24, "$where: pixel ($x, $y) should have stayed untouched")
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
    fun `a resource draws nothing while it loads, then draws its pixels`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val quad = Rect(0.px, 0.px, 16.px, 16.px)
            val uv = Rect(0.px, 0.px, 4.px, 4.px)
            drawQuad(graphics, quad, uv, holder(probe, 4, 4))
            assertTransparent(image, "the first frame only schedules the read")

            drawQuad(graphics, quad, uv, holder(probe, 4, 4))
            assertEquals(0xFFFF0000.toInt(), image.getRGB(8, 8), "the decoded red texture should be there")
            assertEquals(0, image.getRGB(20, 20) ushr 24, "nothing outside the quad")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `a failed resource draws the missing texture inside the quad`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val quad = Rect(0.px, 0.px, 16.px, 16.px)
            val uv = Rect(0.px, 0.px, 16.px, 16.px)
            drawQuad(graphics, quad, uv, holder(absent))
            assertTransparent(image, "a read that has not finished yet draws nothing")

            drawQuad(graphics, quad, uv, holder(absent))
            assertEquals(magenta, image.getRGB(2, 2), "first checker cell")
            assertEquals(black, image.getRGB(12, 2), "second checker cell")
            assertEquals(0, image.getRGB(20, 20) ushr 24, "the quad's bounds are kept")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `the missing texture follows the holder's nominal size, not the placeholder's`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            // Nominal 8x8 over the same 16-pixel quad: the placeholder is scaled up by two, so its
            // eight-pixel cells still line up with pixels 2 and 12 of the quad.
            drawQuad(graphics, Rect(0.px, 0.px, 16.px, 16.px), Rect(0.px, 0.px, 8.px, 8.px), holder(absent, 8, 8))
            drawQuad(graphics, Rect(0.px, 0.px, 16.px, 16.px), Rect(0.px, 0.px, 8.px, 8.px), holder(absent, 8, 8))
            assertEquals(magenta, image.getRGB(2, 2))
            assertEquals(black, image.getRGB(12, 2))
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `out of range UV tiles the missing texture instead of clamping it`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            // A UV rectangle entirely past the top-left corner. Clamping it would stretch one edge
            // pixel over the whole quad and give both samples the same colour; tiling keeps the
            // checkerboard's two cells apart.
            val quad = Rect(0.px, 0.px, 16.px, 16.px)
            val uv = Rect((-24).px, (-24).px, (-8).px, (-8).px)
            drawQuad(graphics, quad, uv, holder(malformed))
            assertEquals(magenta, image.getRGB(2, 2))
            assertEquals(black, image.getRGB(2, 10))
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `an unsupported remote source draws the missing texture immediately`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val remote = ImageHolder(ImageSource.Remote("https://example.invalid/none.png"), 16.px, 16.px)
            drawQuad(graphics, Rect(0.px, 0.px, 16.px, 16.px), Rect(0.px, 0.px, 16.px, 16.px), remote)
            // There is nothing to wait for: this backend never fetches a url.
            assertEquals(magenta, image.getRGB(2, 2))
            assertEquals(black, image.getRGB(12, 2))
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `a malformed identifier is a confirmed failure and starts no read`() {
        val reads = AtomicInteger()
        WindowImages.executor = Executor { reads.incrementAndGet(); it.run() }
        val image = surface()
        val graphics = image.createGraphics()
        try {
            drawQuad(graphics, Rect(0.px, 0.px, 16.px, 16.px), Rect(0.px, 0.px, 16.px, 16.px), holder(malformed))
            assertEquals(0, reads.get(), "a malformed id is answered locally, not with io")
            assertEquals(magenta, image.getRGB(2, 2), "it is still a missing texture, not a throw")
            assertEquals(black, image.getRGB(12, 2))
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `a failed texture keeps its geometry and vertex tint`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val quad = Rect(16.px, 0.px, 32.px, 16.px)
            val uv = Rect(0.px, 0.px, 16.px, 16.px)
            val tint = Color(255, 0, 0, 128)
            drawQuad(graphics, quad, uv, holder(absent), tint)
            drawQuad(graphics, quad, uv, holder(absent), tint)
            // The red tint multiplies the checkerboard's RGB, so magenta becomes red and black stays
            // black. The tint's alpha is applied once, so the result stays at 128 rather than
            // climbing towards 192; the two cells still differ, which is only possible if the
            // texture was sampled, not filled flat.
            assertArgb(0x80FF0000.toInt(), image.getRGB(18, 2), 4, "tinted magenta cell")
            assertArgb(0x80000000.toInt(), image.getRGB(28, 2), 4, "tinted black cell")
            assertEquals(0, image.getRGB(2, 2) ushr 24, "the draw starts at its own left edge")
            assertEquals(0, image.getRGB(40, 20) ushr 24, "nothing past the quad")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `a fully transparent tint leaves a failed texture invisible`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val quad = Rect(0.px, 0.px, 16.px, 16.px)
            val uv = Rect(0.px, 0.px, 16.px, 16.px)
            drawQuad(graphics, quad, uv, holder(absent), Color(255, 0, 0, 0))
            drawQuad(graphics, quad, uv, holder(absent), Color(255, 0, 0, 0))
            assertTransparent(image, "a zero-alpha tint draws nothing, missing texture or not")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `a failed texture under a perspective is sampled, not projected flat`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            // A shallow projective row: the projected quad is still close to its affine shape, so the
            // same two checker cells say whether the fill used the texture or one averaged colour.
            val tilt = Transform(1f, 0f, 0f, 0f, 1f, 0f, 0.0001f, 0f, 1f)
            context(WindowRenderParam(graphics)) {
                renderer.withTransform(tilt) {
                    val quad = Rect(0.px, 0.px, 16.px, 16.px)
                    val uv = Rect(0.px, 0.px, 16.px, 16.px)
                    renderer.renderQuad(quad, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, holder(absent), uv)
                    renderer.renderQuad(quad, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, holder(absent), uv)
                }
            }
            assertEquals(magenta, image.getRGB(2, 2))
            assertEquals(black, image.getRGB(12, 2))
            assertEquals(0, image.getRGB(20, 20) ushr 24)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `a textured triangle list keeps its four-measure stride from pending to pixels`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val colors = ColorArray(3) { Color.WHITE }
            val vertices = MeasureArray(12)
            val values = floatArrayOf(0f, 0f, 0f, 0f, 16f, 0f, 4f, 0f, 0f, 16f, 0f, 4f)
            for (index in values.indices) vertices[index] = values[index].px
            context(WindowRenderParam(graphics)) { renderer.renderTriangles(vertices, colors, holder(probe, 4, 4)) }
            assertTransparent(image, "the pending frame reads the same four measures and draws nothing")

            context(WindowRenderParam(graphics)) { renderer.renderTriangles(vertices, colors, holder(probe, 4, 4)) }
            assertEquals(0xFFFF0000.toInt(), image.getRGB(2, 2), "the ready frame draws with the same stride")
            assertEquals(0, image.getRGB(14, 14) ushr 24, "geometry did not jump when the pixels arrived")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `textured triangles draw the missing texture on their own geometry and UV`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val colors = ColorArray(3) { Color.WHITE }
            val vertices = MeasureArray(12)
            val values = floatArrayOf(0f, 0f, 0f, 0f, 16f, 0f, 16f, 0f, 0f, 16f, 0f, 16f)
            for (index in values.indices) vertices[index] = values[index].px
            context(WindowRenderParam(graphics)) { renderer.renderTriangles(vertices, colors, holder(malformed)) }
            assertEquals(magenta, image.getRGB(2, 2), "first checker cell inside the triangle")
            assertEquals(black, image.getRGB(1, 10), "second checker cell inside the triangle")
            assertEquals(0, image.getRGB(14, 14) ushr 24, "outside the triangle stays untouched")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `an invalid last UV draws no part of a missing texture`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val colors = ColorArray(6) { Color.WHITE }
            val vertices = MeasureArray(24)
            val values = floatArrayOf(
                0f, 0f, 0f, 0f, 16f, 0f, 16f, 0f, 0f, 16f, 0f, 16f,
                20f, 0f, 0f, 0f, 36f, 0f, 16f, 0f, 20f, 16f, 0f, Float.NaN,
            )
            for (index in values.indices) vertices[index] = values[index].px
            // A malformed id would be drawable at once; the validation must still skip the call.
            context(WindowRenderParam(graphics)) { renderer.renderTriangles(vertices, colors, holder(malformed)) }
            assertTransparent(image, "a bad last vertex skips the whole list")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `mutating borrowed inputs cannot change a drawn missing texture`() {
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val rect = MutRect(0.px, 0.px, 16.px, 16.px)
            val uv = MutRect(0.px, 0.px, 16.px, 16.px)
            drawQuad(graphics, rect, uv, holder(malformed))
            val quadCells = image.getRGB(2, 2) to image.getRGB(12, 2)

            // A second, textured triangle: its vertices and colours are borrowed the same way.
            val colors = ColorArray(3) { Color.WHITE }
            val vertices = MeasureArray(12)
            val values = floatArrayOf(24f, 0f, 0f, 0f, 40f, 0f, 16f, 0f, 24f, 16f, 0f, 16f)
            for (index in values.indices) vertices[index] = values[index].px
            context(WindowRenderParam(graphics)) { renderer.renderTriangles(vertices, colors, holder(malformed)) }
            val triangleCells = image.getRGB(26, 2) to image.getRGB(36, 2)

            rect.right = 0.px
            uv.right = 0.px
            vertices[0] = Float.NaN.px
            colors[0] = Color(0, 0, 255)
            assertEquals(quadCells.first, image.getRGB(2, 2), "a reused MutRect cannot repaint the quad")
            assertEquals(quadCells.second, image.getRGB(12, 2))
            assertEquals(triangleCells.first, image.getRGB(26, 2), "a reused array cannot repaint the triangle")
            assertEquals(triangleCells.second, image.getRGB(36, 2))
            assertEquals(0, image.getRGB(20, 20) ushr 24, "the gap between the two stays untouched")
        } finally {
            graphics.dispose()
        }
    }
}
