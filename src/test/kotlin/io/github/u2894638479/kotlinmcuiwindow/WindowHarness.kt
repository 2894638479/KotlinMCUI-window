package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslRootScreenFactory
import io.github.u2894638479.kotlinmcui.container.DslRoot
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage

/**
 * One window's own pieces, built the way the bootstrap builds them, without opening a desktop window.
 *
 * The tests that care about output coordinates drive [paintFrame] - the same entry the panel calls -
 * rather than writing a root's viewport by hand, so a change to the boundary mapping is visible here
 * and every other frame path stays on the one implementation.
 */
internal class WindowHarness {
    val utils = WindowUtils()
    val input = WindowInput()
    val renderer = WindowRenderer()
    val window = WindowFrame(renderer, input)
    val root: DslRoot

    init {
        val screenFactory = DslRootScreenFactory<WindowScreen> { title, dslFunction ->
            window.create(utils, title, dslFunction)
        }
        root = DslRoot(input, renderer, screenFactory, utils)
        window.attach(root)
        input.root = root
    }

    /** Mount [dslFunction] as this window's page without showing a frame. */
    fun mount(dslFunction: DslFunction): WindowScreen {
        val screen = window.create(utils, "probe", dslFunction).screen
        screen.mount()
        return screen
    }

    /** One frame at the panel's logical [width] x [height], through the production entry. */
    fun paint(graphics: Graphics2D, width: Int, height: Int): Boolean =
        window.paintFrame(graphics, width, height)

    /**
     * Paint one frame into a real target with the given platform transform, and hand back the image.
     *
     * The target's size and the logical range are named separately, so a case can say "a 480 x 384
     * backing shown as 320 x 240" the way a HiDPI display does.
     */
    fun paintInto(
        targetWidth: Int,
        targetHeight: Int,
        logicalWidth: Int,
        logicalHeight: Int,
        scaleX: Double,
        scaleY: Double,
    ): BufferedImage {
        val image = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        val incoming = AffineTransform(scaleX, 0.0, 0.0, scaleY, 0.0, 0.0)
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.transform(incoming)
            check(paint(graphics, logicalWidth, logicalHeight)) { "the open root must paint its frame" }
            check(incoming == graphics.transform) { "painting must leave the caller's graphics state alone" }
        } finally {
            graphics.dispose()
        }
        return image
    }
}
