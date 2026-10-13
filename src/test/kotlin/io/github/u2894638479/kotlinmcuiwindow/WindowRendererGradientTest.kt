package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Java2D has no four-corner primitive and no per-vertex interpolation, which is exactly where this
 * backend can quietly draw something that looks plausible and is wrong. These cases read the pixels
 * a draw call actually produced.
 */
class WindowRendererGradientTest {

    private val size = 64

    private fun surface(): Pair<BufferedImage, Graphics2D> {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        return image to image.createGraphics()
    }

    private fun pixel(image: BufferedImage, x: Int, y: Int): IntArray {
        val rgb = image.getRGB(x, y)
        return intArrayOf((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }

    private fun distance(a: IntArray, b: IntArray) =
        kotlin.math.abs(a[0] - b[0]) + kotlin.math.abs(a[1] - b[1]) + kotlin.math.abs(a[2] - b[2])

    @Test
    fun `a quad puts each corner colour where that corner is`() {
        val (image, graphics) = surface()
        val white = Color(255,255,255)
        val hue = Color(255,122,0)
        val black = Color(0,0,0)
        context(WindowRenderParam(graphics)) {
            WindowRenderer.renderQuad(Rect(0.px,0.px,size.px,size.px),white,hue,black,black,null,null)
        }
        graphics.dispose()

        val topLeft = pixel(image,3,3)
        val topRight = pixel(image,size - 4,3)
        val bottomLeft = pixel(image,3,size - 4)
        val bottomRight = pixel(image,size - 4,size - 4)

        // The two right-hand corners carry the hue: a diagonal ramp between white and black loses it.
        assertTrue(distance(topRight,intArrayOf(255,122,0)) < 120, "top right should be the hue, was ${topRight.toList()}")
        assertTrue(distance(topRight,intArrayOf(255,122,0)) < distance(topRight,intArrayOf(150,120,100)), "the hue must survive at the top right")
        assertTrue(distance(topLeft,intArrayOf(255,255,255)) < 120, "top left should be white, was ${topLeft.toList()}")
        assertTrue(distance(bottomLeft,intArrayOf(0,0,0)) < 120, "bottom left should be black, was ${bottomLeft.toList()}")
        assertTrue(distance(bottomRight,intArrayOf(0,0,0)) < 120, "bottom right should be black, was ${bottomRight.toList()}")
    }

    @Test
    fun `a triangle interpolates between its vertex colours`() {
        val (image, graphics) = surface()
        // Apex red, bottom left green, bottom right blue.
        val vertices = floatArrayOf(
            (size / 2).toFloat(), 0f,
            0f, size.toFloat(),
            size.toFloat(), size.toFloat(),
        )
        val colors = intArrayOf(
            Color(255,0,0).rgbaInt,
            Color(0,255,0).rgbaInt,
            Color(0,0,255).rgbaInt,
        )
        context(WindowRenderParam(graphics)) {
            WindowRenderer.renderTriangles(vertices,colors,null)
        }
        graphics.dispose()

        val nearApex = pixel(image,size / 2,4)
        val nearRight = pixel(image,size - 4,size - 4)

        assertTrue(nearApex[0] > 200 && nearApex[1] < 60 && nearApex[2] < 60, "the apex should be red, was ${nearApex.toList()}")
        assertTrue(nearRight[2] > 200 && nearRight[0] < 60, "the right corner should be blue, was ${nearRight.toList()}")
    }
}
