package io.github.u2894638479.kotlinmcuiwindow

import io.github.u2894638479.kotlinmcui.backend.DslPreview
import io.github.u2894638479.kotlinmcui.backend.DslRootScreenFactory
import io.github.u2894638479.kotlinmcui.backend.DslRuntime
import io.github.u2894638479.kotlinmcui.backend.Environment
import io.github.u2894638479.kotlinmcui.backend.dslMetadata
import io.github.u2894638479.kotlinmcui.backend.dslUtils
import io.github.u2894638479.kotlinmcui.container.DslRoot
import io.github.u2894638479.kotlinmcui.dsl.DslFunction
import io.github.u2894638479.kotlinmcui.entry.DslEntryPage
import io.github.u2894638479.kotlinmcui.logger.dslLogger

/** What [startWindow] hands back: the process helpers and the root this window renders. */
internal class WindowSession(
    val utils: WindowUtils,
    val root: DslRoot,
    /** Whether this call published the process utils; `false` means another backend owns them. */
    val published: Boolean,
)

/**
 * Build this backend's root and window, and open it.
 *
 * `install` is not called here: in a game the headless backend installs the metadata before any
 * client entry runs, and the standalone path installs it itself before calling this. Everything else
 * is this backend's own - the utils, the root, the window and the frame loop.
 *
 * The root is built on the very instance that is published to the process, so `dslUtils` and the
 * window answer from the same helpers. The standalone main publishes before the launch stage, so
 * this reuses the instance it already published rather than building a second one that would never
 * get its frame wired.
 *
 * Not owning the process slot is not a failure: [DslRuntime.start] returns `false` when another
 * backend published first, and this window still renders the root built below with its own renderer.
 */
internal fun startWindow(publish: Boolean = true): WindowSession {
    val utils = dslUtils as? WindowUtils ?: WindowUtils()
    val published = if (publish) DslRuntime.start { utils } else true

    val input = WindowInput()
    val renderer = WindowRenderer()
    val window = WindowFrame(renderer, input)
    val screenFactory = DslRootScreenFactory<WindowScreen> { title, dslFunction ->
        window.create(utils, title, dslFunction)
    }
    // The root is this backend's own: rendering, input, screens and the per-output helpers all come
    // from it, so another backend's root is never touched.
    val root = DslRoot(input, renderer, screenFactory, utils)
    window.attach(root)
    input.root = root
    utils.frame = window

    window.open(screenFactory.create(ROOT_TITLE) { DslEntryPage() }.screen)

    // The runtime no longer starts a preview session when a platform is published; it has to be
    // handed this root. Unconditional is right: it does nothing unless -Dkotlinmcui.preview asked
    // for a page, and the preview draws into the root it is given.
    DslPreview.start(root)
    val session = WindowSession(utils, root, published)
    // One window per process while the framework has no enumerable roots; the standalone harness
    // reads it after the launch stage, because opening the window belongs to the entry now.
    windowSession = session
    return session
}

/**
 * The entry's launch stage: build this backend's window, and say so when the process slot belongs to
 * someone else.
 *
 * In `Environment.STANDALONE` the standalone main published the utils itself - the only other publisher
 * in that process would be a second copy of this backend - so a refusal there is not news and must not
 * be warned about. In a game the game's backend is the expected owner, and the warning is the point.
 */
internal fun enterWindow() {
    val standalone = dslMetadata.environment == Environment.STANDALONE
    val session = startWindow(publish = !standalone)
    if (!standalone && !session.published) {
        dslLogger.warn("window: another platform owns this process; this window renders its own root")
    }
}

/** The one window this process has. `null` until the launch stage ran. */
internal var windowSession: WindowSession? = null

private const val ROOT_TITLE = "KotlinMCUI"
