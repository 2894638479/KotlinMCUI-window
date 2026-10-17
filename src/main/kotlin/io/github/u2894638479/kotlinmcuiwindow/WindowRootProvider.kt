package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslBackendInput
import io.github.u2894638479.kotlinmcui.backend.DslBackendRenderer
import io.github.u2894638479.kotlinmcui.backend.DslBackendScreenHolder
import io.github.u2894638479.kotlinmcui.backend.DslRootProvider
import io.github.u2894638479.kotlinmcui.backend.DslRootScreenFactory
import io.github.u2894638479.kotlinmcui.backend.DslRootUtils
import io.github.u2894638479.kotlinmcui.container.ViewerLifetime
import io.github.u2894638479.kotlinmcui.container.DslRoot
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.entry.DslEntryPage
import io.github.u2894638479.kotlinmcui.math.px

/**
 * The per-output half of the window backend: one Swing window and a Java2D software renderer.
 *
 * A provider is registered once and may be shared by several roots, so everything here is "one answer,
 * many roots": the renderer, the input source, the screen factory and the per-output helpers. What
 * belongs to one root - above all the surface the scale is derived from - is written onto the root by
 * [frame], which is the one place that knows the output size.
 *
 * The window itself is [WindowFrame]: it owns the page stack and the 16ms timer, and it paints through
 * this provider. Pages are a stack inside that one window rather than a window each, so the
 * framework's `showScreen` becomes a push and a page ending becomes a pop.
 */
internal class WindowRootProvider(
    private val platform: WindowPlatform,
    private val utils: WindowUtils,
) : DslRootProvider<WindowRenderParam, WindowScreen> {
    private val windowRenderer = WindowRenderer()

    private val windowInput = WindowInput()

    /** The window this provider paints into; one per provider, and one provider is one window here. */
    internal val window = WindowFrame(this, windowInput)

    private val factory = DslRootScreenFactory<WindowScreen> { title, dslFunction ->
        val page = WindowScreen(platform, this, window, DEFAULT_WIDTH, DEFAULT_HEIGHT, title, dslFunction)
        object : DslBackendScreenHolder<WindowScreen> {
            override val screen = page
            // Showing a page is entering it: the page becomes the top of the window's stack, and the
            // page below it stays where it is, unmounted. This is the whole of "show a screen" here.
            override fun show() = window.push(page)
            override val dslScreen get() = page.dslScreen
        }
    }

    init {
        // The window needs its provider to paint into it, and the utilities need the window to
        // photograph and close, so the frame is attached where both exist.
        utils.frame = window
    }

    override val renderer: DslBackendRenderer<WindowRenderParam>? get() = windowRenderer

    override val input: DslBackendInput get() = windowInput

    override val screenFactory: DslRootScreenFactory<WindowScreen>? get() = factory

    override val rootUtils: DslRootUtils get() = utils

    /**
     * Open the window rendering [root], with the framework's entry list as the bottom of the stack.
     *
     * The caller decides which root this is, because the two cases differ: when this provider was the
     * first one registered, that is the process owner root; when another backend got there first, this
     * window renders a root of its own instead. The window is still the same window either way.
     */
    internal fun open(root: DslRoot) {
        window.open(root, factory.create(ROOT_TITLE) { DslEntryPage() }.screen)
    }

    /**
     * One frame of [root]: give it the surface's size, render its tree through this renderer, commit.
     *
     * The rect is written before the render pass, which copies it onto every mounted screen. This runs
     * from the window's `paintComponent`, so it only ever runs on the event dispatch thread - which is
     * what the framework's per-context thread check requires.
     */
    override fun frame(root: DslRoot, renderParam: WindowRenderParam) {
        // Pages lay out in the surface's pixels and their contents are scaled inside that by the scale
        // the renderer derives from this same size, so the root rect is simply the surface.
        windowRenderer.surfaceWidth = renderParam.width
        windowRenderer.surfaceHeight = renderParam.height
        root.rect.apply {
            left = 0.px
            top = 0.px
            right = renderParam.width.px
            bottom = renderParam.height.px
        }
        context(windowRenderer, renderParam, windowInput.mouse) { root.component.render() }
        context(renderParam) { windowRenderer.flush() }
    }

    private companion object {
        const val ROOT_TITLE = "KotlinMCUI"

        const val DEFAULT_WIDTH = 960
        const val DEFAULT_HEIGHT = 540
    }
}
