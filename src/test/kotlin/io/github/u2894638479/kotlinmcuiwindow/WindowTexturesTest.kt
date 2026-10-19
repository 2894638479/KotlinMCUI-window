package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.px
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * How an image source turns into pixels, and what happens when it does not.
 *
 * The naming rule of a resource is the framework's, not this backend's: it is written the way the
 * game writes one (`namespace:path`), and the namespace is the directory under `assets`. A file is a
 * [ImageSource.LocalFile], dispatched by type rather than guessed out of a string.
 *
 * Nothing here reads on the calling thread: a file's pixels come from [WindowImages] once `localImage`
 * has decoded them, and a resource read is scheduled and answers `null` for its first frame. The
 * second half is about failure: a texture that could not be decoded has to be remembered as failed,
 * or a missing file is looked for again on every frame that draws it.
 *
 * The images are made on the spot in the temporary directory where a test needs a real file, so
 * nothing here depends on a game installation.
 */
class WindowTexturesTest {
    @BeforeTest
    fun startFromProductionWiring() {
        WindowImages.reset()
        // The background read and its publication run inline, so "the next ask" is deterministic.
        WindowImages.executor = Executor { it.run() }
        WindowImages.publish = { it() }
    }

    @AfterTest
    fun restoreProductionWiring() {
        WindowImages.reset()
    }

    private fun imageFile(width: Int, height: Int, name: String = "kotlinmcuiwindow-test.png"): File {
        val file = File(System.getProperty("java.io.tmpdir"), name)
        file.deleteOnExit()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(image, "png", file)
        return file
    }

    private fun holder(source: ImageSource, width: Int = 16, height: Int = 16) = ImageHolder(source, width.px, height.px)

    /** Let `localImage` decode [file] for the renderer, the way a page that draws it would. */
    private fun load(file: File): ImageHolder {
        assertSame(ImageHolder.empty, WindowImages.holder(file), "the first ask only starts the read")
        val holder = WindowImages.holder(file)
        assertFalse(holder.isEmpty, "the inline read should have finished")
        return holder
    }

    @Test
    fun `a namespaced resource is read from the assets directory of its namespace`() {
        // `kotlinmcui:icon.png` is the core library's own icon, which is on this classpath, so this
        // ends in a real decode rather than in the fallback.
        val image = holder(ImageSource.Resource("kotlinmcui:icon.png"))
        assertNull(WindowTextures.get(image), "the first ask only schedules the decode")
        val icon = WindowTextures.get(image)
        assertNotNull(icon)
        assertEquals(16, icon.width)
        assertEquals(16, icon.height)
        WindowTextures.forget(ImageSource.Resource("kotlinmcui:icon.png"))
    }

    @Test
    fun `a local file is read from the file system`() {
        val file = imageFile(4, 3, "kotlinmcuiwindow-local.png")
        val image = WindowTextures.get(load(file))
        assertNotNull(image)
        assertEquals(4, image.width)
        assertEquals(3, image.height)
    }

    @Test
    fun `a decoded image is remembered`() {
        val file = imageFile(2, 2, "kotlinmcuiwindow-remembered.png")
        val source = ImageSource.LocalFile(file)
        val first = WindowTextures.get(load(file))
        assertNotNull(first)

        // The file goes away between the two calls, so getting the same image back is what says it
        // was kept rather than read again.
        file.delete()
        assertSame(first, WindowTextures.get(holder(source, 2, 2)))
    }

    @Test
    fun `a failed source stays failed even after the file appears`() {
        val file = File(System.getProperty("java.io.tmpdir"), "kotlinmcuiwindow-absent.png")
        file.delete()
        val source = ImageSource.LocalFile(file)
        val utils = WindowUtils()
        // The first ask schedules a read that cannot decode; the second sees the remembered failure.
        assertSame(ImageHolder.empty, utils.localImage(file))
        assertSame(ImageHolder.empty, utils.localImage(file))
        assertNull(WindowTextures.get(holder(source, 2, 2)))

        // A source that could not be decoded is remembered as failed, so it must not be looked for
        // again - even now that looking would succeed.
        imageFile(2, 2, file.name)
        assertNull(WindowTextures.get(holder(source, 2, 2)))
    }

    @Test
    fun `forgetting a source makes the next read hit the disk again`() {
        val file = imageFile(3, 2, "kotlinmcuiwindow-forgotten.png")
        val source = ImageSource.LocalFile(file)
        val first = WindowTextures.get(load(file))
        assertNotNull(first)

        WindowTextures.forget(source)
        // The renderer never decodes a file itself: a forgotten source has nothing to upload until
        // `localImage` has read it again.
        assertNull(WindowTextures.get(holder(source, 3, 2)))
        val second = WindowTextures.get(load(file))
        assertNotNull(second)
        assertNotSame(first, second)
    }

    /**
     * The renderer's half of the contract: a texture lookup is an upload, never a read. A file that
     * `localImage` was never asked for has no cache entry, and the renderer must answer `null`
     * instead of decoding it on the frame thread.
     */
    @Test
    fun `the renderer never reads a local file by itself`() {
        val file = imageFile(2, 2, "kotlinmcuiwindow-unasked.png")
        assertNull(WindowTextures.get(holder(ImageSource.LocalFile(file), 2, 2)))
    }

    @Test
    fun `a resource that is nowhere resolves to nothing`() {
        val source = ImageSource.Resource("kotlinmcuiwindow:no-such-texture.png")
        assertNull(WindowTextures.get(holder(source)), "the first ask only schedules the decode")
        assertNull(WindowTextures.get(holder(source)), "a remembered failure is not retried")
    }

    @Test
    fun `a remote source has no pixels here`() {
        // This backend has no http stack, so a url is not fetched and has nothing to draw.
        val source = ImageSource.Remote("https://example.invalid/icon.png")
        assertNull(WindowTextures.get(holder(source)))
    }

    @Test
    fun `the empty sentinel has no image`() {
        assertNull(WindowTextures.get(ImageHolder.empty))
    }
}
