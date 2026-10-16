package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.context.DslScaleContext
import io.github.u2894638479.kotlinmcui.math.px
import io.github.u2894638479.kotlinmcui.math.rect.Rect
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A button has two flags, so four combinations, and the page that exercises this backend draws all
 * four side by side - a user has to be able to tell them apart. The plate, the bottom edge and the
 * corner are read back, because any of them may be what carries the difference.
 */
class WindowRendererButtonTest {

    private val width = 40
    private val height = 20

    private val renderer = WindowRenderer()

    private fun render(highlighted: Boolean, active: Boolean): List<Int> {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        context(WindowRenderParam(graphics), DslScaleContext(1.0)) {
            renderer.renderButton(Rect(0.px, 0.px, width.px, height.px), highlighted, active)
        }
        graphics.dispose()
        return listOf(image.getRGB(width / 2, 4), image.getRGB(width / 2, height - 2), image.getRGB(0, 0))
    }

    @Test
    fun `the four combinations are four different pictures`() {
        val combinations = listOf(false to false, false to true, true to false, true to true)
        val rendered = combinations.associateWith { (highlighted, active) -> render(highlighted, active) }

        for ((one, first) in rendered) {
            for ((other, second) in rendered) {
                if (one == other) continue
                assertTrue(first != second, "$one and $other come out the same: $first")
            }
        }
    }

    @Test
    fun `a disabled button shows whether the cursor is on it`() {
        assertTrue(
            render(highlighted = false, active = false) != render(highlighted = true, active = false),
            "a disabled button looks the same highlighted or not",
        )
    }
}
