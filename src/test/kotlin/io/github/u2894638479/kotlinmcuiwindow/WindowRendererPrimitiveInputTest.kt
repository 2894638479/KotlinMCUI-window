package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.ColorArray
import io.github.u2894638479.kotlinmcui.math.MeasureArray
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.MutRect
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The typed primitive entry points, through the real Java2D renderer.
 *
 * Structural errors are caller bugs and throw before a resource is touched; numeric errors skip the
 * whole call, including a valid first triangle, so a transparent target proves no partial output.
 * The texture cases use a real decoded file, not a renderer stub, because the stride is a property
 * of the call and must not depend on what the resource cache happens to contain.
 */
class WindowRendererPrimitiveInputTest {
    private val size = 32
    private val renderer = WindowRenderer()
    private val files = mutableListOf<File>()

    @BeforeTest
    fun decodeReadsInline() {
        WindowImages.reset()
        WindowImages.executor = Executor { it.run() }
        WindowImages.publish = { it() }
    }

    @AfterTest
    fun releaseImages() {
        WindowImages.reset()
        files.forEach { it.delete() }
    }

    private fun surface(): Pair<BufferedImage, java.awt.Graphics2D> {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        return image to image.createGraphics()
    }

    private fun transparent(image: BufferedImage) {
        for (y in 0 until size) for (x in 0 until size) {
            assertEquals(0, image.getRGB(x, y) ushr 24, "pixel ($x, $y) should have stayed untouched")
        }
    }

    private fun loadedTexture(): ImageHolder {
        val file = File(System.getProperty("java.io.tmpdir"), "kotlinmcuiwindow-primitive.png")
        files += file
        val pixels = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 4) for (x in 0 until 4) pixels.setRGB(x, y, 0xFFFF0000.toInt())
        ImageIO.write(pixels, "png", file)
        WindowImages.holder(file)
        return WindowImages.holder(file).also { assertFalse(it.isEmpty) }
    }

    private fun vertexArray(vararg values: Float): MeasureArray {
        val vertices = MeasureArray(values.size)
        for (index in values.indices) vertices[index] = values[index].px
        return vertices
    }

    @Test
    fun `untextured triangles reject a vertex count that is not two per colour`() {
        val (image, graphics) = surface()
        val colors = ColorArray(3) { Color.WHITE }
        val vertices = vertexArray(0f, 0f, 10f, 0f, 0f, 10f, 4f, 4f, 8f, 8f)
        try {
            assertFailsWith<IllegalArgumentException> {
                context(WindowRenderParam(graphics)) { renderer.renderTriangles(vertices, colors) }
            }
            transparent(image)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `a colour count that is not a multiple of three is rejected`() {
        val (image, graphics) = surface()
        val colors = ColorArray(4) { Color.WHITE }
        val vertices = vertexArray(0f, 0f, 10f, 0f, 0f, 10f, 4f, 4f)
        try {
            assertFailsWith<IllegalArgumentException> {
                context(WindowRenderParam(graphics)) { renderer.renderTriangles(vertices, colors) }
            }
            transparent(image)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `textured triangles keep the four measure stride even with an empty image`() {
        val (image, graphics) = surface()
        val colors = ColorArray(3) { Color.WHITE }
        val vertices = vertexArray(0f, 0f, 0f, 0f, 10f, 0f, 1f, 0f, 0f, 10f, 0f, 1f)
        try {
            // Six tests the old resource-dependent stride; the valid shape is twelve.
            val malformed = MeasureArray(6)
            for (index in 0 until 6) malformed[index] = vertices[index]
            assertFailsWith<IllegalArgumentException> {
                context(WindowRenderParam(graphics)) {
                    renderer.renderTriangles(malformed, colors, ImageHolder.empty)
                }
            }
            transparent(image)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `an invalid last vertex skips the whole untextured list`() {
        val (image, graphics) = surface()
        val colors = ColorArray(6) { Color(255, 0, 0) }
        val vertices = vertexArray(
            0f, 0f, 20f, 0f, 0f, 20f,
            0f, 0f, 20f, 0f, 0f, Float.NaN,
        )
        try {
            context(WindowRenderParam(graphics)) { renderer.renderTriangles(vertices, colors) }
            transparent(image)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `an invalid last UV skips the whole textured list before the resource is read`() {
        val (image, graphics) = surface()
        val colors = ColorArray(3) { Color(255, 0, 0) }
        val vertices = vertexArray(
            0f, 0f, 0f, 0f,
            20f, 0f, 1f, 0f,
            0f, 20f, 0f, Float.POSITIVE_INFINITY,
        )
        // A non-empty source with valid nominal dimensions: the numeric check must win, and no
        // resource lookup or draw may happen first.
        val imageHolder = ImageHolder(ImageSource.Remote("https://example.invalid/primitive.png"), 4.px, 4.px)
        try {
            context(WindowRenderParam(graphics)) { renderer.renderTriangles(vertices, colors, imageHolder) }
            transparent(image)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `empty typed arrays are a no-op in both triangle paths`() {
        val (image, graphics) = surface()
        val vertices = MeasureArray(0)
        val colors = ColorArray(0)
        try {
            context(WindowRenderParam(graphics)) {
                renderer.renderTriangles(vertices, colors)
                renderer.renderTriangles(vertices, colors, ImageHolder.empty)
            }
            transparent(image)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `an empty source draws nothing through both textured paths`() {
        val (image, graphics) = surface()
        val red = Color(255, 0, 0)
        val triangle = vertexArray(0f, 0f, 0f, 0f, 20f, 0f, 1f, 0f, 0f, 20f, 0f, 1f)
        try {
            context(WindowRenderParam(graphics)) {
                renderer.renderQuad(Rect(0.px, 0.px, 20.px, 20.px), red, red, red, red, ImageHolder.empty, Rect(0.px, 0.px, 4.px, 4.px))
                renderer.renderTriangles(triangle, ColorArray(3) { red }, ImageHolder.empty)
            }
            transparent(image)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `negative and out of range UV is accepted`() {
        val (image, graphics) = surface()
        val holder = loadedTexture()
        val red = Color(255, 0, 0)
        try {
            context(WindowRenderParam(graphics)) {
                renderer.renderQuad(
                    Rect(0.px, 0.px, 20.px, 20.px),
                    red, red, red, red,
                    holder,
                    Rect((-8).px, (-8).px, 24.px, 24.px),
                )
            }
            assertEquals(0xFFFF0000.toInt(), image.getRGB(10, 10), "the clamped texture should cover the quad")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `mutable rect uv and typed arrays cannot change a completed draw`() {
        val (image, graphics) = surface()
        val holder = loadedTexture()
        val rect = MutRect(0.px, 0.px, 10.px, 10.px)
        val uv = MutRect((-4).px, (-4).px, 8.px, 8.px)
        val colors = ColorArray(3) { Color(0, 255, 0) }
        val vertices = vertexArray(15f, 0f, 30f, 0f, 15f, 20f)
        try {
            context(WindowRenderParam(graphics)) {
                renderer.renderQuad(rect, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, holder, uv)
                renderer.renderTriangles(vertices, colors)
            }
            val quad = image.getRGB(5, 5)
            val triangle = image.getRGB(16, 15)
            rect.right = 0.px
            uv.right = 0.px
            vertices[0] = Float.NaN.px
            colors[0] = Color(0, 0, 255)
            assertEquals(quad, image.getRGB(5, 5))
            assertTrue(triangle != 0, "the triangle should have painted before the arrays were mutated")
        } finally {
            graphics.dispose()
        }
    }
}
