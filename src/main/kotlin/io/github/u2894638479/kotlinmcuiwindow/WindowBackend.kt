package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslBackend
import io.github.u2894638479.kotlinmcui.backend.DslBackendInput
import io.github.u2894638479.kotlinmcui.backend.DslBackendMetadata
import io.github.u2894638479.kotlinmcui.backend.DslBackendRenderer
import io.github.u2894638479.kotlinmcui.backend.DslBackendScreenHolder
import io.github.u2894638479.kotlinmcui.backend.DslBackendUtils
import io.github.u2894638479.kotlinmcui.container.TopComponent
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.entry.DslEntryPage
import io.github.u2894638479.kotlinmcui.math.px
import java.awt.Graphics2D

/**
 * The window backend: one Swing window and a Java2D software renderer, with no Minecraft in it.
 *
 * It is assembled from the same four pieces any backend is - a renderer, metadata, utilities and
 * input - and adds only the frame loop that drives [TopComponent]. The type parameters are this
 * backend's own: [WindowRenderParam] is the `Graphics2D` of the frame being painted and
 * [WindowScreen] is one page of the window, so nothing here has to be parameterised over a game's
 * screen class.
 *
 * There is exactly one window, and pages are a stack inside it rather than a window each: the
 * framework's own `showScreen` becomes [WindowFrame.push] and a page ending becomes
 * [WindowFrame.pop], which puts the page below it back. The stack lives in [WindowFrame]; this
 * class is the framework-facing half of it.
 *
 * Everything that belongs to one window belongs to one instance - the surface the scale is derived
 * from above all - so a process with two windows is two of these rather than one serving two. The
 * entries and the standalone [main] each build the single one their process has. Only the renderer
 * is per-instance so far; the frame, the input and the utilities still live in one place and move
 * the same way next.
 */
class WindowBackend internal constructor(
    private val renderer: WindowRenderer = WindowRenderer(),
    private val input: WindowInput = WindowInput(),
    private val utils: WindowUtils = WindowUtils(),
) :
    DslBackend<WindowRenderParam, WindowScreen>,
    DslBackendRenderer<WindowRenderParam> by renderer,
    DslBackendMetadata by WindowMetadata,
    DslBackendUtils by utils,
    DslBackendInput by input
{
    /** The window this backend paints into, and the page stack inside it. */
    private val frame = WindowFrame(this, input)

    init {
        // The window needs its backend to paint into it, and the utilities need the window to
        // photograph and close, so the frame is attached here, where both exist.
        utils.frame = frame
    }

    /**
     * Open the window with the framework's entry list as the page at the bottom of the stack.
     *
     * Called once by the loader entry points, after [io.github.u2894638479.kotlinmcui.backend.DslRuntime.platformReady]
     * has published this backend - that is what makes the framework's own `dslBackend` usable inside
     * the page, and the entry list is built on it: it lists what `DslEntryLoader` discovered and
     * pushes a page of its own for every entry that is clicked.
     */
    internal fun open() {
        frame.open(create(ROOT_TITLE) { DslEntryPage() }.screen)
    }

    override fun create(title: String, dslFunction: DslFunction): DslBackendScreenHolder<WindowScreen> =
        create(title, dslFunction, DEFAULT_WIDTH, DEFAULT_HEIGHT)

    /** [create] with an explicit window size, which only the first page of the window gets to use. */
    internal fun create(
        title: String,
        dslFunction: DslFunction,
        width: Int,
        height: Int,
    ): DslBackendScreenHolder<WindowScreen> = object : DslBackendScreenHolder<WindowScreen> {
        override val screen = WindowScreen(this@WindowBackend, frame, title, width, height, dslFunction)
        // Showing a page is entering it: the page becomes the top of the window's stack, and the
        // page below it stays where it is, unmounted. This is the whole of "show a screen" here.
        override fun show() = frame.push(screen)
        override val dslScreen get() = screen.dslScreen
    }

    /**
     * One frame: give the root the surface's size, render the tree through it and commit.
     *
     * Called from the window's `paintComponent`, so it only ever runs on the event dispatch thread -
     * which is what the framework's per-context thread check requires, and why there is no
     * synchronisation around the tree here.
     */
    internal fun paint(graphics: Graphics2D, width: Int, height: Int) {
        val param = WindowRenderParam(graphics)
        // Pages lay out in the surface's pixels and their contents are scaled inside that by the
        // scale the renderer derives from this same size, so the root rect is simply the surface.
        renderer.surfaceWidth = width
        renderer.surfaceHeight = height
        TopComponent.rect.apply {
            left = 0.px
            top = 0.px
            right = width.px
            bottom = height.px
        }
        context(this, param, input.mouse) { TopComponent.render() }
        context(param) { flush() }
    }

    private companion object {
        const val ROOT_TITLE = "KotlinMCUI"

        const val DEFAULT_WIDTH = 960
        const val DEFAULT_HEIGHT = 540
    }
}
