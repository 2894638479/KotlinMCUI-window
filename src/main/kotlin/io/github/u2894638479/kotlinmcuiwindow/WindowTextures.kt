package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The backend's texture store: a decoded [BufferedImage] per [ImageHolder.id].
 *
 * A desktop backend has no GPU upload step, so there is nothing to keep off the calling thread and
 * nothing to release: an id is decoded once, kept, and handed straight to Java2D. Ids that do not
 * decode are remembered as failures so a missing file does not hit the disk once per frame.
 *
 * An id is read as a local file path when it names one, and as a classpath resource otherwise.
 */
internal object WindowTextures {
    private val loaded = HashMap<String, BufferedImage>()
    private val failed = HashSet<String>()

    /** The image for [image], or `null` when it is the empty sentinel or cannot be decoded. */
    fun get(image: ImageHolder): BufferedImage? {
        val id = image.id
        if (id.isEmpty()) return null
        loaded[id]?.let { return it }
        if (failed.contains(id)) return null
        val image = decode(id)
        if (image == null) {
            failed += id
            return null
        }
        loaded[id] = image
        return image
    }

    private fun decode(id: String): BufferedImage? = runCatching {
        val file = File(id)
        val stream = if (file.isFile) file.inputStream()
        else javaClass.getResourceAsStream("/" + id.trimStart('/'))
        stream?.use { ImageIO.read(it) }
    }.getOrNull()
}
