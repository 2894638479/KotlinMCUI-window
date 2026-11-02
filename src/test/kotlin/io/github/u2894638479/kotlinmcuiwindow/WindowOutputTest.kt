package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslRootRenderer
import io.github.u2894638479.kotlinmcui.backend.fillRect
import io.github.u2894638479.kotlinmcui.component.DslComponent
import io.github.u2894638479.kotlinmcui.context.DslScaleContext
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.dsl.ui.Spacer
import io.github.u2894638479.kotlinmcui.math.Color
import io.github.u2894638479.kotlinmcui.math.Position
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import io.github.u2894638479.kotlinmcui.modifier.Modifier
import java.awt.Dimension
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The window boundary: logical panel pixels on the platform side, the target's own pixels inside.
 *
 * Every case drives [WindowFrame.paintFrame] - the entry the panel calls - and reads the pixels back
 * from a real target. The probe page draws in the framework's coordinates; a second application of
 * the platform's scale, or a clip left in logical space, moves or cuts those pixels visibly.
 */
class WindowOutputTest {
    /** A page that paints fixed output-pixel geometry, so no layout result can hide the mapping. */
    private fun probe(width: Int, height: Int, plate: Color, marker: Color): DslFunction = {
        Spacer(Modifier, "output-probe").change { base ->
            object : DslComponent by base {
                context(backend: DslRootRenderer<RP>, renderParam: RP, mouse: Position)
                override fun <RP> render() {
                    // A clip and a fill in the same output coordinates: the fill must stop exactly at
                    // the scissor's edge, not at that edge scaled a second time.
                    backend.withScissor(Rect(0.px, 0.px, (width / 2).px, (height / 2).px)) {
                        backend.fillRect(Rect(0.px, 0.px, width.px, height.px), plate)
                    }
                    // The last two target pixels. A scale applied twice puts them outside the target.
                    backend.fillRect(
                        Rect((width - 2).px, (height - 2).px, width.px, height.px),
                        marker,
                    )
                }
            }
        }
    }

    /** A page that fills the whole target, for cases whose clip comes from the platform. */
    private fun fill(width: Int, height: Int, color: Color): DslFunction = {
        Spacer(Modifier, "output-fill").change { base ->
            object : DslComponent by base {
                context(backend: DslRootRenderer<RP>, renderParam: RP, mouse: Position)
                override fun <RP> render() {
                    backend.fillRect(Rect(0.px, 0.px, width.px, height.px), color)
                }
            }
        }
    }

    /** A page that paints one sub-pixel-wide output column, for pointer and clip alignment. */
    private fun column(left: Double, right: Double, bottom: Double, color: Color): DslFunction = {
        Spacer(Modifier, "output-column").change { base ->
            object : DslComponent by base {
                context(backend: DslRootRenderer<RP>, renderParam: RP, mouse: Position)
                override fun <RP> render() {
                    backend.fillRect(Rect(left.px, 0.px, right.px, bottom.px), color)
                }
            }
        }
    }

    /**
     * One frame at [logicalWidth] x [logicalHeight] with the platform's device transform in place,
     * into a target that really has [targetWidth] x [targetHeight] pixels.
     */
    private fun paint(
        harness: WindowHarness,
        targetWidth: Int,
        targetHeight: Int,
        logicalWidth: Int,
        logicalHeight: Int,
        scaleX: Double,
        scaleY: Double,
    ): BufferedImage = harness.paintInto(targetWidth, targetHeight, logicalWidth, logicalHeight, scaleX, scaleY)

    @Test
    fun `a root has no scale until the first frame writes one`() {
        val harness = WindowHarness()
        harness.mount({ })

        // A mounted page is allowed; building it is not. There is no implicit unit scale to build with.
        assertFailsWith<IllegalStateException>("reading scale before the first frame must fail") {
            harness.root.scale
        }

        paint(harness, TARGET_WIDTH, TARGET_HEIGHT, LOGICAL_WIDTH, LOGICAL_HEIGHT, 1.5, 1.6)
        assertEquals(guiScaleFor(TARGET_WIDTH, TARGET_HEIGHT), harness.root.scale, 0.0)
    }

