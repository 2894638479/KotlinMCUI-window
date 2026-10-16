package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.math.px
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * How an image id turns into pixels, and what happens when it does not.
 *
 * The naming rule is the framework's, not this backend's: an id is written the way the game writes
 * one (`namespace:path`), and the namespace is the directory under `assets`. The second half is
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

    @Test
    fun `a namespaced id is read from the assets directory of its namespace`() {
        // `kotlinmcui:icon.png` is the core library's own icon, which is on this classpath, so this
        // ends in a real decode rather than in the fallback.
        val icon = WindowTextures.get(ImageHolder("kotlinmcui:icon.png", 16.px, 16.px))
        assertNotNull(icon)
        assertEquals(16, icon.width)
        assertEquals(16, icon.height)
    }

    @Test
    fun `an id that names a file is read from the file system`() {
        val file = imageFile(4, 3, "kotlinmcuiwindow-local.png")
        val image = WindowTextures.get(ImageHolder(file.path, 4.px, 3.px))
        assertNotNull(image)
        assertEquals(4, image.width)
        assertEquals(3, image.height)
    }

    @Test
    fun `a decoded image is remembered`() {
        val file = imageFile(2, 2, "kotlinmcuiwindow-remembered.png")
        val first = WindowTextures.get(ImageHolder(file.path, 2.px, 2.px))
        assertNotNull(first)

        // The file goes away between the two calls: a second decode would fail, so getting the same
        // image back is what says it was kept.
        file.delete()
        assertSame(first, WindowTextures.get(ImageHolder(file.path, 2.px, 2.px)))
    }

    @Test
    fun `a failed id stays failed even after the file appears`() {
        val file = File(System.getProperty("java.io.tmpdir"), "kotlinmcuiwindow-absent.png")
        file.delete()
        assertNull(WindowTextures.get(ImageHolder(file.path, 2.px, 2.px)))

        // A file that could not be decoded is remembered as failed, so the same id must not be looked
        // for again - even now that looking would succeed.
        imageFile(2, 2, file.name)
        assertNull(WindowTextures.get(ImageHolder(file.path, 2.px, 2.px)))
    }

    @Test
    fun `an id that is nowhere resolves to nothing`() {
        val id = "kotlinmcuiwindow:no-such-texture.png"
        assertNull(WindowTextures.get(ImageHolder(id, 16.px, 16.px)))
        assertNull(WindowTextures.get(ImageHolder(id, 16.px, 16.px)))
    }

    @Test
    fun `the empty sentinel has no image`() {
        assertNull(WindowTextures.get(ImageHolder.empty))
    }
}
