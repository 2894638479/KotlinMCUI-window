package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The backend's texture store: a decoded [BufferedImage] per [ImageSource].
 *
 * A desktop backend has no GPU upload step, so there is nothing to keep off the calling thread and
 * nothing to release: a source is decoded once, kept, and handed straight to Java2D. Sources that do
 * not decode are remembered as failures so a missing file does not hit the disk once per frame.
 *
 * The three sources are dispatched by type, not parsed out of one string: a [ImageSource.Resource] is
 * a classpath location written the way the game writes one - `kotlinmcui:icon.png` is the core jar's
 * `assets/kotlinmcui/icon.png` - and a [ImageSource.LocalFile] is a file. Which jars are on the
 * classpath decides what a resource can find (the game puts every mod there, a standalone run only
 * has the core library), and a texture that lives in the game's own jar cannot be found at all, which
 * is why a vanilla texture still falls back to the quad's own colours.
 *
 * A [ImageSource.Remote] has no pixels here: this backend has no http stack, so it answers `null`
 * instead of fetching. The platform utils hand out [ImageHolder.empty] for a url for the same reason.
 */
internal object WindowTextures {
    private val loaded = HashMap<ImageSource, BufferedImage>()
    private val failed = HashSet<ImageSource>()

    /** The image for [image], or `null` when it has no source or cannot be decoded. */
    fun get(image: ImageHolder): BufferedImage? {
        val source = image.source ?: return null
        loaded[source]?.let { return it }
        if (failed.contains(source)) return null
        val texture = decode(source)
        if (texture == null) {
            failed += source
            return null
        }
        loaded[source] = texture
        return texture
    }

    /**
     * Drop what is remembered about [source], so the next [get] reads it again.
     *
     * A failure is dropped too: "reload" is the caller saying the file or url changed, which is the
     * one case where a remembered failure would be stale rather than a saving.
     */
    fun forget(source: ImageSource) {
        loaded.remove(source)
        failed.remove(source)
    }

    private fun decode(source: ImageSource): BufferedImage? = when (source) {
        is ImageSource.Resource -> decodeResource(source.location)
        is ImageSource.LocalFile -> runCatching { ImageIO.read(source.file) }.getOrNull()
        is ImageSource.Remote -> null
    }

    private fun decodeResource(location: String): BufferedImage? = runCatching {
        paths(location).firstNotNullOfOrNull { path ->
            javaClass.getResourceAsStream(path)?.use { ImageIO.read(it) }
        }
    }.getOrNull()

    /** Where [location] could be inside a jar, most specific first. */
    private fun paths(location: String): List<String> {
        val path = location.trimStart('/')
        val separator = path.indexOf(':')
        if (separator <= 0) return listOf("/$path")
        val namespace = path.substring(0, separator)
        val name = path.substring(separator + 1)
        return listOf("/assets/$namespace/$name", "/$path")
    }
}
