package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.image.ImageHolder
import io.github.u2894638479.kotlinmcui.image.ImageSource
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The backend's texture store: a decoded [BufferedImage] per [ImageSource].
 *
 * A desktop backend has no GPU upload step, so there is nothing to release: a decoded source is kept
 * and handed straight to Java2D. What this store must not do is read on the thread that paints. A
 * local file's pixels are decoded by [WindowImages] before [ready] can see them, and a resource is
 * decoded on the same background thread; [ready] itself only answers with what is already decoded, or
 * starts the read and answers `null` for this frame. Sources that do not decode are remembered as
 * failures so a missing file does not hit the disk once per frame.
 *
 * The three sources are dispatched by type, not parsed out of one string: a [ImageSource.Resource] is
 * a classpath location written the way the game writes one - `kotlinmcui:icon.png` is the core jar's
 * `assets/kotlinmcui/icon.png` - and a [ImageSource.LocalFile] is a file. Which jars are on the
 * classpath decides what a resource can find (the game puts every mod there, a standalone run only
 * has the core library), and a texture that lives in the game's own jar cannot be found at all.
 * A pending resource draws nothing; a source this backend can confirm it cannot use is answered with
 * the one shared checkerboard instead. That is a texture like any other, so a failed draw keeps its
 * geometry, UV and tint and never turns into an untextured or flat-coloured one.
 *
 * An [ImageSource.Remote] has no pixels here: this backend has no http stack, so it is a confirmed
 * unsupported source rather than something still loading. The platform utils hand out
 * [ImageHolder.empty] for a url for the same reason.
 *
 * Readiness is two primitive answers rather than a wrapper object: [ready] is the cached image (or
 * `null`) and [isMissing] is the failure knowledge for that same holder. A warm draw therefore
 * allocates nothing to find out what to sample, and the image itself is cached once per source.
 */
internal object WindowTextures {
    private val loaded = HashMap<ImageSource, BufferedImage>()

    /** Sources whose read finished without pixels; kept so they are not read again every frame. */
    private val failed = HashSet<ImageSource>()

    /** Sources whose read is on the background thread, so it is scheduled only once. */
    private val loading = HashSet<ImageSource>()

    /**
     * The decoded image for [image], or `null` when there is nothing to sample yet - no source, a
     * read in flight, or a source already confirmed unusable. [isMissing] tells the last apart from
     * the first two.
     *
     * On the frame thread this is an upload and nothing more: a local file's pixels were decoded
     * when `localImage` was asked for it, and a resource read is started here but finishes off this
     * thread. The next frame that asks again gets the cached image.
     *
     * Call [isMissing] first when a read this call starts must stay pending for this frame: an
     * inline test executor can finish the read, and record its failure, before this call returns.
     */
    fun ready(image: ImageHolder): BufferedImage? {
        val source = image.source ?: return null
        return when (source) {
            is ImageSource.LocalFile -> WindowImages.pixels(source.file)
            is ImageSource.Resource -> resource(source)
            is ImageSource.Remote -> null
        }
    }

    /**
     * The decoded image for [image], kept as the old upload query's spelling.
     *
     * The renderer uses [ready] and [isMissing] together; this is the same cached image with no
     * wrapper, so callers that only need pixels do not pay for readiness classification.
     */
    fun get(image: ImageHolder): BufferedImage? = ready(image)

    /**
     * Whether [image] is a source this backend has confirmed it cannot use: a malformed identifier,
     * a missing resource, an unsupported url, or a read that finished without pixels.
     *
     * A source still loading, a file nobody has asked for and an empty holder are *not* failures;
     * they are pending, and the difference is what keeps "nothing yet" from being painted as the
     * missing texture. This is internal: readiness is not a public state, return value or callback
     * of the drawing contract.
     */
    fun isMissing(image: ImageHolder): Boolean {
        val source = image.source ?: return false
        return when (source) {
            is ImageSource.LocalFile -> WindowImages.failed(source.file)
            is ImageSource.Resource -> failed.contains(source) || !wellFormed(source.location)
            is ImageSource.Remote -> true
        }
    }

    /**
     * The shared checkerboard every failed or unsupported source is drawn with.
     *
     * One image, made once: a frame that draws many broken textures must not allocate a placeholder
     * per draw. A coloured tint may multiply it into a single reusable copy, but that copy is bounded
     * by the renderer's one tint entry, not made per draw, and UV scaling against a holder's nominal
     * size is plain arithmetic at the draw site.
     */
    val placeholder: BufferedImage get() = checkerboard

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
        if (failed.contains(source)) return null
        // A malformed identifier is data, not a caller error: it is a confirmed missing resource
        // without a read, so the missing texture appears immediately instead of after a failed io.
        if (!wellFormed(source.location)) {
            failed += source
            return null
        }
        if (!loading.add(source)) return null
        WindowImages.loadLater(
            read = { decodeResource(source.location) },
            published = { texture ->
                loading.remove(source)
                if (texture == null) failed += source else loaded[source] = texture
            },
        )
        return null
    }

    /** Whether [location] is at least shaped like `namespace:path` or a plain path. */
    private fun wellFormed(location: String): Boolean {
        val path = location.trim()
        if (path.isEmpty() || path.endsWith('/')) return false
        val separator = path.indexOf(':')
        if (separator < 0) return true
        return separator > 0 && separator < path.length - 1
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

    private val checkerboard: BufferedImage by lazy {
        val size = CHECKERBOARD_SIZE
        val cell = size / 2
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val magenta = ((x / cell) + (y / cell)) % 2 == 0
                    setRGB(x, y, if (magenta) CHECKERBOARD_MAGENTA else CHECKERBOARD_BLACK)
                }
            }
        }
    }

    private const val CHECKERBOARD_SIZE = 16

    private const val CHECKERBOARD_MAGENTA = 0xFFFF00FF.toInt()

    private const val CHECKERBOARD_BLACK = 0xFF000000.toInt()
}