    @Test
    fun `a fractional non-uniform target becomes output pixels once`() {
        val harness = WindowHarness()
        harness.mount(probe(TARGET_WIDTH, TARGET_HEIGHT, RED, GREEN))

        val image = paint(harness, TARGET_WIDTH, TARGET_HEIGHT, LOGICAL_WIDTH, LOGICAL_HEIGHT, 1.5, 1.6)

        // The viewport and the scale are the target's pixels, not the panel's 320 x 240.
        assertEquals(TARGET_WIDTH.toDouble(), harness.root.rect.right.raw, 0.0)
        assertEquals(TARGET_HEIGHT.toDouble(), harness.root.rect.bottom.raw, 0.0)
        assertEquals(guiScaleFor(TARGET_WIDTH, TARGET_HEIGHT), harness.root.scale, 0.0)

        // Drawing and clipping agree at 1.5 x 1.6, not at 1.5 x 1.6 applied twice.
        assertEquals(RED_ARGB, image.getRGB(TARGET_WIDTH / 4, TARGET_HEIGHT / 4))
        assertEquals(0, image.getRGB(3 * TARGET_WIDTH / 4, 3 * TARGET_HEIGHT / 4) ushr 24,
            "the scissor must stop in output pixels")
        assertEquals(GREEN_ARGB, image.getRGB(TARGET_WIDTH - 1, TARGET_HEIGHT - 1),
            "the last target pixel is where output (width - 1, height - 1) says it is")

        // Input is converted once at the boundary with the actual per-axis target ratios.
        harness.input.mouseMoved(7, 11)
        assertEquals(7.0 * TARGET_WIDTH / LOGICAL_WIDTH, harness.input.mouse.x.raw, 1e-9)
        assertEquals(11.0 * TARGET_HEIGHT / LOGICAL_HEIGHT, harness.input.mouse.y.raw, 1e-9)
        assertTrue(harness.input.mouse.x.raw != 7.0, "the cursor must be in output pixels")
    }

    @Test
    fun `a resize updates the viewport scale and cursor before the first resized frame`() {
        val harness = WindowHarness()
        var builtScale = Double.NaN
        harness.mount({ builtScale = currentScale() })
        paint(harness, 480, 384, LOGICAL_WIDTH, LOGICAL_HEIGHT, 1.5, 1.6)
        assertEquals(480.0, harness.root.rect.right.raw, 0.0)
        assertEquals(guiScaleFor(480, 384), harness.root.scale, 0.0)
        assertEquals(1.5, builtScale, 0.0, "the build must see the scale written for its frame")

        paint(harness, 1280, 960, 640, 480, 2.0, 2.0)

        assertEquals(1280.0, harness.root.rect.right.raw, 0.0)
        assertEquals(960.0, harness.root.rect.bottom.raw, 0.0)
        assertEquals(4.0, harness.root.scale, 0.0)
        assertEquals(4.0, builtScale, 0.0, "the first resized build must see the new scale")
        harness.input.mouseMoved(7, 11)
        assertEquals(14.0, harness.input.mouse.x.raw, 0.0)
        assertEquals(22.0, harness.input.mouse.y.raw, 0.0)
    }

    @Test
    fun `a platform repaint clip keeps its output region across the boundary`() {
        val harness = WindowHarness()
        harness.mount(fill(TARGET_WIDTH, TARGET_HEIGHT, RED))

        val image = BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            // The platform hands over a dirty region in logical pixels under its DPI transform; the
            // boundary must turn it into the same physical region, not shrink it by the scale again.
            graphics.transform(AffineTransform(1.5, 0.0, 0.0, 1.6, 0.0, 0.0))
            graphics.clip = Rectangle2D.Double(0.0, 0.0, 160.0, 120.0)
            assertTrue(harness.paint(graphics, LOGICAL_WIDTH, LOGICAL_HEIGHT))
        } finally {
            graphics.dispose()
        }

