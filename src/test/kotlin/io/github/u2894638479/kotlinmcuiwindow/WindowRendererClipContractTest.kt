package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.context.DslScaleContext
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.ColorArray
import io.github.u2894638479.kotlinmcui.math.Measure
import io.github.u2894638479.kotlinmcui.math.MeasureArray
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import io.github.u2894638479.kotlinmcui.math.transform.Transform
import io.github.u2894638479.kotlinmcui.text.DslCharStyle
import io.github.u2894638479.kotlinmcui.text.DslRenderableChar
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The scissor contract, exercised through the production [WindowRenderer] and real images.
 *
 * The expected region is built independently: a small double-precision homography composes the same
 * local-to-output order as the renderer, projects all four rectangle corners, and classifies pixel
 * centres with the half planes of that quadrilateral. It never asks the renderer or a bounding box
 * where the clip is. Samples are taken well clear of an edge, because outer anti-aliasing may move
 * coverage by a pixel without changing the geometric contract.
 */
class WindowRendererClipContractTest {
    private val size = 96
    private val renderer = WindowRenderer()
    private val red = Color(255, 0, 0)
    private val green = Color(0, 255, 0)
    private val blue = Color(0, 0, 255)
    private val white = Color(255, 255, 255)

    private val files = mutableListOf<File>()
    private val directories = mutableListOf<Path>()

    @BeforeTest
    fun inlineImages() {
        WindowImages.reset()
        WindowImages.executor = Executor { it.run() }
        WindowImages.publish = { it() }
    }

