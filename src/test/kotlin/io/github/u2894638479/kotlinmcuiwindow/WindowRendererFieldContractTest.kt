package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.ColorArray
import io.github.u2894638479.kotlinmcui.math.MeasureArray
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import io.github.u2894638479.kotlinmcui.math.transform.Transform
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The field contract, drawn by the real [WindowRenderer] and read back from a real image.
 *
 * The reference below is deliberately independent of the renderer: it premultiplies the corner
 * colours in double, blends them with the bilinear or barycentric weights, multiplies a sampled
 * texel in, and composites over a known backdrop. Comparisons are made in premultiplied space (or on
 * an opaque composite) so a tiny destination alpha cannot make the comparison unstable.
 */
class WindowRendererFieldContractTest {

    private val size = 64
    private val renderer = WindowRenderer()
    private val left = 8.0
    private val top = 8.0
    private val extent = 48.0

    private val quad = Rect(8.px, 8.px, 56.px, 56.px)
    private val white = Color(255, 255, 255)
    private val red = Color(255, 0, 0)
    private val green = Color(0, 255, 0)
    private val blue = Color(0, 0, 255)
    private val black = Color(0, 0, 0)

    private val files = mutableListOf<File>()

    @Test
    fun `an unprojectable last triangle does not leave a visible prefix`() {
        val image = BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            val values = doubleArrayOf(8.0,8.0,48.0,8.0,8.0,48.0,
                200.0,8.0,240.0,8.0,200.0,48.0)
            context(WindowRenderParam(graphics,size,size)) {
                renderer.withTransform(Transform(1f,0f,0f,0f,1f,0f,-0.01f,0f,1f)) {
                    renderer.renderTriangles(MeasureArray(values.size){values[it].px},ColorArray(6){red})
                }
            }
            assertEquals(0,image.getRGB(20,20))
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `a large triangle is clipped to the surface before raster bounds are limited`() {
        val image = BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color.BLUE
            graphics.fillRect(0,0,size,size)
            val values = doubleArrayOf(-1.0e6,-1.0e6,2.0e6,-1.0e6,-1.0e6,2.0e6)
            context(WindowRenderParam(graphics,size,size)) {
                renderer.renderTriangles(MeasureArray(values.size){values[it].px},
                    ColorArray(3){Color(255,255,255,128)})
            }
            val pixel = java.awt.Color(image.getRGB(20,20),true)
            assertEquals(128,pixel.red)
            assertEquals(128,pixel.green)
            assertEquals(255,pixel.blue)
            assertEquals(255,pixel.alpha)
        } finally {
            graphics.dispose()
        }
    }

    @BeforeTest
    fun decodeReadsInline() {
        WindowImages.reset()
        WindowImages.executor = Executor { it.run() }
        WindowImages.publish = { it() }
    }

    @AfterTest
    fun release() {
        files.forEach { it.delete() }
        WindowImages.reset()
    }

    // ---------------------------------------------------------------- independent reference

    private fun Color.premultiplied(): DoubleArray {
        val alpha = aInt / 255.0
        return doubleArrayOf(rInt / 255.0 * alpha, gInt / 255.0 * alpha, bInt / 255.0 * alpha, alpha)
    }

    private fun mix(a: DoubleArray, b: DoubleArray, weight: Double): DoubleArray = doubleArrayOf(
        a[0] + (b[0] - a[0]) * weight,
        a[1] + (b[1] - a[1]) * weight,
        a[2] + (b[2] - a[2]) * weight,
        a[3] + (b[3] - a[3]) * weight,
    )

    /** The bilinear field through four *straight* corner colours at local (u, v). */
    private fun bilinear(lt: Color, rt: Color, lb: Color, rb: Color, u: Double, v: Double): DoubleArray {
        val upper = mix(lt.premultiplied(), rt.premultiplied(), u)
        val lower = mix(lb.premultiplied(), rb.premultiplied(), u)
        return mix(upper, lower, v)
    }

    /** The barycentric field through three *straight* vertex colours. */
    private fun barycentric(c0: Color, c1: Color, c2: Color, weights: DoubleArray): DoubleArray {
        val a = c0.premultiplied()
        val b = c1.premultiplied()
        val c = c2.premultiplied()
        return DoubleArray(4) { a[it] * weights[0] + b[it] * weights[1] + c[it] * weights[2] }
    }

    /** One premultiplied source over one premultiplied destination. */
    private fun over(source: DoubleArray, destination: DoubleArray): DoubleArray {
        val keep = 1.0 - source[3]
        return doubleArrayOf(
            source[0] + destination[0] * keep,
            source[1] + destination[1] * keep,
            source[2] + destination[2] * keep,
            source[3] + destination[3] * keep,
        )
    }

    /** A straight [Color] as a premultiplied destination colour. */
    private fun backdrop(color: java.awt.Color): DoubleArray {
        val alpha = color.alpha / 255.0
        return doubleArrayOf(color.red / 255.0 * alpha, color.green / 255.0 * alpha, color.blue / 255.0 * alpha, alpha)
    }

