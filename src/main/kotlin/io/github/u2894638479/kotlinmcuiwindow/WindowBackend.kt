package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslBackend
import io.github.u2894638479.kotlinmcui.backend.DslBackendInput
import io.github.u2894638479.kotlinmcui.backend.DslBackendMetadata
import io.github.u2894638479.kotlinmcui.backend.DslBackendRenderer
import io.github.u2894638479.kotlinmcui.backend.DslBackendScreenHolder
import io.github.u2894638479.kotlinmcui.backend.DslBackendUtils
import io.github.u2894638479.kotlinmcui.container.TopComponent
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.math.px
import java.awt.Graphics2D

/**
 * The window backend: a Swing window and a Java2D software renderer, with no Minecraft in it.
 *
 * It is assembled from the same four pieces any backend is - a renderer, metadata, utilities and
 * input - and adds only the frame loop that drives [TopComponent]. The type parameters are this
 * backend's own: [WindowRenderParam] is the `Graphics2D` of the frame being painted and
 * [WindowScreen] is the window that owns it, so nothing here has to be parameterised over a game's
 * screen class.
 *
 * It is a singleton because the framework publishes exactly one backend per process; a second window
 * is another [WindowScreen], not another backend.
 */
object WindowBackend : DslBackend<WindowRenderParam, WindowScreen>,
    DslBackendRenderer<WindowRenderParam> by WindowRenderer,
    DslBackendMetadata by WindowMetadata,
    DslBackendUtils by WindowUtils,
    DslBackendInput by WindowInput
{
    /** The screen whose window was shown last, which is the one a screenshot photographs. */
    @Volatile
    var shownScreen: WindowScreen? = null
        private set

    private val screens = ArrayList<WindowScreen>()

    override fun create(title: String, dslFunction: DslFunction): DslBackendScreenHolder<WindowScreen> =
        create(title, dslFunction, DEFAULT_WIDTH, DEFAULT_HEIGHT)

    /** [create] with an explicit window size, for the standalone run's placeholder window. */
    internal fun create(
        title: String,
        dslFunction: DslFunction,
        width: Int,
        height: Int,
    ): DslBackendScreenHolder<WindowScreen> = object : DslBackendScreenHolder<WindowScreen> {
        override val screen = WindowScreen(this@WindowBackend, title, width, height, dslFunction)
        override fun show() = screen.show()
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
        // The root rect is the whole surface: this backend has no GUI scale, so the framework's
        // pixels are the window's pixels.
        TopComponent.rect.apply {
            left = 0.px
            top = 0.px
            right = width.px
            bottom = height.px
        }
        context(this, param, WindowInput.mouse) { TopComponent.render() }
        context(param) { flush() }
    }

    internal fun register(screen: WindowScreen) {
        screens.add(screen)
        shownScreen = screen
    }

    internal fun unregister(screen: WindowScreen) {
        screens.remove(screen)
        if (shownScreen === screen) shownScreen = screens.lastOrNull()
    }

    internal fun closeAll() {
        screens.toList().forEach { it.close() }
    }

    private const val DEFAULT_WIDTH = 960
    private const val DEFAULT_HEIGHT = 540
}
