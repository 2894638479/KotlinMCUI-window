package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslPlatformMetadata
import io.github.u2894638479.kotlinmcui.backend.Environment
import java.nio.file.Path
import java.nio.file.Paths

/** The mod id every descriptor in this jar declares; it is the only name a loader is given. */
internal const val MOD_ID = "kotlinmcuiwindow"

/**
 * What this backend can say about the process it runs in.
 *
 * A window backend has no game to ask, so the working directory is the installation directory and the
 * game version is whatever the host put in [GAME_VERSION_PROPERTY], which is empty when nothing is
 * hosting the jar.
 */
internal object WindowMetadata : DslPlatformMetadata {
    const val GAME_VERSION_PROPERTY = "kotlinmcuiwindow.gameVersion"

    private const val STANDALONE = "window"

    override val gameDir: Path get() = Paths.get(System.getProperty("user.dir") ?: ".")

    override val configDir: Path get() = gameDir.resolve("config")

    override val gameVersion: String get() = System.getProperty(GAME_VERSION_PROPERTY).orEmpty().ifEmpty { STANDALONE }

    /**
     * Empty, because a standalone process has no loader hosting it.
     *
     * In a game the host backend installs its own metadata and reports the loader there - a backend
     * that knows which loader it is running under does not have to guess. A classpath probe here
     * would answer a different question ("is a loader visible from this classpath"), and the one case
     * where it could hit is the standalone main running next to a loader, where there is no game at
     * all.
     */
    override val gameLoader: String get() = ""

    /**
     * This metadata describes the standalone process, which has no game side at all. In a game the
     * headless backend installs its own metadata before any client entry runs, so this object is
     * never asked about a game - and reporting CLIENT here made the window believe it was in one and
     * warn that another backend owned the platform.
     */
    override val environment: Environment get() = Environment.NONE
}