        assertEquals(RED_ARGB, image.getRGB(120, 96), "inside the platform clip")
        assertEquals(0, image.getRGB(300, 250) ushr 24, "outside the platform clip")
        assertEquals(0, image.getRGB(TARGET_WIDTH - 1, TARGET_HEIGHT - 1) ushr 24,
            "a platform clip covers the whole frame, not just the framework's own scissors")
    }

    @Test
    fun `a zero-size target is empty output rather than a zero scale`() {
        val harness = WindowHarness()
        harness.mount({ })
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.transform(AffineTransform.getScaleInstance(1.5, 1.6))
            assertTrue(harness.paint(graphics, 0, 0), "a minimised target is still a frame")
        } finally {
            graphics.dispose()
        }

        assertEquals(0.0, harness.root.rect.right.raw, 0.0)
        assertEquals(0.0, harness.root.rect.bottom.raw, 0.0)
        assertEquals(1.0, harness.root.scale, 0.0)
    }

    @Test
    fun `two windows keep their own viewport scale and cursor mapping`() {
        val small = WindowHarness()
        small.mount(probe(480, 384, RED, GREEN))
        val large = WindowHarness()
        large.mount(probe(640, 480, BLUE, MAGENTA))

        val smallImage = paint(small, 480, 384, LOGICAL_WIDTH, LOGICAL_HEIGHT, 1.5, 1.6)
        val largeImage = paint(large, 640, 480, LOGICAL_WIDTH, LOGICAL_HEIGHT, 2.0, 2.0)

        assertNotSame(small.root, large.root)
        assertEquals(1.5, small.root.scale, 0.0)
        assertEquals(2.0, large.root.scale, 0.0)
        assertEquals(480.0, small.root.rect.right.raw, 0.0)
        assertEquals(640.0, large.root.rect.right.raw, 0.0)

        assertEquals(RED_ARGB, smallImage.getRGB(120, 96))
        assertEquals(GREEN_ARGB, smallImage.getRGB(479, 383))
        assertEquals(BLUE_ARGB, largeImage.getRGB(120, 96))
        assertEquals(MAGENTA_ARGB, largeImage.getRGB(639, 479))

        small.input.mouseMoved(7, 11)
        large.input.mouseMoved(7, 11)
        assertEquals(10.5, small.input.mouse.x.raw, 1e-9)
        assertEquals(17.6, small.input.mouse.y.raw, 1e-9)
        assertEquals(14.0, large.input.mouse.x.raw, 1e-9)
        assertEquals(22.0, large.input.mouse.y.raw, 1e-9)

        // Repainting one window is not resizing the other.
        paint(small, 320, 240, 160, 120, 2.0, 2.0)
        assertEquals(640.0, large.root.rect.right.raw, 0.0)
        assertEquals(2.0, large.root.scale, 0.0)
    }

    @Test
    fun `fractional output keeps the pointer and clip on the painted pixel`() {
        val harness = WindowHarness()
        harness.mount(column(100.0, 100.25, 20.0, GREEN))

        // 101 logical pixels at 1.25 x 1.5 are 126.25 x 91.5 target pixels, so the bitmap extent is
        // 127 x 92. The panel origin is non-zero, as it is for a window on a scaled desktop.
        val logicalWidth = 101
        val logicalHeight = 61
        val scaleX = 1.25
        val scaleY = 1.5
        val originX = 17
        val originY = 11
        val outputWidth = 127
        val outputHeight = 92

        fun render(width: Int, targetWidth: Int, clip: Rectangle2D? = null): BufferedImage {
            val image = BufferedImage(targetWidth, originY + outputHeight + 2, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            val incoming = AffineTransform(scaleX, 0.0, 0.0, scaleY, originX.toDouble(), originY.toDouble())
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.transform(incoming)
                if (clip != null) graphics.clip = clip
                assertTrue(harness.paint(graphics, width, logicalHeight))
                assertEquals(incoming, graphics.transform, "painting must restore the caller's transform")
            } finally {
                graphics.dispose()
            }
            return image
        }

        val image = render(logicalWidth, originX + outputWidth + 2)
        assertEquals(outputWidth.toDouble(), harness.root.rect.right.raw, 0.0)
        assertEquals(outputHeight.toDouble(), harness.root.rect.bottom.raw, 0.0)
        assertEquals(guiScaleFor(outputWidth, outputHeight), harness.root.scale, 0.0)

        // 80 logical pixels are exactly 100 output pixels. The rounded 127 / 101 ratio says 100.594,
        // which puts the pointer past the thin 100..100.25 column that was actually painted.
        harness.input.mouseMoved(80, 10)
        assertEquals(100.0, harness.input.mouse.x.raw, 1e-9)
        assertEquals(15.0, harness.input.mouse.y.raw, 1e-9)
        val markerX = originX + 100
        val markerY = originY + 15
        assertTrue(
            image.getRGB(markerX, markerY) ushr 24 != 0,
            "the pointer must be over the output-pixel column that was painted",
        )
        assertEquals(
            0, image.getRGB(markerX + 1, markerY) ushr 24,
            "the quarter-pixel column ends before the next output pixel",
        )

        // A capture keeps the fractional scale: at logical 102 the next bitmap is 128 pixels, not
        // the 129 that re-deriving the ratio from the rounded 127 would ask for.
        assertEquals(Dimension(127, 92), harness.window.outputSize(101, 61))
        assertEquals(Dimension(128, 92), harness.window.outputSize(102, 61))

        // Resizing while the cursor stands still keeps it on the same output pixel.
        val resized = render(102, originX + 128 + 2)
        assertEquals(128.0, harness.root.rect.right.raw, 0.0)
        assertEquals(100.0, harness.input.mouse.x.raw, 1e-9)
        assertEquals(15.0, harness.input.mouse.y.raw, 1e-9)
        assertTrue(resized.getRGB(markerX, markerY) ushr 24 != 0)

        // The platform clip uses the same scale: logical 80.2 ends at output 100.25, so it removes
        // the quarter-pixel column instead of extending to the rounded bitmap's ratio.
        val clipped = render(
            logicalWidth,
            originX + outputWidth + 2,
            Rectangle2D.Double(0.0, 0.0, 80.2, logicalHeight.toDouble()),
        )
        assertEquals(
            0, clipped.getRGB(markerX, markerY) ushr 24,
            "the platform clip must land on the actual output scale",
        )
    }

    @Test
    fun `a capture uses the target's real bitmap size`() {
        val harness = WindowHarness()
        harness.mount({ })
        paint(harness, 480, 384, LOGICAL_WIDTH, LOGICAL_HEIGHT, 1.5, 1.6)

        assertEquals(Dimension(480, 384), harness.window.outputSize(LOGICAL_WIDTH, LOGICAL_HEIGHT))
        // A resized panel keeps the same measured ratio and still asks for the backing-sized bitmap.
        assertEquals(Dimension(960, 768), harness.window.outputSize(640, 480))
    }

    private companion object {
        const val LOGICAL_WIDTH = 320
        const val LOGICAL_HEIGHT = 240
        const val TARGET_WIDTH = 480
        const val TARGET_HEIGHT = 384

        val RED = Color(255, 0, 0)
        val GREEN = Color(0, 255, 0)
        val BLUE = Color(0, 0, 255)
        val MAGENTA = Color(255, 0, 255)

        const val RED_ARGB = 0xFFFF0000.toInt()
        const val GREEN_ARGB = 0xFF00FF00.toInt()
        const val BLUE_ARGB = 0xFF0000FF.toInt()
        const val MAGENTA_ARGB = 0xFFFF00FF.toInt()
    }
}

/** Reads the DSL scale a build frame sees, through the same context the DSL itself uses. */
context(ctx: DslScaleContext)
private fun currentScale(): Double = ctx.scale
