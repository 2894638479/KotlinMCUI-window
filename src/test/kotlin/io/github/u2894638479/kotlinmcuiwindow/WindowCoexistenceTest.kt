package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslBackendMetadata
import io.github.u2894638479.kotlinmcui.backend.DslPlatform
import io.github.u2894638479.kotlinmcui.backend.DslPlatformUtils
import io.github.u2894638479.kotlinmcui.backend.DslRootScreenFactory
import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.backend.Environment
import io.github.u2894638479.kotlinmcui.backend.dslPlatform
import io.github.u2894638479.kotlinmcui.container.DslRoot
import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.math.px
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Coexistence: another backend published the platform first, so this backend's own publish is
 * refused. The rule under test is "the platform belongs to the process, the root belongs to this
 * backend": the process platform stays the other backend's, and this one still builds a root, still
 * creates pages on that root, and still renders a frame.
 *
 * This is the only class that installs and publishes, and it installs a stub platform, so it must
 * run in a JVM where no other case expects the window's own platform to be published.
 */
class WindowCoexistenceTest {

    /** The pieces one window is made of, built the way the bootstrap builds them. */
    private class Built(
        val platform: WindowPlatform,
        val root: DslRoot,
        val renderer: WindowRenderer,
        val input: WindowInput,
        val window: WindowFrame,
    )

    /**
     * Publish the stub platform. Idempotent: the first call publishes, later ones are refused.
     *
     * `install` is single-shot and its "already installed" signal is internal, so a second call
     * throwing is the expected outcome on every run after the first.
     */
    private fun publishForeignPlatform() {
        runCatching { DslRuntime.install { ForeignPlatform.metadata } }
        DslRuntime.start { ForeignPlatform }
        assertSame(ForeignPlatform, dslPlatform, "the process platform must still be the stub's")
    }

    /** Build one window the way the bootstrap does, and assert that its own publish was refused. */
    private fun buildWindow(): Built {
        val utils = WindowUtils()
        val platform = WindowPlatform(utils)
        assertFalse(DslRuntime.start { platform }, "another platform already owns the process")

        val input = WindowInput()
        val renderer = WindowRenderer()
        val window = WindowFrame(renderer, input)
        val screenFactory = DslRootScreenFactory<WindowScreen> { title, dslFunction ->
            window.create(platform, title, dslFunction)
        }
        val root = DslRoot(input, renderer, screenFactory, utils)
        window.attach(root)
        input.root = root
        return Built(platform, root, renderer, input, window)
    }

    /** Drive one frame the way the window's panel does: size, viewport, render, flush. */
    private fun renderOneFrame(built: Built, width: Int, height: Int) {
        val surface = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = surface.createGraphics()
        try {
            built.renderer.surfaceWidth = width
            built.renderer.surfaceHeight = height
            built.root.rect.apply {
                left = 0.px
                top = 0.px
                right = width.px
                bottom = height.px
            }
            val param = WindowRenderParam(graphics, width, height)
            context(built.renderer, param, built.input.mouse) { built.root.component.render() }
            context(param) { built.renderer.flush() }
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `another platform owning the process does not stop the window from rendering`() {
        publishForeignPlatform()
        val built = buildWindow()

        renderOneFrame(built, SURFACE_WIDTH, SURFACE_HEIGHT)

        assertEquals(SURFACE_WIDTH.toDouble(), built.root.rect.right.raw, 0.0)
        assertEquals(SURFACE_HEIGHT.toDouble(), built.root.rect.bottom.raw, 0.0)
        // Rendering must not have moved the platform slot: it still belongs to the first publisher.
        assertSame(ForeignPlatform, dslPlatform)
    }

    @Test
    fun `a page built for this window belongs to the root this window renders`() {
        publishForeignPlatform()
        val built = buildWindow()

        val holder = built.window.create(built.platform, "a page", dslFunction = { })

        // The page's store is what its components read the tree through, so this says "the page is
        // on my root", not merely "something was called".
        assertSame(built.root, holder.dslScreen.dataStore.root)
        assertSame(ForeignPlatform, dslPlatform)
    }

    /**
     * The closed-root guard in the frame loop, which would otherwise be a claim in a comment only.
     *
     * Removing the guard makes this fail twice over: the loop would paint into the closed root's
     * surface, and the root's teardown gate would throw instead, which the window's panel turns into
     * a "page threw while rendering" line - a false alarm, since the window is simply closing.
     */
    @Test
    fun `a closed root is not painted by the frame loop`() {
        publishForeignPlatform()
        val built = buildWindow()

        val first = BufferedImage(SURFACE_WIDTH, SURFACE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val open = first.createGraphics()
        try {
            assertTrue(
                built.window.paintFrame(open, SURFACE_WIDTH, SURFACE_HEIGHT),
                "an open root must paint its frame",
            )
        } finally {
            open.dispose()
        }

        built.root.close()
        assertTrue(built.root.isClosed)

        val second = BufferedImage(SURFACE_WIDTH, SURFACE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val closed = second.createGraphics()
        try {
            assertFalse(
                built.window.paintFrame(closed, SURFACE_WIDTH, SURFACE_HEIGHT),
                "a closed root must not paint",
            )
        } finally {
            closed.dispose()
        }
        // Nothing reached the surface: every sampled pixel is still fully transparent.
        for (x in 0 until SURFACE_WIDTH step 16) {
            for (y in 0 until SURFACE_HEIGHT step 16) {
                assertEquals(0, second.getRGB(x, y) ushr 24, "a closed frame drew at ($x,$y)")
            }
        }
    }

    private companion object {
        const val SURFACE_WIDTH = 320
        const val SURFACE_HEIGHT = 240
    }
}

/** Metadata of the stub platform; nothing reads it in these cases, but a platform must answer it. */
private object ForeignMetadata : DslBackendMetadata {
    override val configDir = Paths.get("build", "test-config")
    override val gameDir = Paths.get(".")
    override val gameVersion = "foreign"
    override val gameLoader = "test"
    override val environment = Environment.COMMON
}

/** The stub's process-level helpers. Only `quit` matters here, and it must not really exit. */
private object ForeignUtils : DslPlatformUtils {
    override fun translate(key: String, vararg args: Any?): String? = key
    override val isInWorld: Boolean get() = false
    override fun loadLocalImage(file: File): ImageHolder = ImageHolder.empty
    override fun forceLoadLocalImage(file: File): ImageHolder = ImageHolder.empty
    override fun quit(exitCode: Int) {}
}

/**
 * A platform some other backend published first.
 *
 * The dispatcher is [Dispatchers.Unconfined] on purpose: UI state here is confined to whatever
 * thread builds it, so a pool would break the framework's thread checks rather than model a platform.
 */
private object ForeignPlatform : DslPlatform {
    override val metadata: DslBackendMetadata get() = ForeignMetadata
    override val utils: DslPlatformUtils get() = ForeignUtils
    override val dispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined
}
