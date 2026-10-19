package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.px
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
import kotlin.test.assertTrue

/**
 * What the process-level helpers answer about images.
 *
 * The interesting half is what they do when there is nothing to read: a file that is not there and a
 * url this backend cannot fetch are both [ImageHolder.empty] rather than exceptions, because the
 * framework asks for images while it renders and a throw there would end the frame. Reading a local
 * file must also not happen on that thread: the first ask answers empty and the read finishes in the
 * background, so the case that matters is the pair "empty now, holder later".
 *
 * Reloading is the other half: it has to drop the holder and the decoded pixels together, or the
 * next read would answer with what was there before the file changed.
 */
class WindowUtilsTest {
    @BeforeTest
    fun startFromProductionWiring() {
        WindowImages.reset()
    }

    @AfterTest
    fun restoreProductionWiring() {
        WindowImages.reset()
    }

    private fun imageFile(width: Int, height: Int, name: String): File {
        val file = File(System.getProperty("java.io.tmpdir"), name)
        file.deleteOnExit()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(image, "png", file)
        return file
    }

    /** Run the background read and its publication inline, so the sequence is deterministic. */
    private fun inlineLoads() {
        WindowImages.executor = Executor { it.run() }
        WindowImages.publish = { it() }
    }

    /**
     * The first ask starts the read and answers empty; with the inline executor the next ask finds
     * it finished, which is what a running window does one frame later.
     */
    private fun loaded(utils: WindowUtils, file: File): ImageHolder {
        assertSame(
            ImageHolder.empty,
            utils.localImage(file),
            "the first ask must not wait for the file",
        )
        return utils.localImage(file)
    }

    @Test
    fun `a local image carries its file as the source and its own size`() {
        inlineLoads()
        val file = imageFile(5, 4, "kotlinmcuiwindow-utils-size.png")
        val holder = loaded(WindowUtils(), file)

        assertFalse(holder.isEmpty)
        assertEquals(ImageSource.LocalFile(file), holder.source)
        assertEquals(5.0, holder.width.raw, 0.0)
        assertEquals(4.0, holder.height.raw, 0.0)
    }

    /**
     * A real background read, held open: the call that schedules it must come back while the file
     * is still being read. The assertion is deliberately generous - the point is that the wait is
     * absent, not that the scheduler is fast.
     */
    @Test
    fun `localImage answers empty before a slow read finishes`() {
        val file = imageFile(2, 2, "kotlinmcuiwindow-utils-slow.png")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        WindowImages.decode = { source ->
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "the test never released the read" }
            ImageIO.read(source)
        }
        val published = LinkedBlockingQueue<() -> Unit>()
        WindowImages.publish = { published.put(it) }

        val utils = WindowUtils()
        val began = System.nanoTime()
        val first = utils.localImage(file)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)

        assertSame(ImageHolder.empty, first, "a read in flight has nothing to draw yet")
        assertTrue(elapsedMs < 1_000, "localImage waited ${elapsedMs}ms for a read it must not wait for")
        assertTrue(started.await(5, TimeUnit.SECONDS), "the background read never started")

        release.countDown()
        val publication = published.poll(5, TimeUnit.SECONDS)
        assertNotNull(publication, "the finished read was never published")
        publication.invoke()

        val holder = utils.localImage(file)
        assertFalse(holder.isEmpty, "the published read must be visible on the next ask")
        assertEquals(ImageSource.LocalFile(file), holder.source)
        assertEquals(2.0, holder.width.raw, 0.0)
        assertEquals(2.0, holder.height.raw, 0.0)
    }

    @Test
    fun `a missing file is the empty holder`() {
        val absent = File(System.getProperty("java.io.tmpdir"), "kotlinmcuiwindow-utils-absent.png")
        absent.delete()

        assertSame(ImageHolder.empty, WindowUtils().localImage(absent))
        assertSame(ImageHolder.empty, WindowUtils().localImage(absent))
    }

    @Test
    fun `a url has no pixels rather than an exception`() {
        assertSame(ImageHolder.empty, WindowUtils().remoteImage("https://example.invalid/icon.png"))
    }

    @Test
    fun `reloading a local image drops the holder and the decoded texture`() {
        inlineLoads()
        val file = imageFile(2, 2, "kotlinmcuiwindow-utils-reload.png")
        val utils = WindowUtils()
        val first = loaded(utils, file)
        val firstTexture = WindowTextures.get(first)
        assertNotNull(firstTexture)

        utils.reloadLocalImage(file)

        // The reload only drops the entry; it starts no read of its own, so the ask after it is the
        // one that schedules the fresh read.
        assertSame(ImageHolder.empty, utils.localImage(file))
        val second = utils.localImage(file)
        assertNotSame(first, second)
        assertNotNull(WindowTextures.get(second))
        assertNotSame(firstTexture, WindowTextures.get(second))
    }

    @Test
    fun `reloading a url drops what was remembered about it`() {
        val source = ImageSource.Remote("https://example.invalid/reloaded.png")
        val image = ImageHolder(source, 16.px, 16.px)
        // A remote source decodes to nothing, and that failure is remembered; reloading it is
        // allowed and simply clears the record.
        assertNull(WindowTextures.get(image))
        WindowUtils().reloadRemoteImage(source.url)
        assertNull(WindowTextures.get(image))
    }
}
