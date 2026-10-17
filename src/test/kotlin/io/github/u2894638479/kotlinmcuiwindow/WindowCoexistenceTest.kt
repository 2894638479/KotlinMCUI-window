package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslBackendMetadata
import io.github.u2894638479.kotlinmcui.backend.DslPlatform
import io.github.u2894638479.kotlinmcui.backend.DslPlatformUtils
import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.backend.Environment
import io.github.u2894638479.kotlinmcui.backend.dslPlatform
import io.github.u2894638479.kotlinmcui.container.ViewerLifetime
import io.github.u2894638479.kotlinmcui.image.ImageHolder
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
 * refused. That is the multi-backend case the split exists for, and the rule it has to obey is
 * "platform belongs to the process, rendering belongs to the root":
 *
 *  - the platform slot stays with the first publisher, so `dslPlatform` is not this backend's;
 *  - this backend still registers its provider and still renders a root of its own, using its own
 *    [WindowPlatform] object for its screens;
 *  - a root that is not the process owner is created through `createRoot` and renders just as well.
 *
 * This is the only class that installs and publishes, and it does so through a stub platform, so it
 * must run in the same JVM without another case expecting the window's own platform to be published.
 */
class WindowCoexistenceTest {

    /** Publish the stub platform. Idempotent: the first call publishes, later ones are refused. */
    private fun publishForeignPlatform() {
        // `install` is single-shot and its "already installed" signal is internal, so a second call
        // throwing is the expected outcome on every run after the first.
        runCatching { DslRuntime.install { ForeignPlatform.metadata } }
        DslRuntime.platformReady { ForeignPlatform }
        assertSame(ForeignPlatform, dslPlatform, "the process platform must still be the stub's")
    }

    @Test
    fun `another platform owning the process does not stop the window from rendering`() {
        publishForeignPlatform()

        val utils = WindowUtils()
        val platform = WindowPlatform(utils)
        val provider = WindowRootProvider(platform, utils)

        // The entry point's own publish is refused here - that is the situation under test.
        assertFalse(DslRuntime.platformReady { platform })
        assertSame(ForeignPlatform, dslPlatform, "a refused publish must not take the slot")

        // Losing the slot must not stop the window: the provider still registers, and a root still
        // exists. Which root depends on who registered first, exactly as the entries compute it.
        DslRuntime.registerProvider(provider)
        assertTrue(DslRuntime.registeredProviders.any { it === provider }, "the provider must be registered")

        // The same call the entries make, so the case exercises the production choice rather than a
        // parallel one: the first registrant gets the owner root, everyone else a root of their own.
        val root = DslRuntime.rootFor(provider, ViewerLifetime.PROCESS)

        val surface = BufferedImage(SURFACE_WIDTH, SURFACE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = surface.createGraphics()
        try {
            // One frame: the provider writes the root's rect from the surface size, renders the root
            // and flushes. A refused platform must not make any of that throw.
            provider.frame(root, WindowRenderParam(graphics, SURFACE_WIDTH, SURFACE_HEIGHT))
        } finally {
            graphics.dispose()
        }

        assertEquals(SURFACE_WIDTH.toDouble(), root.rect.right.raw, 0.0)
        assertEquals(SURFACE_HEIGHT.toDouble(), root.rect.bottom.raw, 0.0)
        assertSame(ForeignPlatform, dslPlatform, "rendering must not take the platform slot")
    }

    @Test
    fun `a root of its own renders when it is not the process owner`() {
        publishForeignPlatform()

        val utils = WindowUtils()
        val platform = WindowPlatform(utils)
        val provider = WindowRootProvider(platform, utils)

        val root = DslRuntime.createRoot(provider, ViewerLifetime.PROCESS)
        // `isRegistered` is a member extension of the runtime object, so it needs its receiver here.
        with(DslRuntime) { assertTrue(root.isRegistered, "a published platform must let this backend create a root") }
        assertFalse(root.isOwner, "createRoot never creates the owner root")

        val surface = BufferedImage(SURFACE_WIDTH, SURFACE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = surface.createGraphics()
        try {
            provider.frame(root, WindowRenderParam(graphics, SURFACE_WIDTH, SURFACE_HEIGHT))
        } finally {
            graphics.dispose()
        }

        assertEquals(SURFACE_WIDTH.toDouble(), root.rect.right.raw, 0.0)
        assertEquals(SURFACE_HEIGHT.toDouble(), root.rect.bottom.raw, 0.0)
        assertSame(ForeignPlatform, dslPlatform)
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
