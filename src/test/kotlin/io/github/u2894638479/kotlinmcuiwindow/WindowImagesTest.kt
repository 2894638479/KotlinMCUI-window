package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * How long a decoded local image stays around.
 *
 * The cache is swept once per frame, and an entry that no frame asked for during
 * [WindowImages.TTL_MILLIS] is dropped. The cases use the injected clock rather than a sleep: "a
 * minute passed" is a number assigned to [WindowImages.clock], not wall time the test waits for.
 */
class WindowImagesTest {
    /** Arbitrary start, so an accidental wall-clock read stands out instead of looking plausible. */
    private var now = 1_000_000L

    @BeforeTest
    fun startFromKnownClock() {
        WindowImages.reset()
        WindowImages.clock = { now }
        WindowImages.executor = Executor { it.run() }
        WindowImages.publish = { it() }
    }

    @AfterTest
    fun restoreProductionWiring() {
        WindowImages.reset()
    }

    private fun imageFile(width: Int, height: Int, name: String): File {
        val file = File(System.getProperty("java.io.tmpdir"), name)
        file.deleteOnExit()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file)
        return file
    }

    /** Ask twice, the way two frames do: the first schedules the inline read, the second finds it. */
    private fun loaded(file: File) {
        assertSame(ImageHolder.empty, WindowImages.holder(file))
        val holder = WindowImages.holder(file)
        assertFalse(holder.isEmpty)
        assertNotNull(WindowImages.pixels(file))
    }

    @Test
    fun `an entry unused past the ttl is swept away`() {
        val file = imageFile(5, 4, "kotlinmcuiwindow-ttl.png")
        loaded(file)

        now += WindowImages.TTL_MILLIS - 1
        WindowImages.sweep()
        assertNotNull(WindowImages.pixels(file), "an entry just inside the ttl is still young enough")

        // Reading the pixels above counts as a use, so the entry is fresh as of t0 + TTL - 1; from
        // there it takes more than the ttl again to become old.
        now += WindowImages.TTL_MILLIS + 1
        WindowImages.sweep()
        assertNull(WindowImages.pixels(file), "an entry past the ttl must be dropped")
    }

    @Test
    fun `using an entry refreshes it, so only the unused one expires`() {
        val old = imageFile(2, 2, "kotlinmcuiwindow-ttl-old.png")
        val fresh = imageFile(3, 3, "kotlinmcuiwindow-ttl-fresh.png")
        loaded(old)
        loaded(fresh)

        now += 40_000
        WindowImages.holder(fresh) // a hit refreshes the entry it hits
        now += 25_000

        WindowImages.sweep()
        assertNull(WindowImages.pixels(old), "the entry nobody asked for should expire")
        assertNotNull(WindowImages.pixels(fresh), "the entry asked for 25 seconds ago should survive")
        assertFalse(WindowImages.holder(fresh).isEmpty, "a surviving entry is still ready to draw")
    }
}