    /**
     * Compare a pixel with a premultiplied expectation on a known destination.
     *
     * The rendered value is read as straight ARGB and premultiplied again, so no assertion ever
     * divides by a small alpha. A per-channel tolerance of 2 covers the 8-bit raster Java2D asks the
     * paint for; transformed and anti-aliased cases use a little more.
     */
    private fun assertPremul(expected: DoubleArray, argb: Int, tolerance: Int, where: String) {
        val alpha = argb ushr 24 and 0xFF
        val wantAlpha = (expected[3] * 255.0).roundToInt()
        assertTrue(
            abs(alpha - wantAlpha) <= tolerance,
            "$where: alpha expected $wantAlpha but was $alpha (expected 0x${expected.toList()}, got 0x${argb.toUInt().toString(16)})",
        )
        if (wantAlpha == 0 && alpha == 0) return
        val shifts = intArrayOf(16, 8, 0)
        for (channel in 0..2) {
            val got = (argb shr shifts[channel] and 0xFF) * alpha / 255.0
            val want = expected[channel] * 255.0
            assertTrue(
                abs(got - want) <= tolerance + 1,
                "$where: channel $channel expected ${want.roundToInt()} but was ${got.roundToInt()} " +
                    "(expected 0x${expected.toList()}, got 0x${argb.toUInt().toString(16)})",
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun surface(fill: java.awt.Color? = null): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        if (fill != null) {
            val graphics = image.createGraphics()
            graphics.color = fill
            graphics.fillRect(0, 0, size, size)
            graphics.dispose()
        }
        return image
    }

    private fun draw(
        graphics: java.awt.Graphics2D,
        lt: Color = white,
        rt: Color = white,
        lb: Color = white,
        rb: Color = white,
        rect: Rect = quad,
        texture: ImageHolder? = null,
        uv: Rect? = null,
        transform: Transform? = null,
    ) {
        context(WindowRenderParam(graphics)) {
            if (transform == null) {
                if (texture == null) renderer.renderQuad(rect, lt, rt, lb, rb)
                else renderer.renderQuad(rect, lt, rt, lb, rb, texture, uv!!)
            } else {
                renderer.withTransform(transform) {
                    if (texture == null) renderer.renderQuad(rect, lt, rt, lb, rb)
                    else renderer.renderQuad(rect, lt, rt, lb, rb, texture, uv!!)
                }
            }
        }
    }

    private fun u(pixelX: Int): Double = (pixelX + 0.5 - left) / extent
    private fun v(pixelY: Int): Double = (pixelY + 0.5 - top) / extent

    private fun writeTexture(name: String, width: Int, height: Int, pixel: (Int, Int) -> Int): File {
        val file = File(System.getProperty("java.io.tmpdir"), "kotlinmcuiwindow-contract-$name.png")
        file.deleteOnExit()
        file.delete()
        files += file
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, pixel(x, y))
        }, "png", file)
        WindowImages.holder(file)
        val holder = WindowImages.holder(file)
        assertFalse(holder.isEmpty, "the inline read should have finished for $name")
        return file
    }

    private fun holder(file: File, nominal: Int): ImageHolder =
        ImageHolder(ImageSource.LocalFile(file), nominal.px, nominal.px)

    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

    // ---------------------------------------------------------------- quad field

    @Test
    fun `a quad evaluates the bilinear field at its centre, corners and edges`() {
        val image = surface()
        val graphics = image.createGraphics()
        draw(graphics, lt = white, rt = red, lb = black, rb = black)
        graphics.dispose()

        // The centre pixel is not exactly u = v = 0.5 for an even-width rectangle, so the field is
        // checked at the sample the renderer actually takes; the geometric midpoint has its own case.
        val centre = image.getRGB(32, 32)
        assertPremul(bilinear(white, red, black, black, u(32), v(32)), centre, 2, "centre")
        val centreRed = centre shr 16 and 0xFF
        val centreGreen = centre shr 8 and 0xFF
        assertTrue(!(centreRed == 128 && centreGreen == 0), "the centre is not a one-dimensional ramp")

        for (x in intArrayOf(11, 20, 32, 44, 52)) {
            for (y in intArrayOf(11, 20, 32, 44, 52)) {
                val expected = bilinear(white, red, black, black, u(x), v(y))
                assertPremul(expected, image.getRGB(x, y), 2, "sample ($x, $y)")
            }
        }
    }

    @Test
    fun `the geometric midpoint of a quad is the bilinear midpoint of its corners`() {
        // 49 pixels wide, so the pixel centre at (32.5, 32.5) is exactly u = v = 0.5.
        val image = surface()
        val graphics = image.createGraphics()
        draw(graphics, lt = white, rt = red, lb = black, rb = black, rect = Rect(8.px, 8.px, 57.px, 57.px))
        graphics.dispose()

        val centre = image.getRGB(32, 32)
        assertPremul(bilinear(white, red, black, black, 0.5, 0.5), centre, 2, "geometric midpoint")
        assertTrue((centre shr 16 and 0xFF) in 126..130, "midpoint red: ${centre shr 16 and 0xFF}")
        assertTrue((centre shr 8 and 0xFF) in 62..66, "midpoint green: ${centre shr 8 and 0xFF}")
        assertTrue((centre and 0xFF) in 62..66, "midpoint blue: ${centre and 0xFF}")
        val midpointRed = centre shr 16 and 0xFF
        val midpointGreen = centre shr 8 and 0xFF
        assertTrue(!(midpointRed == 128 && midpointGreen == 0), "not the old two-triangle answer")
    }

    @Test
    fun `flipping and transposing a quad mirrors the same field`() {
        val direct = surface()
        val directGraphics = direct.createGraphics()
        draw(directGraphics, lt = white, rt = red, lb = black, rb = black)
        directGraphics.dispose()

        val flipped = surface()
        val flippedGraphics = flipped.createGraphics()
        // Mirror horizontally: left and right corner colours swap.
        draw(flippedGraphics, lt = red, rt = white, lb = black, rb = black)
        flippedGraphics.dispose()

        val transposed = surface()
        val transposedGraphics = transposed.createGraphics()
        // Mirror across the diagonal: top and bottom corner colours swap.
        draw(transposedGraphics, lt = white, rt = black, lb = red, rb = black)
        transposedGraphics.dispose()

        for (x in 12..51 step 3) {
            for (y in 12..51 step 5) {
                val mirrorX = 64 - 1 - x
                val a = direct.getRGB(x, y)
                val b = flipped.getRGB(mirrorX, y)
                assertPremul(bilinear(white, red, black, black, u(x), v(y)), a, 2, "direct ($x, $y)")
                assertPremul(bilinear(red, white, black, black, u(mirrorX), v(y)), b, 2, "flipped ($mirrorX, $y)")
                val ac = direct.getRGB(x, y)
                val at = transposed.getRGB(y, x)
                assertPremul(bilinear(white, black, red, black, u(y), v(x)), at, 2, "transposed ($y, $x)")
                for (shift in intArrayOf(16, 8, 0)) {
                    assertTrue(
                        abs((ac shr shift and 0xFF) - (b shr shift and 0xFF)) <= 2,
                        "a horizontal flip must mirror the field at ($x, $y)",
                    )
                    assertTrue(
                        abs((ac shr shift and 0xFF) - (at shr shift and 0xFF)) <= 2,
                        "a diagonal flip must transpose the field at ($x, $y)",
                    )
                }
            }
        }
    }

    @Test
    fun `opaque red fading to transparent blue stays red at half alpha`() {
        // Left edge opaque red, right edge transparent blue. Straight RGBA interpolation would give
        // purple at the centre; premultiplied interpolation keeps the colour red and only fades out.
        // 49 pixels wide so the pixel at (32, 32) is exactly u = 0.5.
        val half = Rect(8.px, 8.px, 57.px, 57.px)
        val colors = { graphics: java.awt.Graphics2D ->
            draw(graphics, lt = red, rt = Color(0, 0, 255, 0), lb = red, rb = Color(0, 0, 255, 0), rect = half)
        }

        val transparent = surface()
        val transparentGraphics = transparent.createGraphics()
        colors(transparentGraphics)
        transparentGraphics.dispose()
        val centre = transparent.getRGB(32, 32)
        assertPremul(over(doubleArrayOf(0.5, 0.0, 0.0, 0.5), doubleArrayOf(0.0, 0.0, 0.0, 0.0)), centre, 2, "red at half alpha")
        assertTrue((centre ushr 24) in 126..130, "half alpha: ${centre ushr 24}")
        assertTrue((centre shr 16 and 0xFF) >= 250, "the colour is still red: ${centre shr 16 and 0xFF}")
        assertTrue((centre and 0xFF) <= 3, "no blue is mixed in: ${centre and 0xFF}")

        val overBlack = surface(java.awt.Color.BLACK)
        val overBlackGraphics = overBlack.createGraphics()
        colors(overBlackGraphics)
        overBlackGraphics.dispose()
        assertPremul(over(doubleArrayOf(0.5, 0.0, 0.0, 0.5), backdrop(java.awt.Color.BLACK)), overBlack.getRGB(32, 32), 2, "over black")
        assertTrue((overBlack.getRGB(32, 32) shr 16 and 0xFF) in 126..130, "half of opaque red over black")
        assertTrue((overBlack.getRGB(32, 32) and 0xFF) <= 3, "blue must not appear over black")
    }

    @Test
    fun `unequal alpha corners interpolate premultiplied colours`() {
        val lt = Color(255, 255, 255, 255)
        val rt = Color(255, 0, 0, 128)
        val lb = Color(0, 255, 0, 64)
        val rb = Color(0, 0, 255, 0)

        val overBlack = surface(java.awt.Color.BLACK)
        val blackGraphics = overBlack.createGraphics()
        draw(blackGraphics, lt, rt, lb, rb)
        blackGraphics.dispose()

        val overWhite = surface(java.awt.Color.WHITE)
        val whiteGraphics = overWhite.createGraphics()
        draw(whiteGraphics, lt, rt, lb, rb)
        whiteGraphics.dispose()

        for (x in intArrayOf(14, 24, 32, 42, 50)) {
            for (y in intArrayOf(14, 24, 32, 42, 50)) {
                val source = bilinear(lt, rt, lb, rb, u(x), v(y))
                assertPremul(
                    over(source, backdrop(java.awt.Color.BLACK)),
                    overBlack.getRGB(x, y), 2, "over black ($x, $y)",
                )
                assertPremul(
                    over(source, backdrop(java.awt.Color.WHITE)),
                    overWhite.getRGB(x, y), 2, "over white ($x, $y)",
                )
            }
        }
    }

    @Test
    fun `a quad covers both diagonals once over an existing backdrop`() {
        // The old two-triangle fill left a one-pixel line along the shared diagonal where the two
        // anti-aliased fills each covered half the sample and the backdrop showed through. Every
        // interior sample must be the field, and none may remain the backdrop.
        val image = surface(java.awt.Color.MAGENTA)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        draw(graphics, lt = white, rt = red, lb = black, rb = blue)
        graphics.dispose()

        for (step in 10..54 step 2) {
            for (point in intArrayOf(step, 64 - step)) {
                val expected = bilinear(white, red, black, blue, u(point), v(step))
                assertPremul(expected, image.getRGB(point, step), 3, "diagonal ($point, $step)")
            }
            val expected = bilinear(white, red, black, blue, u(step), v(64 - step))
            assertPremul(expected, image.getRGB(step, 64 - step), 3, "anti-diagonal ($step, ${64 - step})")
        }
        val everyDiagonal = bilinear(white, red, black, blue, 0.5, 0.5)
        assertPremul(everyDiagonal, image.getRGB(32, 32), 2, "the diagonal midpoint is the field, not the backdrop")
    }

    @Test
    fun `transparent coloured corners leave an existing backdrop untouched`() {
        val existing = java.awt.Color(48, 96, 160)
        val image = surface(existing)
        val graphics = image.createGraphics()
        // Fully transparent, strongly coloured corners: neither their RGB nor any tint may reach the
        // destination. This is the old SRC_ATOP failure the backend used to have.
        draw(
            graphics,
            lt = Color(0, 255, 0, 0),
            rt = Color(255, 0, 255, 0),
            lb = Color(255, 255, 0, 0),
            rb = Color(0, 255, 255, 0),
        )
        graphics.dispose()

        val untouched = argb(existing.red, existing.green, existing.blue)
        for (y in 8 until 56) for (x in 8 until 56) {
            assertEquals(untouched, image.getRGB(x, y), "transparent corners must not tint the backdrop at ($x, $y)")
        }

        val mixed = surface(existing)
        val mixedGraphics = mixed.createGraphics()
        draw(
            mixedGraphics,
            lt = Color(0, 255, 0, 0),
            rt = Color(255, 0, 0, 255),
            lb = Color(0, 255, 0, 0),
            rb = Color(255, 0, 0, 255),
        )
        mixedGraphics.dispose()
        // Alpha rises from 0 at the left edge to 1 at the right; the composite follows the field.
        for (x in intArrayOf(10, 24, 40, 54)) {
            assertPremul(
                over(
                    bilinear(Color(0, 255, 0, 0), red, Color(0, 255, 0, 0), red, u(x), v(32)),
                    backdrop(existing),
                ),
                mixed.getRGB(x, 32), 2, "mixed-alpha quad at $x",
            )
        }
    }

    // ---------------------------------------------------------------- texture

    @Test
    fun `half alpha texture times half alpha tint composites once over opaque blue`() {
        val file = writeTexture("half-red", 4, 4) { _, _ -> 0x80FF0000.toInt() }
        val image = surface(java.awt.Color.BLUE)
        val graphics = image.createGraphics()
        draw(
            graphics,
            lt = Color(255, 255, 255, 128),
            rt = Color(255, 255, 255, 128),
            lb = Color(255, 255, 255, 128),
            rb = Color(255, 255, 255, 128),
            texture = holder(file, 4),
            uv = Rect(0.px, 0.px, 4.px, 4.px),
        )
        graphics.dispose()

        // tex alpha 128/255 * tint alpha 128/255 = ~0.252; red 1 * 1 * texAlpha over blue.
        val alpha = 128.0 / 255.0 * 128.0 / 255.0
        val expected = over(doubleArrayOf(alpha, 0.0, 0.0, alpha), backdrop(java.awt.Color.BLUE))
        assertPremul(expected, image.getRGB(32, 32), 2, "half tex alpha times half tint alpha")
        val actual = image.getRGB(32, 32)
        assertEquals(255, actual ushr 24, "opaque blue keeps destination alpha at 1")
        assertTrue((actual shr 16 and 0xFF) in 62..66, "red channel is one quarter of 255: ${actual shr 16 and 0xFF}")
        assertTrue((actual and 0xFF) in 189..193, "blue keeps the rest: ${actual and 0xFF}")
    }

    @Test
    fun `half alpha texture times half alpha tint on a transparent target`() {
        val file = writeTexture("half-red-transparent", 4, 4) { _, _ -> 0x80FF0000.toInt() }
        val image = surface()
        val graphics = image.createGraphics()
        draw(
            graphics,
            lt = Color(255, 255, 255, 128),
            rt = Color(255, 255, 255, 128),
            lb = Color(255, 255, 255, 128),
            rb = Color(255, 255, 255, 128),
            texture = holder(file, 4),
            uv = Rect(0.px, 0.px, 4.px, 4.px),
        )
        graphics.dispose()

        val alpha = 128.0 / 255.0 * 128.0 / 255.0
        assertPremul(doubleArrayOf(alpha, 0.0, 0.0, alpha), image.getRGB(32, 32), 2, "same source on a transparent target")
        assertTrue((image.getRGB(32, 32) ushr 24) in 63..65, "effective alpha is about 64: ${image.getRGB(32, 32) ushr 24}")
        assertTrue((image.getRGB(32, 32) shr 16 and 0xFF) >= 250, "the texture is still red")
    }

    @Test
    fun `a nonwhite tint multiplies the texel and transparent texels keep the backdrop`() {
        val file = writeTexture("split", 4, 4) { x, _ ->
            if (x < 2) 0x0000FF00 else 0xFFFF8000.toInt()
        }
        val existing = java.awt.Color(20, 40, 60)
        val image = surface(existing)
        val graphics = image.createGraphics()
        val tint = Color(128, 255, 128, 255)
        draw(
            graphics,
            lt = tint, rt = tint, lb = tint, rb = tint,
            texture = holder(file, 4),
            uv = Rect(0.px, 0.px, 4.px, 4.px),
        )
        graphics.dispose()

        // Transparent texels: the green tint must not paint the backdrop.
        for (y in 10..53) {
            for (x in 9..30) {
                assertEquals(
                    argb(existing.red, existing.green, existing.blue),
                    image.getRGB(x, y),
                    "a transparent texel must leave the backdrop at ($x, $y)",
                )
            }
        }
        // Opaque (255, 128, 0) times (128, 255, 128) is (128, 128, 0).
        val expected = over(
            doubleArrayOf(128 / 255.0, 128 / 255.0, 0.0, 1.0),
            backdrop(existing),
        )
        assertPremul(expected, image.getRGB(50, 32), 2, "tinted opaque texel")
    }

    @Test
    fun `repeating and cropped uv ranges do not restart the gradient`() {
        val file = writeTexture("white", 4, 4) { _, _ -> 0xFFFFFFFF.toInt() }
        val texture = holder(file, 16)

        fun paint(uv: Rect): BufferedImage {
            val image = surface()
            val graphics = image.createGraphics()
            draw(
                graphics,
                lt = white, rt = black, lb = white, rb = black,
                texture = texture,
                uv = uv,
            )
            graphics.dispose()
            return image
        }

        // A white texture multiplies nothing away: the pixels are the gradient alone. UV 0..32 over a
        // 16-unit nominal texture repeats twice; the field must still run white to black once.
        val repeated = paint(Rect(0.px, 0.px, 32.px, 32.px))
        val cropped = paint(Rect(8.px, 8.px, 24.px, 24.px))
        val negative = paint(Rect((-8).px, (-8).px, 8.px, 8.px))

        for (x in intArrayOf(10, 22, 33, 44, 54)) {
            val expected = over(bilinear(white, black, white, black, u(x), 0.5), backdrop(java.awt.Color.BLACK))
            assertPremul(expected, repeated.getRGB(x, 32), 2, "repeated UV at $x")
            assertPremul(expected, cropped.getRGB(x, 32), 2, "cropped UV at $x")
            assertPremul(expected, negative.getRGB(x, 32), 2, "negative UV at $x")
        }
        // A restart at the UV seam would put white back at the middle of the quad.
        val seam = repeated.getRGB(32, 32)
        assertTrue((seam shr 16 and 0xFF) in 124..132, "the gradient is unchanged across the UV seam: ${seam shr 16 and 0xFF}")
    }

    // ---------------------------------------------------------------- clipping and transforms

    @Test
    fun `a scissor clips coverage but does not rescale the field`() {
        val full = surface()
        val fullGraphics = full.createGraphics()
        draw(fullGraphics, lt = white, rt = red, lb = black, rb = blue)
        fullGraphics.dispose()

        val clipped = surface()
        val clippedGraphics = clipped.createGraphics()
        context(WindowRenderParam(clippedGraphics)) {
            renderer.withScissor(Rect(20.px, 20.px, 40.px, 40.px)) {
                draw(clippedGraphics, lt = white, rt = red, lb = black, rb = blue)
            }
        }
        clippedGraphics.dispose()

        val outside = argb(0, 0, 0, 0)
        for (y in 8 until 56) for (x in 8 until 56) {
            val inside = x in 20..39 && y in 20..39
            if (inside) {
                assertEquals(full.getRGB(x, y), clipped.getRGB(x, y), "the field at ($x, $y) must not restart at the scissor")
            } else {
                assertEquals(outside, clipped.getRGB(x, y), "coverage is clipped at ($x, $y)")
            }
        }
    }

    @Test
    fun `rotation and shear keep the source-local field`() {
        val transform = Transform.rotate(32.px, 32.px, Math.toRadians(18.0)) *
            Transform.shear(32.px, 32.px, 0.12, 0.0)
        val image = surface()
        val graphics = image.createGraphics()
        draw(graphics, lt = white, rt = red, lb = black, rb = blue, transform = transform)
        graphics.dispose()

        assertTransformedSamples(image, transform, listOf(0.3 to 0.3, 0.5 to 0.5, 0.7 to 0.4, 0.4 to 0.7, 0.6 to 0.6))
    }

    @Test
    fun `a positive-w perspective keeps the source-local field`() {
        val transform = Transform(1f, 0f, 0f, 0f, 1f, 0f, 0.0009f, 0.0004f, 1f)
        val image = surface()
        val graphics = image.createGraphics()
        draw(graphics, lt = white, rt = red, lb = black, rb = blue, transform = transform)
        graphics.dispose()

        assertTransformedSamples(image, transform, listOf(0.3 to 0.3, 0.5 to 0.5, 0.7 to 0.4, 0.4 to 0.7, 0.6 to 0.6))
    }

    /**
     * Forward-map local points through [transform], sample the pixel that contains the result, and
     * compare it with the field at that pixel's *centre* after mapping back. A bounding-box or
     * screen-space approximation cannot pass this for more than a corner.
     */
    private fun assertTransformedSamples(image: BufferedImage, transform: Transform, locals: List<Pair<Double, Double>>) {
        val inverse = transform.invert()
        for ((u, v) in locals) {
            val device = transform * io.github.u2894638479.kotlinmcui.math.Position(
                (left + u * extent).px,
                (top + v * extent).px,
            )
            val pixelX = device.x.raw.toInt()
            val pixelY = device.y.raw.toInt()
            if (pixelX !in 2 until size - 2 || pixelY !in 2 until size - 2) continue
            val local = inverse * io.github.u2894638479.kotlinmcui.math.Position(
                (pixelX + 0.5).px,
                (pixelY + 0.5).px,
            )
            val localU = (local.x.raw - left) / extent
            val localV = (local.y.raw - top) / extent
            if (localU !in 0.02..0.98 || localV !in 0.02..0.98) continue
            val expected = bilinear(white, red, black, blue, localU, localV)
            assertPremul(expected, image.getRGB(pixelX, pixelY), 3, "transformed sample at local ($u, $v) -> ($pixelX, $pixelY)")
        }
    }

    @Test
    fun `a varying tint multiplies the texture through the same bilinear field`() {
        val file = writeTexture("white-varying", 4, 4) { _, _ -> 0xFFFFFFFF.toInt() }
        val image = surface()
        val graphics = image.createGraphics()
        draw(
            graphics,
            lt = red, rt = green, lb = blue, rb = Color(0, 0, 0, 0),
            texture = holder(file, 4),
            uv = Rect(0.px, 0.px, 4.px, 4.px),
        )
        graphics.dispose()

        // A white texture leaves the field itself; an averaged tint would be a constant colour.
        for (x in intArrayOf(14, 24, 36, 48)) {
            for (y in intArrayOf(14, 24, 36, 48)) {
                val expected = bilinear(red, green, blue, Color(0, 0, 0, 0), u(x), v(y))
                assertPremul(expected, image.getRGB(x, y), 2, "varying tint ($x, $y)")
            }
        }
        val left = image.getRGB(16, 32)
        val right = image.getRGB(48, 32)
        assertTrue((left shr 16 and 0xFF) > (right shr 16 and 0xFF) + 20, "the tint changes across the quad")
    }

    @Test
    fun `a translucent source keeps a translucent destination's alpha correct`() {
        val image = surface()
        val graphics = image.createGraphics()
        // A translucent green backdrop, then a translucent red-to-blue field.
        graphics.color = java.awt.Color(0, 255, 0, 128)
        graphics.fillRect(0, 0, size, size)
        val destination = backdrop(java.awt.Color(0, 255, 0, 128))
        draw(
            graphics,
            lt = Color(255, 0, 0, 128), rt = Color(0, 0, 255, 128),
            lb = Color(255, 0, 0, 128), rb = Color(0, 0, 255, 128),
        )
        graphics.dispose()

        val source = bilinear(
            Color(255, 0, 0, 128), Color(0, 0, 255, 128),
            Color(255, 0, 0, 128), Color(0, 0, 255, 128),
            u(32), v(32),
        )
        val expected = over(source, destination)
        val actual = image.getRGB(32, 32)
        assertPremul(expected, actual, 2, "translucent field over translucent backdrop")
        assertTrue(abs((actual ushr 24) - (expected[3] * 255).roundToInt()) <= 2, "destination alpha is composited, not replaced")
    }

    @Test
    fun `an opaque destination stays opaque under a translucent field`() {
        // The Swing window's surface is RGB, not ARGB: a translucent source must composite into it
        // and leave the destination alpha at 1, never turn the window translucent.
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color.BLACK
        graphics.fillRect(0, 0, size, size)
        draw(
            graphics,
            lt = Color(255, 0, 0, 128), rt = Color(0, 0, 255, 128),
            lb = Color(255, 0, 0, 128), rb = Color(0, 0, 255, 128),
        )
        graphics.dispose()

        val source = bilinear(
            Color(255, 0, 0, 128), Color(0, 0, 255, 128),
            Color(255, 0, 0, 128), Color(0, 0, 255, 128),
            u(32), v(32),
        )
        val actual = image.getRGB(32, 32)
        assertEquals(255, actual ushr 24, "an opaque destination must stay opaque")
        assertPremul(over(source, backdrop(java.awt.Color.BLACK)), actual, 2, "translucent field on an opaque surface")
    }

    @Test
    fun `a projective field under an affine graphics state keeps its local mapping`() {
        // The real composition is graphics-transform ∘ perspective, which is what a page gets when
        // it scales the UI and then draws a perspective-transformed quad inside it.
        val perspective = Transform(1f, 0f, 0f, 0f, 1f, 0f, 0.0008f, 0.0003f, 1f)
        val affine = AffineTransform(1.1, 0.0, 0.0, 0.9, 4.0, 3.0)
        val image = surface()
        val graphics = image.createGraphics()
        graphics.transform(affine)
        draw(graphics, lt = white, rt = red, lb = black, rb = blue, transform = perspective)
        graphics.dispose()

        val toUser = affine.createInverse()
        val inversePerspective = perspective.invert()
        for ((u, v) in listOf(0.3 to 0.3, 0.5 to 0.5, 0.7 to 0.4, 0.4 to 0.7, 0.6 to 0.6)) {
            val projected = perspective * io.github.u2894638479.kotlinmcui.math.Position(
                (left + u * extent).px, (top + v * extent).px,
            )
            val device = java.awt.geom.Point2D.Double(projected.x.raw, projected.y.raw)
            toUser.transform(device, device)
            val pixelX = device.x.toInt()
            val pixelY = device.y.toInt()
            if (pixelX !in 2 until size - 2 || pixelY !in 2 until size - 2) continue
            val user = java.awt.geom.Point2D.Double(pixelX + 0.5, pixelY + 0.5)
            toUser.transform(user, user)
            val local = inversePerspective * io.github.u2894638479.kotlinmcui.math.Position(
                user.x.px, user.y.px,
            )
            val localU = (local.x.raw - left) / extent
            val localV = (local.y.raw - top) / extent
            if (localU !in 0.02..0.98 || localV !in 0.02..0.98) continue
            assertPremul(
                bilinear(white, red, black, blue, localU, localV),
                image.getRGB(pixelX, pixelY), 3,
                "affine plus projective sample at local ($u, $v) -> ($pixelX, $pixelY)",
            )
        }
    }

    // ---------------------------------------------------------------- layering

    @Test
    fun `overlapping gradients composite in draw order`() {
        val existing = java.awt.Color(10, 20, 30)
        val image = surface(existing)
        val graphics = image.createGraphics()
        // A translucent bilinear field, then a half-alpha green over part of it.
        draw(
            graphics,
            lt = Color(255, 0, 0, 160), rt = Color(0, 0, 255, 0),
            lb = Color(0, 0, 255, 0), rb = Color(0, 255, 0, 160),
        )
        draw(
            graphics,
            lt = Color(0, 255, 0, 128), rt = Color(0, 255, 0, 128),
            lb = Color(0, 255, 0, 128), rb = Color(0, 255, 0, 128),
            rect = Rect(24.px, 24.px, 52.px, 52.px),
        )
        graphics.dispose()

        val first = bilinear(
            Color(255, 0, 0, 160), Color(0, 0, 255, 0),
            Color(0, 0, 255, 0), Color(0, 255, 0, 160),
            u(32), v(32),
        )
        val second = Color(0, 255, 0, 128).premultiplied()
        assertPremul(
            over(second, over(first, backdrop(existing))),
            image.getRGB(32, 32), 2, "green over the first gradient over the backdrop",
        )
        // Outside the second quad only the first draw is there.
        val firstAlone = bilinear(
            Color(255, 0, 0, 160), Color(0, 0, 255, 0),
            Color(0, 0, 255, 0), Color(0, 255, 0, 160),
            u(16), v(32),
        )
        assertPremul(over(firstAlone, backdrop(existing)), image.getRGB(16, 32), 2, "first gradient alone")
    }

    // ---------------------------------------------------------------- triangles

    @Test
    fun `untextured triangles interpolate premultiplied vertex colours`() {
        val vertices = floatArrayOf(4f, 4f, 60f, 4f, 4f, 60f)
        val colors = ColorArray(3) { index ->
            if (index == 0) red else Color(0, 0, 255, 0)
        }
        val centroidX = (4 + 60 + 4) / 3
        val centroidY = (4 + 4 + 60) / 3

        val transparent = surface()
        val transparentGraphics = transparent.createGraphics()
        context(WindowRenderParam(transparentGraphics)) { renderer.renderTriangles(measures(vertices), colors) }
        transparentGraphics.dispose()
        val weights = triangleWeights(4.0, 4.0, 60.0, 4.0, 4.0, 60.0, centroidX + 0.5, centroidY + 0.5)
        val source = barycentric(
            red, Color(0, 0, 255, 0), Color(0, 0, 255, 0), weights,
        )
        val centre = transparent.getRGB(centroidX, centroidY)
        assertPremul(over(source, doubleArrayOf(0.0, 0.0, 0.0, 0.0)), centre, 3, "triangle centroid")
        assertTrue((centre ushr 24) in 82..90, "one third alpha: ${centre ushr 24}")
        assertTrue((centre shr 16 and 0xFF) >= 250, "the centroid stays red")

        val overBlack = surface(java.awt.Color.BLACK)
        val blackGraphics = overBlack.createGraphics()
        context(WindowRenderParam(blackGraphics)) { renderer.renderTriangles(measures(vertices), colors) }
        blackGraphics.dispose()
        assertPremul(over(source, backdrop(java.awt.Color.BLACK)), overBlack.getRGB(centroidX, centroidY), 3, "triangle over black")
    }

    @Test
    fun `textured triangles interpolate premultiplied tints and their own uv`() {
        val file = writeTexture("white", 4, 4) { _, _ -> 0xFFFFFFFF.toInt() }
        val texture = holder(file, 16)
        val vertices = floatArrayOf(
            4f, 4f, 0f, 0f,
            60f, 4f, 16f, 0f,
            4f, 60f, 0f, 16f,
        )
        val colors = ColorArray(3) { index ->
            when (index) {
                0 -> red
                1 -> Color(0, 255, 0, 0)
                else -> Color(0, 0, 255, 0)
            }
        }
        val centroidX = (4 + 60 + 4) / 3
        val centroidY = (4 + 4 + 60) / 3

        val transparent = surface()
        val graphics = transparent.createGraphics()
        context(WindowRenderParam(graphics)) { renderer.renderTriangles(measures(vertices), colors, texture) }
        graphics.dispose()

        val source = barycentric(
            red, Color(0, 255, 0, 0), Color(0, 0, 255, 0),
            triangleWeights(4.0, 4.0, 60.0, 4.0, 4.0, 60.0, centroidX + 0.5, centroidY + 0.5),
        )
        assertPremul(source, transparent.getRGB(centroidX, centroidY), 3, "textured triangle centroid")
        assertTrue((transparent.getRGB(centroidX, centroidY) ushr 24) in 82..90, "one third alpha from the tint")
        assertTrue((transparent.getRGB(centroidX, centroidY) shr 16 and 0xFF) >= 250, "the tint stays red")
    }

    /** The barycentric weights of a device pixel centre inside a local triangle. */
    private fun triangleWeights(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
        px: Double, py: Double,
    ): DoubleArray {
        val area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay)
        val weightB = ((cx - px) * (ay - py) - (ax - px) * (cy - py)) / area
        val weightC = ((ax - px) * (by - py) - (bx - px) * (ay - py)) / area
        return doubleArrayOf(1.0 - weightB - weightC, weightB, weightC)
    }

    private fun measures(values: FloatArray): MeasureArray {
        val vertices = MeasureArray(values.size)
        for (index in values.indices) vertices[index] = values[index].px
        return vertices
    }

    // ---------------------------------------------------------------- state and edges

    @Test
    fun `paint composite transform and clip are restored`() {
        val file = writeTexture("white", 4, 4) { _, _ -> 0xFFFFFFFF.toInt() }
        val texture = holder(file, 16)
        val image = surface()
        val graphics = image.createGraphics()
        try {
            val paint = java.awt.Color(1, 2, 3)
            val composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f)
            val transform = AffineTransform(1.0, 0.0, 0.0, 1.0, 3.0, 2.0)
            val clip = Rectangle2D.Float(1f, 1f, 60f, 60f)
            graphics.paint = paint
            graphics.composite = composite
            graphics.transform = transform
            graphics.clip = clip

            context(WindowRenderParam(graphics)) {
                renderer.renderQuad(Rect(10.px, 10.px, 30.px, 30.px), white, red, black, blue)
                renderer.renderQuad(
                    Rect(10.px, 30.px, 30.px, 50.px), white, red, black, blue,
                    texture, Rect(0.px, 0.px, 16.px, 16.px),
                )
                renderer.withTransform(Transform(1f, 0f, 0f, 0f, 1f, 0f, 0.0009f, 0f, 1f)) {
                    renderer.renderQuad(Rect(30.px, 10.px, 50.px, 30.px), white, red, black, blue)
                }
                renderer.renderTriangles(measures(floatArrayOf(30f, 30f, 50f, 30f, 30f, 50f)), ColorArray(3) { red })
            }

            assertSame(paint, graphics.paint, "paint must be restored after every path")
            assertSame(composite, graphics.composite, "composite must be restored after every path")
            assertEquals(transform, graphics.transform, "transform must be restored after every path")
            assertEquals(clip, graphics.clip, "clip must be restored after every path")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `the outer anti-aliased edge is coverage only`() {
        // A transparent target, so the destination alpha still reports the coverage the shape's
        // outer edge received; an opaque backdrop would force every alpha to 255.
        val image = surface()
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        draw(graphics, lt = red, rt = red, lb = red, rb = red, rect = Rect(8.4.px, 8.4.px, 55.6.px, 55.6.px))
        graphics.dispose()

        assertEquals(0, image.getRGB(6, 32) ushr 24, "outside the shape is untouched")
        assertEquals(255, image.getRGB(30, 32) ushr 24, "the interior is fully covered")
        val edge = image.getRGB(8, 32)
        val edgeAlpha = edge ushr 24
        assertTrue(edgeAlpha in 1..254, "the boundary pixel is partially covered, alpha $edgeAlpha")
        assertTrue((edge shr 16 and 0xFF) >= 250 && (edge and 0xFF) <= 3, "edge coverage still carries the source colour")
    }

    // ---------------------------------------------------------------- transforms and triangle meshes

    @Test
    fun `an affine scope inside an active projective composes in both nesting orders`() {
        // The real call path: both scopes go through withTransform, and the graphics state already
        // carries an outer device transform that must survive and stay outermost.
        val projective = Transform(1f, 0.1f, 6f, 0.05f, 1f, 6f, 0.008f, 0.003f, 1f)
        val affine = Transform(1.3f, 0f, 12f, 0f, 1.1f, 8f, 0f, 0f, 1f)
        val deviceState = AffineTransform(1.1, 0.0, 0.0, 1.05, 4.0, 3.0)
        val file = writeTexture("nested-orders", 4, 4) { _, _ -> 0xFFFFFFFF.toInt() }
        val texture = holder(file, 16)
        val locals = listOf(0.3 to 0.3, 0.5 to 0.5, 0.7 to 0.4, 0.4 to 0.7, 0.6 to 0.6)

        for (textured in booleanArrayOf(false, true)) {
            for (order in listOf(
                Triple(projective, affine, projective * affine),
                Triple(affine, projective, affine * projective),
            )) {
                val (outer, inner, composed) = order
                val image = surface()
                val graphics = image.createGraphics()
                graphics.transform(deviceState)
                try {
                    context(WindowRenderParam(graphics)) {
                        renderer.withTransform(outer) {
                            renderer.withTransform(inner) {
                                if (textured) {
                                    renderer.renderQuad(
                                        quad, white, red, black, blue,
                                        texture, Rect(0.px, 0.px, 16.px, 16.px),
                                    )
                                } else {
                                    renderer.renderQuad(quad, white, red, black, blue)
                                }
                            }
                        }
                    }
                    assertEquals(deviceState, graphics.transform, "the outer device transform is preserved")
                } finally {
                    graphics.dispose()
                }

                val toUser = deviceState.createInverse()
                val inverse = composed.invert()
                for ((u, v) in locals) {
                    val projected = composed * io.github.u2894638479.kotlinmcui.math.Position(
                        (left + u * extent).px, (top + v * extent).px,
                    )
                    val device = java.awt.geom.Point2D.Double(projected.x.raw, projected.y.raw)
                    deviceState.transform(device, device)
                    val pixelX = device.x.toInt()
                    val pixelY = device.y.toInt()
                    if (pixelX !in 2 until size - 2 || pixelY !in 2 until size - 2) continue
                    val user = java.awt.geom.Point2D.Double(pixelX + 0.5, pixelY + 0.5)
                    toUser.transform(user, user)
                    val local = inverse * io.github.u2894638479.kotlinmcui.math.Position(user.x.px, user.y.px)
                    val localU = (local.x.raw - left) / extent
                    val localV = (local.y.raw - top) / extent
                    if (localU !in 0.02..0.98 || localV !in 0.02..0.98) continue
                    assertPremul(
                        bilinear(white, red, black, blue, localU, localV),
                        image.getRGB(pixelX, pixelY), 3,
                        "nested ${if (textured) "textured" else "untextured"} at ($u, $v) -> ($pixelX, $pixelY)",
                    )
                }
            }
        }
    }

    @Test
    fun `a triangle mesh covers a shared edge once under anti-aliasing`() {
        val image = surface(java.awt.Color(0, 0, 255))
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val half = Color(255, 255, 255, 128)
        val vertices = floatArrayOf(
            4f, 4f, 45f, 4f, 4f, 45f,
            45f, 4f, 45f, 45f, 4f, 45f,
        )
        try {
            context(WindowRenderParam(graphics)) {
                renderer.renderTriangles(measures(vertices), ColorArray(6) { half })
            }
        } finally {
            graphics.dispose()
        }

        // The diagonal RT-LB is shared by both triangles. Its pixel centres must receive the field
        // exactly once, not half from each triangle over the blue backdrop.
        val expected = over(half.premultiplied(), backdrop(java.awt.Color(0, 0, 255)))
        for (x in 10..38 step 4) {
            val y = 48 - x
            assertPremul(expected, image.getRGB(x, y), 2, "shared edge ($x, $y)")
        }
    }

    @Test
    fun `intentionally overlapping triangles composite in the supplied order`() {
        val image = surface(java.awt.Color(0, 0, 255))
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val first = Color(255, 0, 0, 128)
        val second = Color(0, 255, 0, 128)
        val vertices = floatArrayOf(
            4f, 4f, 45f, 4f, 4f, 45f,
            12f, 9f, 53f, 9f, 12f, 50f,
        )
        try {
            context(WindowRenderParam(graphics)) {
                renderer.renderTriangles(
                    measures(vertices),
                    ColorArray(6) { if (it < 3) first else second },
                )
            }
        } finally {
            graphics.dispose()
        }

        val background = backdrop(java.awt.Color(0, 0, 255))
        val firstAlone = over(first.premultiplied(), background)
        assertPremul(firstAlone, image.getRGB(8, 8), 2, "only the first triangle covers this pixel")
        assertPremul(
            over(second.premultiplied(), firstAlone),
            image.getRGB(20, 20), 2,
            "the overlapping area composites both triangles in supplied order",
        )
    }

    @Test
    fun `a reversed textured mesh keeps its colours and uv under a transform`() {
        val texels = intArrayOf(
            argb(255, 40, 20), argb(20, 240, 40),
            argb(20, 40, 240), argb(240, 200, 80),
        )
        val file = writeTexture("reversed-mesh", 2, 2) { x, y -> texels[y * 2 + x] }
        val texture = holder(file, 2)
        // Reversed winding in both triangles, with the shared vertices carrying the same values.
        val points = doubleArrayOf(
            4.0, 45.0, 45.0, 4.0, 4.0, 4.0,
            4.0, 45.0, 45.0, 45.0, 45.0, 4.0,
        )
        val uvs = doubleArrayOf(
            0.0, 2.0, 2.0, 0.0, 0.0, 0.0,
            0.0, 2.0, 2.0, 2.0, 2.0, 0.0,
        )
        val colors = listOf(
            Color(40, 80, 255, 0), Color(20, 255, 40, 128), Color(255, 20, 80, 255),
            Color(40, 80, 255, 0), Color(240, 160, 40, 64), Color(20, 255, 40, 128),
        )
        val transform = Transform.rotate(24.px, 24.px, Math.toRadians(17.0)) *
            Transform.shear(24.px, 24.px, 0.1, 0.05)
        val image = surface(java.awt.Color(30, 60, 120))
        val graphics = image.createGraphics()
        graphics.transform(AffineTransform(1.0, 0.0, 0.0, 1.0, 3.0, 2.0))
        // The textured stride interleaves x, y, u, v for each of the six vertices.
        val vertices = MeasureArray(24)
        for (vertex in 0 until 6) {
            vertices[vertex * 4] = points[vertex * 2].toFloat().px
            vertices[vertex * 4 + 1] = points[vertex * 2 + 1].toFloat().px
            vertices[vertex * 4 + 2] = uvs[vertex * 2].toFloat().px
            vertices[vertex * 4 + 3] = uvs[vertex * 2 + 1].toFloat().px
        }
        val colorsArray = ColorArray(6) { colors[it] }
        try {
            context(WindowRenderParam(graphics)) {
                renderer.withTransform(transform) {
                    renderer.renderTriangles(vertices, colorsArray, texture)
                }
            }
        } finally {
            graphics.dispose()
        }

        val inverse = transform.invert()
        val background = backdrop(java.awt.Color(30, 60, 120))
        for ((u, v) in listOf(0.2 to 0.2, 0.6 to 0.2, 0.7 to 0.5, 0.2 to 0.6, 0.6 to 0.7)) {
            val projected = transform * io.github.u2894638479.kotlinmcui.math.Position(
                (4 + u * 41).px, (4 + v * 41).px,
            )
            val pixelX = (projected.x.raw + 3.0).toInt()
            val pixelY = (projected.y.raw + 2.0).toInt()
            if (pixelX !in 2 until size - 2 || pixelY !in 2 until size - 2) continue
            val local = inverse * io.github.u2894638479.kotlinmcui.math.Position(
                (pixelX + 0.5 - 3.0).px, (pixelY + 0.5 - 2.0).px,
            )
            val localU = (local.x.raw - 4.0) / 41.0
            val localV = (local.y.raw - 4.0) / 41.0
            val triangle = when {
                localU + localV < 0.98 -> 0
                localU + localV > 1.02 -> 1
                else -> continue
            }
            val offset = triangle * 6
            val weights = triangleWeights(
                points[offset], points[offset + 1],
                points[offset + 2], points[offset + 3],
                points[offset + 4], points[offset + 5],
                local.x.raw, local.y.raw,
            )
            val tinted = barycentric(
                colors[offset / 2], colors[offset / 2 + 1], colors[offset / 2 + 2], weights,
            )
            val texU = weights[0] * uvs[offset] + weights[1] * uvs[offset + 2] + weights[2] * uvs[offset + 4]
            val texV = weights[0] * uvs[offset + 1] + weights[1] * uvs[offset + 3] + weights[2] * uvs[offset + 5]
            val texel = texels[Math.floorMod(Math.floor(texV).toInt(), 2) * 2 + Math.floorMod(Math.floor(texU).toInt(), 2)]
            val source = doubleArrayOf(
                tinted[0] * (texel shr 16 and 0xFF) / 255.0,
                tinted[1] * (texel shr 8 and 0xFF) / 255.0,
                tinted[2] * (texel and 0xFF) / 255.0,
                tinted[3],
            )
            assertPremul(over(source, background), image.getRGB(pixelX, pixelY), 3, "reversed mesh at ($pixelX, $pixelY)")
        }
    }

    @Test
    fun `a triangle's outer edge keeps anti-aliased coverage`() {
        val image = surface()
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val vertices = floatArrayOf(8.4f, 8.4f, 55.6f, 8.4f, 8.4f, 55.6f)
        try {
            context(WindowRenderParam(graphics)) {
                renderer.renderTriangles(measures(vertices), ColorArray(3) { red })
            }
        } finally {
            graphics.dispose()
        }

        assertEquals(0, image.getRGB(5, 5) ushr 24, "outside the triangle is untouched")
        assertEquals(255, image.getRGB(30, 20) ushr 24, "the interior is fully covered")
        val edge = image.getRGB(8, 32) ushr 24
        assertTrue(edge in 1..254, "the slanted boundary keeps partial coverage, alpha $edge")
    }
}
