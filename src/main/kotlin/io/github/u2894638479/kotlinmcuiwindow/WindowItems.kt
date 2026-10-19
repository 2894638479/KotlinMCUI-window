package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.dslMetadata
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.streams.asSequence

/**
 * Item icons for a window running next to the game.
 *
 * Nothing here names a Minecraft class. An item icon is a PNG in a jar, so the version's client jar
 * and every jar in `mods/` are read as archives; the directory and version come from the metadata
 * provider, which the backend installed alongside this one publishes. Without a game - the
 * standalone run - nothing is found and the caller keeps its own stand-in.
 *
 * Icons are decoded once and kept, and a miss is remembered, so a page drawing the same item every
 * frame does not touch the disk again. The decode happens on [WindowImages]' background thread for
 * the same reason a local image does: `renderItem` runs on the frame thread, which must not read.
 * The first ask for an item therefore answers `null` and that frame draws the stand-in.
 */
internal object WindowItems {
    private val loaded = HashMap<String, BufferedImage>()
    private val failed = HashSet<String>()

    /** Items whose read is on the background thread, so it is scheduled only once. */
    private val loading = HashSet<String>()

    /** Written on the session thread, and read by the background read when it resolves the jars. */
    @Volatile
    private var jars: List<ZipFile>? = null

    /** The icon for [item] (`namespace:path`), or `null` when it is not decoded yet or has none. */
    fun get(item: String): BufferedImage? {
        if (item.isEmpty()) return null
        loaded[item]?.let { return it }
        if (failed.contains(item) || !loading.add(item)) return null
        WindowImages.loadLater(
            read = { decode(item, gameJars()) },
            published = { image ->
                loading.remove(item)
                if (image == null) failed += item else loaded[item] = image
            },
        )
        return null
    }

    /**
     * Read the icons out of [gameDir] instead of the published metadata.
     *
     * The metadata is what a game run has; this is how a test - or a window started from a
     * directory of its own - says where the jars are.
     */
    fun use(gameDir: Path?, gameVersion: String?) {
        jars?.forEach { runCatching { it.close() } }
        jars = open(gameDir, gameVersion)
    }

    /** The jars to read, opened once from the published metadata when nothing has said otherwise. */
    private fun gameJars(): List<ZipFile> {
        jars?.let { return it }
        runCatching { use(dslMetadata.gameDir, dslMetadata.gameVersion) }
        return jars ?: emptyList()
    }

    private fun decode(item: String, jars: List<ZipFile>): BufferedImage? = runCatching {
        val paths = paths(item)
        for (jar in jars) {
            val entry = paths.firstNotNullOfOrNull { jar.getEntry(it) } ?: continue
            return@runCatching jar.getInputStream(entry).use { ImageIO.read(it) }
        }
        // The core library's own assets are on the classpath, which is all a standalone run has.
        paths.firstNotNullOfOrNull { path -> javaClass.getResourceAsStream("/$path")?.use { ImageIO.read(it) } }
    }.getOrNull()

    /** Where an item's texture sits inside a jar, most likely first. */
    private fun paths(item: String): List<String> {
        val path = item.trimStart('/')
        val separator = path.indexOf(':')
        val namespace = if (separator <= 0) "minecraft" else path.substring(0, separator)
        val name = if (separator <= 0) path else path.substring(separator + 1)
        // Most items are modelled from `textures/item`; a few are still spelled as block textures.
        return listOf("assets/$namespace/textures/item/$name.png", "assets/$namespace/textures/block/$name.png")
    }

    /** One entry per jar the game's assets could be in: the version jar first, then `mods/`. */
    private fun open(gameDir: Path?, gameVersion: String?): List<ZipFile> {
        val opened = ArrayList<ZipFile>()
        if (gameDir == null) return opened
        val version = gameDir.resolve("versions").resolve(gameVersion ?: "").resolve("${gameVersion ?: ""}.jar")
        if (Files.isRegularFile(version)) openJar(version)?.let { opened += it }
        opened += scan(gameDir.resolve("versions"), 2)
        opened += scan(gameDir.resolve("mods"), 1)
        return opened
    }

    private fun scan(root: Path, depth: Int): List<ZipFile> {
        if (!Files.isDirectory(root)) return emptyList()
        return runCatching {
            Files.walk(root, depth).asSequence()
                .filter { Files.isRegularFile(it) && it.extension.equals("jar", ignoreCase = true) }
                .sorted()
                .mapNotNull { openJar(it) }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun openJar(path: Path): ZipFile? = runCatching { ZipFile(path.toFile()) }.getOrNull()
}
