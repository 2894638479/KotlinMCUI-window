package io.github.u2894638479.kotlinmcuiwindow

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.outputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Where an item's icon comes from. The game is not running here, so the fixtures are the two places
 * the real lookup reads: the version's client jar and the jars in `mods/`.
 *
 * The read is a background job, so a case asks twice: the first ask schedules it, the second finds
 * the icon. The executor and its publication are written inline here to keep that pair deterministic.
 */
class WindowItemsTest {

    private val root = createTempDirectory("window-items")

    @BeforeTest
    fun inlineLoads() {
        WindowImages.executor = Executor { it.run() }
        WindowImages.publish = { it() }
    }

    @AfterTest
    fun tearDown() {
        WindowItems.use(null, null)
        WindowImages.reset()
        root.toFile().deleteRecursively()
    }

    /** The first ask only starts the read; the inline executor has finished it by the second ask. */
    private fun load(item: String): BufferedImage? {
        assertNull(WindowItems.get(item), "the first ask must not wait for the icon")
        return WindowItems.get(item)
    }

    private fun writeJar(path: Path, entries: Map<String, BufferedImage>) {
        path.parent?.createDirectories()
        ZipOutputStream(path.outputStream()).use { zip ->
            for ((name, image) in entries) {
                val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun icon(): BufferedImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply {
        for (x in 0 until 16) for (y in 0 until 16) setRGB(x, y, 0xFFFF0000.toInt())
    }

    @Test
    fun `an item comes out of the version's client jar`() {
        writeJar(root.resolve("versions/1.20.1/1.20.1.jar"), mapOf("assets/minecraft/textures/item/diamond.png" to icon()))
        WindowItems.use(root, "1.20.1")

        val found = load("minecraft:diamond")

        assertNotNull(found, "the icon should come out of the client jar")
        assertEquals(16, found.width)
    }

    @Test
    fun `an item comes out of a mod jar`() {
        writeJar(root.resolve("mods/example.jar"), mapOf("assets/example/textures/item/widget.png" to icon()))
        WindowItems.use(root, "1.20.1")

        assertNotNull(load("example:widget"), "a mod's own item should come out of its jar")
    }

    @Test
    fun `an item nothing has is a miss`() {
        WindowItems.use(root, "1.20.1")

        assertNull(load("minecraft:no_such_item"))
    }

    @Test
    fun `without a game there is nothing to read`() {
        WindowItems.use(null, null)

        assertNull(load("minecraft:stone"), "the standalone run has no jars to read")
    }
}