    @AfterTest
    fun release() {
        WindowItems.use(null, null)
        WindowImages.reset()
        files.forEach { it.delete() }
        directories.forEach { directory ->
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    // ---------------------------------------------------------------- independent projection

    /** A 3x3 homogeneous matrix used only by the reference, never by the renderer. */
    private class Homography(private val m: DoubleArray) {
        operator fun times(other: Homography): Homography {
            val out = DoubleArray(9)
            for (row in 0 until 3) for (column in 0 until 3) {
                out[row * 3 + column] =
                    m[row * 3] * other.m[column] +
                        m[row * 3 + 1] * other.m[3 + column] +
                        m[row * 3 + 2] * other.m[6 + column]
            }
            return Homography(out)
        }

        fun project(x: Double, y: Double): Point2D.Double? {
            val w = m[6] * x + m[7] * y + m[8]
            if (!(w > 1e-6) || !w.isFinite()) return null
            val px = (m[0] * x + m[1] * y + m[2]) / w
            val py = (m[3] * x + m[4] * y + m[5]) / w
            if (!px.isFinite() || !py.isFinite()) return null
            return Point2D.Double(px, py)
        }

        fun quad(left: Float, top: Float, right: Float, bottom: Float): ProjectedQuad {
            val xs = doubleArrayOf(left.toDouble(), right.toDouble(), right.toDouble(), left.toDouble())
            val ys = doubleArrayOf(top.toDouble(), top.toDouble(), bottom.toDouble(), bottom.toDouble())
            val projectedX = DoubleArray(4)
            val projectedY = DoubleArray(4)
            for (index in 0 until 4) {
                val point = assertNotNull(project(xs[index], ys[index]), "reference projection must have positive w")
                projectedX[index] = point.x
                projectedY[index] = point.y
            }
            return ProjectedQuad(projectedX, projectedY)
        }

        companion object {
            val IDENTITY = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

            fun affine(transform: AffineTransform) = Homography(
                doubleArrayOf(
                    transform.scaleX, transform.shearX, transform.translateX,
                    transform.shearY, transform.scaleY, transform.translateY,
                    0.0, 0.0, 1.0,
                ),
            )

            fun of(transform: Transform) = Homography(
                doubleArrayOf(
                    transform.m00.toDouble(), transform.m01.toDouble(), transform.m02.toDouble(),
                    transform.m10.toDouble(), transform.m11.toDouble(), transform.m12.toDouble(),
                    transform.m20.toDouble(), transform.m21.toDouble(), transform.m22.toDouble(),
                ),
            )
        }
    }

    /** A convex quadrilateral classified by its own oriented half planes. */
    private class ProjectedQuad(private val xs: DoubleArray, private val ys: DoubleArray) {
        private val orientation: Double

        init {
            var twiceArea = 0.0
            for (index in 0 until 4) {
                val next = (index + 1) % 4
                twiceArea += xs[index] * ys[next] - xs[next] * ys[index]
            }
            orientation = if (twiceArea >= 0.0) 1.0 else -1.0
        }

        /** Signed distance from the nearest edge; positive is inside. */
        fun clearance(x: Double, y: Double): Double {
            var nearest = Double.POSITIVE_INFINITY
            for (index in 0 until 4) {
                val next = (index + 1) % 4
                val ex = xs[next] - xs[index]
                val ey = ys[next] - ys[index]
                val length = kotlin.math.hypot(ex, ey)
                if (!(length > 0.0)) return Double.NEGATIVE_INFINITY
                val cross = (ex * (y - ys[index]) - ey * (x - xs[index])) / length
                nearest = minOf(nearest, cross * orientation)
            }
            return nearest
        }

        fun contains(x: Double, y: Double, margin: Double = 0.0) = clearance(x, y) >= margin

        val minX get() = xs.minOrNull()!!
        val minY get() = ys.minOrNull()!!
        val maxX get() = xs.maxOrNull()!!
        val maxY get() = ys.maxOrNull()!!
    }

    private fun device(affine: AffineTransform, vararg transforms: Transform): Homography {
        var result = Homography.affine(affine)
        for (transform in transforms) result = result * Homography.of(transform)
        return result
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

    private fun full(margin: Int = 8) = Rect((-margin).px, (-margin).px, (size + margin).px, (size + margin).px)

    private fun drawFull(graphics: java.awt.Graphics2D, color: Color = red) {
        context(WindowRenderParam(graphics, size, size)) {
            renderer.renderQuad(full(), color, color, color, color)
        }
    }

    private fun assertTransparentOutside(image: BufferedImage, where: String) {
        for (y in 0 until size) for (x in 0 until size) {
            assertEquals(0, image.getRGB(x, y) ushr 24, "$where: pixel ($x, $y) is outside the clip")
        }
    }

    private fun assertRegion(image: BufferedImage, reference: ProjectedQuad, where: String) {
        var inside = 0
        var outside = 0
        for (y in 0 until size) for (x in 0 until size) {
            val centreX = x + 0.5
            val centreY = y + 0.5
            val clearance = reference.clearance(centreX, centreY)
            when {
                clearance >= 2.0 -> {
                    inside++
                    assertEquals(255, image.getRGB(x, y) ushr 24, "$where: pixel ($x, $y) is well inside the clip")
                }
                clearance <= -2.0 -> {
                    outside++
                    assertEquals(0, image.getRGB(x, y) ushr 24, "$where: pixel ($x, $y) is well outside the clip")
                }
            }
        }
        assertTrue(inside > 20, "$where: the reference region has too few inside samples: $inside")
        assertTrue(outside > 20, "$where: the reference region has too few outside samples: $outside")
    }

    private fun writeTexture(name: String): ImageHolder {
        val file = File(System.getProperty("java.io.tmpdir"), "kotlinmcuiwindow-clip-$name.png")
        file.deleteOnExit()
        file.delete()
        files += file
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 4) for (x in 0 until 4) image.setRGB(x, y, 0xFFFFFFFF.toInt())
        ImageIO.write(image, "png", file)
        WindowImages.holder(file)
        return ImageHolder(ImageSource.LocalFile(file), 4.px, 4.px)
    }

    private fun measures(values: FloatArray): MeasureArray {
        val result = MeasureArray(values.size)
        for (index in values.indices) result[index] = values[index].px
        return result
    }

    private enum class PrimitiveContent { TEXTURED_QUAD, UNTEXTURED_TRIANGLES, TEXTURED_TRIANGLES }

    private enum class WidgetContent { BUTTON, SLOT, CONTAINER, TOOLTIP, ITEM, BACKGROUND }

    context(renderParam: WindowRenderParam)
    private fun drawPrimitive(kind: PrimitiveContent, texture: ImageHolder, uv: Rect) {
        when (kind) {
            PrimitiveContent.TEXTURED_QUAD ->
                renderer.renderQuad(full(24), white, white, white, white, texture, uv)
            PrimitiveContent.UNTEXTURED_TRIANGLES -> {
                val vertices = floatArrayOf(
                    -24f, -24f, 120f, -24f, 120f, 120f,
                    -24f, -24f, 120f, 120f, -24f, 120f,
                )
                renderer.renderTriangles(measures(vertices), ColorArray(6) { red })
            }
            PrimitiveContent.TEXTURED_TRIANGLES -> {
                val vertices = floatArrayOf(
                    -24f, -24f, 0f, 0f, 120f, -24f, 4f, 0f, 120f, 120f, 4f, 4f,
                    -24f, -24f, 0f, 0f, 120f, 120f, 4f, 4f, -24f, 120f, 0f, 4f,
                )
                renderer.renderTriangles(measures(vertices), ColorArray(6) { white }, texture)
            }
        }
    }

    context(renderParam: WindowRenderParam, ctx: DslScaleContext)
    private fun drawWidget(kind: WidgetContent, plate: Rect) {
        when (kind) {
            WidgetContent.BUTTON -> renderer.renderButton(plate, highlighted = true, active = true)
            WidgetContent.SLOT -> renderer.renderSlot(plate)
            WidgetContent.CONTAINER -> renderer.renderContainer(plate)
            WidgetContent.TOOLTIP -> renderer.renderTooltip(plate)
            WidgetContent.ITEM -> renderer.renderItem(plate, "kotlinmcuiwindow:missing", 3, 0.4, true)
            WidgetContent.BACKGROUND -> renderer.renderDefaultBackground(plate)
        }
    }

    // ---------------------------------------------------------------- exact projected geometry

    @Test
    fun `a rotated scissor is a diamond not its bounding box`() {
        val rotation = Transform.rotate((size / 2).px, (size / 2).px, Math.toRadians(45.0))
        val image = surface()
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        try {
            val param = WindowRenderParam(graphics, size, size)
            context(param) {
                renderer.withTransform(rotation) {
                    renderer.withScissor(Rect(24.px, 24.px, 72.px, 72.px)) {
                        renderer.renderQuad(full(16), red, red, red, red)
                    }
                }
            }
            assertNull(graphics.clip, "the previous clip is restored")
            assertEquals(AffineTransform(), graphics.transform, "the transform is restored")
        } finally {
            graphics.dispose()
        }

        val reference = device(AffineTransform(), rotation).quad(24f, 24f, 72f, 72f)
        assertRegion(image, reference, "45 degree diamond")

        // The four corners of the reference's axis-aligned bound are all outside the diamond. A
        // bounding-box implementation would have painted them.
        for (corner in listOf(
            reference.minX to reference.minY,
            reference.maxX to reference.minY,
            reference.minX to reference.maxY,
            reference.maxX to reference.maxY,
        )) {
            val x = (corner.first + 0.5).toInt().coerceIn(0, size - 1)
            val y = (corner.second + 0.5).toInt().coerceIn(0, size - 1)
            assertFalse(reference.contains(x + 0.5, y + 0.5, 1.0), "bounding-box corner ($x, $y) must be outside")
            assertEquals(0, image.getRGB(x, y) ushr 24, "bounding-box corner ($x, $y) must stay untouched")
        }
    }

    @Test
    fun `nested transforms keep the parent scissor frozen in output space`() {
        val deviceState = AffineTransform(1.1, 0.0, 0.0, 1.05, 3.0, 2.0)
        val outer = Transform.rotate(48.px, 48.px, Math.toRadians(18.0))
        val inner = Transform.rotate(48.px, 48.px, Math.toRadians(-31.0)) *
            Transform.translate(9.px, -5.px)
        val image = surface()
        val graphics = image.createGraphics()
        try {
            graphics.transform(deviceState)
            val param = WindowRenderParam(graphics, size, size)
            context(param) {
                renderer.withTransform(outer) {
                    renderer.withScissor(Rect(26.px, 26.px, 70.px, 70.px)) {
                        renderer.withTransform(inner) {
                            renderer.renderQuad(full(24), red, red, red, red)
                        }
                    }
                }
            }
            assertEquals(deviceState, graphics.transform, "the outer device transform is preserved")
            assertNull(graphics.clip, "the clip is restored after nested transforms")
        } finally {
            graphics.dispose()
        }

        val frozen = device(deviceState, outer).quad(26f, 26f, 70f, 70f)
        assertRegion(image, frozen, "nested affine scissor")

        // A moving clip would follow the inner transform. There must be a sample that is inside the
        // frozen parent and outside the inner-projected scissor; it has to stay painted.
        val moved = device(deviceState, outer, inner).quad(26f, 26f, 70f, 70f)
        var proved = false
        for (y in 0 until size) for (x in 0 until size) {
            val cx = x + 0.5
            val cy = y + 0.5
            if (frozen.clearance(cx, cy) >= 3.0 && moved.clearance(cx, cy) <= -3.0) {
                assertEquals(255, image.getRGB(x, y) ushr 24, "the frozen parent still covers ($x, $y)")
                proved = true
            }
        }
        assertTrue(proved, "the inner transform must differ enough to distinguish a frozen clip from a moving one")
    }

    @Test
    fun `scissors under P A and A P nesting project through the complete matrix`() {
        val perspective = Transform(
            1f, 0.02f, 4f,
            0.015f, 1f, 3f,
            0.0006f, 0.00035f, 1f,
        )
        val affine = Transform(
            1.04f, 0.08f, 5f,
            0.05f, 0.96f, 4f,
            0f, 0f, 1f,
        )
        val deviceState = AffineTransform(1.02, 0.0, 0.0, 1.01, 2.0, 1.0)
        val cases = listOf(
            Triple("P then A", perspective, affine),
            Triple("A then P", affine, perspective),
        )
        for ((name, outer, inner) in cases) {
            val referenceTransform = device(deviceState, outer, inner)
            val image = surface()
            val graphics = image.createGraphics()
            try {
                graphics.transform(deviceState)
                val param = WindowRenderParam(graphics, size, size)
                context(param) {
                    renderer.withTransform(outer) {
                        renderer.withTransform(inner) {
                            renderer.withScissor(Rect(22.px, 24.px, 74.px, 70.px)) {
                                renderer.renderQuad(full(32), red, red, red, red)
                            }
                        }
                    }
                }
                assertNull(graphics.clip, "$name: clip restored")
                assertEquals(deviceState, graphics.transform, "$name: transform restored")
            } finally {
                graphics.dispose()
            }
            val reference = referenceTransform.quad(22f, 24f, 74f, 70f)
            assertRegion(image, reference, "$name projective scissor")
        }
    }

    // ---------------------------------------------------------------- fail closed

    private fun runEmptyCase(name: String, body: (WindowRenderParam, () -> Unit) -> Unit) {
        val image = surface()
        val graphics = image.createGraphics()
        val initialClip = graphics.clip
        val initialTransform = graphics.transform
        val param = WindowRenderParam(graphics, size, size)
        var blocks = 0
        try {
            body(param) { blocks++ }
            assertEquals(1, blocks, "$name: the block must run exactly once")
            assertTransparentOutside(image, "$name empty scissor")
            assertEquals(initialClip, graphics.clip, "$name: the clip is restored")
            assertEquals(initialTransform, graphics.transform, "$name: the transform is restored")
            assertNull(param.perspective, "$name: the perspective is restored")

            // The following draw must not inherit the empty clip or a broken transform.
            context(param) {
                renderer.renderQuad(Rect(44.px, 44.px, 68.px, 68.px), red, red, red, red)
            }
            assertEquals(255, image.getRGB(56, 56) ushr 24, "$name: the next draw is unclipped")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `invalid empty singular and eye-crossing scissors fail closed and restore`() {
        val cases = listOf<Pair<String, (WindowRenderParam, () -> Unit) -> Unit>>(
            "zero width" to { param, block ->
                context(param) {
                    renderer.withScissor(Rect(20.px, 20.px, 20.px, 72.px)) { block(); renderer.renderQuad(full(24), red, red, red, red) }
                }
            },
            "negative height" to { param, block ->
                context(param) {
                    renderer.withScissor(Rect(20.px, 72.px, 72.px, 20.px)) { block(); renderer.renderQuad(full(24), red, red, red, red) }
                }
            },
            "NaN edge" to { param, block ->
                context(param) {
                    renderer.withScissor(Rect(Float.NaN.px, 20.px, 72.px, 72.px)) { block(); renderer.renderQuad(full(24), red, red, red, red) }
                }
            },
            "unmeasured edge" to { param, block ->
                context(param) {
                    renderer.withScissor(Rect(Measure.AUTO, 20.px, 72.px, 72.px)) { block(); renderer.renderQuad(full(24), red, red, red, red) }
                }
            },
            "infinite edge" to { param, block ->
                context(param) {
                    renderer.withScissor(Rect(20.px, 20.px, Float.POSITIVE_INFINITY.px, 72.px)) { block(); renderer.renderQuad(full(24), red, red, red, red) }
                }
            },
            "overflowing extent" to { param, block ->
                context(param) {
                    renderer.withScissor(Rect((-Float.MAX_VALUE).px, 20.px, Float.MAX_VALUE.px, 72.px)) {
                        block(); renderer.renderQuad(full(24), red, red, red, red)
                    }
                }
            },
            "disjoint nested scissors" to { param, block ->
                context(param) {
                    renderer.withScissor(Rect(4.px, 4.px, 20.px, 20.px)) {
                        renderer.withScissor(Rect(70.px, 70.px, 90.px, 90.px)) {
                            block(); renderer.renderQuad(full(24), red, red, red, red)
                        }
                    }
                }
            },
            "singular transform" to { param, block ->
                context(param) {
                    renderer.withTransform(Transform(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)) {
                        renderer.withScissor(Rect(20.px, 20.px, 72.px, 72.px)) {
                            block(); renderer.renderQuad(full(24), red, red, red, red)
                        }
                    }
                }
            },
            "non-finite transform" to { param, block ->
                context(param) {
                    renderer.withTransform(Transform(Float.NaN, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) {
                        renderer.withScissor(Rect(20.px, 20.px, 72.px, 72.px)) {
                            block(); renderer.renderQuad(full(24), red, red, red, red)
                        }
                    }
                }
            },
            "eye crossing" to { param, block ->
                context(param) {
                    renderer.withTransform(Transform(1f, 0f, 0f, 0f, 1f, 0f, -0.05f, 0f, 1f)) {
                        renderer.withScissor(Rect(20.px, 20.px, 72.px, 72.px)) {
                            block(); renderer.renderQuad(full(24), red, red, red, red)
                        }
                    }
                }
            },
            "singular current graphics transform" to { param, block ->
                val saved = param.graphics.transform
                param.graphics.transform = AffineTransform(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
                try {
                    context(param) {
                        renderer.withScissor(Rect(20.px, 20.px, 72.px, 72.px)) {
                            block(); renderer.renderQuad(full(24), red, red, red, red)
                        }
                    }
                } finally {
                    param.graphics.transform = saved
                }
            },
        )
        for ((name, body) in cases) runEmptyCase(name, body)
    }

    @Test
    fun `an empty parent keeps a child empty and still runs both blocks`() {
        val image = surface()
        val graphics = image.createGraphics()
        val param = WindowRenderParam(graphics, size, size)
        var outerBlocks = 0
        var innerBlocks = 0
        try {
            context(param) {
                renderer.withScissor(Rect(0.px, 0.px, 0.px, 0.px)) {
                    outerBlocks++
                    renderer.withScissor(Rect(20.px, 20.px, 70.px, 70.px)) {
                        innerBlocks++
                        renderer.renderQuad(full(24), red, red, red, red)
                    }
                    renderer.renderQuad(full(24), red, red, red, red)
                }
            }
            assertEquals(1, outerBlocks, "the empty parent block runs once")
            assertEquals(1, innerBlocks, "the child block runs once under an empty parent")
            assertTransparentOutside(image, "empty parent")
            assertNull(graphics.clip, "the parent clip is restored")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `a borrowed mutable rect is snapshotted at entry`() {
        class MutableRect : Rect {
            override var left: Measure = 12.px
            override var top: Measure = 10.px
            override var right: Measure = 60.px
            override var bottom: Measure = 58.px
        }

        val rect = MutableRect()
        val image = surface()
        val graphics = image.createGraphics()
        val param = WindowRenderParam(graphics, size, size)
        try {
            context(param) {
                renderer.withScissor(rect) {
                    rect.left = 74.px
                    rect.top = 70.px
                    rect.right = 92.px
                    rect.bottom = 90.px
                    renderer.renderQuad(full(24), red, red, red, red)
                }
            }
            assertEquals(255, image.getRGB(20, 20) ushr 24, "the entry rectangle still covers its inside")
            assertEquals(0, image.getRGB(80, 80) ushr 24, "rewriting the borrowed rect must not grow the clip")
            assertNull(graphics.clip, "the previous clip is restored")
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `exceptions propagate and leave the next draw unclipped`() {
        val boom = IllegalStateException("clip block failure")
        val image = surface()
        val graphics = image.createGraphics()
        val param = WindowRenderParam(graphics, size, size)
        var blocks = 0
        try {
            val thrown = runCatching {
                context(param) {
                    renderer.withScissor(Rect(20.px, 20.px, 60.px, 60.px)) {
                        renderer.withTransform(Transform.rotate(48.px, 48.px, Math.toRadians(30.0))) {
                            renderer.withScissor(Rect(28.px, 28.px, 50.px, 50.px)) {
                                blocks++
                                throw boom
                            }
                        }
                    }
                }
            }.exceptionOrNull()
            assertSame(boom, thrown, "the same exception instance is propagated")
            assertEquals(1, blocks, "the throwing block ran once")
            assertNull(graphics.clip, "the outermost clip is restored")
            assertEquals(AffineTransform(), graphics.transform, "the transform is restored")
            assertNull(param.outputClip, "the frozen region is restored")
            assertNull(param.perspective, "the perspective is restored")

            context(param) {
                renderer.renderQuad(Rect(8.px, 8.px, 88.px, 88.px), red, red, red, red)
            }
            assertEquals(255, image.getRGB(80, 80) ushr 24, "the following draw is not clipped by the failed scope")
        } finally {
            graphics.dispose()
        }
    }

    // ---------------------------------------------------------------- content kinds

    @Test
    fun `text is clipped outside the scissor and unchanged well inside`() {
        val scissor = Rect(16.px, 16.px, 76.px, 76.px)
        val fullImage = surface()
        val clippedImage = surface()
        val fullGraphics = fullImage.createGraphics()
        val clippedGraphics = clippedImage.createGraphics()
        for (graphics in listOf(fullGraphics, clippedGraphics)) {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        }
        val font = renderer.getFont("SansSerif")
        try {
            context(WindowRenderParam(fullGraphics, size, size)) {
                font.renderChar(
                    DslRenderableChar('M'.code, DslCharStyle(), white, 50.px),
                    2.px, 4.px, 0.px, 0.px,
                )
            }
            context(WindowRenderParam(clippedGraphics, size, size)) {
                renderer.withScissor(scissor) {
                    font.renderChar(
                        DslRenderableChar('M'.code, DslCharStyle(), white, 50.px),
                        2.px, 4.px, 0.px, 0.px,
                    )
                }
            }
        } finally {
            fullGraphics.dispose()
            clippedGraphics.dispose()
        }

        val reference = device(AffineTransform()).quad(16f, 16f, 76f, 76f)
        var insidePixels = 0
        var equalInside = 0
        for (y in 0 until size) for (x in 0 until size) {
            val clearance = reference.clearance(x + 0.5, y + 0.5)
            if (clearance <= -2.0) {
                assertEquals(0, clippedImage.getRGB(x, y) ushr 24, "text outside the scissor at ($x, $y)")
            } else if (clearance >= 2.0) {
                if (clippedImage.getRGB(x, y) ushr 24 != 0) insidePixels++
                // The glyph shape itself is not prescribed, but inside the clip the same font and
                // same Java2D raster must produce exactly the unclipped reference.
                if (clippedImage.getRGB(x, y) == fullImage.getRGB(x, y)) equalInside++
            }
        }
        assertTrue(insidePixels > 40, "the named font must actually draw inside the scissor: $insidePixels")
        assertTrue(equalInside > 40, "interior glyph pixels are unchanged, not approximated: $equalInside")
    }

    @Test
    fun `textured quads and both triangle overloads obey the same clip`() {
        val texture = writeTexture("uniform-white")
        val uv = Rect(0.px, 0.px, 4.px, 4.px)
        val scissor = Rect(24.px, 22.px, 70.px, 68.px)
        val reference = device(AffineTransform()).quad(24f, 22f, 70f, 68f)

        for (kind in PrimitiveContent.values()) {
            val image = surface()
            val graphics = image.createGraphics()
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            try {
                val param = WindowRenderParam(graphics, size, size)
                context(param) {
                    renderer.withScissor(scissor) { drawPrimitive(kind, texture, uv) }
                }
            } finally {
                graphics.dispose()
            }
            assertRegion(image, reference, kind.name)
        }
    }

    @Test
    fun `widgets and native item icons obey the same clip`() {
        val scissor = Rect(20.px, 18.px, 76.px, 74.px)
        val reference = device(AffineTransform()).quad(20f, 18f, 76f, 74f)
        val plate = Rect(8.px, 6.px, 88.px, 86.px)

        for (kind in WidgetContent.values()) {
            val image = surface()
            val graphics = image.createGraphics()
            try {
                val param = WindowRenderParam(graphics, size, size)
                context(param, DslScaleContext(1.0)) {
                    renderer.withScissor(scissor) { drawWidget(kind, plate) }
                }
            } finally {
                graphics.dispose()
            }
            assertRegion(image, reference, kind.name)
        }

        // A real icon goes through drawImage, a native AWT path rather than a quad.
        val gameDir = Files.createTempDirectory("kotlinmcuiwindow-clip-icons")
        directories.add(gameDir)
        val versionDir = gameDir.resolve("versions").resolve("test")
        Files.createDirectories(versionDir)
        val jar = versionDir.resolve("test.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("assets/test/textures/item/icon.png"))
            val icon = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 4) for (x in 0 until 4) icon.setRGB(x, y, 0xFFFF0000.toInt())
            ImageIO.write(icon, "png", zip)
            zip.closeEntry()
        }
        WindowItems.use(gameDir, "test")
        WindowItems.get("test:icon")
        assertNotNull(WindowItems.get("test:icon"), "the icon test needs the native drawImage path")

        val iconImage = surface()
        val iconGraphics = iconImage.createGraphics()
        try {
            val param = WindowRenderParam(iconGraphics, size, size)
            context(param, DslScaleContext(1.0)) {
                renderer.withScissor(scissor) {
                    renderer.renderItem(plate, "test:icon", 1, null, false)
                }
            }
        } finally {
            iconGraphics.dispose()
        }
        assertRegion(iconImage, reference, "native icon")
        assertEquals(0xFFFF0000.toInt(), iconImage.getRGB(48, 48), "the icon is sampled inside the clip")
    }

    @Test
    fun `clipping preserves transparent source-over and draw order`() {
        val scissor = Rect(24.px, 20.px, 72.px, 68.px)
        val texture = writeTexture("translucent")
        fun render(image: BufferedImage, clipped: Boolean) {
            val graphics = image.createGraphics()
            try {
                val param = WindowRenderParam(graphics, size, size)
                context(param) {
                    if (clipped) {
                        renderer.withScissor(scissor) {
                            renderer.renderQuad(full(16), Color(255, 0, 0, 180), Color(0, 0, 255, 120),
                                Color(0, 255, 0, 100), Color(255, 255, 0, 200))
                            renderer.renderQuad(Rect(30.px, 30.px, 80.px, 80.px),
                                Color(0, 255, 0, 128), Color(0, 255, 0, 128),
                                Color(0, 255, 0, 128), Color(0, 255, 0, 128))
                        }
                    } else {
                        renderer.renderQuad(full(16), Color(255, 0, 0, 180), Color(0, 0, 255, 120),
                            Color(0, 255, 0, 100), Color(255, 255, 0, 200))
                        renderer.renderQuad(Rect(30.px, 30.px, 80.px, 80.px),
                            Color(0, 255, 0, 128), Color(0, 255, 0, 128),
                            Color(0, 255, 0, 128), Color(0, 255, 0, 128))
                    }
                }
            } finally {
                graphics.dispose()
            }
        }

        val fullImage = surface()
        val clippedImage = surface()
        render(fullImage, clipped = false)
        render(clippedImage, clipped = true)
        val reference = device(AffineTransform()).quad(24f, 20f, 72f, 68f)
        for (y in 0 until size) for (x in 0 until size) {
            val clearance = reference.clearance(x + 0.5, y + 0.5)
            if (clearance <= -2.0) {
                assertEquals(0, clippedImage.getRGB(x, y) ushr 24, "nothing leaks outside at ($x, $y)")
            } else if (clearance >= 2.0) {
                assertEquals(fullImage.getRGB(x, y), clippedImage.getRGB(x, y),
                    "clipping changes coverage only, not source-over at ($x, $y)")
            }
        }
        assertTrue(texture.source != null, "the translucent texture holder remains valid")
    }
}
