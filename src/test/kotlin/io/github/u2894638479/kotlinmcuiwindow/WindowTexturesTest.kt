package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import io.github.u2894638479.kotlinmcui.math.px
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * How an image source turns into pixels, and what happens when it does not.
 *
 * The naming rule of a resource is the framework's, not this backend's: it is written the way the
 * game writes one (`namespace:path`), and the namespace is the directory under `assets`. A file is a
 * [ImageSource.LocalFile], dispatched by type rather than guessed out of a string. The second half is
 * about failure: a texture that could not be decoded has to be remembered as failed, or a missing
 * file is looked for again on every frame that draws it.
 *
 * The images are made on the spot in the temporary directory where a test needs a real file, so
 * nothing here depends on a game installation.
 */
class WindowTexturesTest {
    private fun imageFile(width: Int, height: Int, name: String = "kotlinmcuiwindow-test.png"): File {
        val file = File(System.getProperty("java.io.tmpdir"), name)
        file.deleteOnExit()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(image, "png", file)
        return file
    }

    private fun holder(source: ImageSource, width: Int = 16, height: Int = 16) = ImageHolder(source, width.px, height.px)

    @Test
    fun `a namespaced resource is read from the assets directory of its namespace`() {
        // `kotlinmcui:icon.png` is the core library's own icon, which is on this classpath, so this
        // ends in a real decode rather than in the fallback.
        val icon = WindowTextures.get(holder(ImageSource.Resource("kotlinmcui:icon.png")))
        assertNotNull(icon)
        assertEquals(16, icon.width)
        assertEquals(16, icon.height)
    }

    @Test
    fun `a local file is read from the file system`() {
        val file = imageFile(4, 3, "kotlinmcuiwindow-local.png")
        val image = WindowTextures.get(holder(ImageSource.LocalFile(file), 4, 3))
        assertNotNull(image)
        assertEquals(4, image.width)
        assertEquals(3, image.height)
    }

    @Test
    fun `a decoded image is remembered`() {
        val file = imageFile(2, 2, "kotlinmcuiwindow-remembered.png")
        val source = ImageSource.LocalFile(file)
        val first = WindowTextures.get(holder(source, 2, 2))
        assertNotNull(first)

        // The file goes away between the two calls: a second decode would fail, so getting the same
        // image back is what says it was kept.
        file.delete()
        assertSame(first, WindowTextures.get(holder(source, 2, 2)))
    }

    @Test
    fun `a failed source stays failed even after the file appears`() {
        val file = File(System.getProperty("java.io.tmpdir"), "kotlinmcuiwindow-absent.png")
        file.delete()
        val source = ImageSource.LocalFile(file)
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
        val first = WindowTextures.get(holder(source, 3, 2))
        assertNotNull(first)

        WindowTextures.forget(source)
        val second = WindowTextures.get(holder(source, 3, 2))
        assertNotNull(second)
        assertNotSame(first, second)
    }

    @Test
    fun `a resource that is nowhere resolves to nothing`() {
        val source = ImageSource.Resource("kotlinmcuiwindow:no-such-texture.png")
        assertNull(WindowTextures.get(holder(source)))
        assertNull(WindowTextures.get(holder(source)))
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
