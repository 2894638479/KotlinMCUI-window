package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The backend's texture store: a decoded [BufferedImage] per [ImageSource].
 *
 * A desktop backend has no GPU upload step, so there is nothing to release: a decoded source is kept
 * and handed straight to Java2D. What this store must not do is read on the thread that paints. A
 * local file's pixels are decoded by [WindowImages] before [get] can see them, and a resource is
 * decoded on the same background thread; [get] itself only answers with what is already decoded, or
 * starts the read and answers `null` for this frame. Sources that do not decode are remembered as
 * failures so a missing file does not hit the disk once per frame.
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

    /** Sources whose read finished without pixels; kept so they are not read again every frame. */
    private val failed = HashSet<ImageSource>()

    /** Sources whose read is on the background thread, so it is scheduled only once. */
    private val loading = HashSet<ImageSource>()

    /**
     * The image for [image], or `null` when it has no source or is not decoded yet.
     *
     * On the frame thread this is an upload and nothing more: a local file's pixels were decoded
     * when `localImage` was asked for it, and a resource read is started here but finishes off this
     * thread. The next frame that asks again draws it.
     */
    fun get(image: ImageHolder): BufferedImage? {
        val source = image.source ?: return null
        return when (source) {
            is ImageSource.LocalFile -> WindowImages.pixels(source.file)
            is ImageSource.Resource -> resource(source)
            is ImageSource.Remote -> null
        }
    }

    /**
     * Drop what is remembered about [source], so the next ask reads it again.
     *
     * A local file has one entry in [WindowImages] holding both the holder and the pixels; dropping
     * that entry is what makes the next `localImage` read the file. Failures are dropped too:
     * "reload" is the caller saying the file or url changed, which is the one case where a
     * remembered failure would be stale rather than a saving.
     */
    fun forget(source: ImageSource) {
        when (source) {
            is ImageSource.LocalFile -> WindowImages.forget(source.file)
            is ImageSource.Resource, is ImageSource.Remote -> {
                loaded.remove(source)
                failed.remove(source)
                loading.remove(source)
            }
        }
    }

    /** A classpath resource, decoded off the frame thread; the first ask only schedules the read. */
    private fun resource(source: ImageSource.Resource): BufferedImage? {
        loaded[source]?.let { return it }
        if (failed.contains(source) || !loading.add(source)) return null
        WindowImages.loadLater(
            read = { decodeResource(source.location) },
            published = { texture ->
                loading.remove(source)
                if (texture == null) failed += source else loaded[source] = texture
            },
        )
        return null
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
