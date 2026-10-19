package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.px
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * What the process-level helpers answer about images.
 *
 * The interesting half is what they do when there is nothing to read: a file that is not there and a
 * url this backend cannot fetch are both [ImageHolder.empty] rather than exceptions, because the
 * framework asks for images while it renders and a throw there would end the frame. Reloading is the
 * other half: it has to drop what the holder cache and the renderer's texture cache remember, or the
 * next read would answer with what was there before the file changed.
 */
class WindowUtilsTest {
    private fun imageFile(width: Int, height: Int, name: String): File {
        val file = File(System.getProperty("java.io.tmpdir"), name)
        file.deleteOnExit()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(image, "png", file)
        return file
    }

    @Test
    fun `a local image carries its file as the source and its own size`() {
        val file = imageFile(5, 4, "kotlinmcuiwindow-utils-size.png")
        val holder = WindowUtils().localImage(file)

        assertFalse(holder.isEmpty)
        assertEquals(ImageSource.LocalFile(file), holder.source)
        assertEquals(5.0, holder.width.raw, 0.0)
        assertEquals(4.0, holder.height.raw, 0.0)
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
        val file = imageFile(2, 2, "kotlinmcuiwindow-utils-reload.png")
        val utils = WindowUtils()
        val first = utils.localImage(file)
        val firstTexture = WindowTextures.get(first)
        assertNotNull(firstTexture)

        utils.reloadLocalImage(file)

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
