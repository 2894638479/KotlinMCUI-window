package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslPlatformMetadata
import io.github.u2894638479.kotlinmcui.backend.Environment
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What this backend can say about the process it runs in.
 *
 * A window backend has no game to ask, so the working directory is the installation directory and
 * the game version is whatever the host put in [GAME_VERSION_PROPERTY], which is empty when nothing
 * is hosting the jar. The loader is read off the classpath rather than reported by a descriptor: the
 * answer stays correct when two loaders are present at once, which a development client does.
 */
/** The mod id every descriptor in this jar declares; it is the only name a loader is given. */
internal const val MOD_ID = "kotlinmcuiwindow"

internal object WindowMetadata : DslPlatformMetadata {
    const val GAME_VERSION_PROPERTY = "kotlinmcuiwindow.gameVersion"

    private const val STANDALONE = "window"

    override val gameDir: Path get() = Paths.get(System.getProperty("user.dir") ?: ".")

    override val configDir: Path get() = gameDir.resolve("config")

    override val gameVersion: String get() = System.getProperty(GAME_VERSION_PROPERTY).orEmpty().ifEmpty { STANDALONE }

    override val gameLoader: String get() = detectedLoader

    /**
     * This metadata describes the standalone process, which has no game side at all. In a game the
     * headless backend installs its own metadata before any client entry runs, so this object is
     * never asked about a game - and reporting CLIENT here made the window believe it was in one and
     * warn that another backend owned the platform.
     */
    override val environment: Environment get() = Environment.NONE

    private val detectedLoader: String by lazy {
        when {
            present("net.fabricmc.loader.api.FabricLoader") -> "fabric"
            present("net.neoforged.fml.ModList") -> "neoforge"
            present("net.minecraftforge.fml.ModList") -> "forge"
            else -> STANDALONE
        }
    }

    private fun present(className: String) =
        runCatching { Class.forName(className, false, javaClass.classLoader) }.isSuccess
}
