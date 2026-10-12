package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslBackendMetadata
import io.github.u2894638479.kotlinmcui.backend.Environment
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What this backend can say about the process it runs in.
 *
 * A window backend has no game to ask, so the working directory is the installation directory and
 * the game version is whatever the host put in [GAME_VERSION_PROPERTY], which is empty when nothing
 * is hosting the jar. The loader is read off the classpath rather than reported by the entry point:
 * the three entry classes stay one line each, and the answer stays correct when two loaders are
 * present at once, which a development client does.
 */
internal object WindowMetadata : DslBackendMetadata {
    const val GAME_VERSION_PROPERTY = "kotlinmcuiwindow.gameVersion"

    private const val STANDALONE = "window"

    override val gameDir: Path get() = Paths.get(System.getProperty("user.dir") ?: ".")

    override val configDir: Path get() = gameDir.resolve("config")

    override val gameVersion: String get() = System.getProperty(GAME_VERSION_PROPERTY).orEmpty().ifEmpty { STANDALONE }

    override val gameLoader: String get() = detectedLoader

    /** A window exists only on the client, so this backend never reports anything else. */
    override val environment: Environment get() = Environment.CLIENT

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
