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
 * An id is read as a local file path when it names one, and as a classpath resource otherwise. The
 * framework spells images the way the game does, so `kotlinmcui:icon.png` is the core jar's
 * `assets/kotlinmcui/icon.png`: the namespace is the directory under `assets`. Which jars are on
 * the classpath decides what can be found - the game puts every mod there, a standalone run only
 * has the core library - and a texture that lives in the game's own jar cannot be found at all,
 * which is why a vanilla texture still falls back to the quad's own colours.
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
        if (file.isFile) return@runCatching ImageIO.read(file)
        paths(id).firstNotNullOfOrNull { path ->
            javaClass.getResourceAsStream(path)?.use { ImageIO.read(it) }
        }
    }.getOrNull()

    /** Where [id] could be inside a jar, most specific first. */
    private fun paths(id: String): List<String> {
        val path = id.trimStart('/')
        val separator = path.indexOf(':')
        if (separator <= 0) return listOf("/$path")
        val namespace = path.substring(0, separator)
        val name = path.substring(separator + 1)
        return listOf("/assets/$namespace/$name", "/$path")
    }
}
